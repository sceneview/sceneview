package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoTransportTest {

    @Test
    fun `a video that could not be prepared is not shown playing`() {
        // `rememberMediaPlayer` ended in `Failed`: the demo starts with playback requested and failed.
        assertEquals(
            VideoTransport(playing = false, enabled = false),
            videoTransport(requested = true, failed = true, qa = false, seekFallback = false),
        )
    }

    @Test
    fun `a video that failed stays stopped whatever was requested`() {
        for (requested in listOf(true, false)) {
            for (qa in listOf(true, false)) {
                for (seekFallback in listOf(true, false)) {
                    assertEquals(
                        VideoTransport(playing = false, enabled = false),
                        videoTransport(requested, failed = true, qa = qa, seekFallback = seekFallback),
                    )
                }
            }
        }
    }

    @Test
    fun `a working video shows what was requested and can be toggled`() {
        assertEquals(
            VideoTransport(playing = true, enabled = true),
            videoTransport(requested = true, failed = false, qa = false, seekFallback = false),
        )
        assertEquals(
            VideoTransport(playing = false, enabled = true),
            videoTransport(requested = false, failed = false, qa = false, seekFallback = false),
        )
    }

    @Test
    fun `qa holds the paused frame unless its seek fell back to playback`() {
        assertEquals(
            VideoTransport(playing = false, enabled = false),
            videoTransport(requested = false, failed = false, qa = true, seekFallback = false),
        )
        assertEquals(
            VideoTransport(playing = true, enabled = true),
            videoTransport(requested = true, failed = false, qa = true, seekFallback = true),
        )
    }
}
