package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The live walls counted over recorded scans, before the survey's rules and with them.
 *
 * Run over real scans with `SCENEVIEW_SCAN_CORPUS` (a directory of `.svscan` and `.rrd` files or
 * session directories) and `SCENEVIEW_SCAN_OUT` (where the counts are written) set; without them
 * that run is skipped, and the synthetic recording below checks the same path end to end.
 */
class LiveWallsCorpusTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `the recorded scans, counted before and after the rules`() {
        val corpus = System.getenv(CORPUS)?.let(::File)
        val out = System.getenv(OUT)?.let(::File)
        assumeTrue("$CORPUS and $OUT are not set", corpus != null && corpus.isDirectory && out != null)
        val results = LiveWallsCorpus.run(corpus!!, out!!)
        results.forEach { println(it) }
        assertTrue("no capture read under $corpus", results.isNotEmpty())
    }

    @Test
    fun `a recorded turn with furniture shows only the room's four walls`() {
        val root = temporaryFolder.newFolder("corpus")
        val pack = RerunCapturePack("{}".toByteArray(), ArDebugLogWriter.write(furnishedWalk()).toByteArray(), ByteArray(0))
        File(root, "walk." + RerunScanFile.EXTENSION).writeBytes(RerunScanFile.write(pack))
        val session = File(root, "session").apply { mkdirs() }
        for ((name, bytes) in pack.files()) File(session, name).writeBytes(bytes)
        val out = temporaryFolder.newFolder("out")

        val results = LiveWallsCorpus.run(root, out)

        assertEquals(listOf("session", "walk.svscan"), results.map { it.name })
        assertTrue(File(out, "summary.json").isFile)
        for (result in results) {
            assertTrue("before: $result", result.before.final > result.after.final)
            assertEquals("after: $result", 4, result.after.final)
            assertEquals("on the record screen: $result", 4, result.screen.walls)
            assertTrue("the room done: $result", result.screen.done)
            assertTrue("the room named: $result", result.screen.named)
        }
    }

    /** [ScanWallWalk]'s turn with a sofa, a shelf, a short slanted piece, a refit and a shard added. */
    private fun furnishedWalk(): List<ArDebugEvent> {
        val walk = ScanWallWalk.events()
        val start = walk.first().nanos
        val floor = ScanWallWalk.FLOOR_Y
        fun at(seconds: Float) = start + (seconds * 1e9f).toLong()
        fun plane(seconds: Float, id: Int, x0: Float, z0: Float, x1: Float, z1: Float, bottom: Float, top: Float) =
            ArDebugEvent.Plane(
                at(seconds), id, DebugPlaneKind.Wall,
                floatArrayOf(
                    x0, floor + bottom, z0, x1, floor + bottom, z1,
                    x1, floor + top, z1, x0, floor + top, z0,
                ),
            )
        fun turned(seconds: Float, id: Int, cx: Float, cz: Float, length: Float, top: Float): ArDebugEvent {
            val a = SLANT_DEGREES * PI.toFloat() / 180f
            val hx = cos(a) * length / 2f
            val hz = sin(a) * length / 2f
            return plane(seconds, id, cx - hx, cz - hz, cx + hx, cz + hz, bottom = 0.1f, top = top)
        }
        val added = listOf(
            // A sofa's front and a shelf's: not as tall as a wall.
            plane(2f, 30, -1.8f, 0.9f, -0.3f, 0.9f, bottom = 0.3f, top = 0.8f),
            plane(2f, 31, -1f, 1.4f, 0.6f, 1.4f, bottom = 0.05f, top = 0.9f),
            // A short piece slanted off the room.
            turned(2f, 32, -1f, 0.6f, 0.8f, top = 1.8f),
            // A piece first fitted as tall as a wall, then refit to the chair back it was.
            plane(5f, 33, 0.8f, 0.2f, 1.8f, 0.2f, bottom = 0.1f, top = 1.5f),
            plane(8f, 33, 0.8f, 0.2f, 1.8f, 0.2f, bottom = 0.3f, top = 0.9f),
            // Once the back wall is seen, a piece of it fitted slanted.
            turned(20f, 34, 0.5f, 1.67f, 0.8f, top = 1.5f),
        )
        return (walk + added).sortedBy { it.nanos }
    }

    private companion object {
        const val CORPUS = "SCENEVIEW_SCAN_CORPUS"
        const val OUT = "SCENEVIEW_SCAN_OUT"
        const val SLANT_DEGREES = 19f
    }
}
