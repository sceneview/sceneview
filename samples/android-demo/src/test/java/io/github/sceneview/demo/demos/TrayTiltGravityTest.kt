package io.github.sceneview.demo.demos

import io.github.sceneview.node.PhysicsBody
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM cover for the `animation-physics` tray tilt (#3621).
 *
 * The tray is a pivot node rotated by `(pitch, 0, roll)`; the simulation keeps its flat floor and
 * axis-aligned rails and only ever sees gravity expressed in that rotated frame. These tests pin
 * the three properties the demo depends on: flat is unchanged, the magnitude is conserved, and
 * each axis leans the way the finger does.
 */
class TrayTiltGravityTest {

    private val epsilon = 0.01f

    private fun magnitude(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)

    @Test
    fun flatTrayKeepsGravityStraightDown() {
        val g = trayLocalGravity(0f, 0f)
        assertEquals(0f, g.x, epsilon)
        assertEquals(PhysicsBody.GRAVITY, g.y, epsilon)
        assertEquals(0f, g.z, epsilon)
    }

    @Test
    fun tiltingOnlyRotatesGravityNeverRescalesIt() {
        for (pitch in listOf(-20f, -7f, 0f, 3f, 20f)) {
            for (roll in listOf(-20f, -12f, 0f, 9f, 20f)) {
                val g = trayLocalGravity(pitch, roll)
                assertEquals(
                    "pitch=$pitch roll=$roll must not change the strength of gravity",
                    -PhysicsBody.GRAVITY,
                    magnitude(g.x, g.y, g.z),
                    epsilon,
                )
            }
        }
    }

    @Test
    fun positivePitchPushesTowardsTheNearEdge() {
        // Dragging down tips the +Z (viewer-facing) edge down, so in the tray's frame gravity
        // gains a +Z component and the balls run towards the camera.
        val g = trayLocalGravity(15f, 0f)
        assertTrue("Pitch should push along +Z, was ${g.z}", g.z > 0f)
        assertEquals(0f, g.x, epsilon)
        assertTrue("The tray is still mostly under the balls", g.y < 0f)
    }

    @Test
    fun positiveRollPushesTowardsNegativeX() {
        // Positive roll lifts the +X edge, so downhill is -X. The drag handler subtracts the
        // horizontal drag for exactly this reason: swiping right must roll the balls right.
        val g = trayLocalGravity(0f, 15f)
        assertTrue("Roll should push along -X, was ${g.x}", g.x < 0f)
        assertEquals(0f, g.z, epsilon)
    }

    @Test
    fun twentyDegreesIsAboutATenthOfGravitySideways() {
        // Sanity-check the slope actually moves a ball: sin(20°) * 9.8 ≈ 3.35 m/s², which crosses
        // the 1.6 m tray in well under a second.
        val g = trayLocalGravity(20f, 0f)
        assertEquals(3.35f, g.z, 0.05f)
    }
}
