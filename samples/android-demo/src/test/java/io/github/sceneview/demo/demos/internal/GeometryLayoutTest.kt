package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * JVM tests for [GeometryLayout] — the framing arithmetic behind
 * [io.github.sceneview.demo.demos.GeometryDemo].
 *
 * These exist because the defect they guard against
 * ([#2873](https://github.com/sceneview/sceneview/issues/2873)) is invisible to every other
 * gate: the demo compiled, rendered correctly, and passed the store-capture blank-frame
 * guard while a primitive hung off the edge of the frame. What was wrong was a *number* —
 * a group wider than the frame it was viewed in. So the numbers are what get asserted.
 *
 * The frustum relation used here (`halfHeight = distance · 12 / focalLength`) is the one
 * Filament's `setLensProjection` implements, and it reproduced on-device pixel measurements
 * to within 1.5 % on the QA emulator.
 */
class GeometryLayoutTest {

    /** Fill ratio the cluster must stay under on a real phone-portrait viewport. */
    private val portraitFillCeiling = 0.80f

    /** Fill ratio the cluster must stay under even on the narrowest frame it could meet. */
    private val worstCaseFillCeiling = 0.92f

    @Test
    fun `cluster half extents account for the Y-axis spin`() {
        // The cube sweeps its half-DIAGONAL, not its half-edge — using the half-edge here
        // would under-report the footprint by 27 % and let a clipping layout pass.
        assertEquals(
            GeometryLayout.COLUMN_X + GeometryLayout.PLANE_EDGE * 0.5f,
            GeometryLayout.halfWidth,
            1e-5f,
        )
        assertTrue(
            "the spun cube must not be the widest primitive here, else the plane's " +
                "half-edge is no longer the binding constraint",
            GeometryLayout.CUBE_EDGE * 0.5f * sqrt(2f) < GeometryLayout.PLANE_EDGE * 0.5f,
        )
        assertEquals(
            GeometryLayout.ROW_Y + GeometryLayout.PLANE_EDGE * 0.5f,
            GeometryLayout.halfHeight,
            1e-5f,
        )
    }

    @Test
    fun `cluster fits a phone-portrait frame with visible margin at the default distance`() {
        val fill = GeometryLayout.horizontalFillRatio(
            GeometryLayout.CAMERA_DISTANCE,
            GeometryLayout.PHONE_PORTRAIT_ASPECT,
        )
        assertTrue("cluster fills $fill of the frame width — clipped", fill < 1f)
        assertTrue(
            "cluster fills $fill of the frame width — no visible margin left",
            fill < portraitFillCeiling,
        )
        // A subject that occupies a tenth of the frame passes every automated guard and is
        // still unusable as a screenshot, so the floor matters as much as the ceiling.
        assertTrue("cluster fills only $fill of the frame width — too small to read", fill > 0.6f)
    }

    @Test
    fun `cluster still clears the narrowest frame it could ever be rendered in`() {
        val fill = GeometryLayout.horizontalFillRatio(
            GeometryLayout.CAMERA_DISTANCE,
            GeometryLayout.NARROWEST_EXPECTED_ASPECT,
        )
        assertTrue("cluster fills $fill of the worst-case frame width — clipped", fill < 1f)
        assertTrue(
            "cluster fills $fill of the worst-case frame width — margin too thin",
            fill < worstCaseFillCeiling,
        )
    }

    @Test
    fun `cluster fits the frame height with room to spare`() {
        val fill = GeometryLayout.verticalFillRatio(GeometryLayout.CAMERA_DISTANCE)
        assertTrue("cluster fills $fill of the frame height — clipped", fill < 1f)
        // Portrait has height to spare; the group should not be crammed against top/bottom.
        assertTrue("cluster fills $fill of the frame height — too tight", fill < 0.7f)
    }

    @Test
    fun `the four-wide row this layout replaced would have been clipped`() {
        // The regression this pins, expressed in its own terms: primitives at x = ±0.6 / ±0.2
        // (widest half-extent 0.6 + plane half-edge) seen from the 1.22 m the old
        // eyePosition actually resolved to.
        val oldHalfWidth = 0.6f + GeometryLayout.PLANE_EDGE * 0.5f
        val oldDistance = 1.22f
        val oldFill = oldHalfWidth / GeometryLayout.frameHalfWidth(
            oldDistance,
            GeometryLayout.PHONE_PORTRAIT_ASPECT,
        )
        assertTrue(
            "the old row should measure as clipped (fill $oldFill) — if it does not, the " +
                "frustum model here no longer matches what #2873 observed on-device",
            oldFill > 1f,
        )
    }

    @Test
    fun `orbit home offset has the requested length so the distance means what it says`() {
        listOf(0.5f, 2.6f, 6f, 100f).forEach { distance ->
            val offset = GeometryLayout.orbitHomeOffset(distance)
            val length = sqrt(offset.x * offset.x + offset.y * offset.y + offset.z * offset.z)
            assertEquals("length at distance $distance", distance, length, 1e-3f)
            assertTrue("camera must sit above the view axis", offset.y > 0f)
            assertTrue("camera must sit in front of the target", offset.z > 0f)
        }
    }

    @Test
    fun `a camera_distance override reframes the scene monotonically`() {
        // The lever's whole point: a larger distance must actually pull back. #2873 reports
        // "every camera distance tried" as identical, which is what a silently ignored extra
        // looks like from the outside.
        val near = GeometryLayout.horizontalFillRatio(2f, GeometryLayout.PHONE_PORTRAIT_ASPECT)
        val far = GeometryLayout.horizontalFillRatio(8f, GeometryLayout.PHONE_PORTRAIT_ASPECT)
        assertTrue("pulling back must shrink the subject ($near → $far)", far < near)
    }

    @Test
    fun `frame extents reject nonsensical inputs instead of returning garbage`() {
        listOf(0f, -1f).forEach { bad ->
            runCatching { GeometryLayout.frameHalfHeight(bad) }
                .onSuccess { error("distance $bad should be rejected, returned $it") }
            runCatching { GeometryLayout.orbitHomeOffset(bad) }
                .onSuccess { error("distance $bad should be rejected, returned $it") }
            runCatching { GeometryLayout.frameHalfWidth(1f, bad) }
                .onSuccess { error("aspect $bad should be rejected, returned $it") }
        }
    }
}
