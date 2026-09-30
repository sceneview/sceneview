package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.TrayMotion.Tilt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** The gesture → tilt → physics chain of the `rolling-balls` board. */
class TrayMotionTest {

    private val epsilon = 1e-4f

    @Test
    fun everyMoveEventCounts() {
        // The old chain launched a coroutine per move and read a stale value: two events in one
        // frame kept only the last delta. Accumulated synchronously, ten 10 px moves are 100 px.
        var target = Tilt.LEVEL
        repeat(10) { target = TrayMotion.dragTarget(target, dxPx = 0f, dyPx = 10f, cameraYawRadians = 0f) }
        assertEquals(100f * TrayMotion.DEGREES_PER_PIXEL, target.pitch, epsilon)
        assertEquals(0f, target.roll, epsilon)
    }

    @Test
    fun theBoardTipsFurtherThanBefore() {
        // Thomas: "on ne peut pas relever le plateau davantage". It used to stop at 20°.
        assertTrue(TrayMotion.MAX_TILT_DEGREES >= 35f)
        val far = TrayMotion.dragTarget(Tilt.LEVEL, dxPx = -5_000f, dyPx = 5_000f, cameraYawRadians = 0f)
        assertEquals(TrayMotion.MAX_TILT_DEGREES, far.pitch, epsilon)
        assertEquals(TrayMotion.MAX_TILT_DEGREES, far.roll, epsilon)
    }

    @Test
    fun aHalfScreenSwipeReachesMostOfTheRange() {
        val swipe = TrayMotion.dragTarget(Tilt.LEVEL, dxPx = 0f, dyPx = 450f, cameraYawRadians = 0f)
        assertTrue("was ${swipe.pitch}", swipe.pitch > 30f)
    }

    @Test
    fun theDragFollowsTheScreenWhereverTheCameraIs() {
        // From the opening shot, dragging right lowers the right side (+X): a negative roll.
        val front = TrayMotion.dragTarget(Tilt.LEVEL, dxPx = 100f, dyPx = 0f, cameraYawRadians = 0f)
        assertTrue(front.roll < 0f)
        assertEquals(0f, front.pitch, epsilon)
        // Orbited a quarter turn round to +X, the screen's right is the board's −Z: dragging right
        // now lowers −Z, which is a negative pitch, and leaves the roll alone.
        val side = TrayMotion.dragTarget(Tilt.LEVEL, dxPx = 100f, dyPx = 0f, cameraYawRadians = (PI / 2).toFloat())
        assertEquals(0f, side.roll, 1e-3f)
        assertTrue("was ${side.pitch}", side.pitch < 0f)
    }

    @Test
    fun cameraYawIsZeroInTheOpeningShot() {
        assertEquals(0f, TrayMotion.cameraYaw(eyeX = 0f, eyeZ = 3f, targetX = 0f, targetZ = 0f), epsilon)
        assertEquals((PI / 2).toFloat(), TrayMotion.cameraYaw(3f, 0f, 0f, 0f), epsilon)
    }

    @Test
    fun theBoardEasesTowardsTheTargetAndArrives() {
        var rendered = Tilt.LEVEL
        val target = Tilt(30f, -10f)
        rendered = TrayMotion.follow(rendered, target, 1f / 60f, TrayMotion.FOLLOW_SECONDS)
        // One 60 Hz frame covers 1 − e^(−1/3) ≈ 28 % of the way: no step, no lag either.
        assertEquals(30f * 0.2835f, rendered.pitch, 0.05f)
        repeat(60) { rendered = TrayMotion.follow(rendered, target, 1f / 60f, TrayMotion.FOLLOW_SECONDS) }
        assertEquals(target, rendered)
    }

    @Test
    fun easingDoesNotDependOnTheFrameRate() {
        val target = Tilt(20f, 0f)
        var at60 = Tilt.LEVEL
        repeat(6) { at60 = TrayMotion.follow(at60, target, 1f / 60f, TrayMotion.FOLLOW_SECONDS) }
        var at120 = Tilt.LEVEL
        repeat(12) { at120 = TrayMotion.follow(at120, target, 1f / 120f, TrayMotion.FOLLOW_SECONDS) }
        assertEquals(at60.pitch, at120.pitch, 0.01f)
    }

    @Test
    fun aBallOnASlopeRollsAtFiveSeventhsOfTheSlide() {
        // 35° slope along X, after a step that integrated the full gravity.
        val g = 9.8f
        val theta = 35f * PI.toFloat() / 180f
        val gx = g * sin(theta)
        val gy = -g * kotlin.math.cos(theta)
        val dt = 1f / 120f
        var vx = 0f
        var vz = 0f
        repeat(120) {
            vx += gx * dt
            val (rx, rz) = TrayMotion.roll(vx, vz, gx, gy, 0f, resistance = 0f, dtSeconds = dt)
            vx = rx
            vz = rz
        }
        // One second of rolling with no resistance, only the light drag: close to 5/7 g sinθ.
        val frictionless = 5f / 7f * gx
        assertTrue("was $vx, expected a bit under $frictionless", vx in frictionless * 0.8f..frictionless)
    }

    @Test
    fun aBallOnTheFlatComesToRest() {
        var vx = 1f
        var steps = 0
        while (vx != 0f && steps < 10_000) {
            vx = TrayMotion.roll(vx, 0f, 0f, -9.8f, 0f, resistance = 0.03f, dtSeconds = 1f / 120f).first
            steps++
        }
        assertEquals(0f, vx, 0f)
        assertTrue("took $steps steps", steps < 120 * 5)
    }

    @Test
    fun aBallDoesNotStartOnASlopeShallowerThanItsResistance() {
        // tan(0.3°) ≈ 0.005 < 0.008 (steel): the ball stays put rather than creeping.
        val theta = 0.3f * PI.toFloat() / 180f
        val gx = 9.8f * sin(theta)
        val (vx, _) = TrayMotion.roll(gx / 120f, 0f, gx, -9.8f, 0f, resistance = 0.008f, dtSeconds = 1f / 120f)
        assertEquals(0f, vx, 0f)
    }

    @Test
    fun rollingMeansOnTheFloorAndNotBouncing() {
        assertTrue(TrayMotion.isRolling(y = -0.425f, vy = 0.05f, radius = 0.075f, floorY = -0.5f))
        assertFalse(TrayMotion.isRolling(y = -0.3f, vy = 0f, radius = 0.075f, floorY = -0.5f))
        assertFalse(TrayMotion.isRolling(y = -0.425f, vy = 1.2f, radius = 0.075f, floorY = -0.5f))
    }
}
