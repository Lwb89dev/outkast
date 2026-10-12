package net.primal.domain.nostr

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

class Nip22Test {

    private fun tag(vararg values: String): JsonArray = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private val article = CommentTarget.Address(
        kind = 30023,
        authorPubkey = AUTHOR,
        identifier = "my-article",
        relayHint = "wss://relay.example",
    )

    @Test
    fun `a top-level comment on an article names the article as both root and parent`() {
        val tags = buildCommentTags(root = article, parent = article)

        tags shouldBe listOf(
            tag("A", "30023:$AUTHOR:my-article", "wss://relay.example"),
            tag("K", "30023"),
            tag("P", AUTHOR, "wss://relay.example"),
            tag("a", "30023:$AUTHOR:my-article", "wss://relay.example"),
            tag("k", "30023"),
            tag("p", AUTHOR, "wss://relay.example"),
        )
    }

    @Test
    fun `a reply to a comment keeps the article as root and names the comment as parent`() {
        val comment = CommentTarget.Event(
            eventId = COMMENT_ID,
            kind = 1111,
            authorPubkey = COMMENTER,
            relayHint = "wss://other.example",
        )

        val tags = buildCommentTags(root = article, parent = comment)

        tags shouldBe listOf(
            tag("A", "30023:$AUTHOR:my-article", "wss://relay.example"),
            tag("K", "30023"),
            tag("P", AUTHOR, "wss://relay.example"),
            tag("e", COMMENT_ID, "wss://other.example", COMMENTER),
            tag("k", "1111"),
            tag("p", COMMENTER, "wss://other.example"),
        )
    }

    @Test
    fun `a relay hint that is missing is left out, but never shifts the pubkey of an event tag`() {
        val picture = CommentTarget.Event(eventId = ROOT_ID, kind = 20, authorPubkey = AUTHOR)

        val tags = buildCommentTags(root = picture, parent = picture)

        tags[0] shouldBe tag("E", ROOT_ID, "", AUTHOR)
        tags[2] shouldBe tag("P", AUTHOR)
    }

    @Test
    fun `the root of a comment can be read back and carried into a reply`() {
        val comment = CommentTarget.Event(eventId = COMMENT_ID, kind = 1111, authorPubkey = COMMENTER)
        val tags = buildCommentTags(root = article, parent = comment)

        tags.findCommentRootTarget() shouldBe article
        tags.findCommentRootAddress() shouldBe "30023:$AUTHOR:my-article"
        tags.findCommentRootKind() shouldBe 30023
        tags.findCommentRootAuthor() shouldBe AUTHOR
        tags.findCommentParentEventId() shouldBe COMMENT_ID
        tags.findCommentParentKind() shouldBe 1111
        tags.findCommentParentAuthor() shouldBe COMMENTER
    }

    @Test
    fun `an event root is read back with the author from its own tag, or from P`() {
        val withAuthorInTag = listOf(tag("E", ROOT_ID, "", AUTHOR), tag("K", "20"))
        val withAuthorInP = listOf(tag("E", ROOT_ID), tag("K", "20"), tag("P", AUTHOR))

        val expected = CommentTarget.Event(eventId = ROOT_ID, kind = 20, authorPubkey = AUTHOR)
        withAuthorInTag.findCommentRootTarget() shouldBe expected
        withAuthorInP.findCommentRootTarget() shouldBe expected
    }

    @Test
    fun `an identifier that itself contains colons survives the round trip`() {
        val odd = CommentTarget.Address(kind = 30023, authorPubkey = AUTHOR, identifier = "a:b:c")

        buildCommentTags(root = odd, parent = odd).findCommentRootTarget() shouldBe odd
    }

    @Test
    fun `a root nobody could point a reply at reads back as nothing`() {
        // An external root (a web page, tag I) and a root without its kind cannot be carried forward.
        listOf(tag("I", "https://example.com"), tag("K", "web")).findCommentRootTarget() shouldBe null
        listOf(tag("E", ROOT_ID, "", AUTHOR)).findCommentRootTarget() shouldBe null
        emptyList<JsonArray>().findCommentRootTarget() shouldBe null
    }

    @Test
    fun `the uppercase and lowercase scopes are never mistaken for each other`() {
        val tags = listOf(tag("e", COMMENT_ID), tag("E", ROOT_ID), tag("p", COMMENTER), tag("P", AUTHOR))

        tags.findCommentParentEventId() shouldBe COMMENT_ID
        tags.findCommentRootEventId() shouldBe ROOT_ID
        tags.findCommentParentAuthor() shouldBe COMMENTER
        tags.findCommentRootAuthor() shouldBe AUTHOR
    }

    private companion object {
        const val AUTHOR = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
        const val COMMENTER = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        const val COMMENT_ID = "1111111111111111111111111111111111111111111111111111111111111111"
        const val ROOT_ID = "2222222222222222222222222222222222222222222222222222222222222222"
    }
}
