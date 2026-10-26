package net.primal.android.imageedit.domain

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where each pixel of an edited picture comes from in the original. The whole geometry of a recipe (flip, quarter
 * turns, straighten, perspective, crop) is folded into one mapping, so the picture is resampled only once and a crop
 * never costs pixels that were thrown away anyway.
 *
 * Coordinates: the *canvas* is the original after the flip and quarter turns (its width and height swap for an odd
 * number of turns); straighten and perspective act on the canvas around its centre; the crop is a rectangle of the
 * canvas. An output pixel is mapped to a canvas point, moved through perspective and straighten, then back to the
 * original.
 */
class GeometryMap(private val sourceWidth: Int, private val sourceHeight: Int, geometry: Geometry) {
    private val g = geometry.clamped()
    private val turnsOdd = g.quarterTurns % 2 == 1

    /** Size of the canvas in pixels of the original. */
    val canvasWidth: Int = if (turnsOdd) sourceHeight else sourceWidth
    val canvasHeight: Int = if (turnsOdd) sourceWidth else sourceHeight

    private val cropLeft = g.crop.left * canvasWidth
    private val cropTop = g.crop.top * canvasHeight
    private val cropWidth = (g.crop.right - g.crop.left) * canvasWidth
    private val cropHeight = (g.crop.bottom - g.crop.top) * canvasHeight

    private val radians = Math.toRadians(g.straightenDegrees.toDouble())
    private val cosine = cos(radians)
    private val sine = sin(radians)
    private val horizontal = g.perspectiveHorizontal * PERSPECTIVE_STRENGTH
    private val vertical = g.perspectiveVertical * PERSPECTIVE_STRENGTH

    /**
     * How much the picture is enlarged around its centre so straighten and perspective leave no empty corner
     * (1 = not at all).
     */
    val fillZoom: Double = findFillZoom()

    /** Size of the cropped picture at full resolution, in pixels. */
    val fullWidth: Int get() = max(1, cropWidth.roundToInt())
    val fullHeight: Int get() = max(1, cropHeight.roundToInt())

    /**
     * The longest side to decode the original at so that the picture that comes out of this geometry still has
     * [targetEdge] pixels on its longest side (a tight crop needs a bigger decode to stay sharp), and never more than
     * [cap].
     */
    fun sourceEdgeFor(targetEdge: Int, cap: Int): Int {
        val output = max(fullWidth, fullHeight)
        val original = max(sourceWidth, sourceHeight)
        val needed = ceil(original * targetEdge.toDouble() / output).toInt()
        return needed.coerceIn(1, max(1, cap))
    }

    /** The size at which the longest side is at most [maxEdge] (never larger than full size). */
    fun sizeWithin(maxEdge: Int): Pair<Int, Int> {
        val scale = min(1.0, maxEdge.toDouble() / max(cropWidth, cropHeight))
        return max(1, (cropWidth * scale).roundToInt()) to max(1, (cropHeight * scale).roundToInt())
    }

    /**
     * The point of the original that output pixel ([x], [y]) of an [outWidth] x [outHeight] picture looks at, written
     * into [out] as x, y in pixels of the original (pixel centres at half integers).
     */
    fun sourcePoint(x: Double, y: Double, outWidth: Int, outHeight: Int, out: DoubleArray) {
        val canvasX = cropLeft + x / outWidth * cropWidth
        val canvasY = cropTop + y / outHeight * cropHeight
        canvasToSource(canvasX, canvasY, fillZoom, out)
    }

    /**
     * The smallest box of the original that output rows [top] until [bottom] can read, widened by [margin] pixels:
     * what a band needs.
     */
    fun sourceBox(top: Int, bottom: Int, outWidth: Int, outHeight: Int, margin: Int): IntArray {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        val point = DoubleArray(2)
        for (row in doubleArrayOf(top.toDouble(), bottom.toDouble())) {
            for (column in doubleArrayOf(0.0, outWidth.toDouble())) {
                sourcePoint(column, row, outWidth, outHeight, point)
                minX = min(minX, point[0])
                maxX = max(maxX, point[0])
                minY = min(minY, point[1])
                maxY = max(maxY, point[1])
            }
        }
        val x0 = (minX - margin).toInt().coerceIn(0, sourceWidth - 1)
        val y0 = (minY - margin).toInt().coerceIn(0, sourceHeight - 1)
        val x1 = (maxX + margin).toInt().coerceIn(x0 + 1, sourceWidth)
        val y1 = (maxY + margin).toInt().coerceIn(y0 + 1, sourceHeight)
        return intArrayOf(x0, y0, x1 - x0, y1 - y0)
    }

    private fun canvasToSource(canvasX: Double, canvasY: Double, zoom: Double, out: DoubleArray) {
        val halfW = canvasWidth / 2.0
        val halfH = canvasHeight / 2.0
        // From the middle of the canvas, enlarged by the fill zoom.
        val u = (canvasX - halfW) / zoom / halfW
        val v = (canvasY - halfH) / zoom / halfH
        // Perspective: a projective map, so straight lines stay straight.
        val d = 1.0 + horizontal * u + vertical * v
        val px = u / d * halfW
        val py = v / d * halfH
        // Straighten: sample the point rotated the other way, so the picture appears turned by the angle.
        val qx = px * cosine + py * sine + halfW
        val qy = -px * sine + py * cosine + halfH
        toSource(qx, qy, out)
    }

    /** Undoes the quarter turns and the flips: from a point of the canvas to a point of the original. */
    private fun toSource(canvasX: Double, canvasY: Double, out: DoubleArray) {
        val w = sourceWidth.toDouble()
        val h = sourceHeight.toDouble()
        var x: Double
        var y: Double
        when (g.quarterTurns) {
            0 -> { x = canvasX; y = canvasY }
            1 -> { x = canvasY; y = h - canvasX }
            2 -> { x = w - canvasX; y = h - canvasY }
            else -> { x = w - canvasY; y = canvasX }
        }
        if (g.flipHorizontal) x = w - x
        if (g.flipVertical) y = h - y
        out[0] = x
        out[1] = y
    }

    /** The least enlargement, at least 1, for which the four corners of the canvas map inside the original. */
    @Suppress("ReturnCount")
    private fun findFillZoom(): Double {
        if (g.straightenDegrees == 0f && g.perspectiveHorizontal == 0f && g.perspectiveVertical == 0f) return 1.0
        if (coversCanvas(1.0)) return 1.0
        var low = 1.0
        var high = MAX_ZOOM
        repeat(BISECTION_STEPS) {
            val middle = (low + high) / 2
            if (coversCanvas(middle)) high = middle else low = middle
        }
        return high
    }

    private fun coversCanvas(zoom: Double): Boolean {
        val point = DoubleArray(2)
        val w = canvasWidth.toDouble()
        val h = canvasHeight.toDouble()
        for ((cx, cy) in listOf(0.0 to 0.0, w to 0.0, 0.0 to h, w to h)) {
            canvasToCanvas(cx, cy, zoom, point)
            val insideX = point[0] >= -EPSILON && point[0] <= w + EPSILON
            val insideY = point[1] >= -EPSILON && point[1] <= h + EPSILON
            if (!insideX || !insideY) return false
        }
        return true
    }

    /** The same chain as [canvasToSource] but ending in canvas coordinates, to test where the corners land. */
    private fun canvasToCanvas(canvasX: Double, canvasY: Double, zoom: Double, out: DoubleArray) {
        val halfW = canvasWidth / 2.0
        val halfH = canvasHeight / 2.0
        val u = (canvasX - halfW) / zoom / halfW
        val v = (canvasY - halfH) / zoom / halfH
        val d = 1.0 + horizontal * u + vertical * v
        val px = u / d * halfW
        val py = v / d * halfH
        out[0] = px * cosine + py * sine + halfW
        out[1] = -px * sine + py * cosine + halfH
    }

    companion object {
        /**
         * How strong a perspective value of 1 is: the far edge of the picture is scaled by up to this much less or
         * more.
         */
        const val PERSPECTIVE_STRENGTH = 0.35
        private const val MAX_ZOOM = 4.0
        private const val BISECTION_STEPS = 30
        private const val EPSILON = 1e-6
    }
}
