package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.RerunCaptureBuilder
import io.github.sceneview.demo.demos.internal.room.PlanCanvas
import io.github.sceneview.demo.demos.internal.semantic.SceneFusion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Taps on the replay of real scans (#4485): every `.svscan` and `.rrd` under
 * `SCENEVIEW_SCAN_CORPUS` is opened as the replay opens it, tapped from head height in the
 * middle of the scan all round, and every box found is written to `SCENEVIEW_SCAN_OUT` — its
 * sizes, whether the scan saw it from one side only, the time each tap took, and a picture from
 * above. Skipped when the two are not set; the corpus never enters the repository.
 */
class ReplayTapCorpusTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `taps on every scan in the corpus`() {
        val corpus = System.getenv(RescanZonesCorpusTest.CORPUS)?.takeIf { it.isNotBlank() }?.let(::File)
        val out = System.getenv(OUT)?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("${RescanZonesCorpusTest.CORPUS} and $OUT not both set", corpus != null && out != null)
        val scans = corpus!!.walkTopDown()
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in RescanZonesCorpusTest.EXTENSIONS }
            .sortedBy { it.path }
            .toList()
        assumeTrue("no .svscan or .rrd under $corpus", scans.isNotEmpty())
        out!!.mkdirs()
        val summary = StringBuilder()
        for (scan in scans) {
            val cloud = runCatching { RescanZonesCorpusTest.cloudOf(scan) }.getOrNull()
            if (cloud == null) {
                summary.appendLine("${scan.name}: no dense cloud to read")
                continue
            }
            summary.append(sweep(scan.name, cloud, out).report)
        }
        File(out, "taps-summary.txt").writeText(summary.toString())
        println(summary)
    }

    @Test
    fun `a synthetic recording goes through the corpus run end to end`() {
        val file = folder.newFile("synthetic-room.svscan").apply { writeBytes(recording()) }
        val cloud = RescanZonesCorpusTest.cloudOf(file)
        assertTrue("the recording carries its dense cloud", cloud != null)
        val out = folder.newFolder("out")

        val sweep = sweep(file.name, cloud!!, out)

        assertTrue("the sofa is among the boxes: ${sweep.report}", sweep.answers.any { abs(it.box.length - SOFA_LENGTH) < FUSED_M && abs(it.box.height - SOFA_HEIGHT) < FUSED_M })
        assertTrue("the cabinet is among them", sweep.answers.any { abs(it.box.height - CABINET_HEIGHT) < FUSED_M })
        assertEquals(sweep.answers.size + 2, File(out, "${file.name}-taps.txt").readLines().size)
        val png = File(out, "${file.name}-taps.png").readBytes()
        assertTrue("a PNG", png.size > PNG_HEADER && png[1] == 'P'.code.toByte())
        println(sweep.report)
    }

    /** What a sweep found: each box once, and its text. */
    class Sweep(val answers: List<ReplayTapAnswer>, val report: String)

    companion object {
        private const val OUT = "SCENEVIEW_SCAN_OUT"
        private const val NS_PER_MS = 1_000_000L
        private const val NS_PER_US = 1_000L

        /** Head height above the floor the taps come from. */
        private const val EYE_HEIGHT_M = 1.4f
        private const val AZIMUTH_STEP = 10
        private val ELEVATIONS = floatArrayOf(-60f, -45f, -32f, -20f, -10f)

        /** Two taps on one object give the same box, near enough. */
        private const val SAME_BOX = 0.5f
        private const val PX_PER_M = 120f
        private const val MAX_PX = 1600f
        private const val MARGIN = 24
        private const val TEXT_SCALE = 2
        private const val LIGHT = 0xE8
        private const val DARK = 0x60
        private const val CEILING_SHARE = 0.8f
        private const val MIN_SPAN_M = 0.5f
        private const val BOX_STROKE_PX = 3f
        private const val ALL_ROUND = 0x1E8E3E
        private const val ONE_SIDE = 0xF29900
        private const val INK = 0x202124

        private const val SOFA_LENGTH = 1.4f
        private const val SOFA_HEIGHT = 0.9f
        private const val CABINET_HEIGHT = 1.6f
        private const val FUSED_M = 0.08f
        private const val PNG_HEADER = 8
        private const val FRAME_NANOS = 33_000_000L

        /**
         * Taps [cloud] all round from head height at its middle, as a person standing there would,
         * and writes `<name>-taps.txt` and `<name>-taps.png` under [out].
         */
        fun sweep(name: String, cloud: DenseCloud, out: File): Sweep {
            val started = System.nanoTime()
            val objects = ScanObjects.of(cloud)
            val indexed = (System.nanoTime() - started) / NS_PER_MS
            val b = cloud.bounds()
            val eye = Vec3((b[0] + b[3]) / 2, min(b[1] + EYE_HEIGHT_M, b[4] - 0.1f), (b[2] + b[5]) / 2)
            val answers = mutableListOf<ReplayTapAnswer>()
            val lines = mutableListOf<String>()
            var slowestPick = 0L
            var slowestAnswer = 0L
            var taps = 0
            var closer = 0
            for (az in 0 until 360 step AZIMUTH_STEP) for (el in ELEVATIONS) {
                val a = Math.toRadians(az.toDouble())
                val e = Math.toRadians(el.toDouble())
                val ray = Vec3((cos(e) * cos(a)).toFloat(), sin(e).toFloat(), (cos(e) * sin(a)).toFloat())
                val t0 = System.nanoTime()
                val pick = objects.pick(eye, ray)
                val t1 = System.nanoTime()
                val answer = ReplayTap.answer(cloud, pick, emptyList())
                val t2 = System.nanoTime()
                taps++
                slowestPick = max(slowestPick, t1 - t0)
                slowestAnswer = max(slowestAnswer, t2 - t1)
                if (pick == ScanPick.TapCloser) closer++
                if (answer == null || answers.any { SceneFusion.footprintIou(it.box, answer.box) >= SAME_BOX }) continue
                answers += answer
                val (l, d, h) = ReplayTap.sizes(answer.box, Locale.ROOT)
                lines += "  box at (%.2f, %.2f) yaw %.0f°: %s × %s × %s%s, pick %d ms, answer %d µs".format(
                    Locale.ROOT, answer.box.centerX, answer.box.centerZ, Math.toDegrees(answer.box.yaw.toDouble()),
                    l, d, h, if (answer.oneSided) ", seen from one side" else "",
                    (t1 - t0) / NS_PER_MS, (t2 - t1) / NS_PER_US,
                )
            }
            val head = "$name: ${cloud.count} points, normals ${cloud.normals != null}, indexed in $indexed ms, " +
                "$taps taps, ${answers.size} boxes, $closer tap closer, slowest pick ${slowestPick / NS_PER_MS} ms, " +
                "slowest answer ${slowestAnswer / NS_PER_US} µs"
            val legend = "  picture: from above, ceiling off, x right, z down; green seen all round, amber seen from one side"
            val report = (listOf(head, legend) + lines).joinToString("\n", postfix = "\n")
            out.mkdirs()
            File(out, "$name-taps.txt").writeText(report)
            File(out, "$name-taps.png").writeBytes(picture(cloud, answers))
            return Sweep(answers, report)
        }

        private fun picture(cloud: DenseCloud, answers: List<ReplayTapAnswer>): ByteArray {
            val b = cloud.bounds()
            val scale = min(PX_PER_M, MAX_PX / max(b[3] - b[0], b[5] - b[2]).coerceAtLeast(1f))
            val canvas = PlanCanvas(
                ceil((b[3] - b[0]) * scale).toInt() + 2 * MARGIN,
                ceil((b[5] - b[2]) * scale).toInt() + 2 * MARGIN,
            )
            fun px(x: Float) = MARGIN + (x - b[0]) * scale
            fun py(z: Float) = MARGIN + (z - b[2]) * scale
            val p = cloud.positions
            val span = (b[4] - b[1]).coerceAtLeast(MIN_SPAN_M)
            val order = (0 until cloud.count).filter { p[it * 3 + 1] - b[1] < CEILING_SHARE * span }.sortedBy { p[it * 3 + 1] }
            for (i in order) {
                val t = ((p[i * 3 + 1] - b[1]) / span).coerceIn(0f, 1f)
                val shade = (LIGHT - (LIGHT - DARK) * t).toInt()
                val x = px(p[i * 3])
                val y = py(p[i * 3 + 2])
                canvas.line(x, y, x, y, 1f, (shade shl 16) or (shade shl 8) or shade)
            }
            for (answer in answers) {
                val colour = if (answer.oneSided) ONE_SIDE else ALL_ROUND
                val corners = answer.box.corners().take(4)
                for (k in corners.indices) {
                    val from = corners[k]
                    val to = corners[(k + 1) % corners.size]
                    canvas.line(px(from.x), py(from.z), px(to.x), py(to.z), BOX_STROKE_PX, colour)
                }
                val text = answer.box.sizes().toList().joinToString("*")
                val x = px(answer.box.centerX).toInt() - canvas.textWidth(text, TEXT_SCALE) / 2
                canvas.text(x, py(answer.box.centerZ).toInt(), text, TEXT_SCALE, INK)
            }
            return canvas.png()
        }

        /** The demo's synthetic room as a `.svscan` the app would have written: its poses and its fused cloud. */
        private fun recording(): ByteArray {
            val frames = RerunSyntheticRoom.frames().toList()
            val fusion = DenseFusion()
            frames.forEach { fusion.add(DepthBackProjection.project(it)) }
            val events = frames.mapIndexed { k, frame -> ArDebugEvent.CameraPose((k + 1) * FRAME_NANOS, frame.pose) }
            val lens = ReplayLens.of(
                RerunSyntheticRoom.DEPTH_WIDTH.toFloat(), RerunSyntheticRoom.DEPTH_HEIGHT.toFloat(),
                RerunSyntheticRoom.FOCAL, RerunSyntheticRoom.FOCAL,
            )!!
            val device = ScanDevice("android", "Pixel 9", ScanDevice.TIER_DEPTH, ScanDevice.SOURCE_RAW_DEPTH)
            return RerunScanFile.write(runBlocking { RerunCaptureBuilder.build(events, lens, emptyList(), device, fusion.cloud()) })
        }
    }
}
