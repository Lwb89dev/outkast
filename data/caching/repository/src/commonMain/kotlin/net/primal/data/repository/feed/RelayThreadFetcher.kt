package net.primal.data.repository.feed

import io.github.aakira.napier.Napier
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.data.remote.api.feed.model.FeedResponse
import net.primal.data.repository.cache.LocalEventCache
import net.primal.data.repository.mappers.remote.latestMetadataByPubkey
import net.primal.domain.common.PrimalEvent
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.eventIdTagValues
import net.primal.domain.nostr.findCommentParentEventId
import net.primal.domain.nostr.findCommentRootAddress
import net.primal.domain.nostr.findCommentRootEventId
import net.primal.domain.nostr.findReplyTargetId
import net.primal.domain.nostr.findRootEventId
import net.primal.domain.nostr.isComment
import net.primal.domain.nostr.pubkeyTagValues
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/**
 * Loads a whole thread around an opened note.
 *
 * Latency here is dominated by the number of sequential relay round trips, because each one is
 * bounded by its own EOSE grace and per-pool timeout. The previous version walked the ancestor
 * chain a hop at a time — one round trip per hop, up to five — and then made three or four more
 * in sequence, so opening a reply from the notification list could take tens of seconds.
 *
 * NIP-10 makes the walk unnecessary: a reply's `e` tags already name its root and its parent, so
 * the entire ancestor set can be requested in a single `ids` filter. What is left is three
 * rounds, and the second one runs the ancestors and the replies at the same time:
 *
 *  1. the opened note, and in parallel the replies pointing at it — this is what the user is
 *     looking at, so it is never queued behind anything else;
 *  2. the ancestors named by its tags, and the replies of the whole thread;
 *  3. profile metadata for everyone involved.
 *
 * Replies come in two dialects. A kind 1 reply (NIP-10) carries the root's id in an `e` tag, so one
 * query on the root returns every reply however deep. A kind 1111 comment (NIP-22) names only its
 * direct parent in `e`, and carries the root in an uppercase `E` (or `A` for an addressable root such
 * as an article) instead; the comments of the whole tree are one query on that tag, asked alongside
 * the NIP-10 ones.
 */

/** The flat event set [RelayThreadFetcher.fetchEvents] resolves for one thread. */
internal data class ThreadEvents(
    val all: List<NostrEvent>,
    val referencedNotes: List<NostrEvent>,
)

internal class RelayThreadFetcher(
    private val querier: RelayEventQuerier,
    private val cache: LocalEventCache? = null,
) {

    /**
     * The flat event set a thread needs, before profile metadata is resolved for it.
     *
     * Split out from [fetch] so a caller can overlap [fetchMetadataFor] with an unrelated fetch
     * that only needs this same event list (event-stats, in [FeedRepositoryImpl]) instead of
     * paying for metadata first and starting that other fetch only once it lands.
     */
    suspend fun fetchEvents(
        noteId: String,
        kinds: List<Int>,
        limit: Int,
    ): ThreadEvents =
        coroutineScope {
            // The opened note is almost always a cache hit — the screen was opened from something
            // that had just rendered it (a feed card, a notification) — while its direct replies
            // always need a live query, since new ones are exactly what a re-open is trying to learn
            // about. The two used to be awaited together, which meant knowing the root (round two
            // below only needs `opened`, never `directRepliesAsync`) waited on that live query for no
            // reason: on a comment opened straight from something already on screen, this alone used
            // to cost a whole extra relay round trip before the root note could even be asked for.
            val openedAsync = async { queryByIds(listOf(noteId)) }
            val directRepliesAsync = async { queryEventTags(listOf(noteId), kinds, limit) }

            val opened = openedAsync.await().firstOrNull { it.id == noteId }

            // Every ancestor the opened note references, in one filter instead of one hop at a time.
            val ancestorIds = buildSet {
                opened?.tags?.findRootEventId()?.let { add(it) }
                opened?.tags?.findReplyTargetId()?.let { add(it) }
                opened?.tags?.eventIdTagValues()?.let { addAll(it) }
                opened?.tags?.findCommentRootEventId()?.let { add(it) }
            }.filterNot { it == noteId }

            val rootId = opened.threadRootId() ?: noteId

            // Round two starts the moment `opened` is known, running alongside round one's still
            // in-flight directRepliesAsync rather than behind it.
            val ancestorsAsync = async { queryByIds(ancestorIds) }
            val threadAsync = async {
                if (rootId == noteId) emptyList() else queryEventTags(listOf(rootId), kinds, limit)
            }
            val commentsAsync = async {
                queryComments(
                    rootEventId = opened?.tags?.findCommentRootEventId() ?: noteId,
                    rootAddress = opened?.tags?.findCommentRootAddress(),
                    kinds = kinds,
                    limit = limit,
                )
            }

            val directReplies = directRepliesAsync.await()
            val ancestors = ancestorsAsync.await()
            val threadReplies = threadAsync.await()
            val comments = commentsAsync.await()

            val known = (listOfNotNull(opened) + ancestors + directReplies + threadReplies + comments)
                .distinctBy { it.id }

            // A single top-up pass for parents that were referenced but never arrived. Bounded, and
            // deliberately not recursive: a thread that is still incomplete after this renders with
            // what it has rather than paying more round trips.
            val filled = queryByIds(missingParentIds(known))
            val all = (known + filled).distinctBy { it.id }

            // Quoted notes (a `q` tag, or a bare `nostr:note1…`/`nevent1…` in the content) name a
            // specific note the content renderer needs, same as an ancestor — without this a quote of
            // anything not already fetched for another reason showed "Mentioned event not found."
            val knownIds = all.map { it.id }.toSet()
            val referencedNotes = queryByIds(all.referencedNoteIds().filterNot { it in knownIds })

            Napier.d("Relay thread note=$noteId events=${all.size} ancestors=${ancestors.size}")
            ThreadEvents(all = all, referencedNotes = referencedNotes)
        }

    /** Authors plus everyone the thread mentions, so a `nostr:` mention renders as the name its
     * owner chose rather than as an ellipsized npub. */
    suspend fun fetchMetadataFor(events: ThreadEvents): List<NostrEvent> {
        val metadataSubjects = events.all + events.referencedNotes
        return loadMetadata(
            (metadataSubjects.map { it.pubKey } + metadataSubjects.flatMap { it.tags.pubkeyTagValues() }).distinct(),
        )
    }

    suspend fun fetch(
        noteId: String,
        kinds: List<Int>,
        limit: Int,
    ): FeedResponse {
        val events = fetchEvents(noteId = noteId, kinds = kinds, limit = limit)
        return events.all.toThreadFeedResponse(
            metadata = fetchMetadataFor(events),
            referencedEvents = events.referencedNotes.map { it.asReferencedPrimalEvent() },
        )
    }

    /**
     * Requests only the ids the database does not already hold. Nostr events are immutable and
     * content-addressed, so a stored id is the same event and re-requesting it buys nothing —
     * and a thread's ancestors are usually already there, put by the feed.
     */
    private suspend fun queryByIds(rawIds: List<String>): List<NostrEvent> {
        if (rawIds.isEmpty()) return emptyList()
        val cached = cache?.partitionKnownEventIds(rawIds)
        val ids = cached?.missing ?: rawIds
        val known = cached?.known.orEmpty()
        if (ids.isEmpty()) return known
        return known + coroutineScope {
            ids.chunked(ID_CHUNK).map { chunk ->
                async {
                    runCatching {
                        querier.query(RelayFilter(ids = chunk, limit = chunk.size))
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun queryEventTags(
        eventTags: List<String>,
        kinds: List<Int>,
        limit: Int,
    ): List<NostrEvent> {
        if (eventTags.isEmpty()) return emptyList()
        return coroutineScope {
            eventTags.chunked(ID_CHUNK).map { chunk ->
                async {
                    runCatching {
                        querier.query(RelayFilter(kinds = kinds, eventTags = chunk, limit = limit))
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
    }

    /**
     * The root of the conversation [this] belongs to: the NIP-22 root scope for a comment, the
     * NIP-10 root (or, failing that, the note it replies to) for anything else. Null for a note that
     * names neither, which is then its own root.
     */
    private fun NostrEvent?.threadRootId(): String? =
        when {
            this == null -> null
            isComment() -> tags.findCommentRootEventId() ?: tags.findCommentParentEventId()
            else -> tags.findRootEventId() ?: tags.findReplyTargetId()
        }

    /**
     * Every NIP-22 comment under a root, at any depth: all of them carry the root in `E`, or in `A`
     * when the root is an addressable event. Skipped when the caller did not ask for comments.
     */
    private suspend fun queryComments(
        rootEventId: String,
        rootAddress: String?,
        kinds: List<Int>,
        limit: Int,
    ): List<NostrEvent> {
        if (NostrEventKind.Comment.value !in kinds) return emptyList()
        val commentKind = listOf(NostrEventKind.Comment.value)
        return coroutineScope {
            val byEvent = async {
                runCatching {
                    querier.query(RelayFilter(kinds = commentKind, rootEventTags = listOf(rootEventId), limit = limit))
                }.getOrDefault(emptyList())
            }
            val byAddress = async {
                if (rootAddress == null) {
                    emptyList()
                } else {
                    runCatching {
                        querier.query(
                            RelayFilter(kinds = commentKind, rootAddressTags = listOf(rootAddress), limit = limit),
                        )
                    }.getOrDefault(emptyList())
                }
            }
            byEvent.await() + byAddress.await()
        }
    }

    private fun missingParentIds(events: List<NostrEvent>): List<String> {
        val known = events.map { it.id }.toSet()
        return events.flatMap { it.tags.eventIdTagValues() + listOfNotNull(it.tags.findCommentRootEventId()) }
            .filter { it !in known }
            .distinct()
            .take(MISSING_CAP)
    }

    private suspend fun loadMetadata(rawPubkeys: List<String>): List<NostrEvent> {
        if (rawPubkeys.isEmpty()) return emptyList()
        // Asked once per session rather than on every thread; profiles change, so this is not
        // cached permanently.
        val pubkeys = cache?.claimMetadataPubkeys(rawPubkeys) ?: rawPubkeys
        if (pubkeys.isEmpty()) return emptyList()
        var metadata: List<NostrEvent> = emptyList()
        try {
            metadata = coroutineScope {
                pubkeys.chunked(AUTHOR_CHUNK).map { chunk ->
                    async {
                        runCatching {
                            querier.query(
                                RelayFilter(
                                    kinds = listOf(NostrEventKind.Metadata.value),
                                    authors = chunk,
                                    limit = chunk.size,
                                ),
                            )
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten().latestMetadataByPubkey()
            }
        } finally {
            // A claim that is kept after coming back empty — or after the load was cancelled
            // mid-request, by leaving the screen or pulling to refresh — is how an author stays a
            // raw npub for the rest of the session: nothing ever asks again.
            withContext(NonCancellable) {
                cache?.releaseMetadataPubkeys(pubkeys - metadata.map { it.pubKey }.toSet())
            }
        }
        return metadata
    }

    companion object {
        private const val AUTHOR_CHUNK = 100
        private const val ID_CHUNK = 50
        private const val MISSING_CAP = 50
    }
}

internal fun List<NostrEvent>.toThreadFeedResponse(
    metadata: List<NostrEvent>,
    referencedEvents: List<PrimalEvent> = emptyList(),
): FeedResponse {
    return FeedResponse(
        paging = null,
        metadata = metadata,
        notes = filter { it.kind == NostrEventKind.ShortTextNote.value || it.isComment() },
        articles = emptyList(),
        reposts = filter { it.kind == NostrEventKind.ShortTextNoteRepost.value },
        zaps = filter { it.kind == NostrEventKind.Zap.value },
        referencedEvents = referencedEvents,
        primalEventStats = emptyList(),
        primalEventUserStats = emptyList(),
        cdnResources = emptyList(),
        primalLinkPreviews = emptyList(),
        primalRelayHints = emptyList(),
        blossomServers = emptyList(),
        pictureNotes = filter { it.kind == NostrEventKind.PictureNote.value },
        polls = filter {
            it.kind == NostrEventKind.Poll.value || it.kind == NostrEventKind.ZapPoll.value
        },
        pollResponses = filter { it.kind == NostrEventKind.PollResponse.value },
    )
}
