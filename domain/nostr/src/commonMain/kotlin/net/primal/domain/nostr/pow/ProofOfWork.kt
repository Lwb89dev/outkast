package net.primal.domain.nostr.pow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.cryptography.calculateEventId
import net.primal.domain.nostr.cryptography.toUnsignedEvent
import net.primal.domain.nostr.cryptography.utils.toHex

/** NIP-13: the work of an event is the number of leading zero bits of its id. */
object ProofOfWork {
    const val NONCE_TAG = "nonce"
    const val MIN_DIFFICULTY = 8

    /** About 16 million attempts on average: beyond this a phone takes minutes. */
    const val MAX_DIFFICULTY = 24

    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xff

    fun leadingZeroBits(id: ByteArray): Int {
        var bits = 0
        for (byte in id) {
            val value = byte.toInt() and BYTE_MASK
            if (value == 0) {
                bits += BITS_PER_BYTE
            } else {
                // An Int has 24 more leading zeros than the byte it came from.
                return bits + value.countLeadingZeroBits() - (Int.SIZE_BITS - BITS_PER_BYTE)
            }
        }
        return bits
    }

    /** The difficulty the author says they aimed for: the third element of the `nonce` tag. */
    fun committedDifficulty(tags: List<JsonArray>): Int? =
        tags.firstOrNull { it.stringAt(0) == NONCE_TAG }?.stringAt(2)?.toIntOrNull()

    private fun JsonArray.stringAt(index: Int): String? = (getOrNull(index) as? JsonPrimitive)?.contentOrNull
}

/**
 * The work this event can be credited with: its commitment, provided the id really is the hash of
 * the event and has at least that many zero bits. Zero otherwise.
 *
 * The committed value counts, not the bits found: a bulk spammer aiming low sometimes gets lucky,
 * and NIP-13 has the commitment precisely so that luck is not mistaken for effort.
 */
fun NostrEvent.provenWork(): Int {
    val committed = ProofOfWork.committedDifficulty(tags)?.takeIf { it > 0 } ?: return 0
    val computed = toUnsignedEvent().calculateEventId()
    val genuine = computed.toHex().equals(id, ignoreCase = true)
    return if (genuine && ProofOfWork.leadingZeroBits(computed) >= committed) committed else 0
}
