package io.github.sceneview.node

import dev.romainguy.kotlin.math.Quaternion
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
 * Pins the one Euler convention shared by [Node.rotation] and [Node.worldRotation] (#3745).
 *
 * A [Node] needs a native Filament engine, so the JVM cannot build one. What it can call is
 * [decomposeWorld], the production function `Node.refreshWorldCache()` fills `worldRotation` from:
 * every world reading below goes through it, with the world matrix Filament would hand back
 * (for a node with no parent, the local matrix; under a parent, `parent * local`).
 *
 * Counter-test, run when this was written: with `rotation = world.rotation` put back in
 * [decomposeWorld], all five tests go red on their assertions — `expected:<30.0> but
 * was:<-30.000006>` for the pure yaw. `NodeRotationConventionInstrumentedTest` asks the same
 * question of real nodes, on a device.
 */
class NodeRotationConventionTest {

    /** What `Node.worldRotation` reads for a node whose world matrix is [world]. */
    private fun worldRotationOf(world: Transform): Rotation = decomposeWorld(world).rotation

    /** World matrix of a node with no parent whose `rotation` was set to [written]. */
    private fun rootWorld(written: Rotation, scale: Scale = Scale(1f)): Transform =
        Transform(Position(1f, 2f, 3f), Quaternion.fromEuler(written), scale)

    /** Angle, in degrees, between two orientations (sign-insensitive). */
    private fun angleBetween(a: Quaternion, b: Quaternion): Double =
        Math.toDegrees(2.0 * acos(min(1.0, abs(dot(a, b)).toDouble())))

    private fun assertRotationEquals(message: String, expected: Rotation, actual: Rotation) {
        assertEquals("$message — x of $actual", expected.x, actual.x, EPS)
        assertEquals("$message — y of $actual", expected.y, actual.y, EPS)
        assertEquals("$message — z of $actual", expected.z, actual.z, EPS)
    }

    @Test
    fun `a root node reads through worldRotation the angles written to rotation`() {
        // The issue's headline: `rotation = Rotation(y = 30f)` read `worldRotation.y == -30f`.
        CASES.forEach { written ->
            assertRotationEquals("written $written", written, worldRotationOf(rootWorld(written)))
        }
    }

    @Test
    fun `worldRotation is the Euler reading of worldQuaternion, like rotation is of quaternion`() {
        CASES.forEach { written ->
            val decomposed = decomposeWorld(rootWorld(written))

            assertRotationEquals(
                "written $written",
                decomposed.quaternion.toEulerAngles(),
                decomposed.rotation,
            )
        }
    }

    @Test
    fun `scale does not change what worldRotation reads`() {
        CASES.forEach { written ->
            assertRotationEquals(
                "written $written under a non-uniform scale",
                written,
                worldRotationOf(rootWorld(written, Scale(2f, 3f, 0.5f))),
            )
        }
    }

    @Test
    fun `writing worldRotation back to itself keeps the orientation`() {
        // The setter is `worldQuaternion = Quaternion.fromEuler(value)`. With the two conventions,
        // `node.worldRotation = node.worldRotation` turned the compound case by 64 degrees.
        // Yaws past 90 degrees read as an equivalent triple, so compare orientations, not angles.
        (CASES + BEYOND_QUARTER_TURN).forEach { written ->
            val original = Quaternion.fromEuler(written)
            val readBack = worldRotationOf(rootWorld(written))

            val drift = angleBetween(original, Quaternion.fromEuler(readBack))
            assertTrue(
                "$written read $readBack, which rebuilds an orientation $drift degrees away",
                drift < ANGLE_TOLERANCE_DEG,
            )
        }
    }

    @Test
    fun `a child reads the composition of its parent and its own rotation`() {
        val parent = Transform(
            Position(0f, 1f, 0f),
            Quaternion.fromEuler(Rotation(x = -12f, y = 35f, z = 8f)),
            Scale(1f),
        )

        CASES.forEach { local ->
            val localQuaternion = Quaternion.fromEuler(local)
            val world = parent * Transform(Position(), localQuaternion, Scale(1f))
            val expected = decomposeWorld(parent).quaternion * localQuaternion

            val drift = angleBetween(expected, Quaternion.fromEuler(worldRotationOf(world)))
            assertTrue(
                "child $local read ${worldRotationOf(world)}, $drift degrees off its world orientation",
                drift < ANGLE_TOLERANCE_DEG,
            )
        }
    }

    private companion object {
        const val EPS = 1e-3f
        const val ANGLE_TOLERANCE_DEG = 0.1

        /** Y within ±90 degrees: the range in which ZYX Euler angles read back as written. */
        val CASES = listOf(
            Rotation(y = 30f),
            Rotation(y = -75f),
            Rotation(x = 20f),
            Rotation(z = 15f),
            Rotation(x = 20f, y = 30f, z = 15f),
            Rotation(x = 10f, y = 45f),
            Rotation(y = 45f, z = 10f),
        )

        val BEYOND_QUARTER_TURN = listOf(
            Rotation(y = 120f),
            Rotation(y = -135f),
            Rotation(y = 179.9f),
            Rotation(x = 20f, y = 150f, z = 15f),
        )
    }
}
