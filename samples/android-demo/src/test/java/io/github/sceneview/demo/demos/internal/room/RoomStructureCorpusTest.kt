package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.RerunCaptureBuilder
import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.DebugPlaneKind
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.FakeRerunImageCodec
import io.github.sceneview.demo.demos.internal.ReplayManifest
import io.github.sceneview.demo.demos.internal.RerunScanFile
import io.github.sceneview.demo.demos.internal.ScanDevice
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

/**
 * The room engine over a folder of real recordings, for whoever holds them:
 *
 * ```
 * SCENEVIEW_SCAN_CORPUS=/path/to/scans SCENEVIEW_SCAN_OUT=/path/to/out \
 *   ./gradlew :samples:android-demo:testDebugUnitTest --rerun --tests '*RoomStructureCorpusTest*'
 * ```
 *
 * Each `*.svscan`, `*.rrd` or kept session directory (the app's `rerun/sessions/<id>/`, pulled from a
 * phone) gets `<name>.json` (area, height, perimeter, walls with their lengths
 * and whether each was seen or deduced, openings; the old measures' figures beside the engine's;
 * timings) and `<name>.png` (the plan from above). Without the two variables the real-scan test is
 * skipped; the synthetic one always runs the same path on a recording built here.
 */
class RoomStructureCorpusTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the corpus of real scans`() {
        val corpus = System.getenv(CORPUS)
        val out = System.getenv(OUT)
        assumeTrue("$CORPUS and $OUT not set", !corpus.isNullOrBlank() && !out.isNullOrBlank())
        val results = RoomCorpus.run(File(corpus!!), File(out!!))
        assertTrue("no *.svscan, *.rrd or session directory under $corpus", results.isNotEmpty())
    }

    @Test
    fun `the corpus runs end to end on a synthetic recording`() {
        val room = RoomStructureTest.OPENINGS
        val pack = syntheticPack()
        val corpus = folder.newFolder("corpus")
        File(corpus, "synthetic-room.svscan").writeBytes(RerunScanFile.write(pack))
        val out = folder.newFolder("out")

        val results = RoomCorpus.run(corpus, out)

        val result = results.single()
        assertNull(result.error)
        val structure = assertNotNull(result.structure).let { result.structure!! }
        val area = structure.area!!.value
        println("RoomStructureCorpusTest synthetic: area $area for ${room.area}, before: measure ${result.beforeMeasureArea}, " +
            "survey ${result.beforeSurvey?.area?.value}; full ${result.fullMs} ms, live ${result.liveMs} ms")
        assertTrue("area $area for ${room.area}", abs(area - room.area) / room.area < MAX_ERROR)

        val root = Json.parseToJsonElement(File(out, "synthetic-room.json").readText()).jsonObject
        val after = root["after"]!!.jsonObject
        assertEquals(area, after["area"]!!.jsonObject["value"]!!.jsonPrimitive.float, 1e-4f)
        assertEquals(4, after["walls"]!!.jsonArray.size)
        assertEquals(2, after["openings"]!!.jsonArray.size)
        assertNotNull(root["before"]!!.jsonObject["roomSurveyor"])
        val png = File(out, "synthetic-room.png").readBytes()
        assertTrue("a plan image", png.size > PlanCanvas.SIGNATURE.size && png.copyOf(PlanCanvas.SIGNATURE.size).contentEquals(PlanCanvas.SIGNATURE))
        assertEquals(1, Json.parseToJsonElement(File(out, "summary.json").readText()).jsonArray.size)
    }

    @Test
    fun `a kept session directory reads like its scan file`() {
        val pack = syntheticPack()
        val corpus = folder.newFolder("corpus")
        File(corpus, "room.svscan").writeBytes(RerunScanFile.write(pack))
        val session = File(corpus, "sessions/0F1E2D3C").apply { mkdirs() }
        for ((name, bytes) in pack.files()) File(session, name).writeBytes(bytes)

        val results = RoomCorpus.run(corpus, folder.newFolder("out")).associateBy { it.name }

        assertEquals(setOf("room", "0F1E2D3C"), results.keys)
        val file = results.getValue("room").structure!!.area!!.value
        val kept = results.getValue("0F1E2D3C").structure!!.area!!.value
        assertEquals(file, kept, 1e-4f)
    }

    /** [RoomStructureTest.OPENINGS] recorded and built into a capture, as the phone keeps it. */
    private fun syntheticPack() = runBlocking {
        val capture = RoomStructureTest.capture("openings", RoomStructureTest.OPENINGS)
        val lens = ReplayManifest.parse(String(FakeRerunImageCodec.showcase().manifest))!!.lens
        val device = ScanDevice("android", "Pixel 9", ScanDevice.TIER_DEPTH, ScanDevice.SOURCE_RAW_DEPTH)
        RerunCaptureBuilder.build(eventsOf(capture), lens, emptyList(), device, capture.cloud, DenseFusion.VOXEL_M)
    }

    /** The journal a phone would keep of [capture]: its walk, then the planes ARCore found. */
    private fun eventsOf(capture: SyntheticCapture): List<ArDebugEvent> {
        val events = ArrayList<ArDebugEvent>()
        for ((i, pose) in capture.poses.withIndex()) events += ArDebugEvent.CameraPose(i * FRAME_NANOS, pose)
        val end = capture.poses.size * FRAME_NANOS
        for ((i, plane) in capture.planes.withIndex()) {
            val kind = when (plane.kind) {
                RoomPlaneKind.Floor -> DebugPlaneKind.Floor
                RoomPlaneKind.Ceiling -> DebugPlaneKind.Ceiling
                RoomPlaneKind.Wall -> DebugPlaneKind.Wall
                RoomPlaneKind.Other -> DebugPlaneKind.Unknown
            }
            events += ArDebugEvent.Plane(end, i + 1, kind, plane.polygon)
        }
        return events
    }

    companion object {
        private const val CORPUS = "SCENEVIEW_SCAN_CORPUS"
        private const val OUT = "SCENEVIEW_SCAN_OUT"
        private const val MAX_ERROR = 0.01f
        private const val FRAME_NANOS = 100_000_000L
    }
}
