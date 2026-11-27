package net.primal.android.pow

import androidx.datastore.core.DataStore
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import net.primal.domain.nostr.NostrEventKind

/**
 * One account's NIP-13 choices. Sending: which of its events get mined, and how hard. Receiving:
 * whether strangers are hidden, either all of them or those whose post lacks proof of work.
 */
@Serializable
data class PowSettings(
    val notes: Boolean = true,
    val notesBits: Int = DEFAULT_NOTES_BITS,
    val reactions: Boolean = true,
    val reactionsBits: Int = DEFAULT_REACTIONS_BITS,
    val messages: Boolean = true,
    val messagesBits: Int = DEFAULT_MESSAGES_BITS,
    val filter: Boolean = false,
    val filterBits: Int = DEFAULT_FILTER_BITS,
    /** Hide whoever is outside the account's network, proof of work or not. */
    val networkOnly: Boolean = false,
    /** How many of the account's follows must follow somebody for them to be in that network. */
    val networkMinFollowers: Int = DEFAULT_NETWORK_MIN_FOLLOWERS,
) {
    /**
     * The difficulty an outgoing event of this [kind] is mined to, 0 for none. Only the kinds
     * listed here are ever mined: profile, follow list, relay list and the rest stay as they are.
     */
    fun difficultyFor(kind: Int): Int =
        when (kind) {
            in NOTE_KINDS -> if (notes) notesBits else 0
            NostrEventKind.Reaction.value -> if (reactions) reactionsBits else 0
            NostrEventKind.EncryptedDirectMessages.value -> messagesDifficulty
            else -> 0
        }

    /** For direct messages: the NIP-04 fallback and the NIP-59 wrap of NIP-17 messages. */
    val messagesDifficulty: Int get() = if (messages) messagesBits else 0

    companion object {
        const val DEFAULT_NOTES_BITS = 16
        const val DEFAULT_REACTIONS_BITS = 12
        const val DEFAULT_MESSAGES_BITS = 12
        const val DEFAULT_FILTER_BITS = 16
        const val DEFAULT_NETWORK_MIN_FOLLOWERS = 3
        val NETWORK_WIDTHS = listOf(1, 3, 10)

        private val NOTE_KINDS = setOf(
            NostrEventKind.ShortTextNote.value,
            NostrEventKind.Comment.value,
            NostrEventKind.Poll.value,
            NostrEventKind.ZapPoll.value,
        )
    }
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PowSettingsDataStore

@Singleton
class PowSettingsStore @Inject constructor(
    @PowSettingsDataStore private val persistence: DataStore<Map<String, PowSettings>>,
) {
    fun observe(userId: String): Flow<PowSettings> =
        persistence.data.map { it[userId] ?: PowSettings() }.distinctUntilChanged()

    suspend fun get(userId: String): PowSettings = observe(userId).first()

    suspend fun update(userId: String, change: PowSettings.() -> PowSettings) {
        persistence.updateData { all -> all + (userId to (all[userId] ?: PowSettings()).change()) }
    }

    suspend fun remove(userId: String) {
        persistence.updateData { it - userId }
    }
}
