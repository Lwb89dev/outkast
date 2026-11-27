package net.primal.android.nostr.publish

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import net.primal.android.networking.relays.RelaysSocketManager
import net.primal.android.nostr.notary.NostrNotary
import net.primal.android.pow.PowSettings
import net.primal.android.pow.PowSettingsStore
import net.primal.android.pow.ProofOfWorkService
import net.primal.domain.global.CachingImportRepository
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.SignResult
import net.primal.domain.nostr.cryptography.calculateEventId
import net.primal.domain.nostr.pow.ProofOfWork
import org.junit.Test

class NostrPublisherTest {

    private val signedEvent = NostrEvent(
        id = "event-id",
        pubKey = "pubkey",
        createdAt = 1_700_000_000L,
        kind = 1,
        tags = emptyList(),
        content = "hello",
        sig = "sig",
    )

    private val sentToSigner = slot<NostrUnsignedEvent>()

    private val notary: NostrNotary = mockk(relaxed = true) {
        coEvery { signNostrEvent(capture(sentToSigner)) } returns SignResult.Signed(signedEvent)
    }

    private fun buildPublisher(
        relaysSocketManager: RelaysSocketManager = mockk(relaxed = true),
        cachingImportRepository: CachingImportRepository = mockk(relaxed = true),
        settings: PowSettings = PowSettings(notes = false, reactions = false, messages = false),
    ) = NostrPublisher(
        relaysSocketManager = relaysSocketManager,
        nostrNotary = notary,
        cachingImportRepository = cachingImportRepository,
        powSettingsStore = settingsStore(settings),
        proofOfWork = ProofOfWorkService(),
    )

    private fun settingsStore(settings: PowSettings): PowSettingsStore {
        val store = mockk<PowSettingsStore>()
        coEvery { store.get(any()) } returns settings
        return store
    }

    private fun unsigned(kind: Int) =
        NostrUnsignedEvent(pubKey = "a".repeat(64), createdAt = 1_700_000_000L, kind = kind, content = "hello")

    private suspend fun NostrPublisher.publish(event: NostrUnsignedEvent) =
        signPublishImportNostrEvent(unsignedNostrEvent = event, outboxRelays = emptyList())

    @Test
    fun signPublishImportNostrEvent_persistsLocallyInsteadOfCacheImport() =
        runTest {
            val cachingImportRepository = mockk<CachingImportRepository>(relaxed = true)
            val publisher = buildPublisher(cachingImportRepository = cachingImportRepository)

            publisher.publish(unsigned(kind = 1))

            coVerify { cachingImportRepository.cacheNostrEvents(events = listOf(signedEvent)) }
            coVerify(exactly = 0) { cachingImportRepository.importEvents(any()) }
        }

    @Test
    fun withProofOfWorkOff_theSignerGetsTheEventAsItWas() =
        runTest {
            val event = unsigned(kind = 1)

            buildPublisher().publish(event)

            sentToSigner.captured shouldBe event
        }

    @Test
    fun withProofOfWorkOn_theSignerGetsAMinedEvent() =
        runTest {
            val event = unsigned(kind = 1)

            buildPublisher(settings = PowSettings(notesBits = 10)).publish(event)

            val mined = sentToSigner.captured
            (ProofOfWork.leadingZeroBits(mined.calculateEventId()) >= 10) shouldBe true
            mined.tags.single().map { it.jsonPrimitive.content }.let { (name, _, bits) ->
                name shouldBe "nonce"
                bits shouldBe "10"
            }
            mined.createdAt shouldBe event.createdAt
        }

    @Test
    fun kindsThatAreNotListedAreNeverMined() =
        runTest {
            // Profile, follow list, relay list, repost, deletion.
            listOf(0, 3, 10_002, 6, 5).forEach { kind ->
                val event = unsigned(kind)

                buildPublisher(settings = PowSettings()).publish(event)

                sentToSigner.captured shouldBe event
            }
        }

    @Test
    fun difficultyFollowsTheKindOfEvent() {
        val settings = PowSettings(notesBits = 17, reactionsBits = 13, messagesBits = 11)

        listOf(1, 1111, 1068, 6969).forEach { settings.difficultyFor(it) shouldBe 17 }
        settings.difficultyFor(7) shouldBe 13
        settings.difficultyFor(4) shouldBe 11
        settings.difficultyFor(14) shouldBe 0
        settings.copy(reactions = false).difficultyFor(7) shouldBe 0
    }
}
