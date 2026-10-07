package net.primal.data.repository.feed

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.data.repository.fetch.RelaysUnreachableException
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.hasEventIdTag
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter
import net.primal.domain.nostr.relay.RelayQueryOutcome

/**
 * Walks a feed from its newest page to its oldest the way the paging mediator does (each page
 * starts at the oldest event of the previous one) against a relay that really applies `limit` and
 * `until`, and checks what a user scrolling would see.
 *
 * The earlier tests of this fetcher answered every query with a fixed list, which cannot show a
 * page boundary losing notes or an empty-looking page ending the feed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedPagingWalkTest {

    @Test
    fun `scrolling a feed with one very busy chunk of authors misses no note`() =
        runTest {
            val follows = (0 until FOLLOW_COUNT).map { "author-${it.toString().padStart(3, '0')}" }
            val feed = busyAndQuietFeed(follows = follows, count = 400)
            val relay = SimulatedRelay(feed + followListOf(follows))

            val seen = walk(fetcher(relay), spec = FOLLOWING_SPEC, includeReplies = false)

            // The first 250 follows are one chunk and write most of the notes, so its newest
            // `limit` events stop far short of where the quiet chunks' reach. Taking the newest
            // twenty of the merged result used to run past the busy chunk's last event and the
            // notes it still held between there and the end of the page were never asked for again.
            seen shouldContainAll feed.filter { it.isShownInFollowingFeed() }.map { it.id }
        }

    @Test
    fun `a window of nothing but replies does not end a profile's notes`() =
        runTest {
            val relay = SimulatedRelay(repliesThenNotes(replies = 50, notes = 40))

            val page = fetcher(relay).fetchPage(
                userId = ALICE,
                feedSpec = PROFILE_SPEC,
                includeReplies = false,
                limit = 20,
            )

            // The newest 50 events are replies, which this feed hides. The page used to be built
            // from the first window alone, came back empty, and the mediator took that for the end.
            page.page.size shouldBe 20
            assertTrue(page.page.none { it.tags.hasEventIdTag() })
            page.unreachable shouldBe false
        }

    @Test
    fun `a page that stays empty after the window cap says where to resume`() =
        runTest {
            val relay = SimulatedRelay(repliesThenNotes(replies = 100, notes = 40))
            val fetcher = fetcher(relay)

            val first = fetcher.fetchPage(
                userId = ALICE,
                feedSpec = PROFILE_SPEC,
                includeReplies = false,
                limit = 20,
            )

            first.page.size shouldBe 0
            val resumeFrom = first.resumeUntil ?: fail("an empty page the relays did answer must carry a cursor")
            first.unreachable shouldBe false

            val next = fetcher.fetchPage(
                userId = ALICE,
                feedSpec = PROFILE_SPEC,
                includeReplies = false,
                limit = 20,
                until = resumeFrom,
            )
            assertTrue(next.page.isNotEmpty(), "resuming from the cursor must reach the notes behind the replies")
        }

    @Test
    fun `no relay answering is reported as unreachable, not as an empty feed`() =
        runTest {
            val relay = SimulatedRelay(repliesThenNotes(replies = 0, notes = 10), reachable = false)
            val fetcher = fetcher(relay)

            val page = fetcher.fetchPage(
                userId = ALICE,
                feedSpec = PROFILE_SPEC,
                includeReplies = false,
                limit = 20,
            )

            page.page.size shouldBe 0
            page.unreachable shouldBe true
            assertFailsWith<RelaysUnreachableException> {
                fetcher.fetch(
                    userId = ALICE,
                    feedSpec = PROFILE_SPEC,
                    includeReplies = false,
                    limit = 20,
                    failWhenUnreachable = true,
                )
            }
            // Everything else keeps getting the empty response it always got.
            fetcher.fetch(
                userId = ALICE,
                feedSpec = PROFILE_SPEC,
                includeReplies = false,
                limit = 20,
            ).notes.size shouldBe 0
        }

    @Test
    fun `relays that answer with nothing older are an empty feed, not an unreachable one`() =
        runTest {
            val relay = SimulatedRelay(emptyList())

            val page = fetcher(relay).fetchPage(
                userId = ALICE,
                feedSpec = PROFILE_SPEC,
                includeReplies = false,
                limit = 20,
            )

            page.page.size shouldBe 0
            page.unreachable shouldBe false
            page.resumeUntil shouldBe null
        }

    // ------------------------------------------------------------------------------- harness

    private suspend fun walk(
        fetcher: RelayNotesFeedFetcher,
        spec: String,
        includeReplies: Boolean,
        limit: Int = 20,
    ): Set<String> {
        val seen = linkedSetOf<String>()
        var until: Long? = null
        repeat(MAX_PAGES) {
            val page = fetcher.fetchPage(
                userId = USER,
                feedSpec = spec,
                includeReplies = includeReplies,
                limit = limit,
                until = until,
            )
            val before = seen.size
            seen += page.page.map { it.id }
            val next = page.page.minOfOrNull { it.createdAt } ?: page.resumeUntil
            // The oldest event is asked for again (`until` is inclusive), so a page that adds
            // nothing new and has nowhere further to go is the end of the feed.
            if (seen.size == before && page.resumeUntil == null) return seen
            until = next
        }
        fail("the walk never reached the end of the feed")
    }

    private fun TestScope.fetcher(querier: RelayEventQuerier): RelayNotesFeedFetcher {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return RelayNotesFeedFetcher(
            querier = querier,
            coordinator = FetchCoordinator(
                dispatcherProvider = mockk<DispatcherProvider> {
                    every { io() } returns dispatcher
                    every { main() } returns dispatcher
                },
            ),
        )
    }

    /**
     * Three quarters of the notes come from the first 250 follows, the first chunk the fetcher
     * asks about; the rest are spread over everybody else. Every third kind 1 is a reply and every
     * seventeenth event a repost. Timestamps are unique so no boundary can hide a tie.
     */
    private fun busyAndQuietFeed(follows: List<String>, count: Int): List<NostrEvent> {
        val busy = follows.take(CHUNK)
        val quiet = follows.drop(CHUNK)
        return (0 until count).map { index ->
            val author = if (index % 4 != 3) busy[index % busy.size] else quiet[index % quiet.size]
            val isRepost = index % 17 == 5
            event(
                id = "event-$index",
                pubkey = author,
                kind = if (isRepost) NostrEventKind.ShortTextNoteRepost.value else NostrEventKind.ShortTextNote.value,
                createdAt = NEWEST - index * 10L,
                tags = if (index % 3 == 0 && !isRepost) listOf(eventTag("root-$index")) else emptyList(),
            )
        }
    }

    private fun repliesThenNotes(replies: Int, notes: Int): List<NostrEvent> =
        (0 until replies).map { index ->
            event("reply-$index", ALICE, 1, NEWEST - index * 10L, listOf(eventTag("root-$index")))
        } + (0 until notes).map { index ->
            event("note-$index", ALICE, 1, NEWEST - (replies + index) * 10L)
        }

    private fun followListOf(follows: List<String>) =
        event("follow-list", USER, NostrEventKind.FollowList.value, createdAt = 1L, tags = follows.map(::pubkeyTag))

    private fun NostrEvent.isShownInFollowingFeed() =
        kind == NostrEventKind.ShortTextNoteRepost.value || !tags.hasEventIdTag()

    private fun event(
        id: String,
        pubkey: String,
        kind: Int,
        createdAt: Long,
        tags: List<JsonArray> = emptyList(),
    ) = NostrEvent(id = id, pubKey = pubkey, createdAt = createdAt, kind = kind, tags = tags, content = "x", sig = "s")

    private fun pubkeyTag(pubkey: String) = tag("p", pubkey)

    private fun eventTag(id: String) = tag("e", id)

    private fun tag(name: String, value: String) =
        buildJsonArray {
            add(JsonPrimitive(name))
            add(JsonPrimitive(value))
        }

    /** A relay: newest first, cut at `limit`, `until`/`since` inclusive, as NIP-01 says. */
    private class SimulatedRelay(
        private val events: List<NostrEvent>,
        private val reachable: Boolean = true,
    ) : RelayEventQuerier {

        override suspend fun query(filter: RelayFilter): List<NostrEvent> = queryWithOutcome(filter).events

        override suspend fun queryWithOutcome(filter: RelayFilter): RelayQueryOutcome {
            if (!reachable) return RelayQueryOutcome(events = emptyList(), answered = false)
            val kinds = filter.kinds
            val authors = filter.authors
            val until = filter.until
            val since = filter.since
            val matches = events
                .filter { kinds == null || it.kind in kinds }
                .filter { authors == null || it.pubKey in authors }
                .filter { until == null || it.createdAt <= until }
                .filter { since == null || it.createdAt >= since }
                .sortedByDescending { it.createdAt }
            return RelayQueryOutcome(events = matches.take(filter.limit ?: Int.MAX_VALUE), answered = true)
        }
    }

    private companion object {
        const val USER = "user"
        const val ALICE = "alice"
        const val FOLLOW_COUNT = 600
        const val CHUNK = 250
        const val MAX_PAGES = 400
        const val NEWEST = 1_000_000L
        const val FOLLOWING_SPEC = """{"id":"latest","kind":"notes"}"""
        const val PROFILE_SPEC = """{"id":"feed","kind":"notes","notes":"authored","pubkey":"alice"}"""
    }
}
