package net.primal.android.imageedit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import net.primal.android.R
import net.primal.android.theme.AppTheme

private const val HUE_MAX = 360f
private const val HSV_PARTS = 3
private const val RGB_MASK = 0xFFFFFF
private const val HUE_STOPS = 6
private val PICKER_AREA_HEIGHT = 200.dp
private val HUE_BAR_HEIGHT = 28.dp

/**
 * A color picker for the draw tool: a square for saturation (left to right) and brightness (bottom to top) of the
 * chosen hue, and a bar to choose the hue. The color is only handed back when the user confirms.
 */
@Composable
internal fun AnnotationColorPicker(initial: Color, onPick: (Color) -> Unit, onDismiss: () -> Unit) {
    val start = remember(initial) {
        FloatArray(HSV_PARTS).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) }
    }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var saturation by remember { mutableFloatStateOf(start[1]) }
    var brightness by remember { mutableFloatStateOf(start[2]) }
    val color = Color.hsv(hue, saturation, brightness)

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = AppTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = stringResource(R.string.photo_edit_draw_custom_color),
                    style = AppTheme.typography.titleMedium,
                    color = AppTheme.colorScheme.onSurface,
                )
                ShadeArea(
                    hue = hue,
                    saturation = saturation,
                    brightness = brightness,
                    onChange = { s, b ->
                        saturation = s
                        brightness = b
                    },
                )
                HueBar(hue = hue, onChange = { hue = it })
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        modifier = Modifier
                            .size(36.dp)
                            .border(1.dp, AppTheme.extraColorScheme.surfaceVariantAlt1, CircleShape),
                        shape = CircleShape,
                        color = color,
                    ) {}
                    Text(
                        text = hexOf(color.toArgb()),
                        style = AppTheme.typography.bodyLarge,
                        color = AppTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.photo_edit_cancel)) }
                    TextButton(onClick = { onPick(color) }) { Text(stringResource(R.string.photo_edit_done)) }
                }
            }
        }
    }
}

/** Saturation and brightness of one hue as a square; a finger on it, or dragged over it, picks the point under it. */
@Composable
private fun ShadeArea(hue: Float, saturation: Float, brightness: Float, onChange: (Float, Float) -> Unit) {
    val description = stringResource(R.string.photo_edit_draw_picker_area)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(PICKER_AREA_HEIGHT)
            .clip(RoundedCornerShape(12.dp))
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun pick(at: Offset) =
                        onChange(
                            (at.x / size.width).coerceIn(0f, 1f),
                            1f - (at.y / size.height).coerceIn(0f, 1f),
                        )
                    pick(down.position)
                    drag(down.id) { change ->
                        change.consume()
                        pick(change.position)
                    }
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        // The handle stays whole at the edges: a full saturation or brightness is a handle on the border.
        val reach = 13.dp.toPx()
        val at = Offset(
            x = (saturation * size.width).coerceIn(reach, size.width - reach),
            y = ((1f - brightness) * size.height).coerceIn(reach, size.height - reach),
        )
        drawCircle(Color.White, radius = 11.dp.toPx(), center = at, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = reach, center = at, style = Stroke(1.dp.toPx()))
    }
}

/** The hues from red round to red again, as a bar with a handle at the chosen one. */
@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    val description = stringResource(R.string.photo_edit_draw_hue)
    val stops = remember { (0..HUE_STOPS).map { Color.hsv(it * HUE_MAX / HUE_STOPS, 1f, 1f) } }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(HUE_BAR_HEIGHT)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(stops))
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onChange((down.position.x / size.width).coerceIn(0f, 1f) * HUE_MAX)
                    drag(down.id) { change ->
                        change.consume()
                        onChange((change.position.x / size.width).coerceIn(0f, 1f) * HUE_MAX)
                    }
                }
            },
    ) {
        val radius = size.height / 2f - 1.dp.toPx()
        val at = Offset((hue / HUE_MAX * size.width).coerceIn(radius, size.width - radius), size.height / 2f)
        drawCircle(Color.White, radius = radius, center = at, style = Stroke(3.dp.toPx()))
    }
}

/** `#RRGGBB` of a packed ARGB color, the alpha left out. */
internal fun hexOf(argb: Int): String = "#%06X".format(argb and RGB_MASK)
