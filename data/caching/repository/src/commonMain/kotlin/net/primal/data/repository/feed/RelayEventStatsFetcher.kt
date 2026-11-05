package net.primal.data.repository.feed

import net.primal.data.local.dao.events.EventStats
import net.primal.data.local.dao.events.EventUserStats
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.eventIdTagValues
import net.primal.domain.nostr.findFirstZapAmount
import net.primal.domain.nostr.getTagValueOrNull
import net.primal.domain.nostr.isUppercaseEventIdTag
import net.primal.domain.nostr.relay.RelayEventQuerier

internal data class RelayEventStatsSnapshot(
    val eventStats: List<EventStats>,
    val userStats: List<EventUserStats>,
)

/** Derives note counters from the interaction events published on relays. */
internal class RelayEventStatsFetcher(
    private val querier: RelayEventQuerier,
    private val coordinator: FetchCoordinator,
) {

    suspend fun fetch(eventIds: List<String>, userId: String): RelayEventStatsSnapshot {
        val ids = eventIds.distinct()
        if (ids.isEmpty()) return RelayEventStatsSnapshot(emptyList(), emptyList())

        // Built fresh for every page, so it used to re-ask for the counters of a note the moment
        // it appeared in a second feed. The coordinator shares that per note.
        val interactions = coordinator.fetchEventInteractions(querier = querier, eventIds = ids)

        val eventsByTarget = interactions
            .flatMap { event ->
                event.statTargetIds()
                    .filter { it in ids }
                    .map { it to event }
            }
            .groupBy({ it.first }, { it.second })

        val eventStats = ids.map { eventId ->
            val events = eventsByTarget[eventId].orEmpty()
            EventStats(
                eventId = eventId,
                likes = events.count { it.kind == NostrEventKind.Reaction.value }.toLong(),
                replies = events.count { it.isReplyTo(eventId) }.toLong(),
                // The one counter this table had no use for until now: how many notes quote this one.
                mentions = events.count { it.isQuoteOf(eventId) }.toLong(),
                reposts = events.count { it.isRepost() }.toLong(),
                zaps = events.count { it.kind == NostrEventKind.Zap.value }.toLong(),
                satsZapped = events
                    .filter { it.kind == NostrEventKind.Zap.value }
                    .sumOf { it.tags.findFirstZapAmount()?.toLongOrNull()?.toSats() ?: 0L },
            )
        }

        val userStats = ids.mapNotNull { eventId ->
            val events = eventsByTarget[eventId].orEmpty().filter { it.pubKey == userId }
            if (events.isEmpty()) return@mapNotNull null
            // The user's newest reaction is the one that stands (an emoji replaces the previous one),
            // and its content is what the note should show in place of the heart.
            val ownReaction = events
                .filter { it.kind == NostrEventKind.Reaction.value }
                .maxByOrNull { it.createdAt }
            EventUserStats(
                eventId = eventId,
                userId = userId,
                replied = events.any { it.isReplyTo(eventId) },
                liked = ownReaction != null,
                reaction = ownReaction?.content,
                reposted = events.any { it.isRepost() },
                zapped = events.any { it.kind == NostrEventKind.Zap.value },
            )
        }
        return RelayEventStatsSnapshot(eventStats = eventStats, userStats = userStats)
    }

    /**
     * A text note or NIP-22 comment that answers [eventId] rather than quoting it. A top-level
     * comment names the root in both `e` and `E` and still counts once. A nested comment counts
     * on the root through `E` and on its parent through `e`.
     */
    private fun NostrEvent.isReplyTo(eventId: String): Boolean {
        if (isQuoteOf(eventId)) return false
        return when (kind) {
            NostrEventKind.ShortTextNote.value -> eventId in tags.eventIdTagValues()
            NostrEventKind.Comment.value -> eventId in commentScopeIds()
            else -> false
        }
    }

    /** Lowercase `e`, plus a comment's NIP-22 root `E`. The same id from both tags counts once. */
    private fun NostrEvent.statTargetIds(): List<String> {
        val tagged = if (kind == NostrEventKind.Comment.value) commentScopeIds() else tags.eventIdTagValues()
        return (tagged + listOf(this).referencedNoteIds()).distinct()
    }

    private fun NostrEvent.commentScopeIds(): List<String> {
        val rootIds = tags.mapNotNull { tag ->
            tag.takeIf { it.isUppercaseEventIdTag() }?.getTagValueOrNull()
        }
        return (tags.eventIdTagValues() + rootIds).distinct()
    }

    private fun NostrEvent.isRepost(): Boolean =
        kind == NostrEventKind.ShortTextNoteRepost.value || kind == NostrEventKind.GenericRepost.value

    private fun Long.toSats(): Long = if (this >= 1_000L) this / 1_000L else this
}
