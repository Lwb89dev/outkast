package net.primal.android.spam

import java.util.concurrent.ConcurrentHashMap
import net.primal.core.utils.serialization.decodeFromJsonStringOrNull
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.pow.provenWork
import net.primal.domain.notifications.Notification
import net.primal.domain.notifications.NotificationType

/**
 * What a stranger writes at the user and the user does not want to see. Whoever the account
 * follows, its network and itself are always shown.
 *
 * @param minimumBits the proof of work that lets a stranger's post through ([provenWork]), for the
 *   many apps that never mine; null when no amount of work does, because only the network is wanted.
 */
data class StrangerFilter(
    val following: Set<String>,
    val network: Set<String> = emptySet(),
    val minimumBits: Int? = null,
) {

    /** @param rawEvent the event's JSON; under proof of work, a post that cannot be checked is shown. */
    fun hides(authorId: String, rawEvent: String?, isPrivate: Boolean = false): Boolean =
        when {
            trusts(authorId) -> false
            minimumBits == null -> true
            // A private reply arrives gift wrapped: the work was on the wrap, none is left to check.
            isPrivate -> false
            else -> rawEvent?.decodeFromJsonStringOrNull<NostrEvent>()?.let { workOf(it) < minimumBits } == true
        }

    /** Only notifications about something a person wrote: a like or a zap is never hidden. */
    fun hides(notification: Notification): Boolean {
        if (notification.type !in WRITTEN_AT_THE_USER) return false
        // For these the notification is the other person's note, and their ids are the same.
        val note = notification.actionOnPost?.takeIf { it.eventId == notification.notificationId }
        return if (note != null) {
            hides(authorId = note.author.authorId, rawEvent = note.rawNostrEvent, isPrivate = note.isPrivate)
        } else {
            val authorId = notification.actionUserId
            minimumBits == null && authorId != null && !trusts(authorId)
        }
    }

    private fun trusts(authorId: String) = authorId in following || authorId in network

    private fun workOf(event: NostrEvent): Int {
        if (verified.size >= MAX_VERIFIED) verified.clear()
        return verified.getOrPut(event) { event.provenWork() }
    }

    private companion object {
        val WRITTEN_AT_THE_USER = setOf(
            NotificationType.YOUR_POST_WAS_REPLIED_TO,
            NotificationType.YOU_WERE_MENTIONED_IN_POST,
            NotificationType.YOUR_POST_WAS_MENTIONED_IN_POST,
            NotificationType.POST_YOU_WERE_MENTIONED_IN_WAS_REPLIED_TO,
            NotificationType.POST_YOUR_POST_WAS_MENTIONED_IN_WAS_REPLIED_TO,
            NotificationType.REPLY_TO_REPLY,
            NotificationType.YOUR_POST_WAS_PRIVATELY_REPLIED_TO,
        )

        const val MAX_VERIFIED = 2_000

        /** Keyed by the whole event, so a forged copy cannot borrow a genuine one's result. */
        val verified = ConcurrentHashMap<NostrEvent, Int>()
    }
}
