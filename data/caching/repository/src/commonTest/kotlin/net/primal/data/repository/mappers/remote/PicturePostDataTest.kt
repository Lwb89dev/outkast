package net.primal.data.repository.mappers.remote

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind

class PicturePostDataTest {

    @Test
    fun captionAndImetaImagesBecomeTheStoredNote() {
        val post = picture(
            content = "tramonto #mialuna",
            tags = listOf(imeta("https://cdn.example/a.jpg")),
        ).toPicturePostDataOrNull(nowEpochSeconds = 1_000)

        post.shouldNotBeNull()
        post.content shouldBe "tramonto #mialuna"
        post.uris.first() shouldBe "https://cdn.example/a.jpg"
        post.hashtags shouldBe listOf("#mialuna")
        post.kind shouldBe NostrEventKind.PictureNote.value
        post.replyToPostId shouldBe null
    }

    @Test
    fun closeFriendsAndEncryptedPicturesAreDropped() {
        val encrypted = picture(
            content = "privata",
            tags = listOf(
                buildJsonArray {
                    add(JsonPrimitive("imeta"))
                    add(JsonPrimitive("url https://cdn.example/secret"))
                    add(JsonPrimitive("m image/jpeg"))
                    add(JsonPrimitive("decryption-key abc"))
                },
            ),
        )
        val audience = picture(
            content = "amici",
            tags = listOf(
                imeta("https://cdn.example/a.jpg"),
                buildJsonArray {
                    add(JsonPrimitive("audience"))
                    add(JsonPrimitive("close-friends"))
                },
            ),
        )

        encrypted.toPicturePostDataOrNull(nowEpochSeconds = 1_000).shouldBeNull()
        audience.toPicturePostDataOrNull(nowEpochSeconds = 1_000).shouldBeNull()
    }

    private fun picture(content: String, tags: List<kotlinx.serialization.json.JsonArray>) =
        NostrEvent(
            id = "p1",
            pubKey = "alice",
            createdAt = 10,
            kind = NostrEventKind.PictureNote.value,
            tags = tags,
            content = content,
            sig = "sig",
        )

    private fun imeta(url: String) = buildJsonArray {
        add(JsonPrimitive("imeta"))
        add(JsonPrimitive("url $url"))
        add(JsonPrimitive("m image/jpeg"))
    }
}
