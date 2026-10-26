package net.primal.data.local.dao.notes

data class FeedPostUserStats(
    val userLiked: Boolean = false,
    /** The user's own reaction ("+" or an emoji); see `EventUserStats.reaction`. */
    val userReaction: String? = null,
    val userReplied: Boolean = false,
    val userReposted: Boolean = false,
    val userZapped: Boolean = false,
    val userVotedForOption: String? = null,
)
