package net.primal.data.repository.mappers.local

import net.primal.data.local.dao.events.EventUserStats
import net.primal.domain.events.NostrEventUserStats

fun EventUserStats.asNostrEventUserStats(): NostrEventUserStats {
    return NostrEventUserStats(
        eventId = this.eventId,
        userId = this.userId,
        replied = this.replied,
        liked = this.liked,
        reaction = this.reaction,
        reposted = this.reposted,
        zapped = this.zapped,
        votedForOption = this.votedForOption,
    )
}
