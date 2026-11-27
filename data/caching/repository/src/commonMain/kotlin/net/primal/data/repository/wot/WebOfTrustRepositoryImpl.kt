package net.primal.data.repository.wot

import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.runCatching
import net.primal.data.local.dao.wot.WotNetworkStateData
import net.primal.data.local.dao.wot.WotQualifiedPubkeyData
import net.primal.data.local.db.CachingDatabase
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.nostr.utils.isValidHex
import net.primal.domain.wot.WebOfTrustRepository
import net.primal.domain.wot.WotDiscoveryState
import net.primal.shared.data.local.db.withTransaction

/**
 * See [WebOfTrustRepository]'s own doc for the idea. The algorithm, in full:
 *
 * 1. For every pubkey the owner follows, fetch that pubkey's own kind 3 (follow list) — the
 *    "second degree". A kind 3 event is a NIP-01 *replaceable* event, so a spec-compliant relay
 *    answers with at most one per author regardless of how many times they have ever published
 *    one; [dedupeLatestPerAuthor] is only a defence against a relay that does not honor that.
 * 2. Count, for every pubkey that shows up in any of those lists, how many of the owner's own
 *    follows follow them.
 * 3. Every pubkey counted at least once is stored with its count, and each use picks the count it
 *    asks for (the feed: [WebOfTrustRepository.FEED_MIN_FOLLOWERS]). The owner's own direct
 *    follows are stored as in every network regardless of this count — the whole reason a repost
 *    from a stranger needs checking at all is that its *author* was never followed directly, only
 *    its reposter was.
 *
 * The result is cached, not recomputed on every feed load: it is the caller's job to call
 * [refreshNetwork] again (on a schedule, or from a "refresh" button), not this repository's.
 */
internal class WebOfTrustRepositoryImpl(
    private val dispatcherProvider: DispatcherProvider,
    private val database: CachingDatabase,
    private val relayEventQuerier: RelayEventQuerier,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) : WebOfTrustRepository {

    // Per-owner, in-memory only: what a refresh in progress is doing right now. Not persisted —
    // a progress percentage from a run that got killed mid-way is not a useful thing to show again
    // on the next app start, unlike the completed result in WotNetworkStateData, which is.
    private val discoveryState = MutableStateFlow<Map<String, WotDiscoveryState>>(emptyMap())

    override fun observeFilterEnabled(ownerId: String): Flow<Boolean> =
        database.webOfTrust().observeState(ownerId).map { it?.filterEnabled == true }

    override suspend fun setFilterEnabled(ownerId: String, enabled: Boolean) =
        withContext(dispatcherProvider.io()) {
            val current = database.webOfTrust().getState(ownerId) ?: emptyState(ownerId)
            database.webOfTrust().upsertState(current.copy(filterEnabled = enabled))
        }

    override fun observeDiscoveryState(ownerId: String): Flow<WotDiscoveryState> =
        combine(discoveryState, database.webOfTrust().observeState(ownerId)) { inThisRun, stored ->
            // Nothing computed since the app started: say what is on disk, not that there is nothing.
            inThisRun[ownerId] ?: stored.asDiscoveryState()
        }.distinctUntilChanged()

    private fun WotNetworkStateData?.asDiscoveryState(): WotDiscoveryState =
        if (this == null || computedAtSeconds == 0L) {
            WotDiscoveryState.Idle
        } else {
            WotDiscoveryState.Complete(
                firstDegreeCount = firstDegreeCount,
                qualifiedCount = qualifiedCount,
                computedAtSeconds = computedAtSeconds,
            )
        }

    override fun observeFilteringActive(ownerId: String): Flow<Boolean> =
        database.webOfTrust().observeState(ownerId).map { it != null && it.filterEnabled && it.computedAtSeconds > 0 }

    override fun observeQualifiedPubkeys(ownerId: String, minFollowers: Int): Flow<Set<String>> =
        database.webOfTrust().observeQualifiedPubkeys(ownerId, minFollowers).map { it.toSet() }

    override fun observeNetworkSize(ownerId: String, minFollowers: Int): Flow<Int> =
        database.webOfTrust().observeNetworkSize(ownerId, minFollowers).distinctUntilChanged()

    override fun observeComputedAt(ownerId: String): Flow<Long> =
        database.webOfTrust().observeState(ownerId).map { it?.computedAtSeconds ?: 0 }.distinctUntilChanged()

    override suspend fun refreshNetwork(ownerId: String, firstDegreeFollows: Set<String>) {
        withContext(dispatcherProvider.io()) {
            if (firstDegreeFollows.isEmpty()) {
                setDiscoveryState(ownerId, WotDiscoveryState.Failed(reason = "not following anyone yet"))
                return@withContext
            }

            val totalFollows = firstDegreeFollows.size
            fun reportProgress(fetchedFollowLists: Int) {
                setDiscoveryState(ownerId, WotDiscoveryState.Discovering(fetchedFollowLists, totalFollows))
            }

            reportProgress(fetchedFollowLists = 0)
            val secondDegreeCounts = mutableMapOf<String, Int>()
            var fetched = 0
            var answered = false
            for (chunk in firstDegreeFollows.chunked(FOLLOW_LIST_CHUNK_SIZE)) {
                val events = fetchFollowLists(authors = chunk)
                if (events != null) answered = true
                for (event in dedupeLatestPerAuthor(events.orEmpty())) {
                    for (followed in event.followingPubkeys()) {
                        secondDegreeCounts[followed] = (secondDegreeCounts[followed] ?: 0) + 1
                    }
                }
                fetched += chunk.size
                reportProgress(fetchedFollowLists = fetched)
            }

            // Offline, or every relay down: nothing was learned, and writing that down would shrink
            // the network to the direct follows until somebody noticed.
            if (!answered) {
                setDiscoveryState(ownerId, WotDiscoveryState.Failed(reason = "no relay answered"))
                return@withContext
            }

            val qualifiedCount =
                secondDegreeCounts.count { it.value >= WebOfTrustRepository.FEED_MIN_FOLLOWERS }
            val network = secondDegreeCounts + firstDegreeFollows.associateWith { Int.MAX_VALUE }
            val computedAt = nowSeconds()

            database.withTransaction {
                database.webOfTrust().deleteQualifiedPubkeys(ownerId = ownerId)
                database.webOfTrust().insertQualifiedPubkeys(
                    data = network.map { (pubkey, followers) ->
                        WotQualifiedPubkeyData(ownerId = ownerId, pubkey = pubkey, followers = followers)
                    },
                )
                val existing = database.webOfTrust().getState(ownerId) ?: emptyState(ownerId)
                database.webOfTrust().upsertState(
                    existing.copy(
                        computedAtSeconds = computedAt,
                        firstDegreeCount = firstDegreeFollows.size,
                        qualifiedCount = qualifiedCount,
                    ),
                )
            }

            setDiscoveryState(
                ownerId,
                WotDiscoveryState.Complete(
                    firstDegreeCount = firstDegreeFollows.size,
                    qualifiedCount = qualifiedCount,
                    computedAtSeconds = computedAt,
                ),
            )
        }
    }

    /**
     * One dedicated request per chunk, kept deliberately small: a kind 3 event carries an author's
     * *whole* contact list and has been observed at ~240 KB for a single account, so a batch of
     * many authors' events landing in one relay response is real memory pressure, not a
     * theoretical one — this is exactly the shape of query that caused a past OOM crash when kinds
     * were mixed and the limit was left generous. Never combined with any other kind, and the
     * limit matches the chunk size (one relay is meant to answer with one event per author anyway).
     *
     * Null when no relay answered for this chunk.
     *
     * A relay being unreachable for one chunk should not fail the whole discovery — it just makes
     * that chunk's follows contribute nothing to anyone's count, the same as if they had no
     * followers at all, which is the safe direction for a trust computation to fail in.
     */
    private suspend fun fetchFollowLists(authors: List<String>): List<NostrEvent>? =
        runCatching {
            relayEventQuerier.queryWithOutcome(
                RelayFilter(
                    kinds = listOf(NostrEventKind.FollowList.value),
                    authors = authors,
                    limit = authors.size,
                ),
            )
        }.getOrNull()?.takeIf { it.answered }?.events

    private fun dedupeLatestPerAuthor(events: List<NostrEvent>): Collection<NostrEvent> =
        events.groupBy { it.pubKey }.mapNotNull { (_, revisions) -> revisions.maxByOrNull { it.createdAt } }

    private fun NostrEvent.followingPubkeys(): Set<String> =
        tags.mapNotNull { tag ->
            if (tag.getOrNull(0)?.jsonPrimitive?.content != "p") return@mapNotNull null
            tag.getOrNull(1)?.jsonPrimitive?.content?.takeIf { it.isValidHex() }
        }.toSet()

    private fun emptyState(ownerId: String) =
        WotNetworkStateData(
            ownerId = ownerId,
            filterEnabled = false,
            computedAtSeconds = 0,
            firstDegreeCount = 0,
            qualifiedCount = 0,
        )

    private fun setDiscoveryState(ownerId: String, state: WotDiscoveryState) {
        discoveryState.value = discoveryState.value + (ownerId to state)
    }

    private companion object {
        /** See [fetchFollowLists]'s own doc for why this stays small. */
        const val FOLLOW_LIST_CHUNK_SIZE = 50
    }
}
