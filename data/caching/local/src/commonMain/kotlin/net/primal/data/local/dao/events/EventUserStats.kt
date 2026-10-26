package net.primal.data.local.dao.events

import androidx.room3.Entity
import androidx.room3.Index

@Entity(
    primaryKeys = [
        "eventId",
        "userId",
    ],
    indices = [
        Index(value = ["eventId"]),
        Index(value = ["userId"]),
    ],
)
data class EventUserStats(
    val eventId: String,
    val userId: String,
    val replied: Boolean = false,
    val liked: Boolean = false,
    /**
     * What the user reacted with: the content of their kind 7 event, so "+" for a plain like and an
     * emoji for a custom reaction. Null when [liked] is false, and also for rows written before this
     * column existed, which is why a null on a liked note means "an ordinary like" and nothing else.
     * It is what lets the note show the reaction the user actually gave instead of always a heart.
     */
    val reaction: String? = null,
    val reposted: Boolean = false,
    val zapped: Boolean = false,
    val votedForOption: String? = null,
)
