package net.primal.android.imageedit.domain

/**
 * Decoded pixels, one packed ARGB int each, row by row. Plain data so the image maths runs in JVM tests; the Android
 * side converts to and from `Bitmap` at the edges only.
 */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) {
            "pixel count does not match $width x $height"
        }
    }
}
