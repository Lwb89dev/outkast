@file:Suppress("MagicNumber")

package net.primal.android.imageedit.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws annotations onto finished pixels. Every edge is anti-aliased from the distance of the pixel's centre to the
 * shape, so a line looks the same at the preview's size and at the full-size export: the shapes are fractions of the
 * picture and the thickness a fraction of its longer side, and both are turned into pixels here.
 *
 * Each annotation first fills a coverage map (the highest coverage any of its parts gives a pixel) and is then blended
 * into the picture once, so a freehand stroke that crosses itself, or a translucent one, never darkens at the overlap.
 * Pure Kotlin like the rest of the renderer, so the same code runs in the unit tests and on the phone.
 */
object AnnotationRaster {

    /**
     * Draws [annotations] on [pixels], which hold rows [top] until [top] + [rows] of a finished picture
     * [outWidth] x [outHeight] pixels (one packed ARGB int each, [outWidth] per row).
     */
    @Suppress("LongParameterList")
    fun draw(
        annotations: List<Annotation>,
        crop: Crop,
        pixels: IntArray,
        outWidth: Int,
        outHeight: Int,
        top: Int,
        rows: Int,
    ) {
        if (annotations.isEmpty()) return
        val space = AnnotationSpace(crop, outWidth.toFloat(), outHeight.toFloat())
        val band = Band(pixels, outWidth, top, rows)
        var coverage: FloatArray? = null
        for (annotation in annotations) {
            val map = coverage ?: FloatArray(outWidth * rows).also { coverage = it }
            drawOne(annotation, space, band, map)
        }
    }

    /** Fills the coverage of one annotation and blends it into the band; [map] is left cleared for the next one. */
    private fun drawOne(annotation: Annotation, space: AnnotationSpace, band: Band, map: FloatArray) {
        val shapes = space.shapesOf(annotation)
        val half = space.strokePixels(annotation) / 2f
        val box = band.reach(shapes, half)
        if (shapes.isEmpty() || box.isEmpty) return
        for (shape in shapes) cover(shape, half, band, map, box)
        blend(annotation.color, band, map, box)
    }

    /** The rows of the picture held in memory and the pixels they are in. */
    private class Band(val pixels: IntArray, val width: Int, val top: Int, val rows: Int) {
        /** The pixels the shapes can touch, grown by the line's half thickness and a pixel of anti-aliasing. */
        fun reach(shapes: List<AnnotationShape>, half: Float): Box {
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (shape in shapes) {
                val b = shape.bounds()
                left = min(left, b[0])
                top = min(top, b[1])
                right = max(right, b[2])
                bottom = max(bottom, b[3])
            }
            val grow = half + 1f
            return Box(
                left = max(0, floor(left - grow).toInt()),
                top = max(this.top, floor(top - grow).toInt()),
                right = min(width - 1, floor(right + grow).toInt()),
                bottom = min(this.top + rows - 1, floor(bottom + grow).toInt()),
            )
        }
    }

    /** Pixel columns and rows, both ends included. */
    private class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val isEmpty: Boolean get() = right < left || bottom < top
    }

    private fun AnnotationShape.bounds(): FloatArray =
        when (this) {
            is AnnotationShape.Polyline -> floatArrayOf(x.min(), y.min(), x.max(), y.max())
            is AnnotationShape.Box -> floatArrayOf(left, top, right, bottom)
            is AnnotationShape.Oval -> floatArrayOf(left, top, right, bottom)
        }

    private fun cover(shape: AnnotationShape, half: Float, band: Band, map: FloatArray, box: Box) {
        when (shape) {
            is AnnotationShape.Polyline -> coverPolyline(shape, half, band, map, box)
            is AnnotationShape.Box -> coverBox(shape, half, band, map, box)
            is AnnotationShape.Oval -> coverOval(shape, half, band, map, box)
        }
    }

    private fun coverPolyline(line: AnnotationShape.Polyline, half: Float, band: Band, map: FloatArray, box: Box) {
        val segments = max(line.x.size - 1, 1)
        for (i in 0 until segments) {
            val ax = line.x[i]
            val ay = line.y[i]
            val bx = line.x[min(i + 1, line.x.size - 1)]
            val by = line.y[min(i + 1, line.y.size - 1)]
            val grow = half + 1f
            val x0 = max(box.left, floor(min(ax, bx) - grow).toInt())
            val x1 = min(box.right, floor(max(ax, bx) + grow).toInt())
            val y0 = max(box.top, floor(min(ay, by) - grow).toInt())
            val y1 = min(box.bottom, floor(max(ay, by) + grow).toInt())
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val distance = distanceToSegment(x + 0.5f, y + 0.5f, ax, ay, bx, by)
                    raise(map, band, x, y, half + 0.5f - distance)
                }
            }
        }
    }

    private fun coverBox(rect: AnnotationShape.Box, half: Float, band: Band, map: FloatArray, box: Box) {
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        val hx = (rect.right - rect.left) / 2f
        val hy = (rect.bottom - rect.top) / 2f
        for (y in box.top..box.bottom) {
            for (x in box.left..box.right) {
                val qx = abs(x + 0.5f - cx) - hx
                val qy = abs(y + 0.5f - cy) - hy
                val outside = hypot(max(qx, 0f), max(qy, 0f))
                val signed = outside + min(max(qx, qy), 0f)
                raise(map, band, x, y, half + 0.5f - if (rect.filled) max(signed, 0f) else abs(signed))
            }
        }
    }

    private fun coverOval(oval: AnnotationShape.Oval, half: Float, band: Band, map: FloatArray, box: Box) {
        val cx = (oval.left + oval.right) / 2f
        val cy = (oval.top + oval.bottom) / 2f
        val rx = max((oval.right - oval.left) / 2f, 0.5f)
        val ry = max((oval.bottom - oval.top) / 2f, 0.5f)
        for (y in box.top..box.bottom) {
            for (x in box.left..box.right) {
                val signed = ellipseDistance(x + 0.5f - cx, y + 0.5f - cy, rx, ry)
                raise(map, band, x, y, half + 0.5f - if (oval.filled) max(signed, 0f) else abs(signed))
            }
        }
    }

    /**
     * How far ([px], [py]), measured from the centre, is from the outline of the ellipse with radii [rx] and [ry]:
     * negative inside. Exact for a circle and within a fraction of a pixel of it close to the line, which is all that
     * the anti-aliasing and the thickness look at.
     */
    private fun ellipseDistance(px: Float, py: Float, rx: Float, ry: Float): Float {
        val k0 = hypot(px / rx, py / ry)
        val k1 = hypot(px / (rx * rx), py / (ry * ry))
        return if (k1 < 1e-6f) -min(rx, ry) else k0 * (k0 - 1f) / k1
    }

    @Suppress("LongParameterList")
    private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared <= 1e-9f) 0f else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        val ex = px - (ax + t * dx)
        val ey = py - (ay + t * dy)
        return sqrt(ex * ex + ey * ey)
    }

    /** Keeps the highest coverage, [value] clamped to 0..1, a pixel has been given so far. */
    private fun raise(map: FloatArray, band: Band, x: Int, y: Int, value: Float) {
        if (value <= 0f) return
        val index = (y - band.top) * band.width + x
        val covered = if (value > 1f) 1f else value
        if (covered > map[index]) map[index] = covered
    }

    /** Blends [color] into the pixels of [box] by their coverage, then clears the coverage for the next annotation. */
    private fun blend(color: Int, band: Band, map: FloatArray, box: Box) {
        val opacity = ((color ushr 24) and 0xFF) / 255f
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        for (y in box.top..box.bottom) {
            for (x in box.left..box.right) {
                val index = (y - band.top) * band.width + x
                val alpha = map[index] * opacity
                if (alpha > 0f) {
                    val under = band.pixels[index]
                    band.pixels[index] = (0xFF shl 24) or
                        (mix((under shr 16) and 0xFF, red, alpha) shl 16) or
                        (mix((under shr 8) and 0xFF, green, alpha) shl 8) or
                        mix(under and 0xFF, blue, alpha)
                }
                map[index] = 0f
            }
        }
    }

    private fun mix(under: Int, over: Int, alpha: Float) =
        (under + (over - under) * alpha + 0.5f).toInt().coerceIn(0, 255)
}
