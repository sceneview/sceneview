package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 3D Scan's camera stream: of the back streams ARCore offers that reach 30 fps, one that meets
 * 1280 × 720 first, then one fixed at 30 fps, then the largest CPU image, so the photos and the
 * depth come from the sharpest image the scan keeps up with. Nothing to pick keeps the session's
 * default config.
 */
class CameraStreamPickTest {

    @Test
    fun `the largest CPU image at 30 fps wins`() {
        val offered = listOf(stream(640, 480), stream(1920, 1080), stream(1280, 720))
        assertEquals(stream(1920, 1080), CameraStreamPick.pick(offered))
    }

    @Test
    fun `a stream that only runs at 60 fps and a front stream are left out`() {
        val offered = listOf(
            stream(3840, 2160, minFps = 60, maxFps = 60),
            stream(2560, 1440, back = false),
            stream(1280, 720),
        )
        assertEquals(stream(1280, 720), CameraStreamPick.pick(offered))
    }

    @Test
    fun `a steady 30 fps stream beats a larger one only on the same side of 1280 x 720`() {
        val offered = listOf(stream(1920, 1080, minFps = 15, maxFps = 30), stream(1280, 720))
        assertEquals(stream(1280, 720), CameraStreamPick.pick(offered))
        // Below the target as well, the steady stream comes first.
        val small = listOf(stream(1024, 768, minFps = 15, maxFps = 30), stream(640, 480))
        assertEquals(stream(640, 480), CameraStreamPick.pick(small))
        // Across the target, steadiness does not win: the stream that meets it does.
        val across = listOf(stream(640, 480), stream(1280, 720, minFps = 15, maxFps = 30))
        assertEquals(stream(1280, 720, minFps = 15, maxFps = 30), CameraStreamPick.pick(across))
        // With no steady stream on offer, the largest that reaches 30 is still better than none.
        val ranged = listOf(stream(1920, 1080, minFps = 15, maxFps = 30), stream(640, 480, minFps = 15, maxFps = 30))
        assertEquals(stream(1920, 1080, minFps = 15, maxFps = 30), CameraStreamPick.pick(ranged))
    }

    /**
     * A 640 × 480 stream fixed at 30 fps must not beat a 1920 × 1080 one that may drop to 15:
     * the session's default config would have taken the larger one, and the scan would fall
     * back to the very image size it is meant to leave.
     */
    @Test
    fun `a stream that meets 1280 x 720 beats a smaller one fixed at 30 fps`() {
        val offered = listOf(stream(640, 480), stream(1920, 1080, minFps = 15, maxFps = 30))
        assertEquals(stream(1920, 1080, minFps = 15, maxFps = 30), CameraStreamPick.pick(offered))
    }

    @Test
    fun `the depth sensor, then the larger GPU texture, break a tie on the CPU image`() {
        val withSensor = stream(1280, 720, depthSensor = true)
        assertEquals(withSensor, CameraStreamPick.pick(listOf(stream(1280, 720), withSensor)))
        val wide = stream(1280, 720, gpuWidth = 1920, gpuHeight = 1080)
        assertEquals(wide, CameraStreamPick.pick(listOf(stream(1280, 720, gpuWidth = 1280, gpuHeight = 720), wide)))
    }

    @Test
    fun `nothing to pick keeps the default config`() {
        assertNull(CameraStreamPick.pick(emptyList()))
        assertNull(CameraStreamPick.pick(listOf(stream(1920, 1080, minFps = 60, maxFps = 60), stream(1280, 720, back = false))))
        assertNull(CameraStreamPick.pick(listOf(stream(0, 0))))
    }

    @Test
    fun `the 1280 x 720 target holds either way round`() {
        assertTrue(CameraStreamPick.meetsTarget(stream(1280, 720)))
        assertTrue(CameraStreamPick.meetsTarget(stream(720, 1280)))
        assertTrue(CameraStreamPick.meetsTarget(stream(1920, 1080)))
        assertFalse(CameraStreamPick.meetsTarget(stream(640, 480)))
        assertFalse(CameraStreamPick.meetsTarget(stream(1024, 768)))
    }

    @Test
    fun `the log line names the stream chosen, what was offered and whether it meets the target`() {
        val offered = listOf(stream(640, 480), stream(1920, 1080, gpuWidth = 1920, gpuHeight = 1080))
        val line = CameraStreamPick.logLine(offered, CameraStreamPick.pick(offered))
        assertEquals(
            "camera: 1920x1080 at 30 fps (gpu 1920x1080), picked from 2 offered [640x480 30, 1920x1080 30]; " +
                "meets 1280x720",
            line,
        )
        val small = listOf(stream(640, 480, minFps = 15, maxFps = 30, depthSensor = true))
        assertEquals(
            "camera: 640x480 at 15-30 fps (depth sensor), picked from 1 offered [640x480 15-30]; below 1280x720",
            CameraStreamPick.logLine(small, CameraStreamPick.pick(small)),
        )
    }

    @Test
    fun `the log line says when the default config is kept`() {
        assertEquals("camera: default config, none of 0 offered holds 30 fps", CameraStreamPick.logLine(emptyList(), null))
        val offered = listOf(stream(1920, 1080, minFps = 60, maxFps = 60))
        assertEquals(
            "camera: default config, none of 1 offered holds 30 fps [1920x1080 60]",
            CameraStreamPick.logLine(offered, null),
        )
    }

    /**
     * During a playback ARCore serves the recording's own stream and ignores the config asked for,
     * so the line says the images come from the recording, then what the scan asked for.
     */
    @Test
    fun `during a playback the log line says the recording's stream serves`() {
        val offered = listOf(stream(640, 480), stream(1920, 1080))
        assertEquals(
            "camera: playback, the recording's own stream serves; asked for 1920x1080 at 30 fps, " +
                "picked from 2 offered [640x480 30, 1920x1080 30]; meets 1280x720",
            CameraStreamPick.logLine(offered, CameraStreamPick.pick(offered), playback = true),
        )
        assertEquals(
            "camera: playback, the recording's own stream serves; asked for default config, none of 0 offered holds 30 fps",
            CameraStreamPick.logLine(emptyList(), null, playback = true),
        )
    }

    private fun stream(
        width: Int,
        height: Int,
        minFps: Int = 30,
        maxFps: Int = 30,
        back: Boolean = true,
        depthSensor: Boolean = false,
        gpuWidth: Int = 0,
        gpuHeight: Int = 0,
    ) = CameraStream(width, height, minFps, maxFps, back, depthSensor, gpuWidth, gpuHeight)
}
