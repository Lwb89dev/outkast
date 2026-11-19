package net.primal.android.imageedit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.hypot
import net.primal.android.imageedit.domain.Annotation
import net.primal.android.imageedit.domain.AnnotationKind
import net.primal.android.imageedit.domain.AnnotationPoint
import net.primal.android.imageedit.domain.AnnotationShape
import net.primal.android.imageedit.domain.AnnotationSpace

/** A shape has to be dragged at least this far (in screen pixels) to count; anything less was a stray touch. */
private const val MIN_SHAPE_DRAG = 6f

/** A freehand stroke takes a new point once the finger has moved this far, so a slow stroke is not dense. */
private const val PEN_STEP = 2f

/**
 * What was drawn over the photo, painted over the picture, and in the draw tool the surface the finger draws on.
 *
 * The strokes come from the same [AnnotationSpace] the renderer uses for the saved picture, so what is seen here is
 * what ends up in the file; they are drawn here, over the preview, and not into it, so a stroke is on screen the
 * moment the finger lifts.
 */
@Composable
internal fun AnnotationLayer(state: PhotoEditorState, imageAspect: Float) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var draft by remember { mutableStateOf<Annotation?>(null) }
    val drawing = state.tool == EditTool.DRAW
    val rect = imageRect(size, imageAspect)
    val space = AnnotationSpace(state.recipe.geometry.crop, rect.width, rect.height)
    val currentRect by rememberUpdatedState(rect)
    val currentSpace by rememberUpdatedState(space)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .then(
                if (drawing) {
                    Modifier.pointerInput(state) {
                        drawStrokes(state, { currentRect }, { currentSpace }) { draft = it }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        // A drawing can reach past the picture (a crop that moved in on it, a finger dragged off the edge); the file
        // is cut at the picture's edge and so is the screen.
        clipRect(left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom) {
            translate(left = rect.left, top = rect.top) {
                state.recipe.annotations.forEach { drawAnnotation(it, space) }
                draft?.let { drawAnnotation(it, space) }
            }
        }
    }
}

/**
 * Follows one finger at a time: it starts a stroke or a shape where the finger lands, grows it while the finger
 * moves, and hands it to the editor when the finger lifts. A gesture the system cancels leaves nothing behind.
 */
private suspend fun PointerInputScope.drawStrokes(
    state: PhotoEditorState,
    rect: () -> Rect,
    space: () -> AnnotationSpace,
    onDraft: (Annotation?) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val area = rect()
        val map = space()
        val kind = state.drawKind
        val start = map.pointAt(down.position.x - area.left, down.position.y - area.top)
        var points = listOf(start)

        fun annotation() = Annotation(kind, state.drawColor, state.drawWidth, points, state.drawFilled)
        onDraft(annotation())

        val finished = drag(down.id) { change ->
            change.consume()
            val next = map.pointAt(change.position.x - area.left, change.position.y - area.top)
            points = when {
                kind != AnnotationKind.PEN -> listOf(start, next)
                map.distance(points.last(), next) >= PEN_STEP -> points + next
                else -> points
            }
            onDraft(annotation())
        }

        val result = annotation()
        onDraft(null)
        val shapeIsReal = kind == AnnotationKind.PEN || map.distance(start, points.last()) >= MIN_SHAPE_DRAG
        if (finished && shapeIsReal) state.addAnnotation(result)
    }
}

/** How far apart two annotation points are on screen, in pixels. */
private fun AnnotationSpace.distance(a: AnnotationPoint, b: AnnotationPoint) =
    hypot(pixelX(b) - pixelX(a), pixelY(b) - pixelY(a))

private fun DrawScope.drawAnnotation(annotation: Annotation, space: AnnotationSpace) {
    val color = Color(annotation.color)
    val width = space.strokePixels(annotation)
    val stroke = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    for (shape in space.shapesOf(annotation)) {
        when (shape) {
            is AnnotationShape.Polyline -> drawPolyline(shape, color, width, stroke)
            is AnnotationShape.Box -> {
                val topLeft = Offset(shape.left, shape.top)
                val boxSize = Size(shape.right - shape.left, shape.bottom - shape.top)
                if (shape.filled) drawRect(color, topLeft, boxSize)
                drawRect(color, topLeft, boxSize, style = stroke)
            }

            is AnnotationShape.Oval -> {
                val topLeft = Offset(shape.left, shape.top)
                val boxSize = Size(shape.right - shape.left, shape.bottom - shape.top)
                if (shape.filled) drawOval(color, topLeft, boxSize)
                drawOval(color, topLeft, boxSize, style = stroke)
            }
        }
    }
}

private fun DrawScope.drawPolyline(line: AnnotationShape.Polyline, color: Color, width: Float, stroke: Stroke) {
    if (line.x.size == 1) {
        drawCircle(color, radius = width / 2f, center = Offset(line.x[0], line.y[0]))
        return
    }
    val path = Path().apply {
        moveTo(line.x[0], line.y[0])
        for (i in 1 until line.x.size) lineTo(line.x[i], line.y[i])
    }
    drawPath(path, color, style = stroke)
}
