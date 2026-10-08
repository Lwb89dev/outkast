package net.primal.data.repository.db

import androidx.paging.PagingSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.primal.data.local.dao.profiles.ProfileData
import net.primal.data.local.db.CachingDatabase
import net.primal.data.local.queries.ChronologicalFeedWithRepostsQueryBuilder
import net.primal.data.remote.api.feed.model.FeedResponse
import net.primal.data.repository.feed.paging.FeedSpecInvalidationTracker
import net.primal.data.repository.feed.processors.FeedProcessor
import net.primal.domain.nostr.NostrEvent
import net.primal.shared.data.local.db.LocalDatabaseFactory

/**
 * Every write that adds rows to a feed has to invalidate that feed's PagingSource, appended pages
 * included. The feed query does not observe `FeedPostDataCrossRef`, so the invalidation tracker is
 * the only way a live PagingSource hears about new rows; one that has already returned its last
 * local page never loads again otherwise.
 *
 * An earlier version of this test asserted the opposite, to stop a flicker that came from
 * placeholders (since turned off). The result was a feed that fetched older notes while scrolling
 * and never displayed them.
 */
class FeedProcessorInvalidationTest {

    @Test
    fun append_invalidatesThePagingSourceSoTheNewRowsAreLoaded() =
        withDatabase { database, tracker ->
            FeedProcessor(feedSpec = MAIN_SPEC, database = database, invalidationTracker = tracker)
                .processAndPersistToDatabase(userId = USER_ID, response = feedResponse(noteId = "note-1"), clearFeed = true)
            val pagingSource = tracker.track(
                ownerId = USER_ID,
                feedSpec = MAIN_SPEC,
                pagingSource = database.feedPosts().feedQuery(
                    query = ChronologicalFeedWithRepostsQueryBuilder(
                        feedSpec = MAIN_SPEC,
                        userPubkey = USER_ID,
                        allowMutedThreads = false,
                    ).feedQuery(),
                ),
            )
            val firstPage = pagingSource.load(
                PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false),
            ) as PagingSource.LoadResult.Page
            // The source has handed out everything it had: Paging will not ask it for more.
            assertEquals(1, firstPage.data.size)
            assertNull(firstPage.nextKey)

            FeedProcessor(feedSpec = MAIN_SPEC, database = database, invalidationTracker = tracker)
                .processAndPersistToDatabase(userId = USER_ID, response = feedResponse(noteId = "note-2"), clearFeed = false)

            assertTrue(pagingSource.invalid, "an appended page must invalidate the PagingSource or it is never shown")
        }

    @Test
    fun replacingTheFeedTellsItsMediatorToForgetItsPagingPosition() =
        withDatabase { database, tracker ->
            var replaced = 0
            tracker.registerFeedReplacedHandler(ownerId = USER_ID, feedSpec = MAIN_SPEC) { replaced++ }

            val processor = FeedProcessor(feedSpec = MAIN_SPEC, database = database, invalidationTracker = tracker)
            processor.processAndPersistToDatabase(userId = USER_ID, response = feedResponse("note-1"), clearFeed = false)
            assertEquals(0, replaced, "an append does not replace the feed")

            processor.processAndPersistToDatabase(userId = USER_ID, response = feedResponse("note-2"), clearFeed = true)
            assertEquals(1, replaced)
        }

    @Test
    fun refresh_stillInvalidatesThePagingSource() =
        withDatabase { database, tracker ->
            val pagingSource = tracker.track(
                ownerId = USER_ID,
                feedSpec = MAIN_SPEC,
                pagingSource = database.feedPosts().feedQuery(
                    query = ChronologicalFeedWithRepostsQueryBuilder(
                        feedSpec = MAIN_SPEC,
                        userPubkey = USER_ID,
                        allowMutedThreads = false,
                    ).feedQuery(),
                ),
            )

            FeedProcessor(feedSpec = MAIN_SPEC, database = database, invalidationTracker = tracker)
                .processAndPersistToDatabase(userId = USER_ID, response = feedResponse(noteId = "note-2"), clearFeed = true)

            assertTrue(pagingSource.invalid, "a real refresh must still invalidate the PagingSource")
        }

    // ---------------------------------------------------------------------------------------------
    // harness
    // ---------------------------------------------------------------------------------------------

    private fun withDatabase(block: suspend (CachingDatabase, FeedSpecInvalidationTracker) -> Unit) =
        runBlocking {
            val databaseName = "primal_feed_invalidation_${counter++}.db"
            LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            val database = LocalDatabaseFactory.createDatabase<CachingDatabase>(databaseName = databaseName)
            try {
                database.profiles().insertOrUpdateAll(data = listOf(profileData(ownerId = AUTHOR_ID)))
                block(database, FeedSpecInvalidationTracker())
            } finally {
                database.close()
                LocalDatabaseFactory.deleteDatabases(names = listOf(databaseName))
            }
        }

    private fun feedResponse(noteId: String) =
        FeedResponse(
            paging = null,
            metadata = emptyList(),
            notes = listOf(
                NostrEvent(
                    id = noteId,
                    pubKey = AUTHOR_ID,
                    createdAt = 1_700_000_100L,
                    kind = 1,
                    tags = emptyList(),
                    content = "hello nostr",
                    sig = "signature",
                ),
            ),
            articles = emptyList(),
            reposts = emptyList(),
            zaps = emptyList(),
            referencedEvents = emptyList(),
            primalEventStats = emptyList(),
            primalEventUserStats = emptyList(),
            cdnResources = emptyList(),
            primalLinkPreviews = emptyList(),
            primalRelayHints = emptyList(),
            blossomServers = emptyList(),
        )

    private fun profileData(ownerId: String) =
        ProfileData(
            ownerId = ownerId,
            eventId = "metadata-$ownerId",
            createdAt = 1_700_000_000L,
            raw = "{}",
        )

    companion object {
        private const val USER_ID = "user-pubkey"
        private const val AUTHOR_ID = "author-pubkey"
        private const val MAIN_SPEC = """{"id":"latest","kind":"notes"}"""

        private var counter = 0
    }
}
