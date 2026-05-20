package io.github.sceneview.ar.physics

import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.translation
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins the pure math backing [DepthCollider] (#1713):
 *  - [transformPositionsToWorld] applies a [Mat4] to a flat-packed vertex array.
 *  - [nearestSurfaceYBelow] finds the highest triangle apex under an XZ point + radius.
 *  - [cullIndicesToRegion] drops triangles outside an AABB; [computeBodiesRegion] builds that AABB.
 *
 * ARCore + Filament types are not available in this JVM test, so the math lives in pure top-level
 * `internal` functions and is verified here without an instrumented device. A sign error in the
 * transform application or the barycentric inversion would otherwise only surface on depth-capable
 * hardware.
 */
class DepthMeshCollisionTest {

    // ── transformPositionsToWorld ─────────────────────────────────────────────────────────────────

    @Test
    fun `identity transform leaves positions unchanged`() {
        val cam = floatArrayOf(1f, 2f, 3f, -4f, -5f, -6f, 0f, 0f, 0f)
        val world = transformPositionsToWorld(cam, Mat4.identity())
        assertArrayEqualsFloat(cam, world, eps = 1e-6f)
    }

    @Test
    fun `pure translation shifts every vertex by the translation`() {
        val cam = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        // translation(...) takes the Float3 directly — +X +Y +Z translation by (10, 20, 30).
        val t = translation(Position(10f, 20f, 30f))
        val world = transformPositionsToWorld(cam, t)
        assertArrayEqualsFloat(
            floatArrayOf(10f, 20f, 30f, 11f, 20f, 30f, 10f, 21f, 30f),
            world,
            eps = 1e-5f,
        )
    }

    @Test
    fun `empty positions array returns an empty world array`() {
        val cam = FloatArray(0)
        val world = transformPositionsToWorld(cam, Mat4.identity())
        assertEquals(0, world.size)
    }

    // ── nearestSurfaceYBelow ──────────────────────────────────────────────────────────────────────

    @Test
    fun `single flat triangle at y=0 returns y=0 under its centre`() {
        // Triangle with vertices in CCW from above on the y=0 plane.
        val pos = floatArrayOf(
            -1f, 0f, -1f,    // 0
            1f, 0f, -1f,    // 1
            0f, 0f, 1f,    // 2
        )
        val idx = intArrayOf(0, 1, 2)
        val y = nearestSurfaceYBelow(pos, idx, x = 0f, y = 5f, z = 0f, radius = 0.1f)
        assertNotNull(y)
        assertEquals(0.0f, y!!, 1e-5f)
    }

    @Test
    fun `flat triangle returns null for points outside the XZ footprint`() {
        val pos = floatArrayOf(
            0f, 0f, 0f,
            1f, 0f, 0f,
            0f, 0f, 1f,
        )
        val idx = intArrayOf(0, 1, 2)
        // (10, 10) is far outside the triangle and far outside the radius — must miss.
        val y = nearestSurfaceYBelow(pos, idx, x = 10f, y = 0f, z = 10f, radius = 0.05f)
        assertNull(y)
    }

    @Test
    fun `interpolated Y respects the triangle slope`() {
        // Tilted triangle: y = 1 at x = -1, y = 0 at x = 1 (slope -0.5 across X), constant Z.
        // Three vertices form a horizontal triangle in XZ but with sloped Y.
        val pos = floatArrayOf(
            -1f, 1f, -1f,    // 0
            1f, 0f, -1f,    // 1
            0f, 0.5f, 1f,    // 2 — midpoint Y so the linear ramp matches across the patch
        )
        val idx = intArrayOf(0, 1, 2)
        // At the centroid the interpolated Y should average to 0.5.
        val y = nearestSurfaceYBelow(pos, idx, x = 0f, y = 5f, z = -1f / 3f, radius = 0.1f)
        assertNotNull(y)
        assertEquals(0.5f, y!!, 1e-4f)
    }

    @Test
    fun `picks highest apex among overlapping triangles`() {
        // Two coplanar XZ triangles, both contain origin, but one is at y=0 and one at y=1.
        // The "highest apex" rule must pick y=1 (a real table on top of the floor).
        val pos = floatArrayOf(
            // Low floor triangle
            -2f, 0f, -2f, 2f, 0f, -2f, 0f, 0f, 2f,
            // Higher "tabletop" triangle, same XZ footprint, +1 Y
            -2f, 1f, -2f, 2f, 1f, -2f, 0f, 1f, 2f,
        )
        val idx = intArrayOf(0, 1, 2, 3, 4, 5)
        val y = nearestSurfaceYBelow(pos, idx, x = 0f, y = 5f, z = 0f, radius = 0.05f)
        assertNotNull(y)
        assertEquals(1.0f, y!!, 1e-4f)
    }

    @Test
    fun `empty indices returns null`() {
        val pos = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val y = nearestSurfaceYBelow(pos, IntArray(0), x = 0f, y = 5f, z = 0f, radius = 0.1f)
        assertNull(y)
    }

    @Test
    fun `degenerate XZ-collinear triangle is skipped (no division by zero)`() {
        // All three vertices share the same Z line — XZ-projection has zero area.
        val pos = floatArrayOf(
            0f, 0f, 0f,
            1f, 0f, 0f,
            2f, 0f, 0f,
        )
        val idx = intArrayOf(0, 1, 2)
        // No exception, no crash; just nothing under the query.
        val y = nearestSurfaceYBelow(pos, idx, x = 0.5f, y = 5f, z = 0f, radius = 0.05f)
        assertNull(y)
    }

    @Test
    fun `vertex within radius is picked up even when XZ is outside the triangle`() {
        // Single triangle covering small footprint near origin. Query at (1, ?, 0) — outside the
        // triangle in XZ, but vertex (1, 0.5, 0) is exactly within radius 0.05 of it.
        val pos = floatArrayOf(
            0f, 0f, 0f,
            1f, 0.5f, 0f,
            0f, 0f, 1f,
        )
        val idx = intArrayOf(0, 1, 2)
        val y = nearestSurfaceYBelow(pos, idx, x = 1.04f, y = 5f, z = 0f, radius = 0.05f)
        assertNotNull(y)
        // The fallback picks the highest vertex of the triangle (0.5).
        assertEquals(0.5f, y!!, 1e-4f)
    }

    // ── computeBodiesRegion ───────────────────────────────────────────────────────────────────────

    @Test
    fun `empty centres returns EMPTY region`() {
        val r = computeBodiesRegion(FloatArray(0), padding = 1f)
        assertEquals(RegionAabb.EMPTY, r)
    }

    @Test
    fun `single body region is padded equally on all sides`() {
        val r = computeBodiesRegion(floatArrayOf(1f, 2f, 3f), padding = 0.5f)
        assertEquals(0.5f, r.minX, 1e-6f)
        assertEquals(1.5f, r.minY, 1e-6f)
        assertEquals(2.5f, r.minZ, 1e-6f)
        assertEquals(1.5f, r.maxX, 1e-6f)
        assertEquals(2.5f, r.maxY, 1e-6f)
        assertEquals(3.5f, r.maxZ, 1e-6f)
    }

    @Test
    fun `multi-body region is the AABB plus padding`() {
        val centres = floatArrayOf(
            0f, 0f, 0f,
            2f, 0f, 0f,
            0f, 3f, 5f,
        )
        val r = computeBodiesRegion(centres, padding = 0.25f)
        assertEquals(-0.25f, r.minX, 1e-6f)
        assertEquals(-0.25f, r.minY, 1e-6f)
        assertEquals(-0.25f, r.minZ, 1e-6f)
        assertEquals(2.25f, r.maxX, 1e-6f)
        assertEquals(3.25f, r.maxY, 1e-6f)
        assertEquals(5.25f, r.maxZ, 1e-6f)
    }

    // ── cullIndicesToRegion ───────────────────────────────────────────────────────────────────────

    @Test
    fun `culling keeps overlapping triangle, drops faraway one`() {
        // Two triangles: one at origin, one far away at (100, 0, 0).
        val pos = floatArrayOf(
            0f, 0f, 0f, 0.5f, 0f, 0f, 0f, 0f, 0.5f,                 // tri 0 (kept)
            100f, 0f, 0f, 100.5f, 0f, 0f, 100f, 0f, 0.5f,           // tri 1 (dropped)
        )
        val idx = intArrayOf(0, 1, 2, 3, 4, 5)
        val region = RegionAabb(
            minX = -1f, minY = -1f, minZ = -1f,
            maxX = 1f, maxY = 1f, maxZ = 1f,
        )
        val culled = cullIndicesToRegion(pos, idx, region)
        assertEquals(3, culled.size)
        assertEquals(0, culled[0])
        assertEquals(1, culled[1])
        assertEquals(2, culled[2])
    }

    @Test
    fun `culling against EMPTY region drops every triangle`() {
        val pos = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val idx = intArrayOf(0, 1, 2)
        val culled = cullIndicesToRegion(pos, idx, RegionAabb.EMPTY)
        assertEquals(0, culled.size)
    }

    @Test
    fun `culling an already-empty index array is a no-op`() {
        val pos = FloatArray(0)
        val idx = IntArray(0)
        val region = RegionAabb(0f, 0f, 0f, 1f, 1f, 1f)
        val culled = cullIndicesToRegion(pos, idx, region)
        assertEquals(0, culled.size)
    }

    @Test
    fun `RegionAabb_EMPTY is detected as not finite`() {
        assertFalse(RegionAabb.EMPTY.isFinite())
        assertTrue(RegionAabb(0f, 0f, 0f, 1f, 1f, 1f).isFinite())
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private fun assertArrayEqualsFloat(expected: FloatArray, actual: FloatArray, eps: Float) {
        assertEquals(
            "array length differs: expected ${expected.size}, got ${actual.size}",
            expected.size,
            actual.size,
        )
        for (i in expected.indices) {
            assertEquals(
                "index $i differs: expected ${expected[i]}, got ${actual[i]}",
                expected[i].toDouble(),
                actual[i].toDouble(),
                eps.toDouble(),
            )
        }
    }
}
