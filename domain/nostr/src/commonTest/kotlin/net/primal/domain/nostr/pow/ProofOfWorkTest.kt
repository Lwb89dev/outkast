package net.primal.domain.nostr.pow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.calculateEventId
import net.primal.domain.nostr.cryptography.utils.toHex

class ProofOfWorkTest {

    private val awkwardContent = "virgolette \" barra \\ a capo\n tab\t emoji 🦤🇮🇹 controllo \u0001 fine"

    private fun tag(vararg values: String) = buildJsonArray { values.forEach { add(it) } }

    private fun note(content: String = awkwardContent) =
        NostrUnsignedEvent(
            pubKey = "a".repeat(64),
            createdAt = 1_700_000_000L,
            kind = 1,
            tags = listOf(tag("t", "nostr"), tag("nonce", "5", "2"), tag("client", "OUTKAST")),
            content = content,
        )

    private fun NostrUnsignedEvent.signedLike() =
        NostrEvent(
            id = calculateEventId().toHex(),
            pubKey = pubKey,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = "",
        )

    @Test
    fun countsLeadingZeroBits() {
        assertEquals(0, ProofOfWork.leadingZeroBits(byteArrayOf(0x80.toByte())))
        assertEquals(7, ProofOfWork.leadingZeroBits(byteArrayOf(0x01)))
        assertEquals(8, ProofOfWork.leadingZeroBits(byteArrayOf(0x00, 0xff.toByte())))
        assertEquals(21, ProofOfWork.leadingZeroBits(byteArrayOf(0x00, 0x00, 0x07)))
        assertEquals(16, ProofOfWork.leadingZeroBits(byteArrayOf(0x00, 0x00)))
    }

    @Test
    fun minesTheDifficultyAndChangesOnlyTheNonce() =
        runTest {
            val original = note()

            val mined = PowMiner().mine(original, difficulty = 12)

            assertTrue(ProofOfWork.leadingZeroBits(mined.calculateEventId()) >= 12)
            assertEquals(original.createdAt, mined.createdAt)
            assertEquals(original.content, mined.content)
            assertEquals(listOf(tag("t", "nostr"), tag("client", "OUTKAST")), mined.tags.dropLast(1))
            val nonce = mined.tags.last()
            assertEquals("nonce", (nonce[0] as JsonPrimitive).content)
            assertEquals("12", (nonce[2] as JsonPrimitive).content)
            assertEquals(12, mined.signedLike().provenWork())
        }

    @Test
    fun workIsOnlyCreditedWhenTheIdAndTheCommitmentHoldUp() =
        runTest {
            val mined = PowMiner().mine(note("ciao"), difficulty = 10).signedLike()

            assertEquals(10, mined.provenWork())
            assertEquals(0, mined.copy(content = "altro").provenWork(), "the id no longer matches the event")
            assertEquals(0, mined.copy(id = "0".repeat(64)).provenWork(), "a made up id is not work")

            val boasting = note("ciao").copy(tags = listOf(tag("nonce", "1", "30"))).signedLike()
            assertEquals(0, boasting.provenWork(), "claiming more than was done counts for nothing")

            assertEquals(0, note("ciao").copy(tags = emptyList()).signedLike().provenWork())
        }

    @Test
    fun miningStopsWhenCancelled() =
        runTest {
            withContext(Dispatchers.Default) {
                val job = launch { PowMiner().mine(note(), difficulty = ProofOfWork.MAX_DIFFICULTY) }
                delay(100)
                withTimeout(5_000) { job.cancelAndJoin() }
            }
        }
}
