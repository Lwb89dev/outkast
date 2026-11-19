package net.primal.android.navigation

import net.primal.core.utils.runCatching
import net.primal.domain.nostr.Nip19TLV
import net.primal.domain.nostr.cryptography.utils.bech32ToHexOrThrow

private const val NOSTR_SCHEME_PREFIX = "nostr:"
private const val WEB_DEEP_LINK_BASE = "https://nostrich.org"
private const val LONG_FORM_ARTICLE_KIND = 30023

/**
 * Turns a `nostr:` link (NIP-21) into the `https://nostrich.org/...` link the navigation graph already
 * routes, or returns null when this is not a `nostr:` link or names something the app cannot open.
 *
 * Other apps hand over `nostr:` links: a notification app asking for the note a notification is about,
 * a web page's "open in your client" button, a QR code. The graph only declares web deep links, so the
 * link is mapped onto the matching one instead of declaring a second set of routes that would have to
 * be kept in step with the first:
 *
 * - `npub1…` and `nprofile1…` open the profile (`/p/…`),
 * - `note1…` and `nevent1…` open the thread (`/e/…`),
 * - `naddr1…` of a long-form article opens the article (`/a/…`).
 *
 * The reference is decoded here, so a mistyped or truncated one is refused rather than routed to a
 * screen that would have nothing valid to show. An `naddr` of any other kind (an emoji pack, a
 * follow pack) has no screen to open and is refused as well. `nsec1…` is refused on purpose: a secret
 * key must never travel in a link, let alone be opened by it.
 */
fun String.nostrUriToWebDeepLinkOrNull(): String? {
    val trimmed = trim()
    if (!trimmed.startsWith(NOSTR_SCHEME_PREFIX, ignoreCase = true)) return null

    // `nostr://npub1…` is written that way by some apps; the two slashes are not part of the reference.
    // Bech32 is case-insensitive as long as it is not mixed, and QR codes upper-case it.
    val reference = trimmed.substring(NOSTR_SCHEME_PREFIX.length)
        .trimStart('/')
        .substringBefore('?')
        .substringBefore('#')
        .lowercase()

    val path = when {
        reference.startsWith("npub1") -> "p".takeIf { reference.decodesAsBech32() }
        reference.startsWith("nprofile1") -> "p".takeIf { Nip19TLV.parseUriAsNprofileOrNull(reference) != null }
        reference.startsWith("note1") -> "e".takeIf { reference.decodesAsBech32() }
        reference.startsWith("nevent1") -> "e".takeIf { Nip19TLV.parseUriAsNeventOrNull(reference) != null }
        reference.startsWith("naddr1") ->
            "a".takeIf { Nip19TLV.parseUriAsNaddrOrNull(reference)?.kind == LONG_FORM_ARTICLE_KIND }
        else -> null
    }

    return path?.let { "$WEB_DEEP_LINK_BASE/$it/$reference" }
}

private fun String.decodesAsBech32(): Boolean = runCatching { bech32ToHexOrThrow() }.getOrNull() != null
