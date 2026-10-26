package net.primal.domain.posts

data class FeedPostStats(
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
    /** How many notes quote this one. */
    val quotesCount: Long = 0,
    val userBookmarked: Boolean = false,
    val userVotedForOption: String? = null,
)
