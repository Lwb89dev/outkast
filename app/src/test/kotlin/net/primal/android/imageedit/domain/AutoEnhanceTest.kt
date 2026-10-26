@file:Suppress("ktlint:standard:max-line-length", "MaxLineLength", "ComplexCondition", "MagicNumber")

package net.primal.android.imageedit.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoEnhanceTest {
    private fun lightness(width: Int = 100, height: Int = 100, value: (Int) -> Int) =
        RgbImage(width, height, IntArray(width * height) { i -> value(i).let { EditTestImages.argb(it, it, it) } })

    private val spread = lightness { i -> (i % 100) * 255 / 99 } // every level, evenly: a well-exposed picture

    @Test
    fun aWellExposedPictureGetsAlmostNothing() {
        val a = AutoEnhance.suggest(spread)
        assertEquals(0f, a.exposure, 0.35f)
        assertEquals(0f, a.contrast, 0.05f)
        assertEquals(0f, a.shadows, 0.1f)
        assertEquals(0f, a.highlights, 0.1f)
    }

    @Test
    fun aDarkPictureIsBrightenedAndItsShadowsLifted() {
        val dark = lightness { i -> (i % 100) * 20 / 99 } // most of it crushed into the darkest levels
        val a = AutoEnhance.suggest(dark)
        assertTrue("exposure ${a.exposure}", a.exposure > 0.4f)
        assertTrue("shadows ${a.shadows}", a.shadows > 0.1f)
    }

    @Test
    fun aBrightPictureIsDarkenedAndItsHighlightsCalmed() {
        val bright = lightness { i -> 200 + (i % 100) * 55 / 99 }
        val a = AutoEnhance.suggest(bright)
        assertTrue("exposure ${a.exposure}", a.exposure < -0.4f)
        assertTrue("highlights ${a.highlights}", a.highlights < -0.05f)
    }

    @Test
    fun aFlatGrayPictureGetsContrastAndDeeperBlacks() {
        val flat = lightness { i -> 110 + (i % 100) * 30 / 99 }
        val a = AutoEnhance.suggest(flat)
        assertTrue("contrast ${a.contrast}", a.contrast > 0.15f)
        assertTrue("black point ${a.blackPoint}", a.blackPoint > 0.15f)
    }

    @Test
    fun dullColorsGetMoreVibranceThanColorfulOnes() {
        val dull = RgbImage(50, 50, IntArray(2500) { EditTestImages.argb(120, 125, 130) })
        val colorful = RgbImage(50, 50, IntArray(2500) { EditTestImages.argb(220, 60, 30) })
        assertTrue(AutoEnhance.suggest(dull).vibrance > AutoEnhance.suggest(colorful).vibrance)
    }

    @Test
    fun theSuggestionIsAnOrdinaryInRangeSetOfAdjustments() {
        for (image in listOf(spread, EditTestImages.photoLike(80, 60), EditTestImages.solid(10, 10, EditTestImages.argb(0, 0, 0)), EditTestImages.solid(10, 10, EditTestImages.argb(255, 255, 255)))) {
            val a = AutoEnhance.suggest(image)
            assertEquals(a, a.clamped())
        }
    }

    @Test
    fun applyingItToADarkPictureBrightensIt() {
        val dark = lightness { i -> 20 + (i % 100) * 50 / 99 }
        val enhanced = EditRenderer.render(ImagePixelSource(dark), EditRecipe(AutoEnhance.suggest(dark)), 1000)
        assertTrue(ImageStats.of(enhanced).meanLuma > ImageStats.of(dark).meanLuma + 0.05f)
    }

    @Test
    fun statsDescribeTheLight() {
        val stats = ImageStats.of(spread)
        assertEquals(0.5f, stats.meanLuma, 0.03f)
        assertTrue(stats.darkEnd < 0.06f && stats.lightEnd > 0.94f)
        assertEquals(0f, stats.meanChroma, 1e-4f)
    }
}
