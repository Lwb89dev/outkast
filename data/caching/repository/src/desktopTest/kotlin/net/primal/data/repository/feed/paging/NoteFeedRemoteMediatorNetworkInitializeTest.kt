package net.primal.data.repository.feed.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.RemoteMediator.InitializeAction
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.local.dao.notes.FeedPostDataCrossRef
import net.primal.data.local.dao.notes.FeedPostRemoteKey
import net.primal.data.local.db.CachingDatabase
import net.primal.data.repository.cache.LocalEventCache
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.feeds.networkNotesFeedSpec
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * What the network feed shows when it is opened. It is a ranking rebuilt from the relays, and it used to be cleared on
 * every open, which left an empty screen for as long as the rebuild took.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExperimentalPagingApi
class NoteFeedRemoteMediatorNetworkInitializeTest {

    @Test
    fun `a network feed that was never built is built`() =
        withMediator(builtSecondsAgo = null) { mediator, _ ->
            assertEquals(InitializeAction.LAUNCH_INITIAL_REFRESH, mediator.initialize())
        }

    @Test
    fun `a page built a couple of minutes ago is shown as it is`() =
        withMediator(builtSecondsAgo = 120) { mediator, database ->
            assertEquals(InitializeAction.SKIP_INITIAL_REFRESH, mediator.initialize())
            assertEquals(1, database.storedNotes())
        }

    @Test
    fun `an older page is rebuilt without being cleared first, so it stays on screen until the new one arrives`() =
        withMediator(builtSecondsAgo = 30 * 60) { mediator, database ->
            assertEquals(InitializeAction.LAUNCH_INITIAL_REFRESH, mediator.initialize())
            assertEquals(1, database.storedNotes(), "the old page was cleared before the new one was ready")
        }

    // ------------------------------------------------------------------------------- harness

    private suspend fun CachingDatabase.storedNotes(): Int =
        feedsConnections().findLastBySpec(ownerId = USER_ID, spec = networkNotesFeedSpec)?.let { 1 } ?: 0

    private fun withMediator(
        builtSecondsAgo: Long?,
        block: suspend (NoteFeedRemoteMediator, CachingDatabase) -> Unit,
    ) = runBlocking {
        val databaseName = "primal_network_initialize_${counter++}.db"
        LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
        val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
        try {
            if (builtSecondsAgo != null) {
                database.feedsConnections().connect(
                    data = listOf(
                        FeedPostDataCrossRef(ownerId = USER_ID, feedSpec = networkNotesFeedSpec, eventId = EVENT_ID),
                    ),
                )
                database.feedPostsRemoteKeys().upsert(
                    data = listOf(
                        FeedPostRemoteKey(
                            ownerId = USER_ID,
                            eventId = EVENT_ID,
                            directive = networkNotesFeedSpec,
                            sinceId = 1L,
                            untilId = 1L,
                            cachedAt = Clock.System.now().epochSeconds - builtSecondsAgo,
                        ),
                    ),
                )
            }
            val dispatcher = UnconfinedTestDispatcher()
            val mediator = NoteFeedRemoteMediator(
                dispatcherProvider = mockk<DispatcherProvider> {
                    every { io() } returns dispatcher
                    every { main() } returns dispatcher
                },
                feedSpec = networkNotesFeedSpec,
                userId = USER_ID,
                feedApi = mockk(),
                database = database,
                invalidationTracker = FeedSpecInvalidationTracker(),
                relayEventQuerier = mockk<RelayEventQuerier>(),
                fetchCoordinator = FetchCoordinator(
                    dispatcherProvider = mockk { every { io() } returns dispatcher },
                ),
                localEventCache = LocalEventCache(database = database),
                unreachableRetryDelays = emptyList(),
            )
            block(mediator, database)
        } finally {
            database.close()
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
        }
    }

    private companion object {
        const val USER_ID = "user-pubkey"
        const val EVENT_ID = "ranked-note"

        var counter = 0
    }
}
