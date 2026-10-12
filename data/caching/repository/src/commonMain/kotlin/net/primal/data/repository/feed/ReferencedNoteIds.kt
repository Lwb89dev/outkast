package net.primal.data.repository.feed

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.utils.serialization.CommonJson
import net.primal.domain.common.PrimalEvent
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.findReplyTargetId
import net.primal.domain.nostr.getTagValueOrNull
import net.primal.domain.nostr.hasReplyMarker
import net.primal.domain.nostr.hasRootMarker
import net.primal.domain.nostr.isEventIdTag
import net.primal.domain.nostr.isQuoteTag
import net.primal.domain.nostr.utils.extractNoteId
import net.primal.domain.nostr.utils.parseNostrUris

/**
 * The notes [this] quotes or mentions, by id: a NIP-18 `q` tag, or a `nostr:note1.../nevent1...`
 * URI embedded in the content itself. Both name a specific note the renderer needs in order to
 * show it inline — a quote whose target was never fetched renders as "Mentioned event not found"
 * instead, which is what this exists to prevent.
 */
internal fun List<NostrEvent>.referencedNoteIds(): List<String> =
    flatMap { event ->
        val fromContent = event.content.parseNostrUris().mapNotNull { it.extractNoteId() }
        val fromQuoteTags = event.tags.filter { it.isQuoteTag() }.mapNotNull { it.quotedEventId() }
        fromContent + fromQuoteTags
    }.distinct()

private fun JsonArray.quotedEventId(): String? = getOrNull(1)?.jsonPrimitive?.content

/**
 * Whether this is a text note that quotes [eventId]: it embeds it (a `q` tag, or the note's `nostr:`
 * link in its text) without being a reply to it. A reply that happens to embed the note it answers
 * is still a reply, and is counted and listed as one.
 */
internal fun NostrEvent.isQuoteOf(eventId: String): Boolean =
    kind == NostrEventKind.ShortTextNote.value &&
        eventId in listOf(this).referencedNoteIds() &&
        !answers(eventId)

/**
 * Whether this note names [eventId] as the one it answers: its NIP-10 reply target, or any `e` tag to
 * it marked `reply` or `root`. The explicit markers count even next to a `q` tag, which the plain
 * reply-target rule treats as "this is a quote, not a reply" and so would not see.
 */
private fun NostrEvent.answers(eventId: String): Boolean =
    tags.findReplyTargetId() == eventId ||
        tags.any {
            it.isEventIdTag() && it.getTagValueOrNull() == eventId && (it.hasReplyMarker() || it.hasRootMarker())
        }

/**
 * The Primal-cache-era wire shape for "an event referenced from elsewhere": the referenced event
 * itself, JSON-encoded, inside [PrimalEvent.content]. Relay-fetched referenced notes are wrapped
 * the same way so they flow through the existing persistence path unchanged.
 */
internal fun NostrEvent.asReferencedPrimalEvent(): PrimalEvent =
    PrimalEvent(kind = kind, id = id, pubKey = pubKey, createdAt = createdAt, content = CommonJson.encodeToString(this))
