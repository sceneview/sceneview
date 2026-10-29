package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.inverse
import io.github.sceneview.math.Transform
import io.github.sceneview.math.copyColumnsInto
import io.github.sceneview.math.toTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.tan

/**
 * #4716: 3D Scan's live view drew its points from 2.6 m behind and above the phone, through a lens
 * of its own, over the phone's camera image. While it follows, it now draws from the phone's eye
 * through ARCore's projection, so a point lands on the camera pixel it came from.
 */
class ScanLensTest {

    /** A phone 1.4 m up at (1, 1.4, 2), turned 30° left about +Y. Column-major, as `Pose.toMatrix`. */
    private val phone = floatArrayOf(
        COS30, 0f, -SIN30, 0f,
        0f, 1f, 0f, 0f,
        SIN30, 0f, COS30, 0f,
        1f, 1.4f, 2f, 1f,
    )

    /** A portrait phone camera's projection: 60° vertical, 9:20, off-centre like a real lens. */
    private val cameraLens = perspective(verticalFovDegrees = 60f, aspect = 9f / 20f, shiftY = 0.03f)

    private fun lens() = ScanLens().apply {
        update { cameraToWorld, proj ->
            phone.copyInto(cameraToWorld)
            cameraLens.copyInto(proj)
        }
    }

    private fun following(lens: ScanLens?) = ArDebugOrbitCamera(drift = false, band = ScanFollow.BAND).apply {
        firstPerson = lens
        setViewport(1080, 2400)
        val pose = DebugPose(phone[12], phone[13], phone[14], 0f, 0.2588f, 0f, 0.9659f)
        snapTo(ScanFollow.poseAt(pose, ScanFollow.headingOf(pose)!!))
    }

    @Test
    fun `following, the view stands at the phone's eye`() {
        val orbit = following(lens())
        assertTrue(orbit.inFirstPerson)
        assertTrue(orbit.getTransform().copyColumnsInto(FloatArray(16)).contentEquals(phone))
    }

    @Test
    fun `a wall point straight ahead lands where the camera saw it, not a fifth of the screen lower`() {
        // A point on a wall 4 m ahead of the phone, on the camera's optical axis: the camera image
        // shows it at the projection's principal point.
        val wall = phone.toTransform() * Float4(0f, 0f, -4f, 1f)
        val seenByCamera = ndc(cameraLens.toTransform(), phone.toTransform(), wall)

        val drawn = ndc(cameraLens.toTransform(), following(lens()).getTransform(), wall)
        assertEquals(seenByCamera.first, drawn.first, 1e-3f)
        assertEquals(seenByCamera.second, drawn.second, 1e-3f)

        // The orbit it used to draw from puts the same point far off it: the reported offset.
        val before = ndc(cameraLens.toTransform(), following(null).getTransform(), wall)
        assertTrue(
            "orbit drew the wall ${before.second - seenByCamera.second} NDC off",
            abs(before.second - seenByCamera.second) > 0.2f,
        )
    }

    @Test
    fun `a drag leaves the eye for the orbit, Recenter comes back to it`() {
        val orbit = following(lens())
        orbit.grabBegin(10, 10, strafe = false)
        orbit.grabEnd()
        assertFalse(orbit.inFirstPerson)
        assertFalse(orbit.getTransform().copyColumnsInto(FloatArray(16)).contentEquals(phone))

        orbit.recenter()
        assertTrue(orbit.inFirstPerson)
        assertTrue(orbit.getTransform().copyColumnsInto(FloatArray(16)).contentEquals(phone))
    }

    @Test
    fun `lost tracking or no frame yet keeps the orbit`() {
        val fresh = ScanLens()
        assertFalse(following(fresh).inFirstPerson)

        val lost = lens().apply { invalidate() }
        assertFalse(lost.ready)
        assertFalse(following(lost).inFirstPerson)
    }

    @Test
    fun `a frame without a projection is not an eye`() {
        val lens = ScanLens().apply { update { cameraToWorld, _ -> phone.copyInto(cameraToWorld) } }
        assertFalse(lens.ready)
    }

    @Test
    fun `each update bumps the version`() {
        val lens = lens()
        val first = lens.version
        lens.update { _, _ -> }
        assertTrue(lens.version > first)
    }

    @Test
    fun `paused, the view keeps the phone's eye, saving hands it to the saving orbit`() {
        assertTrue(ScanLens.seesThroughPhone(tracking = true, ScanPhase.Scanning))
        // Paused, the eye stays the live phone: the cloud stays put and resuming does not jump.
        assertTrue(ScanLens.seesThroughPhone(tracking = true, ScanPhase.Paused))
        assertTrue(ScanLens.seesThroughPhone(tracking = true, ScanPhase.Reviewing))
        assertFalse(ScanLens.seesThroughPhone(tracking = true, ScanPhase.Saving))
        assertFalse(ScanLens.seesThroughPhone(tracking = false, ScanPhase.Scanning))

        // Invalidated as the save starts, the orbit's own pose takes over again.
        val lens = lens()
        val orbit = following(lens)
        assertTrue(orbit.inFirstPerson)
        lens.invalidate()
        assertFalse(orbit.inFirstPerson)
    }

    @Test
    fun `a cropped photo's projection puts its kept edges on the view's edges`() {
        // A 3:4 portrait photo filling a 9:20 view: the full height stays, the sides are cropped.
        val out = FloatArray(ScanLens.MATRIX_SIZE)
        ScanLens.cropProjection(ReplayLens(0.375f, 0.5f), 9f / 20f, out)
        val (_, top) = ndc(out.toTransform(), Transform(), Float4(0f, 0.5f * 4f, -4f, 1f))
        val (right, _) = ndc(out.toTransform(), Transform(), Float4(0.5f * 9f / 20f * 4f, 0f, -4f, 1f))
        assertEquals(1f, top, 1e-4f)
        assertEquals(1f, right, 1e-4f)

        // A wide view of the same photo keeps its full width and crops top and bottom instead.
        ScanLens.cropProjection(ReplayLens(0.375f, 0.5f), 1f, out)
        val (wideRight, _) = ndc(out.toTransform(), Transform(), Float4(0.375f * 4f, 0f, -4f, 1f))
        assertEquals(1f, wideRight, 1e-4f)
    }

    @Test
    fun `a photo pose becomes the same model matrix ARCore's pose would`() {
        val out = FloatArray(ScanLens.MATRIX_SIZE)
        ScanLens.poseMatrix(DebugPose(1f, 1.4f, 2f, 0f, SIN15, 0f, COS15), out)
        phone.forEachIndexed { i, expected -> assertEquals("m[$i]", expected, out[i], 1e-4f) }
    }

    private fun ndc(projection: Transform, cameraToWorld: Transform, world: Float4): Pair<Float, Float> {
        val clip = projection * (inverse(cameraToWorld) * world)
        return clip.x / clip.w to clip.y / clip.w
    }

    private companion object {
        const val COS30 = 0.8660254f
        const val SIN30 = 0.5f
        const val SIN15 = 0.25881904f
        const val COS15 = 0.9659258f

        /** An OpenGL perspective matrix, column-major, with the principal point shifted by [shiftY]. */
        fun perspective(verticalFovDegrees: Float, aspect: Float, shiftY: Float): FloatArray {
            val near = ScanLens.NEAR_M
            val far = ScanLens.FAR_M
            val f = 1f / tan(Math.toRadians(verticalFovDegrees / 2.0)).toFloat()
            return floatArrayOf(
                f / aspect, 0f, 0f, 0f,
                0f, f, 0f, 0f,
                0f, shiftY, (far + near) / (near - far), -1f,
                0f, 0f, 2f * far * near / (near - far), 0f,
            )
        }
    }
}
