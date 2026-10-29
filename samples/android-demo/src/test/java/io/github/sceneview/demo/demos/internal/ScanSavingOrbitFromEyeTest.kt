package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import io.github.sceneview.math.copyColumnsInto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * #4719: the saving orbit set out from the follow pose, 2.6 m behind the phone, while the frame
 * before the stop tap was the phone's own eye (#4716) — the save opened on a cut. It now sets out
 * from that eye, and the camera eases off it.
 */
class ScanSavingOrbitFromEyeTest {

    /** A phone 1.4 m up at (1, 1.4, 2), turned 30° left and tilted 30° down, as `Pose.toMatrix` writes it. */
    private val phone = FloatArray(16).also { ScanLens.poseMatrix(pose(yawDegrees = 30.0, pitchDegrees = -30.0), it) }

    /** A portrait phone camera's lens: 60° vertical on 9:20, a little off-centre. */
    private val cameraLens = FloatArray(16).also {
        EyeHandoff.orbitProjection(verticalFovDegrees = 60.0, aspect = 9f / 20f, out = it)
        it[9] = 0.03f
    }

    /** A room's cloud about (0, 0.5, -1), ahead of the phone. */
    private val points = floatArrayOf(-1.5f, 0f, -2.5f, 1.5f, 1f, 0.5f, -1.5f, 1f, 0.5f, 1.5f, 0f, -2.5f)

    private var now = 5_000_000_000L
    private val orbit = ScanSavingOrbit { now }

    private val lens = ScanLens().apply {
        update { cameraToWorld, projection ->
            phone.copyInto(cameraToWorld)
            cameraLens.copyInto(projection)
        }
    }

    /** The live view, seen once through the phone's eye, as the frame before the stop tap. */
    private val camera = ArDebugOrbitCamera(drift = false, band = ScanFollow.BAND).apply {
        firstPerson = lens
        setViewport(1080, 2400)
        val pose = pose(yawDegrees = 30.0, pitchDegrees = -30.0)
        snapTo(ScanFollow.poseAt(pose, ScanFollow.headingOf(pose)!!))
        assertTrue(inFirstPerson)
        getTransform()
    }

    private val followPose = camera.pose

    @Test
    fun `the saving orbit's first pose is the eye it was seeded with`() {
        lens.invalidate() // the save has started: the live view no longer sees through the phone
        val first = orbit.poseFor(followPose, points, camera)!!

        val centre = ScanFollow.cloudCentre(points)!!
        assertEquals(ScanFollow.eyePose(phone, centre), first)
        assertEquals(first, orbit.start)
        assertEquals(first, camera.pose)

        // Where the phone stood, looking where it looked.
        val eye = CameraRig.eye(first)
        assertEquals(phone[12], eye.x, EPS)
        assertEquals(phone[13], eye.y, EPS)
        assertEquals(phone[14], eye.z, EPS)
        val look = direction(eye, first.target)
        assertEquals(-phone[8], look.x, EPS)
        assertEquals(-phone[9], look.y, EPS)
        assertEquals(-phone[10], look.z, EPS)

        // A frame on, the flight has set out from there without a jump, on the phone's heading.
        now += 16_666_667L
        val next = orbit.poseFor(followPose, points, camera)!!
        assertEquals(first.azimuthDegrees, next.azimuthDegrees, 1e-3f)
        assertTrue(CameraRig.eye(next).distanceTo(eye) < 0.01f)
    }

    @Test
    fun `the orbit holds on the eye until the room's lid is cut off, then sets out`() {
        lens.invalidate()
        val first = orbit.poseFor(followPose, points, camera, ready = false)!!
        now += 3_000_000_000L // a slow phone: three seconds to cut the ceiling off
        assertEquals(first, orbit.poseFor(followPose, points, camera, ready = false))
        // Ready, the flight starts from its first second, not three seconds in.
        assertEquals(first, orbit.poseFor(followPose, points, camera, ready = true))
        now += 1_000_000_000L
        val flight = orbit.flight!!
        assertEquals(flight.poseAt(1f), orbit.poseFor(followPose, points, camera, ready = true))
    }

    @Test
    fun `the first frame of the save is the phone's eye and lens, then the orbit's`() {
        lens.invalidate()
        orbit.poseFor(followPose, points, camera)

        assertFalse(camera.inFirstPerson)
        assertArrayEquals(phone, camera.getTransform().copyColumnsInto(FloatArray(16)), 0f)
        val projection = FloatArray(16)
        assertTrue(camera.customProjection(projection))
        assertArrayEquals(cameraLens, projection, 0f)

        // Eased off it frame by frame, then on the orbit with the orbit's own lens.
        var previous = phone[12]
        repeat((EyeHandoff.SECONDS / FRAME).toInt() + 2) {
            camera.update(FRAME)
            val x = camera.getTransform().copyColumnsInto(FloatArray(16))[12]
            assertTrue("moved ${x - previous} m in a frame", kotlin.math.abs(x - previous) < 0.1f)
            previous = x
        }
        assertFalse(camera.customProjection(projection))
    }

    @Test
    fun `a view a drag had taken off the phone sets out from where it is`() {
        camera.grabBegin(0, 0, strafe = false)
        camera.grabEnd()
        lens.invalidate()
        val free = camera.pose
        val first = orbit.poseFor(followPose, points, camera)!!
        assertEquals(followPose, first)
        assertEquals(free, camera.pose)
    }

    private fun direction(from: Position, to: Position): Position {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val n = sqrt(dx * dx + dy * dy + dz * dz)
        return Position(dx / n, dy / n, dz / n)
    }

    private fun Position.distanceTo(o: Position): Float {
        val dx = x - o.x
        val dy = y - o.y
        val dz = z - o.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private companion object {
        const val EPS = 1e-4f
        const val FRAME = 1f / 60f

        /** The phone at (1, 1.4, 2), yawed about +Y then pitched about its own X. */
        fun pose(yawDegrees: Double, pitchDegrees: Double): DebugPose {
            val sy = sin(Math.toRadians(yawDegrees / 2)).toFloat()
            val cy = cos(Math.toRadians(yawDegrees / 2)).toFloat()
            val sx = sin(Math.toRadians(pitchDegrees / 2)).toFloat()
            val cx = cos(Math.toRadians(pitchDegrees / 2)).toFloat()
            return DebugPose(1f, 1.4f, 2f, cy * sx, cx * sy, -sy * sx, cy * cx)
        }
    }
}
