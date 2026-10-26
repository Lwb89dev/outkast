package net.primal.data.repository.feed

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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
import net.primal.domain.feeds.isRelayServableNotesFeedSpec
import net.primal.domain.feeds.networkNotesFeedSpec
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/**
 * The network feed is what the people the user follows reacted to or reposted, written by people the user does not
 * follow. These tests pin what makes it into that list and what never does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkFeedFromRelaysTest {

    @Test
    fun `the network feed is claimed as relay servable`() {
        assertTrue(networkNotesFeedSpec.isRelayServableNotesFeedSpec())
    }

    @Test
    fun `a stranger's note that a friend liked is listed, newest first`() =
        runTest {
            val querier = FakeQuerier(
                follows = friends(2),
                notes = listOf(note("old", STRANGER, 10), note("new", STRANGER, 30)),
                reactions = listOf(reaction(friend(0), "old"), reaction(friend(1), "new")),
            )

            val response = fetcher(querier).fetchNetwork()

            assertEquals(listOf("new", "old"), response.notes.map { it.id })
        }

    @Test
    fun `a repost counts as engagement just like a reaction`() =
        runTest {
            val querier = FakeQuerier(
                follows = friends(1),
                notes = listOf(note("reposted", STRANGER, 10)),
                reactions = listOf(
                    reaction(friend(0), "reposted", kind = NostrEventKind.ShortTextNoteRepost.value),
                ),
            )

            assertEquals(listOf("reposted"), fetcher(querier).fetchNetwork().notes.map { it.id })
        }

    @Test
    fun `notes by people the user already follows are never listed, nor the user's own`() =
        runTest {
            val querier = FakeQuerier(
                follows = friends(2),
                notes = listOf(
                    note("by-friend", friend(1), 10),
                    note("by-me", ME, 20),
                    note("by-stranger", STRANGER, 30),
                ),
                reactions = listOf(
                    reaction(friend(0), "by-friend"),
                    reaction(friend(0), "by-me"),
                    reaction(friend(0), "by-stranger"),
                ),
            )

            assertEquals(listOf("by-stranger"), fetcher(querier).fetchNetwork().notes.map { it.id })
        }

    @Test
    fun `a reply is left out because it is half a conversation`() =
        runTest {
            val querier = FakeQuerier(
                follows = friends(1),
                notes = listOf(
                    note("reply", STRANGER, 10, tags = listOf(tag("e", "something"))),
                    note("root", STRANGER, 20),
                ),
                reactions = listOf(reaction(friend(0), "reply"), reaction(friend(0), "root")),
            )

            assertEquals(listOf("root"), fetcher(querier).fetchNetwork().notes.map { it.id })
        }

    @Test
    fun `a downvote is not engagement`() =
        runTest {
            val querier = FakeQuerier(
                follows = friends(1),
                notes = listOf(note("disliked", STRANGER, 10)),
                reactions = listOf(reaction(friend(0), "disliked", content = "-")),
            )

            assertTrue(fetcher(querier).fetchNetwork().notes.isEmpty())
        }

    @Test
    fun `someone who follows many people needs two friends behind a note, one is not a crowd`() =
        runTest {
            val querier = FakeQuerier(
                follows = friends(40),
                notes = listOf(note("one-friend", STRANGER, 10), note("two-friends", STRANGER, 20)),
                reactions = listOf(
                    reaction(friend(0), "one-friend"),
                    reaction(friend(1), "two-friends"),
                    reaction(friend(2), "two-friends"),
                ),
            )

            assertEquals(listOf("two-friends"), fetcher(querier).fetchNetwork().notes.map { it.id })
        }

    @Test
    fun `the same friend reacting twice to a note is still one friend`() =
        runTest {
            val querier = FakeQuerier(
                follows = friends(40),
                notes = listOf(note("keen", STRANGER, 10)),
                reactions = listOf(reaction(friend(0), "keen", id = "r1"), reaction(friend(0), "keen", id = "r2")),
            )

            assertTrue(fetcher(querier).fetchNetwork().notes.isEmpty())
        }

    @Test
    fun `the until cursor and the limit cut the page like every other feed`() =
        runTest {
            val querier = FakeQuerier(
                follows = friends(1),
                notes = listOf(note("a", STRANGER, 10), note("b", STRANGER, 20), note("c", STRANGER, 30)),
                reactions = listOf(reaction(friend(0), "a"), reaction(friend(0), "b"), reaction(friend(0), "c")),
            )
            val fetcher = fetcher(querier)

            assertEquals(listOf("b", "a"), fetcher.fetchNetwork(until = 20).notes.map { it.id })
            assertEquals(listOf("c", "b"), fetcher.fetchNetwork(limit = 2).notes.map { it.id })
        }

    @Test
    fun `paging through the list asks the relays for the tally only once`() =
        runTest {
            val querier = FakeQuerier(
                follows = friends(1),
                notes = listOf(note("a", STRANGER, 10), note("b", STRANGER, 20)),
                reactions = listOf(reaction(friend(0), "a"), reaction(friend(0), "b")),
            )
            val fetcher = fetcher(querier)

            fetcher.fetchNetwork(limit = 1)
            fetcher.fetchNetwork(limit = 1, until = 19)

            assertEquals(1, querier.engagementQueries)
        }

    @Test
    fun `a user who follows nobody gets an empty page that is not an outage`() =
        runTest {
            val querier = FakeQuerier(follows = emptyList(), notes = emptyList(), reactions = emptyList())

            val response = fetcher(querier).fetch(
                userId = ME,
                feedSpec = networkNotesFeedSpec,
                includeReplies = false,
                limit = 20,
                failWhenUnreachable = true,
            )

            assertTrue(response.notes.isEmpty())
            assertEquals(0, querier.engagementQueries)
        }

    @Test
    fun `nothing coming back for someone who does follow people reads as unreachable, not as the end`() =
        runTest {
            val querier = FakeQuerier(follows = friends(3), notes = emptyList(), reactions = emptyList())

            assertFailsWith<RelaysUnreachableException> {
                fetcher(querier).fetch(
                    userId = ME,
                    feedSpec = networkNotesFeedSpec,
                    includeReplies = false,
                    limit = 20,
                    failWhenUnreachable = true,
                )
            }
        }

    // ------------------------------------------------------------------------------- harness

    private suspend fun RelayNotesFeedFetcher.fetchNetwork(limit: Int = 20, until: Long? = null) =
        fetch(userId = ME, feedSpec = networkNotesFeedSpec, includeReplies = false, limit = limit, until = until)

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

    private class FakeQuerier(
        private val follows: List<String>,
        private val notes: List<NostrEvent>,
        private val reactions: List<NostrEvent>,
    ) : RelayEventQuerier {
        var engagementQueries = 0

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            val kinds = filter.kinds.orEmpty()
            return when {
                NostrEventKind.FollowList.value in kinds -> listOf(followList())

                NostrEventKind.Reaction.value in kinds -> {
                    engagementQueries++
                    val authors = filter.authors.orEmpty()
                    reactions.filter { it.pubKey in authors }
                }

                filter.ids != null -> {
                    val wanted = filter.ids.orEmpty()
                    notes.filter { it.id in wanted }
                }

                else -> emptyList()
            }
        }

        private fun followList() =
            NostrEvent(
                id = "follow-list",
                pubKey = ME,
                createdAt = 1_700_000_000L,
                kind = NostrEventKind.FollowList.value,
                tags = follows.map { tag("p", it) },
                content = "",
                sig = "sig",
            )
    }

    private companion object {
        const val ME = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
        const val STRANGER = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

        /** A valid-looking 64 hex character pubkey per index. */
        fun friend(index: Int) = (index + 1).toString(16).padStart(64, '0')

        fun friends(count: Int) = List(count) { friend(it) }

        fun tag(name: String, value: String) =
            buildJsonArray {
                add(JsonPrimitive(name))
                add(JsonPrimitive(value))
            }

        fun note(id: String, author: String, createdAt: Long, tags: List<JsonArray> = emptyList()) =
            NostrEvent(
                id = id,
                pubKey = author,
                createdAt = createdAt,
                kind = NostrEventKind.ShortTextNote.value,
                tags = tags,
                content = "note $id",
                sig = "sig",
            )

        fun reaction(
            by: String,
            target: String,
            content: String = "+",
            kind: Int = NostrEventKind.Reaction.value,
            id: String = "reaction-$by-$target",
        ) = NostrEvent(
            id = id,
            pubKey = by,
            createdAt = 1_700_000_100L,
            kind = kind,
            tags = listOf(tag("e", target)),
            content = content,
            sig = "sig",
        )
    }
}
