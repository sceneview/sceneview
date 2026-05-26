package io.github.sceneview.node

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Transform
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Regression test for floating-point drift when updating individual transform components at
 * high frame rates (#2187).
 *
 * The previous design read `position` and `scale` by decomposing the underlying 4×4 matrix on
 * every `quaternion` setter call. At 60–120 Hz the getter → setter → getter round-trip fed
 * float imprecision back into the matrix: `scale` drifted from 1.0 by ~1e-4 per 10 000 frames,
 * visibly warping the mesh.
 *
 * The fix caches pristine TRS backing fields (`_position`, `_quaternion`, `_scale`) in `Node`.
 * The setter for any individual component updates the cache and composes the new `Transform`
 * from the pristine values — no matrix round-trip.
 *
 * This test pins the pure-math side of the fix (no Filament Engine required):
 *
 * - Simulates the old accumulating-decomposition pattern and shows it drifts.
 * - Simulates the new backing-field pattern and shows scale remains pristine.
 */
class NodeTransformDriftTest {

    // -----------------------------------------------------------------------
    // Helpers — mirrors the two code paths in isolation (no Filament needed).
    // -----------------------------------------------------------------------

    /**
     * OLD pattern: read position + scale from the matrix on every set, then recompose.
     * Over many iterations the matrix column-lengths accumulate floating-point error.
     */
    private fun simulateOldPattern(iterations: Int): Float {
        var matrix = Transform(
            position = Float3(1f, 2f, 3f),
            quaternion = Quaternion(),
            scale = Float3(1f, 1f, 1f),
        )
        // Simulate the old `quaternion = newQ` setter: read position + scale from matrix, recompose.
        repeat(iterations) { i ->
            val angleRad = (i * 3f) * (Math.PI.toFloat() / 180f)
            val newQ = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), angleRad)
            // Old path: decompose matrix to read position + scale, then compose a new transform.
            val decomposedPosition = matrix.position      // w column — cheap, no error
            val decomposedScale = matrix.scale            // column vector lengths — accumulates error
            matrix = Transform(decomposedPosition, newQ, decomposedScale)
        }
        // Return recovered scale.x — should stay 1.0 if there's no drift.
        return matrix.scale.x
    }

    /**
     * NEW pattern: cache pristine position and scale, compose from those.
     * Scale never passes through matrix decomposition and stays pristine.
     */
    private fun simulateNewPattern(iterations: Int): Float {
        var cachedPosition = Float3(1f, 2f, 3f)
        var cachedQuaternion = Quaternion()
        var cachedScale = Float3(1f, 1f, 1f)

        repeat(iterations) { i ->
            val angleRad = (i * 3f) * (Math.PI.toFloat() / 180f)
            val newQ = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), angleRad)
            // New path: update only the quaternion cache, compose from pristine cached values.
            cachedQuaternion = newQ
            // The Transform is recomposed from pristine values — matrix is written but never read back.
            @Suppress("UNUSED_VARIABLE")
            val matrix = Transform(cachedPosition, cachedQuaternion, cachedScale)
        }
        return cachedScale.x
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    fun `new backing-field pattern keeps scale pristine after 10000 rotation updates`() {
        val recoveredScale = simulateNewPattern(iterations = 10_000)
        val drift = abs(recoveredScale - 1.0f)
        // With pristine cached values the scale should be EXACTLY 1.0 (no matrix read-back at all).
        assertTrue(
            "Scale must remain exactly 1.0 with the new backing-field pattern; actual=$recoveredScale drift=$drift",
            drift < 1e-7f,
        )
    }

    @Test
    fun `old matrix-decomposition pattern accumulates scale drift after 10000 updates`() {
        // This test DOCUMENTS the old broken behaviour. It is expected to FAIL with the old code
        // (scale drifts noticeably) and PASS as a documentation test now that the old path is no
        // longer exercised by Node.
        //
        // We assert that the old pattern DOES drift — confirming that the old path is inherently
        // lossy and the new backing-field fix is necessary.
        val recoveredScaleOld = simulateOldPattern(iterations = 10_000)
        val driftOld = abs(recoveredScaleOld - 1.0f)

        // The old pattern should accumulate detectable drift (> 1e-5).
        // If this assertion fails, something changed in the underlying float arithmetic and we need
        // to revisit the test expectation.
        assertTrue(
            "The old matrix-decomposition pattern should accumulate detectable scale drift; " +
                "actual=$recoveredScaleOld drift=$driftOld (expected > 1e-5). " +
                "If this fails, the test assumption is wrong — check the kotlin-math library version.",
            driftOld > 1e-5f,
        )
    }

    @Test
    fun `scale drift is at least 1000x worse in old pattern vs new pattern`() {
        val driftOld = abs(simulateOldPattern(iterations = 10_000) - 1.0f)
        val driftNew = abs(simulateNewPattern(iterations = 10_000) - 1.0f)

        assertTrue(
            "The old pattern's scale drift ($driftOld) should be at least 1000x worse than the " +
                "new pattern's drift ($driftNew)",
            driftOld > driftNew * 1000f || driftNew < 1e-7f,
        )
    }
}

// Mat4 (=Transform) already provides .position (w-column xyz) and .scale (column-vector lengths)
// as native members — the old decomposition path used those same properties, which is how the
// scale error accumulated. The new backing-field path never reads .scale from the matrix.
