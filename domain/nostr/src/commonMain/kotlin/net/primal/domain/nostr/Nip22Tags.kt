package net.primal.domain.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

/*
 * Readers for the tags of a NIP-22 comment (kind 1111); see [CommentTarget] and [buildCommentTags]
 * for how the two scopes, root in uppercase and parent in lowercase, are written.
 */

private const val RELAY_HINT_INDEX = 2
private const val EVENT_AUTHOR_INDEX = 3

/** The event id of a comment's root scope (`E`), when the root is an event. */
fun List<JsonArray>.findCommentRootEventId(): String? =
    firstOrNull { it.isUppercaseEventIdTag() }?.getTagValueOrNull()

/** The `kind:pubkey:identifier` of a comment's root scope (`A`), when the root is addressable. */
fun List<JsonArray>.findCommentRootAddress(): String? =
    firstOrNull { it.isUppercaseATag() }?.getTagValueOrNull()

/** The id of the event a comment answers (`e`). */
fun List<JsonArray>.findCommentParentEventId(): String? =
    firstOrNull { it.isEventIdTag() }?.getTagValueOrNull()

/** The address a comment answers (`a`), when it answers an addressable event directly. */
fun List<JsonArray>.findCommentParentAddress(): String? =
    firstOrNull { it.isATag() }?.getTagValueOrNull()

fun List<JsonArray>.findCommentRootKind(): Int? =
    firstOrNull { it.isUppercaseKindTag() }?.getTagValueOrNull()?.toIntOrNull()

fun List<JsonArray>.findCommentParentKind(): Int? =
    firstOrNull { it.isKindTag() }?.getTagValueOrNull()?.toIntOrNull()

/** The author of the event a comment answers (`p`). */
fun List<JsonArray>.findCommentParentAuthor(): String? =
    firstOrNull { it.isPubKeyTag() }?.getTagValueOrNull()

fun List<JsonArray>.findCommentRootAuthor(): String? =
    firstOrNull { it.isUppercasePubkeyTag() }?.getTagValueOrNull()

/**
 * The root scope these comment tags name, read back as a [CommentTarget] so a reply can carry it
 * forward unchanged. Null when the tags carry no root a reply could point at: an external root
 * (`I`, a web page) is not representable here, and neither is a root with no `K`.
 */
fun List<JsonArray>.findCommentRootTarget(): CommentTarget? {
    val kind = findCommentRootKind() ?: return null
    val rootAuthor = findCommentRootAuthor()
    return firstOrNull { it.isUppercaseEventIdTag() }?.asRootEventTarget(kind = kind, rootAuthor = rootAuthor)
        ?: firstOrNull { it.isUppercaseATag() }?.asRootAddressTarget()
}

/** An `E` tag as the event root. The author is its own fourth element, or else the `P` tag's. */
private fun JsonArray.asRootEventTarget(kind: Int, rootAuthor: String?): CommentTarget.Event? {
    val eventId = getTagValueOrNull()
    val author = elementOrNull(EVENT_AUTHOR_INDEX) ?: rootAuthor
    return if (eventId == null || author == null) {
        null
    } else {
        CommentTarget.Event(
            eventId = eventId,
            kind = kind,
            authorPubkey = author,
            relayHint = elementOrNull(RELAY_HINT_INDEX),
        )
    }
}

/**
 * An `A` tag, `kind:pubkey:identifier`, as the addressable root. An identifier may itself contain
 * ':', so everything after the pubkey belongs to it.
 */
private fun JsonArray.asRootAddressTarget(): CommentTarget.Address? {
    val parts = getTagValueOrNull()?.split(":").orEmpty()
    val kind = parts.getOrNull(0)?.toIntOrNull()
    val pubkey = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
    return if (kind == null || pubkey == null) {
        null
    } else {
        CommentTarget.Address(
            kind = kind,
            authorPubkey = pubkey,
            identifier = parts.drop(2).joinToString(separator = ":"),
            relayHint = elementOrNull(RELAY_HINT_INDEX),
        )
    }
}

/** A tag element's text, or null when it is missing or empty (an unset relay hint is `""`). */
private fun JsonArray.elementOrNull(index: Int): String? =
    getOrNull(index)?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
