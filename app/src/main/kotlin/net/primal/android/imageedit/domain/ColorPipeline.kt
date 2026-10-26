package net.primal.android.imageedit.domain

import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The tone and color part of an edit, applied to pixels in place. Every step is a plain formula so a recipe means the
 * same thing on any phone, and every step is a no-op at its neutral value.
 *
 * What depends only on one color channel (white balance, exposure, black point, brightness, contrast) is folded into
 * a 256-entry table per channel, built once per picture; per pixel there are then only three lookups. What looks at
 * several channels at once (highlights and shadows, saturation, vibrance, the filter) is done per pixel afterwards.
 *
 * Order: white balance and exposure in linear light, then black point, brightness and contrast on the display values,
 * then shadows and highlights, saturation and vibrance, and last the filter.
 */
class ColorPipeline(adjustments: Adjustments, private val filter: EditFilter, filterAmount: Float) {
    private val a = adjustments.clamped()
    private val amount = if (filter == EditFilter.NONE) 0f else filterAmount.coerceIn(0f, 1f)
    private val tables: Array<FloatArray> = Array(CHANNELS) { channel -> channelTable(channel) }
    private val needsSecondPass = a.highlights != 0f ||
        a.shadows != 0f ||
        a.saturation != 0f ||
        a.vibrance != 0f ||
        amount > 0f

    /** True if applying this pipeline would leave every pixel as it is. */
    val isIdentity: Boolean
        get() = !needsSecondPass && tables.all { table ->
            table.indices.all { abs(table[it] - it / MAX_BYTE) < IDENTITY_TOLERANCE }
        }

    /**
     * Applies the pipeline to `pixels[from until to]` (packed ARGB, alpha left as it is), on all cores for big
     * ranges.
     */
    fun apply(pixels: IntArray, from: Int, to: Int) {
        val chunks = (to - from + CHUNK - 1) / CHUNK
        IntStream.range(0, chunks).parallel().forEach { chunk ->
            applyChunk(pixels, from + chunk * CHUNK, minOf(to, from + (chunk + 1) * CHUNK), FloatArray(CHANNELS))
        }
    }

    private fun applyChunk(pixels: IntArray, from: Int, to: Int, scratch: FloatArray) {
        val redTable = tables[0]
        val greenTable = tables[1]
        val blueTable = tables[2]
        for (i in from until to) {
            val p = pixels[i]
            scratch[0] = redTable[(p shr RED_SHIFT) and BYTE]
            scratch[1] = greenTable[(p shr GREEN_SHIFT) and BYTE]
            scratch[2] = blueTable[p and BYTE]
            if (needsSecondPass) secondPass(scratch)
            pixels[i] = (p and ALPHA_MASK) or
                (toByte(scratch[0]) shl RED_SHIFT) or
                (toByte(scratch[1]) shl GREEN_SHIFT) or
                toByte(scratch[2])
        }
    }

    /** Highlights and shadows, saturation and vibrance, then the filter, on the three values in [rgb], in place. */
    private fun secondPass(rgb: FloatArray) {
        var r = rgb[0]
        var g = rgb[1]
        var b = rgb[2]
        if (a.highlights != 0f || a.shadows != 0f) {
            val delta = toneDelta(luma(r, g, b))
            r += delta
            g += delta
            b += delta
        }
        if (a.saturation != 0f || a.vibrance != 0f) {
            val gray = luma(r, g, b)
            val factor = saturationFactor(r, g, b)
            r = gray + (r - gray) * factor
            g = gray + (g - gray) * factor
            b = gray + (b - gray) * factor
        }
        if (amount > 0f) {
            rgb[0] = clamp(r)
            rgb[1] = clamp(g)
            rgb[2] = clamp(b)
            FilterGrade.apply(filter, rgb)
            r += (rgb[0] - r) * amount
            g += (rgb[1] - g) * amount
            b += (rgb[2] - b) * amount
        }
        rgb[0] = r
        rgb[1] = g
        rgb[2] = b
    }

    /**
     * How much shadows and highlights move this lightness: each acts on its end of the range and fades to nothing
     * towards the middle.
     */
    private fun toneDelta(luma: Float): Float {
        val l = clamp(luma)
        val shadowMask = (1 - l) * (1 - l)
        val highlightMask = l * l
        val shadows = a.shadows * TONE_STRENGTH * shadowMask * (if (a.shadows > 0) 1 - l else l)
        val highlights = a.highlights * TONE_STRENGTH * highlightMask * (if (a.highlights > 0) 1 - l else l)
        return shadows + highlights
    }

    /** Saturation scales all colors; vibrance scales dull colors more than strong ones and skin tones half as much. */
    private fun saturationFactor(r: Float, g: Float, b: Float): Float {
        var factor = 1f + a.saturation
        if (a.vibrance != 0f) {
            val high = max(r, max(g, b))
            val low = min(r, min(g, b))
            val chroma = if (high <= 0f) 0f else (high - low) / high
            val skin = if (isSkinTone(r, g, b)) SKIN_PROTECTION else 1f
            factor *= 1f + a.vibrance * (1f - chroma) * skin
        }
        return max(0f, factor)
    }

    private fun isSkinTone(r: Float, g: Float, b: Float): Boolean =
        r > g && g > b && r - b > SKIN_MIN_SPREAD && (g - b) / max(r - b, EPS) > SKIN_MIN_GREEN

    // --- Per-channel table -------------------------------------------------------------------------

    private fun channelTable(channel: Int): FloatArray {
        val gain = whiteBalanceGain(channel) * 2f.pow(a.exposure)
        val blackPoint = a.blackPoint * BLACK_POINT_RANGE
        val gamma = 2f.pow(-a.brightness * BRIGHTNESS_RANGE)
        return FloatArray(TABLE_SIZE) { value ->
            var v = linearToDisplay(SRGB_TO_LINEAR[value] * gain)
            v = ((v - blackPoint) / (1f - blackPoint)).coerceIn(0f, 1f)
            if (gamma != 1f) v = v.pow(gamma)
            contrast(v)
        }
    }

    /**
     * Temperature warms by gaining red and losing blue; tint adds magenta by losing green. Applied in linear light,
     * where it acts like a real filter.
     */
    private fun whiteBalanceGain(channel: Int): Float =
        when (channel) {
            0 -> 2f.pow(a.temperature * WB_RANGE)
            1 -> 2f.pow(-a.tint * TINT_RANGE)
            else -> 2f.pow(-a.temperature * WB_RANGE)
        }

    private fun contrast(v: Float): Float =
        if (a.contrast >= 0f) {
            val smooth = v * v * (3f - 2f * v)
            v + (smooth - v) * a.contrast
        } else {
            0.5f + (v - 0.5f) * (1f + a.contrast * CONTRAST_SOFTEN)
        }

    companion object {
        private const val CHANNELS = 3
        private const val CHUNK = 1 shl 16
        private const val TABLE_SIZE = 256
        private const val MAX_BYTE = 255f
        private const val BYTE = 0xFF
        private const val ALPHA_MASK = 0xFF shl 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private const val IDENTITY_TOLERANCE = 0.5f / 255f
        private const val EPS = 1e-6f
        private const val WB_RANGE = 0.5f
        private const val TINT_RANGE = 0.35f
        private const val BLACK_POINT_RANGE = 0.15f
        private const val BRIGHTNESS_RANGE = 0.7f
        private const val CONTRAST_SOFTEN = 0.6f
        private const val TONE_STRENGTH = 0.4f
        private const val SKIN_PROTECTION = 0.5f
        private const val SKIN_MIN_SPREAD = 0.05f
        private const val SKIN_MIN_GREEN = 0.2f

        /** sRGB display value of a byte to linear light. */
        private val SRGB_TO_LINEAR = FloatArray(TABLE_SIZE) { i ->
            val c = i / MAX_BYTE
            if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }

        fun linearToDisplay(linear: Float): Float {
            val l = linear.coerceIn(0f, 1f)
            return if (l <= 0.0031308f) l * 12.92f else 1.055f * l.pow(1f / 2.4f) - 0.055f
        }

        fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

        fun clamp(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v

        fun toByte(v: Float): Int = (clamp(v) * MAX_BYTE + 0.5f).toInt()
    }
}

/**
 * What each ready-made look does, on the three display values (0..1) in [rgb], in place. The result may be outside
 * 0..1 (it is clamped later). Every look is a few multiplications, so applying one to a 12-megapixel photo costs no
 * more than the rest of the edit.
 */
object FilterGrade {
    fun apply(filter: EditFilter, rgb: FloatArray) {
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]
        when (filter) {
            EditFilter.NONE -> Unit
            EditFilter.VIVID -> colorful(rgb, 1.35f, CONTRAST_LIGHT)
            EditFilter.WARM -> {
                rgb[0] = r * 1.06f + 0.01f
                rgb[1] = g * 1.01f
                rgb[2] = b * 0.9f
            }
            EditFilter.COOL -> {
                rgb[0] = r * 0.92f
                rgb[1] = g
                rgb[2] = b * 1.08f + 0.01f
            }
            EditFilter.MONO -> mono(rgb, CONTRAST_NONE, 1f)
            EditFilter.NOIR -> mono(rgb, CONTRAST_STRONG, 0.92f)
            EditFilter.FADE -> {
                colorful(rgb, 0.85f, 0f)
                for (i in 0..2) rgb[i] = 0.08f + rgb[i] * 0.9f
            }
            EditFilter.CHROME -> {
                colorful(rgb, 1.15f, CONTRAST_MEDIUM)
                rgb[0] *= 0.98f
                rgb[2] *= 1.03f
            }
            EditFilter.SEPIA -> {
                val l = ColorPipeline.luma(r, g, b)
                rgb[0] = l * 1.05f
                rgb[1] = l * 0.86f
                rgb[2] = l * 0.66f
            }
        }
    }

    private const val CONTRAST_NONE = 0f
    private const val CONTRAST_LIGHT = 0.15f
    private const val CONTRAST_MEDIUM = 0.25f
    private const val CONTRAST_STRONG = 0.55f

    /** Color scaled by [factor] around the gray of the pixel, then an S-curve of strength [contrast]. */
    private fun colorful(rgb: FloatArray, factor: Float, contrast: Float) {
        val gray = ColorPipeline.luma(rgb[0], rgb[1], rgb[2])
        for (i in 0..2) rgb[i] = smooth(gray + (rgb[i] - gray) * factor, contrast)
    }

    private fun mono(rgb: FloatArray, contrast: Float, scale: Float) {
        val l = smooth(ColorPipeline.luma(rgb[0], rgb[1], rgb[2]), contrast) * scale
        rgb[0] = l
        rgb[1] = l
        rgb[2] = l
    }

    private fun smooth(value: Float, amount: Float): Float {
        val v = ColorPipeline.clamp(value)
        return v + (v * v * (3f - 2f * v) - v) * amount
    }
}
