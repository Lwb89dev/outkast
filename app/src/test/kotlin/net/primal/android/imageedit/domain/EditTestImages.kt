@file:Suppress("ktlint:standard:max-line-length", "MaxLineLength", "ComplexCondition", "MagicNumber")

package net.primal.android.imageedit.domain

/** Small pictures with known content for the edit tests. */
object EditTestImages {
    fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    fun solid(width: Int, height: Int, color: Int) = RgbImage(width, height, IntArray(width * height) { color })

    /** Every pixel a different color, so any mix-up of positions shows. */
    fun coordinates(width: Int, height: Int) = RgbImage(width, height, IntArray(width * height) { i -> argb((i % width) * 255 / maxOf(1, width - 1), (i / width) * 255 / maxOf(1, height - 1), 128) })

    /** A smooth colorful picture, like a photograph in that it has gradients and no exact repeats. */
    fun photoLike(width: Int, height: Int) = RgbImage(
        width, height,
        IntArray(width * height) { i ->
            val x = (i % width) / width.toFloat()
            val y = (i / width) / height.toFloat()
            argb(
                (255 * (0.5 + 0.5 * Math.sin(6.0 * x + 2.0 * y))).toInt().coerceIn(0, 255),
                (255 * (0.5 + 0.5 * Math.cos(5.0 * y - 3.0 * x))).toInt().coerceIn(0, 255),
                (255 * (0.3 + 0.6 * x * y)).toInt().coerceIn(0, 255),
            )
        },
    )

    fun channel(pixel: Int, shift: Int) = (pixel shr shift) and 0xFF
    fun red(pixel: Int) = channel(pixel, 16)
    fun green(pixel: Int) = channel(pixel, 8)
    fun blue(pixel: Int) = channel(pixel, 0)
}
