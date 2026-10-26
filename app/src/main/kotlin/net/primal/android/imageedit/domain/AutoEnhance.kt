package net.primal.android.imageedit.domain

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** A few numbers that describe a picture's light, measured on a sample of its pixels. */
data class ImageStats(
    /** Average lightness of the display values, 0 to 1. */
    val meanLuma: Float,
    /** The lightness below which 1% of the pixels lie. */
    val darkEnd: Float,
    /** The lightness above which 1% of the pixels lie. */
    val lightEnd: Float,
    /** Fraction of pixels that are nearly black. */
    val crushedShare: Float,
    /** Fraction of pixels that are nearly white. */
    val blownShare: Float,
    /** Average color strength, 0 to 1. */
    val meanChroma: Float,
) {
    companion object {
        /** Measures [image] from about 40,000 of its pixels. */
        fun of(image: RgbImage): ImageStats {
            val step = maxOf(1, sqrt(image.pixels.size / SAMPLE.toDouble()).toInt())
            val histogram = IntArray(BINS)
            var count = 0
            var sumLuma = 0.0
            var sumChroma = 0.0
            var y = 0
            while (y < image.height) {
                var x = 0
                while (x < image.width) {
                    val p = image.pixels[y * image.width + x]
                    val r = ((p shr 16) and 0xFF) / 255f
                    val g = ((p shr 8) and 0xFF) / 255f
                    val b = (p and 0xFF) / 255f
                    val luma = ColorPipeline.luma(r, g, b)
                    histogram[(luma * (BINS - 1) + 0.5f).toInt()]++
                    sumLuma += luma
                    val high = maxOf(r, g, b)
                    sumChroma += if (high <= 0f) 0f else (high - minOf(r, g, b)) / high
                    count++
                    x += step
                }
                y += step
            }
            val n = count.coerceAtLeast(1)
            return ImageStats(
                meanLuma = (sumLuma / n).toFloat(),
                darkEnd = percentile(histogram, n, 0.01f),
                lightEnd = percentile(histogram, n, 0.99f),
                crushedShare = histogram.take(CRUSHED_BINS).sum().toFloat() / n,
                blownShare = histogram.takeLast(CRUSHED_BINS).sum().toFloat() / n,
                meanChroma = (sumChroma / n).toFloat(),
            )
        }

        private fun percentile(histogram: IntArray, total: Int, fraction: Float): Float {
            var seen = 0
            for (bin in histogram.indices) {
                seen += histogram[bin]
                if (seen >= total * fraction) return bin / (BINS - 1f)
            }
            return 1f
        }

        private const val SAMPLE = 40_000
        private const val BINS = 256
        private const val CRUSHED_BINS = 6
    }
}

/**
 * "Auto enhance": looks at the picture and proposes adjustments, the way a person would nudge the sliders: brighten
 * what is dark, deepen blacks that are gray, lift crushed shadows, calm blown highlights, add contrast to a flat
 * picture, and a little color. It returns ordinary [Adjustments] the user can then change; nothing is hidden inside
 * "auto". A well-exposed picture gets almost nothing.
 */
object AutoEnhance {
    fun suggest(stats: ImageStats): Adjustments {
        val exposure = exposureFor(stats.meanLuma)
        val blackPoint = ((stats.darkEnd - DARK_TARGET) / BLACK_SPAN).coerceIn(0f, 1f) * BLACK_LIMIT
        val spread = stats.lightEnd - stats.darkEnd
        val contrast = ((FLAT_SPREAD - spread) / FLAT_SPREAD).coerceIn(0f, 1f) * CONTRAST_LIMIT
        val shadows = ((stats.crushedShare - CRUSHED_OK) / CRUSHED_SPAN).coerceIn(0f, 1f) * TONE_LIMIT
        val highlights = -((stats.blownShare - BLOWN_OK) / BLOWN_SPAN).coerceIn(0f, 1f) * TONE_LIMIT
        val vibrance = if (stats.meanChroma < DULL_CHROMA) VIBRANCE_DULL else VIBRANCE_NORMAL
        return Adjustments(
            exposure = exposure,
            contrast = contrast,
            highlights = highlights,
            shadows = shadows,
            blackPoint = blackPoint,
            vibrance = vibrance,
        ).clamped()
    }

    fun suggest(image: RgbImage): Adjustments = suggest(ImageStats.of(image))

    /**
     * Moves the average lightness towards a pleasant middle, but only half of the way and never by more than a stop
     * and a half. Exposure acts on linear light while the average is measured on display values, which are the light
     * raised to about 1/2.2, so a change of the display average by some ratio takes 2.2 times as many stops.
     */
    private fun exposureFor(meanLuma: Float): Float {
        val mean = meanLuma.coerceIn(MIN_MEAN, 1f)
        val stops = (ln(TARGET_MEAN / mean) / ln(2f)) * DISPLAY_GAMMA * MOVE_SHARE
        return if (abs(stops) < EXPOSURE_DEADBAND) 0f else stops.coerceIn(-MAX_EXPOSURE, MAX_EXPOSURE)
    }

    private const val TARGET_MEAN = 0.46f
    private const val DISPLAY_GAMMA = 2.2f
    private const val MIN_MEAN = 0.02f
    private const val MOVE_SHARE = 0.5f
    private const val MAX_EXPOSURE = 1.5f
    private const val EXPOSURE_DEADBAND = 0.1f
    private const val DARK_TARGET = 0.03f
    private const val BLACK_SPAN = 0.25f
    private const val BLACK_LIMIT = 0.6f
    private const val FLAT_SPREAD = 0.85f
    private const val CONTRAST_LIMIT = 0.35f
    private const val CRUSHED_OK = 0.15f
    private const val CRUSHED_SPAN = 0.35f
    private const val BLOWN_OK = 0.02f
    private const val BLOWN_SPAN = 0.15f
    private const val TONE_LIMIT = 0.45f
    private const val DULL_CHROMA = 0.25f
    private const val VIBRANCE_DULL = 0.3f
    private const val VIBRANCE_NORMAL = 0.12f
}
