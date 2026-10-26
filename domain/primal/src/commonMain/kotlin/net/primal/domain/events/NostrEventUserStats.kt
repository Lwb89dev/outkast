package net.primal.domain.events

data class NostrEventUserStats(
    val eventId: String,
    val userId: String,
    val replied: Boolean = false,
    val liked: Boolean = false,
    /** What the user reacted with ("+" or an emoji); null for no reaction or an old plain like. */
    val reaction: String? = null,
    val reposted: Boolean = false,
    val zapped: Boolean = false,
    val votedForOption: String? = null,
)
