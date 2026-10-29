package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.RerunCaptureBuilder
import io.github.sceneview.demo.demos.ScanPhoto
import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthBackProjection
import io.github.sceneview.demo.demos.internal.RerunScanFile
import io.github.sceneview.demo.demos.internal.ScanDevice
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

/**
 * The finished model on real scans, for a machine that has them:
 *
 * ```
 * SCENEVIEW_SCAN_CORPUS=/path/to/scans SCENEVIEW_SCAN_OUT=/path/to/out \
 *   ./gradlew :samples:android-demo:testDebugUnitTest --tests '*CleanModelCorpusTest*'
 * ```
 *
 * Every `*.svscan` and `*.rrd` in the corpus, and every session folder the app keeps
 * (`files/rerun/sessions/<id>/`, pulled with `run-as`), gets a folder in the output ([CleanModelCorpus]:
 * both GLBs, both thumbnails, `stats.json`) and a line in `summary.json`. Skipped without the
 * two variables. The second test runs the same code on a synthetic recording, so it is known to
 * work end to end before a real scan reaches it.
 */
class CleanModelCorpusTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `every scan of the corpus is built before and after`() {
        val corpus = System.getenv(CORPUS)?.let(::File)
        val out = System.getenv(OUT)?.let(::File)
        assumeTrue("$CORPUS and $OUT not set", corpus != null && out != null)
        val scans = checkNotNull(corpus).walkTopDown()
            .filter(CleanModelCorpus::isScan)
            .sortedBy { it.path }
            .toList()
        assumeTrue("no *.svscan, *.rrd or session folder in $corpus", scans.isNotEmpty())
        val results = runAll(scans, checkNotNull(out))
        results.forEach(::println)
    }

    @Test
    fun `a synthetic recording goes through the corpus run end to end`() {
        val corpus = folder.newFolder("corpus")
        val out = folder.newFolder("out")
        File(corpus, "synthetic-room.${RerunScanFile.EXTENSION}").writeBytes(RerunScanFile.write(syntheticRecording()))

        val results = runAll(corpus.listFiles()!!.toList(), out)

        val stats = results.single()
        println("corpus run, synthetic recording: $stats")
        assertTrue("$stats", "error" !in stats && "skipped" !in stats)
        val scanOut = File(out, "synthetic-room")
        for (name in listOf("before.glb", "after.glb", "before.png", "after.png", "stats.json")) {
            assertTrue("$name written", File(scanOut, name).length() > 0)
        }
        val after = stats["after"]!!.jsonObject
        val before = stats["before"]!!.jsonObject
        assertEquals("", after["glb_problems"]!!.jsonPrimitive.content)
        assertTrue("the photos texture it: $after", after["atlas"]!!.jsonPrimitive.content != "none")
        assertTrue(after["triangles"]!!.jsonPrimitive.int < before["triangles"]!!.jsonPrimitive.int)
        val keys = listOf(
            "glb_limit_bytes", "glb_guarded", "atlas_halvings", "raw_kept", "out_of_memory_in",
            "textured_share", "floor_area_ratio", "floor_holes_added",
        )
        for (key in keys) {
            assertTrue("$key missing: $after", key in after)
        }
        assertTrue(after["glb_bytes"]!!.jsonPrimitive.int <= after["glb_limit_bytes"]!!.jsonPrimitive.int)
        assertEquals("false", after["raw_kept"]!!.jsonPrimitive.content)
        assertEquals(
            Json.parseToJsonElement(File(scanOut, "stats.json").readText()),
            stats,
        )
        assertTrue(File(out, "summary.json").length() > 0)
        assertTrue(JvmImages.decode(File(scanOut, "after.png").readBytes()) != null)
    }

    @Test
    fun `a scan whose finish runs out of memory is written as its raw surface`() {
        val scan = File(folder.newFolder("corpus"), "synthetic-room.${RerunScanFile.EXTENSION}")
        scan.writeBytes(RerunScanFile.write(syntheticRecording()))
        val out = File(folder.newFolder("out"), "synthetic-room")

        // The heap runs out while cleaning, as on a phone's too small for the scan.
        val stats = CleanModelCorpus.run(scan, out) { done -> if (done >= CLEANING) throw OutOfMemoryError("finish at $done") }

        println("corpus run, out of memory: $stats")
        assertTrue("$stats", "error" !in stats && "skipped" !in stats)
        val after = stats["after"]!!.jsonObject
        assertEquals("true", after["raw_kept"]?.jsonPrimitive?.content)
        assertTrue(
            "after.glb is not the raw surface's",
            File(out, "after.glb").readBytes().contentEquals(File(out, "before.glb").readBytes()),
        )
        assertTrue(JvmImages.decode(File(out, "after.png").readBytes()) != null)
    }

    private fun runAll(scans: List<File>, out: File): List<JsonObject> {
        out.mkdirs()
        val results = scans.map { scan -> CleanModelCorpus.run(scan, File(out, scan.nameWithoutExtension)) }
        File(out, "summary.json").writeText(results.joinToString(",\n", "[\n", "\n]\n"))
        return results
    }

    /**
     * The ray-cast room as the recorder saves a depth scan: a camera pose and a JPEG for each of
     * its key photos, and its dense surfels fused from the walk's depth.
     */
    private fun syntheticRecording() = runBlocking {
        val shots = TexturedSyntheticRoom.photos()
        val events = ArrayList<ArDebugEvent>()
        val photos = shots.mapIndexed { i, shot ->
            val nanos = (i + 1) * FRAME_NANOS
            val path = String.format(Locale.US, "scan/frame-%04d.jpg", i + 1)
            events += ArDebugEvent.CameraPose(nanos, shot.pose)
            events += ArDebugEvent.Image(nanos, path)
            ScanPhoto(path, JvmImages.jpeg(shot.argb, shot.width, shot.height), shot.pose)
        }
        val fusion = DenseFusion()
        TexturedSyntheticRoom.frames().forEach { fusion.add(DepthBackProjection.project(it)) }
        RerunCaptureBuilder.build(
            events = events,
            lens = TexturedSyntheticRoom.PHOTO_LENS,
            photos = photos,
            device = ScanDevice("android", "synthetic", ScanDevice.TIER_DEPTH, ScanDevice.SOURCE_RAW_DEPTH),
            dense = fusion.cloud(minViews = DenseFusion.MIN_VIEWS),
        )
    }

    private companion object {
        const val CORPUS = "SCENEVIEW_SCAN_CORPUS"
        const val OUT = "SCENEVIEW_SCAN_OUT"
        const val FRAME_NANOS = 100_000_000L

        /** The finish's progress while it cleans the surface. */
        const val CLEANING = 0.2f
    }
}
