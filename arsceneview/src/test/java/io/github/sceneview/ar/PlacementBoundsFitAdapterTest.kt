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
import org.junit.Assert.assertFalse
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

    @Test fun `translated tilted plane accepts exact boundary contact for every yaw`() {
        val boundaryBounds = Box(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
        for (yaw in 1..359) {
            val pose = planePose(yaw.toFloat())
            val fit = SnapshotPlane(pose).placementBoundsFit(boundaryBounds, pose.transform)
            assertNotNull("boundary contact must have a verdict at yaw $yaw", fit)
            fit!!
            assertTrue("boundary contact at yaw $yaw, margin=${fit.marginMeters}", fit.fits)
            assertEquals("boundary margin at yaw $yaw", 0f, fit.marginMeters, 1e-5f)
            if (yaw == 1) {
                assertTrue("retain the raw negative roundoff margin", fit.marginMeters < 0f)
            }
        }
    }

    @Test fun `translated tilted plane rejects a millimetre overhang for every yaw`() {
        val boundaryBounds = Box(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
        for (yaw in 1..359) {
            val pose = planePose(yaw.toFloat())
            val modelTransform = pose.transform * translation(Position(0.001f, 0f, 0f))
            val fit = SnapshotPlane(pose).placementBoundsFit(boundaryBounds, modelTransform)
            assertNotNull("overhang must have a verdict at yaw $yaw", fit)
            fit!!
            assertFalse("millimetre overhang at yaw $yaw, margin=${fit.marginMeters}", fit.fits)
            assertEquals("overhang margin at yaw $yaw", -0.001f, fit.marginMeters, 1e-5f)
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

    @Test fun `rotated polygon measures clearance in polygon edge directions`() {
        val pose = planePose(yaw = 37f)
        // A square rotated 45 degrees in plane-local XZ, with half-side sqrt(0.5).
        val plane = SnapshotPlane(pose, polygon = floatArrayOf(0f, -1f, 1f, 0f, 0f, 1f, -1f, 0f))
        val fit = requireNotNull(plane.placementBoundsFit(bounds, pose.transform))
        assertTrue(fit.fits)
        assertEquals(0f, fit.marginMeters, 1e-5f)

        val overhang = requireNotNull(plane.placementBoundsFit(
            bounds, pose.transform * translation(Position(0.1f, 0f, 0f)),
        ))
        assertFalse(overhang.fits)
        assertEquals(-0.1f / kotlin.math.sqrt(2f), overhang.marginMeters, 1e-5f)
    }

    @Test fun `subsumed planes use the top plane tracking pose and polygon`() {
        val pose = planePose(yaw = 37f)
        val top = SnapshotPlane(pose)
        val stalePolygon = floatArrayOf(-0.1f, -0.1f, 0.1f, -0.1f, 0.1f, 0.1f, -0.1f, 0.1f)
        val merged = SnapshotPlane(Pose.IDENTITY, polygon = stalePolygon, parent = top)
        val original = SnapshotPlane(Pose.IDENTITY, TrackingState.STOPPED, parent = merged)
        val fit = requireNotNull(original.placementBoundsFit(bounds, pose.transform))
        assertTrue(fit.fits)
        assertEquals(0.5f, fit.marginMeters, 1e-5f)
    }

    @Test fun `tracking child has no verdict when the subsuming plane is paused`() {
        val pose = planePose(yaw = 1f)
        val top = SnapshotPlane(pose, TrackingState.PAUSED)
        assertNull(SnapshotPlane(pose, parent = top).placementBoundsFit(bounds, pose.transform))
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
        private val polygon: FloatArray = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f),
        private val parent: Plane? = null,
    ) : Plane() {
        override fun getCenterPose(): Pose = pose
        override fun getTrackingState(): TrackingState = state
        override fun getPolygon(): FloatBuffer = FloatBuffer.wrap(polygon)
        override fun getSubsumedBy(): Plane? = parent
    }
}
