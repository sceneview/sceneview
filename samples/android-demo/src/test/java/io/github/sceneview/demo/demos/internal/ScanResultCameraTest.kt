package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.length
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The scan result's one camera, rule by rule (`camera.md`, 2026-11-13). */
class ScanResultCameraTest {
    // A 4 × 3 m room, 1.2 m drawn, turned 20° off the world's axes, its front on +b.
    private val room = ScanResultFrame(
        yawDegrees = 20f,
        local = floatArrayOf(-2f, 0f, -1.5f, 2f, 1.2f, 1.5f),
        frontDegrees = 0f,
        turn = 1f,
    )
    private val insets = FramingInsets(top = 0.12f, bottom = 0.3f)
    private val aspect = 1080f / 2340f

    private fun shownCamera(frame: ScanResultFrame = room): Pair<ScanResultCamera, ScanResultStart> {
        val camera = ScanResultCamera()
        camera.setViewport(1080, 2340)
        val start = ScanResultStart.of(frame, camera.verticalFovDegrees, aspect, insets)
        camera.aim(start)
        repeat(ScanResultCamera.STEADY_FRAMES) { camera.frameDrawn(start, ready = true) }
        assertTrue(camera.shown)
        return camera to start
    }

    private fun ScanResultCamera.drag(dx: Int, dy: Int, steps: Int = 10) {
        stopInertia()
        grabBegin(500, 1000, strafe = false)
        for (i in 1..steps) {
            grabUpdate(500 + dx * i / steps, 1000 + dy * i / steps)
            update(FRAME)
        }
        grabEnd()
    }

    private fun ScanResultCamera.pinch(from: Float, to: Float, steps: Int = 10) {
        stopInertia()
        scrollBegin(500, 1000, from)
        var previous = from
        for (i in 1..steps) {
            val current = from + (to - from) * i / steps
            scrollUpdate(500, 1000, previous, current)
            update(FRAME)
            previous = current
        }
        grabEnd()
    }

    private fun ScanResultCamera.run(seconds: Float) {
        repeat((seconds / FRAME).toInt() + 1) { update(FRAME) }
    }

    @Test
    fun `the centre is the middle of the box, and the start is 35 degrees on a three-quarter of the front`() {
        val (camera, start) = shownCamera()
        val middle = room.worldMiddle
        assertEquals(middle, start.pose.target)
        assertEquals(0.6f, middle.y, 1e-5f)
        assertEquals(ScanResultCamera.START_ELEVATION, start.pose.elevationDegrees, 1e-4f)
        val front = room.toWorld(OrbitPose(azimuthDegrees = room.frontDegrees + ScanResultFraming.THREE_QUARTER)).azimuthDegrees
        assertEquals(0f, CameraRig.shortestDelta(front, start.pose.azimuthDegrees), 1e-3f)
        assertEquals(start.pose, camera.pose)
    }

    @Test
    fun `the start shows what the fit shows, the same eye with the centre still the pivot`() {
        val (camera, start) = shownCamera()
        val fitted = room.pose(ScanResultCamera.START_ELEVATION, ScanResultFraming.THREE_QUARTER, camera.verticalFovDegrees, aspect, insets)
        assertClose(CameraRig.eye(fitted), camera.eye, 1e-3f)
        assertEquals(fitted.distance, start.pose.distance, 1e-4f)
    }

    @Test
    fun `the view waits for the same start over a few frames, then never moves it`() {
        val camera = ScanResultCamera()
        camera.setViewport(1080, 2340)
        val first = ScanResultStart.of(room, camera.verticalFovDegrees, aspect, insets)
        val narrower = ScanResultStart.of(room, camera.verticalFovDegrees, aspect, insets.copy(bottom = 0.4f))
        assertFalse(camera.frameDrawn(first, ready = true))
        // The chrome is measured again: the count starts over on the new start.
        assertFalse(camera.frameDrawn(narrower, ready = true))
        assertFalse(camera.frameDrawn(narrower, ready = true))
        assertTrue(camera.frameDrawn(narrower, ready = true))
        assertTrue(camera.start === narrower)
        // Later starts — the model's own box, a card under it — change nothing.
        assertFalse(camera.frameDrawn(first, ready = true))
        camera.aim(first)
        assertTrue(camera.start === narrower)
        assertEquals(narrower.pose, camera.pose)
    }

    @Test
    fun `nothing moves without the finger`() {
        val (camera, start) = shownCamera()
        camera.run(10f)
        assertEquals(start.pose, camera.pose)
        assertFalse(camera.isFrameActive)
    }

    @Test
    fun `one finger turns about the fixed centre, all the way round`() {
        val (camera, start) = shownCamera()
        val metres = length(camera.eye - start.pose.target)
        camera.drag(dx = -1300, dy = 0)
        camera.run(1f)
        assertEquals(start.pose.target, camera.pose.target)
        assertTrue(abs(camera.pose.azimuthDegrees - start.pose.azimuthDegrees) > 300f)
        assertEquals(start.pose.elevationDegrees, camera.pose.elevationDegrees, 1e-3f)
        // The eye stays at the same distance from the centre as it turns.
        assertEquals(metres, length(camera.eye - start.pose.target), 1e-3f)
    }

    @Test
    fun `the height of view stays between 10 and 89 degrees`() {
        val (camera, _) = shownCamera()
        camera.drag(dx = 0, dy = -3000)
        camera.run(1f)
        assertEquals(ScanResultCamera.PLAN_ELEVATION, camera.pose.elevationDegrees, 1e-4f)
        camera.drag(dx = 0, dy = 3000)
        camera.run(1f)
        assertEquals(ScanResultCamera.MIN_ELEVATION, camera.pose.elevationDegrees, 1e-4f)
    }

    @Test
    fun `two fingers dragged together do not pan`() {
        val (camera, start) = shownCamera()
        camera.grabBegin(500, 1000, strafe = true)
        camera.grabUpdate(900, 1300)
        camera.update(FRAME)
        camera.grabEnd()
        camera.run(1f)
        assertEquals(start.pose, camera.pose)
    }

    @Test
    fun `a pinch zooms towards the centre, stops 15 cm outside the box and at three times the start`() {
        val (camera, start) = shownCamera()
        camera.pinch(from = 300f, to = 3000f)
        camera.pinch(from = 300f, to = 3000f)
        val near = start.minDistance(camera.pose.azimuthDegrees, camera.pose.elevationDegrees)
        assertEquals(near, camera.pose.distance, 1e-4f)
        assertEquals(start.pose.target, camera.pose.target)
        // No coast on the zoom: it stops dead where the fingers left it.
        val held = camera.pose
        camera.run(1f)
        assertEquals(held, camera.pose)
        camera.pinch(from = 3000f, to = 100f)
        camera.pinch(from = 3000f, to = 100f)
        assertEquals(start.pose.distance * ScanResultCamera.MAX_ZOOM_OUT, camera.pose.distance, 1e-3f)
    }

    @Test
    fun `the near bound is the box's own edge in the direction of the eye`() {
        val (_, start) = shownCamera()
        // Square on the room's long side, level: its half depth, 1.5 m, plus the clearance.
        val alongB = room.toWorld(OrbitPose(azimuthDegrees = 0f)).azimuthDegrees
        assertEquals(1.5f + ScanResultCamera.NEAR_CLEARANCE_M, start.minDistance(alongB, 0f), 1e-3f)
        // From straight above: half the height drawn.
        assertEquals(0.6f + ScanResultCamera.NEAR_CLEARANCE_M, start.minDistance(alongB, 90f), 1e-3f)
    }

    @Test
    fun `turning at the near bound keeps the eye out of the box`() {
        val (camera, start) = shownCamera()
        camera.pinch(from = 300f, to = 3000f)
        camera.pinch(from = 300f, to = 3000f)
        camera.drag(dx = 600, dy = 0)
        camera.run(1f)
        val near = start.minDistance(camera.pose.azimuthDegrees, camera.pose.elevationDegrees)
        assertTrue(camera.pose.distance >= near - 1e-4f)
    }

    @Test
    fun `a fling coasts a little, stops within half a second, and a touch stops it at once`() {
        val (camera, _) = shownCamera()
        camera.drag(dx = 400, dy = 0, steps = 4)
        val released = camera.pose.azimuthDegrees
        camera.update(FRAME)
        assertTrue("it coasts", camera.pose.azimuthDegrees != released)
        camera.run(0.5f)
        val rested = camera.pose.azimuthDegrees
        camera.update(FRAME)
        assertEquals(rested, camera.pose.azimuthDegrees, 0f)
        assertTrue("coasted ${abs(rested - released)}°", abs(rested - released) < 30f)

        camera.drag(dx = 400, dy = 0, steps = 4)
        camera.stopInertia()
        val touched = camera.pose
        camera.run(0.5f)
        assertEquals(touched, camera.pose)
    }

    @Test
    fun `a finger held still before it lifts throws nothing`() {
        val (camera, _) = shownCamera()
        camera.grabBegin(500, 1000, strafe = false)
        for (i in 1..5) {
            camera.grabUpdate(500 + i * 60, 1000)
            camera.update(FRAME)
        }
        repeat(10) {
            camera.grabUpdate(800, 1000)
            camera.update(FRAME)
        }
        camera.grabEnd()
        val lifted = camera.pose
        camera.run(0.5f)
        assertEquals(lifted.azimuthDegrees, camera.pose.azimuthDegrees, 0.5f)
    }

    @Test
    fun `a double tap flies back to the start in 0_4 s, the centre unchanged, and a two-finger tap does nothing`() {
        val (camera, start) = shownCamera()
        camera.drag(dx = 500, dy = -300)
        camera.pinch(from = 300f, to = 900f)
        camera.run(1f)
        val moved = camera.pose
        camera.doubleTapZoom(0, 0, zoomIn = false)
        camera.run(1f)
        assertEquals(moved, camera.pose)

        camera.doubleTapZoom(0, 0, zoomIn = true)
        assertTrue(camera.moving)
        repeat((ScanResultCamera.FLY_SECONDS / FRAME).toInt() - 1) {
            camera.update(FRAME)
            assertEquals(start.pose.target, camera.pose.target)
        }
        camera.update(FRAME)
        camera.update(FRAME)
        assertEquals(start.pose, camera.pose)
        assertFalse(camera.moving)
    }

    @Test
    fun `a touch cuts a fly short where it is`() {
        val (camera, _) = shownCamera()
        camera.drag(dx = 500, dy = 0)
        camera.run(1f)
        camera.recenter()
        camera.update(FRAME)
        camera.update(FRAME)
        camera.stopInertia()
        val cut = camera.pose
        camera.run(1f)
        assertEquals(cut, camera.pose)
    }

    @Test
    fun `plan flies to 89 degrees at the same centre, heading and distance, and model flies back where it was`() {
        val (camera, start) = shownCamera()
        camera.drag(dx = 300, dy = 100)
        camera.pinch(from = 300f, to = 500f)
        camera.run(1f)
        val before = camera.pose
        camera.showPlan(true)
        assertTrue(camera.plan)
        camera.run(ScanResultCamera.FLY_SECONDS + 0.1f)
        assertEquals(before.copy(elevationDegrees = ScanResultCamera.PLAN_ELEVATION), camera.pose)
        assertEquals(start.pose.target, camera.pose.target)
        // The finger keeps its gestures on the plan.
        camera.drag(dx = 200, dy = 0)
        camera.run(1f)
        assertTrue(camera.pose.azimuthDegrees != before.azimuthDegrees)
        camera.showPlan(false)
        camera.run(ScanResultCamera.FLY_SECONDS + 0.1f)
        assertEquals(before, camera.pose)
        assertFalse(camera.plan)
    }

    @Test
    fun `a double tap on the plan comes back to the start seen from above`() {
        val (camera, start) = shownCamera()
        camera.showPlan(true)
        camera.run(1f)
        camera.drag(dx = 300, dy = 0)
        camera.run(1f)
        camera.recenter()
        camera.run(1f)
        assertEquals(start.plan, camera.pose)
    }

    @Test
    fun `the film flies back to the start, sweeps 40 degrees each way in 8 s, and stops there`() {
        val (camera, start) = shownCamera()
        camera.drag(dx = 300, dy = 0)
        camera.run(1f)
        camera.startFilm()
        assertTrue(camera.filming)
        assertFalse(camera.sweeping)
        camera.run(ScanResultCamera.FLY_SECONDS + 0.05f)
        assertTrue(camera.sweeping)
        var widest = 0f
        var steps = 0
        while (camera.sweeping && steps < 1000) {
            camera.update(FRAME)
            widest = maxOf(widest, abs(CameraRig.shortestDelta(start.pose.azimuthDegrees, camera.pose.azimuthDegrees)))
            assertEquals(start.pose.elevationDegrees, camera.pose.elevationDegrees, 1e-4f)
            assertEquals(start.pose.distance, camera.pose.distance, 1e-4f)
            steps++
        }
        assertEquals(ResultFilm.SWING_DEGREES, widest, 0.5f)
        assertEquals(ResultFilm.SECONDS, steps * FRAME, 0.1f)
        assertEquals(start.pose, camera.pose)
        assertEquals(1, camera.filmsLanded)
        assertFalse(camera.filming)
    }

    @Test
    fun `a touch interrupts the film, which does not land`() {
        val (camera, _) = shownCamera()
        camera.startFilm()
        camera.run(2f)
        assertTrue(camera.sweeping)
        camera.stopInertia()
        assertFalse(camera.filming)
        camera.run(8f)
        assertEquals(0, camera.filmsLanded)
    }

    @Test
    fun `gestures before the view is shown do nothing`() {
        val camera = ScanResultCamera()
        camera.setViewport(1080, 2340)
        val start = ScanResultStart.of(room, camera.verticalFovDegrees, aspect, insets)
        camera.aim(start)
        camera.drag(dx = 500, dy = 0)
        camera.pinch(from = 300f, to = 900f)
        camera.doubleTapZoom(0, 0, zoomIn = true)
        assertEquals(start.pose, camera.pose)
        assertFalse(camera.shown)
    }

    @Test
    fun `the centre stands on one point of the screen at every angle and distance`() {
        val (camera, start) = shownCamera()
        val atStart = screenPoint(camera, start.pose.target)
        camera.drag(dx = 700, dy = -400)
        camera.pinch(from = 300f, to = 700f)
        camera.run(1f)
        val after = screenPoint(camera, start.pose.target)
        assertEquals(atStart.first, after.first, 1e-3f)
        assertEquals(atStart.second, after.second, 1e-3f)
    }

    /** Where [point] lands in [camera]'s picture, as shares of its half width and half height. */
    private fun screenPoint(camera: ScanResultCamera, point: Position): Pair<Float, Float> {
        val eye = camera.eye
        // The camera looks along its own orbit's line: the pose's target as moved by the shift.
        val target = camera.pose.target + (eye - CameraRig.eye(camera.pose))
        val forward = dev.romainguy.kotlin.math.normalize(target - eye)
        val right = dev.romainguy.kotlin.math.normalize(dev.romainguy.kotlin.math.cross(forward, Float3(0f, 1f, 0f)))
        val up = dev.romainguy.kotlin.math.cross(right, forward)
        val to = point - eye
        val depth = dev.romainguy.kotlin.math.dot(to, forward)
        val tanV = kotlin.math.tan(Math.toRadians(camera.verticalFovDegrees / 2.0)).toFloat()
        return dev.romainguy.kotlin.math.dot(to, right) / (depth * tanV * camera.aspect) to
            dev.romainguy.kotlin.math.dot(to, up) / (depth * tanV)
    }

    private fun assertClose(expected: Position, actual: Position, delta: Float) {
        assertEquals(expected.x, actual.x, delta)
        assertEquals(expected.y, actual.y, delta)
        assertEquals(expected.z, actual.z, delta)
    }

    private companion object {
        const val FRAME = 1f / 60f
    }
}
