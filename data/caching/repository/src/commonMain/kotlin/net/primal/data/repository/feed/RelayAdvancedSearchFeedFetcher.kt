package net.primal.data.repository.feed

import io.github.aakira.napier.Napier
import kotlin.math.ln
import kotlin.time.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.core.utils.serialization.decodeFromJsonStringOrNull
import net.primal.data.local.dao.events.EventStats
import net.primal.data.local.db.CachingDatabase
import net.primal.data.remote.api.feed.model.FeedResponse
import net.primal.data.repository.feed.search.ContentFilter
import net.primal.data.repository.feed.search.SearchMatcher
import net.primal.data.repository.feed.search.SearchOrder
import net.primal.data.repository.feed.search.SearchPeople
import net.primal.data.repository.feed.search.SearchQuery
import net.primal.data.repository.feed.search.SearchTerm
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.domain.feeds.extractAdvancedSearchQuery
import net.primal.domain.nostr.ContentMetadata
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.pubkeyTagValues
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.nostr.utils.extractProfileId

/** A profile this device already knows, as far as matching a typed name goes. */
internal data class ProfileCandidate(
    val pubkey: String,
    val name: String?,
    val displayName: String?,
    val nip05: String?,
)

internal suspend fun CachingDatabase.findProfileCandidates(prefix: String): List<ProfileCandidate> =
    profiles().findProfilesByPrefix(prefix = prefix, limit = LOCAL_PROFILE_CANDIDATES).map {
        ProfileCandidate(
            pubkey = it.ownerId,
            name = it.handle,
            displayName = it.displayName,
            nip05 = it.internetIdentifier,
        )
    }

private const val LOCAL_PROFILE_CANDIDATES = 20

/**
 * Executes a search against relays.
 *
 * The command comes from the search box or the advanced search screen (see [SearchQuery]). Relays
 * get what NIP-01 and NIP-50 can express, and the rest is applied here, which also gives useful
 * results on relays that do not implement NIP-50.
 *
 * Two orders exist, as on X: newest first, which pages back in time, and "Top", which ranks one
 * larger batch by the interactions it got and has no further pages.
 */
internal class RelayAdvancedSearchFeedFetcher(
    private val querier: RelayEventQuerier,
    private val coordinator: FetchCoordinator,
    /** Profiles known locally whose name starts with the argument: most mentions are of people already seen. */
    private val findLocalProfiles: suspend (String) -> List<ProfileCandidate> = { emptyList() },
) {

    private val statsFetcher = RelayEventStatsFetcher(querier = querier, coordinator = coordinator)

    // Each early return is a way a query can be unable to match anything at all.
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    suspend fun fetch(
        userId: String,
        feedSpec: String,
        fallbackKinds: List<Int>,
        limit: Int,
        until: Long? = null,
        since: Long? = null,
    ): FeedResponse {
        val text = feedSpec.extractAdvancedSearchQuery().orEmpty()
        val query = SearchQuery.parse(text)
        val ranked = query.order != SearchOrder.Latest
        // A ranking is not a timeline: there is nothing "older" to append to it.
        if (ranked && until != null) return emptyFeedResponse()

        val follows = FollowsOnce(userId)
        val people = resolvePeople(query, follows) ?: return emptyFeedResponse()
        val zappedBy = resolveAll(query.zappedBy, follows) ?: return emptyFeedResponse()

        val scopeAuthors = if (query.scope == "myfollows" && people.from.isEmpty()) {
            follows.get().ifEmpty { return emptyFeedResponse() }
        } else {
            emptySet()
        }
        val notifications = if (query.scope == "mynotifications") setOf(userId) else emptySet()
        val excludedAuthors = if (query.scope == "notmyfollows") follows.get() else emptySet()

        val pageSize = if (ranked || query.minimums.any) {
            RANKING_CANDIDATES
        } else {
            (limit * 2).coerceAtMost(RANKING_CANDIDATES)
        }
        val base = RelayFilter(
            kinds = query.relayKinds(fallbackKinds),
            authors = people.from.ifEmpty { scopeAuthors }.toList().ifEmpty { null },
            pubkeyTags = (people.to + zappedBy + notifications).toList().ifEmpty { null },
            eventTags = query.replyToEventIds.ifEmpty { null },
            limit = pageSize,
            until = until ?: query.until,
            since = since ?: query.since,
        )

        val matcher = SearchMatcher(query, people)
        fun accepts(event: NostrEvent) =
            event.pubKey !in excludedAuthors &&
                (notifications.isEmpty() || event.tags.pubkeyTagValues().any { it in notifications }) &&
                matcher.matches(event)

        val candidates = search(variants(query, base, people), ::accepts)
        val page = candidates.orderedFor(query, follows).take(if (ranked) RANKING_CANDIDATES / 2 else limit)

        Napier.i {
            "Relay search order=${query.order} candidates=${candidates.size} results=${page.size} " +
                "newest=${page.maxOfOrNull { it.createdAt }} oldest=${page.minOfOrNull { it.createdAt }}"
        }

        // Quoted notes (a `q` tag, or a bare `nostr:note1…`/`nevent1…` in the content) name a
        // specific note the content renderer needs — without this, a quote of anything not
        // already in the page for some other reason showed "Mentioned event not found," which
        // only ever affected search results since the feed/thread fetchers already do this.
        //
        // The page's own profiles are fetched at the same time as the quoted notes instead of
        // after them; only the (usually few) extra authors those quotes bring in wait for them.
        val pageIds = page.map { it.id }.toSet()
        val pagePubkeys = page.metadataSubjectPubkeys()
        val (referencedNotes, metadata) = coroutineScope {
            val referencedDeferred = async { queryByIds(page.referencedNoteIds().filterNot { it in pageIds }) }
            val pageMetadataDeferred = async { queryMetadata(pagePubkeys) }
            val referenced = referencedDeferred.await()
            val extraPubkeys = referenced.metadataSubjectPubkeys().filterNot { it in pagePubkeys }
            referenced to (pageMetadataDeferred.await() + queryMetadata(extraPubkeys))
        }
        return page.toFeedResponse(metadata, referencedEvents = referencedNotes.map { it.asReferencedPrimalEvent() })
    }

    /**
     * The filters to send. A relay filter cannot say "this word or that one", so an `OR` becomes
     * one filter per alternative, and a mention becomes one for what the person wrote and one for
     * what was written to them.
     */
    private fun variants(
        query: SearchQuery,
        base: RelayFilter,
        people: SearchPeople,
    ): List<RelayFilter> {
        // Hashtags go to the `#t` index, not to full-text search: a relay honors `limit` on a tag
        // filter by returning the newest matches (NIP-01), while a search term only has to return
        // its best full-text matches, which skews towards old posts. A tag filter matches ANY of
        // its values, so one group (one tag, or `#a OR #b`) goes there and the rest is checked here.
        val tagGroup = query.groups.firstOrNull { group -> group.all { it is SearchTerm.Hashtag } }
        val orGroup = query.groups.firstOrNull { it.size > 1 && it !== tagGroup }
        val required = query.groups.filter { it.size == 1 && it !== tagGroup }.map { it.single() }
        val alternatives: List<SearchTerm?> = orGroup?.take(MAX_ALTERNATIVES) ?: listOf(null)

        val byText = alternatives.map { alternative ->
            val terms = required + listOfNotNull(alternative)
            val words = terms.mapNotNull {
                when (it) {
                    is SearchTerm.Word -> it.text
                    is SearchTerm.Phrase -> it.text
                    is SearchTerm.Hashtag -> null
                }
            }
            val tags = tagGroup?.filterIsInstance<SearchTerm.Hashtag>()?.map { it.tag }
                ?: listOfNotNull((alternative as? SearchTerm.Hashtag)?.tag)
            base.copy(
                search = words.joinToString(" ").ifBlank { null },
                hashtagTags = tags.ifEmpty { null },
            )
        }

        if (people.mentions.isEmpty() || base.authors != null) return byText.distinct()
        val mentioned = people.mentions.toList()
        return byText.flatMap { filter ->
            listOf(
                filter.copy(pubkeyTags = filter.pubkeyTags.orEmpty() + mentioned),
                filter.copy(authors = mentioned),
            )
        }.distinct()
    }

    /**
     * NIP-50 is optional. If a relay ignores or rejects `search`, retry the same standard filters
     * and apply the text predicate locally. The decision is made on what survives that predicate,
     * not on the raw answer: a relay that ignores `search` returns arbitrary events, and trusting
     * that non-empty page skipped the retry only for the predicate to throw all of it away. The
     * retry does not keep the page size: a small arbitrary sample makes rare terms look as if they
     * only have ancient results.
     */
    private suspend fun search(filters: List<RelayFilter>, accepts: (NostrEvent) -> Boolean): List<NostrEvent> {
        suspend fun run(requests: List<RelayFilter>) =
            coroutineScope { requests.map { async { query(it) } }.awaitAll() }
                .flatten()
                .distinctBy { it.id }
                .filter(accepts)

        val matches = run(filters)
        if (matches.isNotEmpty() || filters.none { it.search != null }) return matches
        return run(
            filters.map { it.copy(search = null, limit = maxOf(it.limit ?: 0, FALLBACK_SEARCH_EVENT_LIMIT)) }
                .distinct(),
        )
    }

    private suspend fun List<NostrEvent>.orderedFor(query: SearchQuery, follows: FollowsOnce): List<NostrEvent> {
        val newestFirst = sortedByDescending { it.createdAt }
        if (query.order == SearchOrder.Latest && !query.minimums.any) return newestFirst

        val candidates = newestFirst.take(RANKING_CANDIDATES)
        val stats = runCatching {
            statsFetcher.fetch(eventIds = candidates.map { it.id }, userId = "").eventStats.associateBy { it.eventId }
        }.getOrDefault(emptyMap())
        val enough = candidates.filter { stats[it.id].meets(query) }

        return when (query.order) {
            SearchOrder.Latest -> enough
            SearchOrder.Likes -> enough.sortedByDescending { stats[it.id]?.likes ?: 0 }
            SearchOrder.Replies -> enough.sortedByDescending { stats[it.id]?.replies ?: 0 }
            SearchOrder.Zaps -> enough.sortedByDescending { stats[it.id]?.satsZapped ?: 0 }
            SearchOrder.Top -> {
                val followed = follows.get()
                val now = Clock.System.now().epochSeconds
                enough.sortedByDescending { topScore(it, stats[it.id], it.pubKey in followed, now) }
            }
        }
    }

    private fun EventStats?.meets(query: SearchQuery): Boolean {
        val minimums = query.minimums
        if (!minimums.any) return true
        val likes = this?.likes ?: 0
        val replies = this?.replies ?: 0
        val reposts = this?.reposts ?: 0
        val zaps = this?.zaps ?: 0
        return likes >= minimums.likes &&
            replies >= minimums.replies &&
            reposts >= minimums.reposts &&
            zaps >= minimums.zaps &&
            likes + replies + reposts + zaps >= minimums.interactions
    }

    /**
     * "Top": mostly how much a post was interacted with, on a logarithmic scale so one viral post
     * does not bury everything, with a nudge for people the account follows and a slow decay by age.
     */
    @Suppress("MagicNumber") // The weights of one reply, repost or zap against one like.
    private fun topScore(event: NostrEvent, stats: EventStats?, followed: Boolean, now: Long): Double {
        val engagement = (stats?.likes ?: 0) +
            2 * ((stats?.reposts ?: 0) + (stats?.replies ?: 0)) +
            3 * (stats?.zaps ?: 0)
        val ageDays = ((now - event.createdAt).coerceAtLeast(0) / SECONDS_PER_DAY).coerceAtMost(MAX_AGE_DAYS)
        return ln(1.0 + engagement) + (if (followed) 1.0 else 0.0) - ageDays / DECAY_DAYS
    }

    private fun SearchQuery.relayKinds(fallbackKinds: List<Int>): List<Int> {
        // A repost's content is the reposted event as JSON: matching words in it only finds copies.
        val base = kinds.ifEmpty { fallbackKinds.filterNot { it in REPOST_KINDS } }
        val wantsPictures = ContentFilter.Media in requiredFilters || ContentFilter.Image in requiredFilters
        return (if (wantsPictures) base + NostrEventKind.PictureNote.value else base).distinct()
    }

    /** @return null when somebody named in the query cannot be found, so nothing can match. */
    @Suppress("ReturnCount")
    private suspend fun resolvePeople(query: SearchQuery, follows: FollowsOnce): SearchPeople? {
        val from = resolveAll(query.from, follows) ?: return null
        val to = resolveAll(query.to, follows) ?: return null
        val mentions = resolveAll(query.mentions, follows) ?: return null
        // An unknown name to leave out excludes nobody, which is not a reason to find nothing.
        val notFrom = query.notFrom.mapNotNull { resolve(it, follows) }.toSet()
        return SearchPeople(from = from, notFrom = notFrom, to = to, mentions = mentions)
    }

    private suspend fun resolveAll(references: List<String>, follows: FollowsOnce): Set<String>? {
        if (references.isEmpty()) return emptySet()
        return references.mapNotNull { resolve(it, follows) }.toSet().ifEmpty { null }
    }

    /** An npub, nprofile or hex key as it is; anything else is a name to look up. */
    @Suppress("ReturnCount")
    private suspend fun resolve(reference: String, follows: FollowsOnce): String? {
        if (reference.startsWith("npub1") || reference.startsWith("nprofile1")) {
            return runCatching { reference.extractProfileId() }.getOrDefault(null)
        }
        if (HEX_KEY.matches(reference)) return reference.lowercase()

        val name = reference.lowercase()
        val local = runCatching { findLocalProfiles(name) }.getOrDefault(emptyList())
        val followed = follows.get()
        local.firstOrNull { it.pubkey in followed && it.isNamed(name) }?.let { return it.pubkey }

        val remote = query(
            RelayFilter(kinds = listOf(NostrEventKind.Metadata.value), search = name, limit = PROFILE_CANDIDATES),
        ).latestMetadataByPubkey().mapNotNull { it.toCandidate() }

        return (local + remote)
            .map { it to it.matchRank(name, it.pubkey in followed) }
            .filter { it.second > 0 }
            // Stable, so among equals the people already seen stay ahead of the ones relays offered.
            .maxByOrNull { it.second }
            ?.first?.pubkey
    }

    private fun ProfileCandidate.names(): List<String> =
        listOfNotNull(name, displayName, nip05, nip05?.substringBefore('@')).map { it.lowercase() }

    private fun ProfileCandidate.isNamed(wanted: String) = wanted in names()

    private fun ProfileCandidate.matchRank(wanted: String, followed: Boolean): Int =
        when {
            isNamed(wanted) -> if (followed) RANK_FOLLOWED_EXACT else RANK_EXACT
            names().any { it.startsWith(wanted) } -> if (followed) RANK_FOLLOWED_PREFIX else RANK_PREFIX
            else -> 0
        }

    private fun NostrEvent.toCandidate(): ProfileCandidate? {
        val metadata = content.decodeFromJsonStringOrNull<ContentMetadata>() ?: return null
        return ProfileCandidate(
            pubkey = pubKey,
            name = metadata.name,
            displayName = metadata.displayName,
            nip05 = metadata.nip05,
        )
    }

    /** The follow list, read at most once per search and only when something needs it. */
    private inner class FollowsOnce(private val userId: String) {
        private var loaded: Set<String>? = null

        suspend fun get(): Set<String> = loaded ?: loadFollowAuthors(userId).toSet().also { loaded = it }
    }

    private fun List<NostrEvent>.metadataSubjectPubkeys(): List<String> =
        (map { it.pubKey } + flatMap { it.tags.pubkeyTagValues() }).distinct()

    private suspend fun queryMetadata(pubkeys: List<String>): List<NostrEvent> {
        if (pubkeys.isEmpty()) return emptyList()
        return runCatching {
            querier.query(
                RelayFilter(
                    kinds = listOf(NostrEventKind.Metadata.value),
                    authors = pubkeys,
                    limit = pubkeys.size,
                ),
            ).latestMetadataByPubkey()
        }.getOrDefault(emptyList())
    }

    private suspend fun query(filter: RelayFilter): List<NostrEvent> =
        runCatching {
            querier.query(filter)
        }.getOrDefault(emptyList())

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

    private suspend fun loadFollowAuthors(userId: String): List<String> =
        runCatching {
            // Routed through the coordinator: the note feed, article feed and profile screen already
            // ask for this same follow list, often within the same burst of tab loads at app start.
            coordinator.fetchFollowList(querier = querier, pubkey = userId)
                .maxByOrNull { it.createdAt }
                ?.tags
                ?.mapNotNull { tag ->
                    if (tag.size >= 2 && tag.getOrNull(0)?.jsonPrimitive?.contentOrNull == "p") {
                        tag.getOrNull(1)?.jsonPrimitive?.contentOrNull
                    } else {
                        null
                    }
                }
                .orEmpty()
        }.getOrDefault(emptyList())

    private companion object {
        private const val FALLBACK_SEARCH_EVENT_LIMIT = 500
        private const val ID_CHUNK = 50
        private const val RANKING_CANDIDATES = 100
        private const val PROFILE_CANDIDATES = 50
        private const val MAX_ALTERNATIVES = 4
        private const val SECONDS_PER_DAY = 86_400.0
        private const val MAX_AGE_DAYS = 365.0
        private const val DECAY_DAYS = 90.0
        private const val RANK_FOLLOWED_EXACT = 4
        private const val RANK_EXACT = 3
        private const val RANK_FOLLOWED_PREFIX = 2
        private const val RANK_PREFIX = 1
        private val HEX_KEY = Regex("[0-9a-fA-F]{64}")
        private val REPOST_KINDS = setOf(
            NostrEventKind.ShortTextNoteRepost.value,
            NostrEventKind.GenericRepost.value,
        )
    }
}
