package net.primal.android.imageedit.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.primal.android.BuildConfig
import net.primal.android.imageedit.domain.EditRecipe
import net.primal.android.imageedit.domain.EditRenderer
import net.primal.android.imageedit.domain.GeometryMap

/**
 * Turns an edit into the file that gets uploaded: the recipe is drawn over the original at full resolution and written
 * as a new JPEG in the app's cache, which is handed back as a content URI the upload code reads like any picked photo.
 * The original is only read, never touched.
 *
 * The photo is decoded once (reduced to at most [MAX_PIXELS] if it is bigger, which a social post never needs) and the
 * result is drawn in bands straight into a bitmap, so neither is ever held as a Java array of ints.
 *
 * The output carries no metadata at all: no location, no camera details, no timestamps. That is a deliberate
 * privacy gain for a photo about to be published, and it is also why the orientation needs no EXIF tag: the pixels
 * are already upright.
 */
class PhotoEditExporter(
    private val context: Context,
    private val loader: PhotoEditLoader = PhotoEditLoader(context),
) {

    /**
     * Draws [recipe] over the photo at [source] and returns the URI of the JPEG it wrote.
     *
     * @throws IOException if the picture cannot be decoded or the file cannot be written.
     * @throws OutOfMemoryError for a photo too big for the phone's memory; the caller falls back to the original.
     */
    suspend fun export(source: Uri, recipe: EditRecipe): Uri =
        withContext(Dispatchers.Default) {
            val bitmap = draw(source, recipe)
            try {
                val file = newOutputFile()
                file.outputStream().use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                        "could not write the edited picture"
                    }
                }
                FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", file)
            } finally {
                bitmap.recycle()
            }
        }

    private fun draw(source: Uri, recipe: EditRecipe): Bitmap {
        val decoded = loader.decodeWithin(source, MAX_PIXELS)
        try {
            val map = GeometryMap(decoded.width, decoded.height, recipe.geometry)
            val width = map.fullWidth
            val height = map.fullHeight
            val out = createBitmap(width, height)
            val bandRows = (BAND_PIXELS / width).coerceIn(MIN_BAND_ROWS, MAX_BAND_ROWS)
            EditRenderer.renderBands(BitmapPixelSource(decoded), recipe, width, height, bandRows) { top, rows, pixels ->
                out.setPixels(pixels, 0, width, 0, top, width, rows)
            }
            return out
        } finally {
            decoded.recycle()
        }
    }

    private fun newOutputFile(): File {
        val directory = File(context.cacheDir, OUTPUT_DIRECTORY).also { it.mkdirs() }
        deleteStaleFiles(directory)
        return File.createTempFile("edit_", ".jpg", directory)
    }

    /** Edited pictures are only needed until they are uploaded; whatever is a day old has long been used. */
    private fun deleteStaleFiles(directory: File) {
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MILLIS
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private companion object {
        /**
         * About 12 megapixels: more than any post is shown at, and small enough that the decoded photo and the
         * result (4 bytes a pixel each) stay far from the app's memory limit.
         */
        const val MAX_PIXELS = 12_000_000L
        const val JPEG_QUALITY = 92
        const val BAND_PIXELS = 2_000_000
        const val MIN_BAND_ROWS = 16
        const val MAX_BAND_ROWS = 512
        const val OUTPUT_DIRECTORY = "photo_edits"
        const val STALE_AFTER_MILLIS = 24L * 60 * 60 * 1000
    }
}
