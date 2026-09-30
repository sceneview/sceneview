package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "a usable surface" means (plan §2.3), as the predicates the session unpacks ARCore
 * objects into. The ARCore types themselves cannot be built on the JVM, which is exactly
 * why the policy takes booleans and metres.
 */
class UsableSurfacePolicyTest {

    private fun accept(
        upward: Boolean = true,
        tracking: Boolean = true,
        inPolygon: Boolean = true,
        distance: Float = 1.5f,
    ) = UsableSurfacePolicy.accept(
        isUpwardHorizontalPlane = upward,
        isTrackableTracking = tracking,
        isPoseInPolygon = inPolygon,
        distanceMeters = distance,
    )

    @Test
    fun `a tracked upward floor inside its polygon at arm's length is usable`() {
        assertTrue(accept())
    }

    @Test
    fun `walls, ceilings, points and depth guesses are never usable`() {
        assertFalse(accept(upward = false))
    }

    @Test
    fun `a plane that is not tracking is not usable`() {
        assertFalse(accept(tracking = false))
    }

    @Test
    fun `a hit outside the polygon is an extrapolation, not a surface`() {
        assertFalse(accept(inPolygon = false))
    }

    @Test
    fun `the distance band is closed at both ends`() {
        assertTrue(accept(distance = UsableSurfacePolicy.MIN_DISTANCE_M))
        assertTrue(accept(distance = UsableSurfacePolicy.MAX_DISTANCE_M))
        assertFalse("a hand or a table edge", accept(distance = UsableSurfacePolicy.MIN_DISTANCE_M - 0.01f))
        assertFalse("too far to judge", accept(distance = UsableSurfacePolicy.MAX_DISTANCE_M + 0.01f))
        assertEquals(0.25f, UsableSurfacePolicy.MIN_DISTANCE_M)
        assertEquals(3.0f, UsableSurfacePolicy.MAX_DISTANCE_M)
    }

    // ── Wall hits (#4070) ─────────────────────────────────────────────────────────────

    @Suppress("LongParameterList")
    private fun wall(
        vertical: Boolean = false,
        depth: Boolean = false,
        tracking: Boolean = true,
        inPolygon: Boolean = false,
        outside: Float = Float.POSITIVE_INFINITY,
        normalY: Float = 0f,
        distance: Float = 1.5f,
    ) = UsableSurfacePolicy.wallHit(
        isVerticalPlane = vertical,
        isDepthPoint = depth,
        isTrackableTracking = tracking,
        isPoseInPolygon = inPolygon,
        outsidePolygonMeters = outside,
        normalY = normalY,
        distanceMeters = distance,
    )

    @Test
    fun `a vertical plane hit inside its polygon is a plane wall`() {
        assertEquals(WallHitKind.PLANE, wall(vertical = true, inPolygon = true, outside = 0f))
    }

    @Test
    fun `a vertical plane hit up to one metre outside its polygon still counts`() {
        assertEquals(WallHitKind.EXTENDED_PLANE, wall(vertical = true, outside = 0.4f))
        assertEquals(
            WallHitKind.EXTENDED_PLANE,
            wall(vertical = true, outside = UsableSurfacePolicy.WALL_POLYGON_TOLERANCE_M),
        )
        assertNull(wall(vertical = true, outside = UsableSurfacePolicy.WALL_POLYGON_TOLERANCE_M + 0.01f))
        assertNull(wall(vertical = true, outside = Float.NaN))
    }

    @Test
    fun `a depth point with a horizontal normal is a wall, a floor depth point is not`() {
        assertEquals(WallHitKind.DEPTH_POINT, wall(depth = true, normalY = 0.1f))
        assertEquals(WallHitKind.DEPTH_POINT, wall(depth = true, normalY = -0.24f))
        assertNull("floor", wall(depth = true, normalY = 0.97f))
        assertNull("ceiling", wall(depth = true, normalY = -0.97f))
        assertNull(wall(depth = true, normalY = UsableSurfacePolicy.WALL_DEPTH_NORMAL_MAX_Y))
        assertNull(wall(depth = true, normalY = Float.NaN))
    }

    @Test
    fun `a horizontal plane or a feature point is never a wall`() {
        assertNull(wall(inPolygon = true, outside = 0f))
    }

    @Test
    fun `wall hits need tracking and the distance band`() {
        assertNull(wall(vertical = true, inPolygon = true, tracking = false))
        assertNull(wall(depth = true, tracking = false))
        assertNull(wall(depth = true, distance = UsableSurfacePolicy.MAX_DISTANCE_M + 0.01f))
        assertNull(wall(depth = true, distance = UsableSurfacePolicy.MIN_DISTANCE_M - 0.01f))
        assertNull(wall(depth = true, distance = Float.NaN))
    }

    @Test
    fun `wall hit kinds are ordered from the most trusted`() {
        assertTrue(WallHitKind.PLANE < WallHitKind.EXTENDED_PLANE)
        assertTrue(WallHitKind.EXTENDED_PLANE < WallHitKind.DEPTH_POINT)
        assertTrue(WallHitKind.DEPTH_POINT < WallHitKind.FLOOR_SEAM)
    }

    // ── Fallback ranking ──────────────────────────────────────────────────────────────

    @Test
    fun `visible plane centres are ranked by screen center proximity`() {
        val ranked = UsableSurfacePolicy.rankFallback(
            listOf(
                FallbackCandidate("far", 2.5f, Ndc(0.2f, -0.4f)),
                FallbackCandidate("near", 0.8f, Ndc(-0.5f, 0.1f)),
                FallbackCandidate("mid", 1.6f, Ndc(0.9f, 0.9f)),
            ),
        )
        assertEquals(listOf("far", "near", "mid"), ranked)
    }

    @Test
    fun `a plane behind the camera is dropped`() {
        val ranked = UsableSurfacePolicy.rankFallback(
            listOf(
                FallbackCandidate("behind", 0.5f, ndc = null),
                FallbackCandidate("ahead", 1.0f, Ndc(0f, 0f)),
            ),
        )
        assertEquals(listOf("ahead"), ranked)
    }

    @Test
    fun `a plane outside the viewport is dropped even when it is the nearest`() {
        val ranked = UsableSurfacePolicy.rankFallback(
            listOf(
                FallbackCandidate("off-left", 0.5f, Ndc(-1.2f, 0f)),
                FallbackCandidate("off-top", 0.6f, Ndc(0f, 1.01f)),
                FallbackCandidate("visible", 2.0f, Ndc(1f, -1f)),
            ),
        )
        assertEquals(listOf("visible"), ranked)
    }

    @Test
    fun `the fallback honours the same distance band as the ray`() {
        val ranked = UsableSurfacePolicy.rankFallback(
            listOf(
                FallbackCandidate("touching", 0.1f, Ndc(0f, 0f)),
                FallbackCandidate("across-the-room", 4f, Ndc(0f, 0f)),
                FallbackCandidate("ok", 1f, Ndc(0f, 0f)),
            ),
        )
        assertEquals(listOf("ok"), ranked)
    }

    @Test
    fun `no candidates means no surface, not a crash`() {
        assertTrue(UsableSurfacePolicy.rankFallback(emptyList<FallbackCandidate<String>>()).isEmpty())
    }

    // ── Projection ────────────────────────────────────────────────────────────────────

    private val identity = FloatArray(16).also { for (i in 0 until 4) it[i * 5] = 1f }

    @Test
    fun `identity projects a point to itself`() {
        val ndc = ViewportProjection.project(identity, 0.5f, -0.25f, 0f)
        assertNotNull(ndc)
        assertEquals(0.5f, ndc!!.x, 1e-6f)
        assertEquals(-0.25f, ndc.y, 1e-6f)
        assertTrue(ndc.isInsideViewport)
    }

    @Test
    fun `a perspective camera drops what lies behind it`() {
        // Simplest perspective: clip w = -z (camera looks down -Z).
        val perspective = FloatArray(16).also {
            it[0] = 1f
            it[5] = 1f
            it[11] = -1f
        }
        assertNull(ViewportProjection.project(perspective, 0f, 0f, 1f))
        assertNull("the camera plane itself is not in front", ViewportProjection.project(perspective, 0f, 0f, 0f))
        val ahead = ViewportProjection.project(perspective, 1f, 0.5f, -2f)
        assertNotNull(ahead)
        assertEquals(0.5f, ahead!!.x, 1e-6f)
        assertEquals(0.25f, ahead.y, 1e-6f)
    }

    @Test
    fun `multiply matches column-major convention`() {
        val translate = identity.copyOf().also { it[12] = 3f; it[13] = -1f }
        val scale = identity.copyOf().also { it[0] = 2f; it[5] = 2f }
        // (translate × scale) applied to (1, 1, 0) = translate(scale(p)) = (5, 1)
        val m = ViewportProjection.multiply(translate, scale)
        val p = ViewportProjection.project(m, 1f, 1f, 0f)!!
        assertEquals(5f, p.x, 1e-6f)
        assertEquals(1f, p.y, 1e-6f)
    }

    @Test
    fun `distance is euclidean`() {
        assertEquals(5f, ViewportProjection.distance(0f, 0f, 0f, 3f, 4f, 0f), 1e-6f)
    }

    @Test
    fun `ndc viewport edges are inclusive`() {
        assertTrue(Ndc(1f, -1f).isInsideViewport)
        assertFalse(Ndc(1.0001f, 0f).isInsideViewport)
    }


}
