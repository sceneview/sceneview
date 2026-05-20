package io.github.sceneview.ar.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/**
 * Pure-JVM regression suite for [computeARFogFactor], the reference
 * implementation of the shader-side fog factor in
 * `arsceneview/src/main/materials/camera_stream_depth.mat` (issue #1717).
 *
 * The shader operates on a per-pixel depth value from ARCore. Keeping a
 * Kotlin mirror lets us pin the formula behaviour without spinning up
 * Filament — depth → factor is pure math and easy to fuzz.
 */
class ARFogNodeTest {

    private val tolerance = 1e-4f

    @Test
    fun `factor is zero when disabled regardless of depth`() {
        assertEquals(0f, computeARFogFactor(5f, 0.1f, 0f, 30f, enabled = false), 0f)
        assertEquals(0f, computeARFogFactor(100f, 1f, 0f, 30f, enabled = false), 0f)
    }

    @Test
    fun `factor is zero when depth is invalid (no ARCore depth available)`() {
        // ARCore returns depth_mm = 0 for pixels with no depth. We treat
        // anything below 1 mm as invalid to avoid tinting the passthrough on
        // regions the depth API can't see.
        assertEquals(0f, computeARFogFactor(0f, 0.1f, 0f, 30f), 0f)
        assertEquals(0f, computeARFogFactor(-1f, 0.1f, 0f, 30f), 0f)
        assertEquals(0f, computeARFogFactor(0.0005f, 0.1f, 0f, 30f), 0f)
    }

    @Test
    fun `factor is zero at the start distance`() {
        assertEquals(0f, computeARFogFactor(2f, 0.1f, 2f, 30f), tolerance)
    }

    @Test
    fun `factor is zero before the start distance`() {
        // Surfaces closer than start stay crisp.
        assertEquals(0f, computeARFogFactor(1f, 0.5f, 2f, 30f), tolerance)
    }

    @Test
    fun `factor follows exponential falloff after start`() {
        // f = 1 - exp(-density * (depth - start))
        val depth = 5f
        val density = 0.2f
        val start = 1f
        val expected = 1f - exp(-density * (depth - start))
        assertEquals(expected, computeARFogFactor(depth, density, start, 30f), tolerance)
    }

    @Test
    fun `factor saturates at the end distance`() {
        // Past `end`, the (depth - start) term is clamped, so the factor
        // freezes at its end-of-volume value. With density 1 and a 10 m
        // range, exp(-10) ≈ 4.5e-5 so the factor is effectively 1.
        val nearEnd = computeARFogFactor(end = 11f, depthMeters = 11f, density = 1f, start = 1f)
        val pastEnd = computeARFogFactor(end = 11f, depthMeters = 50f, density = 1f, start = 1f)
        assertEquals(nearEnd, pastEnd, tolerance)
        assertTrue("Factor at end should be near 1, was $nearEnd", nearEnd > 0.999f)
    }

    @Test
    fun `factor is monotonic non-decreasing with depth`() {
        // Random-but-deterministic sweep along the depth axis.
        var previous = -1f
        for (i in 0..100) {
            val depth = i * 0.3f
            val factor = computeARFogFactor(depth, density = 0.1f, start = 0f, end = 50f)
            assertTrue(
                "Factor decreased at depth=$depth: $previous -> $factor",
                factor >= previous - tolerance
            )
            previous = factor
        }
    }

    @Test
    fun `factor stays in zero-to-one range under extreme inputs`() {
        val cases = listOf(
            floatArrayOf(1f, 1f, 0f, 1f),
            floatArrayOf(1000f, 1f, 0f, 30f),
            floatArrayOf(0.001f, 0.0001f, 0f, 30f),
            floatArrayOf(5f, 1f, 4f, 5f), // end equals depth exactly
            floatArrayOf(5f, 1f, 10f, 20f), // start past depth
        )
        for ((depth, density, start, end) in cases.map { Quad(it[0], it[1], it[2], it[3]) }) {
            val factor = computeARFogFactor(depth, density, start, end)
            assertTrue(
                "Factor out of range for ($depth,$density,$start,$end): $factor",
                factor in 0f..1f
            )
        }
    }

    @Test
    fun `density coerced into supported range`() {
        // Negative density would otherwise blow up exp(); shader contract
        // clamps to [0, 1] and the helper mirrors that.
        val factor = computeARFogFactor(5f, -3f, 0f, 30f)
        assertEquals(0f, factor, tolerance)
        val saturated = computeARFogFactor(50f, 10f, 0f, 30f)
        assertEquals(1f, saturated, tolerance)
    }

    @Test
    fun `end below start collapses safely to a tiny volume`() {
        // Bogus config — start > end. The shader guards with
        // max(end - start, 0.001); the Kotlin mirror matches so a buggy
        // caller never gets NaN / Inf.
        val factor = computeARFogFactor(5f, 0.1f, 10f, 5f)
        assertTrue("Factor must be finite for inverted range: $factor", factor.isFinite())
        assertTrue(factor in 0f..1f)
    }

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)
}
