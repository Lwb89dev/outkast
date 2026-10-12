package net.primal.data.repository.articles.paging

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.findCommentRootAddress
import net.primal.domain.nostr.findCommentRootEventId
import net.primal.domain.nostr.relay.RelayEventQuerier
import net.primal.domain.nostr.relay.RelayFilter

/**
 * Comments under an article exist in two dialects, and the page has to show both: NIP-22 (kind 1111,
 * the article's address in an uppercase `A` on every comment of the tree) and the kind 1 replies
 * several older clients still write.
 */
class RelayArticleDetailsFetcherTest {

    private val address = "30023:$AUTHOR:$IDENTIFIER"

    private val article = event(
        id = ARTICLE_ID,
        kind = NostrEventKind.LongFormContent.value,
        pubkey = AUTHOR,
        tags = listOf(tag("d", IDENTIFIER)),
    )

    @Test
    fun fetch_returnsNip22CommentsAtEveryDepth_andLegacyKind1Replies() = runTest {
        val top = comment("top", "bob", parent = null)
        val nested = comment("nested", "carol", parent = "top")
        val deeper = comment("deeper", "dave", parent = "nested")
        val legacyByAddress = event("legacy-a", NostrEventKind.ShortTextNote.value, "erin", listOf(tag("a", address)))
        val legacyByEvent = event("legacy-e", NostrEventKind.ShortTextNote.value, "frank", listOf(tag("e", ARTICLE_ID)))
        val elsewhere = event(
            "elsewhere",
            NostrEventKind.Comment.value,
            "gina",
            listOf(tag("A", "30023:$AUTHOR:another"), tag("K", "30023")),
        )
        val all = listOf(article, top, nested, deeper, legacyByAddress, legacyByEvent, elsewhere)

        val response = RelayArticleDetailsFetcher(FakeQuerier(all))
            .fetch(articleId = IDENTIFIER, articleAuthorId = AUTHOR, commentLimit = 100)

        response.notes.map { it.id } shouldContainExactlyInAnyOrder
            listOf("top", "nested", "deeper", "legacy-a", "legacy-e")
        response.articles.map { it.id } shouldBe listOf(ARTICLE_ID)
    }

    @Test
    fun fetch_newestCommentFirst() = runTest {
        val old = comment("old", "bob", parent = null, at = 10)
        val recent = comment("recent", "carol", parent = null, at = 20)

        val response = RelayArticleDetailsFetcher(FakeQuerier(listOf(article, old, recent)))
            .fetch(articleId = IDENTIFIER, articleAuthorId = AUTHOR, commentLimit = 100)

        response.notes.map { it.id } shouldBe listOf("recent", "old")
    }

    // ------------------------------------------------------------------------------- harness

    private fun comment(id: String, pubkey: String, parent: String?, at: Long = 10): NostrEvent =
        event(
            id = id,
            kind = NostrEventKind.Comment.value,
            pubkey = pubkey,
            createdAt = at,
            tags = listOfNotNull(
                tag("A", address),
                tag("K", "30023"),
                parent?.let { tag("e", it) } ?: tag("a", address),
                tag("k", if (parent == null) "30023" else "1111"),
            ),
        )

    private fun event(
        id: String,
        kind: Int,
        pubkey: String,
        tags: List<JsonArray> = emptyList(),
        createdAt: Long = 10,
    ) = NostrEvent(
        id = id,
        pubKey = pubkey,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = "text",
        sig = "sig",
    )

    private fun tag(name: String, value: String) = buildJsonArray {
        add(JsonPrimitive(name))
        add(JsonPrimitive(value))
    }

    private class FakeQuerier(private val events: List<NostrEvent>) : RelayEventQuerier {
        override suspend fun query(filter: RelayFilter): List<NostrEvent> =
            events.filter { matches(it, filter) }.let { matched -> filter.limit?.let { matched.take(it) } ?: matched }

        private fun matches(event: NostrEvent, filter: RelayFilter): Boolean {
            if (filter.kinds != null && event.kind !in filter.kinds.orEmpty()) return false
            if (filter.authors != null && event.pubKey !in filter.authors.orEmpty()) return false
            if (filter.ids != null && event.id !in filter.ids.orEmpty()) return false
            if (filter.addressTags != null && !event.hasTagValue("a", filter.addressTags.orEmpty())) return false
            if (filter.identifierTags != null && !event.hasTagValue("d", filter.identifierTags.orEmpty())) return false
            if (filter.eventTags != null && !event.hasTagValue("e", filter.eventTags.orEmpty())) return false
            val rootAddresses = filter.rootAddressTags
            if (rootAddresses != null && event.tags.findCommentRootAddress() !in rootAddresses) return false
            val rootEvents = filter.rootEventTags
            if (rootEvents != null && event.tags.findCommentRootEventId() !in rootEvents) return false
            return true
        }

        private fun NostrEvent.hasTagValue(name: String, values: List<String>): Boolean =
            tags.any { it.getOrNull(0)?.let { n -> (n as JsonPrimitive).content } == name && valueOf(it) in values }

        private fun valueOf(tag: JsonArray) = (tag[1] as JsonPrimitive).content
    }

    private companion object {
        const val AUTHOR = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
        const val IDENTIFIER = "my-article"
        const val ARTICLE_ID = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100"
    }
}
