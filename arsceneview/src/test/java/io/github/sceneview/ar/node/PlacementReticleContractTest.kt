package io.github.sceneview.ar.node

import dev.romainguy.kotlin.math.Quaternion
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless contract tests for `PlacementReticleNode` (#2241 Sprint-1, PR 4).
 *
 * Behavioural tests on [ReticleOrientationSmoother], the pure Depth Lab `OrientedReticle`
 * damping implementation (no ARCore or Filament needed).
 */
class PlacementReticleContractTest {

    // ── ReticleOrientationSmoother — behaviour ───────────────────────────────────────────

    private val identity = Quaternion(0f, 0f, 0f, 1f)

    /** 90° around Y. */
    private val target = Quaternion(0f, sqrt(0.5f).toFloat(), 0f, sqrt(0.5f).toFloat())

    private fun angleDegreesTo(q: Quaternion, other: Quaternion): Float {
        val dot = abs(q.x * other.x + q.y * other.y + q.z * other.z + q.w * other.w)
            .coerceIn(0.0f, 1.0f)
        return Math.toDegrees(2.0 * Math.acos(dot.toDouble())).toFloat()
    }

    @Test
    fun `first sample is applied verbatim - no roll-in from identity`() {
        val smoother = ReticleOrientationSmoother(smoothing = 0.75f)
        val out = smoother.smooth(target)
        assertEquals(0.0f, angleDegreesTo(out, target), 0.1f)
    }

    @Test
    fun `subsequent samples converge exponentially toward the target`() {
        val smoother = ReticleOrientationSmoother(smoothing = 0.75f)
        smoother.smooth(identity)
        val step1 = smoother.smooth(target)
        // One 0.75 slerp step across 90° leaves ~22.5° of error.
        assertEquals(22.5f, angleDegreesTo(step1, target), 0.5f)
        val step2 = smoother.smooth(target)
        // Each further step damps the remaining error by 4x.
        assertEquals(5.6f, angleDegreesTo(step2, target), 0.5f)
        assertTrue(
            "smoothing must be monotonic",
            angleDegreesTo(step2, target) < angleDegreesTo(step1, target)
        )
    }

    @Test
    fun `smoothing = 1 applies the raw orientation`() {
        val smoother = ReticleOrientationSmoother(smoothing = 1.0f)
        smoother.smooth(identity)
        val out = smoother.smooth(target)
        assertEquals(0.0f, angleDegreesTo(out, target), 0.1f)
    }

    @Test
    fun `reset drops the damping state - next sample verbatim`() {
        val smoother = ReticleOrientationSmoother(smoothing = 0.75f)
        smoother.smooth(identity)
        smoother.reset()
        val out = smoother.smooth(target)
        assertEquals(0.0f, angleDegreesTo(out, target), 0.1f)
    }

    @Test
    fun `smoothing values outside 0-1 are coerced`() {
        assertEquals(1.0f, ReticleOrientationSmoother(5.0f).smoothing, 0.0f)
        assertEquals(0.0f, ReticleOrientationSmoother(-1.0f).smoothing, 0.0f)
        val smoother = ReticleOrientationSmoother(0.5f)
        smoother.smoothing = 2.0f
        assertEquals(1.0f, smoother.smoothing, 0.0f)
    }

}
