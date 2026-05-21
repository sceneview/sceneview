package io.github.sceneview.ar.node

import com.google.android.filament.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the degenerate-AABB clamp of [computeAabb] (#1806).
 *
 * The previous fix (#1783) only handled `positions.isEmpty()`. When geometry has valid samples but
 * they all share the same coordinate (e.g. a depth image where every pixel reports the same range,
 * or the first frame after a scene reset where only one off-grid (0,0,0) vertex made it through),
 * the min/max collapse and at least one half-extent comes out as 0 — same Filament
 * `AABB can't be empty` SIGABRT.
 *
 * Surfaced by the May 2026 Tier-2 SECURITY audit on commit `4d4b53dd2`.
 */
class DepthMeshNodeAabbTest {

    @Test
    fun `computeAabb empty positions returns the degenerate cube (#1783 regression pin)`() {
        val aabb = computeAabb(FloatArray(0))
        assertHalfExtentsAtLeastDegenerate(aabb)
        assertCenter(aabb, 0f, 0f, 0f)
    }

    @Test
    fun `computeAabb same-coordinate positions clamp to the degenerate cube (#1806)`() {
        // Acceptance: a unit test where all positions equal (0.5, 1.0, -2.0) — should not emit a
        // zero half-extent. The previous fix only handled positions.isEmpty(), so this case slipped
        // through and re-triggered Filament's `AABB can't be empty` SIGABRT.
        val flat = FloatArray(30) // 10 vertices
        var i = 0
        while (i < flat.size) {
            flat[i] = 0.5f
            flat[i + 1] = 1.0f
            flat[i + 2] = -2.0f
            i += 3
        }
        val aabb = computeAabb(flat)
        assertHalfExtentsAtLeastDegenerate(aabb)
        // Centre is preserved at the shared coordinate.
        assertCenter(aabb, 0.5f, 1.0f, -2.0f)
    }

    @Test
    fun `computeAabb same-X-and-same-Z positions clamp only the degenerate axes (#1806)`() {
        // Worst case in practice — a fronto-parallel depth surface where Z is constant. Y still
        // varies, so only X and Z are degenerate. The clamp must touch ONLY those axes.
        val flat = floatArrayOf(
            0.0f, 0.0f, -1.0f,
            0.0f, 1.0f, -1.0f,
            0.0f, 2.0f, -1.0f,
        )
        // X is constant (0) → halfX would be 0 without the clamp.
        // Y spans 0..2 → halfY = 1.
        // Z is constant (-1) → halfZ would be 0 without the clamp.
        val aabb = computeAabb(flat)
        assertTrue(
            "halfX must be clamped above DEGENERATE_AABB_HALF_EXTENT_M",
            aabb.halfExtent[0] >= DepthMeshNode.DEGENERATE_AABB_HALF_EXTENT_M,
        )
        assertEquals(
            "halfY must NOT be clamped (real spread of 1.0)",
            1.0f,
            aabb.halfExtent[1],
            1e-6f,
        )
        assertTrue(
            "halfZ must be clamped above DEGENERATE_AABB_HALF_EXTENT_M",
            aabb.halfExtent[2] >= DepthMeshNode.DEGENERATE_AABB_HALF_EXTENT_M,
        )
        // Centre: (0, 1, -1)
        assertEquals(0f, aabb.center[0], 1e-6f)
        assertEquals(1f, aabb.center[1], 1e-6f)
        assertEquals(-1f, aabb.center[2], 1e-6f)
    }

    @Test
    fun `computeAabb identical-Z depth frame clamps only the Z axis (#1957)`() {
        // The exact case named in #1957 — every depth pixel reports the same range, so Z is
        // constant while the unprojected X/Y still spread across the image. Only Z is degenerate;
        // the clamp must lift halfZ above the threshold and leave halfX / halfY untouched, so the
        // returned Box still satisfies Filament's `halfExtent > 0` precondition without inflating
        // a real, well-spread mesh.
        val flat = floatArrayOf(
            -1.5f, -2.0f, -4.0f,
            0.5f, 0.0f, -4.0f,
            2.5f, 3.0f, -4.0f,
            -0.5f, 1.0f, -4.0f,
        )
        val aabb = computeAabb(flat)
        // X spans -1.5..2.5 → halfX = 2.0 (real spread, must NOT clamp).
        assertEquals("halfX must keep the real spread", 2.0f, aabb.halfExtent[0], 1e-6f)
        // Y spans -2.0..3.0 → halfY = 2.5 (real spread, must NOT clamp).
        assertEquals("halfY must keep the real spread", 2.5f, aabb.halfExtent[1], 1e-6f)
        // Z is constant at -4.0 → halfZ would be 0 without the clamp.
        assertTrue(
            "halfZ must be clamped above DEGENERATE_AABB_HALF_EXTENT_M",
            aabb.halfExtent[2] >= DepthMeshNode.DEGENERATE_AABB_HALF_EXTENT_M,
        )
        // Centre: X (-1.5+2.5)/2 = 0.5, Y (-2.0+3.0)/2 = 0.5, Z = -4.0
        assertCenter(aabb, 0.5f, 0.5f, -4.0f)
    }

    @Test
    fun `computeAabb single-vertex returns degenerate cube around the vertex (#1806)`() {
        val flat = floatArrayOf(3.14f, -2.71f, 1.41f)
        val aabb = computeAabb(flat)
        assertHalfExtentsAtLeastDegenerate(aabb)
        assertCenter(aabb, 3.14f, -2.71f, 1.41f)
    }

    @Test
    fun `computeAabb single (0,0,0) vertex returns degenerate cube around origin (#1806)`() {
        // The exact case called out in the issue — first frame after a scene reset where only one
        // off-grid (0,0,0) vertex made it through. Previously slipped past the .isEmpty() guard.
        val aabb = computeAabb(floatArrayOf(0f, 0f, 0f))
        assertHalfExtentsAtLeastDegenerate(aabb)
        assertCenter(aabb, 0f, 0f, 0f)
    }

    @Test
    fun `computeAabb well-spread positions keep the real half-extents`() {
        val flat = floatArrayOf(
            -1f, -2f, -3f,
            1f, 2f, 3f,
        )
        val aabb = computeAabb(flat)
        assertEquals(1f, aabb.halfExtent[0], 1e-6f)
        assertEquals(2f, aabb.halfExtent[1], 1e-6f)
        assertEquals(3f, aabb.halfExtent[2], 1e-6f)
        assertCenter(aabb, 0f, 0f, 0f)
    }

    private fun assertHalfExtentsAtLeastDegenerate(aabb: Box) {
        val min = DepthMeshNode.DEGENERATE_AABB_HALF_EXTENT_M
        assertTrue("halfX = ${aabb.halfExtent[0]} must be ≥ $min", aabb.halfExtent[0] >= min)
        assertTrue("halfY = ${aabb.halfExtent[1]} must be ≥ $min", aabb.halfExtent[1] >= min)
        assertTrue("halfZ = ${aabb.halfExtent[2]} must be ≥ $min", aabb.halfExtent[2] >= min)
    }

    private fun assertCenter(aabb: Box, x: Float, y: Float, z: Float) {
        assertEquals("center.x", x, aabb.center[0], 1e-6f)
        assertEquals("center.y", y, aabb.center[1], 1e-6f)
        assertEquals("center.z", z, aabb.center[2], 1e-6f)
    }
}
