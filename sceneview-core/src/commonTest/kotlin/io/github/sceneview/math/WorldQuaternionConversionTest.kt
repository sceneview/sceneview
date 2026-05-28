package io.github.sceneview.math

import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.inverse
import dev.romainguy.kotlin.math.normalize
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves the direct-Quaternion overloads of [worldToLocalQuaternion] /
 * [localToWorldQuaternion] (and their Euler [worldToLocalRotation] /
 * [localToWorldRotation] cousins) produce the **same** result as the legacy
 * [Transform]-based overloads — the ones that run a polar decomposition on the
 * matrix every call.
 *
 * Quaternions are compared via `|dot| ≈ 1` to tolerate the sign double-cover
 * (`q` and `-q` represent the same rotation, and the Mat4→quaternion path may
 * pick the opposite hemisphere).
 */
class WorldQuaternionConversionTest {

    private val tolerance = 1e-6f

    /** `q` and `-q` are the same rotation, so compare via `|dot| ≈ 1`. */
    private fun assertSameRotation(expected: Quaternion, actual: Quaternion, msg: String = "") {
        val d = abs(dot(normalize(expected), normalize(actual)))
        assertTrue(abs(1f - d) < tolerance, "$msg |dot|=$d expected≈1 ($expected vs $actual)")
    }

    // A spread of non-trivial parent / child rotations covering several axes.
    private val parents = listOf(
        Quaternion.fromAxisAngle(Rotation(0f, 1f, 0f), 90f),
        Quaternion.fromAxisAngle(Rotation(1f, 0f, 0f), 37f),
        Quaternion.fromAxisAngle(Rotation(0.3f, 0.6f, 0.74f), 123f),
        Quaternion.fromEuler(Rotation(15f, -47f, 88f))
    )
    private val children = listOf(
        Quaternion(),
        Quaternion.fromAxisAngle(Rotation(0f, 0f, 1f), 45f),
        Quaternion.fromEuler(Rotation(-22f, 60f, 12f)),
        Quaternion.fromAxisAngle(Rotation(0.5f, 0.5f, 0.71f), -200f)
    )

    @Test
    fun directWorldToLocalQuaternionMatchesMatrixPath() {
        for (parent in parents) {
            // The matrix path: parent's world transform is rotation-only.
            val parentWorldTransform = Transform(quaternion = parent)
            val worldToLocal = inverse(parentWorldTransform)
            for (child in children) {
                val direct = worldToLocalQuaternion(parent, child)
                val viaMatrix = worldToLocalQuaternion(child, worldToLocal)
                assertSameRotation(viaMatrix, direct, "worldToLocal parent=$parent child=$child")
            }
        }
    }

    @Test
    fun directLocalToWorldQuaternionMatchesMatrixPath() {
        for (parent in parents) {
            val parentWorldTransform = Transform(quaternion = parent)
            for (child in children) {
                val direct = localToWorldQuaternion(parent, child)
                val viaMatrix = localToWorldQuaternion(child, parentWorldTransform)
                assertSameRotation(viaMatrix, direct, "localToWorld parent=$parent child=$child")
            }
        }
    }

    @Test
    fun directRotationOverloadsMatchMatrixPath() {
        for (parent in parents) {
            val parentWorldTransform = Transform(quaternion = parent)
            val worldToLocal = inverse(parentWorldTransform)
            for (child in children) {
                val childEuler = child.toEulerAngles()

                val directLocal = worldToLocalRotation(parent, childEuler)
                val matrixLocal = worldToLocalRotation(childEuler, worldToLocal)
                assertSameRotation(
                    matrixLocal.toQuaternion(),
                    directLocal.toQuaternion(),
                    "worldToLocalRotation parent=$parent child=$childEuler"
                )

                val directWorld = localToWorldRotation(parent, childEuler)
                val matrixWorld = localToWorldRotation(childEuler, parentWorldTransform)
                assertSameRotation(
                    matrixWorld.toQuaternion(),
                    directWorld.toQuaternion(),
                    "localToWorldRotation parent=$parent child=$childEuler"
                )
            }
        }
    }

    @Test
    fun directOverloadsRoundTrip() {
        for (parent in parents) {
            for (child in children) {
                val world = localToWorldQuaternion(parent, child)
                val backToLocal = worldToLocalQuaternion(parent, world)
                assertSameRotation(child, backToLocal, "round-trip parent=$parent child=$child")
            }
        }
    }
}
