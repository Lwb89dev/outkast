package net.primal.android.imageedit.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import net.primal.core.utils.runCatching

/**
 * Puts the photo editor between "the user picked a photo" and "the photo is attached".
 *
 * Returns the function to call with whatever a picker or the camera returned. Pictures that can be edited open the
 * editor one after the other, and what comes out of it ([onReady] with the edited file, or with the original when
 * nothing was changed) is what gets attached. Everything else goes straight to [onReady] untouched: videos, because
 * there is nothing to draw on, and GIFs, SVGs and animated WebPs, because the editor works on a single still frame and
 * would silently flatten an animation. A photo the user cancels out of is not attached at all.
 *
 * The editor is shown from the composable this is called in, so call it where the picker button lives.
 */
@Composable
fun rememberPhotoEditGate(onReady: (List<Uri>) -> Unit): (List<Uri>) -> Unit {
    val context = LocalContext.current
    val currentOnReady by rememberUpdatedState(onReady)
    var waiting by remember { mutableStateOf(emptyList<Uri>()) }

    waiting.firstOrNull()?.let { uri ->
        PhotoEditorDialog(
            sourceUri = uri,
            onDone = { edited ->
                waiting = waiting.drop(1)
                currentOnReady(listOf(edited ?: uri))
            },
            onCancel = { waiting = waiting.drop(1) },
        )
    }

    return remember(context) {
        { picked: List<Uri> ->
            val (editable, untouched) = picked.partition { context.isEditablePhoto(it) }
            if (untouched.isNotEmpty()) currentOnReady(untouched)
            if (editable.isNotEmpty()) waiting = waiting + editable
        }
    }
}

/** True for a still picture the editor can open; false for video, animations and anything of unknown type. */
internal fun Context.isEditablePhoto(uri: Uri): Boolean {
    val mimeType = runCatching { contentResolver.getType(uri) }.getOrNull() ?: return false
    return when {
        !mimeType.startsWith("image/") -> false
        mimeType == MIME_GIF || mimeType == MIME_SVG -> false
        mimeType == MIME_WEBP -> !isAnimatedWebp(uri)
        else -> true
    }
}

/**
 * An animated WebP says so in the flags byte of its `VP8X` header chunk: bytes 12..15 read "VP8X" and bit 1 of byte 20
 * is the animation flag. A lossless or lossy still has no such chunk, or has the bit clear.
 */
private fun Context.isAnimatedWebp(uri: Uri): Boolean {
    val header = ByteArray(WEBP_HEADER_SIZE)
    val read = runCatching {
        contentResolver.openInputStream(uri)?.use { it.read(header) } ?: 0
    }.getOrNull() ?: 0
    if (read < WEBP_HEADER_SIZE) return false
    val isVp8x = String(header, WEBP_CHUNK_OFFSET, WEBP_CHUNK_NAME_SIZE, Charsets.US_ASCII) == "VP8X"
    return isVp8x && (header[WEBP_FLAGS_OFFSET].toInt() and WEBP_ANIMATION_FLAG) != 0
}

private const val MIME_GIF = "image/gif"
private const val MIME_SVG = "image/svg+xml"
private const val MIME_WEBP = "image/webp"
private const val WEBP_HEADER_SIZE = 21
private const val WEBP_CHUNK_OFFSET = 12
private const val WEBP_CHUNK_NAME_SIZE = 4
private const val WEBP_FLAGS_OFFSET = 20
private const val WEBP_ANIMATION_FLAG = 0x02
