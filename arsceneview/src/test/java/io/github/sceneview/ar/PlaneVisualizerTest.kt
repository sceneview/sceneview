package io.github.sceneview.ar

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PlaneVisualizer]'s pure-JVM parts: the per-plane animation
 * ([PlaneRevealAnimation] — fade, focus, reveal front chasing a growing plane) and the polygon
 * scan-radius computation. No Filament Engine, no ARCore Session.
 */
class PlaneVisualizerTest {

    private val frame = 1f / 60f

    private fun PlaneRevealAnimation.run(
        seconds: Float,
        show: Boolean = true,
        focusTarget: Float = 0f,
    ) {
        var t = 0f
        while (t < seconds) {
            advance(frame, show, focusTarget)
            t += frame
        }
    }

    // ── approach ────────────────────────────────────────────────────────────────────

    @Test
    fun `approach moves by at most the step and lands exactly on the target`() {
        assertEquals(0.25f, approach(0f, 1f, 0.25f), 0f)
        assertEquals(0.75f, approach(1f, 0f, 0.25f), 0f)
        assertEquals(1f, approach(0.9f, 1f, 0.25f), 0f)
        assertEquals(0.5f, approach(0.5f, 0.5f, 0f), 0f)
    }

    // ── Fade ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a shown plane fades in over FADE_IN_SECONDS, not in one frame`() {
        val anim = PlaneRevealAnimation()
        anim.advance(frame, show = true, focusTarget = 0f)
        assertTrue("one frame must not pop the plane in: ${anim.opacity}", anim.opacity < 0.1f)
        anim.run(FADE_IN_SECONDS)
        assertEquals(1f, anim.opacity, 0f)
    }

    @Test
    fun `a hidden plane fades out to exactly zero`() {
        val anim = PlaneRevealAnimation()
        anim.run(1f)
        anim.advance(frame, show = false, focusTarget = 0f)
        assertTrue("fading out, not cut: ${anim.opacity}", anim.opacity in 0.5f..0.99f)
        anim.run(FADE_OUT_SECONDS + frame, show = false)
        assertEquals(0f, anim.opacity, 0f)
    }

    @Test
    fun `focus eases towards its target`() {
        val anim = PlaneRevealAnimation()
        anim.advance(frame, show = true, focusTarget = 1f)
        assertTrue(anim.focus > 0f && anim.focus < 1f)
        anim.run(FOCUS_SECONDS + frame, focusTarget = 1f)
        assertEquals(1f, anim.focus, 0f)
    }

    // ── Reveal ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a new plane is revealed by a front that reaches its edge within SCAN_IN_DURATION_MS`() {
        val anim = PlaneRevealAnimation()
        anim.targetRadius = 2f
        anim.advance(frame, show = true, focusTarget = 0f)
        assertTrue("the front starts at the centre: ${anim.revealRadius}", anim.revealRadius < 0.1f)
        assertTrue("the front is on screen: ${anim.scanProgress}", anim.scanProgress < 1f)

        anim.run(PlaneVisualizer.SCAN_IN_DURATION_MS / 1000f)
        assertEquals(2f, anim.revealRadius, REVEAL_CAUGHT_UP_M)

        anim.run(FRONT_GLOW_SECONDS + 2 * frame)
        assertEquals("once caught up the shader skips the front", 1f, anim.scanProgress, 0f)
    }

    @Test
    fun `a plane that grows gets its new area revealed, not popped in`() {
        val anim = PlaneRevealAnimation()
        anim.targetRadius = 1f
        anim.run(2f)
        assertEquals(1f, anim.scanProgress, 0f)

        anim.targetRadius = 1.5f
        anim.advance(frame, show = true, focusTarget = 0f)
        assertTrue("the front is back on screen: ${anim.scanProgress}", anim.scanProgress < 1f)
        assertTrue("it starts from the old edge: ${anim.revealRadius}", anim.revealRadius in 1f..1.1f)
        anim.run(1f)
        assertEquals(1.5f, anim.revealRadius, 0f)
    }

    @Test
    fun `a small extension is covered at no less than the minimum speed`() {
        val anim = PlaneRevealAnimation()
        anim.targetRadius = 1f
        anim.run(2f)
        anim.targetRadius = 1.1f
        anim.run(0.1f / MIN_REVEAL_SPEED_M_PER_S + frame)
        assertEquals(1.1f, anim.revealRadius, 0f)
    }

    @Test
    fun `a plane that shrinks is clipped to its new size at once`() {
        val anim = PlaneRevealAnimation()
        anim.targetRadius = 2f
        anim.run(2f)
        anim.targetRadius = 1f
        anim.advance(frame, show = true, focusTarget = 0f)
        assertEquals(1f, anim.revealRadius, 0f)
        assertTrue(anim.scanProgress >= 0.99f)
    }

    @Test
    fun `scanProgress stays in the unit range throughout`() {
        val anim = PlaneRevealAnimation()
        anim.targetRadius = 3f
        repeat(200) {
            anim.advance(frame, show = true, focusTarget = 1f)
            assertTrue(anim.scanProgress in 0f..1f)
        }
    }

    // ── computeScanRadius ───────────────────────────────────────────────────────────

    @Test
    fun `scan radius for a square 2x2 polygon centred at origin is sqrt(2)`() {
        // ARCore polygon convention: interleaved (x, z) in plane-local space, the
        // origin sits at the centroid. A 2×2 square has corners at (±1, ±1); the
        // furthest corner is at distance √2 ≈ 1.4142. This is the brief's exact
        // example case (PR #3 row in the plan).
        val polygon = floatBufferOf(
            -1f, -1f,
            1f, -1f,
            1f, 1f,
            -1f, 1f,
        )
        val expected = kotlin.math.sqrt(2f)
        assertEquals(expected, computeScanRadius(polygon), 1e-5f)
    }

    @Test
    fun `scan radius for an empty polygon is 0`() {
        // 0-vertex buffer → degenerate plane (no boundary yet). The helper returns
        // 0 so the shader's `scanRadius * scanProgress` collapses to 0 and the
        // ring never appears for a plane that hasn't yet grown a boundary.
        val polygon = FloatBuffer.allocate(0)
        assertEquals(0f, computeScanRadius(polygon), 0f)
    }

    @Test
    fun `scan radius for a single-vertex polygon equals that vertex distance`() {
        // Pathological but valid: a single (x, z) sample at (3, 4) → distance 5.
        // The helper must not crash and must produce the right scalar.
        val polygon = floatBufferOf(3f, 4f)
        assertEquals(5f, computeScanRadius(polygon), 1e-5f)
    }

    @Test
    fun `scan radius restores the FloatBuffer position`() {
        // ARCore reuses the polygon FloatBuffer across frames — the helper must
        // leave the buffer's position unchanged so the caller can keep using it.
        val polygon = floatBufferOf(-2f, 0f, 0f, 3f, 2f, 0f)
        polygon.position(2) // simulate a partially-consumed buffer
        computeScanRadius(polygon)
        assertEquals(2, polygon.position())
    }

    @Test
    fun `scan radius for an L-shaped polygon picks the outer corner`() {
        // A non-convex L: 6 corners, the outermost is (3, 3) → √18 ≈ 4.2426.
        // Verifies we walk every vertex and take the max — not the first / centroid.
        val polygon = floatBufferOf(
            0f, 0f,
            3f, 0f,
            3f, 3f,
            1f, 3f,
            1f, 1f,
            0f, 1f,
        )
        val expected = kotlin.math.sqrt(18f)
        assertEquals(expected, computeScanRadius(polygon), 1e-5f)
    }

    private fun floatBufferOf(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                rewind()
            }
}
