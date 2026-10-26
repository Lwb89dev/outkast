package net.primal.android.imageedit.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Sharpening and vignette: the two steps that look at where a pixel is, not only at its color. Both work on packed
 * ARGB in place.
 */
object DetailPasses {
    /**
     * Unsharp mask on lightness: blur the lightness by [radius], and add [amount] times what the blur took away to
     * every channel. Edges get crisper and flat areas are untouched. [width] x [rows] pixels, row by row; pixels
     * outside the band are treated as the edge.
     */
    fun sharpen(pixels: IntArray, width: Int, rows: Int, radius: Int, amount: Float) {
        if (amount <= 0f || radius <= 0) return
        val luma = FloatArray(width * rows) { i ->
            val p = pixels[i]
            ColorPipeline.luma(
                ((p shr RED_SHIFT) and BYTE) / MAX_BYTE,
                ((p shr GREEN_SHIFT) and BYTE) / MAX_BYTE,
                (p and BYTE) / MAX_BYTE,
            )
        }
        val blurred = boxBlur(boxBlur(luma, width, rows, radius), width, rows, radius)
        for (i in pixels.indices) {
            val delta = (luma[i] - blurred[i]) * amount * SHARPEN_GAIN
            val p = pixels[i]
            val r = ((p shr RED_SHIFT) and BYTE) / MAX_BYTE + delta
            val g = ((p shr GREEN_SHIFT) and BYTE) / MAX_BYTE + delta
            val b = (p and BYTE) / MAX_BYTE + delta
            pixels[i] = (p and ALPHA_MASK) or
                (ColorPipeline.toByte(r) shl RED_SHIFT) or
                (ColorPipeline.toByte(g) shl GREEN_SHIFT) or
                ColorPipeline.toByte(b)
        }
    }

    /**
     * Darkens (positive [strength]) or lightens (negative) towards the corners of a [width] x [height] picture; [top]
     * is the band's first row.
     */
    fun vignette(pixels: IntArray, width: Int, top: Int, rows: Int, height: Int, strength: Float) {
        if (strength == 0f) return
        for (row in 0 until rows) {
            val ny = (top + row + 0.5f - height / 2f) / (height / 2f)
            for (x in 0 until width) {
                val nx = (x + 0.5f - width / 2f) / (width / 2f)
                val falloff = smoothstep(VIGNETTE_START, VIGNETTE_END, sqrt(nx * nx + ny * ny))
                val index = row * width + x
                pixels[index] = shade(pixels[index], falloff, strength)
            }
        }
    }

    private fun shade(p: Int, falloff: Float, strength: Float): Int {
        fun channel(shift: Int): Int {
            val v = ((p shr shift) and BYTE) / MAX_BYTE
            val out = if (strength > 0f) {
                v * (1f - strength * VIGNETTE_DARKEN * falloff)
            } else {
                v + (1f - v) * -strength * VIGNETTE_LIGHTEN * falloff
            }
            return ColorPipeline.toByte(out)
        }
        return (p and ALPHA_MASK) or (channel(RED_SHIFT) shl RED_SHIFT) or (channel(GREEN_SHIFT) shl GREEN_SHIFT) or
            channel(0)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** A blur in both directions with a running sum, so its cost does not depend on [radius]. */
    private fun boxBlur(source: FloatArray, width: Int, rows: Int, radius: Int): FloatArray {
        val horizontal = FloatArray(source.size)
        for (y in 0 until rows) blurLine(source, y * width, 1, width, horizontal, radius)
        val out = FloatArray(source.size)
        for (x in 0 until width) blurLine(horizontal, x, width, rows, out, radius)
        return out
    }

    private fun blurLine(source: FloatArray, from: Int, step: Int, length: Int, target: FloatArray, radius: Int) {
        val window = (2 * radius + 1).toFloat()
        var sum = 0f
        for (i in -radius..radius) sum += source[from + min(max(i, 0), length - 1) * step]
        for (i in 0 until length) {
            target[from + i * step] = sum / window
            sum += source[from + min(i + radius + 1, length - 1) * step] - source[from + max(i - radius, 0) * step]
        }
    }

    private const val BYTE = 0xFF
    private const val MAX_BYTE = 255f
    private const val ALPHA_MASK = 0xFF shl 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val SHARPEN_GAIN = 2.5f
    private const val VIGNETTE_START = 0.45f
    private const val VIGNETTE_END = 1.3f
    private const val VIGNETTE_DARKEN = 0.75f
    private const val VIGNETTE_LIGHTEN = 0.5f
}
