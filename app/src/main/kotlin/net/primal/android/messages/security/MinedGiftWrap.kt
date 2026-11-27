package net.primal.android.messages.security

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip44Encryption.Nip44v2
import com.vitorpamplona.quartz.utils.TimeUtils
import fr.acinq.secp256k1.Hex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.signOrThrow
import net.primal.domain.nostr.cryptography.utils.toHex

/**
 * A NIP-59 Gift Wrap with NIP-13 proof of work.
 *
 * Quartz's `GiftWrapEvent.create` makes the throwaway key and signs in one step, which leaves
 * nowhere to put a nonce, so the wrap is assembled here when it has to be mined. It is built the
 * way Quartz builds it: a fresh key, the seal encrypted to the recipient with NIP-44, a
 * `created_at` randomized over the last two days, the `p` tag and, for a message that expires, the
 * `expiration` tag. The key is wiped once the wrap is signed.
 */
internal suspend fun minedGiftWrap(
    seal: Event,
    recipientId: String,
    expirationDelta: Long?,
    difficulty: Int,
    mine: suspend (NostrUnsignedEvent, Int) -> NostrUnsignedEvent,
): NostrEvent {
    val throwaway = KeyPair()
    val privateKey = requireNotNull(throwaway.privKey) { "No private key was generated." }
    try {
        val createdAt = TimeUtils.randomWithTwoDays()
        val content = Nip44v2().encrypt(
            msg = seal.toJson(),
            privateKey = privateKey,
            pubKey = Hex.decode(recipientId),
        ).encodePayload()
        val tags = buildList {
            add(tag("p", recipientId))
            expirationDelta?.let { add(tag("expiration", (createdAt + it + TimeUtils.twoDays()).toString())) }
        }
        val unsigned = NostrUnsignedEvent(
            pubKey = throwaway.pubKey.toHex(),
            createdAt = createdAt,
            kind = NostrEventKind.GiftWrap.value,
            tags = tags,
            content = content,
        )
        return mine(unsigned, difficulty).signOrThrow(hexPrivateKey = privateKey)
    } finally {
        privateKey.fill(0)
    }
}

private fun tag(name: String, value: String): JsonArray =
    buildJsonArray {
        add(name)
        add(value)
    }
