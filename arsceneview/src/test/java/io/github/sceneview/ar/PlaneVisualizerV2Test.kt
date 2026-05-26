package io.github.sceneview.ar

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [PlaneVisualizerV2]'s pure-JVM helper math — the scan-in / reflection
 * fade-in time curve and the polygon scan-radius computation. Both helpers are
 * `internal` top-level functions deliberately so they can be exercised without
 * spinning up a Filament Engine, an ARCore Session, or a real Plane (see #2203 PR #3).
 *
 * Robolectric isn't required for these — `computeScanProgress`, `computeReflectionFadeIn`
 * and `computeScanRadius` are pure functions over primitives + a [FloatBuffer], so the
 * tests run on plain JUnit + the JVM.
 */
class PlaneVisualizerV2Test {

    // ── computeScanProgress / computeReflectionFadeIn ───────────────────────────────

    @Test
    fun `scanProgress is 0 at the moment of first detection`() {
        assertEquals(0f, computeScanProgress(0L), 0f)
    }

    @Test
    fun `scanProgress is 05 at half the scan-in duration`() {
        val halfDurationNanos = (PlaneVisualizerV2.SCAN_IN_DURATION_MS / 2L) * 1_000_000L
        assertEquals(0.5f, computeScanProgress(halfDurationNanos), 1e-4f)
    }

    @Test
    fun `scanProgress is 10 at exactly the scan-in duration`() {
        val fullDurationNanos = PlaneVisualizerV2.SCAN_IN_DURATION_MS * 1_000_000L
        assertEquals(1f, computeScanProgress(fullDurationNanos), 1e-4f)
    }

    @Test
    fun `scanProgress stays clamped at 10 well past the scan-in duration`() {
        // 1.5 s after detection — well past the 800 ms scan-in window. Verifies
        // the coerceIn so the shader never sees `scanProgress > 1`, which would
        // walk the ring off the polygon and produce a frame of garbage.
        val pastNanos = 1_500L * 1_000_000L
        assertEquals(1f, computeScanProgress(pastNanos), 0f)
    }

    @Test
    fun `scanProgress clamps negative elapsed to 0`() {
        // Defensive: System.nanoTime is monotonic in practice but the helper must
        // never feed a negative ring radius to the shader. `-100 ms` → 0.
        assertEquals(0f, computeScanProgress(-100_000_000L), 0f)
    }

    @Test
    fun `reflectionFadeIn is 0 at the moment of first detection`() {
        assertEquals(0f, computeReflectionFadeIn(0L), 0f)
    }

    @Test
    fun `reflectionFadeIn is 04 at 400ms`() {
        val nanos = 400L * 1_000_000L
        assertEquals(0.4f, computeReflectionFadeIn(nanos), 1e-4f)
    }

    @Test
    fun `reflectionFadeIn is 10 at exactly the fade-in duration`() {
        val nanos = PlaneVisualizerV2.REFLECTION_FADE_IN_MS * 1_000_000L
        assertEquals(1f, computeReflectionFadeIn(nanos), 1e-4f)
    }

    @Test
    fun `reflectionFadeIn stays clamped at 10 well past the fade-in duration`() {
        val pastNanos = 1_500L * 1_000_000L
        assertEquals(1f, computeReflectionFadeIn(pastNanos), 0f)
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
