package net.primal.domain.wot

import kotlinx.coroutines.flow.Flow

/**
 * A local, opt-in alternative to trusting relays (or a mega-relay) to keep spam out of the
 * following feed: an account is judged trustworthy enough to show up there when the user follows
 * it directly, or when enough of the people the user already follows also follow it. Nothing here
 * calls out to any third-party scoring service; the whole computation is a local reading of
 * publicly available follow lists (NIP-02, kind 3), the same data every client already fetches.
 *
 * The idea and the threshold below are the client "wisp"'s (MIT-licensed); this is our own
 * implementation of the same approach, adapted to this app's own relay and storage layers.
 *
 * The following/chronological feed applies it at [FEED_MIN_FOLLOWERS]; hiding strangers' replies
 * and mentions lets the user choose the width. A feed whose whole purpose is discovering strangers
 * (explore, hashtags, trending) does not: filtering by "do I already sort of know them" would
 * defeat the point of it.
 */
interface WebOfTrustRepository {

    /** Whether the user has turned the filter on. Independent of whether a network has been computed. */
    fun observeFilterEnabled(ownerId: String): Flow<Boolean>

    suspend fun setFilterEnabled(ownerId: String, enabled: Boolean)

    /** What the last (or in-progress) network computation looks like, for the settings screen. */
    fun observeDiscoveryState(ownerId: String): Flow<WotDiscoveryState>

    /**
     * Whether the feed query should actually restrict itself right now: the toggle is on AND a
     * network has actually been computed at least once. The second half matters on its own: without
     * it, turning the toggle on would hide every note in the feed until the first computation
     * finishes, rather than showing everything, unfiltered, until it does.
     */
    fun observeFilteringActive(ownerId: String): Flow<Boolean>

    /**
     * The accounts in [ownerId]'s network as last computed, direct follows included: those followed
     * by at least [minFollowers] of the owner's follows. Empty if it was never computed.
     */
    fun observeQualifiedPubkeys(ownerId: String, minFollowers: Int): Flow<Set<String>>

    /** How many accounts [observeQualifiedPubkeys] would hold, without loading them. */
    fun observeNetworkSize(ownerId: String, minFollowers: Int): Flow<Int>

    /** When the network was last computed, in epoch seconds; 0 if never. */
    fun observeComputedAt(ownerId: String): Flow<Long>

    /**
     * (Re)computes [ownerId]'s network from the relays and replaces whatever was cached before.
     *
     * [firstDegreeFollows] is supplied by the caller (already-known, already-loaded account data)
     * rather than fetched again here, so this stays a pure "network discovery" operation with no
     * opinion on how a follow list is loaded or kept warm elsewhere in the app.
     *
     * When no relay answers at all, the network computed before is kept and the state is Failed.
     */
    suspend fun refreshNetwork(ownerId: String, firstDegreeFollows: Set<String>)

    companion object {
        /** How many of the owner's follows must follow an account for the feed filter to trust it. */
        const val FEED_MIN_FOLLOWERS = 10
    }
}
