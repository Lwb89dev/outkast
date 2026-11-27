package net.primal.domain.nostr.pow

import io.ktor.utils.io.core.toByteArray
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.calculateEventId
import net.primal.domain.nostr.cryptography.serializeForEventId
import net.primal.domain.nostr.cryptography.utils.CryptoUtils

/** SHA-256 over three consecutive parts, so the miner never has to join them. */
fun interface PartsHasher {
    fun hash(
        prefix: ByteArray,
        nonce: ByteArray,
        suffix: ByteArray,
    ): ByteArray
}

/**
 * Finds a `nonce` tag that gives an unsigned event an id with the wanted number of leading zero
 * bits (NIP-13). It has to run before signing: the nonce is part of what gets signed.
 *
 * The event is serialized once and only the nonce changes between attempts. The loop is
 * cooperative: cancelling the coroutine stops it within a few thousand attempts. It is CPU work, so
 * the caller runs it off the main thread.
 *
 * @param newHasher gives one hasher per mining run, which may therefore keep state.
 */
class PowMiner(
    private val newHasher: () -> PartsHasher = {
        PartsHasher { prefix, nonce, suffix -> CryptoUtils.sha256(prefix + nonce + suffix) }
    },
) {

    /**
     * @return [event] with its tags otherwise untouched and in order, an older `nonce` replaced by
     * the new one at the end. `created_at` is not changed.
     */
    suspend fun mine(event: NostrUnsignedEvent, difficulty: Int): NostrUnsignedEvent {
        require(difficulty in 1..ProofOfWork.MAX_DIFFICULTY) { "Unsupported difficulty $difficulty." }
        val tags = event.tags.filterNot { (it.firstOrNull() as? JsonPrimitive)?.content == ProofOfWork.NONCE_TAG }

        val template = event.copy(tags = tags.plusElement(nonceTag(PLACEHOLDER, difficulty))).serializeForEventId()
        // Content that itself contains the placeholder is escaped differently only if it has quotes,
        // so split at the quoted tag value and insist there is exactly one.
        val parts = template.split("\"$PLACEHOLDER\"")
        require(parts.size == 2) { "The event cannot be mined." }
        val prefix = (parts[0] + "\"").toByteArray()
        val suffix = ("\"" + parts[1]).toByteArray()

        val hasher = newHasher()
        val context = coroutineContext
        var nonce = 0L
        while (true) {
            if (nonce % CANCELLATION_CHECK_INTERVAL == 0L) context.ensureActive()
            val id = hasher.hash(prefix, nonce.toString().encodeToByteArray(), suffix)
            if (ProofOfWork.leadingZeroBits(id) >= difficulty) break
            nonce++
        }

        val mined = event.copy(tags = tags.plusElement(nonceTag(nonce.toString(), difficulty)))
        // A hasher that disagreed with the id computation would otherwise publish work that is not there.
        check(ProofOfWork.leadingZeroBits(mined.calculateEventId()) >= difficulty) { "Mined id does not verify." }
        return mined
    }

    private fun nonceTag(nonce: String, difficulty: Int): JsonArray =
        buildJsonArray {
            add(ProofOfWork.NONCE_TAG)
            add(nonce)
            add(difficulty.toString())
        }

    private companion object {
        const val PLACEHOLDER = "pow-nonce-4b1f0c9e7a2d"
        const val CANCELLATION_CHECK_INTERVAL = 1_000L
    }
}
