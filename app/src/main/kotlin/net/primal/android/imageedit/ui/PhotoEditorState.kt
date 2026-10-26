package net.primal.android.imageedit.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.primal.android.imageedit.data.PhotoEditExporter
import net.primal.android.imageedit.data.PhotoEditLoader
import net.primal.android.imageedit.data.toBitmap
import net.primal.android.imageedit.data.toRgbImage
import net.primal.android.imageedit.domain.Adjustments
import net.primal.android.imageedit.domain.AutoEnhance
import net.primal.android.imageedit.domain.Crop
import net.primal.android.imageedit.domain.CropShape
import net.primal.android.imageedit.domain.CropTool
import net.primal.android.imageedit.domain.EditFilter
import net.primal.android.imageedit.domain.EditRecipe
import net.primal.android.imageedit.domain.EditRenderer
import net.primal.android.imageedit.domain.Geometry
import net.primal.android.imageedit.domain.ImagePixelSource
import net.primal.android.imageedit.domain.RgbImage
import net.primal.core.utils.onFailure
import net.primal.core.utils.runCatching

/** The groups of tools along the bottom of the editor. */
enum class EditTool { LIGHT, COLOR, DETAIL, FILTERS, CROP }

/**
 * Everything the photo editor holds while it is open: the recipe being edited, the reduced copy of the photo it is
 * drawn on, and the pictures the screen shows.
 *
 * The original file is only ever read: for the preview (a reduced copy, so dragging a slider stays responsive even on a
 * slow phone) and, when the user confirms, at full size by [PhotoEditExporter]. Nothing here writes to it.
 *
 * It is a plain object rather than a `ViewModel` because it lives exactly as long as the editor dialog is on screen
 * (the activity handles configuration changes itself), and it is given the coroutine scope of that dialog, so
 * leaving the editor cancels any drawing still in progress.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoEditorState(
    private val sourceUri: Uri,
    private val loader: PhotoEditLoader,
    private val exporter: PhotoEditExporter,
    private val scope: CoroutineScope,
) {
    /** True until the photo has been decoded; false afterwards even if decoding failed (see [loadFailed]). */
    var loading by mutableStateOf(true)
        private set

    /** The photo could not be read as a picture, so there is nothing to edit and the original is used as it is. */
    var loadFailed by mutableStateOf(false)
        private set

    var recipe by mutableStateOf(EditRecipe.NONE)
        private set

    var tool by mutableStateOf(EditTool.LIGHT)
        private set

    /** What the crop is locked to while the crop tool is open. */
    var cropShape by mutableStateOf(CropShape.FREE)
        private set

    var preview by mutableStateOf<Bitmap?>(null)
        private set

    /** The photo without the edit, drawn once, shown while the preview is held down. */
    var original by mutableStateOf<Bitmap?>(null)
        private set

    var filterThumbnails by mutableStateOf<Map<EditFilter, Bitmap>>(emptyMap())
        private set

    /** True while the full-size picture is being drawn and written. */
    var saving by mutableStateOf(false)
        private set

    /** The last attempt to prepare the edited picture failed; cleared by the next attempt. */
    var saveFailed by mutableStateOf(false)
        private set

    val changed: Boolean get() = !recipe.isIdentity

    private var source: RgbImage? = null
    private val shown = MutableStateFlow(EditRecipe.NONE to EditTool.LIGHT)

    init {
        scope.launch { load() }
        scope.launch {
            // A StateFlow already skips equal values, so a slider that settles where it was draws nothing.
            shown
                .mapLatest { (recipe, tool) -> draw(shownRecipe(recipe, tool)) }
                .collect { drawn -> if (drawn != null) preview = drawn }
        }
    }

    // --- Loading -------------------------------------------------------------------------------------

    private suspend fun load() {
        val decoded = withContext(Dispatchers.Default) {
            runCatching {
                val bitmap = loader.decode(sourceUri, PREVIEW_EDGE)
                bitmap.toRgbImage().also { bitmap.recycle() }
            }.onFailure { Napier.w(throwable = it) { "Could not decode the photo for editing." } }.getOrNull()
        }
        if (decoded == null) {
            loadFailed = true
            loading = false
            return
        }
        source = decoded
        val (thumbnails, plain) = withContext(Dispatchers.Default) { thumbnailsOf(decoded) to decoded.toBitmap() }
        filterThumbnails = thumbnails
        original = plain
        loading = false
        shown.value = recipe to tool
    }

    /**
     * While the crop tool is open the whole (turned, straightened) picture is shown so the crop can be drawn over it,
     * and dragging the crop does not redraw anything.
     */
    private fun shownRecipe(recipe: EditRecipe, tool: EditTool): EditRecipe =
        if (tool == EditTool.CROP) recipe.copy(geometry = recipe.geometry.copy(crop = Crop.FULL)) else recipe

    private suspend fun draw(recipe: EditRecipe): Bitmap? {
        val picture = source ?: return null
        return withContext(Dispatchers.Default) {
            EditRenderer.render(ImagePixelSource(picture), recipe, PREVIEW_EDGE).toBitmap()
        }
    }

    private fun thumbnailsOf(picture: RgbImage): Map<EditFilter, Bitmap> {
        val small = EditRenderer.render(ImagePixelSource(picture), EditRecipe.NONE, THUMBNAIL_EDGE)
        return EditFilter.entries.associateWith { filter ->
            EditRenderer.render(ImagePixelSource(small), EditRecipe(filter = filter), THUMBNAIL_EDGE).toBitmap()
        }
    }

    // --- Editing -------------------------------------------------------------------------------------

    fun selectTool(next: EditTool) {
        tool = next
        shown.value = recipe to tool
    }

    private fun edit(change: (EditRecipe) -> EditRecipe) {
        recipe = change(recipe).clamped()
        shown.value = recipe to tool
        saveFailed = false
    }

    fun adjust(change: (Adjustments) -> Adjustments) = edit { it.copy(adjustments = change(it.adjustments)) }

    fun setFilter(filter: EditFilter) =
        edit { it.copy(filter = filter, filterAmount = if (filter == it.filter) it.filterAmount else 1f) }

    fun setFilterAmount(amount: Float) = edit { it.copy(filterAmount = amount) }

    fun geometry(change: (Geometry) -> Geometry) = edit { it.copy(geometry = change(it.geometry)) }

    fun rotate() = geometry { it.copy(quarterTurns = it.quarterTurns + 1, crop = Crop.FULL) }

    fun flip() = geometry { it.copy(flipHorizontal = !it.flipHorizontal) }

    /** Locks the crop to [shape], fitting the largest crop of that shape into the picture. */
    fun lockCropShape(shape: CropShape) {
        cropShape = shape
        val picture = preview ?: return
        val aspect = picture.width.toFloat() / picture.height
        val ratio = CropTool.ratioOf(shape, aspect) ?: return
        geometry { it.copy(crop = CropTool.fit(it.crop, ratio, aspect)) }
    }

    /** Sets the adjustments to what "Auto enhance" proposes for this photo; they are ordinary sliders afterwards. */
    fun autoEnhance() {
        val current = source ?: return
        scope.launch {
            val proposal = withContext(Dispatchers.Default) { AutoEnhance.suggest(current) }
            edit { it.copy(adjustments = proposal) }
        }
    }

    /** Back to the original picture: every adjustment, the filter and the geometry are cleared. */
    fun revert() = edit { EditRecipe.NONE }

    // --- Leaving -------------------------------------------------------------------------------------

    /**
     * Draws the edit at full size and hands the resulting file to [onDone]; with nothing edited, or when the photo
     * could not be read as a picture at all, [onDone] gets null, meaning "use the original as it is" (no re-encoding,
     * no loss). On failure [saveFailed] is set and [onDone] is not called, so the user can retry or cancel.
     */
    fun finish(onDone: (Uri?) -> Unit) {
        if (saving) return
        if (loadFailed || recipe.isIdentity) {
            onDone(null)
            return
        }
        scope.launch {
            saving = true
            saveFailed = false
            val edited = runCatching { exporter.export(sourceUri, recipe) }
                .onFailure { Napier.w(throwable = it) { "Could not render the edited photo." } }
                .getOrNull()
            saving = false
            if (edited != null) onDone(edited) else saveFailed = true
        }
    }

    private companion object {
        const val PREVIEW_EDGE = 1400
        const val THUMBNAIL_EDGE = 140
    }
}
