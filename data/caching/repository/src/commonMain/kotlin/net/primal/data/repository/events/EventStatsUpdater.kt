package net.primal.data.repository.events

import kotlin.time.Clock
import net.primal.core.utils.CurrencyConversionUtils.toBtc
import net.primal.data.local.dao.events.EventStats
import net.primal.data.local.dao.events.EventUserStats
import net.primal.data.local.dao.events.EventZap
import net.primal.data.local.db.CachingDatabase
import net.primal.shared.data.local.db.withTransaction

internal class EventStatsUpdater(
    val eventId: String,
    val userId: String,
    val eventAuthorId: String,
    val database: CachingDatabase,
) {

    private val timestamp: Long = Clock.System.now().epochSeconds

    // What the stats were before this updater first touched them, so a failed publish can put exactly
    // that back. Reading them again at revert time would find the values this updater had already written.
    private var statsBefore: EventStats? = null
    private var userStatsBefore: EventUserStats? = null

    private suspend fun resolveEventStats(): EventStats {
        return database.eventStats().find(eventId = eventId)
            ?: EventStats(eventId = eventId)
    }

    private suspend fun resolveEventUserStats(): EventUserStats {
        return database.eventUserStats().find(eventId = eventId, userId = userId)
            ?: EventUserStats(eventId = eventId, userId = userId)
    }

    /** One like more, and remember [reaction] (the content of the kind 7 event) as what the user gave. */
    suspend fun increaseLikeStats(reaction: String = PLAIN_LIKE) {
        val eventStats = resolveEventStats().rememberAsBefore()
        val eventUserStats = resolveEventUserStats().rememberAsBefore()
        database.withTransaction {
            database.eventStats().upsert(data = eventStats.copy(likes = eventStats.likes + 1))
            database.eventUserStats().upsert(data = eventUserStats.copy(liked = true, reaction = reaction))
        }
    }

    /** Takes the user's reaction back: one like fewer (never below zero) and the heart empty again. */
    suspend fun decreaseLikeStats() {
        val eventStats = resolveEventStats().rememberAsBefore()
        val eventUserStats = resolveEventUserStats().rememberAsBefore()
        database.withTransaction {
            database.eventStats().upsert(data = eventStats.copy(likes = (eventStats.likes - 1).coerceAtLeast(0)))
            database.eventUserStats().upsert(data = eventUserStats.copy(liked = false, reaction = null))
        }
    }

    suspend fun increaseRepostStats() {
        val eventStats = resolveEventStats().rememberAsBefore()
        val eventUserStats = resolveEventUserStats().rememberAsBefore()
        database.withTransaction {
            database.eventStats().upsert(data = eventStats.copy(reposts = eventStats.reposts + 1))
            database.eventUserStats().upsert(data = eventUserStats.copy(reposted = true))
        }
    }

    suspend fun increaseZapStats(amountInSats: Int, zapComment: String) {
        val eventStats = resolveEventStats().rememberAsBefore()
        val eventUserStats = resolveEventUserStats().rememberAsBefore()
        database.withTransaction {
            val zapSender = database.profiles().findProfileData(profileId = userId)
            database.eventStats().upsert(
                data = eventStats.copy(
                    zaps = eventStats.zaps + 1,
                    satsZapped = eventStats.satsZapped + amountInSats,
                ),
            )
            database.eventUserStats().upsert(data = eventUserStats.copy(zapped = true))

            database.eventZaps().insert(
                data = EventZap(
                    zapSenderId = userId,
                    zapReceiverId = eventAuthorId,
                    eventId = eventId,
                    zapRequestAt = timestamp,
                    zapReceiptAt = timestamp,
                    zapSenderAvatarCdnImage = zapSender?.avatarCdnImage,
                    zapSenderHandle = zapSender?.handle,
                    zapSenderDisplayName = zapSender?.displayName,
                    zapSenderInternetIdentifier = zapSender?.internetIdentifier,
                    zapSenderPrimalLegendProfile = zapSender?.primalPremiumInfo?.legendProfile,
                    amountInBtc = amountInSats.toBtc(),
                    message = zapComment,
                    invoice = null,
                ),
            )
        }
    }

    private fun EventStats.rememberAsBefore(): EventStats = also { if (statsBefore == null) statsBefore = it }

    private fun EventUserStats.rememberAsBefore(): EventUserStats =
        also { if (userStatsBefore == null) userStatsBefore = it }

    suspend fun revertStats() {
        val eventStats = statsBefore ?: resolveEventStats()
        val eventUserStats = userStatsBefore ?: resolveEventUserStats()
        database.withTransaction {
            database.eventStats().upsert(data = eventStats)
            database.eventUserStats().upsert(data = eventUserStats)
            database.eventZaps().delete(
                noteId = eventId,
                senderId = userId,
                receiverId = eventAuthorId,
                timestamp = timestamp,
            )
        }
    }

    private companion object {
        /** NIP-25's content for an ordinary like. */
        const val PLAIN_LIKE = "+"
    }
}
