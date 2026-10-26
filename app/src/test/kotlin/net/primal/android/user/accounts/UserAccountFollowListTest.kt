package net.primal.android.user.accounts

import io.kotest.matchers.shouldBe
import net.primal.android.user.domain.UserAccount
import org.junit.Test

/**
 * A relay that has not seen the user's latest follow list answers with an older one. Taking it back into the account
 * undid the last follow or unfollow, which looked exactly like the button not working.
 */
class UserAccountFollowListTest {

    private val account = UserAccount.buildLocal(pubkey = PUBKEY)

    private fun list(createdAt: Long?, vararg following: String) =
        UserAccount.buildLocal(pubkey = PUBKEY).copy(
            following = following.toSet(),
            followListCreatedAt = createdAt,
        )

    @Test
    fun `a newer follow list replaces the one held`() {
        val held = account.copyFollowListIfNotNull(list(100, "a", "b"))

        val updated = held.copyFollowListIfNotNull(list(200, "a"))

        updated.following shouldBe setOf("a")
        updated.followListCreatedAt shouldBe 200
    }

    @Test
    fun `an older follow list from a lagging relay is ignored`() {
        val held = account.copyFollowListIfNotNull(list(200, "a"))

        val updated = held.copyFollowListIfNotNull(list(100, "a", "b"))

        updated.following shouldBe setOf("a")
        updated.followListCreatedAt shouldBe 200
    }

    @Test
    fun `a follow list published in the same second is accepted`() {
        val held = account.copyFollowListIfNotNull(list(200, "a", "b"))

        held.copyFollowListIfNotNull(list(200, "a")).following shouldBe setOf("a")
    }

    @Test
    fun `an account stored before the timestamp existed accepts the first list it is given`() {
        val legacy = account.copy(following = setOf("old"), followListCreatedAt = null)

        val updated = legacy.copyFollowListIfNotNull(list(100, "a"))

        updated.following shouldBe setOf("a")
        updated.followListCreatedAt shouldBe 100
    }

    @Test
    fun `no follow list changes nothing`() {
        val held = account.copyFollowListIfNotNull(list(100, "a"))

        held.copyFollowListIfNotNull(null) shouldBe held
    }

    private companion object {
        const val PUBKEY = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"
    }
}
