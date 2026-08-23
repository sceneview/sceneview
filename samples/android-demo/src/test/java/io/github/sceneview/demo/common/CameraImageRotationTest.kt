package io.github.sceneview.demo.common

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the `Surface.ROTATION_*` -> rotation-degrees mapping shared by
 * `ar-ml-object-label` (ML Kit) and `ar-body-tracker` (MediaPipe) to correct ARCore's raw
 * sensor-orientation CPU camera image before feeding it into either vision pipeline.
 *
 * `ar-body-tracker` shipped without this correction (#3266): its bitmap conversion never
 * accounted for rotation, so a person standing upright in front of the camera appeared
 * sideways to the pose model, which almost never found a body. This mapping is the fix, so
 * every rotation case is pinned here.
 */
class CameraImageRotationTest {

    @Test
    fun `ROTATION_0 (portrait) needs 90 degrees of correction`() {
        assertEquals(90, rotationDegreesForDisplayRotation(Surface.ROTATION_0))
    }

    @Test
    fun `ROTATION_90 needs no correction`() {
        assertEquals(0, rotationDegreesForDisplayRotation(Surface.ROTATION_90))
    }

    @Test
    fun `ROTATION_180 needs 270 degrees of correction`() {
        assertEquals(270, rotationDegreesForDisplayRotation(Surface.ROTATION_180))
    }

    @Test
    fun `ROTATION_270 needs 180 degrees of correction`() {
        assertEquals(180, rotationDegreesForDisplayRotation(Surface.ROTATION_270))
    }

    @Test
    fun `unknown rotation values fall back to the portrait correction`() {
        assertEquals(90, rotationDegreesForDisplayRotation(-1))
    }
}
