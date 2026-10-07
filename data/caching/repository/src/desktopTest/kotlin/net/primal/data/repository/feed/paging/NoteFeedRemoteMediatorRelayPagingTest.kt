package net.primal.data.repository.feed.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.dao.notes.FeedPost
import net.primal.data.local.dao.notes.FeedPostData
import net.primal.data.local.dao.notes.FeedPostDataCrossRef
import net.primal.data.local.dao.notes.FeedPostRemoteKey
import net.primal.data.local.db.CachingDatabase
import net.primal.data.repository.cache.LocalEventCache
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.nostr.relay.RelayQueryOutcome
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * The relay-served path of [NoteFeedRemoteMediator]'s APPEND direction, against a relay that
 * applies `limit` and `until` like a real one.
 *
 * What these pin: a connection that drops, or relays that are still backing off, must not use up
 * the few attempts the feed has before it gives up (the relays answered nothing, which is not the
 * same as having nothing older); and the cursor must keep moving through rows the list stored but
 * does not show, instead of being re-derived from the last row it does show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExperimentalPagingApi
class NoteFeedRemoteMediatorRelayPagingTest {

    @Test
    fun `an APPEND that reaches no relay never ends pagination`() =
        withMediator { mediator, relay ->
            relay.reachable = false

            repeat(8) { attempt ->
                val result = mediator.load(LoadType.APPEND, staleSeedState())
                assertEquals(false, result.endOfPaginationReachedOrFail(), "attempt ${attempt + 1} gave up")
            }
        }

    @Test
    fun `attempts that reached no relay do not count towards giving up`() =
        withMediator { mediator, relay ->
            relay.reachable = false
            repeat(5) { mediator.load(LoadType.APPEND, staleSeedState()) }

            // The connection is back and the relays hold nothing older: these are the real
            // strikes, and the feed still has all four of them.
            relay.reachable = true
            val answers = (1..4).map { mediator.load(LoadType.APPEND, staleSeedState()) }

            answers.dropLast(1).forEachIndexed { index, result ->
                assertEquals(false, result.endOfPaginationReachedOrFail(), "strike ${index + 1} of 4 gave up early")
            }
            assertEquals(true, answers.last().endOfPaginationReachedOrFail())
        }

    @Test
    fun `older notes load as soon as the connection is back`() =
        withMediator { mediator, relay ->
            relay.events = olderNotes(count = 5)
            relay.reachable = false
            repeat(2) { mediator.load(LoadType.APPEND, staleSeedState()) }

            relay.reachable = true
            mediator.load(LoadType.APPEND, staleSeedState())

            val stored = database.feedsConnections().findLastBySpec(ownerId = USER_ID, spec = FEED_SPEC)
            assertTrue(stored?.eventId?.startsWith("older-") == true, "the older notes were not stored: $stored")
        }

    @Test
    fun `a stretch of replies that the feed hides moves the cursor instead of using up an attempt`() =
        withMediator { mediator, relay ->
            relay.events = repliesOnly(count = 400)

            // Each call reads four windows of twenty replies and finds nothing to show. The old
            // mediator called every one of these an empty batch and gave up on the fourth.
            val results = (1..4).map { mediator.load(LoadType.APPEND, staleSeedState()) }

            results.forEachIndexed { index, result ->
                assertEquals(false, result.endOfPaginationReachedOrFail(), "call ${index + 1} gave up")
            }
            val untils = relay.noteQueryUntils()
            assertTrue(
                untils.zipWithNext().all { (earlier, later) -> later <= earlier },
                "the cursor must never move forward: $untils",
            )
            assertTrue(untils.last() < untils.first(), "the cursor never moved: $untils")
        }

    @Test
    fun `the next page starts behind what was just loaded even when the list shows none of it`() =
        withMediator { mediator, relay ->
            relay.events = olderNotes(count = 60)

            mediator.load(LoadType.APPEND, staleSeedState())
            // The list is still showing the row it showed before: whatever the first call stored
            // is hidden from it (muted, outside the web of trust), so the cursor derived from the
            // last visible row is the old one. The second request must not repeat the first, nor
            // jump a whole EMPTY_BATCH_STEP_BACK past notes nobody has seen.
            mediator.load(LoadType.APPEND, staleSeedState())

            val untils = relay.noteQueryUntils()
            assertEquals(2, untils.size)
            val oldestOfFirstPage = olderNotes(count = 60).take(PAGE_SIZE).minOf { it.createdAt }
            assertEquals(oldestOfFirstPage, untils[1], "the second request should start where the first page ended")
        }

    // ------------------------------------------------------------------------------- harness

    private class SimulatedRelay : RelayEventQuerier {
        var events: List<NostrEvent> = emptyList()
        var reachable = true
        private val filters = mutableListOf<RelayFilter>()

        /** `until` of every request for notes, in the order they were made. */
        fun noteQueryUntils(): List<Long> =
            filters.filter { it.kinds?.contains(1) == true && it.authors != null }.mapNotNull { it.until }

        override suspend fun query(filter: RelayFilter): List<NostrEvent> = queryWithOutcome(filter).events

        override suspend fun queryWithOutcome(filter: RelayFilter): RelayQueryOutcome {
            filters += filter
            if (!reachable) return RelayQueryOutcome(events = emptyList(), answered = false)
            val kinds = filter.kinds
            val authors = filter.authors
            val until = filter.until
            val matches = events
                .filter { kinds == null || it.kind in kinds }
                .filter { authors == null || it.pubKey in authors }
                .filter { until == null || it.createdAt <= until }
                .sortedByDescending { it.createdAt }
            return RelayQueryOutcome(events = matches.take(filter.limit ?: Int.MAX_VALUE), answered = true)
        }
    }

    private lateinit var database: CachingDatabase

    private fun olderNotes(count: Int): List<NostrEvent> =
        (1..count).map { index ->
            NostrEvent(
                id = "older-$index",
                pubKey = AUTHOR_ID,
                createdAt = SEED_UNTIL - index * 10L,
                kind = 1,
                tags = emptyList(),
                content = "older note $index",
                sig = "sig",
            )
        }

    private fun repliesOnly(count: Int): List<NostrEvent> =
        (1..count).map { index ->
            NostrEvent(
                id = "reply-$index",
                pubKey = AUTHOR_ID,
                createdAt = SEED_UNTIL - index * 10L,
                kind = 1,
                tags = listOf(eventTag("root-$index")),
                content = "reply $index",
                sig = "sig",
            )
        }

    private fun eventTag(id: String): JsonArray =
        buildJsonArray {
            add(JsonPrimitive("e"))
            add(JsonPrimitive(id))
        }

    /** The list as the user sees it: the seeded row is the last one shown, however much is stored. */
    private fun staleSeedState(): PagingState<Int, FeedPost> {
        val seed = FeedPost(
            data = FeedPostData(
                postId = SEED_EVENT_ID,
                authorId = AUTHOR_ID,
                createdAt = SEED_UNTIL,
                kind = 1,
                content = "seed",
                raw = "",
            ),
        )
        return PagingState(
            pages = listOf(PagingSource.LoadResult.Page(data = listOf(seed), prevKey = null, nextKey = null)),
            anchorPosition = 0,
            config = PagingConfig(pageSize = PAGE_SIZE),
            leadingPlaceholderCount = 0,
        )
    }

    private fun RemoteMediator.MediatorResult.endOfPaginationReachedOrFail(): Boolean =
        when (this) {
            is RemoteMediator.MediatorResult.Success -> endOfPaginationReached
            else -> fail("expected a Success result, got $this")
        }

    private fun withMediator(block: suspend (NoteFeedRemoteMediator, SimulatedRelay) -> Unit) =
        runBlocking {
            val databaseName = "primal_feed_relay_paging_${counter++}.db"
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
            try {
                database.feedsConnections().connect(
                    data = listOf(
                        FeedPostDataCrossRef(ownerId = USER_ID, feedSpec = FEED_SPEC, eventId = SEED_EVENT_ID),
                    ),
                )
                database.feedPostsRemoteKeys().upsert(
                    data = listOf(
                        FeedPostRemoteKey(
                            ownerId = USER_ID,
                            eventId = SEED_EVENT_ID,
                            directive = FEED_SPEC,
                            sinceId = SEED_UNTIL,
                            untilId = SEED_UNTIL,
                            cachedAt = 1_700_000_000L,
                        ),
                    ),
                )

                val relay = SimulatedRelay()
                val dispatcher = UnconfinedTestDispatcher()
                val mediator = NoteFeedRemoteMediator(
                    dispatcherProvider = mockk<DispatcherProvider> {
                        every { io() } returns dispatcher
                        every { main() } returns dispatcher
                    },
                    feedSpec = FEED_SPEC,
                    userId = USER_ID,
                    feedApi = mockk(),
                    database = database,
                    invalidationTracker = FeedSpecInvalidationTracker(),
                    relayEventQuerier = relay,
                    fetchCoordinator = FetchCoordinator(
                        dispatcherProvider = mockk { every { io() } returns dispatcher },
                    ),
                    localEventCache = LocalEventCache(database = database),
                    unreachableRetryDelays = emptyList(),
                )
                block(mediator, relay)
            } finally {
                database.close()
                LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            }
        }

    private companion object {
        const val USER_ID = "user-pubkey"
        // A valid 64-character pubkey: the spec is only recognised as relay-served with one.
        const val AUTHOR_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val FEED_SPEC = """{"id":"feed","kind":"notes","notes":"authored","pubkey":"$AUTHOR_ID"}"""
        const val SEED_EVENT_ID = "seed-note"
        const val SEED_UNTIL = 1_700_000_000L
        const val PAGE_SIZE = 20

        var counter = 0
    }
}
