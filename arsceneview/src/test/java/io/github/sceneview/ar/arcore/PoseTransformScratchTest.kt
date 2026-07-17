package io.github.sceneview.ar.arcore

import io.github.sceneview.math.toColumnsFloatArray
import io.github.sceneview.math.toTransform
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Pins the math safety behind the [Pose.transform] per-thread-scratch fix (#2406, umbrella #2263).
 *
 * `Pose.transform` used to allocate a fresh `FloatArray(16)` on every access; the fix reuses a
 * per-thread scratch buffer ([poseMatrixScratch]) instead. A real ARCore [com.google.ar.core.Pose] is
 * JNI-backed and cannot be instantiated in a JVM runner (see [PoseNodeWriteTest]), so this test pins
 * the two invariants the fix RELIES ON, exercising the exact `FloatArray.toTransform()` production
 * code with the column-major floats ARCore's `Pose.toMatrix` would write:
 *
 * 1. **Value-equivalence** — converting a buffer of 16 floats yields a [io.github.sceneview.math.Transform]
 *    whose columns are exactly those floats, so the scratch-reuse path returns the same value the old
 *    fresh-allocation path did.
 * 2. **No aliasing across reuse** — `toTransform()` COPIES the floats into the returned matrix's own
 *    columns, so overwriting the scratch buffer for a later access does NOT corrupt a transform
 *    returned by an earlier access. This is the property that makes reusing one buffer safe.
 */
class PoseTransformScratchTest {

    // Two distinct rigid-ish matrices, column-major (the layout Pose.toMatrix writes).
    private val matrixA = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        10f, 20f, 30f, 1f,
    )
    private val matrixB = floatArrayOf(
        0f, 1f, 0f, 0f,
        -1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f,
        -5f, 7f, -9f, 1f,
    )

    @Test
    fun `toTransform yields columns equal to the source floats`() {
        val t = matrixA.toTransform()
        assertArrayEquals(
            "the converted transform must carry exactly the source column floats",
            matrixA, t.toColumnsFloatArray(), 0f,
        )
    }

    @Test
    fun `reusing one scratch buffer does not corrupt an earlier returned transform`() {
        // Simulate the production hot path: a single reusable buffer (poseMatrixScratch's per-thread
        // array) filled by toMatrix, then converted, then refilled and converted again.
        val scratch = FloatArray(16)

        matrixA.copyInto(scratch)
        val first = scratch.toTransform()

        // Refill the SAME buffer for the next access (what the next Pose.transform read does).
        matrixB.copyInto(scratch)
        val second = scratch.toTransform()

        // The first transform must be untouched — toTransform copied, it did not alias the buffer.
        assertArrayEquals(
            "an earlier transform must survive the buffer being reused for a later access",
            matrixA, first.toColumnsFloatArray(), 0f,
        )
        assertArrayEquals(
            "the later transform must reflect the refilled buffer",
            matrixB, second.toColumnsFloatArray(), 0f,
        )
    }
}
