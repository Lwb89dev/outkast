package net.primal.data.repository.feed

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.cryptography.utils.hexToNoteHrp
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/**
 * The list behind a note's quote counter. Quotes come in two shapes depending on the client that wrote
 * them, and a reply that mentions the note must not be taken for one.
 */
class RelayQuotesFetcherTest {

    private val link = "nostr:${TARGET.hexToNoteHrp()}"

    @Test
    fun a_note_quoting_through_a_q_tag_is_found() = runTest {
        val quote = note("q1", "bob", at = 20, tags = listOf(tag("q", TARGET)))

        val found = RelayQuotesFetcher(FakeQuerier(listOf(target(), quote))).fetch(noteId = TARGET, limit = 50)

        found.quotes.map { it.id } shouldContainExactly listOf("q1")
    }

    @Test
    fun a_note_quoting_through_an_e_mention_and_a_link_in_its_text_is_found() = runTest {
        val quote = note(
            id = "old-style",
            pubkey = "carol",
            at = 20,
            content = "look at this $link",
            tags = listOf(tag("e", TARGET, "", "mention")),
        )

        val found = RelayQuotesFetcher(FakeQuerier(listOf(target(), quote))).fetch(noteId = TARGET, limit = 50)

        found.quotes.map { it.id } shouldContainExactly listOf("old-style")
    }

    @Test
    fun a_reply_is_not_a_quote_even_when_it_embeds_the_note_it_answers() = runTest {
        val plainReply = note("reply", "dave", at = 20, tags = listOf(tag("e", TARGET, "", "reply")))
        val replyEmbedding = note(
            id = "reply-with-link",
            pubkey = "erin",
            at = 21,
            content = "as in $link",
            tags = listOf(tag("e", TARGET, "", "reply"), tag("q", TARGET)),
        )

        val found = RelayQuotesFetcher(FakeQuerier(listOf(target(), plainReply, replyEmbedding)))
            .fetch(noteId = TARGET, limit = 50)

        found.quotes shouldBe emptyList()
    }

    @Test
    fun an_unrelated_note_that_quotes_something_else_is_left_out() = runTest {
        val elsewhere = note("elsewhere", "frank", at = 20, tags = listOf(tag("q", "f".repeat(64))))

        val found = RelayQuotesFetcher(FakeQuerier(listOf(target(), elsewhere))).fetch(noteId = TARGET, limit = 50)

        found.quotes shouldBe emptyList()
    }

    @Test
    fun a_note_found_by_both_queries_is_listed_once_and_the_newest_comes_first() = runTest {
        val older = note(
            id = "older",
            pubkey = "gina",
            at = 10,
            content = link,
            tags = listOf(tag("q", TARGET), tag("e", TARGET, "", "mention")),
        )
        val newer = note("newer", "hank", at = 30, tags = listOf(tag("q", TARGET)))

        val found = RelayQuotesFetcher(FakeQuerier(listOf(target(), older, newer))).fetch(noteId = TARGET, limit = 50)

        found.quotes.map { it.id } shouldContainExactly listOf("newer", "older")
    }

    @Test
    fun the_quoted_note_and_the_authors_profiles_come_with_the_quotes() = runTest {
        val quote = note("q1", "bob", at = 20, tags = listOf(tag("q", TARGET)))
        val profile = NostrEvent(
            id = "meta",
            pubKey = "bob",
            createdAt = 5,
            kind = NostrEventKind.Metadata.value,
            tags = emptyList(),
            content = "{\"name\":\"bob\"}",
            sig = "sig",
        )

        val found = RelayQuotesFetcher(FakeQuerier(listOf(target(), quote, profile))).fetch(noteId = TARGET, limit = 50)

        found.quotedNote?.id shouldBe TARGET
        found.metadata.map { it.pubKey } shouldContainExactly listOf("bob")
    }

    @Test
    fun the_quote_query_asks_for_text_notes_that_name_the_note_in_a_q_tag() = runTest {
        val querier = RecordingQuerier(FakeQuerier(listOf(target())))

        RelayQuotesFetcher(querier).fetch(noteId = TARGET, limit = 50)

        val asksForQuoteTag = querier.sent.any {
            it.quoteTags == listOf(TARGET) && it.kinds == listOf(NostrEventKind.ShortTextNote.value)
        }
        asksForQuoteTag shouldBe true
    }

    // ------------------------------------------------------------------------------- harness

    private fun target() = note(TARGET, "alice", at = 1)

    private fun note(
        id: String,
        pubkey: String,
        at: Long,
        content: String = "text",
        tags: List<JsonArray> = emptyList(),
    ) = NostrEvent(
        id = id,
        pubKey = pubkey,
        createdAt = at,
        kind = NostrEventKind.ShortTextNote.value,
        tags = tags,
        content = content,
        sig = "sig",
    )

    private fun tag(vararg values: String): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private class FakeQuerier(private val events: List<NostrEvent>) : RelayEventQuerier {
        override suspend fun query(filter: RelayFilter): List<NostrEvent> =
            events.filter { matches(it, filter) }.let { found -> filter.limit?.let { found.take(it) } ?: found }

        private fun matches(event: NostrEvent, filter: RelayFilter): Boolean {
            if (filter.kinds != null && event.kind !in filter.kinds.orEmpty()) return false
            if (filter.ids != null && event.id !in filter.ids.orEmpty()) return false
            if (filter.authors != null && event.pubKey !in filter.authors.orEmpty()) return false
            if (filter.eventTags != null && !event.hasTagValue("e", filter.eventTags.orEmpty())) return false
            if (filter.quoteTags != null && !event.hasTagValue("q", filter.quoteTags.orEmpty())) return false
            return true
        }

        private fun NostrEvent.hasTagValue(name: String, values: List<String>): Boolean =
            tags.any { (it[0] as JsonPrimitive).content == name && (it[1] as JsonPrimitive).content in values }
    }

    private class RecordingQuerier(private val delegate: RelayEventQuerier) : RelayEventQuerier {
        val sent = mutableListOf<RelayFilter>()

        override suspend fun query(filter: RelayFilter): List<NostrEvent> {
            sent += filter
            return delegate.query(filter)
        }
    }

    private companion object {
        const val TARGET = "33c4ce1a423a65d403f0871e8fd2619f61af57d45830080c50a6896569544c7b"
    }
}
