package net.primal.data.repository.feed

import net.primal.core.utils.detectMimeType
import net.primal.core.utils.detectUrls
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.extractMimeType
import net.primal.domain.nostr.findIMetaTagForUrl

/**
 * Whether this note puts an image or a video in front of the reader: the thing a profile's media
 * tab is a grid of.
 *
 * It asks the question the way the stored note will later be asked, so a note this lets through is
 * one the grid can actually draw a tile for. The links are the ones in the content (that is where
 * [net.primal.data.local.dao.notes.PostData.uris] comes from), and each link is classified the way
 * the attachment parser classifies it: by its file extension first, then by the `m` field of the
 * NIP-92 `imeta` tag that describes the same URL. The second step is what recognises a Blossom link,
 * which has no extension to go by, and a link whose extension says nothing.
 *
 * A note whose only media sits in an `imeta` tag with no matching link in the content is not
 * counted: the note renderer shows what the content links to, so such a note would be a blank tile.
 */
internal fun NostrEvent.carriesMedia(): Boolean =
    content.detectUrls().any { url ->
        val mimeType = url.detectMimeType() ?: tags.findIMetaTagForUrl(url)?.extractMimeType()
        mimeType != null && (mimeType.startsWith(IMAGE_MIME_PREFIX) || mimeType.startsWith(VIDEO_MIME_PREFIX))
    }

private const val IMAGE_MIME_PREFIX = "image"
private const val VIDEO_MIME_PREFIX = "video"
