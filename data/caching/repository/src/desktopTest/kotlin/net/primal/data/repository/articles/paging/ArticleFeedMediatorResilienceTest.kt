package net.primal.data.repository.articles.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.dao.notes.FeedPostRemoteKey
import net.primal.data.local.dao.reads.ArticleFeedCrossRef
import net.primal.data.local.dao.reads.ArticleFeedItem
import net.primal.data.local.db.CachingDatabase
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.nostr.relay.RelayQueryOutcome
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * The Reads feed's two ways of quietly losing its content: a refresh that reached nobody replaced
 * the cached articles with nothing, and two scrolls within ten seconds ended the list for good.
 * Plus the request that made the first one likely: the whole follow list in a single `authors`
 * array, which relays with a size limit refuse.
 */
@ExperimentalPagingApi
class ArticleFeedMediatorResilienceTest {

    @Test
    fun `a refresh that reached no relay keeps the cached articles`() =
        withMediator { mediator, relay ->
            relay.reachable = false

            val result = mediator.load(LoadType.REFRESH, emptyState())

            assertEquals(false, result.endOfPaginationReachedOrFail())
            assertCachedFeedIntact()
        }

    @Test
    fun `a refresh the relays answered with no articles keeps the cached articles`() =
        withMediator { mediator, relay ->
            relay.events = followListOf(listOf(AUTHOR))

            mediator.load(LoadType.REFRESH, emptyState())

            assertCachedFeedIntact()
        }

    @Test
    fun `a refresh that finds articles replaces the cached ones`() =
        withMediator { mediator, relay ->
            relay.events = followListOf(listOf(AUTHOR)) + article(id = "fresh", createdAt = NEWEST)

            mediator.load(LoadType.REFRESH, emptyState())

            val newest = database.articleFeedsConnections().findLastBySpec(ownerId = USER, spec = SPEC)
            assertNotNull(newest)
            assertTrue(newest.articleATag != SEED_A_TAG, "the cached article should have been replaced")
        }

    @Test
    fun `two scrolls in quick succession do not end the list`() =
        withMediator { mediator, relay ->
            // Older articles do not exist, so nothing is stored and the second call asks for the
            // exact boundary of the first. That used to be answered with end-of-pagination, which
            // Paging never reverses.
            relay.events = followListOf(listOf(AUTHOR))

            val first = mediator.load(LoadType.APPEND, emptyState())
            val second = mediator.load(LoadType.APPEND, emptyState())

            assertEquals(false, first.endOfPaginationReachedOrFail())
            assertEquals(false, second.endOfPaginationReachedOrFail())
        }

    @Test
    fun `an APPEND that reaches no relay does not end the list`() =
        withMediator { mediator, relay ->
            relay.reachable = false

            repeat(3) {
                assertEquals(false, mediator.load(LoadType.APPEND, emptyState()).endOfPaginationReachedOrFail())
            }
        }

    @Test
    fun `a follow list of hundreds is asked about in chunks relays accept`() =
        withMediator { mediator, relay ->
            val follows = (0 until 600).map { "follow-$it" }
            relay.events = followListOf(follows) +
                article(id = "far-away", createdAt = NEWEST, author = "follow-550")

            mediator.load(LoadType.REFRESH, emptyState())

            val articleQueries = relay.filters.filter { it.kinds == listOf(NostrEventKind.LongFormContent.value) }
            assertTrue(articleQueries.isNotEmpty())
            assertTrue(
                articleQueries.all { (it.authors?.size ?: 0) <= MAX_AUTHORS_PER_REQUEST },
                "an article query carried ${articleQueries.maxOf { it.authors?.size ?: 0 }} authors in one filter",
            )
            val stored = database.articleFeedsConnections().findLastBySpec(ownerId = USER, spec = SPEC)
            assertEquals("30023:follow-550:far-away", stored?.articleATag)
        }

    // ------------------------------------------------------------------------------- harness

    private class SimulatedRelay : RelayEventQuerier {
        var events: List<NostrEvent> = emptyList()
        var reachable = true
        val filters = mutableListOf<RelayFilter>()

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

    private suspend fun assertCachedFeedIntact() {
        val cached = database.articleFeedsConnections().findLastBySpec(ownerId = USER, spec = SPEC)
        assertEquals(SEED_A_TAG, cached?.articleATag, "the cached feed was wiped")
    }

    private fun article(id: String, createdAt: Long, author: String = AUTHOR) =
        NostrEvent(
            id = "event-$id",
            pubKey = author,
            createdAt = createdAt,
            kind = NostrEventKind.LongFormContent.value,
            tags = listOf(tag("d", id), tag("title", "Title of $id")),
            content = "# $id",
            sig = "sig",
        )

    private fun followListOf(follows: List<String>) =
        listOf(
            NostrEvent(
                id = "follow-list",
                pubKey = USER,
                createdAt = 1L,
                kind = NostrEventKind.FollowList.value,
                tags = follows.map { tag("p", it) },
                content = "",
                sig = "sig",
            ),
        )

    private fun tag(name: String, value: String): JsonArray =
        buildJsonArray {
            add(JsonPrimitive(name))
            add(JsonPrimitive(value))
        }

    private fun emptyState() =
        PagingState<Int, ArticleFeedItem>(
            pages = emptyList(),
            anchorPosition = null,
            config = PagingConfig(pageSize = 20),
            leadingPlaceholderCount = 0,
        )

    private fun RemoteMediator.MediatorResult.endOfPaginationReachedOrFail(): Boolean =
        when (this) {
            is RemoteMediator.MediatorResult.Success -> endOfPaginationReached
            else -> fail("expected a Success result, got $this")
        }

    private fun withMediator(block: suspend (ArticleFeedMediator, SimulatedRelay) -> Unit) =
        runBlocking {
            val databaseName = "primal_article_mediator_${counter++}.db"
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
            try {
                database.articleFeedsConnections().connect(
                    data = listOf(
                        ArticleFeedCrossRef(
                            ownerId = USER,
                            spec = SPEC,
                            articleATag = SEED_A_TAG,
                            articleAuthorId = AUTHOR,
                        ),
                    ),
                )
                database.feedPostsRemoteKeys().upsert(
                    data = listOf(
                        FeedPostRemoteKey(
                            ownerId = USER,
                            eventId = SEED_A_TAG,
                            directive = SPEC,
                            sinceId = SEED_UNTIL,
                            untilId = SEED_UNTIL,
                            cachedAt = 1_700_000_000L,
                        ),
                    ),
                )

                val relay = SimulatedRelay()
                // A real dispatcher on purpose: the follow-list fetch waits 350 ms between attempts
                // when it comes back empty, and a test dispatcher's virtual clock never advances here.
                @Suppress("InjectDispatcher")
                val dispatcher = Dispatchers.Default
                val mediator = ArticleFeedMediator(
                    userId = USER,
                    feedSpec = SPEC,
                    database = database,
                    dispatcherProvider = mockk<DispatcherProvider> {
                        every { io() } returns dispatcher
                        every { main() } returns dispatcher
                    },
                    relayEventQuerier = relay,
                    fetchCoordinator = FetchCoordinator(
                        dispatcherProvider = mockk { every { io() } returns dispatcher },
                    ),
                )
                block(mediator, relay)
            } finally {
                database.close()
                LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            }
        }

    private companion object {
        const val USER = "user"
        const val AUTHOR = "author"
        const val SPEC = """{"id":"latest","kind":"reads"}"""
        const val SEED_A_TAG = "30023:author:seed-article"
        const val SEED_UNTIL = 1_700_000_000L
        const val NEWEST = 1_700_500_000L
        const val MAX_AUTHORS_PER_REQUEST = 250

        var counter = 0
    }
}
