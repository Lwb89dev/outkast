@file:Suppress("MagicNumber")

package net.primal.android.imageedit.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What an [Annotation] draws: a freehand stroke, or a shape dragged out between two points. */
enum class AnnotationKind { PEN, LINE, ARROW, RECTANGLE, ELLIPSE }

/**
 * A point as fractions of the (rotated and straightened) picture *before* the crop, the same space [Crop] is written
 * in: 0,0 is the top left, 1,1 the bottom right. Written there, and not on the finished picture, an annotation stays
 * on the thing it was drawn on when the crop is moved afterwards.
 */
data class AnnotationPoint(val x: Float, val y: Float)

/**
 * One thing drawn over the photo, part of the [EditRecipe] like any adjustment, so it is a value and can be undone
 * by dropping it from the list.
 *
 * A [AnnotationKind.PEN] stroke has as many [points] as the finger gave; every other kind has two, the start and the
 * end of the drag (for a rectangle or an ellipse, opposite corners of its box).
 */
data class Annotation(
    val kind: AnnotationKind,
    /** Packed ARGB. The alpha is the opacity of the whole annotation, which never darkens where strokes cross. */
    val color: Int,
    /** The thickness of the line, as a fraction of the longer side of the finished picture. */
    val width: Float,
    val points: List<AnnotationPoint>,
    /** Rectangles and ellipses only: filled rather than outlined. */
    val filled: Boolean = false,
) {
    /** False for an annotation with nothing to draw (a shape with fewer than two points, a stroke with none). */
    val isDrawable: Boolean
        get() = when (kind) {
            AnnotationKind.PEN -> points.isNotEmpty()
            else -> points.size >= 2
        }

    fun clamped() =
        copy(
            width = width.coerceIn(MIN_WIDTH, MAX_WIDTH),
            points = points.map { AnnotationPoint(it.x.limited(), it.y.limited()) },
        )

    private fun Float.limited() = coerceIn(-OUTSIDE, 1f + OUTSIDE)

    /** The same drawing after the picture was turned a quarter turn clockwise. */
    fun rotatedClockwise() = mapPoints { AnnotationPoint(1f - it.y, it.x) }

    /** The same drawing after the picture was mirrored, left to right or (when [horizontal] is false) top to bottom. */
    fun mirrored(horizontal: Boolean) =
        mapPoints { if (horizontal) AnnotationPoint(1f - it.x, it.y) else AnnotationPoint(it.x, 1f - it.y) }

    private fun mapPoints(transform: (AnnotationPoint) -> AnnotationPoint) = copy(points = points.map(transform))

    companion object {
        const val MIN_WIDTH = 0.002f
        const val MAX_WIDTH = 0.06f
        const val DEFAULT_WIDTH = 0.008f

        /** How far past the picture's edge a point may be: a finger can drag off it, and the crop can cut it. */
        private const val OUTSIDE = 1f
    }
}

/**
 * The finished picture's pixels, [width] x [height], and which part of the pre-crop picture they show. It turns the
 * fractions annotations are stored in into pixels and back, for the renderer and for the editor's touch overlay
 * alike, so both draw the same thing.
 */
class AnnotationSpace(private val crop: Crop, val width: Float, val height: Float) {
    private val cropWidth = max(crop.right - crop.left, Crop.MIN_SIZE)
    private val cropHeight = max(crop.bottom - crop.top, Crop.MIN_SIZE)

    /** The longer side, which line widths are a fraction of. */
    val longSide: Float get() = max(width, height)

    fun pixelX(point: AnnotationPoint) = (point.x - crop.left) / cropWidth * width

    fun pixelY(point: AnnotationPoint) = (point.y - crop.top) / cropHeight * height

    /** The annotation point under pixel ([x], [y]) of the finished picture. */
    fun pointAt(x: Float, y: Float) =
        AnnotationPoint(crop.left + x / width * cropWidth, crop.top + y / height * cropHeight)

    /** The thickness of [annotation]'s line in pixels, never thinner than a hairline that still shows. */
    fun strokePixels(annotation: Annotation) = max(annotation.width * longSide, MIN_STROKE_PIXELS)

    /** What [annotation] is made of, in pixels of the finished picture. */
    fun shapesOf(annotation: Annotation): List<AnnotationShape> {
        if (!annotation.isDrawable) return emptyList()
        val xs = FloatArray(annotation.points.size) { pixelX(annotation.points[it]) }
        val ys = FloatArray(annotation.points.size) { pixelY(annotation.points[it]) }
        val last = xs.size - 1
        return when (annotation.kind) {
            AnnotationKind.PEN, AnnotationKind.LINE -> listOf(AnnotationShape.Polyline(xs, ys))
            AnnotationKind.ARROW -> arrowShapes(xs[0], ys[0], xs[last], ys[last], strokePixels(annotation))
            AnnotationKind.RECTANGLE -> {
                val box = boxOf(xs[0], ys[0], xs[last], ys[last])
                listOf(AnnotationShape.Box(box[0], box[1], box[2], box[3], annotation.filled))
            }

            AnnotationKind.ELLIPSE -> {
                val box = boxOf(xs[0], ys[0], xs[last], ys[last])
                listOf(AnnotationShape.Oval(box[0], box[1], box[2], box[3], annotation.filled))
            }
        }
    }

    /** Left, top, right and bottom of the box with these two opposite corners, whichever way it was dragged. */
    private fun boxOf(x0: Float, y0: Float, x1: Float, y1: Float) =
        floatArrayOf(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))

    /** A shaft with an open arrowhead at its end, the head sized with the line but never longer than half the shaft. */
    private fun arrowShapes(x0: Float, y0: Float, x1: Float, y1: Float, stroke: Float): List<AnnotationShape> {
        val length = hypot(x1 - x0, y1 - y0)
        val shaft = AnnotationShape.Polyline(floatArrayOf(x0, x1), floatArrayOf(y0, y1))
        if (length < MIN_ARROW_LENGTH) return listOf(shaft)
        val head = min(max(stroke * HEAD_PER_STROKE, longSide * MIN_HEAD_FRACTION), length * MAX_HEAD_PER_SHAFT)
        val backX = (x0 - x1) / length
        val backY = (y0 - y1) / length
        val wingX = FloatArray(HEAD_POINTS)
        val wingY = FloatArray(HEAD_POINTS)
        for ((index, turn) in listOf(HEAD_ANGLE, null, -HEAD_ANGLE).withIndex()) {
            if (turn == null) {
                wingX[index] = x1
                wingY[index] = y1
            } else {
                wingX[index] = x1 + head * (backX * cos(turn) - backY * sin(turn))
                wingY[index] = y1 + head * (backX * sin(turn) + backY * cos(turn))
            }
        }
        return listOf(shaft, AnnotationShape.Polyline(wingX, wingY))
    }

    private companion object {
        const val MIN_STROKE_PIXELS = 1.5f
        const val MIN_ARROW_LENGTH = 1.5f
        const val HEAD_PER_STROKE = 4.5f
        const val MIN_HEAD_FRACTION = 0.03f
        const val MAX_HEAD_PER_SHAFT = 0.5f
        const val HEAD_POINTS = 3
        val HEAD_ANGLE = (28 * PI / 180).toFloat()
    }
}

/** The pieces an annotation is drawn as, in pixels. Every one is stroked with a round cap and join. */
sealed interface AnnotationShape {
    /** Connected segments through ([x], [y]); one point is a dot. */
    class Polyline(val x: FloatArray, val y: FloatArray) : AnnotationShape

    /** An axis-aligned rectangle. */
    class Box(val left: Float, val top: Float, val right: Float, val bottom: Float, val filled: Boolean) :
        AnnotationShape

    /** The ellipse that fits in the box. */
    class Oval(val left: Float, val top: Float, val right: Float, val bottom: Float, val filled: Boolean) :
        AnnotationShape
}
