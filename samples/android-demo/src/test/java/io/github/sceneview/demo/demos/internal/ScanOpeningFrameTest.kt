package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

class ScanOpeningFrameTest {
    @Test
    fun `room and object fill three quarters of the shorter side throughout the opening`() {
        val boxes = listOf(
            floatArrayOf(-2f, 0f, -1.5f, 2f, 2.5f, 1.5f), // room shell
            floatArrayOf(-2f, 0f, -1.5f, 2f, 1.2f, 1.5f), // cut dollhouse
            floatArrayOf(-0.15f, 0f, -0.1f, 0.15f, 0.5f, 0.1f), // object
            floatArrayOf(-5f, 0f, -0.8f, 5f, 2.5f, 0.8f), // long room
        )
        for (box in boxes) for (aspect in listOf(0.45f, 1f, 2.22f)) {
            for (fov in listOf(35.0, 46.4, 65.0)) for (yaw in listOf(0f, 27f)) {
                val frame = ScanResultFrame(yaw, box, 0f, 1f)
                for (step in 0..10) {
                    val angle = 28f + ScanResultTurntable.angleAt(step * 0.25f)
                    val pose = ScanOpeningFrame.pose(frame, angle, 38f, fov, aspect)
                    val fill = projectedFill(frame, pose, fov, aspect)
                    assertEquals("box=${box.toList()} aspect=$aspect angle=$angle fov=$fov", 0.75, fill, 1e-4)
                    assertEquals(frame.worldCentre.first, pose.target.x, 1e-5f)
                    assertEquals(frame.worldCentre.second, pose.target.z, 1e-5f)
                    assertEquals((box[1] + box[4]) / 2f, pose.target.y, 1e-5f)
                }
            }
        }
    }

    @Test
    fun `flat and empty bounds produce finite poses`() {
        for (box in listOf(FloatArray(6), floatArrayOf(-2f, 0f, -2f, 2f, 0f, 2f))) {
            val pose = ScanOpeningFrame.pose(ScanResultFrame.unturned(box), 0f, 38f, 46.4, 0.45f)
            assertTrue(pose.distance.isFinite())
            assertTrue(pose.distance >= ArDebugFraming.MIN_DISTANCE)
        }
    }

    @Test
    fun `two percent bounds exclude far points on every axis without altering input`() {
        val core = ArrayList<Float>()
        repeat(125) {
            for (corner in 0..7) core += listOf(
                if (corner and 1 == 0) -2f else 2f,
                if (corner and 2 == 0) 0f else 3f,
                if (corner and 4 == 0) -1f else 1f,
            )
        }
        // Horizontal wall normals keep the box axes fixed; each tail contains < 2% strays.
        repeat(10) { core += listOf(-100f, -100f, -100f, 100f, 100f, 100f) }
        val points = core.toFloatArray()
        val before = points.copyOf()
        val normals = FloatArray(points.size) { if (it % 3 == 0) 1f else 0f }
        val frame = requireNotNull(ScanResultFraming.of(points, normals))
        assertArrayEquals(floatArrayOf(-2f, 0f, -1f, 2f, 3f, 1f), frame.local, 1e-5f)
        assertArrayEquals(before, points, 0f)
    }

    @Test
    fun `quarter turn is eased and ends exactly on the reset heading`() {
        assertEquals(-90f, ScanResultTurntable.angleAt(0f), 0f)
        assertEquals(-45f, ScanResultTurntable.angleAt(1.25f), 1e-5f)
        assertEquals(0f, ScanResultTurntable.angleAt(2.5f), 0f)
        assertEquals(0f, ScanResultTurntable.angleAt(10f), 0f)
        val startStep = ScanResultTurntable.angleAt(0.01f) + 90f
        val middleStep = ScanResultTurntable.angleAt(1.26f) - ScanResultTurntable.angleAt(1.25f)
        val endStep = -ScanResultTurntable.angleAt(2.49f)
        assertTrue(startStep < middleStep / 20f)
        assertTrue(endStep < middleStep / 20f)
        for (step in 0..249) {
            assertTrue(ScanResultTurntable.angleAt(step / 100f) <= ScanResultTurntable.angleAt((step + 1) / 100f))
        }
    }

    @Test
    fun `first presented frame starts the clock and completion never loops`() {
        val turn = ScanResultTurntable()
        turn.advanceTo(9_000_000_000L)
        assertEquals(-90f, turn.angle, 0f)
        turn.advanceTo(10_250_000_000L)
        assertEquals(-45f, turn.angle, 1e-5f)
        turn.advanceTo(11_500_000_000L)
        assertFalse(turn.running)
        assertEquals(0f, turn.angle, 0f)
        turn.advanceTo(99_000_000_000L)
        assertEquals(0f, turn.angle, 0f)
    }

    @Test
    fun `cancellation or reduced motion before starting cannot restart the clock`() {
        for (started in listOf(false, true)) {
            val turn = ScanResultTurntable()
            if (started) {
                turn.advanceTo(1_000_000_000L)
                turn.advanceTo(2_000_000_000L)
            }
            turn.stop()
            turn.advanceTo(3_000_000_000L)
            assertFalse(turn.running)
            assertEquals(0f, turn.angle, 0f)
        }
    }

    @Test
    fun `orbit endpoint and reset use exactly the same pose`() {
        val frame = ScanResultFrame(27f, floatArrayOf(-2f, 0f, -1.5f, 2f, 2.5f, 1.5f), 90f, -1f)
        val angle = frame.frontDegrees + frame.turn * ScanResultFraming.THREE_QUARTER
        val reset = ScanOpeningFrame.pose(frame, angle, 38f, 46.4, 0.45f)
        val end = ScanOpeningFrame.pose(frame, angle + ScanResultTurntable.angleAt(2.5f), 38f, 46.4, 0.45f)
        assertEquals(reset, end)
    }

    @Test
    fun `touch hands off the current pose and reset returns to the tight endpoint`() {
        val frame = ScanResultFrame(0f, floatArrayOf(-2f, 0f, -1.5f, 2f, 2.5f, 1.5f), 90f, 1f)
        val reset = ScanOpeningFrame.pose(frame, 90f, 38f, 46.4, 0.45f)
        val midway = ScanOpeningFrame.pose(frame, 45f, 38f, 46.4, 0.45f)
        val orbit = ArDebugOrbitCamera(drift = false)
        val turn = ScanResultTurntable()
        orbit.followTo(midway)
        orbit.snapTo(midway)
        turn.stop()
        orbit.hold()
        turn.advanceTo(9_000_000_000L)
        orbit.update(0.016f)
        assertEquals(midway, orbit.pose)
        assertFalse(orbit.following)
        orbit.home = reset
        orbit.frontAzimuth = reset.azimuthDegrees
        orbit.recenter()
        repeat(600) { orbit.update(0.016f) }
        assertEquals(reset, orbit.pose)
        assertFalse(turn.running)
    }

    /** Independently project world-space corners through the camera's basis into pixel ratios. */
    private fun projectedFill(frame: ScanResultFrame, pose: OrbitPose, fov: Double, aspect: Float): Double {
        val eye = CameraRig.eye(pose)
        val yaw = Math.toRadians(frame.yawDegrees.toDouble())
        val azimuth = Math.toRadians(pose.azimuthDegrees.toDouble())
        val elevation = Math.toRadians(pose.elevationDegrees.toDouble())
        val tanV = tan(Math.toRadians(fov / 2.0))
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (corner in 0..7) {
            val b = frame.local
            val a = b[if (corner and 1 == 0) 0 else 3].toDouble()
            val c = b[if (corner and 4 == 0) 2 else 5].toDouble()
            val x = a * cos(yaw) - c * sin(yaw) - eye.x
            val y = b[if (corner and 2 == 0) 1 else 4] - eye.y
            val z = a * sin(yaw) + c * cos(yaw) - eye.z
            val depth = -x * sin(azimuth) * cos(elevation) - y * sin(elevation) - z * cos(azimuth) * cos(elevation)
            assertTrue("corner in front of lens", depth > 0.0)
            xs += (x * cos(azimuth) - z * sin(azimuth)) / (depth * tanV * aspect)
            ys += (-x * sin(azimuth) * sin(elevation) + y * cos(elevation) - z * cos(azimuth) * sin(elevation)) / (depth * tanV)
        }
        assertTrue("box inside viewport", xs.all { it in -1.0..1.0 } && ys.all { it in -1.0..1.0 })
        return max((xs.max() - xs.min()) * aspect, ys.max() - ys.min()) / (2.0 * min(aspect, 1f))
    }
}
