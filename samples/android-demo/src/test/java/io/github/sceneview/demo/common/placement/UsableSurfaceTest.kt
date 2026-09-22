package io.github.sceneview.demo.common.placement

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
class UsableSurfaceTest {

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

    // ── Fallback ranking ──────────────────────────────────────────────────────────────

    @Test
    fun `visible plane centres are ranked nearest first`() {
        val ranked = UsableSurfacePolicy.rankFallback(
            listOf(
                FallbackCandidate("far", 2.5f, Ndc(0.2f, -0.4f)),
                FallbackCandidate("near", 0.8f, Ndc(-0.5f, 0.1f)),
                FallbackCandidate("mid", 1.6f, Ndc(0.9f, 0.9f)),
            ),
        )
        assertEquals(listOf("near", "mid", "far"), ranked)
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

    // ── Scale label ───────────────────────────────────────────────────────────────────

    @Test
    fun `only a measured size may say actual size`() {
        assertEquals(ScaleLabelMode.ACTUAL, scaleLabelMode(sizeIsMeasured = true))
        assertEquals(ScaleLabelMode.PREVIEW, scaleLabelMode(sizeIsMeasured = false))
    }
}
