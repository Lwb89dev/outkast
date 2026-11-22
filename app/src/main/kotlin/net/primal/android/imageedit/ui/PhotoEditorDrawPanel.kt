package net.primal.android.imageedit.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.librenostr.android.R
import net.primal.android.imageedit.domain.Annotation
import net.primal.android.imageedit.domain.AnnotationKind
import net.primal.android.theme.AppTheme

/** The colors offered first, as packed ARGB. Any other is one tap away in the picker. */
private val PALETTE: List<Int> = listOf(
    0xFFFFFFFF,
    0xFF000000,
    0xFFFF3B30,
    0xFFFF9500,
    0xFFFFCC00,
    0xFF34C759,
    0xFF007AFF,
    0xFFAF52DE,
).map { it.toInt() }

private val RAINBOW = listOf(
    Color(0xFFFF0000),
    Color(0xFFFFFF00),
    Color(0xFF00FF00),
    Color(0xFF00FFFF),
    Color(0xFF0000FF),
    Color(0xFFFF00FF),
    Color(0xFFFF0000),
)

private val KIND_BUTTON_SIZE = 40.dp
private val SWATCH_SIZE = 30.dp
private val CUSTOM_DOT_SIZE = 14.dp

/**
 * The draw tool's panel: what to draw (a freehand line or a shape), in which color and how thick, and the buttons to
 * take back the last thing drawn or clear them all. Drawing itself is done on the picture, in [AnnotationLayer].
 */
@Composable
internal fun DrawPanel(state: PhotoEditorState) {
    var picking by remember { mutableStateOf(false) }
    val hasDrawing = state.recipe.annotations.isNotEmpty()
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KindButton(Icons.Outlined.Gesture, R.string.photo_edit_draw_pen, AnnotationKind.PEN, state)
                KindButton(Icons.Outlined.HorizontalRule, R.string.photo_edit_draw_line, AnnotationKind.LINE, state)
                KindButton(Icons.Outlined.NorthEast, R.string.photo_edit_draw_arrow, AnnotationKind.ARROW, state)
                KindButton(
                    icon = Icons.Outlined.CropSquare,
                    label = R.string.photo_edit_draw_rectangle,
                    kind = AnnotationKind.RECTANGLE,
                    state = state,
                )
                KindButton(
                    icon = Icons.Outlined.RadioButtonUnchecked,
                    label = R.string.photo_edit_draw_circle,
                    kind = AnnotationKind.ELLIPSE,
                    state = state,
                )
            }
            Box(Modifier.weight(1f))
            IconButton(onClick = state::undoAnnotation, enabled = hasDrawing, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.photo_edit_draw_undo))
            }
            IconButton(onClick = state::clearAnnotations, enabled = hasDrawing, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.photo_edit_draw_clear))
            }
        }
        ColorRow(state = state, onCustom = { picking = true })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.photo_edit_draw_thickness),
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colorScheme.onSurface,
            )
            Slider(
                value = state.drawWidth,
                onValueChange = state::chooseDrawWidth,
                valueRange = Annotation.MIN_WIDTH..Annotation.MAX_WIDTH,
                colors = SliderDefaults.colors(
                    thumbColor = AppTheme.colorScheme.primary,
                    activeTrackColor = AppTheme.colorScheme.primary,
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            if (state.drawKind == AnnotationKind.RECTANGLE || state.drawKind == AnnotationKind.ELLIPSE) {
                FilterChip(
                    selected = state.drawFilled,
                    onClick = { state.chooseDrawFilled(!state.drawFilled) },
                    label = { Text(stringResource(R.string.photo_edit_draw_fill)) },
                    leadingIcon = if (state.drawFilled) {
                        { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
    if (picking) {
        AnnotationColorPicker(
            initial = Color(state.drawColor),
            onPick = {
                state.chooseDrawColor(it.toArgb())
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun KindButton(icon: ImageVector, @StringRes label: Int, kind: AnnotationKind, state: PhotoEditorState) {
    val selected = state.drawKind == kind
    val tint = if (selected) AppTheme.colorScheme.primary else AppTheme.extraColorScheme.onSurfaceVariantAlt3
    Box(
        modifier = Modifier
            .size(KIND_BUTTON_SIZE)
            .clip(CircleShape)
            .background(if (selected) AppTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = { state.selectDrawKind(kind) }),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = stringResource(label), tint = tint)
    }
}

/**
 * The palette, spread over the width, and the rainbow that opens the picker; the rainbow shows the color when it is
 * not one of the palette's.
 */
@Composable
private fun ColorRow(state: PhotoEditorState, onCustom: () -> Unit) {
    val current = state.drawColor
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PALETTE.forEach { argb ->
            Swatch(argb = argb, selected = argb == current, onClick = { state.chooseDrawColor(argb) })
        }
        CustomSwatch(current = current, selected = current !in PALETTE, onClick = onCustom)
    }
}

@Composable
private fun CustomSwatch(current: Int, selected: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.photo_edit_draw_custom_color)
    val outline = if (selected) AppTheme.colorScheme.primary else AppTheme.extraColorScheme.surfaceVariantAlt1
    Box(
        modifier = Modifier
            .size(SWATCH_SIZE)
            .clip(CircleShape)
            .background(Brush.sweepGradient(RAINBOW))
            .border(if (selected) 3.dp else 1.dp, outline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(CUSTOM_DOT_SIZE)
                    .clip(CircleShape)
                    .background(Color(current))
                    .border(1.5.dp, Color.White, CircleShape),
            )
        }
    }
}

@Composable
private fun Swatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.photo_edit_draw_color, hexOf(argb))
    val outline = if (selected) AppTheme.colorScheme.primary else AppTheme.extraColorScheme.surfaceVariantAlt1
    Box(
        modifier = Modifier
            .size(SWATCH_SIZE)
            .clip(CircleShape)
            .background(Color(argb))
            .border(if (selected) 3.dp else 1.dp, outline, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
    )
}
