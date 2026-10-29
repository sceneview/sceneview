package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.CameraStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The glue between ARCore's offered camera configs and the 3D Scan's stream choice: the session
 * keeps its default config when the offer cannot be read or holds nothing, the config returned is
 * the one whose stream won, and the bake log's line says which happened.
 */
class RerunScanCameraTest {

    @Test
    fun `a read that throws keeps the default config and says why`() {
        val chosen = RerunScanCamera.select(
            offered = { throw IllegalStateException("no camera") },
            streamOf = Config::stream,
            default = { DEFAULT },
        )
        assertSame(DEFAULT, chosen)
        assertEquals(
            "camera: default config, the offered streams could not be read " +
                "(java.lang.IllegalStateException: no camera)",
            RerunScanCamera.line,
        )
    }

    @Test
    fun `a stream that cannot be read is a failed read too`() {
        val chosen = RerunScanCamera.select(
            offered = { listOf(Config("broken", CameraStream(1920, 1080, 30, 30))) },
            streamOf = { throw IllegalArgumentException("no size") },
            default = { DEFAULT },
        )
        assertSame(DEFAULT, chosen)
        assertEquals(
            "camera: default config, the offered streams could not be read " +
                "(java.lang.IllegalArgumentException: no size)",
            RerunScanCamera.line,
        )
    }

    @Test
    fun `an empty offer keeps the default config`() {
        assertSame(DEFAULT, select(emptyList()))
        assertEquals("camera: default config, none of 0 offered holds 30 fps", RerunScanCamera.line)
    }

    @Test
    fun `the config returned is the one whose stream won, by its position`() {
        val small = Config("small", CameraStream(640, 480, 30, 30))
        val large = Config("large", CameraStream(1920, 1080, 15, 30))
        assertSame(large, select(listOf(small, large)))
        assertEquals(
            "camera: 1920x1080 at 15-30 fps, picked from 2 offered [640x480 30, 1920x1080 15-30]; meets 1280x720",
            RerunScanCamera.line,
        )
        // Two configs whose streams are equal: the first offered, never one found again by equality.
        val first = Config("first", CameraStream(1280, 720, 30, 30))
        val second = Config("second", CameraStream(1280, 720, 30, 30))
        assertSame(first, select(listOf(first, second)))
    }

    @Test
    fun `during a playback the line says the recording's stream serves, read or not`() {
        val small = Config("small", CameraStream(640, 480, 30, 30))
        val chosen = RerunScanCamera.select(
            offered = { listOf(small) },
            streamOf = Config::stream,
            default = { DEFAULT },
            playback = true,
        )
        assertSame(small, chosen)
        assertEquals(
            "camera: playback, the recording's own stream serves; asked for 640x480 at 30 fps, " +
                "picked from 1 offered [640x480 30]; below 1280x720",
            RerunScanCamera.line,
        )
        RerunScanCamera.select(
            offered = { throw IllegalStateException("no camera") },
            streamOf = Config::stream,
            default = { DEFAULT },
            playback = true,
        )
        assertEquals(
            "camera: playback, the recording's own stream serves; asked for default config, " +
                "the offered streams could not be read (java.lang.IllegalStateException: no camera)",
            RerunScanCamera.line,
        )
    }

    private fun select(offered: List<Config>): Config =
        RerunScanCamera.select(offered = { offered }, streamOf = Config::stream, default = { DEFAULT })

    /** A stand-in for ARCore's `CameraConfig`, which only a live session can make. */
    private data class Config(val name: String, val stream: CameraStream)

    private companion object {
        val DEFAULT = Config("default", CameraStream(0, 0, 0, 0))
    }
}
