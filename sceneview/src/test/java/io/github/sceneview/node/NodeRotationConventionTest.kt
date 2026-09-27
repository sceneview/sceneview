package io.github.sceneview.node

import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.RotationsOrder
import dev.romainguy.kotlin.math.dot
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min

/**
 * Pins the Euler conventions of [Node.rotation] and [Node.worldRotation] for a node with no
 * parent (#3745).
 *
 * A [Node] needs a native Filament engine, so this reproduces the two getters' exact expressions
 * on the JVM:
 *
 * - `rotation` reads `quaternion.toEulerAngles()` — ZYX, the same order its setter
 *   (`Quaternion.fromEuler`) writes, so it reads back what was written.
 * - `worldRotation` reads `world.rotation`, the kotlin-math `Mat4.rotation` decomposition of the
 *   world matrix. For a root node the world matrix is the local one,
 *   `Transform(position, quaternion, scale)`, so the two getters see the same orientation.
 *
 * Measured result: they only agree for a pure X or pure Z rotation. `Mat4.rotation` returns
 * YXZ-order angles with the Y (yaw) sign negated, so a pure yaw of 30° reads back as -30° and a
 * compound rotation reads back as different numbers altogether. Its setter goes through
 * `Quaternion.fromEuler` (ZYX), so `node.worldRotation = node.worldRotation` turns a root node.
 *
 * Major version 4 is frozen, so this test documents the current behaviour rather than changing it.
 * If either getter is ever aligned (5.0), this test is the one expected to change.
 */
class NodeRotationConventionTest {

    /** `Node.rotation` getter for a node whose local quaternion was set from [written]. */
    private fun localRead(written: Rotation): Rotation = Quaternion.fromEuler(written).toEulerAngles()

    /** `Node.worldRotation` getter for a root node whose local quaternion was set from [written]. */
    private fun rootWorldRead(written: Rotation): Rotation =
        Transform(Position(), Quaternion.fromEuler(written), Scale(1f)).rotation

    /** Angle, in degrees, between two orientations (sign-insensitive). */
    private fun angleBetween(a: Quaternion, b: Quaternion): Double =
        Math.toDegrees(2.0 * acos(min(1.0, abs(dot(a, b)).toDouble())))

    private fun assertRotationEquals(expected: Rotation, actual: Rotation) {
        assertEquals("x of $actual", expected.x, actual.x, EPS)
        assertEquals("y of $actual", expected.y, actual.y, EPS)
        assertEquals("z of $actual", expected.z, actual.z, EPS)
    }

    @Test
    fun `rotation reads back what was written`() {
        CASES.forEach { written -> assertRotationEquals(written, localRead(written)) }
    }

    @Test
    fun `root worldRotation agrees with rotation for a pure pitch or roll`() {
        listOf(Rotation(x = 20f), Rotation(z = 15f)).forEach { written ->
            assertRotationEquals(localRead(written), rootWorldRead(written))
        }
    }

    @Test
    fun `root worldRotation negates a pure yaw`() {
        val written = Rotation(y = 30f)

        assertRotationEquals(Rotation(y = 30f), localRead(written))
        assertRotationEquals(Rotation(y = -30f), rootWorldRead(written))
    }

    @Test
    fun `root worldRotation and rotation disagree on a compound rotation`() {
        val written = Rotation(x = 20f, y = 30f, z = 15f)

        assertRotationEquals(written, localRead(written))
        // Measured 2026-09-27: (12.05, -33.68, 13.25).
        assertRotationEquals(Rotation(x = 12.049748f, y = -33.681595f, z = 13.249608f), rootWorldRead(written))
    }

    @Test
    fun `root worldRotation is YXZ order with the yaw sign negated`() {
        CASES.forEach { written ->
            val world = rootWorldRead(written)
            val rebuilt = Quaternion.fromEuler(Rotation(world.x, -world.y, world.z), RotationsOrder.YXZ)
            assertTrue(
                "$written read back as $world, which is not YXZ with negated yaw",
                angleBetween(Quaternion.fromEuler(written), rebuilt) < ANGLE_TOLERANCE_DEG
            )
        }
    }

    @Test
    fun `writing a root node's worldRotation back to itself turns it`() {
        // The setter is `worldQuaternion = Quaternion.fromEuler(value)` (ZYX), so feeding it the
        // getter's YXZ, yaw-negated angles lands on another orientation.
        val written = Rotation(x = 20f, y = 30f, z = 15f)
        val before = Quaternion.fromEuler(written)
        val after = Quaternion.fromEuler(rootWorldRead(written))

        assertTrue(angleBetween(before, after) > 60.0)
    }

    private companion object {
        const val EPS = 1e-3f

        /** Float `acos` near 1 costs a few hundredths of a degree on its own. */
        const val ANGLE_TOLERANCE_DEG = 0.1

        val CASES = listOf(
            Rotation(y = 30f),
            Rotation(x = 20f),
            Rotation(z = 15f),
            Rotation(x = 20f, y = 30f, z = 15f),
            Rotation(x = 10f, y = 45f),
            Rotation(y = 45f, z = 10f),
        )
    }
}
