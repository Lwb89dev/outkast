package net.primal.android.navigation

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import net.primal.domain.nostr.Naddr
import net.primal.domain.nostr.Nip19TLV.toNaddrString
import net.primal.domain.nostr.Nip19TLV.toNprofileString
import net.primal.domain.nostr.Nprofile
import net.primal.domain.nostr.cryptography.utils.hexToNoteHrp
import net.primal.domain.nostr.cryptography.utils.hexToNpubHrp
import org.junit.Test

class NostrUriDeepLinkTest {

    private val hex = "13b9b9007ab24d82f2f80f5cb22c12e1c19f725ebb8f2034a97d7f6f69374087"

    private val nevent = "nevent1qqs0ejzkvqdlqaej8k7edamkvzcnjv77u6npmp2qhdpv" +
        "swyjvcplafqpp4mhxue69uhkummn9ekx7mqzyrhxagf6h8l9cjngatumrg60uq2" +
        "2v66qz979pm32v985ek54ndh8gqcyqqqqqqgpldx8x"

    private val note = hex.hexToNoteHrp()
    private val npub = hex.hexToNpubHrp()
    private val nprofile = Nprofile(pubkey = hex, relays = listOf("wss://relay.example.com")).toNprofileString()

    private fun naddr(kind: Int) =
        Naddr(identifier = "my-article", relays = emptyList(), userId = hex, kind = kind).toNaddrString()

    @Test
    fun `a nevent opens the thread`() {
        "nostr:$nevent".nostrUriToWebDeepLinkOrNull() shouldBe "https://nostrich.org/e/$nevent"
    }

    @Test
    fun `a note opens the thread`() {
        "nostr:$note".nostrUriToWebDeepLinkOrNull() shouldBe "https://nostrich.org/e/$note"
    }

    @Test
    fun `an npub and an nprofile open the profile`() {
        "nostr:$npub".nostrUriToWebDeepLinkOrNull() shouldBe "https://nostrich.org/p/$npub"
        "nostr:$nprofile".nostrUriToWebDeepLinkOrNull() shouldBe "https://nostrich.org/p/$nprofile"
    }

    @Test
    fun `a long-form article opens the article`() {
        val article = naddr(kind = 30023)

        "nostr:$article".nostrUriToWebDeepLinkOrNull() shouldBe "https://nostrich.org/a/$article"
    }

    @Test
    fun `an naddr of any other kind has no screen and is refused`() {
        "nostr:${naddr(kind = 30030)}".nostrUriToWebDeepLinkOrNull().shouldBeNull()
        "nostr:${naddr(kind = 39089)}".nostrUriToWebDeepLinkOrNull().shouldBeNull()
    }

    @Test
    fun `the scheme is case-insensitive and two slashes after it are tolerated`() {
        "NOSTR:$npub".nostrUriToWebDeepLinkOrNull() shouldBe "https://nostrich.org/p/$npub"
        "nostr://$npub".nostrUriToWebDeepLinkOrNull() shouldBe "https://nostrich.org/p/$npub"
    }

    @Test
    fun `an upper-cased reference, as a QR code carries it, is routed in lower case`() {
        "nostr:${npub.uppercase()}".nostrUriToWebDeepLinkOrNull() shouldBe "https://nostrich.org/p/$npub"
    }

    @Test
    fun `a query or fragment after the reference is not part of it`() {
        "nostr:$nevent?relay=wss://x".nostrUriToWebDeepLinkOrNull() shouldBe "https://nostrich.org/e/$nevent"
        "nostr:$nevent#frag".nostrUriToWebDeepLinkOrNull() shouldBe "https://nostrich.org/e/$nevent"
    }

    @Test
    fun `a reference that does not decode is refused`() {
        "nostr:${npub.dropLast(3)}".nostrUriToWebDeepLinkOrNull().shouldBeNull()
        "nostr:${note.dropLast(3)}".nostrUriToWebDeepLinkOrNull().shouldBeNull()
        "nostr:${nevent.dropLast(3)}".nostrUriToWebDeepLinkOrNull().shouldBeNull()
        "nostr:nevent1qqqqqq".nostrUriToWebDeepLinkOrNull().shouldBeNull()
    }

    @Test
    fun `a secret key is never routed`() {
        "nostr:nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5".nostrUriToWebDeepLinkOrNull()
            .shouldBeNull()
    }

    @Test
    fun `anything that is not a nostr link is left alone`() {
        "https://nostrich.org/e/$nevent".nostrUriToWebDeepLinkOrNull().shouldBeNull()
        "https://njump.me/$nevent".nostrUriToWebDeepLinkOrNull().shouldBeNull()
        "nostrconnect://abc?relay=wss://x".nostrUriToWebDeepLinkOrNull().shouldBeNull()
        "nostr:".nostrUriToWebDeepLinkOrNull().shouldBeNull()
        "".nostrUriToWebDeepLinkOrNull().shouldBeNull()
    }
}
