package net.primal.data.repository.feed

import kotlin.time.Clock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.data.local.dao.events.EventStats
import net.primal.data.remote.api.feed.model.FeedResponse
import net.primal.data.repository.cache.LocalEventCache
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.data.repository.fetch.RelaysUnreachableException
import net.primal.domain.common.ContentPrimalPaging
import net.primal.domain.common.PrimalEvent
import net.primal.domain.feeds.extractFollowSetDTag
import net.primal.domain.feeds.extractFollowSetPubkey
import net.primal.domain.feeds.extractPubkeyFromFeedSpec
import net.primal.domain.feeds.isFollowSetFeedSpec
import net.primal.domain.feeds.isNetworkNotesFeedSpec
import net.primal.domain.feeds.isNotesBookmarkFeedSpec
import net.primal.domain.feeds.isProfileAuthoredMediaFeedSpec
import net.primal.domain.feeds.isProfileAuthoredNoteRepliesFeedSpec
import net.primal.domain.feeds.isProfileAuthoredNotesFeedSpec
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.eventIdTagValues
import net.primal.domain.nostr.findFirstIdentifier
import net.primal.domain.nostr.hasEventIdTag
import net.primal.domain.nostr.isComment
import net.primal.domain.nostr.pubkeyTagValues
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.nostr.relay.RelayQueryOutcome

/** The flat event set [RelayNotesFeedFetcher.fetchPage] resolves for one feed page. */
internal data class NotesFeedPage(
    val page: List<NostrEvent>,
    val referencedNotes: List<NostrEvent>,
    /**
     * True when the page is empty because no relay answered at all, which says nothing about
     * whether older notes exist. A page that is empty because the relays had nothing more to give
     * is not unreachable.
     */
    val unreachable: Boolean = false,
    /**
     * Only for an empty page: the timestamp the next, older page should start from. The relays
     * did answer, but everything they returned was filtered out (replies on a feed that hides
     * them, say), so there is nothing on the page to derive a cursor from.
     */
    val resumeUntil: Long? = null,
    /**
     * Counters and the user's own interaction flags for [page], when the fetcher already had to compute them to
     * decide the order of the page (the network feed). The caller stores them instead of asking the relays again.
     */
    val stats: RelayEventStatsSnapshot? = null,
)

/** The network feed's notes, resolved once and kept for a while: see [RelayNotesFeedFetcher.fetchNetworkNotesPage]. */
private class NetworkNotes(
    val userId: String,
    val loadedAt: Long,
    /** Best first: most interactions, then most friends behind it, then newest. */
    val ranked: List<NostrEvent> = emptyList(),
    /** Counters and the user's own flags for [ranked]; the order was decided from them, so they are kept. */
    val stats: RelayEventStatsSnapshot = RelayEventStatsSnapshot(emptyList(), emptyList()),
    /** True when nothing came back although the user follows people, i.e. the relays did not answer. */
    val unreachable: Boolean = false,
)

internal class RelayNotesFeedFetcher(
    private val querier: RelayEventQuerier,
    private val coordinator: FetchCoordinator,
    private val cache: LocalEventCache? = null,
    /**
     * The notes the user bookmarked, most recently bookmarked first, as the local table holds them.
     * That table is kept current by the bookmark sync and by every add and remove, so it answers
     * without a round trip; when it is empty (a fresh install whose first sync has not landed
     * yet) the list is read from the relays instead.
     */
    private val localBookmarkedNoteIds: (suspend (userId: String, limit: Int) -> List<String>)? = null,
) {

    @Volatile
    private var networkNotes: NetworkNotes? = null

    /**
     * @param failWhenUnreachable throw [RelaysUnreachableException] instead of returning an empty
     *   response when no relay answered. For a caller paging backwards through history, where an
     *   empty answer normally means "that was the last page": a dropped connection must not be
     *   mistaken for it. Off by default, so every other caller keeps getting an empty response.
     */
    @Throws(RelaysUnreachableException::class)
    suspend fun fetch(
        userId: String,
        feedSpec: String,
        includeReplies: Boolean,
        limit: Int,
        until: Long? = null,
        since: Long? = null,
        failWhenUnreachable: Boolean = false,
    ): FeedResponse {
        val feedPage = fetchPage(
            userId = userId,
            feedSpec = feedSpec,
            includeReplies = includeReplies,
            limit = limit,
            until = until,
            since = since,
        )
        if (failWhenUnreachable && feedPage.unreachable) throw RelaysUnreachableException()
        return feedPage.page.toFeedResponse(
            metadata = fetchMetadataFor(feedPage),
            referencedEvents = feedPage.referencedNotes.map { it.asReferencedPrimalEvent() },
            emptyPageCursor = feedPage.resumeUntil,
        )
    }

    /**
     * The events a page needs, before profile metadata is resolved for them — see
     * [fetchMetadataFor]. A caller that wants the whole thing in one shot should use [fetch].
     */
    suspend fun fetchPage(
        userId: String,
        feedSpec: String,
        includeReplies: Boolean,
        limit: Int,
        until: Long? = null,
        since: Long? = null,
    ): NotesFeedPage =
        if (feedSpec.isNotesBookmarkFeedSpec()) {
            fetchBookmarkedNotesPage(userId = userId, limit = limit, until = until, since = since)
        } else if (feedSpec.isNetworkNotesFeedSpec()) {
            fetchNetworkNotesPage(userId = userId, limit = limit, until = until, since = since)
        } else {
            fetchAuthoredNotesPage(
                userId = userId,
                feedSpec = feedSpec,
                includeReplies = includeReplies,
                limit = limit,
                until = until,
                since = since,
            )
        }

    /**
     * Every feed whose scope is a set of authors: following, a follow set, or one profile.
     *
     * A page is assembled from one or more windows of history. A window asks every author chunk
     * for its newest [limit] events older than the cursor and keeps what the feed shows (replies
     * are dropped unless asked for). When that leaves fewer than [limit] events, which happens as
     * soon as a window is mostly replies, the next window starts where this one stopped instead of
     * handing back a short, or empty, page. An empty page used to be read as "no older notes
     * exist", and a profile whose latest notes were all replies stopped loading at that point.
     */
    private suspend fun fetchAuthoredNotesPage(
        userId: String,
        feedSpec: String,
        includeReplies: Boolean,
        limit: Int,
        until: Long? = null,
        since: Long? = null,
    ): NotesFeedPage {
        val authors = loadAuthors(userId, feedSpec)
        if (authors.isEmpty()) return NotesFeedPage(page = emptyList(), referencedNotes = emptyList())

        // A media grid keeps only the notes that carry an image or a video, which is a small share of
        // anyone's notes: the windows below are what turns "20 notes" into "20 notes worth a tile".
        val isMediaFeed = feedSpec.isProfileAuthoredMediaFeedSpec()
        val kinds = if (isMediaFeed) MEDIA_NOTE_KINDS else FEED_NOTE_KINDS
        val isShown = { event: NostrEvent ->
            if (isMediaFeed) event.isShownInMediaGrid() else event.isShownInFeed(includeReplies)
        }

        val shown = LinkedHashMap<String, NostrEvent>()
        var cursor = until
        var resumeUntil: Long? = null
        var answered = false
        var windows = 1
        var window = queryWindow(authors = authors, kinds = kinds, limit = limit, until = cursor, since = since)
        while (window.answered) {
            answered = true
            window.events.filter(isShown).forEach { shown[it.id] = it }
            val next = window.nextCursor(after = cursor)?.takeIf { since == null || it >= since }
            resumeUntil = next
            if (shown.size >= limit || next == null || windows >= MAX_WINDOWS_PER_PAGE) break
            cursor = next
            window = queryWindow(authors = authors, kinds = kinds, limit = limit, until = cursor, since = since)
            windows++
        }

        val page = shown.values.sortedByDescending { it.createdAt }.take(limit)
        return buildPageEvents(page).copy(
            unreachable = !answered && page.isEmpty(),
            resumeUntil = resumeUntil.takeIf { page.isEmpty() },
        )
    }

    private fun NostrEvent.isShownInFeed(includeReplies: Boolean): Boolean =
        when (kind) {
            NostrEventKind.ShortTextNote.value -> includeReplies || !tags.hasEventIdTag()
            NostrEventKind.ShortTextNoteRepost.value -> true
            else -> false
        }

    /**
     * A media grid draws plain notes only: a repost is somebody else's picture, and counting it
     * would also spend part of each window's [limit] on events that are then thrown away.
     */
    private fun NostrEvent.isShownInMediaGrid(): Boolean =
        kind == NostrEventKind.ShortTextNote.value && carriesMedia()

    /** One window of history: every author chunk asked for its newest [limit] events of [kinds]. */
    private suspend fun queryWindow(
        authors: List<String>,
        kinds: List<Int>,
        limit: Int,
        until: Long?,
        since: Long?,
    ): AuthorsWindow {
        val chunks = authors.take(MAX_FOLLOW_AUTHORS).chunked(AUTHOR_CHUNK)
        val semaphore = Semaphore(MAX_PARALLEL_CHUNKS)
        val outcomes = coroutineScope {
            chunks.map { chunk ->
                async {
                    semaphore.withPermit {
                        queryChunkWithOutcome(
                            authors = chunk,
                            kinds = kinds,
                            limit = limit,
                            until = until,
                            since = since,
                        )
                    }
                }
            }.awaitAll()
        }
        return AuthorsWindow.from(outcomes = outcomes, limit = limit)
    }

    /**
     * The notes a page needs around it, before profile metadata is resolved for them.
     *
     * Split from the old `buildPageResponse` so a caller (the REFRESH branch of
     * [net.primal.data.repository.feed.paging.NoteFeedRemoteMediator]) can persist this alone —
     * the note bodies and the quotes they need to render — without waiting for kind 0 to also
     * land first, the same split [RelayThreadFetcher] already uses for the same reason.
     */
    private suspend fun buildPageEvents(page: List<NostrEvent>): NotesFeedPage {
        val pageIds = page.map { it.id }.toSet()
        val referencedNotes = fetchReferencedNotes(
            referencedIds = page.referencedNoteIds().filterNot { it in pageIds },
        )
        return NotesFeedPage(page = page, referencedNotes = referencedNotes)
    }

    /** Everyone a page's content renderer needs a kind 0 for: authors, mentions, and quotes. */
    suspend fun fetchMetadataFor(feedPage: NotesFeedPage): List<NostrEvent> {
        val pageAuthorCandidates = feedPage.page.metadataAuthorCandidates()
        val pageMetadata = fetchMetadataFor(pubkeys = pageAuthorCandidates)

        // A quoted note can introduce an author the page itself never mentioned. Everyone else
        // was already covered by the query above, so this is a small, often-empty top-up rather
        // than a third full round trip.
        val extraAuthorCandidates =
            feedPage.referencedNotes.metadataAuthorCandidates() - pageAuthorCandidates.toSet()
        val extraMetadata = fetchMetadataFor(pubkeys = extraAuthorCandidates)

        return pageMetadata + extraMetadata
    }

    /**
     * A bookmarks feed is the odd one out among the feeds served here: its scope is not a set of
     * authors but the list of event ids the user saved (NIP-51, kind 10003), so there is no author
     * filter to build. Each id is resolved the way a quoted note is, from what is already stored
     * locally and from the relays only for the rest, and the page is then cut by the notes' own
     * timestamps like every other feed, so the mediator's `until` cursor keeps its usual meaning.
     *
     * Only kind 1 notes come back: they are the one kind the relay-served feeds turn into feed
     * posts with their content intact. A bookmark that points at anything else is left out rather
     * than shown as an empty card.
     */
    private suspend fun fetchBookmarkedNotesPage(
        userId: String,
        limit: Int,
        until: Long?,
        since: Long?,
    ): NotesFeedPage {
        val bookmarkedIds = loadBookmarkedNoteIds(userId)
        if (bookmarkedIds.isEmpty()) return NotesFeedPage(page = emptyList(), referencedNotes = emptyList())

        val page = fetchReferencedNotes(referencedIds = bookmarkedIds)
            .distinctBy { it.id }
            .filter { it.kind == NostrEventKind.ShortTextNote.value }
            .filter { until == null || it.createdAt <= until }
            .filter { since == null || it.createdAt >= since }
            .sortedByDescending { it.createdAt }
            .take(limit)
        return buildPageEvents(page)
    }

    /**
     * The network feed: what the people the user follows reacted to or reposted, written by people the user does
     * not follow, best first.
     *
     * Nothing here needs a server that ranks the network. The user's follows are asked once for the reactions and
     * reposts they made in the last [NETWORK_WINDOW_SECONDS] (a handful of requests, however many people that is),
     * and every target note is tallied by how many *different* friends engaged with it. That tally picks the
     * candidates: a note a few of your friends liked is worth a look; one that only one did, usually is not, unless
     * the user follows so few people that one is all there is ([minimumFriends]). Notes by people the user already
     * follows, their own, and replies never make the list; the point is to surface people the user does not follow
     * yet.
     *
     * The order is by interactions: the note's reactions plus its replies plus the number of zaps it received (the
     * number, not the sats, so one big zap does not outweigh a hundred small ones), with the friend tally and then
     * recency to break ties. That needs the counters of the shortlisted notes, which are fetched once here and
     * handed to the caller in [NotesFeedPage.stats].
     *
     * A ranking has no "older than" to page by, so the first page carries the whole list (at most
     * [NETWORK_PAGE_SIZE] notes) and every later page, asked for with a time cursor, is empty. The result is kept for
     * [NETWORK_CACHE_SECONDS], so reopening the section does not repeat the requests.
     */
    private suspend fun fetchNetworkNotesPage(
        userId: String,
        limit: Int,
        until: Long?,
        since: Long?,
    ): NotesFeedPage {
        if (until != null || since != null) return NotesFeedPage(page = emptyList(), referencedNotes = emptyList())

        val network = loadNetworkNotes(userId)
        if (network.ranked.isEmpty()) {
            return NotesFeedPage(page = emptyList(), referencedNotes = emptyList(), unreachable = network.unreachable)
        }
        val page = network.ranked.take(maxOf(limit, NETWORK_PAGE_SIZE))
        val pageIds = page.map { it.id }.toSet()
        val stats = RelayEventStatsSnapshot(
            eventStats = network.stats.eventStats.filter { it.eventId in pageIds },
            userStats = network.stats.userStats.filter { it.eventId in pageIds },
        )
        return buildPageEvents(page).copy(stats = stats)
    }

    private suspend fun loadNetworkNotes(userId: String): NetworkNotes {
        val now = Clock.System.now().epochSeconds
        networkNotes?.takeIf { it.userId == userId && now - it.loadedAt < NETWORK_CACHE_SECONDS }?.let { return it }

        val friends = loadFollows(userId).filter { it != userId }.toSet()
        if (friends.isEmpty()) return NetworkNotes(userId = userId, loadedAt = now)

        val window = queryInChunks(
            authors = friends.toList(),
            kinds = NETWORK_ENGAGEMENT_KINDS,
            limit = NETWORK_ENGAGEMENT_LIMIT,
            since = now - NETWORK_WINDOW_SECONDS,
        )
        val tally = window.tallyFriendEngagement(friends)
            .filterValues { it.size >= minimumFriends(friends.size) }
        val candidateIds = tally.entries
            .sortedByDescending { it.value.size }
            .take(MAX_NETWORK_CANDIDATES)
            .map { it.key }
        val shortlist = fetchReferencedNotes(candidateIds)
            .asSequence()
            .distinctBy { it.id }
            .filter { it.kind == NostrEventKind.ShortTextNote.value && !it.tags.hasEventIdTag() }
            .filter { it.pubKey !in friends && it.pubKey != userId }
            .sortedWith(compareByDescending<NostrEvent> { tally[it.id]?.size ?: 0 }.thenByDescending { it.createdAt })
            .take(NETWORK_PAGE_SIZE)
            .toList()

        // Nothing came back at all from a user who does follow people: the relays did not answer, which is not
        // the same as "your friends liked nothing", so the page must not read as the end of the list.
        if (shortlist.isEmpty()) {
            return NetworkNotes(userId = userId, loadedAt = now, unreachable = window.isEmpty())
                .also { if (window.isNotEmpty()) networkNotes = it }
        }

        val stats = runCatching { RelayEventStatsFetcher(querier, coordinator).fetch(shortlist.map { it.id }, userId) }
            .getOrDefault(RelayEventStatsSnapshot(emptyList(), emptyList()))
        val interactions = stats.eventStats.associate { it.eventId to it.interactionScore() }
        val ranked = shortlist.sortedWith(
            compareByDescending<NostrEvent> { interactions[it.id] ?: 0L }
                .thenByDescending { tally[it.id]?.size ?: 0 }
                .thenByDescending { it.createdAt },
        )
        return NetworkNotes(userId = userId, loadedAt = now, ranked = ranked, stats = stats)
            .also { networkNotes = it }
    }

    /**
     * What a note's interactions add up to for the network feed's order: reactions, replies and the number of
     * zaps. Reposts are not counted, and a zap counts as one however many sats it carried.
     */
    private fun EventStats.interactionScore(): Long = likes + replies + zaps

    /** One friend is enough for someone who follows few people; a crowd needs more than one to mean something. */
    private fun minimumFriends(friendCount: Int): Int =
        if (friendCount >= NETWORK_MANY_FRIENDS) MIN_FRIENDS_FOR_MANY else 1

    /**
     * Which friends engaged with which note: reactions and reposts name their target in their last `e`
     * tag. A downvote (`-`) is not engagement, and an event by somebody who is not a friend (a relay
     * handing back more than it was asked for) is not counted.
     */
    private fun List<NostrEvent>.tallyFriendEngagement(friends: Set<String>): Map<String, Set<String>> {
        val byNote = HashMap<String, MutableSet<String>>()
        asSequence()
            .filter { it.pubKey in friends }
            .filterNot { it.kind == NostrEventKind.Reaction.value && it.content == DOWNVOTE }
            .forEach { event ->
                event.tags.eventIdTagValues().lastOrNull()?.let { target ->
                    byNote.getOrPut(target) { mutableSetOf() }.add(event.pubKey)
                }
            }
        return byNote
    }

    private suspend fun loadBookmarkedNoteIds(userId: String): List<String> {
        val local = runCatching { localBookmarkedNoteIds?.invoke(userId, MAX_BOOKMARKED_NOTES) }
            .getOrDefault(null)
            .orEmpty()
        return local.ifEmpty { loadBookmarkedNoteIdsFromRelays(userId) }
    }

    private suspend fun loadBookmarkedNoteIdsFromRelays(userId: String): List<String> {
        val list = runCatching {
            querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.BookmarksList.value),
                    authors = listOf(userId),
                    limit = BOOKMARK_LIST_QUERY_LIMIT,
                ),
            )
        }.getOrDefault(emptyList()).maxByOrNull { it.createdAt } ?: return emptyList()

        // Entries are appended as they are added, so the newest bookmark is the last tag.
        return list.tags.eventIdTagValues().distinct().asReversed().take(MAX_BOOKMARKED_NOTES)
    }

    /** Everyone the content renderer might need a kind 0 for: an author, or an `nostr:` mention. */
    private fun List<NostrEvent>.metadataAuthorCandidates(): List<String> =
        (map { it.pubKey } + flatMap { it.tags.pubkeyTagValues() } + flatMap { it.mentionedInContent() }).distinct()

    /**
     * The notes a page's own quotes/mentions point at are usually already stored locally,
     * because either the feed or a previous page put them there. [cache] answers that without a
     * relay round trip; only ids it doesn't recognize are actually requested.
     */
    private suspend fun fetchReferencedNotes(referencedIds: List<String>): List<NostrEvent> {
        if (referencedIds.isEmpty()) return emptyList()
        val cached = cache?.partitionKnownEventIds(referencedIds)
        val missing = cached?.missing ?: referencedIds
        val known = cached?.known.orEmpty()
        return if (missing.isEmpty()) known else known + queryByIds(missing)
    }

    /**
     * Without the mentioned profiles the renderer has no kind 0 to resolve a `nostr:` mention
     * against and falls back to an ellipsized npub, so a tagged user showed up as @npub1abc…xyz
     * instead of their name. [cache] claims each pubkey for the session so a profile already
     * fetched by an earlier page (an active poster recurs across pages far more often than not)
     * is never asked for twice; a claim that comes back empty-handed is released so it can be
     * retried later instead of staying a raw npub for the rest of the session.
     */
    private suspend fun fetchMetadataFor(pubkeys: List<String>): List<NostrEvent> {
        val distinct = pubkeys.distinct().take(MAX_METADATA_AUTHORS)
        val wanted = cache?.claimMetadataPubkeys(distinct) ?: distinct
        if (wanted.isEmpty()) return emptyList()
        var metadata: List<NostrEvent> = emptyList()
        try {
            metadata = queryInChunks(
                authors = wanted,
                kinds = listOf(NostrEventKind.Metadata.value),
                limit = wanted.size,
            )
        } finally {
            // Also on cancellation (pull-to-refresh, leaving the screen mid-load): a claim never
            // given back leaves the author a raw npub for the rest of the session.
            withContext(NonCancellable) {
                cache?.releaseMetadataPubkeys(wanted - metadata.map { it.pubKey }.toSet())
            }
        }
        return metadata
    }

    private suspend fun queryByIds(ids: List<String>): List<NostrEvent> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(ID_CHUNK).let { chunks ->
            coroutineScope {
                chunks.map { chunk ->
                    async {
                        runCatching {
                            querier.query(RelayFilter(ids = chunk, limit = chunk.size))
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
        }
    }

    /** The author scope for a feed spec. Exposed so a live subscription can reuse it. */
    suspend fun resolveAuthors(userId: String, feedSpec: String): List<String> = loadAuthors(userId, feedSpec)

    private suspend fun loadAuthors(userId: String, feedSpec: String): List<String> {
        if (feedSpec.isFollowSetFeedSpec()) {
            val pubkey = feedSpec.extractFollowSetPubkey() ?: userId
            val dTag = feedSpec.extractFollowSetDTag() ?: return emptyList()
            return loadFollowSet(pubkey, dTag)
        }
        feedSpec.profileFeedAuthor()?.let { return listOf(it) }
        return loadFollows(userId) + userId
    }

    /**
     * The one person a profile tab is about.
     *
     * Somebody's notes are a plain `authors` filter, so there was never a reason for these tabs to
     * need anything a relay cannot do. They fell through to the centralized feed API instead,
     * which in a relay-only build always throws, so the notes and replies tabs of every profile
     * showed "unable to load content" and always had.
     */
    private fun String.profileFeedAuthor(): String? =
        when {
            isProfileAuthoredNotesFeedSpec() ->
                extractPubkeyFromFeedSpec(prefix = AUTHORED_NOTES_PREFIX, suffix = "}")

            isProfileAuthoredNoteRepliesFeedSpec() ->
                extractPubkeyFromFeedSpec(prefix = AUTHORED_REPLIES_PREFIX, suffix = "}")

            isProfileAuthoredMediaFeedSpec() ->
                extractPubkeyFromFeedSpec(prefix = AUTHORED_MEDIA_PREFIX, suffix = "}")

            else -> null
        }

    private suspend fun loadFollowSet(pubkey: String, dTag: String): List<String> {
        val events = runCatching {
            querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.CategorizedPeopleList.value),
                    authors = listOf(pubkey),
                ),
            )
        }.getOrDefault(emptyList())
        val latest = events
            .filter { it.tags.findFirstIdentifier() == dTag }
            .maxByOrNull { it.createdAt }
            ?: return emptyList()
        return latest.tags.pubkeyTagValues().distinct().take(MAX_FOLLOW_AUTHORS)
    }

    private suspend fun queryInChunks(
        authors: List<String>,
        kinds: List<Int>,
        limit: Int? = null,
        until: Long? = null,
        since: Long? = null,
    ): List<NostrEvent> {
        if (authors.isEmpty()) return emptyList()
        val capped = authors.take(MAX_FOLLOW_AUTHORS)
        val chunks = capped.chunked(AUTHOR_CHUNK)
        val semaphore = Semaphore(MAX_PARALLEL_CHUNKS)
        return coroutineScope {
            chunks.map { chunk ->
                async {
                    queryChunkLimited(semaphore, chunk, kinds, limit, until, since)
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun queryChunkLimited(
        semaphore: Semaphore,
        chunk: List<String>,
        kinds: List<Int>,
        limit: Int?,
        until: Long?,
        since: Long?,
    ): List<NostrEvent> =
        semaphore.withPermit {
            queryChunk(
                authors = chunk,
                kinds = kinds,
                limit = limit ?: chunk.size,
                until = until,
                since = since,
            )
        }

    private suspend fun queryChunk(
        authors: List<String>,
        kinds: List<Int>,
        limit: Int,
        until: Long?,
        since: Long?,
    ): List<NostrEvent> =
        runCatching {
            querier.query(
                RelayFilter(
                    kinds = kinds,
                    authors = authors,
                    limit = limit,
                    until = until,
                    since = since,
                ),
            )
        }.getOrDefault(emptyList())

    /**
     * [queryChunk] for a caller that has to know whether the relays answered. A query that throws
     * counts as unanswered too, where [queryChunk] folds it into an empty list.
     */
    private suspend fun queryChunkWithOutcome(
        authors: List<String>,
        kinds: List<Int>,
        limit: Int,
        until: Long?,
        since: Long?,
    ): RelayQueryOutcome =
        runCatching {
            querier.queryWithOutcome(
                RelayFilter(
                    kinds = kinds,
                    authors = authors,
                    limit = limit,
                    until = until,
                    since = since,
                ),
            )
        }.getOrDefault(RelayQueryOutcome(events = emptyList(), answered = false))

    /**
     * The follow list used to be fetched before every single page — a full round trip paid up
     * front, with the notes query blocked behind it — and the per-fetcher cache that replaced it
     * lived as long as the object, which meant the article feed and the profile screen each kept
     * their own copy. The coordinator holds one, briefly, for all of them.
     */
    private suspend fun loadFollows(userId: String): List<String> {
        val events = runCatching { coordinator.fetchFollowList(querier = querier, pubkey = userId) }
            .getOrDefault(emptyList())
        val latest = events.maxByOrNull { it.createdAt } ?: return emptyList()
        return latest.tags.pubkeyTagValues().distinct().take(MAX_FOLLOW_AUTHORS)
    }

    companion object {
        /** Relays reject very large filter arrays; a page of notes cannot need more. */
        private const val MAX_METADATA_AUTHORS = 500

        // Wider chunks and more of them in flight mean fewer sequential waves. A page used to
        // cost ceil(follows / 100) / 4 waves, and each wave is bounded by the relay timeout, so
        // 800 follows paid two full waves before a single note could be shown.
        private const val AUTHOR_CHUNK = 250
        private const val MAX_FOLLOW_AUTHORS = 2_000
        private const val MAX_PARALLEL_CHUNKS = 8
        private const val ID_CHUNK = 50

        /**
         * How many windows of history one page may spend looking for [limit] events worth showing.
         * Each window is a full round of relay queries, so this bounds the cost of a feed that is
         * mostly replies; if the cap is hit, the page reports where to resume from instead.
         */
        private const val MAX_WINDOWS_PER_PAGE = 4

        private val FEED_NOTE_KINDS = listOf(
            NostrEventKind.ShortTextNote.value,
            NostrEventKind.ShortTextNoteRepost.value,
        )
        private val MEDIA_NOTE_KINDS = listOf(NostrEventKind.ShortTextNote.value)

        // --- The network feed ---------------------------------------------------------------------------

        /** How far back friends' reactions and reposts are looked at. */
        private const val NETWORK_WINDOW_SECONDS = 48L * 60 * 60

        /** The tally is reused for this long, so a scroll through the list costs the requests once. */
        private const val NETWORK_CACHE_SECONDS = 10L * 60

        /** Per author chunk: the newest this many reactions and reposts of up to [AUTHOR_CHUNK] friends. */
        private const val NETWORK_ENGAGEMENT_LIMIT = 1_000

        /** Notes fetched by id at most; the ones with the most friends behind them come first. */
        private const val MAX_NETWORK_CANDIDATES = 200

        /**
         * Notes ranked, and so listed: the shortlist (most friends first) whose counters are fetched. Bounded because
         * the counters cost several requests per fifty notes.
         */
        private const val NETWORK_PAGE_SIZE = 100

        /** Following this many people or more, a note needs [MIN_FRIENDS_FOR_MANY] friends behind it. */
        private const val NETWORK_MANY_FRIENDS = 40
        private const val MIN_FRIENDS_FOR_MANY = 2
        private const val DOWNVOTE = "-"
        private val NETWORK_ENGAGEMENT_KINDS = listOf(
            NostrEventKind.Reaction.value,
            NostrEventKind.ShortTextNoteRepost.value,
            NostrEventKind.GenericRepost.value,
        )

        /** Every page resolves the whole list, so the list a feed will ever look at is bounded. */
        private const val MAX_BOOKMARKED_NOTES = 300
        private const val BOOKMARK_LIST_QUERY_LIMIT = 5

        private const val AUTHORED_NOTES_PREFIX = """{"id":"feed","kind":"notes","notes":"authored""""
        private const val AUTHORED_REPLIES_PREFIX =
            """{"id":"feed","include_replies":true,"kind":"notes","notes":"authored""""
        private const val AUTHORED_MEDIA_PREFIX = """{"id":"feed","kind":"notes","notes":"user_media_thumbnails""""
    }
}

/**
 * What one window of history returned, merged across the author chunks that were asked.
 *
 * A chunk that came back with [limit] events was cut short by that limit: it holds more, older,
 * and the events it did return only cover time down to its oldest one. Taking the newest [limit]
 * of the merged result regardless used to let a quiet chunk's older events push the page past that
 * point, and the busy chunk's events in the gap were never requested again, because the next page
 * starts at the oldest event shown. [cutoff] is the oldest point every chunk is known to cover.
 */
private class AuthorsWindow(
    /** Merged and de-duplicated, already trimmed to what every chunk fully covers. */
    val events: List<NostrEvent>,
    val answered: Boolean,
    /** The oldest second the window fully covers; null when no chunk was cut short. */
    val cutoff: Long?,
) {

    /**
     * Where the next window should start, or null when this one reached the end of what the
     * relays hold. [after] is the cursor this window was asked with.
     */
    fun nextCursor(after: Long?): Long? =
        cutoff?.let { covered ->
            // A whole chunk limit of events in one second makes the cutoff equal to the cursor,
            // which would ask the same question again forever. Skip the rest of that second.
            if (after == null || covered < after) covered else after - 1
        }

    companion object {
        fun from(outcomes: List<RelayQueryOutcome>, limit: Int): AuthorsWindow {
            val cutoff = outcomes
                .filter { it.events.size >= limit }
                .map { outcome -> outcome.events.minOf { it.createdAt } }
                .maxOrNull()
            val merged = outcomes.flatMap { it.events }.distinctBy { it.id }
            return AuthorsWindow(
                events = if (cutoff == null) merged else merged.filter { it.createdAt >= cutoff },
                answered = outcomes.any { it.answered },
                cutoff = cutoff,
            )
        }
    }
}

internal fun List<NostrEvent>.toFeedResponse(
    metadata: List<NostrEvent>,
    referencedEvents: List<PrimalEvent> = emptyList(),
    /** See [NotesFeedPage.resumeUntil]; only read when this list is empty. */
    emptyPageCursor: Long? = null,
): FeedResponse {
    val notes = filter { it.kind == NostrEventKind.ShortTextNote.value || it.isComment() }
    val reposts = filter { it.kind == NostrEventKind.ShortTextNoteRepost.value }
    val created = map { it.createdAt }
    val paging = if (created.isEmpty()) {
        ContentPrimalPaging(
            orderBy = "created_at",
            sinceId = emptyPageCursor ?: 0,
            untilId = emptyPageCursor ?: 0,
            elements = emptyList(),
        )
    } else {
        ContentPrimalPaging(
            orderBy = "created_at",
            sinceId = created.min(),
            untilId = created.max(),
            elements = map { it.id },
        )
    }
    return FeedResponse(
        paging = paging,
        metadata = metadata,
        notes = notes,
        articles = filter { it.kind == NostrEventKind.LongFormContent.value },
        reposts = reposts,
        zaps = emptyList(),
        referencedEvents = referencedEvents,
        primalEventStats = emptyList(),
        primalEventUserStats = emptyList(),
        cdnResources = emptyList(),
        primalLinkPreviews = emptyList(),
        primalRelayHints = emptyList(),
        blossomServers = emptyList(),
    )
}

internal fun emptyFeedResponse(): FeedResponse = emptyList<NostrEvent>().toFeedResponse(emptyList())
