@file:Suppress("ktlint:standard:max-line-length")

package net.primal.android.imageedit.domain

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropToolTest {
    private val canvas = 1.5f // a 3:2 picture
    private val crop = Crop(0.2f, 0.2f, 0.8f, 0.8f)

    private fun width(c: Crop) = c.right - c.left
    private fun height(c: Crop) = c.bottom - c.top

    /** Shape of the crop in pixels: width over height. */
    private fun shape(c: Crop) = width(c) * canvas / height(c)

    private fun inside(c: Crop) = c.left >= 0f && c.top >= 0f && c.right <= 1f + 1e-6f && c.bottom <= 1f + 1e-6f && width(c) >= Crop.MIN_SIZE - 1e-6f && height(c) >= Crop.MIN_SIZE - 1e-6f

    @Test
    fun handlesAreFoundByTheirPlaceWithCornersFirst() {
        val slop = 0.04f
        assertEquals(CropHandle.TOP_LEFT, CropTool.handleAt(crop, 0.21f, 0.19f, slop))
        assertEquals(CropHandle.BOTTOM_RIGHT, CropTool.handleAt(crop, 0.8f, 0.8f, slop))
        assertEquals(CropHandle.TOP, CropTool.handleAt(crop, 0.5f, 0.2f, slop))
        assertEquals(CropHandle.LEFT, CropTool.handleAt(crop, 0.2f, 0.5f, slop))
        assertEquals(CropHandle.MOVE, CropTool.handleAt(crop, 0.5f, 0.5f, slop))
        assertNull(CropTool.handleAt(crop, 0.05f, 0.05f, slop))
    }

    @Test
    fun movingKeepsTheSizeAndStopsAtTheEdgeOfThePicture() {
        val moved = CropTool.drag(crop, CropHandle.MOVE, 0.5f, -0.5f, null, canvas)
        assertEquals(width(crop), width(moved), 1e-6f)
        assertEquals(height(crop), height(moved), 1e-6f)
        assertEquals(1f, moved.right, 1e-6f)
        assertEquals(0f, moved.top, 1e-6f)
    }

    @Test
    fun aFreeDragMovesOnlyTheGrabbedSides() {
        val edge = CropTool.drag(crop, CropHandle.RIGHT, -0.1f, 0.3f, null, canvas)
        assertEquals(crop.left, edge.left, 0f)
        assertEquals(crop.top, edge.top, 0f)
        assertEquals(crop.bottom, edge.bottom, 0f)
        assertEquals(0.7f, edge.right, 1e-6f)
        val corner = CropTool.drag(crop, CropHandle.TOP_LEFT, 0.1f, 0.05f, null, canvas)
        assertEquals(0.3f, corner.left, 1e-6f)
        assertEquals(0.25f, corner.top, 1e-6f)
    }

    @Test
    fun theCropNeverLeavesThePictureOrShrinksToNothing() {
        for (handle in CropHandle.entries) for ((dx, dy) in listOf(5f to 5f, -5f to -5f, 5f to -5f, -5f to 5f)) {
            for (ratio in listOf(null, 1f, 16f / 9f)) {
                val out = CropTool.drag(crop, handle, dx, dy, ratio, canvas)
                assertTrue("$handle $dx,$dy ratio $ratio -> $out", inside(out))
            }
        }
    }

    @Test
    fun aLockedShapeIsKeptWhateverIsDragged() {
        for (handle in CropHandle.entries.filter { it != CropHandle.MOVE }) {
            val out = CropTool.drag(Crop(0.1f, 0.1f, 0.7f, 0.5f), handle, 0.07f, -0.05f, 1f, canvas)
            assertEquals("$handle", 1f, shape(out), 0.02f)
        }
    }

    @Test
    fun draggingACornerOfALockedCropKeepsTheOppositeCorner() {
        val start = Crop(0.1f, 0.1f, 0.7f, 0.5f)
        val out = CropTool.drag(start, CropHandle.TOP_LEFT, 0.1f, 0.1f, 4f / 3f, canvas)
        assertEquals(start.right, out.right, 1e-5f)
        assertEquals(start.bottom, out.bottom, 1e-5f)
        assertEquals(4f / 3f, shape(out), 0.02f)
    }

    @Test
    fun choosingAShapeFitsTheLargestCropOfThatShapeAroundTheCentre() {
        val square = CropTool.fit(Crop.FULL, 1f, canvas)
        assertEquals(1f, shape(square), 0.01f)
        assertEquals(1f, height(square), 1e-5f) // as tall as the picture, narrower
        assertEquals(0.5f, (square.left + square.right) / 2, 1e-5f)
        val wide = CropTool.fit(Crop.FULL, 16f / 9f, canvas)
        assertEquals(16f / 9f, shape(wide), 0.02f)
        assertEquals(1f, width(wide), 1e-5f)
    }

    @Test
    fun theOriginalShapeIsThePicturesOwn() {
        assertEquals(canvas, CropTool.ratioOf(CropShape.ORIGINAL, canvas)!!, 0f)
        assertNull(CropTool.ratioOf(CropShape.FREE, canvas))
        assertEquals(1f, CropTool.ratioOf(CropShape.SQUARE, canvas)!!, 0f)
        assertTrue(abs(CropShape.SIXTEEN_NINE.ratio!! - 1.7778f) < 1e-3f)
    }
}
