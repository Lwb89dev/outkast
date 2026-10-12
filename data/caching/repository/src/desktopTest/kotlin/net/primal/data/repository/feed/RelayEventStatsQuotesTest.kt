package net.primal.data.repository.feed

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.data.repository.fetch.FetchCoordinator
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.cryptography.utils.hexToNoteHrp
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/**
 * The counters under a note: a quote is neither a reply nor a repost, and is counted on its own. Quotes
 * carry the note in a `q` tag rather than an `e` tag, so they are only found by asking for them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayEventStatsQuotesTest {

    private val link = "nostr:${TARGET.hexToNoteHrp()}"

    @Test
    fun a_quote_is_counted_as_a_quote_and_not_as_a_reply() =
        runTest {
            val quoteByTag = note("q1", tags = listOf(tag("q", TARGET)))
            val oldStyleQuote = note("q2", content = link, tags = listOf(tag("e", TARGET, "", "mention")))
            val reply = note("r1", tags = listOf(tag("e", TARGET, "", "reply")))

            val stats = statsFor(quoteByTag, oldStyleQuote, reply)

            stats.mentions shouldBe 2L
            stats.replies shouldBe 1L
        }

    @Test
    fun a_reply_that_embeds_the_note_it_answers_is_counted_once_as_a_reply() =
        runTest {
            val reply = note("r1", content = link, tags = listOf(tag("e", TARGET, "", "reply"), tag("q", TARGET)))

            val stats = statsFor(reply)

            stats.replies shouldBe 1L
            stats.mentions shouldBe 0L
        }

    @Test
    fun likes_and_reposts_are_not_quotes() =
        runTest {
            val like = event("l1", NostrEventKind.Reaction.value, tags = listOf(tag("e", TARGET)))
            val repost = event("rp1", NostrEventKind.ShortTextNoteRepost.value, tags = listOf(tag("e", TARGET)))

            val stats = statsFor(like, repost)

            stats.likes shouldBe 1L
            stats.reposts shouldBe 1L
            stats.mentions shouldBe 0L
        }

    @Test
    fun quotes_are_asked_for_with_a_q_filter() =
        runTest {
            val sent = mutableListOf<RelayFilter>()
            val querier = object : RelayEventQuerier {
                override suspend fun query(filter: RelayFilter): List<NostrEvent> {
                    sent += filter
                    return emptyList()
                }
            }

            fetcher(querier).fetch(eventIds = listOf(TARGET), userId = ME)

            sent.any { it.quoteTags == listOf(TARGET) } shouldBe true
        }

    // ------------------------------------------------------------------------------- harness

    private suspend fun TestScope.statsFor(vararg interactions: NostrEvent) =
        fetcher(InteractionQuerier(interactions.toList())).fetch(eventIds = listOf(TARGET), userId = ME)
            .eventStats.single()

    private fun TestScope.fetcher(querier: RelayEventQuerier): RelayEventStatsFetcher {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return RelayEventStatsFetcher(
            querier = querier,
            coordinator = FetchCoordinator(
                dispatcherProvider = mockk<DispatcherProvider> {
                    every { io() } returns dispatcher
                    every { main() } returns dispatcher
                },
            ),
        )
    }

    /** Answers like a relay: each query gets the interactions whose tags match what it asked for. */
    private class InteractionQuerier(private val events: List<NostrEvent>) : RelayEventQuerier {
        override suspend fun query(filter: RelayFilter): List<NostrEvent> =
            events.filter { event ->
                (filter.kinds == null || event.kind in filter.kinds.orEmpty()) &&
                    (filter.eventTags == null || event.hasTag("e", filter.eventTags.orEmpty())) &&
                    (filter.quoteTags == null || event.hasTag("q", filter.quoteTags.orEmpty()))
            }

        private fun NostrEvent.hasTag(name: String, values: List<String>) =
            tags.any { (it[0] as JsonPrimitive).content == name && (it[1] as JsonPrimitive).content in values }
    }

    private fun note(id: String, content: String = "text", tags: List<JsonArray> = emptyList()) =
        event(id, NostrEventKind.ShortTextNote.value, content, tags)

    private fun event(id: String, kind: Int, content: String = "", tags: List<JsonArray> = emptyList()) =
        NostrEvent(
            id = id,
            pubKey = "someone",
            createdAt = 10,
            kind = kind,
            tags = tags,
            content = content,
            sig = "sig",
        )

    private fun tag(vararg values: String): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private companion object {
        const val TARGET = "33c4ce1a423a65d403f0871e8fd2619f61af57d45830080c50a6896569544c7b"
        const val ME = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
    }
}
