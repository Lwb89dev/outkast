package net.primal.android.messages.security

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import net.primal.android.core.serialization.json.NostrNotaryJson
import net.primal.android.networking.relays.RelaysSocketManager
import net.primal.android.nostr.notary.NostrNotary
import net.primal.android.pow.PowSettings
import net.primal.android.pow.PowSettingsStore
import net.primal.android.pow.ProofOfWorkService
import net.primal.android.user.domain.Relay
import net.primal.core.utils.coroutines.createDispatcherProvider
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.SignResult
import net.primal.domain.nostr.cryptography.hasValidIdAndSignature
import net.primal.domain.nostr.cryptography.signOrThrow
import net.primal.domain.nostr.cryptography.utils.toHex
import net.primal.domain.nostr.pow.provenWork
import org.junit.Test

/**
 * A mined Gift Wrap is built by this app instead of by Quartz, so the thing to prove is that other
 * clients can still open it. These open it with Quartz, as most of them do.
 *
 * Instrumented: NIP-44 needs the native libraries of an Android runtime.
 */
class Nip17ProofOfWorkTest {

    private val senderKeys = KeyPair()
    private val sender = NostrSignerInternal(senderKeys)
    private val recipientKeys = KeyPair()
    private val recipient = NostrSignerInternal(recipientKeys)

    private fun tag(vararg values: String): JsonArray = buildJsonArray { values.forEach { add(it) } }

    private fun JsonArray.at(index: Int) = this[index].jsonPrimitive.content

    private fun signed(keys: KeyPair, event: NostrUnsignedEvent): NostrEvent =
        event.signOrThrow(hexPrivateKey = requireNotNull(keys.privKey))

    private suspend fun open(wrap: NostrEvent): Event {
        val giftWrap = Event.fromJson(NostrNotaryJson.encodeToString(wrap)) as GiftWrapEvent
        val seal = giftWrap.unwrapThrowing(recipient) as SealedRumorEvent
        return seal.unsealThrowing(recipient)
    }

    private fun rumorFrom(content: String) =
        signed(
            senderKeys,
            NostrUnsignedEvent(
                pubKey = sender.pubKey,
                createdAt = 1_700_000_000L,
                kind = 14,
                tags = listOf(tag("p", recipient.pubKey)),
                content = content,
            ),
        )

    @Test
    fun aMinedWrapCarriesTheWorkAndStillOpens(): Unit = runBlocking {
        val rumor = Event.fromJson(NostrNotaryJson.encodeToString(rumorFrom("ciao 🦤 \"virgolette\"\n")))
        val seal = SealedRumorEvent.create(rumor, recipient.pubKey, sender, null)
        val before = System.currentTimeMillis() / 1000

        val wrap = minedGiftWrap(seal, recipient.pubKey, null, 10, ProofOfWorkService()::mine)

        wrap.kind shouldBe 1059
        wrap.hasValidIdAndSignature() shouldBe true
        wrap.provenWork() shouldBe 10
        wrap.pubKey shouldNotBe sender.pubKey
        wrap.tags.first { it.at(0) == "p" }.at(1) shouldBe recipient.pubKey
        wrap.tags.none { it.at(0) == "expiration" } shouldBe true
        // Randomized over the past two days, as NIP-59 asks.
        (wrap.createdAt <= before + 5 && wrap.createdAt >= before - 2 * 24 * 60 * 60 - 5) shouldBe true

        val opened = open(wrap)
        opened.content shouldBe "ciao 🦤 \"virgolette\"\n"
        opened.pubKey shouldBe sender.pubKey
    }

    @Test
    fun aMinedWrapKeepsTheExpiration(): Unit = runBlocking {
        val rumor = Event.fromJson(NostrNotaryJson.encodeToString(rumorFrom("scade")))
        val seal = SealedRumorEvent.create(rumor, recipient.pubKey, sender, 3_600L)

        val wrap = minedGiftWrap(seal, recipient.pubKey, 3_600L, 8, ProofOfWorkService()::mine)

        wrap.tags.first { it.at(0) == "expiration" }.at(1).toLong() shouldBe
            wrap.createdAt + 3_600L + 2 * 24 * 60 * 60
    }

    @Test
    fun sendingMinesOnlyTheRecipientsWrapAndLeavesTheMessageAlone(): Unit = runBlocking {
        val inbox = signed(
            recipientKeys,
            NostrUnsignedEvent(
                pubKey = recipient.pubKey,
                kind = 10_050,
                tags = listOf(tag("relay", "wss://inbox.example")),
                content = "",
            ),
        )
        val published = mutableListOf<NostrEvent>()
        val event = slot<NostrEvent>()
        val relays: RelaysSocketManager = mockk(relaxed = true) {
            coEvery { query(any()) } answers {
                listOf(inbox).filter { firstArg<net.primal.domain.nostr.relay.RelayFilter>().authors == listOf(recipient.pubKey) }
            }
            coEvery { configuredUserRelays(any()) } returns listOf(Relay("wss://mine.example", true, true))
            coEvery { publishEvent(capture(event), any<List<Relay>>()) } answers { published += event.captured }
        }
        val signedKinds = mutableListOf<NostrUnsignedEvent>()
        val notary: NostrNotary = mockk(relaxed = true) {
            coEvery { signNostrEvent(any()) } answers {
                val unsigned = firstArg<NostrUnsignedEvent>()
                signedKinds += unsigned
                SignResult.Signed(signed(senderKeys, unsigned))
            }
            coEvery { nip44Encrypt(any(), any(), any()) } coAnswers {
                sender.nip44Encrypt(thirdArg(), secondArg())
            }
        }
        val settings = mockk<PowSettingsStore>()
        coEvery { settings.get(any()) } returns PowSettings(messagesBits = 10)
        val transport = Nip17TransportImpl(
            relays = relays,
            notary = notary,
            dispatcherProvider = createDispatcherProvider(),
            powSettingsStore = settings,
            proofOfWork = ProofOfWorkService(),
        )

        transport.sendMessage(
            userId = senderKeys.pubKey.toHex(),
            receiverId = recipient.pubKey,
            content = "solo per te",
            extraTags = emptyList(),
        )

        val wraps = published.filter { it.kind == 1059 }
        val forRecipient = wraps.single { wrap -> wrap.tags.any { it.at(0) == "p" && it.at(1) == recipient.pubKey } }
        val ownCopy = wraps.single { wrap -> wrap.tags.any { it.at(0) == "p" && it.at(1) == sender.pubKey } }
        forRecipient.provenWork() shouldBe 10
        ownCopy.tags.none { it.at(0) == "nonce" } shouldBe true
        // The message and the seals are signed as ever: mining adds no signature requests.
        signedKinds.none { unsigned -> unsigned.tags.any { it.at(0) == "nonce" } } shouldBe true
        open(forRecipient).content shouldBe "solo per te"
    }
}
