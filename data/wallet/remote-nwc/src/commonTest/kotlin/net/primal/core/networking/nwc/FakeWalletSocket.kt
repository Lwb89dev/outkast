package net.primal.core.networking.nwc

import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.networking.sockets.NostrIncomingMessage
import net.primal.core.networking.sockets.NostrSocketClient
import net.primal.core.nips.encryption.service.NostrEncryptionServiceImpl
import net.primal.core.utils.serialization.CommonJson
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.NostrKeyPair
import net.primal.domain.nostr.cryptography.signOrThrow
import net.primal.domain.nostr.cryptography.utils.CryptoUtils

/** What the fake wallet does with a request. */
enum class WalletBehaviour { Pay, RefuseNoFunds, Silent, Impostor, WrongRequestId, RelayRefuses }

/** A relay and a wallet service in one object: it answers the REQ and EVENT frames the client sends. */
class FakeWalletSocket(
    private val wallet: NostrKeyPair,
    private val clientPubkey: String,
    private val behaviour: WalletBehaviour = WalletBehaviour.Pay,
    private val advertisedEncryption: String? = "nip44_v2 nip04",
    private val reachable: Boolean = true,
) : NostrSocketClient {

    private val encryption = NostrEncryptionServiceImpl()
    private val messages = MutableSharedFlow<NostrIncomingMessage>(extraBufferCapacity = 64)

    val requests = mutableListOf<NostrEvent>()
    val decryptedRequests = mutableListOf<String>()
    private var replySubscriptionId = ""

    override val socketUrl = "wss://wallet.example"
    override val incomingMessages: SharedFlow<NostrIncomingMessage> = messages
    override val connectionGeneration: StateFlow<Long> = MutableStateFlow(1L)

    override suspend fun close() = Unit

    override suspend fun ensureSocketConnectionOrThrow() {
        if (!reachable) throw NetworkException("unreachable")
    }

    override suspend fun sendAUTH(signedEvent: JsonObject) = Unit

    override suspend fun sendCLOSE(subscriptionId: String) = Unit

    override suspend fun sendCOUNT(data: JsonObject): String = ""

    override suspend fun sendREQ(subscriptionId: String, data: JsonObject) {
        val kind = data.getValue("kinds").jsonArray.first().jsonPrimitive.content.toInt()
        if (kind == 13_194) {
            if (advertisedEncryption != null) {
                val info = signedByWallet(
                    kind = 13_194,
                    content = "pay_invoice get_balance get_info",
                    tags = listOf(tag("encryption", advertisedEncryption)),
                )
                messages.emit(NostrIncomingMessage.EventMessage(subscriptionId, info))
            }
            messages.emit(NostrIncomingMessage.EoseMessage(subscriptionId))
        } else {
            replySubscriptionId = subscriptionId
            messages.emit(NostrIncomingMessage.EoseMessage(subscriptionId))
        }
    }

    override suspend fun sendEVENT(signedEvent: JsonObject) {
        val request = CommonJson.decodeFromJsonElement(NostrEvent.serializer(), signedEvent)
        requests += request
        val nip44 = "?iv=" !in request.content
        val plaintext = if (nip44) {
            encryption.nip44Decrypt(wallet.privateKey, clientPubkey, request.content).getOrThrow()
        } else {
            encryption.nip04Decrypt(wallet.privateKey, clientPubkey, request.content).getOrThrow()
        }
        decryptedRequests += plaintext
        val method = (CommonJson.parseToJsonElement(plaintext) as JsonObject)["method"]
            .let { (it as JsonPrimitive).contentOrNull.orEmpty() }

        if (behaviour == WalletBehaviour.RelayRefuses) {
            messages.emit(NostrIncomingMessage.OkMessage(request.id, success = false, message = "blocked: no"))
            return
        }
        messages.emit(NostrIncomingMessage.OkMessage(request.id, success = true))
        if (behaviour == WalletBehaviour.Silent) return

        val body = when {
            behaviour == WalletBehaviour.RefuseNoFunds ->
                """{"result_type":"$method","error":{"code":"INSUFFICIENT_BALANCE","message":"not enough"}}"""
            method == "pay_invoice" -> """{"result_type":"pay_invoice","result":{"preimage":"00ff","fees_paid":1000}}"""
            method == "get_balance" -> """{"result_type":"get_balance","result":{"balance":123000}}"""
            else -> """{"result_type":"get_info","result":{"alias":"Test wallet","methods":["pay_invoice"]}}"""
        }
        val signer = if (behaviour == WalletBehaviour.Impostor) CryptoUtils.generateHexEncodedKeypair() else wallet
        val content = if (nip44) {
            encryption.nip44Encrypt(signer.privateKey, clientPubkey, body).getOrThrow()
        } else {
            encryption.nip04Encrypt(signer.privateKey, clientPubkey, body).getOrThrow()
        }
        val answered = if (behaviour == WalletBehaviour.WrongRequestId) "0".repeat(64) else request.id
        val response = signed(
            keys = signer,
            kind = 23_195,
            content = content,
            tags = listOf(tag("p", clientPubkey), tag("e", answered)),
        )
        messages.emit(NostrIncomingMessage.EventMessage(replySubscriptionId, response))
    }

    private fun signedByWallet(
        kind: Int,
        content: String,
        tags: List<JsonArray>,
    ) = signed(wallet, kind, content, tags)

    private fun signed(
        keys: NostrKeyPair,
        kind: Int,
        content: String,
        tags: List<JsonArray>,
    ): NostrEvent =
        NostrUnsignedEvent(pubKey = keys.pubKey, kind = kind, content = content, tags = tags)
            .signOrThrow(hexPrivateKey = Hex.decode(keys.privateKey))

    private fun tag(name: String, value: String): JsonArray =
        buildJsonArray {
            add(name)
            add(value)
        }
}
