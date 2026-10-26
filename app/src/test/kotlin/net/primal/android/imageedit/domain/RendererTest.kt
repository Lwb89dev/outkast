@file:Suppress("ktlint:standard:max-line-length")

package net.primal.android.imageedit.domain

import net.primal.android.imageedit.domain.EditTestImages.red
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererTest {
    private val photo = EditTestImages.photoLike(120, 90)

    private fun render(recipe: EditRecipe, source: RgbImage = photo, maxEdge: Int = 10_000) = EditRenderer.render(ImagePixelSource(source), recipe, maxEdge)

    @Test
    fun anEmptyRecipeReturnsThePictureUnchanged() {
        assertEquals(photo.pixels.toList(), render(EditRecipe.NONE).pixels.toList())
    }

    @Test
    fun aPreviewIsScaledDownToTheRequestedEdge() {
        val out = render(EditRecipe.NONE, maxEdge = 60)
        assertEquals(60, out.width)
        assertEquals(45, out.height)
    }

    @Test
    fun theOriginalIsNeverModified() {
        val before = photo.pixels.copyOf()
        render(EditRecipe(Adjustments(exposure = 1f, sharpness = 1f, vignette = 1f), Geometry(quarterTurns = 1, straightenDegrees = 5f), EditFilter.NOIR))
        assertEquals(before.toList(), photo.pixels.toList())
    }

    private val complicated = EditRecipe(
        adjustments = Adjustments(exposure = 0.4f, contrast = 0.3f, shadows = 0.5f, highlights = -0.4f, saturation = 0.2f, vibrance = 0.4f, temperature = 0.2f, sharpness = 0.8f, vignette = 0.6f),
        geometry = Geometry(quarterTurns = 1, straightenDegrees = 6f, perspectiveVertical = 0.25f, crop = Crop(0.05f, 0.1f, 0.95f, 0.85f)),
        filter = EditFilter.CHROME,
        filterAmount = 0.7f,
    )

    @Test
    fun drawingInBandsGivesExactlyTheSamePictureAsDrawingAtOnce() {
        val whole = render(complicated)
        for (bandRows in listOf(1, 7, 16, 50)) {
            val pixels = IntArray(whole.pixels.size)
            EditRenderer.renderBands(ImagePixelSource(photo), complicated, whole.width, whole.height, bandRows) { top, rows, band ->
                System.arraycopy(band, 0, pixels, top * whole.width, rows * whole.width)
            }
            assertEquals("bands of $bandRows rows", whole.pixels.toList(), pixels.toList())
        }
    }

    @Test
    fun bandsAreDeliveredInOrderAndCoverEveryRowOnce() {
        val whole = render(complicated)
        var next = 0
        EditRenderer.renderBands(ImagePixelSource(photo), complicated, whole.width, whole.height, 11) { top, rows, band ->
            assertEquals(next, top)
            assertEquals(rows * whole.width, band.size)
            next += rows
        }
        assertEquals(whole.height, next)
    }

    /** Counts how many pixels of the original are read, to check that a crop does not read (or hold) the whole picture. */
    private class CountingSource(private val inner: PixelSource) : PixelSource {
        var pixelsRead = 0L
        var largestRead = 0
        override val width get() = inner.width
        override val height get() = inner.height
        override fun read(x: Int, y: Int, w: Int, h: Int, out: IntArray) {
            pixelsRead += w.toLong() * h
            largestRead = maxOf(largestRead, w * h)
            inner.read(x, y, w, h, out)
        }
    }

    @Test
    fun aBandReadsOnlyTheBoxItNeedsSoAHugePhotoIsNeverHeldWhole() {
        val big = EditTestImages.photoLike(800, 600)
        val counting = CountingSource(ImagePixelSource(big))
        val recipe = EditRecipe(geometry = Geometry(crop = Crop(0.25f, 0.25f, 0.75f, 0.75f)))
        val (w, h) = GeometryMap(800, 600, recipe.geometry).sizeWithin(10_000)

        EditRenderer.renderBands(counting, recipe, w, h, bandRows = 30) { _, _, _ -> }

        assertTrue("read ${counting.pixelsRead} pixels for a quarter of the picture", counting.pixelsRead < 800L * 600 / 2)
        assertTrue("one read of ${counting.largestRead} pixels", counting.largestRead < 800 * 600 / 4)
    }

    @Test
    fun theSameRecipeAlwaysGivesTheSamePicture() {
        assertEquals(render(complicated).pixels.toList(), render(complicated).pixels.toList())
    }

    @Test
    fun aVignetteDarkensCornersMoreThanTheCentre() {
        val flat = EditTestImages.solid(100, 80, argb(200))
        val out = render(EditRecipe(Adjustments(vignette = 1f)), flat)
        val centre = red(out.pixels[40 * 100 + 50])
        val corner = red(out.pixels[0])
        assertTrue("centre $centre", kotlin.math.abs(200 - centre) <= 3)
        assertTrue("corner $corner vs centre $centre", corner < centre - 40)
        val lighter = render(EditRecipe(Adjustments(vignette = -1f)), flat)
        assertTrue(red(lighter.pixels[0]) > 205)
    }

    @Test
    fun sharpeningMakesAnEdgeCrisperAndLeavesAFlatAreaAlone() {
        val edge = RgbImage(60, 20, IntArray(60 * 20) { i -> if (i % 60 < 30) argb(90) else argb(160) })
        val out = render(EditRecipe(Adjustments(sharpness = 1f)), edge)
        val row = 10
        assertTrue("dark side gets darker next to the edge", red(out.pixels[row * 60 + 28]) < 90)
        assertTrue("light side gets lighter next to the edge", red(out.pixels[row * 60 + 31]) > 160)
        assertEquals(90, red(out.pixels[row * 60 + 5]))
        assertEquals(160, red(out.pixels[row * 60 + 55]))
    }

    @Test
    fun alphaOfTheResultIsOpaque() {
        assertTrue(render(complicated).pixels.all { (it shr 24) and 0xFF == 0xFF })
    }

    @Test
    fun theSpeedIsWorthKnowing() {
        val big = EditTestImages.photoLike(1600, 1200) // about 2 megapixels
        val start = System.nanoTime()
        val out = render(complicated, big, maxEdge = 10_000)
        val millis = (System.nanoTime() - start) / 1_000_000
        System.err.println("EDIT-SPEED ${big.width}x${big.height} -> ${out.width}x${out.height} in $millis ms")
        assertTrue("took $millis ms", millis < 30_000)
    }

    private fun argb(v: Int) = EditTestImages.argb(v, v, v)
}
