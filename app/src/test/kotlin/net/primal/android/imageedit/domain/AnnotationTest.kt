@file:Suppress("ktlint:standard:max-line-length", "MaxLineLength", "MagicNumber")

package net.primal.android.imageedit.domain

import net.primal.android.imageedit.domain.EditTestImages.blue
import net.primal.android.imageedit.domain.EditTestImages.green
import net.primal.android.imageedit.domain.EditTestImages.red
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationTest {
    private val red = EditTestImages.argb(255, 0, 0)
    private val blue = EditTestImages.argb(0, 0, 255)
    private val white = EditTestImages.argb(255, 255, 255)

    private fun p(x: Float, y: Float) = AnnotationPoint(x, y)

    private fun shape(kind: AnnotationKind, a: AnnotationPoint, b: AnnotationPoint, color: Int = red, width: Float = 0.05f, filled: Boolean = false) =
        Annotation(kind, color, width, listOf(a, b), filled)

    /** [annotations] drawn over a white 100 x 100 picture. */
    private fun drawn(vararg annotations: Annotation, crop: Crop = Crop.FULL, size: Int = 100): IntArray {
        val pixels = IntArray(size * size) { white }
        AnnotationRaster.draw(annotations.toList(), crop, pixels, size, size, 0, size)
        return pixels
    }

    private fun IntArray.at(x: Int, y: Int, size: Int = 100) = this[y * size + x]

    private fun IntArray.isUntouched(x: Int, y: Int) = at(x, y) == white

    // --- What a shape draws ---------------------------------------------------------------------------

    @Test
    fun aLineIsDrawnInItsColorAlongItsLengthAndNowhereElse() {
        val out = drawn(shape(AnnotationKind.LINE, p(0.1f, 0.5f), p(0.9f, 0.5f)))
        assertEquals(red, out.at(50, 49))
        assertEquals(red, out.at(20, 50))
        assertTrue(out.isUntouched(50, 20))
        assertTrue(out.isUntouched(50, 80))
    }

    @Test
    fun theLineThicknessIsAFractionOfTheLongerSide() {
        // 0.1 of 100 pixels is ten wide: rows 45..54 are solid, and the rows 6 pixels from the centre are not touched.
        val out = drawn(shape(AnnotationKind.LINE, p(0.1f, 0.5f), p(0.9f, 0.5f), width = 0.1f))
        for (y in 45..54) assertEquals("row $y", red, out.at(50, y))
        assertTrue(out.isUntouched(50, 43))
        assertTrue(out.isUntouched(50, 57))
    }

    @Test
    fun theEdgeOfALineIsAntiAliased() {
        val out = drawn(shape(AnnotationKind.LINE, p(0.1f, 0.5f), p(0.9f, 0.5f), width = 0.0525f))
        // Thick 5.25: the row beyond the solid rows is partly covered, so it is neither white nor fully red.
        val edge = out.at(50, 52)
        assertTrue("green ${green(edge)}", green(edge) in 1..254)
        assertEquals(255, red(edge))
    }

    @Test
    fun aRectangleOutlineLeavesItsInsideUntouchedAndAFilledOneDoesNot() {
        val outline = drawn(shape(AnnotationKind.RECTANGLE, p(0.2f, 0.2f), p(0.8f, 0.8f), width = 0.03f))
        assertEquals(red, outline.at(20, 50))
        assertEquals(red, outline.at(50, 80))
        assertTrue(outline.isUntouched(50, 50))
        assertTrue(outline.isUntouched(5, 5))

        val filled = drawn(shape(AnnotationKind.RECTANGLE, p(0.2f, 0.2f), p(0.8f, 0.8f), width = 0.03f, filled = true))
        assertEquals(red, filled.at(50, 50))
        assertTrue(filled.isUntouched(5, 5))
    }

    @Test
    fun aRectangleDraggedBackwardsIsTheSameRectangle() {
        val forward = drawn(shape(AnnotationKind.RECTANGLE, p(0.2f, 0.3f), p(0.8f, 0.7f)))
        val backward = drawn(shape(AnnotationKind.RECTANGLE, p(0.8f, 0.7f), p(0.2f, 0.3f)))
        assertEquals(forward.toList(), backward.toList())
    }

    @Test
    fun aCircleOutlineIsDrawnOnItsCircumference() {
        val out = drawn(shape(AnnotationKind.ELLIPSE, p(0.2f, 0.2f), p(0.8f, 0.8f), width = 0.03f))
        assertEquals(red, out.at(20, 50))
        assertEquals(red, out.at(79, 50))
        assertEquals(red, out.at(50, 20))
        assertTrue(out.isUntouched(50, 50))
        // The corner of its box is outside the circle.
        assertTrue(out.isUntouched(22, 22))
    }

    @Test
    fun aFilledCircleCoversItsMiddleButNotTheCornersOfItsBox() {
        val out = drawn(shape(AnnotationKind.ELLIPSE, p(0.2f, 0.2f), p(0.8f, 0.8f), width = 0.02f, filled = true))
        assertEquals(red, out.at(50, 50))
        assertEquals(red, out.at(35, 50))
        assertTrue(out.isUntouched(22, 22))
    }

    @Test
    fun anEllipseFollowsItsBoxNotACircle() {
        val out = drawn(shape(AnnotationKind.ELLIPSE, p(0.1f, 0.35f), p(0.9f, 0.65f), width = 0.02f))
        assertEquals(red, out.at(10, 50))
        assertEquals(red, out.at(50, 35))
        assertTrue(out.isUntouched(50, 20))
        assertTrue(out.isUntouched(50, 50))
    }

    @Test
    fun anArrowHasAShaftAndAHeadAtItsEnd() {
        val out = drawn(shape(AnnotationKind.ARROW, p(0.1f, 0.5f), p(0.9f, 0.5f), width = 0.02f))
        assertEquals(red, out.at(40, 50))
        assertEquals(red, out.at(89, 50))
        // The head spreads back from the tip, above and below the shaft, and the start of the shaft has none.
        assertFalse("upper barb", out.isUntouched(83, 46))
        assertFalse("lower barb", out.isUntouched(83, 53))
        assertTrue(out.isUntouched(12, 40))
    }

    @Test
    fun aFreehandStrokeFollowsItsPoints() {
        val stroke = Annotation(AnnotationKind.PEN, red, 0.03f, listOf(p(0.1f, 0.1f), p(0.1f, 0.9f), p(0.9f, 0.9f)))
        val out = drawn(stroke)
        assertEquals(red, out.at(10, 50))
        assertEquals(red, out.at(50, 90))
        assertTrue(out.isUntouched(50, 50))
        assertTrue(out.isUntouched(50, 10))
    }

    @Test
    fun aSingleTapWithThePenLeavesADot() {
        val out = drawn(Annotation(AnnotationKind.PEN, red, 0.1f, listOf(p(0.5f, 0.5f))))
        assertEquals(red, out.at(50, 50))
        assertEquals(red, out.at(53, 50))
        assertTrue(out.isUntouched(60, 50))
    }

    // --- Layering -------------------------------------------------------------------------------------

    @Test
    fun laterAnnotationsAreDrawnOverEarlierOnes() {
        val under = shape(AnnotationKind.RECTANGLE, p(0.1f, 0.1f), p(0.9f, 0.9f), color = red, filled = true)
        val over = shape(AnnotationKind.ELLIPSE, p(0.3f, 0.3f), p(0.7f, 0.7f), color = blue, filled = true)
        val out = drawn(under, over)
        assertEquals(blue, out.at(50, 50))
        assertEquals(red, out.at(15, 15))
    }

    @Test
    fun aTranslucentStrokeDoesNotDarkenWhereItCrossesItself() {
        // Three points that double back over the same ground: the doubled part must show the same color as the rest.
        val half = EditTestImages.argb(255, 0, 0) and 0x00FFFFFF or (0x80 shl 24)
        val stroke = Annotation(AnnotationKind.PEN, half, 0.06f, listOf(p(0.1f, 0.5f), p(0.9f, 0.5f), p(0.1f, 0.5f), p(0.9f, 0.5f)))
        val out = drawn(stroke)
        assertEquals(out.at(30, 50), out.at(70, 50))
        assertTrue("half way between white and red, green ${green(out.at(50, 50))}", green(out.at(50, 50)) in 120..135)
    }

    @Test
    fun aTranslucentColorLetsThePictureShowThrough() {
        val half = (0x80 shl 24) or 0xFF0000
        val out = drawn(shape(AnnotationKind.RECTANGLE, p(0.2f, 0.2f), p(0.8f, 0.8f), color = half, filled = true))
        val mixed = out.at(50, 50)
        assertEquals(255, red(mixed))
        assertTrue(blue(mixed) in 120..135)
        assertEquals(255, (mixed ushr 24) and 0xFF)
    }

    // --- Space ----------------------------------------------------------------------------------------

    @Test
    fun theCropDecidesWhereAnAnnotationLandsOnTheFinishedPicture() {
        // The picture is cut to its right half, so a line along the middle of the whole picture now starts at the left edge.
        val line = shape(AnnotationKind.LINE, p(0.5f, 0.5f), p(0.75f, 0.5f), width = 0.04f)
        val out = drawn(line, crop = Crop(0.5f, 0f, 1f, 1f))
        assertEquals(red, out.at(2, 50))
        assertEquals(red, out.at(48, 50))
        assertTrue(out.isUntouched(70, 50))
    }

    @Test
    fun partOfAnAnnotationOutsideTheCropIsCutOff() {
        val line = shape(AnnotationKind.LINE, p(0.1f, 0.5f), p(0.9f, 0.5f), width = 0.04f)
        val out = drawn(line, crop = Crop(0.5f, 0f, 1f, 1f))
        assertEquals(red, out.at(5, 50))
        assertEquals(red, out.at(60, 50))
        assertTrue(out.isUntouched(95, 50))
    }

    @Test
    fun aPointUnderAPixelAndThePixelOfAPointAreInverse() {
        val space = AnnotationSpace(Crop(0.2f, 0.1f, 0.9f, 0.8f), 700f, 300f)
        val point = space.pointAt(140f, 120f)
        assertEquals(140f, space.pixelX(point), 0.01f)
        assertEquals(120f, space.pixelY(point), 0.01f)
        assertEquals(0.34f, point.x, 0.0001f)
        assertEquals(0.38f, point.y, 0.0001f)
    }

    // --- Following the picture ------------------------------------------------------------------------

    @Test
    fun aQuarterTurnMovesTheDrawingWithThePicture() {
        // The top left corner goes to the top right when the picture is turned clockwise.
        val turned = Annotation(AnnotationKind.PEN, red, 0.01f, listOf(p(0f, 0f), p(1f, 0f))).rotatedClockwise()
        assertEquals(listOf(p(1f, 0f), p(1f, 1f)), turned.points)
    }

    @Test
    fun fourQuarterTurnsAreNoTurnAtAll() {
        val start = Annotation(AnnotationKind.LINE, red, 0.01f, listOf(p(0.2f, 0.3f), p(0.6f, 0.9f)))
        var turned = start
        repeat(4) { turned = turned.rotatedClockwise() }
        turned.points.zip(start.points).forEach { (a, b) ->
            assertEquals(a.x, b.x, 0.0001f)
            assertEquals(a.y, b.y, 0.0001f)
        }
    }

    @Test
    fun mirroringIsItsOwnInverse() {
        val start = Annotation(AnnotationKind.LINE, red, 0.01f, listOf(p(0.2f, 0.3f), p(0.6f, 0.9f)))
        val across = start.mirrored(horizontal = true).points
        assertEquals(0.8f, across[0].x, 0.0001f)
        assertEquals(0.3f, across[0].y, 0.0001f)
        assertEquals(0.4f, across[1].x, 0.0001f)
        val back = start.mirrored(horizontal = false).mirrored(horizontal = false).points
        assertEquals(start.points.map { it.y }, back.map { it.y })
    }

    // --- In the recipe --------------------------------------------------------------------------------

    @Test
    fun aRecipeWithADrawingIsNotIdentity() {
        assertTrue(EditRecipe.NONE.isIdentity)
        val drawing = shape(AnnotationKind.LINE, p(0.1f, 0.1f), p(0.9f, 0.9f))
        assertFalse(EditRecipe(annotations = listOf(drawing)).isIdentity)
    }

    @Test
    fun anAnnotationWithNothingToDrawDoesNotCountAsAnEdit() {
        val incomplete = Annotation(AnnotationKind.RECTANGLE, red, 0.01f, listOf(p(0.1f, 0.1f)))
        assertFalse(incomplete.isDrawable)
        assertTrue(EditRecipe(annotations = listOf(incomplete)).isIdentity)
    }

    @Test
    fun theThicknessAndTheReachOfAnAnnotationAreKeptInRange() {
        val wild = Annotation(AnnotationKind.LINE, red, 9f, listOf(p(-7f, 0.5f), p(8f, 0.5f))).clamped()
        assertEquals(Annotation.MAX_WIDTH, wild.width, 0f)
        assertTrue(wild.points.all { it.x in -1f..2f })
        assertEquals(Annotation.MIN_WIDTH, Annotation(AnnotationKind.LINE, red, 0f, emptyList()).clamped().width, 0f)
    }

    // --- Through the renderer -------------------------------------------------------------------------

    private fun render(recipe: EditRecipe, source: RgbImage) = EditRenderer.render(ImagePixelSource(source), recipe, 10_000)

    @Test
    fun annotationsKeepTheirColorWhateverTheFilterIs() {
        val photo = EditTestImages.solid(100, 100, EditTestImages.argb(90, 120, 200))
        val line = shape(AnnotationKind.LINE, p(0.1f, 0.5f), p(0.9f, 0.5f), color = red, width = 0.1f)
        val out = render(EditRecipe(filter = EditFilter.MONO, annotations = listOf(line)), photo)
        assertEquals(red, out.pixels[50 * 100 + 50])
        // The photo itself is turned gray by the filter, so the filter was applied to it and not to the drawing.
        val background = out.pixels[10 * 100 + 10]
        assertEquals(red(background), green(background))
    }

    @Test
    fun drawingInBandsGivesTheSamePictureAsDrawingAtOnce() {
        val photo = EditTestImages.photoLike(120, 90)
        val annotations = listOf(
            Annotation(AnnotationKind.PEN, red, 0.03f, listOf(p(0.1f, 0.2f), p(0.4f, 0.8f), p(0.9f, 0.3f))),
            shape(AnnotationKind.ELLIPSE, p(0.2f, 0.1f), p(0.8f, 0.9f), color = blue, filled = true),
            shape(AnnotationKind.ARROW, p(0.05f, 0.95f), p(0.7f, 0.15f), color = white, width = 0.02f),
        )
        val recipe = EditRecipe(geometry = Geometry(crop = Crop(0.1f, 0.05f, 0.95f, 0.9f)), annotations = annotations)
        val whole = render(recipe, photo)
        for (bandRows in listOf(1, 7, 16, 50)) {
            val pixels = IntArray(whole.pixels.size)
            EditRenderer.renderBands(ImagePixelSource(photo), recipe, whole.width, whole.height, bandRows) { top, rows, band ->
                System.arraycopy(band, 0, pixels, top * whole.width, rows * whole.width)
            }
            assertEquals("bands of $bandRows rows", whole.pixels.toList(), pixels.toList())
        }
    }

    @Test
    fun aPreviewAndAFullSizePictureShowTheSameDrawing() {
        val photo = EditTestImages.solid(400, 400, EditTestImages.argb(30, 30, 30))
        val circle = shape(AnnotationKind.ELLIPSE, p(0.25f, 0.25f), p(0.75f, 0.75f), color = red, width = 0.04f)
        val recipe = EditRecipe(annotations = listOf(circle))
        val full = EditRenderer.render(ImagePixelSource(photo), recipe, 400)
        val small = EditRenderer.render(ImagePixelSource(photo), recipe, 100)
        // The same fractional places: on the circle's left edge, in its middle, and well outside it.
        assertEquals(red, full.pixels[200 * 400 + 100])
        assertEquals(red, small.pixels[50 * 100 + 25])
        assertEquals(full.pixels[200 * 400 + 200], small.pixels[50 * 100 + 50])
        assertEquals(full.pixels[20 * 400 + 20], small.pixels[5 * 100 + 5])
    }
}
