@file:Suppress("MagicNumber")

package net.primal.android.imageedit.domain

import java.util.stream.IntStream
import kotlin.math.floor
import kotlin.math.max

/**
 * Where the original's pixels are read from: an image in memory for a preview, a decoded bitmap on the phone for a
 * full-size copy.
 */
interface PixelSource {
    val width: Int
    val height: Int

    /** Fills [out] (row by row, [w] pixels per row) with the packed ARGB pixels of the box at ([x], [y]). */
    fun read(x: Int, y: Int, w: Int, h: Int, out: IntArray)
}

class ImagePixelSource(private val image: RgbImage) : PixelSource {
    override val width: Int get() = image.width
    override val height: Int get() = image.height

    override fun read(x: Int, y: Int, w: Int, h: Int, out: IntArray) {
        for (row in 0 until h) {
            System.arraycopy(image.pixels, (y + row) * image.width + x, out, row * w, w)
        }
    }
}

/**
 * Draws a recipe on a picture: one resampling for the whole geometry, then the tone and color steps, then sharpening
 * and the vignette.
 *
 * It works in horizontal bands, reading from the original only the box each band needs, so a 50-megapixel photo can
 * be drawn at full size without holding it (or its result) in memory as arrays; a preview is simply one band. Rows
 * are independent, so they are drawn on all cores. Pure Kotlin on ints and floats: the same code runs in the unit
 * tests and on the phone.
 */
object EditRenderer {
    /**
     * A picture at most [maxEdge] pixels on its longest side (or full size if it is smaller), with [recipe]
     * applied.
     */
    fun render(source: PixelSource, recipe: EditRecipe, maxEdge: Int): RgbImage {
        val (width, height) = GeometryMap(source.width, source.height, recipe.geometry).sizeWithin(maxEdge)
        val pixels = IntArray(width * height)
        renderBands(source, recipe, width, height, max(height, 1)) { top, rows, band ->
            System.arraycopy(band, 0, pixels, top * width, rows * width)
        }
        return RgbImage(width, height, pixels)
    }

    /**
     * Draws the picture as [outWidth] x [outHeight] pixels in bands of [bandRows] rows, handing each finished band to
     * [sink] in order (top row, number of rows, packed ARGB pixels).
     */
    @Suppress("LongParameterList")
    fun renderBands(
        source: PixelSource,
        recipe: EditRecipe,
        outWidth: Int,
        outHeight: Int,
        bandRows: Int,
        sink: (Int, Int, IntArray) -> Unit,
    ) {
        val r = recipe.clamped()
        val map = GeometryMap(source.width, source.height, r.geometry)
        val colors = ColorPipeline(r.adjustments, r.filter, r.filterAmount)
        val radius = sharpenRadius(outWidth, outHeight)
        val margin = if (r.adjustments.sharpness > 0f) radius * 2 else 0
        var top = 0
        while (top < outHeight) {
            val bottom = minOf(top + bandRows, outHeight)
            val from = max(0, top - margin)
            val to = minOf(outHeight, bottom + margin)
            val band = drawBand(BandRequest(source, map, colors, r, outWidth, outHeight, from, to, radius))
            val rows = bottom - top
            val out = IntArray(rows * outWidth)
            System.arraycopy(band, (top - from) * outWidth, out, 0, out.size)
            sink(top, rows, out)
            top = bottom
        }
    }

    /** The radius of sharpening that looks the same at any size: about one pixel per 800 of the longest side. */
    fun sharpenRadius(width: Int, height: Int): Int =
        (max(width, height) / SHARPEN_PIXELS_PER_RADIUS).coerceIn(1, MAX_SHARPEN_RADIUS)

    /** What one band needs to be drawn, grouped so the drawing function does not take ten loose parameters. */
    @Suppress("LongParameterList")
    private class BandRequest(
        val source: PixelSource,
        val map: GeometryMap,
        val colors: ColorPipeline,
        val recipe: EditRecipe,
        val outWidth: Int,
        val outHeight: Int,
        val from: Int,
        val to: Int,
        val radius: Int,
    )

    private fun drawBand(request: BandRequest): IntArray {
        val rows = request.to - request.from
        val box = request.map.sourceBox(
            top = request.from,
            bottom = request.to,
            outWidth = request.outWidth,
            outHeight = request.outHeight,
            margin = WINDOW_MARGIN,
        )
        val window = IntArray(box[2] * box[3])
        request.source.read(box[0], box[1], box[2], box[3], window)
        val band = IntArray(rows * request.outWidth)
        IntStream.range(0, rows).parallel().forEach { row ->
            val point = DoubleArray(2)
            for (x in 0 until request.outWidth) {
                request.map.sourcePoint(
                    x = x + 0.5,
                    y = request.from + row + 0.5,
                    outWidth = request.outWidth,
                    outHeight = request.outHeight,
                    out = point,
                )
                band[row * request.outWidth + x] = sample(window, box, point[0], point[1])
            }
        }
        if (!request.colors.isIdentity) request.colors.apply(band, 0, band.size)
        DetailPasses.sharpen(band, request.outWidth, rows, request.radius, request.recipe.adjustments.sharpness)
        DetailPasses.vignette(
            pixels = band,
            width = request.outWidth,
            top = request.from,
            rows = rows,
            height = request.outHeight,
            strength = request.recipe.adjustments.vignette,
        )
        return band
    }

    /**
     * Bilinear interpolation of the window at a point given in pixels of the original (centres at half integers), edge
     * pixels repeated.
     */
    private fun sample(window: IntArray, box: IntArray, sourceX: Double, sourceY: Double): Int {
        val w = box[2]
        val h = box[3]
        val fx = sourceX - 0.5 - box[0]
        val fy = sourceY - 0.5 - box[1]
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = (fx - x0).toFloat()
        val ty = (fy - y0).toFloat()
        val xa = x0.coerceIn(0, w - 1)
        val xb = (x0 + 1).coerceIn(0, w - 1)
        val ya = y0.coerceIn(0, h - 1)
        val yb = (y0 + 1).coerceIn(0, h - 1)
        val corners = intArrayOf(window[ya * w + xa], window[ya * w + xb], window[yb * w + xa], window[yb * w + xb])
        return (OPAQUE shl ALPHA_SHIFT) or
            (mix(corners, tx, ty, RED_SHIFT) shl RED_SHIFT) or
            (mix(corners, tx, ty, GREEN_SHIFT) shl GREEN_SHIFT) or
            mix(corners, tx, ty, 0)
    }

    /** One channel of the four neighbouring pixels (top left, top right, bottom left, bottom right), blended. */
    private fun mix(corners: IntArray, tx: Float, ty: Float, shift: Int): Int {
        val a = ((corners[0] shr shift) and BYTE) * (1 - tx) + ((corners[1] shr shift) and BYTE) * tx
        val b = ((corners[2] shr shift) and BYTE) * (1 - tx) + ((corners[3] shr shift) and BYTE) * tx
        return (a * (1 - ty) + b * ty + 0.5f).toInt().coerceIn(0, BYTE)
    }

    private const val WINDOW_MARGIN = 2
    private const val SHARPEN_PIXELS_PER_RADIUS = 800
    private const val MAX_SHARPEN_RADIUS = 6
    private const val BYTE = 0xFF
    private const val OPAQUE = 0xFF
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
}
