package io.github.sceneview.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the #3880 framing math: `fitToModels()` frames boxes where they are drawn
 * ([ContentCentering.transformed]) and the clip planes follow the content size
 * ([ContentCentering.clipPlanes], #3747).
 */
class FitFramingTest {

    private fun box(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double) =
        ContentCentering.Aabb(doubleArrayOf(minX, minY, minZ), doubleArrayOf(maxX, maxY, maxZ))

    private fun assertBox(expected: ContentCentering.Aabb, actual: ContentCentering.Aabb) {
        for (axis in 0 until 3) {
            assertEquals(expected.min[axis], actual.min[axis], 1e-9, "min[$axis]")
            assertEquals(expected.max[axis], actual.max[axis], 1e-9, "max[$axis]")
        }
    }

    /** Column-major translate-then-uniform-scale matrix, as Filament stores it. */
    private fun translateScale(tx: Double, ty: Double, tz: Double, s: Double = 1.0) = doubleArrayOf(
        s, 0.0, 0.0, 0.0,
        0.0, s, 0.0, 0.0,
        0.0, 0.0, s, 0.0,
        tx, ty, tz, 1.0,
    )

    @Test
    fun identityLeavesTheBoxUnchanged() {
        val b = box(-1.0, 0.0, 2.0, 3.0, 4.0, 5.0)
        assertBox(b, ContentCentering.transformed(b, translateScale(0.0, 0.0, 0.0)))
    }

    @Test
    fun theCentringPivotMovesTheFramedBoxToTheOrigin() {
        // A model authored off the origin, then centred by the content-root pivot:
        // the box the fit must frame is the moved one (#3880), centred on 0.
        val authored = box(100.0, 0.0, -50.0, 140.0, 30.0, -30.0)
        val offset = ContentCentering.centeringOffset(authored)!!
        val drawn = ContentCentering.transformed(authored, translateScale(offset[0], offset[1], offset[2]))
        val c = ContentCentering.center(drawn)
        assertEquals(0.0, c[0], 1e-9)
        assertEquals(0.0, c[1], 1e-9)
        assertEquals(0.0, c[2], 1e-9)
        assertEquals(ContentCentering.diagonal(authored), ContentCentering.diagonal(drawn), 1e-9)
    }

    @Test
    fun scaleAndRotationAreRebounded() {
        // Uniform scale 2 about the origin.
        assertBox(
            box(2.0, 4.0, 6.0, 4.0, 8.0, 12.0),
            ContentCentering.transformed(box(1.0, 2.0, 3.0, 2.0, 4.0, 6.0), translateScale(0.0, 0.0, 0.0, 2.0)),
        )
        // 90° about Y (column-major): x' = z, z' = -x.
        val rotY = doubleArrayOf(
            0.0, 0.0, -1.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            1.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
        assertBox(
            box(0.0, 0.0, -2.0, 1.0, 1.0, 0.0),
            ContentCentering.transformed(box(0.0, 0.0, 0.0, 2.0, 1.0, 1.0), rotY),
        )
    }

    @Test
    fun metreToBuildingScaleKeepsTheHistoricalPlanes() {
        for (radius in listOf(1.0, 5.0, 40.0)) {
            val planes = ContentCentering.clipPlanes(radius, radius * 10.0)
            assertEquals(ContentCentering.DEFAULT_NEAR, planes[0], 1e-12, "near @ r=$radius")
            assertEquals(ContentCentering.DEFAULT_FAR, planes[1], 1e-12, "far @ r=$radius")
        }
    }

    @Test
    fun aTwoCentimetreModelIsInFrontOfTheNearPlane() {
        // #3880: a 2 cm part (radius ~1 cm) is framed ~2.5 cm away; the fixed
        // 0.1 m near plane swallowed it whole.
        val radius = 0.01
        val distance = ContentCentering.fitDistance(radius, 1.0)
        val near = ContentCentering.clipPlanes(radius, radius * 10.0)[0]
        assertTrue(distance - radius > near, "front face ${distance - radius} must lie beyond near $near")
        assertTrue(distance - radius < ContentCentering.DEFAULT_NEAR, "the old near plane clipped it")
        assertEquals(0.001, near, 1e-12)
    }

    @Test
    fun aMillimetreAuthoredModelStaysInsideTheFarPlane() {
        // #3747: a glTF authored in millimetres — the demo's Retro Piano is 2162
        // units across — is framed at 2.5 × 1081 ≈ 2700, past the fixed far = 1000.
        val radius = 1081.0
        val maxDistance = radius * 10.0
        val far = ContentCentering.clipPlanes(radius, maxDistance)[1]
        assertTrue(ContentCentering.fitDistance(radius) + radius > ContentCentering.DEFAULT_FAR, "the old far plane clipped it")
        assertTrue(far >= maxDistance + radius, "far $far must reach the back of the content fully zoomed out")
    }

    @Test
    fun degenerateRadiusReturnsTheDefaults() {
        for (radius in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val planes = ContentCentering.clipPlanes(radius, 10.0)
            assertEquals(ContentCentering.DEFAULT_NEAR, planes[0], 0.0, "near @ r=$radius")
            assertEquals(ContentCentering.DEFAULT_FAR, planes[1], 0.0, "far @ r=$radius")
        }
    }
}
