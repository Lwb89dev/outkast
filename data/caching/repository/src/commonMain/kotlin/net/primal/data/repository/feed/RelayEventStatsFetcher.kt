package net.primal.data.repository.feed

import net.primal.data.local.dao.events.EventStats
import net.primal.data.local.dao.events.EventUserStats
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.eventIdTagValues
import net.primal.domain.nostr.findFirstZapAmount
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
                (event.tags.eventIdTagValues() + listOf(event).referencedNoteIds())
                    .filter { it in ids }
                    .distinct()
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
                reposts = events.count { it.kind == NostrEventKind.ShortTextNoteRepost.value }.toLong(),
                zaps = events.count { it.kind == NostrEventKind.Zap.value }.toLong(),
                satsZapped = events
                    .filter { it.kind == NostrEventKind.Zap.value }
                    .sumOf { it.tags.findFirstZapAmount()?.toLongOrNull()?.toSats() ?: 0L },
            )
        }

        val userStats = ids.mapNotNull { eventId ->
            val events = eventsByTarget[eventId].orEmpty().filter { it.pubKey == userId }
            if (events.isEmpty()) return@mapNotNull null
            EventUserStats(
                eventId = eventId,
                userId = userId,
                replied = events.any { it.isReplyTo(eventId) },
                liked = events.any { it.kind == NostrEventKind.Reaction.value },
                reposted = events.any { it.kind == NostrEventKind.ShortTextNoteRepost.value },
                zapped = events.any { it.kind == NostrEventKind.Zap.value },
            )
        }
        return RelayEventStatsSnapshot(eventStats = eventStats, userStats = userStats)
    }

    /**
     * A text note that answers [eventId] rather than quoting it: it names it as its parent. A reply
     * that happens to embed the note it answers is still a reply, and counts once.
     */
    private fun NostrEvent.isReplyTo(eventId: String): Boolean =
        kind == NostrEventKind.ShortTextNote.value && eventId in tags.eventIdTagValues() && !isQuoteOf(eventId)

    private fun Long.toSats(): Long = if (this >= 1_000L) this / 1_000L else this
}
