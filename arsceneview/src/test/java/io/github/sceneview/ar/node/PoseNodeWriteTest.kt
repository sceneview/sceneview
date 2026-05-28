package io.github.sceneview.ar.node

import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.normalize
import dev.romainguy.kotlin.math.rotation
import dev.romainguy.kotlin.math.translation
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.math.toQuaternion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pins the math equivalence behind the [PoseNode.pose] / [ARCameraNode.pose] hot-path fix (#2266,
 * umbrella #2263).
 *
 * The previous setter routed through `worldTransform(pose.transform)`, where `Pose.transform`
 * allocated a fresh `FloatArray(16)`, ran ARCore's `toMatrix`, wrapped it in a [Transform], and the
 * world-transform setter then **decomposed that matrix straight back** into position + quaternion +
 * scale. The fix writes the translation + rotation components directly
 * (`worldPosition = pose.position; worldQuaternion = pose.quaternion`), skipping the matrix
 * round-trip entirely.
 *
 * A real ARCore [com.google.ar.core.Pose] is JNI-backed and cannot be instantiated in a JVM unit
 * runner (see [io.github.sceneview.ar.arcore.ArCoreExtensionsTest]). So this test pins the pure-math
 * invariants the fix relies on, using the same `translation(p) * rotation(q)` composition ARCore's
 * `toMatrix` produces for a rigid pose:
 *
 * - Decomposing the matrix yields the **same** translation + rotation the fix writes directly
 *   (so the two paths are visually equivalent — no anchor moves).
 * - A rigid pose carries **unit scale**, so the old matrix path forced node scale back to identity
 *   every frame, whereas the direct-write path leaves the node's scale untouched. This documents
 *   the intentional behaviour change.
 */
class PoseNodeWriteTest {

    /** Builds the rigid TRS matrix ARCore's `Pose.toMatrix` produces for translation [t] + rotation [q]. */
    private fun rigidPoseMatrix(t: Position, q: Quaternion): Transform =
        translation(t) * rotation(normalize(q))

    @Test
    fun `direct translation matches the w-column of the decomposed pose matrix`() {
        val t = Position(1.5f, -2.25f, 0.75f)
        val q = normalize(Quaternion(0.1f, 0.2f, 0.3f, 0.9f))

        val matrix = rigidPoseMatrix(t, q)

        // Old path read translation from the matrix w-column; the fix writes `t` directly.
        val decomposed = matrix.position
        assertEquals(t.x, decomposed.x, 1e-5f)
        assertEquals(t.y, decomposed.y, 1e-5f)
        assertEquals(t.z, decomposed.z, 1e-5f)
    }

    @Test
    fun `direct quaternion matches the rotation decomposed from the pose matrix`() {
        val t = Position(3f, 4f, 5f)
        val q = normalize(Quaternion(0.25f, -0.5f, 0.1f, 0.8f))

        val matrix = rigidPoseMatrix(t, q)

        // Old path: matrix.toQuaternion(). Fix: writes `q` directly. Quaternions q and -q represent
        // the same rotation, so compare via the absolute dot product (|q . decomposed| ~= 1).
        val decomposed = normalize(matrix.toQuaternion())
        val dot = q.x * decomposed.x + q.y * decomposed.y + q.z * decomposed.z + q.w * decomposed.w
        assertTrue(
            "Decomposed rotation must equal the directly-written quaternion (|dot|=${abs(dot)})",
            abs(abs(dot) - 1f) < 1e-4f,
        )
    }

    @Test
    fun `a rigid pose matrix carries unit scale`() {
        // The old `worldTransform(pose.transform)` set the full matrix, so its unit scale reset any
        // user-set node scale to identity every frame. The direct-write path leaves scale untouched.
        val matrix = rigidPoseMatrix(
            Position(10f, -3f, 7f),
            normalize(Quaternion(0.4f, 0.1f, -0.2f, 0.88f)),
        )
        val scale = matrix.scale
        assertEquals(1f, scale.x, 1e-4f)
        assertEquals(1f, scale.y, 1e-4f)
        assertEquals(1f, scale.z, 1e-4f)
    }
}
