package io.github.sceneview.ar

import io.github.sceneview.math.Position
import io.github.sceneview.node.ContactShadowContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class PlacementContactShadowTest {

    /** The eight corners of a box standing on y = 0, centred on (cx, cz). */
    private fun box(cx: Float, cz: Float, halfX: Float, height: Float, halfZ: Float) = buildList {
        for (x in listOf(-1f, 1f)) for (y in listOf(0f, height)) for (z in listOf(-1f, 1f)) {
            add(Position(cx + x * halfX, y, cz + z * halfZ))
        }
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * `contact_shadow.mat`'s rounded-rectangle branch, line for line, at (x, z) from the quad
     * centre in the pivot's units: the opacity drawn there by [PlacementContactShadowNode].
     */
    private fun alpha(q: PlacementContactShadow.Quad, x: Float, z: Float): Float {
        val r = min(q.cornerRadius, min(q.halfX, q.halfZ))
        val kx = abs(x) - (q.halfX - r)
        val kz = abs(z) - (q.halfZ - r)
        val sd = hypot(max(kx, 0f), max(kz, 0f)) + min(max(kx, kz), 0f) - r
        var a = 1f - smoothstep(0f, q.fade, sd)
        val ex = q.sizeX / 2f - abs(x)
        val ez = q.sizeZ / 2f - abs(z)
        a *= smoothstep(0f, 0.01f, min(ex, ez))
        return a * PlacementContactShadow.EDGE_ALPHA
    }

    /** The elliptical gradient the placement drew before: Floor preset, core at 0.8 of the footprint. */
    private fun ellipseAlpha(halfX: Float, halfZ: Float, x: Float, z: Float): Float {
        val floor = ContactShadowContext.Floor
        val sizeX = halfX * 0.8f / floor.radiusU
        val sizeZ = halfZ * 0.8f / floor.radiusV
        val qx = x / sizeX / floor.radiusU
        val qz = z / sizeZ / floor.radiusV
        val d = sqrt(qx * qx + qz * qz)
        var a = 1f - smoothstep(1f, 1f + floor.softness, d)
        val e = min(0.5f - abs(x / sizeX), 0.5f - abs(z / sizeZ))
        a *= smoothstep(0f, 0.08f, e)
        return a * floor.intensity
    }

    /** The Will It Fit three-seat sofa, 87 × 37 in, seen from above. */
    private val sofaHalfX = 87f * 0.0254f / 2f
    private val sofaHalfZ = 37f * 0.0254f / 2f

    @Test fun `the shadow is off by default`() {
        assertFalse(AutoPlacementState().showsContactShadow)
    }

    @Test fun `the pool is centred under the footprint`() {
        val q = PlacementContactShadow.quad(box(0.2f, -0.1f, 0.3f, 0.8f, 0.4f))!!
        assertEquals(0.2f, q.centerX, 1e-6f)
        assertEquals(-0.1f, q.centerZ, 1e-6f)
    }

    @Test fun `the rectangle is the footprint`() {
        val q = PlacementContactShadow.quad(box(0f, 0f, sofaHalfX, 0.84f, sofaHalfZ))!!
        assertEquals(sofaHalfX, q.halfX, 1e-6f)
        assertEquals(sofaHalfZ, q.halfZ, 1e-6f)
        assertEquals(PlacementContactShadow.FADE_METERS, q.fade, 1e-6f)
    }

    @Test fun `the whole edge of a sofa is as dark as the target`() {
        val q = PlacementContactShadow.quad(box(0f, 0f, sofaHalfX, 0.84f, sofaHalfZ))!!
        // Middle of the front, three quarters along it, and middle of a side.
        for ((x, z) in listOf(0f to sofaHalfZ, 0.75f * sofaHalfX to sofaHalfZ, sofaHalfX to 0f)) {
            val a = alpha(q, x, z)
            assertTrue("alpha $a at ($x, $z)", a in 0.35f..0.45f)
        }
        // The rounded corner leaves only the last few centimetres of each edge paler than half.
        val halfDark = (0..300).map { it / 1000f }
            .first { alpha(q, sofaHalfX - it, sofaHalfZ + 0.001f) >= PlacementContactShadow.EDGE_ALPHA / 2f }
        assertTrue("edge half dark from ${halfDark}m off the corner", halfDark < 0.10f)
    }

    @Test fun `the band fades out over the same absolute width on every side`() {
        val q = PlacementContactShadow.quad(box(0f, 0f, sofaHalfX, 0.84f, sofaHalfZ))!!
        for (d in listOf(0.03f, 0.06f, 0.09f)) {
            assertEquals("at $d m", alpha(q, sofaHalfX + d, 0f), alpha(q, 0f, sofaHalfZ + d), 1e-5f)
        }
        // Half the edge alpha half way, nothing at the end of the fade.
        assertEquals(PlacementContactShadow.EDGE_ALPHA / 2f, alpha(q, 0f, sofaHalfZ + q.fade / 2f), 1e-5f)
        assertEquals(0f, alpha(q, 0f, sofaHalfZ + q.fade), 0f)
        assertEquals(0f, alpha(q, sofaHalfX + q.fade, 0f), 0f)
        assertTrue(q.fade in 0.10f..0.15f)
    }

    @Test fun `the old ellipse left most of the sofa's edge bare`() {
        // What the placement drew before this change, measured the same way: only the middle of
        // each side came near the target, and the corners were bare.
        val front = ellipseAlpha(sofaHalfX, sofaHalfZ, 0f, sofaHalfZ)
        val alongFront = ellipseAlpha(sofaHalfX, sofaHalfZ, 0.75f * sofaHalfX, sofaHalfZ)
        val corner = ellipseAlpha(sofaHalfX, sofaHalfZ, sofaHalfX, sofaHalfZ)
        assertEquals(0.31f, front, 0.01f)
        assertTrue("three quarters along the front: $alongFront", alongFront < 0.05f)
        assertEquals(0f, corner, 0f)
    }

    /** How far past a round object's rim the band is still half dark, along [dirX], [dirZ]. */
    private fun halfDarkReach(q: PlacementContactShadow.Quad, radius: Float, dirX: Float, dirZ: Float): Float {
        val n = hypot(dirX, dirZ)
        return (0..400).map { it / 1000f }.last {
            alpha(q, (radius + it) * dirX / n, (radius + it) * dirZ / n) >= PlacementContactShadow.EDGE_ALPHA / 2f
        }
    }

    @Test fun `a round table gets a nearly round pool`() {
        // A 1 m round table: its bounds are a 1 m square. Without rounding, the band would stay
        // half dark 25 cm past the rim on the diagonals against 6 cm on the axes.
        val q = PlacementContactShadow.quad(box(0f, 0f, 0.5f, 0.75f, 0.5f))!!
        val axis = halfDarkReach(q, 0.5f, 1f, 0f)
        val diagonal = halfDarkReach(q, 0.5f, 1f, 1f)
        assertEquals(0.06f, axis, 0.002f)
        assertTrue("diagonal reaches ${diagonal - axis}m farther", diagonal - axis < 0.09f)
    }

    @Test fun `a mug gets a nearly round pool too`() {
        val q = PlacementContactShadow.quad(box(0f, 0f, 0.045f, 0.1f, 0.045f))!!
        val axis = halfDarkReach(q, 0.045f, 1f, 0f)
        val diagonal = halfDarkReach(q, 0.045f, 1f, 1f)
        assertTrue("diagonal reaches ${diagonal - axis}m farther", diagonal - axis < 0.01f)
    }

    @Test fun `the fade keeps its floor width at any pinch`() {
        val corners = box(0f, 0f, sofaHalfX, 0.84f, sofaHalfZ)
        for (pinch in listOf(0.25f, 0.5f, 1f, 2f, 4f)) {
            val q = PlacementContactShadow.quad(corners, pinch)!!
            // The rectangle stays the footprint in the pivot's units; the pivot scales it.
            assertEquals(sofaHalfX, q.halfX, 1e-6f)
            assertEquals("fade on the floor at $pinch", PlacementContactShadow.FADE_METERS, q.fade * pinch, 1e-5f)
            assertTrue(q.halfX + q.fade < q.sizeX / 2f)
        }
        // A degenerate pinch falls back to the real size rather than an infinite fade.
        assertEquals(PlacementContactShadow.FADE_METERS, PlacementContactShadow.quad(corners, 0f)!!.fade, 1e-6f)
    }

    @Test fun `a pinched-down mug keeps a fade proportional to itself`() {
        // At 25 % a 9 cm mug shows 2 cm wide: its fade stays half its half-extent, not 12 cm.
        val q = PlacementContactShadow.quad(box(0f, 0f, 0.045f, 0.1f, 0.045f), 0.25f)!!
        assertEquals(0.045f * PlacementContactShadow.FADE_FRACTION, q.fade, 1e-6f)
    }

    @Test fun `the fade ends inside the quad, before its border feather`() {
        val q = PlacementContactShadow.quad(box(0f, 0f, sofaHalfX, 0.84f, sofaHalfZ))!!
        assertEquals(q.halfX + q.fade + PlacementContactShadow.MARGIN_METERS, q.sizeX / 2f, 1e-6f)
        assertEquals(q.halfZ + q.fade + PlacementContactShadow.MARGIN_METERS, q.sizeZ / 2f, 1e-6f)
        assertTrue(PlacementContactShadow.MARGIN_METERS >= 0.01f)
    }

    @Test fun `a small object gets a proportionally short fade`() {
        // A 6 cm mug: a 12 cm pool would dwarf it.
        val q = PlacementContactShadow.quad(box(0f, 0f, 0.03f, 0.1f, 0.03f))!!
        assertEquals(0.03f * PlacementContactShadow.FADE_FRACTION, q.fade, 1e-6f)
    }

    @Test fun `the reach covers the shadow's farthest corner`() {
        val q = PlacementContactShadow.quad(box(0f, 0f, sofaHalfX, 0.84f, sofaHalfZ))!!
        // Along the corner's diagonal, the band ends at the reach.
        val dx = q.halfX - q.cornerRadius
        val dz = q.halfZ - q.cornerRadius
        val n = hypot(dx, dz)
        val out = q.cornerRadius + q.fade
        assertEquals(0f, alpha(q, dx + dx / n * out, dz + dz / n * out), 1e-6f)
        assertEquals(q.reach, hypot(dx + dx / n * out, dz + dz / n * out), 1e-5f)
        assertTrue(q.reach <= hypot(q.sizeX, q.sizeZ) / 2f)
    }

    @Test fun `a tiny object keeps a few millimetres of pool`() {
        val q = PlacementContactShadow.quad(box(0f, 0f, 0.001f, 0.001f, 0.001f))!!
        assertEquals(PlacementContactShadow.MIN_HALF_EXTENT, q.halfX, 1e-6f)
        assertTrue(q.sizeX > 0f && q.fade > 0f)
    }

    @Test fun `no corners or non-finite bounds give no shadow`() {
        assertNull(PlacementContactShadow.quad(emptyList()))
        assertNull(PlacementContactShadow.quad(listOf(Position(Float.NaN, 0f, 0f), Position(1f, 0f, 1f))))
        assertNotNull(PlacementContactShadow.quad(listOf(Position(0f, 0f, 0f))))
    }
}
