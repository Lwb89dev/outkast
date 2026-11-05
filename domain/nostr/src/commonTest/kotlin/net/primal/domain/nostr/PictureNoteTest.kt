package net.primal.domain.nostr

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

class PictureNoteTest {

    @Test
    fun aPictureKeepsItsCaptionAndReadsImagesFromImeta() {
        val event = picture(
            content = "tramonto a procida\n#mialuna",
            tags = listOf(
                imeta("https://cdn.example/a.jpg", "image/jpeg"),
                imeta("https://cdn.example/b.jpg", "image/webp"),
                buildJsonArray {
                    add(JsonPrimitive("t"))
                    add(JsonPrimitive("mialuna"))
                },
            ),
        )

        event.isDisplayablePictureNote(1_000) shouldBe true
        event.pictureImageUrls() shouldBe listOf("https://cdn.example/a.jpg", "https://cdn.example/b.jpg")
        event.pictureCaption() shouldBe "tramonto a procida\n#mialuna"
    }

    @Test
    fun titleIsShownAboveTheCaptionAndARepeatedUrlIsNotASecondPicture() {
        val event = picture(
            content = "https://cdn.example/a.jpg\nil mare",
            tags = listOf(
                buildJsonArray {
                    add(JsonPrimitive("title"))
                    add(JsonPrimitive("Procida"))
                },
                imeta("https://cdn.example/a.jpg", "image/jpeg"),
                buildJsonArray {
                    add(JsonPrimitive("url"))
                    add(JsonPrimitive("https://cdn.example/a.jpg"))
                },
            ),
        )

        event.pictureCaption() shouldBe "Procida\n\nil mare"
        event.pictureImageUrls() shouldBe listOf("https://cdn.example/a.jpg")
    }

    @Test
    fun anEncryptedOrCloseFriendsOrExpiredOrVideoPictureIsNotShown() {
        val encrypted = picture(
            content = "privata",
            tags = listOf(
                buildJsonArray {
                    add(JsonPrimitive("imeta"))
                    add(JsonPrimitive("url https://cdn.example/blob"))
                    add(JsonPrimitive("m image/jpeg"))
                    add(JsonPrimitive("encryption-algorithm aes-gcm"))
                    add(JsonPrimitive("decryption-key abc"))
                },
            ),
        )
        val closeFriends = picture(
            content = "amici",
            tags = listOf(
                imeta("https://cdn.example/a.jpg", "image/jpeg"),
                buildJsonArray {
                    add(JsonPrimitive("audience"))
                    add(JsonPrimitive("close-friends"))
                },
            ),
        )
        val expired = picture(
            content = "storia",
            tags = listOf(
                imeta("https://cdn.example/a.jpg", "image/jpeg"),
                buildJsonArray {
                    add(JsonPrimitive("expiration"))
                    add(JsonPrimitive("500"))
                },
            ),
        )
        val video = picture(
            content = "reel",
            tags = listOf(imeta("https://cdn.example/v.mp4", "video/mp4")),
        )

        encrypted.isDisplayablePictureNote(1_000) shouldBe false
        closeFriends.isDisplayablePictureNote(1_000) shouldBe false
        expired.isDisplayablePictureNote(1_000) shouldBe false
        expired.isDisplayablePictureNote(100) shouldBe true
        video.isDisplayablePictureNote(1_000) shouldBe false
    }

    @Test
    fun contentWarningIsTheTagNotTheCaption() {
        val tags = listOf(
            buildJsonArray {
                add(JsonPrimitive("content-warning"))
                add(JsonPrimitive("nudità"))
            },
        )
        tags.contentWarning() shouldBe "nudità"
        emptyList<kotlinx.serialization.json.JsonArray>().contentWarning() shouldBe null
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

    private fun imeta(url: String, mime: String) = buildJsonArray {
        add(JsonPrimitive("imeta"))
        add(JsonPrimitive("url $url"))
        add(JsonPrimitive("m $mime"))
    }
}
