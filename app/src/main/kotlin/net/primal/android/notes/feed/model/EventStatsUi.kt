package net.primal.android.notes.feed.model

import net.primal.domain.events.NostrEventStats
import net.primal.domain.events.NostrEventUserStats
import net.primal.domain.posts.FeedPostStats

data class EventStatsUi(
    val repliesCount: Long = 0,
    val userReplied: Boolean = false,
    val zapsCount: Long = 0,
    val satsZapped: Long = 0,
    val userZapped: Boolean = false,
    val likesCount: Long = 0,
    val userLiked: Boolean = false,
    /** What the user reacted with ("+" or an emoji); null for no reaction or an old plain like. */
    val userReaction: String? = null,
    val repostsCount: Long = 0,
    val userReposted: Boolean = false,
    val quotesCount: Long = 0,
) {
    /**
     * The emoji to draw in place of the heart because the user reacted with it, or null to keep the
     * heart. Only a reaction that is itself a character can be drawn: a plain like ("+"), a dislike
     * ("-") and a `:shortcode:` custom emoji (an image, which the reaction does not carry) all keep
     * the heart.
     */
    val userReactionEmoji: String?
        get() = userReaction?.takeIf { userLiked && it.isDrawableReactionEmoji() }

    companion object {
        fun from(eventStats: NostrEventStats?, userStats: NostrEventUserStats?) =
            EventStatsUi(
                repliesCount = eventStats?.replies ?: 0,
                userReplied = userStats?.replied ?: false,
                zapsCount = eventStats?.zaps ?: 0,
                satsZapped = eventStats?.satsZapped ?: 0,
                userZapped = userStats?.zapped ?: false,
                likesCount = eventStats?.likes ?: 0,
                userLiked = userStats?.liked ?: false,
                userReaction = userStats?.reaction,
                repostsCount = eventStats?.reposts ?: 0,
                userReposted = userStats?.reposted ?: false,
                quotesCount = eventStats?.mentions ?: 0,
            )

        fun from(stats: FeedPostStats?) =
            EventStatsUi(
                repliesCount = stats?.repliesCount ?: 0,
                userReplied = stats?.userReplied == true,
                zapsCount = stats?.zapsCount ?: 0,
                satsZapped = stats?.satsZapped ?: 0,
                userZapped = stats?.userZapped == true,
                likesCount = stats?.likesCount ?: 0,
                userLiked = stats?.userLiked == true,
                userReaction = stats?.userReaction,
                repostsCount = stats?.repostsCount ?: 0,
                userReposted = stats?.userReposted == true,
                quotesCount = stats?.quotesCount ?: 0,
            )
    }
}

/** True for a reaction that is an emoji (or other symbol) rather than NIP-25's "+" / "-" or a `:shortcode:`. */
internal fun String.isDrawableReactionEmoji(): Boolean {
    val reaction = trim()
    if (reaction.isEmpty() || reaction == "+" || reaction == "-") return false
    if (reaction.startsWith(":") && reaction.endsWith(":")) return false
    // A keycap emoji ("1️⃣") contains a digit but is one; everything else with a letter or digit is text.
    return reaction.contains(KEYCAP_COMBINING_MARK) || reaction.none { it.isLetterOrDigit() }
}

private const val KEYCAP_COMBINING_MARK = '\u20E3'
