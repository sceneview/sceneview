package io.github.sceneview.demo.demos.internal

import io.github.sceneview.ar.camera.CameraImageSize
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraMappingProbeTest {

    // What ARCore answers for a 640x480 image drawn centred in a 1080x1920 portrait view.
    private val portrait = CameraImageCentreSample(
        imageSize = CameraImageSize(640, 480),
        inputRotationDegrees = 90,
        centreInViewX = 540f,
        centreInViewY = 960f,
    )

    @Test
    fun `the report puts the image centre next to the view centre`() {
        assertEquals(
            "image centre in view = (540.0, 960.0), viewSize/2 = (540.0, 960.0) " +
                "[image 640x480, view 1080x1920, input rotation 90]",
            cameraMappingReport(portrait, viewWidth = 1080, viewHeight = 1920),
        )
    }

    @Test
    fun `a display geometry set for another view shows as a gap`() {
        // The view measured by Compose is 200 px shorter than the one ARCore draws into.
        assertEquals(
            "image centre in view = (540.0, 960.0), viewSize/2 = (540.0, 860.0) " +
                "[image 640x480, view 1080x1720, input rotation 90]",
            cameraMappingReport(portrait, viewWidth = 1080, viewHeight = 1720),
        )
    }

    @Test
    fun `one line per geometry, not one per detector pass`() {
        val lines = mutableListOf<String>()
        val probe = CameraMappingProbe(log = lines::add)
        val landscape = CameraImageCentreSample(
            imageSize = CameraImageSize(640, 480),
            inputRotationDegrees = 0,
            centreInViewX = 960f,
            centreInViewY = 540f,
        )

        probe.report(portrait, 0, 0) // not laid out yet: nothing to compare against
        repeat(3) { probe.report(portrait, 1080, 1920) }
        repeat(3) { probe.report(landscape, 1920, 1080) }
        probe.report(portrait, 1080, 1920)

        assertEquals(3, lines.size)
    }
}
