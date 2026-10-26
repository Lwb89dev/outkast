package net.primal.data.repository.feed

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
 * follow, ordered by how much those notes were interacted with. These tests pin what makes it into that list, what
 * never does, and in which order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class NetworkFeedFromRelaysTest {

    @Test
    fun `the network feed is claimed as relay servable`() {
        assertTrue(networkNotesFeedSpec.isRelayServableNotesFeedSpec())
    }

    @Test
    fun `a stranger's note that a friend liked is listed, and with equal interactions the newest comes first`() =
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
    fun `notes are ordered by reactions plus replies plus the number of zaps`() =
        runTest {
            val querier = FakeQuerier(
                follows = friends(1),
                notes = listOf(
                    note("quiet", STRANGER, 30),
                    note("busy", STRANGER, 10),
                    note("zapped", STRANGER, 20),
                ),
                reactions = listOf(
                    reaction(friend(0), "quiet"),
                    reaction(friend(0), "busy"),
                    reaction(friend(0), "zapped"),
                ),
                otherInteractions = listOf(
                    // busy: 1 friend reaction + 2 reactions + 1 reply = 4
                    reaction("0a".padEnd(64, '0'), "busy"),
                    reaction("0b".padEnd(64, '0'), "busy"),
                    reply("0c".padEnd(64, '0'), "busy"),
                    // zapped: 1 friend reaction + 2 zaps = 3, whatever the sats
                    zap("zap-1", "zapped", sats = 1_000_000),
                    zap("zap-2", "zapped", sats = 21),
                    // quiet: only the friend's reaction = 1
                ),
            )

            val response = fetcher(querier).fetchNetwork()

            assertEquals(listOf("busy", "zapped", "quiet"), response.notes.map { it.id })
        }

    @Test
    fun `one huge zap does not outweigh several small interactions`() =
        runTest {
            val querier = FakeQuerier(
                follows = friends(1),
                notes = listOf(note("whale", STRANGER, 30), note("crowd", STRANGER, 10)),
                reactions = listOf(reaction(friend(0), "whale"), reaction(friend(0), "crowd")),
                otherInteractions = listOf(
                    zap("zap-1", "whale", sats = 10_000_000),
                    reaction("0a".padEnd(64, '0'), "crowd"),
                    reaction("0b".padEnd(64, '0'), "crowd"),
                ),
            )

            assertEquals(listOf("crowd", "whale"), fetcher(querier).fetchNetwork().notes.map { it.id })
        }

    @Test
    fun `the ranked list is the first page, and a page asked for with a time cursor is empty`() =
        runTest {
            val querier = FakeQuerier(
                follows = friends(1),
                notes = listOf(note("a", STRANGER, 10), note("b", STRANGER, 20), note("c", STRANGER, 30)),
                reactions = listOf(reaction(friend(0), "a"), reaction(friend(0), "b"), reaction(friend(0), "c")),
            )
            val fetcher = fetcher(querier)

            assertEquals(3, fetcher.fetchNetwork(limit = 2).notes.size, "a ranking cannot be paged by time")
            assertTrue(fetcher.fetchNetwork(until = 20).notes.isEmpty())
            assertTrue(fetcher.fetchNetwork(since = 20).notes.isEmpty())
        }

    @Test
    fun `the first page hands over the counters the order was decided from`() =
        runTest {
            val querier = FakeQuerier(
                follows = friends(1),
                notes = listOf(note("a", STRANGER, 10)),
                reactions = listOf(reaction(friend(0), "a")),
                otherInteractions = listOf(reply("0c".padEnd(64, '0'), "a")),
            )

            val page = fetcher(querier).fetchPage(
                userId = ME,
                feedSpec = networkNotesFeedSpec,
                includeReplies = false,
                limit = 20,
            )

            val stats = page.stats!!.eventStats.single()
            assertEquals("a", stats.eventId)
            assertEquals(1L, stats.likes)
            assertEquals(1L, stats.replies)
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
    fun `opening the section after a prefetch does not ask the relays for the friends' reactions again`() =
        runTest {
            val querier = FakeQuerier(
                follows = friends(1),
                notes = listOf(note("seen", STRANGER, 10)),
                reactions = listOf(reaction(friend(0), "seen")),
            )
            val fetcher = fetcher(querier)

            fetcher.prefetchNetworkNotes(ME)
            val response = fetcher.fetchNetwork()

            assertEquals(listOf("seen"), response.notes.map { it.id })
            assertEquals(1, querier.engagementQueries)
        }

    @Test
    fun `the page is complete after a prefetch, with the authors' profiles, and opening it asks for no profile`() =
        runTest {
            val querier = FakeQuerier(
                follows = friends(1),
                notes = listOf(note("seen", STRANGER, 10)),
                reactions = listOf(reaction(friend(0), "seen")),
                profiles = listOf(profile(STRANGER)),
            )
            val fetcher = fetcher(querier)

            fetcher.prefetchNetworkNotes(ME)
            assertEquals(1, querier.metadataQueries)

            val page = fetcher.fetchPage(
                userId = ME,
                feedSpec = networkNotesFeedSpec,
                includeReplies = false,
                limit = 20,
            )
            val profiles = fetcher.fetchMetadataFor(page)

            assertEquals(listOf(STRANGER), profiles.map { it.pubKey })
            assertEquals(1, querier.metadataQueries)
        }

    @Test
    fun `a note the early counters did not cover is still counted before the list is ranked`() =
        runTest {
            // 100 notes by friends fill the early counters (they have the most friends behind them) and are then left
            // out of the list; the one stranger's note comes last in that order and is counted afterwards.
            val byFriends = List(100) { note("friend-note-$it", friend(1), 100L + it) }
            val querier = FakeQuerier(
                follows = friends(2),
                notes = byFriends + note("late", STRANGER, 10),
                reactions = byFriends.flatMap { listOf(reaction(friend(0), it.id), reaction(friend(1), it.id)) } +
                    reaction(friend(0), "late"),
            )

            val page = fetcher(querier).fetchPage(
                userId = ME,
                feedSpec = networkNotesFeedSpec,
                includeReplies = false,
                limit = 20,
            )

            assertEquals(listOf("late"), page.page.map { it.id })
            assertEquals(1L, page.stats?.eventStats?.single { it.eventId == "late" }?.likes)
        }

    @Test
    fun `a request that arrives while the network feed is being resolved waits for it instead of repeating it`() =
        runTest {
            val querier = FakeQuerier(
                follows = friends(1),
                notes = listOf(note("seen", STRANGER, 10)),
                reactions = listOf(reaction(friend(0), "seen")),
                engagementDelayMs = 1_000,
            )
            val fetcher = fetcher(querier)

            val prefetch = async { fetcher.prefetchNetworkNotes(ME) }
            val page = async { fetcher.fetchNetwork() }
            prefetch.await()

            assertEquals(listOf("seen"), page.await().notes.map { it.id })
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

    private suspend fun RelayNotesFeedFetcher.fetchNetwork(limit: Int = 20, until: Long? = null, since: Long? = null) =
        fetch(
            userId = ME,
            feedSpec = networkNotesFeedSpec,
            includeReplies = false,
            limit = limit,
            until = until,
            since = since,
        )

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
        /** Interactions by people who are not the user's friends, e.g. other readers' reactions, replies, zaps. */
        private val otherInteractions: List<NostrEvent> = emptyList(),
        /** How long the friends' reactions take to arrive, to have two callers overlap. */
        private val engagementDelayMs: Long = 0,
        /** The kind 0 events relays have, by author. */
        private val profiles: List<NostrEvent> = emptyList(),
    ) : RelayEventQuerier {
        /** How many times profiles were asked for. */
        var metadataQueries = 0
        /** How many times the friends' reactions and reposts were asked for (a query that names authors). */
        var engagementQueries = 0

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            val kinds = filter.kinds.orEmpty()
            return when {
                NostrEventKind.FollowList.value in kinds -> listOf(followList())

                NostrEventKind.Metadata.value in kinds -> {
                    metadataQueries++
                    profiles.filter { it.pubKey in filter.authors.orEmpty() }
                }

                filter.eventTags != null -> {
                    // The counters of the shortlisted notes: every event that points at one of them.
                    val wanted = filter.eventTags.orEmpty().toSet()
                    (reactions + otherInteractions).filter { event ->
                        event.kind in kinds && event.tags.any { it.size > 1 && it[1].toString().trim('"') in wanted }
                    }
                }

                NostrEventKind.Reaction.value in kinds -> {
                    engagementQueries++
                    if (engagementDelayMs > 0) delay(engagementDelayMs)
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

        fun profile(author: String) =
            NostrEvent(
                id = "profile-$author",
                pubKey = author,
                createdAt = 1_700_000_000L,
                kind = NostrEventKind.Metadata.value,
                tags = emptyList(),
                content = "{\"name\":\"someone\"}",
                sig = "sig",
            )

        fun reply(by: String, target: String) =
            NostrEvent(
                id = "reply-$by-$target",
                pubKey = by,
                createdAt = 1_700_000_200L,
                kind = NostrEventKind.ShortTextNote.value,
                tags = listOf(tag("e", target)),
                content = "a reply",
                sig = "sig",
            )

        fun zap(id: String, target: String, sats: Long) =
            NostrEvent(
                id = id,
                pubKey = "ee".padEnd(64, '0'),
                createdAt = 1_700_000_300L,
                kind = NostrEventKind.Zap.value,
                tags = listOf(tag("e", target), tag("amount", (sats * 1_000).toString())),
                content = "",
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
