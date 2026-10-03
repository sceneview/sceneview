package io.github.sceneview.ar.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraImageToViewMappingTest {

    @Test
    fun `center crop maps rotated detector pixels for every quarter turn`() {
        val image = CameraImageSize(640, 480)
        val view = CameraImageSize(1080, 1920)

        listOf(0, 90, 180, 270).forEach { rotation ->
            val mapping = CameraImageToViewMapping.centerCrop(image, view, rotation)
            val center = mapping.mapPixel(mapping.detectorWidth / 2f, mapping.detectorHeight / 2f)
            assertPoint(center, 540f, 960f, "centre at $rotation degrees")

            val leftQuarter = mapping.mapPixel(
                mapping.detectorWidth / 4f,
                mapping.detectorHeight / 2f,
            )
            val expectedX = if (rotation % 180 == 0) -100f else 180f
            assertPoint(leftQuarter, expectedX, 960f, "left quarter at $rotation degrees")
        }
    }

    @Test
    fun `normalized mapping uses rotated detector dimensions`() {
        val mapping = CameraImageToViewMapping.centerCrop(
            imageSize = CameraImageSize(640, 480),
            viewSize = CameraImageSize(1080, 1920),
            inputRotationDegrees = 90,
        )

        assertPoint(mapping.mapNormalized(0.5f, 0.75f), 540f, 1440f)
        // MediaPipe reports normalized coordinates in the unrotated source image even when
        // ImageProcessingOptions rotates the image for inference.
        assertPoint(mapping.mapImageNormalized(0.5f, 0.75f), 180f, 960f)
        assertPoint(mapping.mapPixel(120f, 320f), 180f, 960f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-quarter-turn rotation`() {
        CameraImageToViewMapping.centerCrop(
            CameraImageSize(640, 480),
            CameraImageSize(1080, 1920),
            45,
        )
    }

    private fun assertPoint(
        actual: CameraImagePoint,
        expectedX: Float,
        expectedY: Float,
        message: String = "point",
    ) {
        assertEquals("$message x", expectedX, actual.x, 0.01f)
        assertEquals("$message y", expectedY, actual.y, 0.01f)
    }
}
