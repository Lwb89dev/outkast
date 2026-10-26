@file:Suppress("ktlint:standard:max-line-length")

package net.primal.android.imageedit.domain

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryMapTest {
    private val image = EditTestImages.coordinates(6, 4)

    private fun draw(geometry: Geometry, source: RgbImage = image): RgbImage =
        EditRenderer.render(ImagePixelSource(source), EditRecipe(geometry = geometry), maxEdge = 10_000)

    private fun at(picture: RgbImage, x: Int, y: Int) = picture.pixels[y * picture.width + x]

    @Test
    fun withNoGeometryEveryPixelStaysWhereItWas() {
        val out = draw(Geometry.NONE)
        assertEquals(image.width, out.width)
        assertEquals(image.pixels.toList(), out.pixels.toList())
    }

    @Test
    fun aQuarterTurnPutsTheTopLeftCornerAtTheTopRight() {
        val out = draw(Geometry(quarterTurns = 1))
        assertEquals(4, out.width)
        assertEquals(6, out.height)
        assertEquals(at(image, 0, 0), at(out, 3, 0))
        assertEquals(at(image, 5, 0), at(out, 3, 5)) // top right goes to bottom right
        assertEquals(at(image, 0, 3), at(out, 0, 0)) // bottom left goes to top left
    }

    @Test
    fun threeQuarterTurnsTheOtherWayAndHalfATurnBothWays() {
        val three = draw(Geometry(quarterTurns = 3))
        assertEquals(at(image, 0, 0), at(three, 0, 5))
        assertEquals(at(image, 5, 3), at(three, 3, 0))
        val half = draw(Geometry(quarterTurns = 2))
        assertEquals(at(image, 0, 0), at(half, 5, 3))
        assertEquals(at(image, 5, 3), at(half, 0, 0))
    }

    @Test
    fun fourQuarterTurnsAreNoTurnAtAll() {
        assertEquals(image.pixels.toList(), draw(Geometry(quarterTurns = 4)).pixels.toList())
    }

    @Test
    fun flipsMirrorTheirAxisAndFlippingBothIsAHalfTurn() {
        val h = draw(Geometry(flipHorizontal = true))
        assertEquals(at(image, 0, 1), at(h, 5, 1))
        val v = draw(Geometry(flipVertical = true))
        assertEquals(at(image, 2, 0), at(v, 2, 3))
        assertEquals(draw(Geometry(quarterTurns = 2)).pixels.toList(), draw(Geometry(flipHorizontal = true, flipVertical = true)).pixels.toList())
    }

    @Test
    fun flippingComesBeforeTurning() {
        val out = draw(Geometry(quarterTurns = 1, flipHorizontal = true))
        // After the flip the top-left holds the original top-right, and a quarter turn then puts it at the top-right;
        // the original top-left, flipped to the top-right, ends at the bottom-right.
        assertEquals(at(image, 5, 0), at(out, 3, 0))
        assertEquals(at(image, 0, 0), at(out, 3, 5))
    }

    @Test
    fun aCropKeepsTheChosenRectangleOnly() {
        val out = draw(Geometry(crop = Crop(1f / 6, 0.25f, 5f / 6, 0.75f)))
        assertEquals(4, out.width)
        assertEquals(2, out.height)
        assertEquals(at(image, 1, 1), at(out, 0, 0))
        assertEquals(at(image, 4, 2), at(out, 3, 1))
    }

    @Test
    fun aCropAfterATurnIsMeasuredOnTheTurnedPicture() {
        val out = draw(Geometry(quarterTurns = 1, crop = Crop(0f, 0f, 1f, 0.5f)))
        assertEquals(4, out.width)
        assertEquals(3, out.height)
    }

    @Test
    fun theOutputCanBeSmallerThanTheOriginal() {
        val map = GeometryMap(4000, 3000, Geometry.NONE)
        assertEquals(1000 to 750, map.sizeWithin(1000))
        assertEquals(4000 to 3000, map.sizeWithin(9000))
    }

    // --- straighten and perspective ---------------------------------------------------------------

    private fun everyOutputPointIsInsideTheOriginal(map: GeometryMap, width: Int, height: Int, sourceWidth: Int, sourceHeight: Int): Boolean {
        val point = DoubleArray(2)
        for (y in 0 until height step 3) for (x in 0 until width step 3) {
            map.sourcePoint(x + 0.5, y + 0.5, width, height, point)
            if (point[0] < -1e-3 || point[0] > sourceWidth + 1e-3 || point[1] < -1e-3 || point[1] > sourceHeight + 1e-3) return false
        }
        return true
    }

    @Test
    fun straighteningEnlargesJustEnoughToLeaveNoEmptyCorner() {
        for (angle in listOf(-30f, -5f, 2f, 10f, 45f)) {
            val map = GeometryMap(400, 300, Geometry(straightenDegrees = angle))
            assertTrue("angle $angle zoom ${map.fillZoom}", map.fillZoom > 1.0)
            assertTrue("angle $angle leaves an empty corner", everyOutputPointIsInsideTheOriginal(map, 400, 300, 400, 300))
        }
        assertEquals(1.0, GeometryMap(400, 300, Geometry.NONE).fillZoom, 0.0)
    }

    @Test
    fun theFillZoomIsTheLeastOneSoACornerSitsExactlyOnAnEdge() {
        val map = GeometryMap(400, 300, Geometry(straightenDegrees = 10f))
        val point = DoubleArray(2)
        val distances = listOf(0.0 to 0.0, 400.0 to 0.0, 0.0 to 300.0, 400.0 to 300.0).map { (x, y) ->
            map.sourcePoint(x, y, 400, 300, point)
            minOf(point[0], 400 - point[0], point[1], 300 - point[1])
        }
        assertTrue("no corner touches an edge: $distances", distances.min() < 1e-3)
        assertTrue("a corner is outside: $distances", distances.min() > -1e-3)
    }

    @Test
    fun perspectiveAlsoLeavesNoEmptyCorner() {
        for ((v, h) in listOf(0.5f to 0f, -0.5f to 0f, 0f to 0.7f, 1f to -1f, 0.3f to 0.3f)) {
            val map = GeometryMap(400, 300, Geometry(perspectiveVertical = v, perspectiveHorizontal = h))
            assertTrue("perspective $v,$h", everyOutputPointIsInsideTheOriginal(map, 400, 300, 400, 300))
            assertTrue(map.fillZoom > 1.0)
        }
    }

    @Test
    fun perspectiveKeepsStraightLinesStraight() {
        // Three points of a vertical line of the output stay collinear in the original: the map is projective.
        val map = GeometryMap(400, 300, Geometry(perspectiveVertical = 0.6f, perspectiveHorizontal = 0.2f, straightenDegrees = 3f))
        val a = DoubleArray(2)
        val b = DoubleArray(2)
        val c = DoubleArray(2)
        map.sourcePoint(100.0, 20.0, 400, 300, a)
        map.sourcePoint(100.0, 150.0, 400, 300, b)
        map.sourcePoint(100.0, 280.0, 400, 300, c)
        val cross = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
        assertEquals(0.0, cross, 1e-6 * 400 * 300)
    }

    @Test
    fun aTiltedHorizonIsLevelledByTheOppositeAngle() {
        // A dark line falling to the right by 4 degrees on a light picture.
        val w = 400
        val h = 300
        val slope = Math.tan(Math.toRadians(4.0))
        val tilted = RgbImage(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (kotlin.math.abs(y - (h / 2 + (x - w / 2) * slope)) < 1.5) EditTestImages.argb(0, 0, 0) else EditTestImages.argb(255, 255, 255)
        })
        fun spread(straighten: Float): Double {
            val out = draw(Geometry(straightenDegrees = straighten), tilted)
            val rows = (0 until out.width step 10).map { x -> (0 until out.height).minByOrNull { y -> EditTestImages.red(at(out, x, y)) }!! }
            return (rows.max() - rows.min()).toDouble()
        }
        assertTrue("levelled ${spread(-4f)} vs unchanged ${spread(0f)}", spread(-4f) <= 2.0 && spread(0f) > 10.0)
        assertTrue("the wrong way is worse: ${spread(4f)}", spread(4f) > spread(0f))
    }

    // --- what a band needs ----------------------------------------------------------------------------

    @Test
    fun aBandsSourceBoxContainsEverythingItReads() {
        val geometry = Geometry(quarterTurns = 1, straightenDegrees = 7f, perspectiveVertical = 0.3f, crop = Crop(0.1f, 0.1f, 0.9f, 0.8f))
        val map = GeometryMap(400, 300, geometry)
        val (w, h) = map.sizeWithin(200)
        val point = DoubleArray(2)
        for ((top, bottom) in listOf(0 to h / 3, h / 3 to 2 * h / 3, 2 * h / 3 to h)) {
            val box = map.sourceBox(top, bottom, w, h, 0)
            for (y in top until bottom) for (x in 0 until w) {
                map.sourcePoint(x + 0.5, y + 0.5, w, h, point)
                assertTrue("($x,$y) -> ${point.toList()} outside ${box.toList()}",
                    point[0] >= box[0] - 1 && point[0] <= box[0] + box[2] + 1 && point[1] >= box[1] - 1 && point[1] <= box[1] + box[3] + 1)
            }
        }
    }

    @Test
    fun theMapIsPureArithmeticSoTheSamePointGivesTheSamePlaceTwice() {
        val map = GeometryMap(400, 300, Geometry(straightenDegrees = 2f))
        val a = DoubleArray(2)
        val b = DoubleArray(2)
        map.sourcePoint(12.5, 40.5, 100, 75, a)
        map.sourcePoint(12.5, 40.5, 100, 75, b)
        assertEquals(a.toList(), b.toList())
        assertTrue(abs(a[0]) < 400)
    }

    // --- how large to decode ----------------------------------------------------------------------------

    @Test
    fun withoutACropTheDecodeIsAsLargeAsTheScreenNeeds() {
        assertEquals(1000, GeometryMap(4000, 3000, Geometry.NONE).sourceEdgeFor(targetEdge = 1000, cap = 4096))
    }

    @Test
    fun aTightCropNeedsABiggerDecodeToStaySharp() {
        val half = GeometryMap(4000, 3000, Geometry(crop = Crop(0.25f, 0.25f, 0.75f, 0.75f)))
        assertEquals(2000, half.sourceEdgeFor(targetEdge = 1000, cap = 4096))
    }

    @Test
    fun theDecodeNeverGoesPastTheCap() {
        val tiny = GeometryMap(4000, 3000, Geometry(crop = Crop(0.45f, 0.45f, 0.55f, 0.55f)))
        assertEquals(4096, tiny.sourceEdgeFor(targetEdge = 1000, cap = 4096))
    }

    @Test
    fun aQuarterTurnDoesNotChangeHowLargeTheDecodeIs() {
        assertEquals(1000, GeometryMap(4000, 3000, Geometry(quarterTurns = 1)).sourceEdgeFor(targetEdge = 1000, cap = 4096))
    }
}
