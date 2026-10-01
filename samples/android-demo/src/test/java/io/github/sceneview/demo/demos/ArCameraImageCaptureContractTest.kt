package io.github.sceneview.demo.demos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The demos that read the AR camera image must acquire it from the frame `onSessionUpdated`
 * just handed them, and must never let an acquisition failure escape the render callback.
 *
 * Play vitals, 4.47.0: the AR Image demo kept `latestFrame` and acquired its camera image from
 * the "Capture this view" tap, on a frame that was no longer current, and crashed with
 * `DeadlineExceededException`. The ML Object Label demo re-threw `cameraImage()` failures from
 * its render callback.
 */
class ArCameraImageCaptureContractTest {

    private fun demo(name: String): String {
        val file = File("src/main/java/io/github/sceneview/demo/demos/$name")
        assertTrue("Expected ${file.absolutePath}", file.exists())
        return file.readText()
    }

    @Test
    fun `AR Image capture acquires on the next frame, not from a stored one`() {
        val source = demo("ARImageDemo.kt")
        assertFalse("ARImageDemo must not keep a Frame for later", "latestFrame" in source)
        val callback = source.substringAfter("onSessionUpdated = {")
        assertTrue(
            "The camera image must be acquired inside onSessionUpdated, on the frame it receives",
            Regex("""if\s*\(captureRequested\)[\s\S]*?frame\.cameraImage\(\)""").containsMatchIn(callback)
        )
    }

    @Test
    fun `ML Object Label never re-throws a camera image failure`() {
        val source = demo("ARMLObjectLabelDemo.kt")
        assertFalse("ARMLObjectLabelDemo must not keep a Frame for later", "latestFrame" in source)
        val acquisition = source.substringAfter("val cameraImage =").substringBefore("if (cameraImage == null)")
        assertFalse("cameraImage() failures must not be re-thrown: $acquisition", "throw" in acquisition)
    }
}
