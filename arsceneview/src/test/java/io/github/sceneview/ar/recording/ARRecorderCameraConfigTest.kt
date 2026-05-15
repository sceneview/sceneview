package io.github.sceneview.ar.recording

import android.util.Size
import com.google.ar.core.CameraConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM unit tests for [ARRecorder.pickCameraConfig] — the pure camera-config selection
 * logic behind the `recordingResolution` parameter added in
 * [#1065](https://github.com/sceneview/sceneview/issues/1065).
 *
 * [CameraConfig] declares a `protected CameraConfig()` no-arg constructor, so a test double
 * subclass can stub the JNI-backed `getImageSize()` without an emulator. `getImageSize()`
 * returns an [android.util.Size], itself shadowed by Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ARRecorderCameraConfigTest {

    /** [CameraConfig] test double: stubs only the image size the selector reads. */
    private class FakeCameraConfig(private val size: Size) : CameraConfig() {
        override fun getImageSize(): Size = size
        override fun toString(): String = "FakeCameraConfig(${size.width}x${size.height})"
    }

    private fun config(width: Int, height: Int): CameraConfig = FakeCameraConfig(Size(width, height))

    @Test
    fun `empty config list yields null so the caller leaves the session untouched`() {
        assertNull(ARRecorder.pickCameraConfig(emptyList(), Size(1920, 1080)))
    }

    @Test
    fun `single config is always returned regardless of how far it is from the request`() {
        val only = config(640, 480)
        assertEquals(only, ARRecorder.pickCameraConfig(listOf(only), Size(1920, 1080)))
    }

    @Test
    fun `exact match wins over every other config`() {
        val exact = config(1920, 1080)
        val configs = listOf(config(640, 480), exact, config(3840, 2160))
        assertEquals(exact, ARRecorder.pickCameraConfig(configs, Size(1920, 1080)))
    }

    @Test
    fun `picks the config with the closest pixel count to the request`() {
        val configs = listOf(
            config(640, 480),    // 307_200 px
            config(1280, 720),   // 921_600 px — closest to 1080p (2_073_600)? no
            config(1920, 1080),  // 2_073_600 px — closest
            config(3840, 2160),  // 8_294_400 px
        )
        assertEquals(
            config(1920, 1080).imageSize,
            ARRecorder.pickCameraConfig(configs, Size(1920, 1080))!!.imageSize,
        )
    }

    @Test
    fun `requesting 1080p on a Pixel-class config list does NOT return the 640x480 default`() {
        // The exact bug from #1065: ARCore's default is the low-res config, but a 1080p
        // request must escape it.
        val configs = listOf(config(640, 480), config(1280, 960), config(1920, 1080))
        val picked = ARRecorder.pickCameraConfig(configs, Size(1920, 1080))!!
        assertEquals(1920, picked.imageSize.width)
        assertEquals(1080, picked.imageSize.height)
    }

    @Test
    fun `tie on absolute pixel distance breaks towards the higher-resolution config`() {
        // Request 1_000_000 px. Two configs are equidistant: 800x1000 (800_000, -200_000)
        // and 1000x1200 (1_200_000, +200_000). The higher-res one must win.
        val low = config(800, 1000)
        val high = config(1000, 1200)
        val picked = ARRecorder.pickCameraConfig(listOf(low, high), Size(1000, 1000))!!
        assertEquals(high.imageSize, picked.imageSize)
    }

    @Test
    fun `request smaller than every available config still returns the closest (smallest)`() {
        val configs = listOf(config(1280, 720), config(1920, 1080), config(3840, 2160))
        val picked = ARRecorder.pickCameraConfig(configs, Size(320, 240))!!
        assertEquals(config(1280, 720).imageSize, picked.imageSize)
    }
}
