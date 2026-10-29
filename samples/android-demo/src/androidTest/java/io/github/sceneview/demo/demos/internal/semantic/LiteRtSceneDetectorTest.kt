package io.github.sceneview.demo.demos.internal.semantic

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import kotlin.math.abs

/**
 * The furniture detector as the app runs it: the downloaded `.tflite` mapped in place and run by
 * LiteRT on the device, in the build type the instrumentation targets — a minified one shows that
 * R8 left LiteRT's native bridge whole. The model and the photo are pushed to the device, never
 * bundled; the test is skipped without them:
 *
 * ```
 * adb push yolox_tiny.tflite dog.jpg /data/local/tmp/
 * adb shell am instrument -w -e sceneModel /data/local/tmp/yolox_tiny.tflite \
 *   -e sceneImage /data/local/tmp/dog.jpg \
 *   -e class io.github.sceneview.demo.demos.internal.semantic.LiteRtSceneDetectorTest \
 *   <test package>/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * The photo is YOLOX's own `assets/dog.jpg`; the expected classes and scores are those of the ONNX
 * export the model was converted from, on the same photo.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtSceneDetectorTest {
    @Test
    fun findsWhatTheOnnxExportFindsOnTheReferencePhoto() {
        val args = InstrumentationRegistry.getArguments()
        val modelPath = args.getString(MODEL_ARG)
        val imagePath = args.getString(IMAGE_ARG)
        assumeTrue(
            "-e $MODEL_ARG and -e $IMAGE_ARG unset: no model on the device",
            modelPath != null && imagePath != null,
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = pulled(modelPath!!, File(context.cacheDir, "scene-detector-test.tflite"))
        val photo = pulled(imagePath!!, File(context.cacheDir, "scene-detector-test.jpg")).readBytes()
        val image = sceneImageOf { options -> BitmapFactory.decodeByteArray(photo, 0, photo.size, options) }
        assertNotNull("the photo did not decode", image)

        val mapped = FileInputStream(model).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.channel.size()) }
        val found = LiteRtSceneDetector(mapped, name = SceneModel.NAME, keep = { true }).use { it.detect(image!!) }

        assertEquals(found.joinToString { "${it.name} ${it.score}" }, EXPECTED.keys, found.map { it.name }.toSet())
        for (detection in found) {
            val expected = EXPECTED.getValue(detection.name)
            assertTrue(
                "${detection.name}: ${detection.score}, expected $expected",
                abs(detection.score - expected) <= SCORE_TOLERANCE,
            )
        }
    }

    /** [devicePath], copied through the shell — which can read `/data/local/tmp` — into [into]. */
    private fun pulled(devicePath: String, into: File): File {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("cat $devicePath").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input -> FileOutputStream(into).use { input.copyTo(it) } }
        }
        assertTrue("$devicePath could not be read", into.length() > 0)
        return into
    }

    private companion object {
        const val MODEL_ARG = "sceneModel"
        const val IMAGE_ARG = "sceneImage"
        const val SCORE_TOLERANCE = 0.05f

        /** The ONNX export's scores on `dog.jpg`, every class kept. */
        val EXPECTED = mapOf("bicycle" to 0.886f, "car" to 0.698f, "cat" to 0.553f, "dog" to 0.542f, "truck" to 0.513f)
    }
}
