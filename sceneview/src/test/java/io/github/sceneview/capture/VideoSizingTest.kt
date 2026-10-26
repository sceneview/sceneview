package io.github.sceneview.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The size a [SceneRecorder] video is encoded at: the scene view's own aspect ratio, fitted in a
 * 1080p box oriented like the view, so a portrait phone records a portrait video with no black
 * bars, and stepped down only when the device's encoder refuses the size.
 */
class VideoSizingTest {

    @Test
    fun `a 16 by 9 portrait view records at exactly 1080 by 1920`() {
        assertEquals(VideoSize(1080, 1920), chooseVideoSize(1080, 1920, 1920, 1080))
    }

    @Test
    fun `a 20 by 9 phone keeps its aspect ratio inside the 1080p box`() {
        // 1080 x 2400 scaled by 0.8: the long edge hits 1920, the short edge follows.
        assertEquals(VideoSize(864, 1920), chooseVideoSize(1080, 2400, 1920, 1080))
    }

    @Test
    fun `a landscape view records a landscape video`() {
        assertEquals(VideoSize(1920, 864), chooseVideoSize(2400, 1080, 1920, 1080))
    }

    @Test
    fun `a small view is recorded at full 1080p because the mirror re-renders the scene`() {
        assertEquals(VideoSize(1080, 1920), chooseVideoSize(540, 960, 1920, 1080))
    }

    @Test
    fun `both edges are even, as H264 4-2-0 requires`() {
        val size = chooseVideoSize(1001, 1999, 1920, 1080)!!
        assertEquals(0, size.width % 2)
        assertEquals(0, size.height % 2)
    }

    @Test
    fun `a square view fits the short edge of the box`() {
        assertEquals(VideoSize(1080, 1080), chooseVideoSize(800, 800, 1920, 1080))
    }

    @Test
    fun `an encoder that wants multiples of 16 gets them`() {
        // 1080 x 2340 fits as 886 x 1920 with even edges; 16-aligned it is 880 x 1920.
        val size = chooseVideoSize(1080, 2340, 1920, 1080) { w, h -> w % 16 == 0 && h % 16 == 0 }
        assertEquals(VideoSize(880, 1920), size)
    }

    @Test
    fun `an encoder without 1080p steps down to 720p`() {
        val size = chooseVideoSize(1080, 1920, 1920, 1080) { w, h -> maxOf(w, h) <= 1280 }
        assertEquals(VideoSize(720, 1280), size)
    }

    @Test
    fun `a smaller requested box is honoured`() {
        assertEquals(VideoSize(720, 1280), chooseVideoSize(1080, 1920, 1280, 720))
    }

    @Test
    fun `no size when the encoder supports none`() {
        assertNull(chooseVideoSize(1080, 1920, 1920, 1080) { _, _ -> false })
    }

    @Test
    fun `no size before the view has been laid out`() {
        assertNull(chooseVideoSize(0, 1920, 1920, 1080))
        assertNull(chooseVideoSize(1080, 0, 1920, 1080))
    }

    @Test
    fun `video settings reject impossible values`() {
        assertThrows(IllegalArgumentException::class.java) { VideoSettings(maxLongEdge = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            VideoSettings(maxLongEdge = 720, maxShortEdge = 1080)
        }
        assertThrows(IllegalArgumentException::class.java) { VideoSettings(frameRate = 0) }
        assertThrows(IllegalArgumentException::class.java) { VideoSettings(bitRate = 0) }
        assertThrows(IllegalArgumentException::class.java) { VideoSettings(maxDurationMillis = -1) }
    }

    @Test
    fun `default video settings are 1080p at 30 fps, silent, unlimited`() {
        val settings = VideoSettings()
        assertEquals(1920, settings.maxLongEdge)
        assertEquals(1080, settings.maxShortEdge)
        assertEquals(30, settings.frameRate)
        assertEquals(false, settings.recordAudio)
        assertEquals(0L, settings.maxDurationMillis)
    }
}
