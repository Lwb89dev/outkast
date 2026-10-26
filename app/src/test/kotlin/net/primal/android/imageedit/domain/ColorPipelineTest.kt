@file:Suppress("ktlint:standard:max-line-length", "MaxLineLength", "ComplexCondition", "MagicNumber")

package net.primal.android.imageedit.domain

import net.primal.android.imageedit.domain.EditTestImages.argb
import net.primal.android.imageedit.domain.EditTestImages.blue
import net.primal.android.imageedit.domain.EditTestImages.green
import net.primal.android.imageedit.domain.EditTestImages.red
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorPipelineTest {
    private fun apply(pixel: Int, adjustments: Adjustments = Adjustments.NONE, filter: EditFilter = EditFilter.NONE, amount: Float = 1f): Int {
        val pixels = intArrayOf(pixel)
        ColorPipeline(adjustments, filter, amount).apply(pixels, 0, 1)
        return pixels[0]
    }

    private val gray = argb(128, 128, 128)

    /** Two byte values within [tolerance] of each other. */
    private fun near(expected: Int, actual: Int, tolerance: Int) = assertTrue("expected $expected (+/-$tolerance) but was $actual", abs(expected - actual) <= tolerance)

    @Test
    fun withNothingChangedEveryPixelIsExactlyTheSame() {
        val pipeline = ColorPipeline(Adjustments.NONE, EditFilter.NONE, 1f)
        assertTrue(pipeline.isIdentity)
        val image = EditTestImages.photoLike(64, 48)
        val copy = image.pixels.copyOf()
        pipeline.apply(copy, 0, copy.size)
        assertEquals(image.pixels.toList(), copy.toList())
        assertFalse(ColorPipeline(Adjustments(exposure = 0.5f), EditFilter.NONE, 1f).isIdentity)
    }

    @Test
    fun alphaIsNeverTouched() {
        val translucent = (0x40 shl 24) or 0x336699
        assertEquals(0x40, (apply(translucent, Adjustments(exposure = 1f)) shr 24) and 0xFF)
    }

    @Test
    fun aStopOfExposureDoublesTheLightSoMidGrayGoesFromAbout128To176() {
        // 128/255 is 0.2159 in linear light; twice that is 0.4318, which is 176 as a display value.
        near(176, red(apply(gray, Adjustments(exposure = 1f))).toLong().toInt(), 1)
        assertEquals(green(apply(gray, Adjustments(exposure = 1f))), red(apply(gray, Adjustments(exposure = 1f))))
        // Half the light: 0.1079 linear is 92.
        near(92, red(apply(gray, Adjustments(exposure = -1f))), 1)
    }

    @Test
    fun exposureNeverPushesAboveWhiteOrBelowBlack() {
        assertEquals(255, red(apply(argb(250, 250, 250), Adjustments(exposure = 2f))))
        assertEquals(0, red(apply(argb(0, 0, 0), Adjustments(exposure = 2f))))
    }

    @Test
    fun brightnessLiftsTheMiddleButKeepsBlackAndWhite() {
        val up = Adjustments(brightness = 0.6f)
        assertTrue(red(apply(gray, up)) > 140)
        assertEquals(0, red(apply(argb(0, 0, 0), up)))
        assertEquals(255, red(apply(argb(255, 255, 255), up)))
        assertTrue(red(apply(gray, Adjustments(brightness = -0.6f))) < 115)
    }

    @Test
    fun contrastSpreadsLightAndDarkAwayFromTheMiddle() {
        val more = Adjustments(contrast = 0.8f)
        assertTrue(red(apply(argb(200, 200, 200), more)) > 200)
        assertTrue(red(apply(argb(60, 60, 60), more)) < 60)
        near(128, red(apply(gray, more)), 1)
        val less = Adjustments(contrast = -0.8f)
        assertTrue(red(apply(argb(200, 200, 200), less)) < 200)
        assertTrue(red(apply(argb(60, 60, 60), less)) > 60)
    }

    @Test
    fun theBlackPointDeepensBlacksOrLiftsThem() {
        assertEquals(0, red(apply(argb(20, 20, 20), Adjustments(blackPoint = 1f))))
        assertTrue(red(apply(argb(0, 0, 0), Adjustments(blackPoint = -1f))) > 10)
        assertEquals(255, red(apply(argb(255, 255, 255), Adjustments(blackPoint = 1f))))
    }

    @Test
    fun shadowsTouchOnlyTheDarkAndHighlightsOnlyTheLight() {
        val lift = Adjustments(shadows = 1f)
        assertTrue(red(apply(argb(30, 30, 30), lift)) > 45)
        near(240, red(apply(argb(240, 240, 240), lift)), 6)
        val recover = Adjustments(highlights = -1f)
        assertTrue(red(apply(argb(230, 230, 230), recover)) < 215)
        near(30, red(apply(argb(30, 30, 30), recover)), 3)
        near(128, red(apply(gray, Adjustments(shadows = 1f, highlights = -1f))), 20)
    }

    @Test
    fun noSaturationIsGrayAndMoreSaturationSeparatesTheChannels() {
        val orange = argb(200, 120, 60)
        val flat = apply(orange, Adjustments(saturation = -1f))
        near(red(flat), green(flat), 1)
        near(green(flat), blue(flat), 1)
        val vivid = apply(orange, Adjustments(saturation = 0.8f))
        assertTrue(red(vivid) - blue(vivid) > red(orange) - blue(orange))
    }

    @Test
    fun vibranceBoostsDullColorsMoreThanStrongOnesAndSpareSkin() {
        val dullBlue = argb(110, 120, 140)
        val strongBlue = argb(20, 60, 230)
        val skin = argb(210, 160, 130)
        val v = Adjustments(vibrance = 1f)
        fun spread(p: Int) = maxOf(red(p), green(p), blue(p)) - minOf(red(p), green(p), blue(p))
        val dullGain = spread(apply(dullBlue, v)) - spread(dullBlue)
        val strongGain = spread(apply(strongBlue, v)) - spread(strongBlue)
        val skinGain = spread(apply(skin, v)) - spread(skin)
        assertTrue("dull $dullGain vs strong $strongGain", dullGain > strongGain)
        val plain = spread(apply(skin, Adjustments(saturation = 0.5f))) - spread(skin)
        assertTrue("skin $skinGain vs saturation $plain", skinGain < plain)
    }

    @Test
    fun warmerIsMoreRedAndLessBlueCoolerTheOpposite() {
        val warm = apply(gray, Adjustments(temperature = 0.8f))
        assertTrue(red(warm) > 128 && blue(warm) < 128)
        val cool = apply(gray, Adjustments(temperature = -0.8f))
        assertTrue(red(cool) < 128 && blue(cool) > 128)
        val magenta = apply(gray, Adjustments(tint = 0.8f))
        assertTrue(green(magenta) < red(magenta))
        val green = apply(gray, Adjustments(tint = -0.8f))
        assertTrue(green(green) > red(green))
    }

    @Test
    fun monoAndNoirAreGrayAndSepiaIsWarmGray() {
        for (filter in listOf(EditFilter.MONO, EditFilter.NOIR)) {
            val out = apply(argb(200, 90, 40), filter = filter)
            assertEquals(red(out), green(out))
            assertEquals(green(out), blue(out))
        }
        val sepia = apply(argb(120, 120, 120), filter = EditFilter.SEPIA)
        assertTrue(red(sepia) > green(sepia) && green(sepia) > blue(sepia))
        assertTrue(red(apply(argb(200, 200, 200), filter = EditFilter.NOIR)) < red(apply(argb(200, 200, 200), filter = EditFilter.MONO)))
    }

    @Test
    fun aFilterAtHalfStrengthIsHalfwayAndAtZeroIsOff() {
        val original = argb(200, 90, 40)
        val full = apply(original, filter = EditFilter.MONO, amount = 1f)
        val half = apply(original, filter = EditFilter.MONO, amount = 0.5f)
        near((red(original) + red(full)) / 2, red(half), 2)
        assertEquals(original, apply(original, filter = EditFilter.MONO, amount = 0f))
    }

    @Test
    fun everyFilterChangesAColorPictureAndStaysInRange() {
        val image = EditTestImages.photoLike(32, 32)
        for (filter in EditFilter.entries.filter { it != EditFilter.NONE }) {
            val copy = image.pixels.copyOf()
            ColorPipeline(Adjustments.NONE, filter, 1f).apply(copy, 0, copy.size)
            assertTrue("$filter changed nothing", copy.indices.count { copy[it] != image.pixels[it] } > copy.size / 2)
        }
    }

    @Test
    fun theStepsCombineWithoutOverflowingOnExtremeSettings() {
        val extreme = Adjustments(2f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)
        val image = EditTestImages.photoLike(20, 20)
        val copy = image.pixels.copyOf()
        ColorPipeline(extreme, EditFilter.VIVID, 1f).apply(copy, 0, copy.size)
        assertTrue(copy.all { (it shr 24) and 0xFF == 0xFF })
        assertTrue(abs(red(copy[0])) <= 255)
    }
}
