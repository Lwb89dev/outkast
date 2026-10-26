package net.primal.android.notes.feed.model

import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import net.primal.domain.posts.FeedPost
import net.primal.domain.posts.FeedPostAuthor
import net.primal.domain.posts.FeedPostStats
import org.junit.Test

/** A counter that the data layer fills but this mapper forgets to copy simply never shows up. */
class FeedPostUiStatsTest {

    private fun post(stats: FeedPostStats?) =
        FeedPost(
            eventId = "e".repeat(64),
            author = FeedPostAuthor(authorId = "a".repeat(64), handle = "alice", displayName = "Alice"),
            kind = 1,
            content = "hello",
            tags = emptyList(),
            timestamp = Instant.fromEpochSeconds(1),
            rawNostrEvent = "{}",
            stats = stats,
        )

    @Test
    fun `the reaction the user gave reaches the UI`() {
        val ui = post(FeedPostStats(userLiked = true, userReaction = "\uD83E\uDDE1")).asFeedPostUi()

        ui.stats.userReaction shouldBe "\uD83E\uDDE1"
        ui.stats.userReactionEmoji shouldBe "\uD83E\uDDE1"
    }

    @Test
    fun `every counter of a post reaches the UI, quotes included`() {
        val ui = post(
            FeedPostStats(
                repliesCount = 2,
                zapsCount = 3,
                satsZapped = 210,
                likesCount = 5,
                repostsCount = 7,
                quotesCount = 11,
                userLiked = true,
            ),
        ).asFeedPostUi()

        ui.stats shouldBe EventStatsUi(
            repliesCount = 2,
            zapsCount = 3,
            satsZapped = 210,
            likesCount = 5,
            userLiked = true,
            repostsCount = 7,
            quotesCount = 11,
        )
    }

    @Test
    fun `a post with no stats shows none`() {
        post(stats = null).asFeedPostUi().stats shouldBe EventStatsUi()
    }
}
