package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ResultFilmTest {
    @Test
    fun `the film is an 8 second sweep, whole frames at its rate`() {
        assertEquals(8f, ResultFilm.SECONDS, 0f)
        assertEquals(ResultFilm.frameCount.toFloat() / ResultFilm.FPS, ResultFilm.SECONDS, 1f / ResultFilm.FPS)
    }

    @Test
    fun `the clip is 720p, at the film's rate, and stops on its own after twice the sweep`() {
        val video = ResultFilm.video
        assertEquals(720, video.maxShortEdge)
        assertEquals(ResultFilm.FPS, video.frameRate)
        // A safety net only: the sweep plays in frame time, so it may run long on a slow device.
        assertTrue(video.maxDurationMillis >= ResultFilm.SECONDS * 2000f)
    }

    @Test
    fun `it leaves the start, swings 40 degrees each way, and lands back on it`() {
        assertEquals(0f, ResultFilm.azimuthOffsetAt(0f), 1e-4f)
        assertEquals(0f, ResultFilm.azimuthOffsetAt(ResultFilm.SECONDS), 1e-3f)
        val offsets = (0..ResultFilm.frameCount).map { ResultFilm.azimuthOffsetAt(it.toFloat() / ResultFilm.FPS) }
        assertEquals(ResultFilm.SWING_DEGREES, offsets.max(), 0.5f)
        assertEquals(-ResultFilm.SWING_DEGREES, offsets.min(), 0.5f)
        // One way first, then the other: out, through the start, out and back.
        assertTrue(offsets.indexOf(offsets.max()) < offsets.indexOf(offsets.min()))
    }

    @Test
    fun `it starts and lands at rest, and never jumps from one frame to the next`() {
        val step = 1f / ResultFilm.FPS
        val first = abs(ResultFilm.azimuthOffsetAt(step))
        val last = abs(ResultFilm.azimuthOffsetAt(ResultFilm.SECONDS - step))
        val steps = (1..ResultFilm.frameCount).map {
            abs(ResultFilm.azimuthOffsetAt(it * step) - ResultFilm.azimuthOffsetAt((it - 1) * step))
        }
        assertTrue("first frame turns $first°", first < 0.1f)
        assertTrue("last frame turns $last°", last < 0.1f)
        assertTrue("a frame turns ${steps.max()}°", steps.max() < 2.5f)
    }

    @Test
    fun `before and after the sweep the camera is on the start`() {
        assertEquals(0f, ResultFilm.azimuthOffsetAt(-1f), 0f)
        assertEquals(0f, ResultFilm.azimuthOffsetAt(ResultFilm.SECONDS + 3f), 1e-3f)
    }

    @Test
    fun `a take drops on a touch or a stopped recording, never once landed`() {
        assertFalse(ResultFilm.aborts(landed = false, cameraFilming = true, recording = true))
        assertTrue(ResultFilm.aborts(landed = false, cameraFilming = false, recording = true))
        assertTrue(ResultFilm.aborts(landed = false, cameraFilming = true, recording = false))
        assertFalse(ResultFilm.aborts(landed = true, cameraFilming = false, recording = true))
    }
}
