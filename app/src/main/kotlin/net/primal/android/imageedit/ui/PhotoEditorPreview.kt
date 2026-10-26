package net.primal.android.imageedit.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import net.primal.android.R
import net.primal.android.imageedit.domain.Crop
import net.primal.android.imageedit.domain.CropHandle
import net.primal.android.imageedit.domain.CropTool
import net.primal.android.theme.AppTheme

/**
 * The photo as edited. Hold it (outside the crop tool) to see the original; in the crop tool the crop rectangle is
 * drawn over it.
 */
@Composable
fun PhotoEditorPreview(state: PhotoEditorState, modifier: Modifier = Modifier) {
    var comparing by remember { mutableStateOf(false) }
    val cropping = state.tool == EditTool.CROP
    val shown = if (comparing && !cropping) state.original else state.preview
    Box(
        modifier = modifier.fillMaxSize().pointerInput(cropping) {
            if (!cropping) {
                detectTapGestures(
                    onPress = {
                        comparing = true
                        tryAwaitRelease()
                        comparing = false
                    },
                )
            }
        },
    ) {
        if (shown != null) PreviewPicture(shown, state, cropping)
        if (!cropping && state.changed) {
            // Holding the picture does the same; this is for whoever cannot hold (a screen reader, a shaky hand) or
            // has not found that yet.
            OriginalToggle(
                showing = comparing,
                onToggle = { comparing = !comparing },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            )
        }
    }
}

/**
 * The "Original" button over the photo. It sits on the picture itself, which can be any colour, so it carries its own
 * background: dark and nearly opaque with white text and a thin light outline, and white with dark text while the
 * original is being shown.
 */
@Composable
private fun OriginalToggle(showing: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val background = if (showing) Color.White else Color.Black.copy(alpha = 0.72f)
    val content = if (showing) Color.Black else Color.White
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(50))
            .selectable(selected = showing, role = Role.Button, onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Image, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(
            text = stringResource(R.string.photo_edit_original),
            color = content,
            style = AppTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** The picture, with the crop rectangle over it while the crop tool is open. */
@Composable
private fun PreviewPicture(shown: Bitmap, state: PhotoEditorState, cropping: Boolean) {
    Image(
        bitmap = shown.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
    )
    if (!cropping) return
    val aspect = shown.width.toFloat() / shown.height
    CropOverlay(
        imageAspect = aspect,
        crop = state.recipe.geometry.crop,
        ratio = CropTool.ratioOf(state.cropShape, aspect),
        onChange = { crop -> state.geometry { it.copy(crop = crop) } },
    )
}

private const val TOUCH_SLOP = 28f

/**
 * The crop rectangle over the picture: the outside is dimmed, the inside has a rule-of-thirds grid, and the corners,
 * edges and middle can be dragged. The arithmetic is in [CropTool]; this only turns touches on the screen into
 * fractions of the picture.
 */
@Composable
private fun CropOverlay(imageAspect: Float, crop: Crop, ratio: Float?, onChange: (Crop) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val currentCrop by rememberUpdatedState(crop)
    val currentRatio by rememberUpdatedState(ratio)
    val shown = imageRect(size, imageAspect)
    var handle by remember { mutableStateOf<CropHandle?>(null) }
    val description = stringResource(
        R.string.photo_edit_crop_area,
        percent(crop.left),
        percent(crop.right),
        percent(crop.top),
        percent(crop.bottom),
    )
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .semantics { contentDescription = description }
            .pointerInput(size, imageAspect) {
                detectDragGestures(
                    onDragStart = { at ->
                        val rect = imageRect(size, imageAspect)
                        handle = CropTool.handleAt(
                            crop = currentCrop,
                            x = (at.x - rect.left) / rect.width,
                            y = (at.y - rect.top) / rect.height,
                            slop = TOUCH_SLOP * density / rect.width,
                        )
                    },
                    onDragEnd = { handle = null },
                    onDragCancel = { handle = null },
                    onDrag = { change, delta ->
                        val active = handle ?: return@detectDragGestures
                        change.consume()
                        val rect = imageRect(size, imageAspect)
                        onChange(
                            CropTool.drag(
                                crop = currentCrop,
                                handle = active,
                                dx = delta.x / rect.width,
                                dy = delta.y / rect.height,
                                ratio = currentRatio,
                                canvasAspect = imageAspect,
                            ),
                        )
                    },
                )
            },
    ) { drawCrop(shown, crop) }
}

private fun percent(fraction: Float) = (fraction * 100).roundToInt()

/** Where the picture is drawn inside a container of [size] (fitted and centred), in pixels. */
private fun imageRect(size: IntSize, aspect: Float): Rect {
    if (size.width == 0 || size.height == 0 || aspect <= 0f) return Rect(Offset.Zero, Size(1f, 1f))
    val containerAspect = size.width.toFloat() / size.height
    val (w, h) = if (aspect > containerAspect) {
        size.width.toFloat() to size.width / aspect
    } else {
        size.height * aspect to size.height.toFloat()
    }
    return Rect(Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h))
}

private fun DrawScope.drawCrop(image: Rect, crop: Crop) {
    val rect = Rect(
        left = image.left + crop.left * image.width,
        top = image.top + crop.top * image.height,
        right = image.left + crop.right * image.width,
        bottom = image.top + crop.bottom * image.height,
    )
    val dim = Color.Black.copy(alpha = 0.55f)
    drawRect(dim, Offset(image.left, image.top), Size(image.width, rect.top - image.top))
    drawRect(dim, Offset(image.left, rect.bottom), Size(image.width, image.bottom - rect.bottom))
    drawRect(dim, Offset(image.left, rect.top), Size(rect.left - image.left, rect.height))
    drawRect(dim, Offset(rect.right, rect.top), Size(image.right - rect.right, rect.height))
    drawRect(Color.White, rect.topLeft, rect.size, style = Stroke(width = 2.dp.toPx()))
    drawThirds(rect)
    drawCorners(rect)
}

private fun DrawScope.drawThirds(rect: Rect) {
    val grid = Color.White.copy(alpha = 0.5f)
    for (i in 1..2) {
        val x = rect.left + rect.width * i / 3
        val y = rect.top + rect.height * i / 3
        drawLine(grid, Offset(x, rect.top), Offset(x, rect.bottom), strokeWidth = 1.dp.toPx())
        drawLine(grid, Offset(rect.left, y), Offset(rect.right, y), strokeWidth = 1.dp.toPx())
    }
}

private fun DrawScope.drawCorners(rect: Rect) {
    val corner = 18.dp.toPx()
    val stroke = 4.dp.toPx()
    val corners = listOf(
        Triple(rect.topLeft, 1f, 1f),
        Triple(rect.topRight, -1f, 1f),
        Triple(rect.bottomLeft, 1f, -1f),
        Triple(rect.bottomRight, -1f, -1f),
    )
    for ((point, dx, dy) in corners) {
        drawLine(Color.White, point, Offset(point.x + dx * corner, point.y), strokeWidth = stroke)
        drawLine(Color.White, point, Offset(point.x, point.y + dy * corner), strokeWidth = stroke)
    }
}
