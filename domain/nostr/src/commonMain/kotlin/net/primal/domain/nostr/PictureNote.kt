package net.primal.domain.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * A NIP-68 picture post (kind 20), the note Mialuna and other picture-first clients publish.
 *
 * The caption lives in [NostrEvent.content] (and optionally a `title` tag). The pictures live in
 * `imeta` tags, not in the text, so a reader that only looks at content URLs sees an empty note.
 */
fun NostrEvent.isDisplayablePictureNote(nowEpochSeconds: Long): Boolean {
    if (kind != NostrEventKind.PictureNote.value) return false
    if (isCloseFriendsPicture()) return false
    if (isExpiredAt(nowEpochSeconds)) return false
    return pictureImageUrls().isNotEmpty()
}

/** Mialuna close-friends posts are gift-wrapped rumors. A public copy of that tag is not a picture we can show. */
fun NostrEvent.isCloseFriendsPicture(): Boolean =
    tags.any { tag ->
        tag.tagName() == "audience" && tag.getTagValueOrNull() == "close-friends"
    }

/** NIP-40. A missing or unreadable `expiration` does not expire the event. */
fun NostrEvent.isExpiredAt(nowEpochSeconds: Long): Boolean {
    val expiry = tags.firstOrNull { it.tagName() == "expiration" }?.getTagValueOrNull()?.toLongOrNull()
        ?: return false
    return expiry <= nowEpochSeconds
}

/**
 * Picture URLs in display order: each `imeta`'s `url`, then plain `url` / `image` tags other clients add.
 *
 * Encrypted items (close-friends media) and declared non-images are left out. `fallback` mirrors are
 * not extra pictures.
 */
fun NostrEvent.pictureImageUrls(): List<String> {
    val fromImeta = tags.filter { it.isIMetaTag() }.flatMap { it.pictureUrlsFromImeta() }
    val fromUrlTags = tags.filter { it.tagName() == "url" }.mapNotNull { it.getTagValueOrNull() }
    val fromImageTags = tags.filter { it.isImageTag() }.mapNotNull { it.getTagValueOrNull() }
    return (fromImeta + fromUrlTags + fromImageTags).map { it.trim() }.filter { it.isHttpUrl() }.distinct()
}

/**
 * Caption shown under the pictures. A `title` that is not already the start of the description is
 * placed above it. Lines that are only a picture URL are dropped, because the gallery draws those.
 */
fun NostrEvent.pictureCaption(): String {
    val images = pictureImageUrls().toSet()
    val body = content.lineSequence()
        .filterNot { line -> line.trim() in images }
        .joinToString("\n")
        .trim()
    val title = tags.findFirstTitle()?.trim().orEmpty()
    if (title.isEmpty() || body.startsWith(title)) return body
    if (body.isEmpty()) return title
    return "$title\n\n$body"
}

/** NIP-36. The tag's presence is the warning; the value is an optional reason. */
fun List<JsonArray>.contentWarning(): String? {
    val tag = firstOrNull { it.tagName() == "content-warning" } ?: return null
    return tag.getTagValueOrNull().orEmpty()
}

private fun JsonArray.pictureUrlsFromImeta(): List<String> {
    if (isEncryptedImeta()) return emptyList()
    val mime = extractMimeType()
    if (mime != null && !mime.startsWith("image")) return emptyList()
    val values = mapNotNull { element -> runCatching { element.jsonPrimitive.content }.getOrNull() }
    val inline = values.mapNotNull { value ->
        value.removePrefix("url ").takeIf { value.startsWith("url ") }?.trim()?.takeIf { it.isNotEmpty() }
    }
    val split = values.windowed(size = 2, partialWindows = false)
        .mapNotNull { (key, value) -> value.takeIf { key == "url" && it.isNotBlank() } }
    return inline + split
}

private fun JsonArray.isEncryptedImeta(): Boolean =
    any { element ->
        val value = runCatching { element.jsonPrimitive.content }.getOrNull() ?: return@any false
        value.startsWith("encryption-algorithm ") ||
            value.startsWith("decryption-key ") ||
            value == "encryption-algorithm" ||
            value == "decryption-key"
    }

private fun JsonArray.tagName(): String? =
    getOrNull(0)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

private fun String.isHttpUrl(): Boolean = startsWith("https://") || startsWith("http://")
