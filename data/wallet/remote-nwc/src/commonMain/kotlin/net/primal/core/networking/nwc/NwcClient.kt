package net.primal.core.networking.nwc

import fr.acinq.secp256k1.Hex
import io.github.aakira.napier.Napier
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import net.primal.core.networking.nwc.nip47.GetBalanceResponsePayload
import net.primal.core.networking.nwc.nip47.GetInfoResponsePayload
import net.primal.core.networking.nwc.nip47.NwcEncryptionScheme
import net.primal.core.networking.nwc.nip47.PayInvoiceResponsePayload
import net.primal.core.networking.sockets.NostrIncomingMessage
import net.primal.core.networking.sockets.NostrSocketClient
import net.primal.core.networking.sockets.NostrSocketClientFactory
import net.primal.core.networking.sockets.RelayConnectionBackoff
import net.primal.core.networking.sockets.filterBySubscriptionId
import net.primal.core.nips.encryption.service.NostrEncryptionService
import net.primal.core.utils.getOrElse
import net.primal.core.utils.runCatching
import net.primal.core.utils.serialization.CommonJson
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.hasValidIdAndSignature
import net.primal.domain.nostr.cryptography.signOrThrow
import net.primal.domain.nostr.serialization.toNostrJsonObject
import net.primal.domain.wallet.NostrWalletConnect

data class NwcTimeouts(
    val connect: Duration = 10.seconds,
    val info: Duration = 5.seconds,
    val query: Duration = 15.seconds,
    /** Lightning payments over several hops can take a while; giving up early only creates doubt. */
    val payment: Duration = 60.seconds,
)

/**
 * The client side of NIP-47: asks an external wallet to do things by way of encrypted events on
 * the relay named in the connection string.
 *
 * Requests are signed with the connection's own secret, never with the user's identity key, so a
 * relay cannot tie the payments to the account. A reply is believed only when it is signed by the
 * wallet named in the connection string and refers to the request it answers.
 */
class NwcClient(
    private val connection: NostrWalletConnect,
    private val encryptionService: NostrEncryptionService,
    private val socketFactory: (relayUrl: String) -> NostrSocketClient = { url ->
        // The user is waiting on this: a relay that failed a moment ago gets a fresh attempt.
        RelayConnectionBackoff.Shared.reset(url)
        NostrSocketClientFactory.create(wssUrl = url, keepAliveEnabled = true)
    },
    private val timeouts: NwcTimeouts = NwcTimeouts(),
    private val now: () -> Long = { Clock.System.now().epochSeconds },
) {

    private var negotiatedEncryption: NwcEncryptionScheme? = null

    suspend fun getInfo(): GetInfoResponsePayload =
        request("get_info", buildJsonObject {}, GetInfoResponsePayload.serializer(), timeouts.query)

    suspend fun getBalance(): GetBalanceResponsePayload =
        request("get_balance", buildJsonObject {}, GetBalanceResponsePayload.serializer(), timeouts.query)

    suspend fun payInvoice(invoice: String): PayInvoiceResponsePayload =
        request(
            method = "pay_invoice",
            params = buildJsonObject { put("invoice", invoice) },
            deserializer = PayInvoiceResponsePayload.serializer(),
            replyTimeout = timeouts.payment,
        )

    /**
     * Sends one request through the first relay that can be reached. Once a request has gone out it
     * is never repeated on another relay: for a payment, "no answer here" must not turn into a
     * second attempt.
     */
    private suspend fun <R> request(
        method: String,
        params: JsonObject,
        deserializer: DeserializationStrategy<R>,
        replyTimeout: Duration,
    ): R {
        var lastFailure: Throwable? = null
        for (relayUrl in connection.relays.take(MAX_RELAYS)) {
            val socket = socketFactory(relayUrl)
            try {
                val failure = socket.connect()
                if (failure == null) return socket.exchange(method, params, deserializer, replyTimeout)
                lastFailure = failure
            } finally {
                withContext(NonCancellable) { closeQuietly { socket.close() } }
            }
        }
        throw NwcNotSent("Could not reach the wallet's relay.", lastFailure)
    }

    /** @return null once connected, otherwise why not. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun NostrSocketClient.connect(): Throwable? =
        try {
            val connected = withTimeoutOrNull(timeouts.connect) { ensureSocketConnectionOrThrow() }
            if (connected == null) IllegalStateException("Connection timed out.") else null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error
        }

    private suspend fun <R> NostrSocketClient.exchange(
        method: String,
        params: JsonObject,
        deserializer: DeserializationStrategy<R>,
        replyTimeout: Duration,
    ): R {
        val scheme = negotiateEncryption()
        val request = buildRequest(method, params, scheme, replyTimeout)
        val subscriptionId = Uuid.random().toString()
        val reply = CompletableDeferred<String>()

        return coroutineScope {
            val listener = launch(start = CoroutineStart.UNDISPATCHED) {
                incomingMessages
                    .onSubscription { send(subscriptionId, request, reply) }
                    .collect { message -> onReplyMessage(message, subscriptionId, request.id, reply) }
            }
            try {
                val plaintext = withTimeoutOrNull(replyTimeout) { reply.await() } ?: throw NwcNoReply()
                parseReply(plaintext, method, deserializer)
            } finally {
                listener.cancel()
                withContext(NonCancellable) { closeQuietly { sendCLOSE(subscriptionId) } }
            }
        }
    }

    /** Listens first, then publishes: the reply is an ephemeral event no relay has to keep. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun NostrSocketClient.send(
        subscriptionId: String,
        request: NostrEvent,
        reply: CompletableDeferred<String>,
    ) {
        try {
            sendREQ(
                subscriptionId = subscriptionId,
                data = buildJsonObject {
                    put("kinds", buildJsonArray { add(NostrEventKind.NwcResponse.value) })
                    put("authors", buildJsonArray { add(connection.pubkey) })
                    put("#e", buildJsonArray { add(request.id) })
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reply.completeExceptionally(NwcNotSent("Could not talk to the wallet's relay.", error))
            return
        }
        try {
            sendEVENT(request.toNostrJsonObject())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The frame may have left before the failure, so this is doubt, not a clean "not sent".
            reply.completeExceptionally(NwcNoReply(cause = error))
        }
    }

    private fun onReplyMessage(
        message: NostrIncomingMessage,
        subscriptionId: String,
        requestId: String,
        reply: CompletableDeferred<String>,
    ) {
        when (message) {
            is NostrIncomingMessage.EventMessage -> {
                if (message.subscriptionId != subscriptionId) return
                val event = message.nostrEvent?.takeIf { it.isReplyTo(requestId) } ?: return
                decryptOrNull(event.content)?.let { reply.complete(it) }
            }

            is NostrIncomingMessage.OkMessage -> {
                val refused = message.eventId == requestId && !message.success &&
                    message.machineReadablePrefix != "duplicate"
                if (refused) {
                    reply.completeExceptionally(
                        NwcNotSent("The wallet's relay refused the request: ${message.message.orEmpty()}"),
                    )
                }
            }

            else -> Unit
        }
    }

    private fun NostrEvent.isReplyTo(requestId: String): Boolean =
        kind == NostrEventKind.NwcResponse.value &&
            pubKey == connection.pubkey &&
            tagValue("e") == requestId &&
            hasValidIdAndSignature()

    private fun <R> parseReply(
        plaintext: String,
        method: String,
        deserializer: DeserializationStrategy<R>,
    ): R {
        val json = readable { CommonJson.parseToJsonElement(plaintext) as JsonObject }
        (json["error"] as? JsonObject)?.let { throw it.asWalletError() }

        val result = (json["result"] as? JsonObject)?.takeIf { json.string("result_type") == method }
            ?: throw NwcNoReply("The wallet sent an unexpected reply.")
        return readable { CommonJson.decodeFromJsonElement(deserializer, result) }
    }

    private fun JsonObject.asWalletError() =
        NwcWalletError(code = string("code") ?: "OTHER", message = string("message").orEmpty())

    /** A reply that cannot be read leaves the outcome unknown, like no reply at all. */
    private fun <T> readable(read: () -> T): T =
        runCatching(read).getOrElse { throw NwcNoReply("The wallet sent a reply that could not be read.", it) }

    /**
     * The wallet says what it can decrypt in its info event (kind 13194). Without one, or without an
     * `encryption` tag in it, NIP-47 says to assume NIP-04.
     */
    private suspend fun NostrSocketClient.negotiateEncryption(): NwcEncryptionScheme {
        negotiatedEncryption?.let { return it }

        val subscriptionId = Uuid.random().toString()
        var info: NostrEvent? = null
        withTimeoutOrNull(timeouts.info) {
            incomingMessages
                .onSubscription {
                    sendREQ(
                        subscriptionId = subscriptionId,
                        data = buildJsonObject {
                            put("kinds", buildJsonArray { add(NostrEventKind.WalletInfo.value) })
                            put("authors", buildJsonArray { add(connection.pubkey) })
                            put("limit", 1)
                        },
                    )
                }
                .filterBySubscriptionId(subscriptionId)
                .transformWhile { message ->
                    emit(message)
                    message is NostrIncomingMessage.EventMessage
                }
                .collect { message ->
                    val event = (message as? NostrIncomingMessage.EventMessage)?.nostrEvent ?: return@collect
                    if (event.isWalletInfo() && event.createdAt > (info?.createdAt ?: 0L)) info = event
                }
        }
        closeQuietly { sendCLOSE(subscriptionId) }

        val advertised = info?.tagValue("encryption")?.split(' ').orEmpty()
        val scheme = if (NwcEncryptionScheme.NIP44.value in advertised) {
            NwcEncryptionScheme.NIP44
        } else {
            NwcEncryptionScheme.NIP04
        }
        // Not remembered when no info event arrived: that may have been a slow relay.
        if (info != null) negotiatedEncryption = scheme
        return scheme
    }

    private fun NostrEvent.isWalletInfo(): Boolean =
        kind == NostrEventKind.WalletInfo.value && pubKey == connection.pubkey && hasValidIdAndSignature()

    private fun buildRequest(
        method: String,
        params: JsonObject,
        scheme: NwcEncryptionScheme,
        replyTimeout: Duration,
    ): NostrEvent {
        val plaintext = buildJsonObject {
            put("method", method)
            put("params", params)
        }.toString()
        val secret = connection.keypair.privateKey
        val content = when (scheme) {
            NwcEncryptionScheme.NIP44 -> encryptionService.nip44Encrypt(secret, connection.pubkey, plaintext)
            NwcEncryptionScheme.NIP04 -> encryptionService.nip04Encrypt(secret, connection.pubkey, plaintext)
        }.getOrThrow()
        val createdAt = now()
        // The wallet must ignore the request once this app has stopped waiting for the answer.
        val expiresAt = createdAt + replyTimeout.inWholeSeconds + EXPIRATION_MARGIN_SECONDS

        return NostrUnsignedEvent(
            pubKey = connection.keypair.pubkey,
            createdAt = createdAt,
            kind = NostrEventKind.NwcRequest.value,
            content = content,
            tags = listOf(
                tag("p", connection.pubkey),
                tag("encryption", scheme.value),
                tag("expiration", expiresAt.toString()),
            ),
        ).signOrThrow(hexPrivateKey = Hex.decode(secret))
    }

    private fun decryptOrNull(content: String): String? {
        val secret = connection.keypair.privateKey
        // The two formats are told apart by shape, so a wallet that answers in the older one is
        // still understood.
        val result = if ("?iv=" in content) {
            encryptionService.nip04Decrypt(secret, connection.pubkey, content)
        } else {
            encryptionService.nip44Decrypt(secret, connection.pubkey, content)
        }
        return result.getOrNull().also {
            if (it == null) Napier.w { "Ignoring a wallet reply that does not decrypt." }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun closeQuietly(close: suspend () -> Unit) {
        try {
            close()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Napier.d { "Closing the wallet connection failed." }
        }
    }

    private fun NostrEvent.tagValue(name: String): String? =
        tags.firstOrNull { (it.getOrNull(0) as? JsonPrimitive)?.contentOrNull == name }
            ?.let { (it.getOrNull(1) as? JsonPrimitive)?.contentOrNull }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun tag(name: String, value: String): JsonArray =
        buildJsonArray {
            add(name)
            add(value)
        }

    private companion object {
        const val MAX_RELAYS = 3
        const val EXPIRATION_MARGIN_SECONDS = 30L
    }
}
