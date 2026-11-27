package net.primal.core.networking.nwc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.nips.encryption.service.NostrEncryptionServiceImpl
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.cryptography.hasValidIdAndSignature
import net.primal.domain.nostr.cryptography.utils.CryptoUtils
import net.primal.domain.wallet.NostrWalletConnect

class NwcClientTest {

    private val wallet = CryptoUtils.generateHexEncodedKeypair()
    private val app = CryptoUtils.generateHexEncodedKeypair()
    private val uri = "nostr+walletconnect://${wallet.pubKey}?relay=wss%3A%2F%2Fwallet.example&secret=${app.privateKey}"
    private val connection: NostrWalletConnect = NwcUri.parse(uri)
    private val shortTimeouts = NwcTimeouts(query = 300.milliseconds, payment = 300.milliseconds)

    private fun socket(
        behaviour: WalletBehaviour = WalletBehaviour.Pay,
        encryption: String? = "nip44_v2 nip04",
        reachable: Boolean = true,
    ) = FakeWalletSocket(
        wallet = wallet,
        clientPubkey = app.pubKey,
        behaviour = behaviour,
        advertisedEncryption = encryption,
        reachable = reachable,
    )

    private fun client(socket: FakeWalletSocket) =
        NwcClient(
            connection = connection,
            encryptionService = NostrEncryptionServiceImpl(),
            socketFactory = { socket },
            timeouts = shortTimeouts,
        )

    private fun NostrEvent.tag(name: String) =
        tags.firstOrNull { it[0].jsonPrimitive.content == name }?.get(1)?.jsonPrimitive?.content

    @Test
    fun parsesTheConnectionString() {
        val parsed = NwcUri.parse(
            "nostr+walletconnect://${wallet.pubKey}?relay=wss%3A%2F%2Fone.example%2Fv1" +
                "&relay=wss://two.example/&secret=${app.privateKey}&lud16=me%40wallet.example",
        )

        parsed.pubkey shouldBe wallet.pubKey
        parsed.relays shouldContainExactly listOf("wss://one.example/v1", "wss://two.example")
        parsed.keypair.privateKey shouldBe app.privateKey
        parsed.keypair.pubkey shouldBe app.pubKey
        parsed.lightningAddress shouldBe "me@wallet.example"
    }

    @Test
    fun rejectsBrokenStringsWithoutEchoingTheSecret() {
        listOf(
            "lightning:lnbc1",
            "nostr+walletconnect://nothex?relay=wss://a.example&secret=${app.privateKey}",
            "nostr+walletconnect://${wallet.pubKey}?secret=${app.privateKey}",
            "nostr+walletconnect://${wallet.pubKey}?relay=wss://a.example",
        ).forEach { broken ->
            val error = shouldThrow<IllegalArgumentException> { NwcUri.parse(broken) }
            error.message.orEmpty() shouldNotContain app.privateKey
        }
    }

    @Test
    fun paysWithNip44AndSignsWithTheConnectionKey() =
        runTest {
            val socket = socket()

            val result = client(socket).payInvoice("lnbc1invoice")

            result.preimage shouldBe "00ff"
            val request = socket.requests.single()
            request.kind shouldBe 23_194
            request.pubKey shouldBe app.pubKey
            request.hasValidIdAndSignature() shouldBe true
            request.tag("p") shouldBe wallet.pubKey
            request.tag("encryption") shouldBe "nip44_v2"
            (request.tag("expiration")!!.toLong() > request.createdAt) shouldBe true
            socket.decryptedRequests.single() shouldContain "lnbc1invoice"
        }

    @Test
    fun fallsBackToNip04WhenTheWalletAdvertisesNothing() =
        runTest {
            val socket = socket(encryption = null)

            client(socket).getBalance().balance shouldBe 123_000L

            socket.requests.single().tag("encryption") shouldBe "nip04"
            socket.requests.single().content shouldContain "?iv="
        }

    @Test
    fun reportsTheWalletsOwnError() =
        runTest {
            val error = shouldThrow<NwcWalletError> {
                client(socket(WalletBehaviour.RefuseNoFunds)).payInvoice("lnbc1invoice")
            }

            error.code shouldBe "INSUFFICIENT_BALANCE"
        }

    @Test
    fun aReplyNotSignedByTheWalletIsNotBelieved() =
        runTest {
            shouldThrow<NwcNoReply> { client(socket(WalletBehaviour.Impostor)).payInvoice("lnbc1invoice") }
        }

    @Test
    fun aReplyToAnotherRequestIsNotBelieved() =
        runTest {
            shouldThrow<NwcNoReply> { client(socket(WalletBehaviour.WrongRequestId)).payInvoice("lnbc1invoice") }
        }

    @Test
    fun silenceAfterSendingIsDoubtNotFailure() =
        runTest {
            shouldThrow<NwcNoReply> { client(socket(WalletBehaviour.Silent)).payInvoice("lnbc1invoice") }
        }

    @Test
    fun aRefusalByTheRelayMeansNothingWasSent() =
        runTest {
            shouldThrow<NwcNotSent> { client(socket(WalletBehaviour.RelayRefuses)).payInvoice("lnbc1invoice") }
        }

    @Test
    fun anUnreachableRelayMeansNothingWasSent() =
        runTest {
            val socket = socket(reachable = false)

            shouldThrow<NwcNotSent> { client(socket).payInvoice("lnbc1invoice") }

            socket.requests shouldBe emptyList()
        }
}
