package net.primal.core.networking.nwc

import fr.acinq.secp256k1.Hex
import io.ktor.http.decodeURLQueryComponent
import net.primal.domain.nostr.cryptography.utils.CryptoUtils
import net.primal.domain.nostr.cryptography.utils.toHex
import net.primal.domain.wallet.NostrWalletConnect
import net.primal.domain.wallet.NostrWalletKeypair

/**
 * The connection string a wallet hands out (NIP-47):
 * `nostr+walletconnect://<wallet pubkey>?relay=<url>&secret=<key>[&lud16=<address>]`.
 *
 * It carries the key that can spend from the wallet, so neither the string nor what is parsed from
 * it may ever be logged. Exception messages here deliberately never echo the input.
 */
object NwcUri {

    private val SCHEMES = listOf(
        "nostr+walletconnect://",
        "nostr+walletconnect:",
        "nostrwalletconnect://",
        "nostrwalletconnect:",
    )
    private val KEY_HEX = Regex("[0-9a-f]{64}")

    fun parse(uri: String): NostrWalletConnect {
        val trimmed = uri.trim()
        val scheme = SCHEMES.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
        require(scheme != null) { "Not a Nostr Wallet Connect string." }

        val body = trimmed.substring(scheme.length)
        val walletPubkey = body.substringBefore('?').trim('/').lowercase()
        require(KEY_HEX.matches(walletPubkey)) { "The wallet public key is missing or malformed." }

        val params = body.substringAfter('?', missingDelimiterValue = "")
            .split('&')
            .filter { it.isNotEmpty() }
            .map { it.substringBefore('=') to it.substringAfter('=', "").decodeURLQueryComponent() }

        val relays = params.filter { it.first == "relay" }
            .map { it.second.trim().trimEnd('/') }
            .filter { it.startsWith("wss://") || it.startsWith("ws://") }
            .distinct()
        require(relays.isNotEmpty()) { "The connection string names no relay." }

        val secret = params.firstOrNull { it.first == "secret" }?.second?.lowercase()
        require(secret != null && KEY_HEX.matches(secret)) { "The secret is missing or malformed." }

        return NostrWalletConnect(
            lightningAddress = params.firstOrNull { it.first == "lud16" }?.second?.takeIf { it.isNotBlank() },
            relays = relays,
            pubkey = walletPubkey,
            keypair = NostrWalletKeypair(
                privateKey = secret,
                pubkey = CryptoUtils.publicKeyCreate(Hex.decode(secret)).toHex(),
            ),
        )
    }
}
