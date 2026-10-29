package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Pins the heading-up follow view of 3D Scan's live capture (#4628): the heading read off the
 * phone's pose, the view turned so that heading is the top of the screen, and the Recenter state
 * a drag leaves and a recenter restores.
 */
class ScanFollowTest {

    /** A phone at [position], turned [yawDegrees] about the vertical, pitched [pitchDegrees]. */
    private fun phone(yawDegrees: Float, pitchDegrees: Float = 0f, position: Float3 = Float3()): DebugPose {
        // q = yaw(Y) * pitch(X): ARCore's world is Y-up, the camera looks down its −Z.
        val yaw = Math.toRadians(yawDegrees.toDouble()) / 2
        val pitch = Math.toRadians(pitchDegrees.toDouble()) / 2
        val cy = cos(yaw).toFloat()
        val sy = sin(yaw).toFloat()
        val cp = cos(pitch).toFloat()
        val sp = sin(pitch).toFloat()
        return DebugPose(
            position.x, position.y, position.z,
            qx = cy * sp, qy = sy * cp, qz = -sy * sp, qw = cy * cp,
        )
    }

    private fun assertAngle(expected: Float, actual: Float?) {
        assertNotNull(actual)
        assertEquals(0f, CameraRig.shortestDelta(expected, actual!!), 1e-3f)
    }

    /** The orbit camera's horizontal forward, read off the transform it hands the renderer. */
    private fun viewForward(orbit: ArDebugOrbitCamera): Float3 {
        val m = orbit.getTransform()
        // A camera looks down its −Z: minus the third column.
        val f = Float3(-m.z.x, 0f, -m.z.z)
        return normalize(f)
    }

    @Test
    fun `a phone facing down -Z has heading 0, the azimuth an orbit camera looks down -Z at`() {
        assertAngle(0f, ScanFollow.headingOf(phone(0f)))
    }

    @Test
    fun `turning the phone left by 90 degrees turns the heading by 90 degrees`() {
        // Yaw +90° about Y turns −Z onto −X: the phone now faces −X.
        val (fx, _, fz) = ScanFollow.forwardOf(phone(90f))
        assertEquals(-1f, fx, 1e-4f)
        assertEquals(0f, fz, 1e-4f)
        assertAngle(90f, ScanFollow.headingOf(phone(90f)))
        assertAngle(-135f, ScanFollow.headingOf(phone(-135f)))
    }

    @Test
    fun `the heading ignores pitch, the phone tilted down at the floor still has its yaw`() {
        assertAngle(30f, ScanFollow.headingOf(phone(30f, pitchDegrees = -60f)))
        assertAngle(30f, ScanFollow.headingOf(phone(30f, pitchDegrees = 45f)))
    }

    @Test
    fun `a phone pointing straight down keeps the last heading instead of a noisy one`() {
        val down = phone(170f, pitchDegrees = -89f)
        assertEquals(42f, ScanFollow.headingOf(down, previous = 42f)!!, 0f)
        assertNull(ScanFollow.headingOf(down, previous = null))

        val follow = ScanFollow()
        assertNotNull(follow.poseFor(phone(20f)))
        val held = follow.poseFor(phone(170f, pitchDegrees = -89f))
        assertAngle(20f, held?.azimuthDegrees)
    }

    @Test
    fun `heading-up - the view looks the way the phone faces, whatever the yaw`() {
        for (yaw in listOf(0f, 37f, 90f, 180f, -100f)) {
            val orbit = ArDebugOrbitCamera(drift = false, band = ScanFollow.BAND)
            orbit.setViewport(1080, 2400)
            val pose = ScanFollow().poseFor(phone(yaw, pitchDegrees = -20f))!!
            orbit.snapTo(pose)

            val (fx, _, fz) = ScanFollow.forwardOf(phone(yaw, pitchDegrees = -20f))
            val horizontal = sqrt(fx * fx + fz * fz)
            val view = viewForward(orbit)
            assertEquals("yaw $yaw", fx / horizontal, view.x, 1e-3f)
            assertEquals("yaw $yaw", fz / horizontal, view.z, 1e-3f)
        }
    }

    @Test
    fun `the view stands behind and above the phone, on it`() {
        val at = Float3(1f, 1.4f, -2f)
        val pose = ScanFollow.poseAt(phone(0f, position = at), 0f)
        assertEquals(at.x, pose.target.x, 0f)
        assertEquals(at.y, pose.target.y, 0f)
        assertEquals(at.z, pose.target.z, 0f)
        val eye = CameraRig.eye(pose)
        // Facing −Z, behind is +Z.
        assertTrue(eye.z > at.z)
        assertTrue(eye.y > at.y)
        assertEquals(ScanFollow.DISTANCE_M, pose.distance, 0f)
    }

    @Test
    fun `turning the phone turns the followed view the short way, damped`() {
        val orbit = ArDebugOrbitCamera(
            drift = false,
            approachRate = ScanFollow.APPROACH_RATE,
            maxTurnDegreesPerSecond = ScanFollow.MAX_TURN_DEGREES_PER_SECOND,
        )
        val follow = ScanFollow()
        orbit.snapTo(follow.poseFor(phone(170f))!!)
        orbit.followTo(follow.poseFor(phone(-170f))!!)

        orbit.update(1f / 60f)
        val step = CameraRig.shortestDelta(170f, orbit.pose.azimuthDegrees)
        // 20° the short way across ±180°, not 340° the long way, and only part of it per frame.
        assertTrue("step $step", step > 0f && step < 20f)

        repeat(60) { orbit.update(1f / 60f) }
        assertAngle(-170f, orbit.pose.azimuthDegrees)
    }

    @Test
    fun `a drag leaves the follow view, Recenter returns to it`() {
        val orbit = ArDebugOrbitCamera(drift = false)
        val changes = mutableListOf<Boolean>()
        orbit.onFollowingChange = { changes += it }
        assertTrue(orbit.following)

        orbit.grabBegin(10, 10, strafe = false)
        orbit.grabUpdate(80, 10)
        orbit.grabEnd()
        assertFalse(orbit.following)

        // Free, it no longer eases onto the phone.
        val before = orbit.pose
        orbit.followTo(ScanFollow.poseAt(phone(90f), 90f))
        orbit.update(0.1f)
        assertEquals(before, orbit.pose)

        orbit.recenter()
        assertTrue(orbit.following)
        orbit.update(0.1f)
        assertTrue(abs(CameraRig.shortestDelta(before.azimuthDegrees, orbit.pose.azimuthDegrees)) > 0f)
        assertEquals(listOf(false, true), changes)
    }

    @Test
    fun `a pinch leaves the follow view too, and only a change is reported`() {
        val orbit = ArDebugOrbitCamera(drift = false)
        val changes = mutableListOf<Boolean>()
        orbit.onFollowingChange = { changes += it }
        orbit.recenter()
        orbit.scrollBegin(0, 0, 100f)
        orbit.scrollBegin(0, 0, 100f)
        assertEquals(listOf(false), changes)
    }

    /** The scan's own orbit camera, as `RerunLiveScreen` builds it, on a Pixel 7a-sized view. */
    private fun scanOrbit(width: Int = 1280, height: Int = 2856) = ArDebugOrbitCamera(
        drift = false,
        band = ScanFollow.BAND,
        approachRate = ScanFollow.APPROACH_RATE,
        maxTurnDegreesPerSecond = ScanFollow.MAX_TURN_DEGREES_PER_SECOND,
        degreesPerViewWidth = ScanFollow.DRAG_DEGREES_PER_VIEW_WIDTH,
        elevationRange = ScanFollow.DRAG_ELEVATION_RANGE,
        maxCoastDegrees = ScanFollow.MAX_COAST_DEGREES,
    ).apply { setViewport(width, height) }

    /** Drags by ([dx], [dy]) over 24 frames, then lets it coast for [coastSeconds]. */
    private fun drag(orbit: ArDebugOrbitCamera, dx: Int, dy: Int = 0, coastSeconds: Float = 0f) {
        orbit.grabBegin(400, 1400, strafe = false)
        // A finger's drag arrives as many small moves, each a frame apart.
        val steps = 24
        for (step in 1..steps) {
            orbit.grabUpdate(400 + dx * step / steps, 1400 + dy * step / steps)
            orbit.update(1f / 60f)
        }
        orbit.grabEnd()
        repeat((coastSeconds * 60).toInt()) { orbit.update(1f / 60f) }
    }

    /**
     * Where [point] lands on the drawn view, in normalised device coordinates (−1…1 on both axes
     * is the screen), or `null` behind the camera.
     */
    private fun projected(orbit: ArDebugOrbitCamera, point: Float3): Pair<Float, Float>? {
        val m = orbit.getTransform()
        val v = point - Float3(m.w.x, m.w.y, m.w.z)
        val depth = dot(v, -Float3(m.z.x, m.z.y, m.z.z))
        if (depth <= 0f) return null
        val tanVertical = tan(Math.toRadians(orbit.verticalFovDegrees / 2.0)).toFloat()
        val x = dot(v, Float3(m.x.x, m.x.y, m.x.z)) / depth / (tanVertical * orbit.aspect)
        val y = dot(v, Float3(m.y.x, m.y.y, m.y.z)) / depth / tanVertical
        return x to y
    }

    @Test
    fun `a drag across the whole width turns the view half round, a third of it 60 degrees`() {
        for (width in listOf(1080, 1280, 1440)) {
            val full = scanOrbit(width = width)
            full.snapTo(ScanFollow.poseAt(phone(0f), 0f))
            drag(full, dx = width)
            assertAngle(-180f, full.pose.azimuthDegrees)

            val third = scanOrbit(width = width)
            third.snapTo(ScanFollow.poseAt(phone(0f), 0f))
            drag(third, dx = -width / 3)
            assertEquals("width $width", 60f, third.pose.azimuthDegrees, 0.2f)
        }
    }

    @Test
    fun `a flick coasts on after release, smoothly and no further than a quarter turn`() {
        val orbit = scanOrbit()
        orbit.snapTo(ScanFollow.poseAt(phone(0f), 0f))
        // A fast flick: the whole width in 24 frames, 450°/s, well over the coast's top speed.
        drag(orbit, dx = 1280)
        val released = orbit.pose.azimuthDegrees
        var previousStep = Float.MAX_VALUE
        var coasted = 0f
        repeat(600) {
            val before = orbit.pose.azimuthDegrees
            orbit.update(1f / 60f)
            val step = abs(CameraRig.shortestDelta(before, orbit.pose.azimuthDegrees))
            // It decays: never speeds up, frame after frame.
            assertTrue("step $step after $previousStep", step <= previousStep + 1e-4f)
            previousStep = step
            coasted += step
        }
        assertTrue("coasted $coasted", coasted > 30f)
        assertTrue("coasted $coasted", coasted <= ScanFollow.MAX_COAST_DEGREES + 5f)
        assertTrue(abs(CameraRig.shortestDelta(released, orbit.pose.azimuthDegrees)) > 0f)
        assertEquals(0f, previousStep, 0f)
    }

    @Test
    fun `the pitch of a drag stops short of the floor and of straight down`() {
        val orbit = scanOrbit()
        orbit.snapTo(ScanFollow.poseAt(phone(0f), 0f))
        drag(orbit, dx = 0, dy = -5000, coastSeconds = 5f)
        assertEquals(ScanFollow.DRAG_ELEVATION_RANGE.endInclusive, orbit.pose.elevationDegrees, 1e-3f)
        drag(orbit, dx = 0, dy = 5000, coastSeconds = 5f)
        assertEquals(ScanFollow.DRAG_ELEVATION_RANGE.start, orbit.pose.elevationDegrees, 1e-3f)
        assertTrue(CameraRig.eye(orbit.pose).y > orbit.pose.target.y)
    }

    @Test
    fun `taking the view over moves its pivot onto the cloud without moving the picture`() {
        val phoneAt = Float3(0f, 1.4f, 0f)
        val orbit = scanOrbit()
        orbit.snapTo(ScanFollow.poseAt(phone(0f, position = phoneAt), 0f))
        // The cloud lies ahead of the phone, down its -Z.
        val cloud = Float3(0.3f, 0.6f, -2.5f)
        orbit.pivot = cloud
        val before = orbit.getTransform()

        orbit.grabBegin(400, 1400, strafe = false)
        val after = orbit.getTransform()
        for ((b, a) in listOf(before.x to after.x, before.y to after.y, before.z to after.z, before.w to after.w)) {
            assertEquals(b.x, a.x, 1e-3f)
            assertEquals(b.y, a.y, 1e-3f)
            assertEquals(b.z, a.z, 1e-3f)
        }
        // The orbit now turns round a point at the cloud's depth, no longer round the phone.
        assertTrue(length(orbit.pose.target - cloud) < length(phoneAt - cloud))
        orbit.grabEnd()
    }

    @Test
    fun `whatever the drag, the cloud stays on screen`() {
        val cloud = Float3(0.3f, 0.6f, -2.5f)
        for (dx in listOf(-2560, -1280, -900, -427, 427, 900, 1280, 2560)) for (dy in listOf(-3000, 0, 3000)) {
            val orbit = scanOrbit()
            orbit.snapTo(ScanFollow.poseAt(phone(0f, position = Float3(0f, 1.4f, 0f)), 0f))
            orbit.pivot = cloud
            // Checked once the coast has died out, after the flick has carried it furthest.
            drag(orbit, dx, dy, coastSeconds = 10f)
            val drawn = projected(orbit, cloud)
            assertNotNull("dx $dx dy $dy: behind the camera", drawn)
            val (x, y) = drawn!!
            assertTrue("dx $dx dy $dy at ($x, $y)", abs(x) < 1f && abs(y) < 1f)
        }
    }

    @Test
    fun `the orbit's centre is the scanned cloud's own, sampled on a large cloud`() {
        assertNull(ScanFollow.cloudCentre(FloatArray(0)))
        val two = ScanFollow.cloudCentre(floatArrayOf(0f, 0f, 0f, 2f, 4f, -6f))!!
        assertEquals(1f, two.x, 1e-6f)
        assertEquals(2f, two.y, 1e-6f)
        assertEquals(-3f, two.z, 1e-6f)
        val big = room(points = 500_000)
        val centre = ScanFollow.cloudCentre(big)!!
        // 25 times the sample: every 25th point is averaged, within a few cm of the true mean.
        for (axis in 0..2) {
            var sum = 0.0
            for (i in 0 until big.size / 3) sum += big[i * 3 + axis]
            val exact = (sum / (big.size / 3)).toFloat()
            assertEquals("axis $axis", exact, floatArrayOf(centre.x, centre.y, centre.z)[axis], 0.1f)
        }
    }

    @Test
    fun `a half turn round the cloud still faces the room the phone stands at the edge of`() {
        val walls = room(points = 4_000)
        // The phone, and so its whole path, stands by the near wall looking in.
        val phone = phone(0f, position = Float3(0f, 1.4f, -0.7f))
        fun shownAfter(dx: Int?): Int {
            val orbit = scanOrbit()
            orbit.snapTo(ScanFollow.poseAt(phone, 0f))
            orbit.pivot = ScanFollow.cloudCentre(walls)
            if (dx != null) drag(orbit, dx, coastSeconds = 10f)
            var shown = 0
            for (i in 0 until walls.size / 3) {
                val p = projected(orbit, Float3(walls[i * 3], walls[i * 3 + 1], walls[i * 3 + 2])) ?: continue
                if (abs(p.first) < 1f && abs(p.second) < 1f) shown++
            }
            return shown
        }
        val following = shownAfter(null)
        assertTrue("$following on screen while following", following > 0)
        for (dx in listOf(-1280, -900, -640, 640, 900, 1280)) {
            val shown = shownAfter(dx)
            assertTrue("dx $dx: $shown on screen, $following while following", shown >= following / 2)
        }
    }

    /**
     * [points] spread on the far and side walls of a 4 m square room centred on (0, −2.5): what a
     * phone at its near edge has scanned ahead of it, the wall behind it not yet.
     */
    private fun room(points: Int): FloatArray {
        val out = FloatArray(points * 3)
        for (i in 0 until points) {
            val t = (i % 1000) / 1000f * 4f - 2f
            val h = (i / 1000 % 25) / 25f * 2.4f
            val (x, z) = when (i % 3) {
                0 -> t to -4.5f
                1 -> -2f to t - 2.5f
                else -> 2f to t - 2.5f
            }
            out[i * 3] = x
            out[i * 3 + 1] = h
            out[i * 3 + 2] = z
        }
        return out
    }
}
