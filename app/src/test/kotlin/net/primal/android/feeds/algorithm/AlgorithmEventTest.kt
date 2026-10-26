package net.primal.android.feeds.algorithm

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import kotlin.random.Random
import kotlinx.serialization.json.jsonPrimitive
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.findFirstIdentifier
import net.primal.domain.nostr.findFirstTitle
import net.primal.domain.nostr.pubkeyTagValues
import org.junit.Test

/** The list an "algorithm" is published as: a NIP-51 follow set that other clients can read too. */
class AlgorithmEventTest {

    private val me = "aa".repeat(32)
    private val alice = "bb".repeat(32)
    private val bob = "cc".repeat(32)

    @Test
    fun `the identifier is a readable slug of the name and a random suffix`() {
        newAlgorithmIdentifier("My Friends", Random(1)) shouldMatch Regex("my-friends-[0-9a-f]{6}")
    }

    @Test
    fun `punctuation and repeated spaces collapse into single dashes`() {
        newAlgorithmIdentifier("  Bitcoin &  Nostr!! ", Random(2)) shouldMatch Regex("bitcoin-nostr-[0-9a-f]{6}")
    }

    @Test
    fun `a name with no letters or digits still gets a usable identifier`() {
        newAlgorithmIdentifier("!!!", Random(3)) shouldStartWith "algorithm-"
    }

    @Test
    fun `letters of other alphabets are kept`() {
        newAlgorithmIdentifier("Амици", Random(4)) shouldStartWith "амици-"
    }

    @Test
    fun `a long name is cut short`() {
        val identifier = newAlgorithmIdentifier("a".repeat(100), Random(5))
        identifier.length shouldBe 24 + 1 + 6
    }

    @Test
    fun `two lists with the same name get different identifiers`() {
        val random = Random(6)
        (newAlgorithmIdentifier("Same", random) == newAlgorithmIdentifier("Same", random)) shouldBe false
    }

    @Test
    fun `the event is a follow set with identifier, title and one p tag per person`() {
        val event = buildAlgorithmEvent(me, "friends-abc123", "Friends", listOf(alice, bob))

        event.kind shouldBe NostrEventKind.CategorizedPeopleList.value
        event.pubKey shouldBe me
        event.content shouldBe ""
        event.tags.findFirstIdentifier() shouldBe "friends-abc123"
        event.tags.findFirstTitle() shouldBe "Friends"
        event.tags.pubkeyTagValues() shouldBe listOf(alice, bob)
    }

    @Test
    fun `a person chosen twice is listed once and the order is kept`() {
        val event = buildAlgorithmEvent(me, "x-000000", "X", listOf(bob, alice, bob))

        event.tags.pubkeyTagValues() shouldBe listOf(bob, alice)
    }

    @Test
    fun `the first two tags are the identifier and the title`() {
        val event = buildAlgorithmEvent(me, "x-000000", "X", listOf(alice))

        event.tags[0][0].jsonPrimitive.content shouldBe "d"
        event.tags[1][0].jsonPrimitive.content shouldBe "title"
        event.tags[2][0].jsonPrimitive.content shouldBe "p"
    }
}
