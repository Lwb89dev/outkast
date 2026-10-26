package net.primal.android.imageedit.ui

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.PhotoFilter
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.util.Locale
import net.primal.android.R
import net.primal.android.imageedit.domain.Adjustments
import net.primal.android.imageedit.domain.CropShape
import net.primal.android.imageedit.domain.EditFilter
import net.primal.android.imageedit.domain.Geometry
import net.primal.android.theme.AppTheme

private val PANEL_MAX_HEIGHT = 230.dp
private val UNIT = -1f..1f

/** The panel of the chosen tool, and the row of tools under it. */
@Composable
fun PhotoEditorTools(state: PhotoEditorState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().heightIn(max = PANEL_MAX_HEIGHT).padding(horizontal = 16.dp)) {
            when (state.tool) {
                EditTool.LIGHT -> LightPanel(state)
                EditTool.COLOR -> ColorPanel(state)
                EditTool.DETAIL -> DetailPanel(state)
                EditTool.FILTERS -> FilterPanel(state)
                EditTool.CROP -> CropPanel(state)
            }
        }
        ToolRow(state)
    }
}

// --- The row of tools -----------------------------------------------------------------------------

/** The tools spread over the width when they fit, and scroll sideways when they do not (large text, narrow phone). */
@Composable
private fun ToolRow(state: PhotoEditorState) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .widthIn(min = maxWidth)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ToolButton(
                icon = Icons.Outlined.AutoFixHigh,
                label = R.string.photo_edit_tool_auto,
                selected = false,
                role = Role.Button,
            ) { state.autoEnhance() }
            ToolButton(Icons.Outlined.Tune, R.string.photo_edit_tool_light, state.tool == EditTool.LIGHT) {
                state.selectTool(EditTool.LIGHT)
            }
            ToolButton(Icons.Outlined.Palette, R.string.photo_edit_tool_color, state.tool == EditTool.COLOR) {
                state.selectTool(EditTool.COLOR)
            }
            ToolButton(Icons.Outlined.BlurOn, R.string.photo_edit_tool_detail, state.tool == EditTool.DETAIL) {
                state.selectTool(EditTool.DETAIL)
            }
            ToolButton(Icons.Outlined.PhotoFilter, R.string.photo_edit_tool_filters, state.tool == EditTool.FILTERS) {
                state.selectTool(EditTool.FILTERS)
            }
            ToolButton(Icons.Outlined.Crop, R.string.photo_edit_tool_crop, state.tool == EditTool.CROP) {
                state.selectTool(EditTool.CROP)
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    @StringRes label: Int,
    selected: Boolean,
    role: Role = Role.Tab,
    onClick: () -> Unit,
) {
    val tint = if (selected) AppTheme.colorScheme.primary else AppTheme.extraColorScheme.onSurfaceVariantAlt3
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .semantics { if (role == Role.Tab) this.selected = selected }
            .clickable(role = role, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
        Text(
            text = stringResource(label),
            style = AppTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
        )
    }
}

// --- Sliders ----------------------------------------------------------------------------------------

/** A labelled slider that shows its value and returns to [neutral] when the label is tapped. */
@Composable
private fun AdjustSlider(
    @StringRes label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    neutral: Float = 0f,
    onChange: (Float) -> Unit,
) {
    val name = stringResource(label)
    val valueText = String.format(Locale.US, "%+.2f", value)
    val resetLabel = stringResource(R.string.photo_edit_slider_reset)
    Column(Modifier.fillMaxWidth()) {
        // Sighted users tap the name to reset; for a screen reader the slider itself carries the name, the value and
        // the reset action, so this row is hidden from it.
        Row(
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).clickable { onChange(neutral) },
            )
            Text(
                text = valueText,
                style = AppTheme.typography.labelMedium,
                color = if (value == neutral) {
                    AppTheme.extraColorScheme.onSurfaceVariantAlt3
                } else {
                    AppTheme.colorScheme.primary
                },
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = AppTheme.colorScheme.primary,
                activeTrackColor = AppTheme.colorScheme.primary,
            ),
            modifier = Modifier.semantics {
                contentDescription = name
                stateDescription = valueText
                customActions = listOf(CustomAccessibilityAction(resetLabel) { onChange(neutral); true })
            },
        )
    }
}

@Composable
private fun LightPanel(state: PhotoEditorState) {
    val a = state.recipe.adjustments
    Column(Modifier.verticalScroll(rememberScrollState())) {
        AdjustSlider(
            label = R.string.photo_edit_adj_exposure,
            value = a.exposure,
            range = -Adjustments.EXPOSURE_RANGE..Adjustments.EXPOSURE_RANGE,
        ) { v -> state.adjust { it.copy(exposure = v) } }
        AdjustSlider(R.string.photo_edit_adj_brightness, a.brightness, UNIT) { v ->
            state.adjust { it.copy(brightness = v) }
        }
        AdjustSlider(R.string.photo_edit_adj_contrast, a.contrast, UNIT) { v ->
            state.adjust { it.copy(contrast = v) }
        }
        AdjustSlider(R.string.photo_edit_adj_highlights, a.highlights, UNIT) { v ->
            state.adjust { it.copy(highlights = v) }
        }
        AdjustSlider(R.string.photo_edit_adj_shadows, a.shadows, UNIT) { v ->
            state.adjust { it.copy(shadows = v) }
        }
        AdjustSlider(R.string.photo_edit_adj_black_point, a.blackPoint, UNIT) { v ->
            state.adjust { it.copy(blackPoint = v) }
        }
    }
}

@Composable
private fun ColorPanel(state: PhotoEditorState) {
    val a = state.recipe.adjustments
    Column(Modifier.verticalScroll(rememberScrollState())) {
        AdjustSlider(R.string.photo_edit_adj_saturation, a.saturation, UNIT) { v ->
            state.adjust { it.copy(saturation = v) }
        }
        AdjustSlider(R.string.photo_edit_adj_vibrance, a.vibrance, UNIT) { v ->
            state.adjust { it.copy(vibrance = v) }
        }
        AdjustSlider(R.string.photo_edit_adj_temperature, a.temperature, UNIT) { v ->
            state.adjust { it.copy(temperature = v) }
        }
        AdjustSlider(R.string.photo_edit_adj_tint, a.tint, UNIT) { v ->
            state.adjust { it.copy(tint = v) }
        }
    }
}

@Composable
private fun DetailPanel(state: PhotoEditorState) {
    val a = state.recipe.adjustments
    Column(Modifier.verticalScroll(rememberScrollState())) {
        AdjustSlider(R.string.photo_edit_adj_sharpness, a.sharpness, 0f..1f) { v ->
            state.adjust { it.copy(sharpness = v) }
        }
        AdjustSlider(R.string.photo_edit_adj_vignette, a.vignette, UNIT) { v ->
            state.adjust { it.copy(vignette = v) }
        }
    }
}

// --- Filters ------------------------------------------------------------------------------------------

@Composable
private fun FilterPanel(state: PhotoEditorState) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(EditFilter.entries.toList(), key = { it.name }) { filter ->
                FilterItem(
                    filter = filter,
                    selected = state.recipe.filter == filter,
                    thumbnail = state.filterThumbnails[filter],
                    onSelect = { state.setFilter(filter) },
                )
            }
        }
        if (state.recipe.filter != EditFilter.NONE) {
            AdjustSlider(
                label = R.string.photo_edit_filter_amount,
                value = state.recipe.filterAmount,
                range = 0f..1f,
                neutral = 1f,
                onChange = state::setFilterAmount,
            )
        }
    }
}

@Composable
private fun FilterItem(filter: EditFilter, selected: Boolean, thumbnail: Bitmap?, onSelect: () -> Unit) {
    val outline = if (selected) AppTheme.colorScheme.primary else AppTheme.extraColorScheme.surfaceVariantAlt1
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp).clip(shape).border(2.dp, outline, shape)) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp),
                )
            }
        }
        Text(
            text = stringResource(filterName(filter)),
            style = AppTheme.typography.labelSmall,
            color = if (selected) AppTheme.colorScheme.primary else AppTheme.extraColorScheme.onSurfaceVariantAlt3,
        )
    }
}

@StringRes
private fun filterName(filter: EditFilter): Int =
    when (filter) {
        EditFilter.NONE -> R.string.photo_edit_filter_none
        EditFilter.VIVID -> R.string.photo_edit_filter_vivid
        EditFilter.WARM -> R.string.photo_edit_filter_warm
        EditFilter.COOL -> R.string.photo_edit_filter_cool
        EditFilter.MONO -> R.string.photo_edit_filter_mono
        EditFilter.NOIR -> R.string.photo_edit_filter_noir
        EditFilter.FADE -> R.string.photo_edit_filter_fade
        EditFilter.CHROME -> R.string.photo_edit_filter_chrome
        EditFilter.SEPIA -> R.string.photo_edit_filter_sepia
    }

// --- Crop and geometry --------------------------------------------------------------------------------

@Composable
private fun CropPanel(state: PhotoEditorState) {
    val g = state.recipe.geometry
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = state::rotate) {
                Icon(Icons.Outlined.RotateRight, stringResource(R.string.photo_edit_crop_rotate))
            }
            IconButton(onClick = state::flip) {
                Icon(Icons.Outlined.Flip, stringResource(R.string.photo_edit_crop_flip))
            }
            ShapeChips(selected = state.cropShape, onSelect = state::lockCropShape)
        }
        AdjustSlider(
            label = R.string.photo_edit_geo_straighten,
            value = g.straightenDegrees,
            range = -Geometry.MAX_STRAIGHTEN..Geometry.MAX_STRAIGHTEN,
        ) { v -> state.geometry { it.copy(straightenDegrees = v) } }
        AdjustSlider(R.string.photo_edit_geo_perspective_vertical, g.perspectiveVertical, UNIT) { v ->
            state.geometry { it.copy(perspectiveVertical = v) }
        }
        AdjustSlider(R.string.photo_edit_geo_perspective_horizontal, g.perspectiveHorizontal, UNIT) { v ->
            state.geometry { it.copy(perspectiveHorizontal = v) }
        }
    }
}

/** The shapes the crop can be locked to, one chip each. */
@Composable
private fun ShapeChips(selected: CropShape, onSelect: (CropShape) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(CropShape.entries.toList(), key = { it.name }) { shape ->
            FilterChip(
                selected = selected == shape,
                onClick = { onSelect(shape) },
                label = { Text(shapeName(shape)) },
            )
        }
    }
}

@Composable
private fun shapeName(shape: CropShape): String =
    when (shape) {
        CropShape.FREE -> stringResource(R.string.photo_edit_shape_free)
        CropShape.ORIGINAL -> stringResource(R.string.photo_edit_shape_original)
        CropShape.SQUARE -> stringResource(R.string.photo_edit_shape_square)
        CropShape.FOUR_THREE -> "4:3"
        CropShape.THREE_TWO -> "3:2"
        CropShape.SIXTEEN_NINE -> "16:9"
    }
