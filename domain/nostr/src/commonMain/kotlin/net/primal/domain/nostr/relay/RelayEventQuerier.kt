package net.primal.domain.nostr.relay

import net.primal.domain.nostr.NostrEvent

interface RelayEventQuerier {
    suspend fun query(filter: RelayFilter): List<NostrEvent>

    /**
     * Same query as [query], but it also says whether any relay actually answered.
     *
     * [query] returns an empty list both when the relays have nothing to give and when none of
     * them could be asked at all (no connection, every relay backing off, every request timing
     * out). Code that walks backwards through history needs to tell the two apart: the first means
     * the history has ended, the second means nothing has been learned and the same question
     * should simply be asked again later. Treating a failure as the end of the history used to
     * stop a feed from loading older notes for good after one dropped connection.
     *
     * The default assumes the relays answered, which is what [query] implicitly claims, so
     * implementations that cannot tell keep their current behaviour.
     */
    suspend fun queryWithOutcome(filter: RelayFilter): RelayQueryOutcome =
        RelayQueryOutcome(events = query(filter), answered = true)
}

/**
 * What a relay query produced.
 *
 * @property answered true when at least one relay finished the query (sent EOSE) or delivered an
 *   event. False means every relay failed or timed out, so an empty [events] says nothing about
 *   what the relays hold.
 */
data class RelayQueryOutcome(
    val events: List<NostrEvent>,
    val answered: Boolean,
)
