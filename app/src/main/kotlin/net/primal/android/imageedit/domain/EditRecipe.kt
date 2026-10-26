package net.primal.android.imageedit.domain

/**
 * The tone and color adjustments of a photo edit. Every value is a plain number with a neutral default of 0, so a
 * recipe says what was changed and by how much, never a picture: the original file is never touched. Ranges are
 * noted per field; [clamped] forces a value into its range.
 *
 * The photo editor is a port of the one in the eikon gallery (MIT), kept as pure Kotlin on ints and floats so that
 * what a recipe means can be tested on the JVM and is identical on every phone.
 */
data class Adjustments(
    /** Exposure in stops (EV), -2 to 2: each stop doubles or halves the light. */
    val exposure: Float = 0f,
    /** Lifts or lowers the middle tones without moving pure black or white, -1 to 1. */
    val brightness: Float = 0f,
    /** Separates light from dark, -1 to 1. */
    val contrast: Float = 0f,
    /** Darkens (negative) or brightens (positive) only the lightest areas, -1 to 1. */
    val highlights: Float = 0f,
    /** Brightens (positive) or darkens (negative) only the darkest areas, -1 to 1. */
    val shadows: Float = 0f,
    /** Moves the level that counts as black: positive makes blacks deeper, -1 to 1. */
    val blackPoint: Float = 0f,
    /** Overall color strength, -1 (gray) to 1 (twice as colorful). */
    val saturation: Float = 0f,
    /** Color strength for the dull colors first, sparing already strong ones and skin, -1 to 1. */
    val vibrance: Float = 0f,
    /** Cooler (negative, bluer) to warmer (positive, more orange), -1 to 1. */
    val temperature: Float = 0f,
    /** Greener (negative) to more magenta (positive), -1 to 1. */
    val tint: Float = 0f,
    /** Edge crispness, 0 to 1. */
    val sharpness: Float = 0f,
    /** Darkens (positive) or lightens (negative) the corners, -1 to 1. */
    val vignette: Float = 0f,
) {
    val isNeutral: Boolean get() = this == NONE

    fun clamped() =
        Adjustments(
            exposure = exposure.coerceIn(-EXPOSURE_RANGE, EXPOSURE_RANGE),
            brightness = brightness.unit(),
            contrast = contrast.unit(),
            highlights = highlights.unit(),
            shadows = shadows.unit(),
            blackPoint = blackPoint.unit(),
            saturation = saturation.unit(),
            vibrance = vibrance.unit(),
            temperature = temperature.unit(),
            tint = tint.unit(),
            sharpness = sharpness.coerceIn(0f, 1f),
            vignette = vignette.unit(),
        )

    private fun Float.unit() = coerceIn(-1f, 1f)

    companion object {
        val NONE = Adjustments()
        const val EXPOSURE_RANGE = 2f
    }
}

/**
 * The part of the picture kept, as fractions of the (rotated and straightened) picture: 0,0 is the top left, 1,1 the
 * bottom right.
 */
data class Crop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val isFull: Boolean get() = this == FULL

    /** Inside 0..1 and at least [MIN_SIZE] wide and tall. */
    fun clamped(): Crop {
        val l = left.coerceIn(0f, 1f - MIN_SIZE)
        val t = top.coerceIn(0f, 1f - MIN_SIZE)
        return Crop(l, t, right.coerceIn(l + MIN_SIZE, 1f), bottom.coerceIn(t + MIN_SIZE, 1f))
    }

    companion object {
        val FULL = Crop()
        const val MIN_SIZE = 0.05f
    }
}

/**
 * How the picture is turned and cut, applied in this order: flip, quarter turns, then straighten and perspective (with
 * the picture enlarged just enough that no empty corner shows), then the crop. All of it is parameters; the pixels
 * are only resampled once, when a picture is drawn.
 */
data class Geometry(
    /** Clockwise quarter turns, 0 to 3. */
    val quarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    /** Tilt to level a horizon, in degrees, -45 to 45. Positive turns the picture clockwise. */
    val straightenDegrees: Float = 0f,
    /** Corrects converging verticals (a building shot from below): -1 to 1. */
    val perspectiveVertical: Float = 0f,
    /** Corrects converging horizontals (a shot from the side): -1 to 1. */
    val perspectiveHorizontal: Float = 0f,
    val crop: Crop = Crop.FULL,
) {
    val isNeutral: Boolean get() = this == NONE

    fun clamped() =
        Geometry(
            quarterTurns = Math.floorMod(quarterTurns, QUARTERS),
            flipHorizontal = flipHorizontal,
            flipVertical = flipVertical,
            straightenDegrees = straightenDegrees.coerceIn(-MAX_STRAIGHTEN, MAX_STRAIGHTEN),
            perspectiveVertical = perspectiveVertical.coerceIn(-1f, 1f),
            perspectiveHorizontal = perspectiveHorizontal.coerceIn(-1f, 1f),
            crop = crop.clamped(),
        )

    companion object {
        val NONE = Geometry()
        const val QUARTERS = 4
        const val MAX_STRAIGHTEN = 45f
    }
}

/**
 * Ready-made looks. Each is a small, fixed set of color changes; [EditRecipe.filterAmount] says how strongly it is
 * applied.
 */
enum class EditFilter { NONE, VIVID, WARM, COOL, MONO, NOIR, FADE, CHROME, SEPIA }

/**
 * Everything done to one photo, without changing the file. It is a value: two recipes that say the same thing are
 * equal.
 */
data class EditRecipe(
    val adjustments: Adjustments = Adjustments.NONE,
    val geometry: Geometry = Geometry.NONE,
    val filter: EditFilter = EditFilter.NONE,
    /** 0 to 1. */
    val filterAmount: Float = 1f,
) {
    /** True when drawing the recipe would change nothing. */
    val isIdentity: Boolean
        get() = adjustments.isNeutral && geometry.isNeutral && (filter == EditFilter.NONE || filterAmount <= 0f)

    fun clamped() =
        EditRecipe(
            adjustments = adjustments.clamped(),
            geometry = geometry.clamped(),
            filter = filter,
            filterAmount = filterAmount.coerceIn(0f, 1f),
        )

    companion object {
        val NONE = EditRecipe()
    }
}
