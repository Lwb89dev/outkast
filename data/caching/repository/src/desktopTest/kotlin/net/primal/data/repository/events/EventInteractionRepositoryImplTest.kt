package net.primal.data.repository.events

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.createAppBuildHelper
import net.primal.core.utils.runCatching
import net.primal.data.local.dao.events.EventStats
import net.primal.data.local.dao.events.EventStatsDao
import net.primal.data.local.dao.events.EventUserStats
import net.primal.data.local.dao.events.EventUserStatsDao
import net.primal.data.local.dao.events.EventZapDao
import net.primal.data.local.dao.profiles.ProfileDataDao
import net.primal.data.local.db.CachingDatabase
import net.primal.domain.events.EventInteractionRepository
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.getTagValueOrNull
import net.primal.domain.nostr.isClientTag
import net.primal.domain.nostr.isEventIdTag
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.publisher.NostrPublishException
import net.primal.domain.publisher.PrimalPublishResult
import net.primal.domain.publisher.PrimalPublisher
import net.primal.shared.data.local.db.withTransaction

@Suppress("MaxLineLength")
class EventInteractionRepositoryImplTest {

    private val testDispatcher = StandardTestDispatcher()

    private val userId = "test_user_id"
    private val eventId = "test_event_id"
    private val eventAuthorId = "test_author_id"

    private val dummyNostrEvent = NostrEvent(
        id = "signed_event_id",
        pubKey = userId,
        createdAt = 0,
        kind = 1,
        tags = emptyList(),
        content = "",
        sig = "signature",
    )
    private val dummyPublishResult = PrimalPublishResult(nostrEvent = dummyNostrEvent)

    private lateinit var mockEventStatsDao: EventStatsDao
    private lateinit var mockEventUserStatsDao: EventUserStatsDao
    private lateinit var mockEventZapDao: EventZapDao
    private lateinit var mockDatabase: CachingDatabase
    private lateinit var dispatcherProvider: DispatcherProvider

    @BeforeTest
    fun setUp() {
        mockkStatic("net.primal.shared.data.local.db.RoomDatabaseExtKt")
        mockkStatic("net.primal.core.utils.AppBuildHelper_desktopKt")
        every { createAppBuildHelper() } returns mockk {
            every { getClientName() } returns "Primal Test"
        }

        dispatcherProvider = mockk {
            every { io() } returns testDispatcher
            every { main() } returns testDispatcher
        }

        mockEventStatsDao = mockk<EventStatsDao>(relaxUnitFun = true) {
            coEvery { find(eventId = any()) } returns EventStats(eventId = eventId)
        }
        mockEventUserStatsDao = mockk<EventUserStatsDao>(relaxUnitFun = true) {
            coEvery { find(eventId = any(), userId = any()) } returns EventUserStats(eventId = eventId, userId = userId)
        }
        mockEventZapDao = mockk<EventZapDao>(relaxUnitFun = true)
        mockDatabase = mockk<CachingDatabase> {
            coEvery { eventStats() } returns mockEventStatsDao
            coEvery { eventUserStats() } returns mockEventUserStatsDao
            coEvery { eventZaps() } returns mockEventZapDao
            coEvery { profiles() } returns mockk<ProfileDataDao> {
                coEvery { findProfileData(profileId = any<String>()) } returns null
            }
        }

        coEvery { mockDatabase.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("net.primal.shared.data.local.db.RoomDatabaseExtKt")
        unmockkStatic("net.primal.core.utils.AppBuildHelper_desktopKt")
    }

    private fun buildRepository(
        primalPublisher: PrimalPublisher = mockk {
            coEvery { signPublishImportNostrEvent(any(), any()) } returns dummyPublishResult
        },
        relayEventQuerier: RelayEventQuerier = mockk {
            coEvery { query(any()) } returns emptyList()
        },
    ): EventInteractionRepository {
        return EventInteractionRepositoryImpl(
            dispatcherProvider = dispatcherProvider,
            primalPublisher = primalPublisher,
            database = mockDatabase,
            relayEventQuerier = relayEventQuerier,
        )
    }

    private fun reactionEvent(id: String, at: Long, content: String = "+") = NostrEvent(
        id = id,
        pubKey = userId,
        createdAt = at,
        kind = NostrEventKind.Reaction.value,
        tags = emptyList(),
        content = content,
        sig = "sig",
    )

    /**
     * Makes the mocked DAOs remember what was written to them, as a real table would, and answer
     * from it. The default mocks always return the original row, which cannot tell a revert that
     * restores the old value from one that quietly keeps the new one.
     */
    private fun statefulStatsStores(stats: EventStats, userStats: EventUserStats) {
        var currentStats = stats
        var currentUserStats = userStats
        coEvery { mockEventStatsDao.find(eventId = any()) } answers { currentStats }
        coEvery { mockEventUserStatsDao.find(eventId = any(), userId = any()) } answers { currentUserStats }
        coEvery { mockEventStatsDao.upsert(data = any()) } answers { currentStats = firstArg() }
        coEvery { mockEventUserStatsDao.upsert(data = any()) } answers { currentUserStats = firstArg() }
        statsReader = { currentStats to currentUserStats }
    }

    private var statsReader: () -> Pair<EventStats, EventUserStats> = {
        EventStats(eventId = eventId) to EventUserStats(eventId = eventId, userId = userId)
    }

    @Test
    fun `likePost updates like stats in database if reaction was published`() =
        runTest(testDispatcher) {
            val repository = buildRepository()

            repository.likeEvent(
                userId = userId,
                eventId = eventId,
                eventAuthorId = eventAuthorId,
            )

            coVerify { mockEventStatsDao.upsert(data = match { it.likes == 1L }) }
            coVerify { mockEventUserStatsDao.upsert(data = match { it.liked }) }
        }

    @Test
    fun `likePost reverts like stats in database if reaction was not published`() =
        runTest(testDispatcher) {
            val failingPublisher = mockk<PrimalPublisher> {
                coEvery {
                    signPublishImportNostrEvent(any(), any())
                } throws NostrPublishException("publish failed")
            }
            val repository = buildRepository(primalPublisher = failingPublisher)

            runCatching {
                repository.likeEvent(
                    userId = userId,
                    eventId = eventId,
                    eventAuthorId = eventAuthorId,
                )
            }

            // increaseLikeStats upserts once, then revertStats upserts once more
            coVerify(atLeast = 2) { mockEventStatsDao.upsert(data = any()) }
            coVerify(atLeast = 2) { mockEventUserStatsDao.upsert(data = any()) }
        }

    @Test
    fun `a failed like puts the stats back exactly as they were`() =
        runTest(testDispatcher) {
            statefulStatsStores(
                stats = EventStats(eventId = eventId, likes = 4),
                userStats = EventUserStats(eventId = eventId, userId = userId, liked = false),
            )
            val repository = buildRepository(
                primalPublisher = mockk {
                    coEvery { signPublishImportNostrEvent(any(), any()) } throws NostrPublishException("failed")
                },
            )

            runCatching { repository.likeEvent(userId = userId, eventId = eventId, eventAuthorId = eventAuthorId) }

            val (stats, userStats) = statsReader()
            stats.likes shouldBe 4L
            userStats.liked shouldBe false
        }

    @Test
    fun `likePost with an emoji publishes that emoji as the reaction`() =
        runTest(testDispatcher) {
            val publisher = mockk<PrimalPublisher> {
                coEvery { signPublishImportNostrEvent(any(), any()) } returns dummyPublishResult
            }

            buildRepository(primalPublisher = publisher).likeEvent(
                userId = userId,
                eventId = eventId,
                eventAuthorId = eventAuthorId,
                reaction = "🔥",
            )

            coVerify {
                publisher.signPublishImportNostrEvent(
                    match { it.kind == NostrEventKind.Reaction.value && it.content == "🔥" },
                    any(),
                )
            }
        }

    @Test
    fun `likePost without a reaction is a plain like`() =
        runTest(testDispatcher) {
            val publisher = mockk<PrimalPublisher> {
                coEvery { signPublishImportNostrEvent(any(), any()) } returns dummyPublishResult
            }

            buildRepository(primalPublisher = publisher).likeEvent(
                userId = userId,
                eventId = eventId,
                eventAuthorId = eventAuthorId,
            )

            coVerify { publisher.signPublishImportNostrEvent(match { it.content == "+" }, any()) }
        }

    @Test
    fun `unlike deletes every reaction the user has on the event and empties the heart`() =
        runTest(testDispatcher) {
            statefulStatsStores(
                stats = EventStats(eventId = eventId, likes = 3),
                userStats = EventUserStats(eventId = eventId, userId = userId, liked = true),
            )
            val publisher = mockk<PrimalPublisher> {
                coEvery { signPublishImportNostrEvent(any(), any()) } returns dummyPublishResult
            }
            val querier = mockk<RelayEventQuerier> {
                coEvery { query(any()) } returns listOf(
                    reactionEvent(id = "older", at = 10),
                    reactionEvent(id = "newer", at = 20, content = "🔥"),
                )
            }

            buildRepository(primalPublisher = publisher, relayEventQuerier = querier)
                .unlikeEvent(userId = userId, eventId = eventId, eventAuthorId = eventAuthorId)

            val deletions = mutableListOf<NostrUnsignedEvent>()
            coVerify(exactly = 2) {
                publisher.signPublishImportNostrEvent(
                    match { event ->
                        (event.kind == NostrEventKind.EventDeletion.value).also { isDeletion ->
                            if (isDeletion) deletions += event
                        }
                    },
                    any(),
                )
            }
            deletions.flatMap { event -> event.tags.mapNotNull { it.eventIdValue() } }.toSet() shouldBe
                setOf("older", "newer")
            val (stats, userStats) = statsReader()
            stats.likes shouldBe 2L
            userStats.liked shouldBe false
        }

    @Test
    fun `unlike asks the relays only for the user's own reactions on that event`() =
        runTest(testDispatcher) {
            val querier = mockk<RelayEventQuerier> {
                coEvery { query(any()) } returns listOf(reactionEvent(id = "mine", at = 1))
            }

            buildRepository(relayEventQuerier = querier)
                .unlikeEvent(userId = userId, eventId = eventId, eventAuthorId = eventAuthorId)

            coVerify {
                querier.query(
                    match {
                        it.kinds == listOf(NostrEventKind.Reaction.value) &&
                            it.authors == listOf(userId) &&
                            it.eventTags == listOf(eventId)
                    },
                )
            }
        }

    @Test
    fun `unlike with no reaction found fails and leaves the heart filled`() =
        runTest(testDispatcher) {
            statefulStatsStores(
                stats = EventStats(eventId = eventId, likes = 3),
                userStats = EventUserStats(eventId = eventId, userId = userId, liked = true),
            )

            shouldThrow<NostrPublishException> {
                buildRepository().unlikeEvent(userId = userId, eventId = eventId, eventAuthorId = eventAuthorId)
            }

            val (stats, userStats) = statsReader()
            stats.likes shouldBe 3L
            userStats.liked shouldBe true
        }

    @Test
    fun `unlike whose deletion is not published restores the like`() =
        runTest(testDispatcher) {
            statefulStatsStores(
                stats = EventStats(eventId = eventId, likes = 3),
                userStats = EventUserStats(eventId = eventId, userId = userId, liked = true),
            )
            val publisher = mockk<PrimalPublisher> {
                coEvery { signPublishImportNostrEvent(any(), any()) } throws NostrPublishException("failed")
            }
            val querier = mockk<RelayEventQuerier> {
                coEvery { query(any()) } returns listOf(reactionEvent(id = "mine", at = 1))
            }

            runCatching {
                buildRepository(primalPublisher = publisher, relayEventQuerier = querier)
                    .unlikeEvent(userId = userId, eventId = eventId, eventAuthorId = eventAuthorId)
            }

            val (stats, userStats) = statsReader()
            stats.likes shouldBe 3L
            userStats.liked shouldBe true
        }

    @Test
    fun `unlike never takes the count below zero`() =
        runTest(testDispatcher) {
            statefulStatsStores(
                stats = EventStats(eventId = eventId, likes = 0),
                userStats = EventUserStats(eventId = eventId, userId = userId, liked = true),
            )
            val querier = mockk<RelayEventQuerier> {
                coEvery { query(any()) } returns listOf(reactionEvent(id = "mine", at = 1))
            }

            buildRepository(relayEventQuerier = querier)
                .unlikeEvent(userId = userId, eventId = eventId, eventAuthorId = eventAuthorId)

            statsReader().first.likes shouldBe 0L
        }

    @Test
    fun `likePost throws exception if reaction was not published`() =
        runTest(testDispatcher) {
            val failingPublisher = mockk<PrimalPublisher> {
                coEvery {
                    signPublishImportNostrEvent(any(), any())
                } throws NostrPublishException("publish failed")
            }
            val repository = buildRepository(primalPublisher = failingPublisher)

            shouldThrow<NostrPublishException> {
                repository.likeEvent(
                    userId = userId,
                    eventId = eventId,
                    eventAuthorId = eventAuthorId,
                )
            }
        }

    @Test
    fun `likePost does not update like stats in database if post was liked by user`() =
        runTest(testDispatcher) {
            // Override the mock to return already-liked user stats
            coEvery {
                mockEventUserStatsDao.find(eventId = eventId, userId = userId)
            } returns EventUserStats(eventId = eventId, userId = userId, liked = true)

            val repository = buildRepository()

            repository.likeEvent(
                userId = userId,
                eventId = eventId,
                eventAuthorId = eventAuthorId,
            )

            // EventStatsUpdater still calls upsert, but the liked flag remains true
            // (already liked), so the user stats don't functionally change.
            coVerify {
                mockEventUserStatsDao.upsert(data = match { it.liked })
            }
        }

    @Test
    fun `repostPost updates repost stats in database if reaction was published`() =
        runTest(testDispatcher) {
            val repository = buildRepository()

            repository.repostEvent(
                userId = userId,
                eventId = eventId,
                eventAuthorId = eventAuthorId,
                eventKind = NostrEventKind.ShortTextNote.value,
                eventRawNostrEvent = "{}",
            )

            coVerify { mockEventStatsDao.upsert(data = match { it.reposts == 1L }) }
            coVerify { mockEventUserStatsDao.upsert(data = match { it.reposted }) }
        }

    @Test
    fun `repostPost reverts repost stats in database if reaction was not published`() =
        runTest(testDispatcher) {
            val failingPublisher = mockk<PrimalPublisher> {
                coEvery {
                    signPublishImportNostrEvent(any(), any())
                } throws NostrPublishException("publish failed")
            }
            val repository = buildRepository(primalPublisher = failingPublisher)

            runCatching {
                repository.repostEvent(
                    userId = userId,
                    eventId = eventId,
                    eventAuthorId = eventAuthorId,
                    eventKind = NostrEventKind.ShortTextNote.value,
                    eventRawNostrEvent = "{}",
                )
            }

            // increaseRepostStats upserts once, then revertStats upserts once more
            coVerify(atLeast = 2) { mockEventStatsDao.upsert(data = any()) }
            coVerify(atLeast = 2) { mockEventUserStatsDao.upsert(data = any()) }
        }

    @Test
    fun `repostPost throws exception if reaction was not published`() =
        runTest(testDispatcher) {
            val failingPublisher = mockk<PrimalPublisher> {
                coEvery {
                    signPublishImportNostrEvent(any(), any())
                } throws NostrPublishException("publish failed")
            }
            val repository = buildRepository(primalPublisher = failingPublisher)

            shouldThrow<NostrPublishException> {
                repository.repostEvent(
                    userId = userId,
                    eventId = eventId,
                    eventAuthorId = eventAuthorId,
                    eventKind = NostrEventKind.ShortTextNote.value,
                    eventRawNostrEvent = "{}",
                )
            }
        }

    @Test
    fun `likePost includes client tag in published event`() =
        runTest(testDispatcher) {
            val mockPublisher = mockk<PrimalPublisher> {
                coEvery { signPublishImportNostrEvent(any(), any()) } returns dummyPublishResult
            }
            val repository = buildRepository(primalPublisher = mockPublisher)

            repository.likeEvent(
                userId = userId,
                eventId = eventId,
                eventAuthorId = eventAuthorId,
            )

            coVerify {
                mockPublisher.signPublishImportNostrEvent(
                    match { event -> event.tags.any { it.isClientTag() } },
                    any(),
                )
            }
        }

    private fun JsonArray.eventIdValue(): String? =
        if (isEventIdTag()) getTagValueOrNull() else null

    @Test
    fun `repostPost includes client tag in published event`() =
        runTest(testDispatcher) {
            val mockPublisher = mockk<PrimalPublisher> {
                coEvery { signPublishImportNostrEvent(any(), any()) } returns dummyPublishResult
            }
            val repository = buildRepository(primalPublisher = mockPublisher)

            repository.repostEvent(
                userId = userId,
                eventId = eventId,
                eventAuthorId = eventAuthorId,
                eventKind = NostrEventKind.ShortTextNote.value,
                eventRawNostrEvent = "{}",
            )

            coVerify {
                mockPublisher.signPublishImportNostrEvent(
                    match { event -> event.tags.any { it.isClientTag() } },
                    any(),
                )
            }
        }

}
