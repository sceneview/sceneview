package io.github.sceneview.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import io.github.sceneview.demo.demos.squat.UprightFrame
import io.github.sceneview.demo.demos.squat.yuv420ToUprightRgbHalf
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Before/after cost of the Squat Coach pose pipeline (#4604) on one 640×480 ARCore-sized
 * YUV frame, on whatever CPU runs it — no AR session needed, so it runs on the emulator.
 *
 * - **before**: what the old Body Tracker did *on the render thread* — YUV → NV21 → JPEG
 *   encode → decode → rotate, then a blocking `detect` in `RunningMode.IMAGE`.
 * - **after, render thread**: the plane copy `SquatPosePipeline.onFrame` still does there.
 * - **after, worker**: direct YUV → upright half-res RGB, then the inference — off the
 *   render thread; at most one in flight, extra frames dropped.
 *
 * Results go to logcat under `SquatCoachPerf`. Run without `connectedAndroidTest`:
 * `adb -s emulator-5554 shell am instrument -w -e class io.github.sceneview.demo.SquatPipelineBenchmarkTest …`
 */
@RunWith(AndroidJUnit4::class)
class SquatPipelineBenchmarkTest {

    private val w = 640
    private val h = 480

    // Synthetic YUV_420_888 planes with ARCore's usual layout: interleaved chroma
    // (pixel stride 2), U and V views one byte apart.
    private val y = ByteArray(w * h) { ((it % w) * 255 / w).toByte() }
    private val uv = ByteArray(w * h / 2) { (96 + (it / w) % 64).toByte() }
    private val u = uv.copyOfRange(0, uv.size - 1)
    private val v = uv.copyOfRange(1, uv.size)

    // Portrait back camera, as ARCore reports it.
    private val portrait = UprightFrame.fromTransformedCorners(floatArrayOf(1f, 0f, 1f, 1f, 0f, 0f))!!

    private fun landmarker(): PoseLandmarker {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return PoseLandmarker.createFromOptions(
            context,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("mediapipe/pose_landmarker_lite.task").build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumPoses(1)
                .build(),
        )
    }

    private inline fun timeMs(block: () -> Unit): Double {
        val t = System.nanoTime()
        block()
        return (System.nanoTime() - t) / 1e6
    }

    private fun List<Double>.summary() = String.format(
        java.util.Locale.US, "avg=%.1fms p50=%.1fms max=%.1fms", average(), sorted()[size / 2], max(),
    )

    @Test
    fun beforeAndAfter() {
        val landmarker = landmarker()
        val runs = 30

        // Before: everything on the render thread.
        val oldConvert = mutableListOf<Double>()
        val oldTotal = mutableListOf<Double>()
        repeat(runs + 3) { i ->
            var bitmap: Bitmap? = null
            val c = timeMs {
                val nv21 = ByteArray(y.size + uv.size)
                System.arraycopy(y, 0, nv21, 0, y.size)
                System.arraycopy(uv, 0, nv21, y.size, uv.size)
                val out = ByteArrayOutputStream()
                YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 80, out)
                val bytes = out.toByteArray()
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                bitmap = Bitmap.createBitmap(decoded, 0, 0, w, h, Matrix().apply { postRotate(90f) }, false)
            }
            val d = timeMs { landmarker.detect(BitmapImageBuilder(bitmap!!).build()) }
            if (i >= 3) {
                oldConvert += c
                oldTotal += c + d
            }
        }

        // After: plane copy on the render thread, the rest on a worker.
        val yCopy = ByteArray(y.size)
        val uCopy = ByteArray(u.size)
        val vCopy = ByteArray(v.size)
        val rgb = ByteArray((w / 2) * (h / 2) * 3)
        val direct = ByteBuffer.allocateDirect(rgb.size).order(ByteOrder.nativeOrder())
        val handOff = mutableListOf<Double>()
        val newConvert = mutableListOf<Double>()
        val newWorker = mutableListOf<Double>()
        repeat(runs + 3) { i ->
            val hOff = timeMs {
                System.arraycopy(y, 0, yCopy, 0, y.size)
                System.arraycopy(u, 0, uCopy, 0, u.size)
                System.arraycopy(v, 0, vCopy, 0, v.size)
            }
            var size = 0 to 0
            val c = timeMs {
                size = yuv420ToUprightRgbHalf(yCopy, w, uCopy, vCopy, w, 2, w, h, portrait, rgb)
                direct.clear()
                direct.put(rgb)
                direct.flip()
            }
            val image = ByteBufferImageBuilder(direct, size.first, size.second, MPImage.IMAGE_FORMAT_RGB).build()
            val d = timeMs { landmarker.detect(image) }
            if (i >= 3) {
                handOff += hOff
                newConvert += c
                newWorker += c + d
            }
        }
        landmarker.close()

        Log.i(
            TAG,
            "BEFORE render-thread block per detection: ${oldTotal.summary()} (JPEG convert ${oldConvert.summary()})",
        )
        Log.i(TAG, "AFTER  render-thread block per frame: ${handOff.summary()}")
        Log.i(TAG, "AFTER  worker per detection: ${newWorker.summary()} (direct convert ${newConvert.summary()})")

        assertTrue("render thread must block far less than before", handOff.average() < oldTotal.average() / 10)
    }

    private companion object {
        const val TAG = "SquatCoachPerf"
    }
}
