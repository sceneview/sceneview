package io.github.sceneview.ar

import com.google.android.filament.Box
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.scale
import dev.romainguy.kotlin.math.translation
import io.github.sceneview.ar.arcore.transform
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.toQuaternion
import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the public adapter with real Pose math and a plane snapshot, without ARCore JNI. */
class PlacementBoundsFitAdapterTest {
    private val bounds = Box(floatArrayOf(0f, 0f, 0f), floatArrayOf(0.5f, 0.5f, 0.5f))

    @Test fun `tilted plane keeps a fit verdict at its centre for every yaw`() {
        // The general matrix inverse changed homogeneous w to 0.9999999 for valid poses,
        // causing the geometry core's affine check to reject the adapter's own transform.
        for (yaw in 1..359) {
            val pose = planePose(yaw.toFloat())
            val fit = SnapshotPlane(pose).placementBoundsFit(bounds, pose.transform)
            assertNotNull("4 degree tilt, $yaw degree yaw must have a fit verdict", fit)
            assertTrue(fit!!.fits)
            assertEquals("clearance at yaw $yaw", 0.5f, fit.marginMeters, 1e-5f)
        }
    }

    @Test fun `translated tilted plane preserves model offsets and nonuniform scale`() {
        val pose = planePose(yaw = 37f)
        val offsetBounds = Box(floatArrayOf(0.1f, 0f, 0f), floatArrayOf(0.5f, 0.5f, 0.5f))
        val modelTransform = pose.transform * translation(Position(0.25f, 0f, -0.1f)) *
            scale(Scale(1f, 2f, 0.5f))
        val fit = SnapshotPlane(pose).placementBoundsFit(offsetBounds, modelTransform)
        assertNotNull(fit)
        assertTrue(fit!!.fits)
        assertEquals(0.15f, fit.marginMeters, 1e-5f)
    }

    @Test fun `paused plane has no verdict even with a valid pose and polygon`() {
        val pose = planePose(yaw = 1f)
        assertNull(SnapshotPlane(pose, TrackingState.PAUSED).placementBoundsFit(bounds, pose.transform))
    }

    private fun planePose(yaw: Float): Pose {
        val q = Rotation(x = 4f, y = yaw).toQuaternion()
        return Pose(floatArrayOf(5f, 2f, -3f), floatArrayOf(q.x, q.y, q.z, q.w))
    }

    // Plane exposes a protected no-session constructor; override only its JNI-backed reads.
    // Pose construction, inversion, matrix conversion and the public adapter stay real.
    private class SnapshotPlane(
        private val pose: Pose,
        private val state: TrackingState = TrackingState.TRACKING,
    ) : Plane() {
        override fun getCenterPose(): Pose = pose
        override fun getTrackingState(): TrackingState = state
        override fun getPolygon(): FloatBuffer =
            FloatBuffer.wrap(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f))
    }
}
