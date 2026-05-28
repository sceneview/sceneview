package io.github.sceneview.math

import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.inverse
import dev.romainguy.kotlin.math.normalize
import dev.romainguy.kotlin.math.rotation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies the direct-quaternion [worldToLocalQuaternion] / [localToWorldQuaternion]
 * overloads (#2267) produce the same result as the legacy Mat4-decomposition path,
 * without running a polar decomposition.
 */
class WorldQuaternionConversionTest {

    private val epsilon = 1e-6f

    /**
     * Quaternions `q` and `-q` represent the same rotation. Compare on the rotation
     * they encode by taking the absolute dot product (1.0 ⇒ identical orientation).
     */
    private fun assertSameRotation(expected: Quaternion, actual: Quaternion, msg: String = "") {
        val e = normalize(expected)
        val a = normalize(actual)
        val cos = abs(dot(e, a))
        assertTrue(cos > 1f - epsilon, "$msg expected $expected but got $actual (|cos|=$cos)")
    }

    /** Several non-identity parent rotations to exercise the conversion. */
    private val parentRotations = listOf(
        Quaternion.fromEuler(Rotation(0f, 90f, 0f)),
        Quaternion.fromEuler(Rotation(30f, 45f, 60f)),
        Quaternion.fromEuler(Rotation(-120f, 15f, 200f)),
        Quaternion.fromEuler(Rotation(89f, -33f, 12f)),
        Quaternion.fromEuler(Rotation(180f, 0f, 0f))
    )

    private val sampleLocals = listOf(
        Quaternion(),
        Quaternion.fromEuler(Rotation(10f, 20f, 30f)),
        Quaternion.fromEuler(Rotation(-45f, 90f, 0f))
    )

    @Test
    fun worldToLocalDirectMatchesMat4Path() {
        for (parent in parentRotations) {
            // Legacy path: decompose the parent's world matrix, then invert that rotation.
            val parentWorldTransform = rotation(parent)
            for (world in sampleLocals) {
                @Suppress("DEPRECATION")
                val viaMat4 = worldToLocalQuaternion(world, inverse(parentWorldTransform))
                val viaDirect = worldToLocalQuaternion(
                    parentWorldQuaternion = parent,
                    worldQuaternion = world
                )
                assertSameRotation(viaMat4, viaDirect, "worldToLocal parent=$parent world=$world")
            }
        }
    }

    @Test
    fun localToWorldDirectMatchesMat4Path() {
        for (parent in parentRotations) {
            val parentWorldTransform = rotation(parent)
            for (local in sampleLocals) {
                @Suppress("DEPRECATION")
                val viaMat4 = localToWorldQuaternion(local, parentWorldTransform)
                val viaDirect = localToWorldQuaternion(
                    parentWorldQuaternion = parent,
                    localQuaternion = local
                )
                assertSameRotation(viaMat4, viaDirect, "localToWorld parent=$parent local=$local")
            }
        }
    }

    @Test
    fun worldLocalRoundTrip() {
        for (parent in parentRotations) {
            for (local in sampleLocals) {
                val world = localToWorldQuaternion(
                    parentWorldQuaternion = parent,
                    localQuaternion = local
                )
                val backToLocal = worldToLocalQuaternion(
                    parentWorldQuaternion = parent,
                    worldQuaternion = world
                )
                assertSameRotation(local, backToLocal, "roundTrip parent=$parent local=$local")
            }
        }
    }

    @Test
    fun rotationOverloadsMatchQuaternionOverloads() {
        for (parent in parentRotations) {
            val worldRotation = Rotation(15f, -40f, 80f)
            val viaRotation = worldToLocalRotation(parent, worldRotation)
            val viaQuaternion = worldToLocalQuaternion(
                parentWorldQuaternion = parent,
                worldQuaternion = worldRotation.toQuaternion()
            ).toRotation()
            assertSameRotation(
                viaRotation.toQuaternion(),
                viaQuaternion.toQuaternion(),
                "worldToLocalRotation parent=$parent"
            )

            val localRotation = Rotation(5f, 25f, -50f)
            val worldViaRotation = localToWorldRotation(parent, localRotation)
            val worldViaQuaternion = localToWorldQuaternion(
                parentWorldQuaternion = parent,
                localQuaternion = localRotation.toQuaternion()
            ).toRotation()
            assertSameRotation(
                worldViaRotation.toQuaternion(),
                worldViaQuaternion.toQuaternion(),
                "localToWorldRotation parent=$parent"
            )
        }
    }
}
