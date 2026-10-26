package net.primal.data.repository.feed

import net.primal.core.utils.getOrDefault
import net.primal.core.utils.runCatching
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.utils.extractProfileId
import net.primal.domain.nostr.utils.parseNostrUris

/**
 * The pubkeys a note names in its own text as `nostr:npub1…` or `nostr:nprofile1…`.
 *
 * A well-behaved client also adds a `p` tag for each, but not every client does, and a mention with
 * no tag used to be left without a kind 0 to render against, so it stayed a raw npub. Only notes
 * that contain the marker are parsed, which is almost none of them, so this costs nothing on an
 * ordinary page.
 */
internal fun NostrEvent.mentionedInContent(): List<String> =
    if (content.contains("npub1") || content.contains("nprofile1")) {
        content.parseNostrUris().mapNotNull { runCatching { it.extractProfileId() }.getOrDefault(null) }
    } else {
        emptyList()
    }
