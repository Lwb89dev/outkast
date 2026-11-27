package net.primal.data.repository.feed

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/**
 * Advanced search's `scope:myfollows` was the case named when [FetchCoordinator] was introduced —
 * the note feed, article feed and this both ask for the active user's follow list, often within
 * the same burst of tab loads — but this fetcher queried relays directly and was never actually
 * wired to it. This pins that it now shares the coordinator's follow-list cache instead of always
 * paying for its own round trip.
 */
class RelayAdvancedSearchFeedFetcherTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `myfollows scope reuses an already-cached follow list instead of asking again`() =
        runTest {
            val querier = RecordingQuerier()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val coordinator = FetchCoordinator(
                dispatcherProvider = mockk<DispatcherProvider> {
                    every { io() } returns dispatcher
                    every { main() } returns dispatcher
                },
            )
            coordinator.fetchFollowList(querier = querier, pubkey = USER_ID)

            RelayAdvancedSearchFeedFetcher(querier = querier, coordinator = coordinator).fetch(
                userId = USER_ID,
                feedSpec = """{"id":"advsearch","query":"scope:myfollows"}""",
                fallbackKinds = listOf(1),
                limit = 20,
            )

            assertEquals(1, querier.followListQueries, "a warm cache must not cost a second relay round trip")
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.fetcher(querier: RelayEventQuerier): RelayAdvancedSearchFeedFetcher {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val coordinator = FetchCoordinator(
            dispatcherProvider = mockk<DispatcherProvider> {
                every { io() } returns dispatcher
                every { main() } returns dispatcher
            },
        )
        return RelayAdvancedSearchFeedFetcher(querier = querier, coordinator = coordinator)
    }

    private suspend fun RelayAdvancedSearchFeedFetcher.search(query: String) =
        fetch(
            userId = USER_ID,
            feedSpec = """{"id":"advsearch","query":"$query"}""",
            fallbackKinds = listOf(1, 6),
            limit = 20,
        )

    @Test
    fun `from with a user name looks the name up and asks for that author`() =
        runTest {
            val querier = FakeRelay(
                events = listOf(
                    event("p1", ALICE, kind = 0, content = """{"name":"alice"}"""),
                    event("n1", ALICE, content = "buongiorno"),
                    event("n2", "someone-else", content = "buongiorno"),
                ),
            )

            val response = fetcher(querier).search("from:alice buongiorno")

            assertEquals(listOf("n1"), response.notes.map { it.id })
            val noteQuery = querier.filters.first { it.kinds?.contains(1) == true }
            assertEquals(listOf(ALICE), noteQuery.authors)
            assertEquals(listOf(1), noteQuery.kinds, "reposts are not searched")
        }

    @Test
    fun `a name nobody has finds nothing instead of everything`() =
        runTest {
            val querier = FakeRelay(events = listOf(event("n1", ALICE, content = "buongiorno")))

            val response = fetcher(querier).search("from:nobody buongiorno")

            assertEquals(emptyList(), response.notes)
        }

    @Test
    fun `top order puts the post people interacted with before the newer one`() =
        runTest {
            val querier = FakeRelay(
                events = listOf(
                    event("quiet", ALICE, content = "nostr ciao", createdAt = 2_000),
                    event("loved", ALICE, content = "nostr ciao", createdAt = 1_000),
                    event("r1", "fan1", kind = 7, content = "+", tags = listOf("e" to "loved")),
                    event("r2", "fan2", kind = 7, content = "+", tags = listOf("e" to "loved")),
                ),
            )

            val latest = fetcher(querier).search("ciao")
            val top = fetcher(querier).search("ciao orderby:top")

            assertEquals(listOf("quiet", "loved"), latest.paging?.elements)
            assertEquals(listOf("loved", "quiet"), top.paging?.elements)
        }

    private fun event(
        id: String,
        author: String,
        kind: Int = 1,
        content: String = "",
        createdAt: Long = 1_700_000_000L,
        tags: List<Pair<String, String>> = emptyList(),
    ) = NostrEvent(
        id = id,
        pubKey = author,
        createdAt = createdAt,
        kind = kind,
        tags = tags.map { (name, value) ->
            buildJsonArray {
                add(JsonPrimitive(name))
                add(JsonPrimitive(value))
            }
        },
        content = content,
        sig = "sig",
    )

    /** Answers a filter the way a relay without NIP-50 would: by kind, author, id and tag. */
    private class FakeRelay(private val events: List<NostrEvent>) : RelayEventQuerier {
        val filters = mutableListOf<RelayFilter>()

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            filters += filter
            return events.filter { event ->
                (filter.kinds?.contains(event.kind) != false) &&
                    (filter.authors?.contains(event.pubKey) != false) &&
                    (filter.ids?.contains(event.id) != false) &&
                    (filter.eventTags?.let { ids -> event.tags.any { it[1].jsonPrimitive.content in ids } } != false)
            }
        }
    }

    private class RecordingQuerier : RelayEventQuerier {
        var followListQueries = 0

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            if (filter.kinds?.contains(NostrEventKind.FollowList.value) == true) {
                followListQueries++
                return listOf(
                    NostrEvent(
                        id = "follow-list",
                        pubKey = USER_ID,
                        createdAt = 1_700_000_000L,
                        kind = NostrEventKind.FollowList.value,
                        tags = listOf(
                            buildJsonArray {
                                add(JsonPrimitive("p"))
                                add(JsonPrimitive("followed-pubkey"))
                            },
                        ),
                        content = "",
                        sig = "sig",
                    ),
                )
            }
            return emptyList()
        }
    }

    private companion object {
        const val USER_ID = "user-pubkey"
        val ALICE = "a".repeat(64)
    }
}
