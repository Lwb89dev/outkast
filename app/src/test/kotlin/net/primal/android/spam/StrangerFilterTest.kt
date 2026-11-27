package net.primal.android.spam

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.primal.core.utils.serialization.CommonJson
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.calculateEventId
import net.primal.domain.nostr.cryptography.utils.toHex
import net.primal.domain.nostr.pow.PowMiner
import net.primal.domain.notifications.Notification
import net.primal.domain.notifications.NotificationType
import org.junit.Test

class StrangerFilterTest {

    private val stranger = "b".repeat(64)
    private val friend = "c".repeat(64)
    private val friendOfFriend = "d".repeat(64)
    private val powFilter = StrangerFilter(following = setOf(friend), minimumBits = 10)
    private val networkOnly = StrangerFilter(following = setOf(friend), network = setOf(friendOfFriend))

    private fun note(author: String) =
        NostrUnsignedEvent(pubKey = author, createdAt = 1_700_000_000L, kind = 1, content = "ciao")

    private fun NostrUnsignedEvent.raw(): String =
        CommonJson.encodeToString(
            NostrEvent(calculateEventId().toHex(), pubKey, createdAt, kind, tags, content, sig = ""),
        )

    private suspend fun mined(author: String, bits: Int) = PowMiner().mine(note(author), bits).raw()

    // The note of a reply or mention carries the notification's own id; a liked post does not.
    private fun notification(type: NotificationType, actor: String) =
        mockk<Notification>(relaxed = true) {
            every { this@mockk.type } returns type
            every { actionUserId } returns actor
            every { actionOnPost } returns null
        }

    @Test
    fun trustedAuthorsNeedNoWork() {
        powFilter.hides(authorId = friend, rawEvent = note(friend).raw()) shouldBe false
    }

    @Test
    fun aStrangerNeedsAtLeastTheMinimum() =
        runTest {
            powFilter.hides(authorId = stranger, rawEvent = note(stranger).raw()) shouldBe true
            powFilter.hides(authorId = stranger, rawEvent = mined(stranger, 8)) shouldBe true
            powFilter.hides(authorId = stranger, rawEvent = mined(stranger, 10)) shouldBe false
        }

    @Test
    fun workBorrowedFromAnotherEventDoesNotCount() =
        runTest {
            val genuine = mined(stranger, 10)
            val tampered = genuine.replace("ciao", "spam")

            powFilter.hides(authorId = stranger, rawEvent = genuine) shouldBe false
            powFilter.hides(authorId = stranger, rawEvent = tampered) shouldBe true
        }

    @Test
    fun underProofOfWorkWhatCannotBeCheckedIsNotHidden() {
        powFilter.hides(authorId = stranger, rawEvent = null) shouldBe false
        powFilter.hides(authorId = stranger, rawEvent = "{") shouldBe false
        powFilter.hides(authorId = stranger, rawEvent = note(stranger).raw(), isPrivate = true) shouldBe false
    }

    @Test
    fun networkOnlyHidesAStrangerWhateverTheWork() =
        runTest {
            networkOnly.hides(authorId = stranger, rawEvent = mined(stranger, 10)) shouldBe true
            networkOnly.hides(authorId = stranger, rawEvent = null) shouldBe true
            networkOnly.hides(authorId = stranger, rawEvent = null, isPrivate = true) shouldBe true
            networkOnly.hides(authorId = friend, rawEvent = null) shouldBe false
            networkOnly.hides(authorId = friendOfFriend, rawEvent = null) shouldBe false
        }

    @Test
    fun aStrangersWritingRaisesNoNotificationButTheirLikeDoes() {
        listOf(
            NotificationType.YOUR_POST_WAS_REPLIED_TO,
            NotificationType.YOU_WERE_MENTIONED_IN_POST,
            NotificationType.YOUR_POST_WAS_MENTIONED_IN_POST,
            NotificationType.REPLY_TO_REPLY,
        ).forEach { type ->
            networkOnly.hides(notification(type, actor = stranger)) shouldBe true
            networkOnly.hides(notification(type, actor = friendOfFriend)) shouldBe false
        }
        networkOnly.hides(notification(NotificationType.YOUR_POST_WAS_LIKED, actor = stranger)) shouldBe false
        networkOnly.hides(notification(NotificationType.YOUR_POST_WAS_ZAPPED, actor = stranger)) shouldBe false
    }
}
