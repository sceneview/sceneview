package io.github.sceneview.demo.demos

import dev.romainguy.kotlin.math.length
import io.github.sceneview.demo.BandLens
import io.github.sceneview.demo.fitOrbitRadius
import io.github.sceneview.demo.pendulumCeiling
import io.github.sceneview.demo.pendulumSwingExtent
import io.github.sceneview.math.Position
import io.github.sceneview.physics.DoublePendulum
import io.github.sceneview.physics.DoublePendulumLink
import io.github.sceneview.physics.DoublePendulumState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Double Pendulum's home shot (#4326): what the camera is fitted for upright and on a phone
 * held sideways, checked against the simulation the demo really steps and the stand it really
 * draws.
 */
class DoublePendulumHomeShotTest {

    private val lengths1 = listOf(
        PENDULUM_LENGTH_1_RANGE.start, PENDULUM_DEFAULT_LENGTH_1, PENDULUM_LENGTH_1_RANGE.endInclusive,
    )
    private val lengths2 = listOf(
        PENDULUM_LENGTH_2_RANGE.start, PENDULUM_DEFAULT_LENGTH_2, PENDULUM_LENGTH_2_RANGE.endInclusive,
    )
    private val gravities = listOf(
        PENDULUM_GRAVITY_RANGE.start, PENDULUM_DEFAULT_GRAVITY, PENDULUM_GRAVITY_RANGE.endInclusive,
    )

    // The band above the Release pill on a Pixel 7a held sideways, and narrower strips.
    private val restingAspects = listOf(7.3f, 4f, 2f)

    private fun ceiling(length1: Float, length2: Float) = pendulumCeiling(
        length1 = length1,
        length2 = length2,
        mass1 = PENDULUM_JOINT_MASS,
        mass2 = PENDULUM_TIP_MASS,
        releaseAngle1 = PENDULUM_RELEASE_ANGLE_1,
        releaseAngle2 = PENDULUM_RELEASE_ANGLE_2,
        jointBobRadius = JOINT_BOB_RADIUS,
        tipBobRadius = TIP_BOB_RADIUS,
    )

    /** The state the demo seeds on Release and on every slider change. */
    private fun released(length1: Float, length2: Float, gravity: Float) = DoublePendulumState(
        link1 = DoublePendulumLink(length = length1, mass = PENDULUM_JOINT_MASS, angle = PENDULUM_RELEASE_ANGLE_1),
        link2 = DoublePendulumLink(length = length2, mass = PENDULUM_TIP_MASS, angle = PENDULUM_RELEASE_ANGLE_2),
        pivot = Position(0f, PIVOT_HEIGHT, 0f),
        gravity = gravity,
        damping = PENDULUM_DAMPING,
    )

    /** Highest point drawn above the hinge over [seconds] of the demo's own stepping. */
    private fun highestDrawn(start: DoublePendulumState, framesPerSecond: Int, seconds: Int = 60): Float {
        var state = start
        var top = Float.NEGATIVE_INFINITY
        repeat(framesPerSecond * seconds) {
            top = maxOf(top, state.joint.y + JOINT_BOB_RADIUS, state.tip.y + TIP_BOB_RADIUS)
            state = DoublePendulum.step(state, 1f / framesPerSecond)
        }
        return top - PIVOT_HEIGHT
    }

    @Test
    fun `no pendulum the demo can release climbs past the ceiling its strip shot is fitted for`() {
        for (l1 in lengths1) for (l2 in lengths2) for (g in gravities) for (fps in listOf(30, 60, 120)) {
            val reached = highestDrawn(released(l1, l2, g), fps)
            assertTrue(
                "arms $l1 + $l2 m, gravity $g, $fps fps: drawn up to $reached m above the hinge, " +
                    "fitted for ${ceiling(l1, l2)}",
                reached <= ceiling(l1, l2),
            )
        }
    }

    @Test
    fun `at the default lengths the ceiling is close above what the swing reaches`() {
        val reached = highestDrawn(
            released(PENDULUM_DEFAULT_LENGTH_1, PENDULUM_DEFAULT_LENGTH_2, PENDULUM_DEFAULT_GRAVITY),
            framesPerSecond = 60,
        )
        val spare = ceiling(PENDULUM_DEFAULT_LENGTH_1, PENDULUM_DEFAULT_LENGTH_2) - reached
        // A bound, not a trajectory: it may leave air, but not the 0.44 m the whole disc did.
        assertTrue("spare height $spare m", spare in 0f..0.15f)
    }

    /** The outline of what can be drawn: the reachable disc, cut at the floor and at [top]. */
    private fun outline(reach: Float, top: Float): List<Position> =
        (0 until 360 step 5).flatMap { degrees ->
            val angle = Math.toRadians(degrees.toDouble())
            val x = (reach * sin(angle)).toFloat()
            val y = (PIVOT_HEIGHT + reach * cos(angle)).toFloat().coerceIn(0f, top)
            listOf(Position(x, y, -TIP_BOB_RADIUS), Position(x, y, TIP_BOB_RADIUS))
        }

    /** The eight corners of the stand's base plate, as the demo places it. */
    private fun basePlate(): List<Position> =
        listOf(-STAND_BASE_WIDTH / 2f, STAND_BASE_WIDTH / 2f).flatMap { x ->
            listOf(0f, STAND_BASE_HEIGHT).flatMap { y ->
                listOf(STAND_POST_Z - STAND_BASE_DEPTH / 2f, STAND_POST_Z + STAND_BASE_DEPTH / 2f).map { z ->
                    Position(x, y, z)
                }
            }
        }

    @Test
    fun `in a strip the stand's foot, the arms' reach and the ceiling are drawn inside the band`() {
        for (l1 in lengths1) for (l2 in lengths2) for (resting in restingAspects) {
            val shot = pendulumHomeShot(l1, l2, freeAspect = resting, strip = true)
            val reach = pendulumSwingExtent(l1, l2, TIP_BOB_RADIUS) / 2f
            val points = outline(reach, top = PIVOT_HEIGHT + ceiling(l1, l2)) + basePlate()
            // The fit is made once, for the band at rest; a sheet coming up only widens it.
            listOf(resting, resting * 1.5f, resting * 3f).forEach { aspect ->
                assertHeld(shot, points, aspect, "arms $l1 + $l2 m fitted for $resting")
            }
        }
    }

    /** Every one of [points] is drawn inside a band of [aspect] seen from [shot]. */
    private fun assertHeld(shot: PendulumHomeShot, points: List<Position>, aspect: Float, what: String) {
        val lens = BandLens(shot.eye, shot.target, focalLengthMm = 28.0, aspect = aspect)
        points.forEach { point ->
            assertTrue("$what, band at $aspect: $point is at ${lens.project(point)}", lens.holds(point))
        }
    }

    @Test
    fun `in a strip the default pendulum is drawn a sixth larger than a fit to the whole disc`() {
        val l1 = PENDULUM_DEFAULT_LENGTH_1
        val l2 = PENDULUM_DEFAULT_LENGTH_2
        val shot = pendulumHomeShot(l1, l2, freeAspect = 7.3f, strip = true)
        val swing = pendulumSwingExtent(l1, l2, TIP_BOB_RADIUS)
        // What #4326 first shipped: floor to the top of the disc, same lens, pitch and fill.
        val wholeDisc = fitOrbitRadius(
            extentX = swing,
            extentY = PIVOT_HEIGHT + swing / 2f,
            extentZ = TIP_BOB_RADIUS * 2f,
            aspect = 7.3f,
            elevationDegrees = PENDULUM_ELEVATION_DEGREES,
            fill = PENDULUM_STRIP_FRAME_FILL,
            azimuthInvariant = false,
        )
        val gain = wholeDisc / length(shot.eye - shot.target)
        assertTrue("drawn $gain times as large", gain >= 1.15f)
        // And the stand, floor to hinge, takes more than half of the band's height — under the
        // whole-disc fit it took less.
        val lens = BandLens(shot.eye, shot.target, focalLengthMm = 28.0, aspect = 7.3f)
        val post = lens.heightShare(Position(0f, 0f, 0f), Position(0f, PIVOT_HEIGHT, 0f))
        assertTrue("the stand spans $post of the band", post > 0.5)
        assertTrue("under the whole-disc fit it spanned ${post / gain}", post / gain < 0.5)
    }

    @Test
    fun `upright the shot aims at the hinge and holds the whole disc the arms can reach`() {
        for (l1 in lengths1) for (l2 in lengths2) for (aspect in listOf(0.45f, 0.6f, 1f, 1.6f)) {
            val shot = pendulumHomeShot(l1, l2, freeAspect = aspect, strip = false)
            assertEquals(Position(0f, PIVOT_HEIGHT, 0f), shot.target)
            val reach = pendulumSwingExtent(l1, l2, TIP_BOB_RADIUS) / 2f
            assertHeld(shot, outline(reach, top = PIVOT_HEIGHT + reach), aspect, "arms $l1 + $l2 m upright")
        }
    }

    @Test
    fun `the shot looks down at its target from in front of the swing plane`() {
        for (strip in listOf(false, true)) {
            val shot = pendulumHomeShot(
                PENDULUM_DEFAULT_LENGTH_1, PENDULUM_DEFAULT_LENGTH_2, freeAspect = 2f, strip = strip,
            )
            val toEye = shot.eye - shot.target
            assertEquals(0f, toEye.x, 0f)
            assertTrue(toEye.z > 0f)
            val elevation = Math.toDegrees(kotlin.math.atan2(toEye.y.toDouble(), toEye.z.toDouble()))
            assertEquals(PENDULUM_ELEVATION_DEGREES.toDouble(), elevation, 0.01)
        }
    }
}
