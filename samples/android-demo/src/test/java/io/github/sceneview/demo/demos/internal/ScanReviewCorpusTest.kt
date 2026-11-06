package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.RerunCaptureBuilder
import io.github.sceneview.demo.demos.internal.room.PlanCanvas
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Review before you leave on real scans: every `.svscan` and `.rrd` under `SCENEVIEW_SCAN_CORPUS`,
 * its dense cloud read as the review reads the capture at Stop ([ScanReview.of]), written to
 * `SCENEVIEW_SCAN_OUT`:
 *
 * - `<scan>-review.png`: the scan from above, ceiling off, x right and z down — on the left as
 *   scanned, on the right the same with the parts the review lists framed (amber missing wall,
 *   red floor hole, blue sparse area) and their count under it. Same framing, at most 1,000 px wide.
 * - `<scan>-review.txt` and `review-summary.txt`: the parts in words and how long the read took.
 *
 * Skipped when either variable is unset: scans stay off the repository. Run with
 * `SCENEVIEW_SCAN_CORPUS=<dir> SCENEVIEW_SCAN_OUT=<dir> ./gradlew :samples:android-demo:testDebugUnitTest
 * --tests '*ScanReviewCorpusTest' --rerun`.
 */
class ScanReviewCorpusTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `every scan in the corpus, as reviewed at Stop`() {
        val corpus = System.getenv(CORPUS)?.takeIf { it.isNotBlank() }?.let(::File)
        val out = System.getenv(OUT)?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("$CORPUS and $OUT not both set", corpus != null && out != null)
        val results = ScanReviewCorpus.run(corpus!!, out!!)
        assumeTrue("no .svscan or .rrd under $corpus", results.isNotEmpty())
    }

    @Test
    fun `a synthetic room missing a wall strip and a floor square, end to end`() {
        val corpus = temp.newFolder("corpus")
        val out = System.getenv(SYNTHETIC_OUT)?.takeIf { it.isNotBlank() }?.let(::File) ?: temp.newFolder("out")
        File(corpus, "synthetic-living-room.svscan").writeBytes(ScanReviewCorpus.recording(ScanReviewTest.damaged))
        File(corpus, "synthetic-living-room-whole.svscan").writeBytes(ScanReviewCorpus.recording(RescanZonesTest.room))
        val results = ScanReviewCorpus.run(corpus, out).associateBy { it.name }
        val damaged = results.getValue("synthetic-living-room.svscan")
        val whole = results.getValue("synthetic-living-room-whole.svscan")
        assertEquals(damaged.toString(), listOf(RescanKind.MissingWall, RescanKind.FloorHole), damaged.review.zones.map { it.kind })
        assertTrue(whole.toString(), whole.review.clear)
        assertTrue(File(out, "synthetic-living-room.svscan-review.png").length() > 0)
        assertTrue(File(out, ScanReviewCorpus.SUMMARY).readText().contains("synthetic-living-room.svscan: 2 parts"))
    }

    private companion object {
        const val CORPUS = "SCENEVIEW_SCAN_CORPUS"
        const val OUT = "SCENEVIEW_SCAN_OUT"

        /** Where the synthetic run's boards go when set, to look at; a temp folder otherwise. */
        const val SYNTHETIC_OUT = "SCENEVIEW_SYNTHETIC_OUT"
    }
}

/** The run behind [ScanReviewCorpusTest], and the synthetic recording it is checked on. */
internal object ScanReviewCorpus {
    const val SUMMARY = "review-summary.txt"

    class Result(val name: String, val points: Int, val review: ScanReviewResult) {
        override fun toString() = "$name: ${review.zones.size} parts in ${review.millis} ms from $points points" +
            review.zones.joinToString("") { "\n  $it" }
    }

    fun run(corpus: File, out: File): List<Result> {
        val scans = corpus.walkTopDown()
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in RescanZonesCorpusTest.EXTENSIONS }
            .sortedBy { it.path }
            .toList()
        out.mkdirs()
        val summary = StringBuilder()
        val results = ArrayList<Result>()
        for (scan in scans) {
            val cloud = runCatching { RescanZonesCorpusTest.cloudOf(scan) }.getOrNull()
            if (cloud == null) {
                summary.appendLine("${scan.name}: no dense cloud to read")
                continue
            }
            val result = Result(scan.name, cloud.count, ScanReview.of(cloud))
            File(out, "${scan.name}-review.txt").writeText("$result\n")
            File(out, "${scan.name}-review.png").writeBytes(board(cloud, result.review))
            summary.appendLine(result.toString())
            results += result
        }
        File(out, SUMMARY).writeText(summary.toString())
        println(summary)
        return results
    }

    /** The scan from above: as scanned on the left, the review's parts framed on the right. */
    private fun board(cloud: DenseCloud, review: ScanReviewResult): ByteArray {
        val b = cloud.bounds()
        val zones = review.zones
        val minX = min(b[0], zones.minOfOrNull { it.box[0] } ?: b[0])
        val minZ = min(b[2], zones.minOfOrNull { it.box[2] } ?: b[2])
        val maxX = max(b[3], zones.maxOfOrNull { it.box[3] } ?: b[3])
        val maxZ = max(b[5], zones.maxOfOrNull { it.box[5] } ?: b[5])
        val scale = min(PANEL_PX / (maxX - minX).coerceAtLeast(MIN_SPAN_M), PANEL_PX / (maxZ - minZ).coerceAtLeast(MIN_SPAN_M))
        val panelW = ((maxX - minX) * scale).toInt() + 1
        val panelH = ((maxZ - minZ) * scale).toInt() + 1
        val canvas = PlanCanvas(2 * panelW + 3 * MARGIN, panelH + 2 * MARGIN + LABEL_PX)
        val p = cloud.positions
        val span = (b[4] - b[1]).coerceAtLeast(MIN_SPAN_M)
        // Low points first and light, high ones over them and dark, the ceiling left off: the floor
        // reads where it was seen, a wall as a dark line, and a hole stays white.
        val order = (0 until cloud.count).filter { p[it * 3 + 1] - b[1] < CEILING_SHARE * span }.sortedBy { p[it * 3 + 1] }
        for (k in 0..1) {
            val left = MARGIN + k * (panelW + MARGIN)
            fun px(x: Float) = left + (x - minX) * scale
            fun py(z: Float) = MARGIN + (z - minZ) * scale
            for (i in order) {
                val t = ((p[i * 3 + 1] - b[1]) / span).coerceIn(0f, 1f)
                val shade = (LIGHT - (LIGHT - DARK) * t).toInt()
                val x = px(p[i * 3])
                val y = py(p[i * 3 + 2])
                canvas.line(x, y, x, y, DOT, (shade shl 16) or (shade shl 8) or shade)
            }
            if (k == 1) {
                for (zone in zones) {
                    val colour = colourOf(zone.kind)
                    val x0 = px(zone.box[0])
                    val x1 = px(zone.box[3])
                    val y0 = py(zone.box[2])
                    val y1 = py(zone.box[5])
                    canvas.line(x0, y0, x1, y0, STROKE, colour)
                    canvas.line(x1, y0, x1, y1, STROKE, colour)
                    canvas.line(x1, y1, x0, y1, STROKE, colour)
                    canvas.line(x0, y1, x0, y0, STROKE, colour)
                }
                canvas.text(left, MARGIN + panelH + LABEL_GAP, "${zones.size}", TEXT_SCALE, INK)
            }
        }
        return canvas.png()
    }

    private fun colourOf(kind: RescanKind) = when (kind) {
        RescanKind.MissingWall -> AMBER
        RescanKind.FloorHole -> RED
        RescanKind.LowDensity -> BLUE
    }

    /** [cloud] saved as a `.svscan`, as a capture of it would be: a turn on the spot, a depth phone. */
    fun recording(cloud: DenseCloud): ByteArray {
        val events = RerunSyntheticRoom.poses(yawSteps = YAW_STEPS, stops = listOf(RerunSyntheticRoom.STOPS.first()))
            .mapIndexed { k, pose -> ArDebugEvent.CameraPose((k + 1) * FRAME_NANOS, pose) }
        val lens = ReplayLens.of(
            RerunSyntheticRoom.DEPTH_WIDTH.toFloat(), RerunSyntheticRoom.DEPTH_HEIGHT.toFloat(),
            RerunSyntheticRoom.FOCAL, RerunSyntheticRoom.FOCAL,
        )!!
        val device = ScanDevice("android", "Pixel 9", ScanDevice.TIER_DEPTH, ScanDevice.SOURCE_RAW_DEPTH)
        return RerunScanFile.write(runBlocking { RerunCaptureBuilder.build(events, lens, emptyList(), device, cloud) })
    }

    private const val YAW_STEPS = 12
    private const val FRAME_NANOS = 33_333_333L
    private const val PANEL_PX = 470f
    private const val MIN_SPAN_M = 0.5f
    private const val MARGIN = 16
    private const val LABEL_PX = 28
    private const val LABEL_GAP = 6
    private const val TEXT_SCALE = 3
    private const val DOT = 2f
    private const val STROKE = 3f
    private const val CEILING_SHARE = 0.9f
    private const val LIGHT = 215f
    private const val DARK = 40f
    private const val INK = 0x1F1F1F

    /** The `warning` amber the result frames parts with. */
    private const val AMBER = 0xF59E0B
    private const val RED = 0xDC2626
    private const val BLUE = 0x2563EB
}
