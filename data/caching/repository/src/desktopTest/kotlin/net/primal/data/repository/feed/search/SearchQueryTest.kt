package net.primal.data.repository.feed.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.domain.nostr.NostrEvent

class SearchQueryTest {

    private fun word(text: String) = SearchTerm.Word(text)

    @Test
    fun `words are all required and an exact phrase stays together`() {
        val query = SearchQuery.parse("Bitcoin \"Lightning Network\"")

        assertEquals(listOf(listOf(word("bitcoin")), listOf(SearchTerm.Phrase("lightning network"))), query.groups)
    }

    @Test
    fun `upper case OR joins its neighbours only and lower case or is a word`() {
        val either = SearchQuery.parse("caffe OR tè mattina")
        assertEquals(listOf(listOf(word("caffe"), word("tè")), listOf(word("mattina"))), either.groups)

        val literal = SearchQuery.parse("now or never")
        assertEquals(listOf(listOf(word("now")), listOf(word("or")), listOf(word("never"))), literal.groups)
    }

    @Test
    fun `people operators keep the names as typed`() {
        val query = SearchQuery.parse("from:@alice to:bob @carol -from:dave ciao")

        assertEquals(listOf("alice"), query.from)
        assertEquals(listOf("bob"), query.to)
        assertEquals(listOf("carol"), query.mentions)
        assertEquals(listOf("dave"), query.notFrom)
        assertEquals(listOf(listOf(word("ciao"))), query.groups)
    }

    @Test
    fun `the advanced search author group does not turn the words into alternatives`() {
        val query = SearchQuery.parse("kind:1 bitcoin lightning (from:npub1aaa OR from:npub1bbb) pas:1")

        assertEquals(listOf(listOf(word("bitcoin")), listOf(word("lightning"))), query.groups)
        assertEquals(listOf("npub1aaa", "npub1bbb"), query.from)
        assertEquals(listOf(1), query.kinds)
    }

    @Test
    fun `exclusions hashtags filters dates and minimums are read`() {
        val query = SearchQuery.parse(
            "#Nostr -spam -\"buy now\" filter:media -filter:replies min_faves:5 " +
                "since:2026-01-02 until:2026-01-03 orderby:top",
        )

        assertEquals(listOf(listOf(SearchTerm.Hashtag("nostr"))), query.groups)
        assertEquals(listOf(word("spam"), SearchTerm.Phrase("buy now")), query.excluded)
        assertEquals(setOf(ContentFilter.Media), query.requiredFilters)
        assertEquals(setOf(ContentFilter.Replies), query.excludedFilters)
        assertEquals(5, query.minimums.likes)
        assertEquals(1_767_312_000L, query.since)
        assertEquals(1_767_398_400L, query.until)
        assertEquals(SearchOrder.Top, query.order)
    }

    @Test
    fun `a colon in ordinary text is not taken for a command`() {
        val query = SearchQuery.parse("ratio:3 https://example.com")

        assertEquals(listOf(listOf(word("ratio:3")), listOf(word("https://example.com"))), query.groups)
    }

    private fun note(
        content: String,
        author: String = "author",
        tags: List<List<String>> = emptyList(),
    ) = NostrEvent(
        id = content,
        pubKey = author,
        createdAt = 1L,
        kind = 1,
        tags = tags.map { tag -> buildJsonArray { tag.forEach { add(JsonPrimitive(it)) } } },
        content = content,
        sig = "",
    )

    private fun matches(
        text: String,
        event: NostrEvent,
        people: SearchPeople = SearchPeople(),
    ) = SearchMatcher(SearchQuery.parse(text), people).matches(event)

    @Test
    fun `a hashtag matches the tag or the whole word in the text`() {
        assertEquals(true, matches("#nostr", note("amo #nostr!")))
        assertEquals(true, matches("#nostr", note("senza cancelletto", tags = listOf(listOf("t", "Nostr")))))
        assertEquals(false, matches("#nostr", note("il mio #nostrich")))
    }

    @Test
    fun `exclusions and filters are applied to the post`() {
        assertEquals(false, matches("caffè -zucchero", note("caffè con zucchero")))
        assertEquals(true, matches("lago filter:media", note("lago https://example.com/a.jpg")))
        assertEquals(false, matches("lago filter:media", note("lago senza foto")))
        val reply = note("risposta", tags = listOf(listOf("e", "root", "", "root")))
        assertEquals(false, matches("risposta -filter:replies", reply))
        assertEquals(true, matches("risposta filter:replies", reply))
    }

    @Test
    fun `a mention finds what the person wrote and what names them`() {
        val people = SearchPeople(mentions = setOf("alice"))

        assertEquals(true, matches("@alice", note("mio", author = "alice"), people))
        assertEquals(true, matches("@alice", note("per te", tags = listOf(listOf("p", "alice"))), people))
        assertEquals(false, matches("@alice", note("altro"), people))
    }
}
