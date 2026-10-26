package net.primal.android.notes.feed.model

import io.kotest.matchers.shouldBe
import org.junit.Test

/** Which reactions replace the heart on a note, and which keep it. */
class EventStatsUiTest {

    private fun reacted(with: String?) = EventStatsUi(userLiked = true, userReaction = with)

    @Test
    fun `an emoji reaction replaces the heart`() {
        reacted("🧡").userReactionEmoji shouldBe "🧡"
        reacted("👍").userReactionEmoji shouldBe "👍"
        reacted("❤️").userReactionEmoji shouldBe "❤️"
    }

    @Test
    fun `a flag, a skin tone and a keycap are still one emoji`() {
        reacted("🇮🇹").userReactionEmoji shouldBe "🇮🇹"
        reacted("👍🏽").userReactionEmoji shouldBe "👍🏽"
        reacted("1️⃣").userReactionEmoji shouldBe "1️⃣"
    }

    @Test
    fun `a plain like keeps the heart`() {
        reacted("+").userReactionEmoji shouldBe null
        reacted("").userReactionEmoji shouldBe null
        reacted(null).userReactionEmoji shouldBe null
    }

    @Test
    fun `a dislike and a custom emoji shortcode keep the heart`() {
        reacted("-").userReactionEmoji shouldBe null
        reacted(":soapbox:").userReactionEmoji shouldBe null
    }

    @Test
    fun `text is not drawn in place of the heart`() {
        reacted("nice").userReactionEmoji shouldBe null
    }

    @Test
    fun `no emoji is shown when the user has not reacted`() {
        EventStatsUi(userLiked = false, userReaction = "🧡").userReactionEmoji shouldBe null
    }
}
