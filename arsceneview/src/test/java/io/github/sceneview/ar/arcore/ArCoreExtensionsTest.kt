package io.github.sceneview.ar.arcore

import dev.romainguy.kotlin.math.Float2
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the additive ARCore extension helpers introduced by #1771 — single-line wrappers
 * around raw ARCore getters plus the [CameraIntrinsicsSnapshot] conversion.
 *
 * Real ARCore types (`Frame`, `Camera`, `Pose`, `HitResult`, `Plane`, `CameraIntrinsics`)
 * are JNI-backed and can't be instantiated in a JVM unit-test runner. Pure value-conversion
 * helpers are extracted to `internal` top-level functions and verified here in isolation.
 * The thin getter wrappers (`HitResult.distance`, `Camera.displayOrientedPose`,
 * `Plane.polygon`, `Plane.subsumedBy`, `Frame.depthImage()` etc.) carry no logic worth
 * pinning — they delegate directly to ARCore and are exercised by the demos at runtime.
 */
class ArCoreExtensionsTest {

    @Test
    fun `cameraIntrinsicsSnapshot copies focal length, principal point and dimensions`() {
        val snapshot = cameraIntrinsicsSnapshot(
            focalLength = floatArrayOf(540f, 540f),
            principalPoint = floatArrayOf(320f, 240f),
            imageDimensions = intArrayOf(640, 480)
        )

        assertEquals(Float2(540f, 540f), snapshot.focalLength)
        assertEquals(Float2(320f, 240f), snapshot.principalPoint)
        assertEquals(640, snapshot.imageWidth)
        assertEquals(480, snapshot.imageHeight)
    }

    @Test
    fun `cameraIntrinsicsSnapshot handles different image vs texture intrinsics`() {
        // Simulates the CPU image stream (typically higher resolution than GPU texture
        // stream on Pixel devices — e.g. 1920x1440 vs 640x480).
        val image = cameraIntrinsicsSnapshot(
            focalLength = floatArrayOf(1500f, 1500f),
            principalPoint = floatArrayOf(960f, 720f),
            imageDimensions = intArrayOf(1920, 1440)
        )
        val texture = cameraIntrinsicsSnapshot(
            focalLength = floatArrayOf(540f, 540f),
            principalPoint = floatArrayOf(320f, 240f),
            imageDimensions = intArrayOf(640, 480)
        )

        // Pinhole projection consistency check: principalPoint should scale with imageSize.
        assertEquals(image.imageWidth / 2, image.principalPoint.x.toInt())
        assertEquals(image.imageHeight / 2, image.principalPoint.y.toInt())
        assertEquals(texture.imageWidth / 2, texture.principalPoint.x.toInt())
        assertEquals(texture.imageHeight / 2, texture.principalPoint.y.toInt())
    }

    @Test
    fun `cameraIntrinsicsSnapshot equals contract is value-based (data class)`() {
        val a = cameraIntrinsicsSnapshot(
            focalLength = floatArrayOf(1f, 2f),
            principalPoint = floatArrayOf(3f, 4f),
            imageDimensions = intArrayOf(5, 6)
        )
        val b = cameraIntrinsicsSnapshot(
            focalLength = floatArrayOf(1f, 2f),
            principalPoint = floatArrayOf(3f, 4f),
            imageDimensions = intArrayOf(5, 6)
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
