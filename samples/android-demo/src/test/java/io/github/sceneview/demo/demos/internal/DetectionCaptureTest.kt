package io.github.sceneview.demo.demos.internal

import io.github.sceneview.ar.camera.CameraImageSize
import io.github.sceneview.ar.camera.CameraImageToViewMapping
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The world ray cast through a detector pixel from the pose the image was acquired at. Every
 * expected value is derived by hand from the pinhole model, not from the code under test.
 */
class DetectionCaptureTest {

    @Test
    fun `the principal point looks straight down the camera axis`() {
        // Unrotated detector input: detector pixels are image pixels.
        val ray = capture(rotation = 0).worldRay(PRINCIPAL_X, PRINCIPAL_Y)

        assertVector(ray.direction, 0f, 0f, -1f)
        assertVector(ray.origin, 0f, 0f, 0f)
    }

    @Test
    fun `a pixel right of and below the principal point leans right and down`() {
        // One focal length to the right (+u) and half a focal length down (+v): the camera-space
        // direction is (1, -0.5, -1) before normalisation, v pointing against camera +Y.
        val ray = capture(rotation = 0).worldRay(PRINCIPAL_X + FOCAL, PRINCIPAL_Y + FOCAL / 2f)

        val length = sqrt(1f + 0.25f + 1f)
        assertVector(ray.direction, 1f / length, -0.5f / length, -1f / length)
    }

    @Test
    fun `a rotated detector pixel is cast through the image pixel it was read from`() {
        // Portrait phone: the detector sees the 640x480 image turned 90 degrees clockwise, as
        // 480x640. Its pixel (120, 320) is image pixel (320, 360): 120 px below the principal
        // point (320, 240) in the sensor image, so the ray leans towards camera -Y.
        val ray = capture(rotation = 90).worldRay(120f, 320f)

        val length = sqrt(0.24f * 0.24f + 1f)
        assertVector(ray.direction, 0f, -0.24f / length, -1f / length)
    }

    @Test
    fun `the detector centre is the image centre at every rotation`() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val capture = capture(rotation = rotation)
            val ray = capture.worldRay(
                capture.mapping.detectorWidth / 2f,
                capture.mapping.detectorHeight / 2f,
            )

            assertVector(ray.direction, 0f, 0f, -1f, "centre at $rotation degrees")
        }
    }

    @Test
    fun `the ray starts at the acquisition pose and turns with it`() {
        // Camera yawed 90 degrees to the left about world +Y: its forward (-Z) is world -X.
        val half = Math.toRadians(45.0)
        val pose = DetectionCameraPose(
            tx = 1f, ty = 1.5f, tz = -2f,
            qx = 0f, qy = sin(half).toFloat(), qz = 0f, qw = cos(half).toFloat(),
        )
        val ray = capture(rotation = 0, pose = pose).worldRay(PRINCIPAL_X, PRINCIPAL_Y)

        assertVector(ray.origin, 1f, 1.5f, -2f)
        assertVector(ray.direction, -1f, 0f, 0f)
    }

    @Test
    fun `a pitched camera sends its image up axis forward`() {
        // Camera pitched 90 degrees down about world +X (looking at the floor): forward is
        // world -Y, and the image's up direction is world -Z.
        val half = Math.toRadians(-45.0)
        val pose = DetectionCameraPose(
            tx = 0f, ty = 0f, tz = 0f,
            qx = sin(half).toFloat(), qy = 0f, qz = 0f, qw = cos(half).toFloat(),
        )
        val capture = capture(rotation = 0, pose = pose)

        assertVector(capture.worldRay(PRINCIPAL_X, PRINCIPAL_Y).direction, 0f, -1f, 0f)
        // One focal length above the principal point (v smaller): camera (0, 1, -1).
        val up = capture.worldRay(PRINCIPAL_X, PRINCIPAL_Y - FOCAL).direction
        val length = sqrt(2f)
        assertVector(up, 0f, -1f / length, -1f / length)
    }

    @Test
    fun `intrinsics reported for another resolution are rescaled to the image`() {
        // Same sensor crop described at twice the resolution: the ray must not change.
        val doubled = DetectionIntrinsics(
            focalX = FOCAL * 2f,
            focalY = FOCAL * 2f,
            principalX = PRINCIPAL_X * 2f,
            principalY = PRINCIPAL_Y * 2f,
            width = IMAGE.width * 2,
            height = IMAGE.height * 2,
        )
        val expected = capture(rotation = 0).worldRay(500f, 100f).direction
        val actual = capture(rotation = 0, intrinsics = doubled).worldRay(500f, 100f).direction

        assertVector(actual, expected[0], expected[1], expected[2])
    }

    private fun capture(
        rotation: Int,
        pose: DetectionCameraPose = IDENTITY,
        intrinsics: DetectionIntrinsics = INTRINSICS,
    ) = DetectionCapture(
        pose = pose,
        intrinsics = intrinsics,
        mapping = CameraImageToViewMapping.centerCrop(IMAGE, CameraImageSize(1080, 1920), rotation),
    )

    private fun assertVector(
        actual: FloatArray,
        x: Float,
        y: Float,
        z: Float,
        message: String = "vector",
    ) {
        assertEquals("$message x", x, actual[0], EPSILON)
        assertEquals("$message y", y, actual[1], EPSILON)
        assertEquals("$message z", z, actual[2], EPSILON)
    }

    private companion object {
        const val EPSILON = 1e-4f
        const val FOCAL = 500f
        const val PRINCIPAL_X = 320f
        const val PRINCIPAL_Y = 240f
        val IMAGE = CameraImageSize(640, 480)
        val IDENTITY = DetectionCameraPose(0f, 0f, 0f, 0f, 0f, 0f, 1f)
        val INTRINSICS = DetectionIntrinsics(FOCAL, FOCAL, PRINCIPAL_X, PRINCIPAL_Y, 640, 480)
    }
}
