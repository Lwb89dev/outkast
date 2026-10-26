package net.primal.android.imageedit.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import kotlin.math.max
import kotlin.math.sqrt
import net.primal.android.imageedit.domain.PixelSource
import net.primal.android.imageedit.domain.RgbImage

/** A decoded bitmap as the renderer's source: the box asked for is read straight out of the bitmap. */
class BitmapPixelSource(private val bitmap: Bitmap) : PixelSource {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun read(x: Int, y: Int, w: Int, h: Int, out: IntArray) {
        bitmap.getPixels(out, 0, w, x, y, w, h)
    }
}

/** A bitmap as a plain picture in memory (only for small ones: previews and thumbnails). */
fun Bitmap.toRgbImage(): RgbImage {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return RgbImage(width, height, pixels)
}

fun RgbImage.toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

/**
 * Decodes the photo a user picked, for editing. Every picture comes out as an upright, software, sRGB bitmap:
 * upright because the editor has no notion of EXIF orientation, software so its pixels can be read back, and sRGB
 * (wide-gamut photos are converted) so that an edit looks the same everywhere and the arithmetic of the recipe is
 * well defined. A picture with transparency is laid on white, because the editor works on opaque pixels and would
 * otherwise turn the transparent parts black.
 */
class PhotoEditLoader(private val context: Context) {

    /** A photo no larger than [maxEdge] on its longest side (never enlarged), upright. */
    fun decode(uri: Uri, maxEdge: Int): Bitmap =
        decode(uri) { width, height ->
            val scale = maxEdge.toDouble() / max(width, height)
            if (scale < 1.0) scaled(width, height, scale) else null
        }

    /** A photo at full resolution, or reduced to at most [maxPixels] pixels if it is bigger. */
    fun decodeWithin(uri: Uri, maxPixels: Long): Bitmap =
        decode(uri) { width, height ->
            val pixels = width.toLong() * height
            if (pixels > maxPixels) scaled(width, height, sqrt(maxPixels.toDouble() / pixels)) else null
        }

    private fun scaled(width: Int, height: Int, scale: Double) =
        (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)

    private fun decode(uri: Uri, target: (width: Int, height: Int) -> Pair<Int, Int>?): Bitmap {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(uri, target)
        } else {
            decodeWithBitmapFactory(uri, target)
        }
        return bitmap.onWhiteIfTransparent()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(uri: Uri, target: (Int, Int) -> Pair<Int, Int>?): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            target(info.size.width, info.size.height)?.let { (w, h) -> decoder.setTargetSize(w, h) }
        }
    }

    /**
     * Android 8 (API 26-27) has no ImageDecoder: sample down with BitmapFactory and apply EXIF orientation by hand.
     *
     * The framework `ExifInterface` is used on purpose: it is only reached on those two releases, only to read the
     * orientation tag of a photo the user picked, and `androidx.exifinterface` is not a dependency of the app.
     */
    @SuppressLint("ExifInterface")
    private fun decodeWithBitmapFactory(uri: Uri, target: (Int, Int) -> Pair<Int, Int>?): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not a picture: $uri" }

        val wanted = target(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            inSampleSize = wanted?.let { (w, _) -> sampleSizeFor(bounds.outWidth, w) } ?: 1
        }
        val decoded = requireNotNull(
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) },
        ) { "could not decode $uri" }

        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        return decoded.uprightFor(orientation)
    }

    private fun sampleSizeFor(sourceWidth: Int, targetWidth: Int): Int {
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
        return sample
    }

    private fun Bitmap.uprightFor(orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(ROTATE_90)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(ROTATE_180)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(ROTATE_270)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(ROTATE_90)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(ROTATE_270)
                matrix.postScale(-1f, 1f)
            }
            else -> return this
        }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true).also { if (it !== this) recycle() }
    }

    private fun Bitmap.onWhiteIfTransparent(): Bitmap {
        if (!hasAlpha()) return this
        val flat = createBitmap(width, height)
        Canvas(flat).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(this@onWhiteIfTransparent, 0f, 0f, null)
        }
        recycle()
        return flat
    }

    private companion object {
        const val ROTATE_90 = 90f
        const val ROTATE_180 = 180f
        const val ROTATE_270 = 270f
    }
}
