package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DenseSamples
import io.github.sceneview.demo.demos.internal.FakeRerunImageCodec
import io.github.sceneview.demo.demos.internal.OpenedCapture
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunRrdReader
import io.github.sceneview.demo.demos.internal.RerunScanFile
import io.github.sceneview.demo.demos.internal.ScanDevice
import io.github.sceneview.demo.demos.internal.ScanPreset
import io.github.sceneview.demo.demos.internal.ScanWalkthrough
import io.github.sceneview.demo.demos.internal.SvpcCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 3D Scan's live view over real scans (#4743), run by hand on a desktop: the view as it drew
 * before (the map's stride, [ScanDenseMap.LIVE_MAX_SURFELS] surfels) and as it draws now
 * ([DenseFusion.liveCloud], [ScanDenseMap.LIVE_DRAWN_SURFELS] surfels), each looked at from the
 * scan's own camera path once a second ([LiveDrawLoad]).
 *
 * `SCENEVIEW_SCAN_CORPUS` names a `.svscan` or `.rrd` file, or a directory holding them;
 * `SCENEVIEW_SCAN_OUT` the directory the report ([REPORT]) goes to, with three PNGs a scan
 * (a quarter of the screen, before over after, from a quarter, half and three quarters of the
 * way along the path). Without both the run is skipped, as on CI:
 *
 * ```
 * SCENEVIEW_SCAN_CORPUS=/path/to/scans SCENEVIEW_SCAN_OUT=/tmp/scan-live \
 *   ./gradlew :samples:android-demo:testDebugUnitTest --tests '*ScanLiveRealScanTest*'
 * ```
 *
 * A scan file holds the map's confirmed surfels at the end of the scan, not the map each live
 * snapshot saw: they are fused again, once each, into a map of the saved voxel, and both views
 * are taken from it — like for like, the end of the scan seen from every point of its path. A
 * surfel the phone's map had coarsened comes back at the finest level, so both views draw it
 * smaller than the phone did. An `.rrd` holds no normals: its surfels lie flat in both views, as
 * the replay draws such a cloud.
 */
class ScanLiveRealScanTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the live view over the scans of SCENEVIEW_SCAN_CORPUS`() {
        val corpus = System.getenv(CORPUS)?.takeIf { it.isNotBlank() }?.let(::File)
        val out = System.getenv(OUT)?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("$CORPUS names no scan file or directory", corpus != null && corpus.exists())
        assumeTrue("$OUT names no output directory", out != null)
        val scans = scanFiles(corpus!!)
        assertTrue("no .svscan or .rrd under $corpus", scans.isNotEmpty())
        val results = run(scans, out!!)
        assertTrue("no scan of $corpus held a dense cloud", results.isNotEmpty())
    }

    @Test
    fun `a synthetic recording runs through to a report and images`() {
        val scan = folder.newFile("walkthrough.svscan").apply { writeBytes(syntheticScan()) }
        val out = folder.newFolder("out")
        val result = run(listOf(scan), out).single()
        assertTrue("${result.eyes} eyes along the walk", result.eyes >= MIN_EYES)
        assertTrue(result.before.drawn.max() <= ScanDenseMap.LIVE_MAX_SURFELS)
        assertTrue(result.after.drawn.max() <= ScanDenseMap.LIVE_DRAWN_SURFELS)
        assertTrue(File(out, REPORT).readText().contains("walkthrough.svscan"))
        val images = out.listFiles { f -> f.extension == "png" }.orEmpty()
        assertEquals(SHOTS.size, images.size)
        val w = LiveDrawLoad.VIEW_LONG_PX / SHRINK
        val h = 2 * (LiveDrawLoad.VIEW_SHORT_PX / SHRINK) + GAP_PX
        for (image in images) {
            val header = DataInputStream(image.inputStream()).use { input -> ByteArray(PNG_HEADER).also(input::readFully) }
            assertArrayEquals(image.name, PNG_SIGNATURE, header.copyOf(PNG_SIGNATURE.size))
            val size = ByteBuffer.wrap(header, IHDR_SIZE_AT, 8)
            assertEquals(w, size.int)
            assertEquals(h, size.int)
        }
    }

    /** One view's figures, an entry per eye. */
    private class Figures {
        val drawn = ArrayList<Double>()
        val coverage = ArrayList<Double>()
        val overdraw = ArrayList<Double>()
        val buildMs = ArrayList<Double>()

        fun add(load: LiveDrawLoad, ms: Double) {
            drawn += load.surfels.toDouble()
            coverage += load.coverage.toDouble()
            overdraw += load.overdraw.toDouble()
            buildMs += ms
        }
    }

    private class Result(val name: String, val saved: Int, val eyes: Int, val before: Figures, val after: Figures)

    internal companion object {
        const val CORPUS = "SCENEVIEW_SCAN_CORPUS"
        const val OUT = "SCENEVIEW_SCAN_OUT"
        const val REPORT = "scan-live-real.md"

        /** One eye a second of the scan's path: the live snapshot's own cadence. */
        const val EYE_SPACING_S = 1f
        const val MIN_EYES = 30

        /** Where along the path the images look from. */
        val SHOTS = listOf(25, 50, 75)

        /** The images are a quarter of the phone screen [LiveDrawLoad] stands for, before over after. */
        const val SHRINK = 4
        const val GAP_PX = 4
        const val BACKGROUND = 0x202020
        const val GAP = 0x808080
        const val UNCOLOURED = 0xB0B0B0
        const val RGB = 0xFFFFFF

        /** An 8-bit RGB PNG: its signature, then its header chunk, width and height first. */
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        const val IHDR_SIZE_AT = 16
        const val PNG_HEADER = 24
        const val BIT_DEPTH = 8
        const val COLOR_TYPE_RGB = 2

        const val P50 = 0.5
        const val P90 = 0.9
        const val NANOS_PER_MS = 1e6
        const val FULL_CONFIDENCE: Byte = -1

        fun scanFiles(corpus: File): List<File> = corpus.walk()
            .filter { it.isFile && (it.extension == RerunScanFile.EXTENSION || it.extension == "rrd") }
            .sortedBy { it.path }
            .toList()

        /** Every scan of [scans] through both live views; the report and images go to [out]. */
        private fun run(scans: List<File>, out: File): List<Result> {
            out.mkdirs()
            val report = StringBuilder("# 3D Scan live view over real scans\n\n")
            report.append("Before: the map's stride, ${ScanDenseMap.LIVE_MAX_SURFELS} surfels at most. ")
            report.append("After: near surfels whole, far ones merged by distance, ")
            report.append("${ScanDenseMap.LIVE_DRAWN_SURFELS} at most. ")
            report.append("Host times, one eye a second of each scan's path.\n")
            val results = scans.mapNotNull { file ->
                val opened = open(file)
                val dense = opened?.let(::denseOf)
                if (opened == null || dense == null) {
                    report.append("\n## ${file.name}\n\nNo dense cloud to draw: skipped.\n")
                    return@mapNotNull null
                }
                measure(file, opened, dense.first, dense.second, out).also { report.append(it.second) }.first
            }
            File(out, REPORT).writeText(report.toString())
            return results
        }

        fun open(file: File): OpenedCapture? {
            val bytes = file.readBytes()
            val pack = if (file.extension == RerunScanFile.EXTENSION) {
                RerunScanFile.read(bytes)
            } else {
                // The photos do not matter here: the test codec passes them through.
                runCatching { RerunRrdReader.capturePack(bytes, file.name, FakeRerunImageCodec) }.getOrNull()
            }
            return pack?.open()
        }

        /** The scan's dense cloud and its voxel, as the replay reads them. */
        fun denseOf(opened: OpenedCapture): Pair<DenseCloud, Float>? {
            val dense = opened.manifest.dense ?: return null
            val span = opened.manifest.media[dense.path] ?: return null
            val cloud = SvpcCodec.decode(opened.media, span.offset, span.length)?.takeIf { it.count > 0 } ?: return null
            return cloud to dense.voxelM
        }

        /** [saved] fused again, each surfel once, into a map that never compacts. */
        fun refuse(saved: DenseCloud, voxelM: Float): DenseFusion {
            val n = saved.count
            val fusion = DenseFusion(voxelM = voxelM, maxPoints = n * 2 + 1)
            val normals = saved.normals ?: FloatArray(n * 3) { if (it % 3 == 1) 1f else 0f }
            val confidences = saved.confidences ?: ByteArray(n) { FULL_CONFIDENCE }
            fusion.add(DenseSamples(n, saved.positions, normals, saved.colors, confidences))
            return fusion
        }

        @Suppress("LongMethod")
        private fun measure(file: File, opened: OpenedCapture, saved: DenseCloud, voxelM: Float, out: File): Pair<Result, String> {
            val fusion = refuse(saved, voxelM)
            val trace = opened.trace
            val eyes = ArrayList<DebugPose>()
            var next = 0f
            for (i in 0 until trace.poseCount) {
                if (trace.poseTime(i) < next) continue
                eyes += trace.pose(i)
                next = trace.poseTime(i) + EYE_SPACING_S
            }
            val before = Figures()
            val after = Figures()
            val shots = SHOTS.map { pct -> ((eyes.size - 1) * pct / 100).coerceAtLeast(0) }
            for ((e, eye) in eyes.withIndex()) {
                var start = System.nanoTime()
                val stride = fusion.cloud(minViews = 1, maxPoints = ScanDenseMap.LIVE_MAX_SURFELS)
                before.add(LiveDrawLoad.of(stride, voxelM, eye), (System.nanoTime() - start) / NANOS_PER_MS)
                start = System.nanoTime()
                val live = fusion.liveCloud(eye.x, eye.y, eye.z, ScanDenseMap.LIVE_DRAWN_SURFELS, minViews = 1)
                after.add(LiveDrawLoad.of(live, voxelM, eye), (System.nanoTime() - start) / NANOS_PER_MS)
                for ((s, at) in shots.withIndex()) {
                    if (at != e) continue
                    val name = "${file.nameWithoutExtension}-live-${SHOTS[s]}.png"
                    File(out, name).writeBytes(png(draw(stride, voxelM, eye), draw(live, voxelM, eye)))
                }
            }
            val coarse = (0 until saved.count).count { saved.levelOf(it) > 0 }
            val section = buildString {
                append("\n## ${file.name}\n\n")
                append("${saved.count} surfels saved at ${"%.1f".format(voxelM * 100)} cm")
                if (coarse > 0) append(" ($coarse coarsened, drawn at the finest level here)")
                if (saved.normals == null) append(", no normals (flat in both views)")
                append("; ${eyes.size} eyes over ${"%.1f".format(trace.duration)} s.\n\n")
                append("| Per eye | Before p50 | Before p90 | After p50 | After p90 |\n")
                append("|---|---:|---:|---:|---:|\n")
                row("Surfels drawn", before.drawn, after.drawn, "%.0f")
                row("Vertices submitted", before.drawn.map { it * 4 }, after.drawn.map { it * 4 }, "%.0f")
                row("Screen covered", before.coverage, after.coverage, "%.3f")
                row("Fragments a pixel", before.overdraw, after.overdraw, "%.2f")
                row("Cloud built, ms", before.buildMs, after.buildMs, "%.1f")
                if (eyes.isNotEmpty()) {
                    append("\nImages, before (top) over after: ")
                    append(SHOTS.joinToString { "`${file.nameWithoutExtension}-live-$it.png`" })
                    append(".\n")
                }
            }
            return Result(file.name, saved.count, eyes.size, before, after) to section
        }

        fun StringBuilder.row(label: String, before: List<Double>, after: List<Double>, format: String) {
            if (before.isEmpty()) return
            append("| $label | ${format.format(before.pct(P50))} | ${format.format(before.pct(P90))} | ")
            append("${format.format(after.pct(P50))} | ${format.format(after.pct(P90))} |\n")
        }

        /**
         * [cloud] from [eye] at a quarter of the screen, far surfels first and near ones over them:
         * `0xRRGGBB` pixels, row by row, [LiveDrawLoad.VIEW_LONG_PX] / [SHRINK] wide.
         */
        fun draw(cloud: DenseCloud, voxelM: Float, eye: DebugPose): IntArray {
            val w = LiveDrawLoad.VIEW_LONG_PX / SHRINK
            val h = LiveDrawLoad.VIEW_SHORT_PX / SHRINK
            val pixels = IntArray(w * h) { BACKGROUND }
            val index = ArrayList<Int>()
            val us = ArrayList<Float>()
            val vs = ArrayList<Float>()
            val depths = ArrayList<Float>()
            val areas = ArrayList<Float>()
            LiveDrawLoad.forEachSplat(cloud, voxelM, eye) { i, u, v, depth, area ->
                index += i; us += u; vs += v; depths += depth; areas += area
            }
            for (k in index.indices.sortedByDescending { depths[it] }) {
                val half = sqrt(areas[k]) / SHRINK / 2f
                val cx = (us[k] + LiveDrawLoad.HALF_LONG) / SHRINK
                val cy = (LiveDrawLoad.HALF_SHORT - vs[k]) / SHRINK
                val x0 = (cx - half).roundToInt().coerceAtLeast(0)
                val x1 = maxOf((cx + half).roundToInt(), x0 + 1).coerceAtMost(w)
                val y0 = (cy - half).roundToInt().coerceAtLeast(0)
                val y1 = maxOf((cy + half).roundToInt(), y0 + 1).coerceAtMost(h)
                val c = (cloud.colors[index[k]] and RGB).takeIf { cloud.colors[index[k]] != 0 } ?: UNCOLOURED
                for (y in y0 until y1) for (x in x0 until x1) pixels[y * w + x] = c
            }
            return pixels
        }

        /** Two of [draw]'s images, one over the other with a grey band between: an 8-bit RGB PNG. */
        fun png(top: IntArray, bottom: IntArray): ByteArray {
            val w = LiveDrawLoad.VIEW_LONG_PX / SHRINK
            val pixels = top + IntArray(w * GAP_PX) { GAP } + bottom
            val h = pixels.size / w
            val rows = ByteArrayOutputStream()
            DeflaterOutputStream(rows).use { deflate ->
                for (y in 0 until h) {
                    deflate.write(0) // no filter
                    for (x in 0 until w) {
                        val c = pixels[y * w + x]
                        deflate.write(c shr 16 and 0xFF)
                        deflate.write(c shr 8 and 0xFF)
                        deflate.write(c and 0xFF)
                    }
                }
            }
            val header = ByteBuffer.allocate(13).putInt(w).putInt(h)
                .put(BIT_DEPTH.toByte()).put(COLOR_TYPE_RGB.toByte()).put(0).put(0).put(0).array()
            val file = ByteArrayOutputStream()
            file.write(PNG_SIGNATURE)
            DataOutputStream(file).apply {
                chunk("IHDR", header)
                chunk("IDAT", rows.toByteArray())
                chunk("IEND", ByteArray(0))
            }
            return file.toByteArray()
        }

        fun DataOutputStream.chunk(type: String, data: ByteArray) {
            val name = type.toByteArray()
            writeInt(data.size)
            write(name)
            write(data)
            writeInt(CRC32().apply { update(name); update(data) }.value.toInt())
        }

        /** The walkthrough's two rooms as the recorder saves them: camera path and dense cloud. */
        fun syntheticScan(): ByteArray {
            val walk = ScanWalkthrough(yawSteps = WALK_YAW_STEPS, revisit = false)
            val dense = ScanDenseMap(maxPoints = DenseFusion.budgetFor(256L shl 20), clock = { 0L })
            val events = ArrayList<ArDebugEvent>()
            var now = 0L
            for (i in 0 until minOf(WALK_FRAMES, walk.frameCount)) {
                now += ScanCapture.DEPTH_INTERVAL_NS
                val frame = walk.frame(i)
                events += ArDebugEvent.CameraPose(now, frame.pose)
                dense.add(dense.project(frame, now, farM = ScanPreset.ROOM.farM))
            }
            val device = ScanDevice("android", "synthetic", ScanDevice.TIER_DEPTH, ScanDevice.SOURCE_RAW_DEPTH)
            val pack: RerunCapturePack = runBlocking {
                RerunCaptureBuilder.build(
                    events, ReplayLens.Default, emptyList(), device, dense.savedCloud(), dense.fusion.voxelM,
                )
            }
            return RerunScanFile.write(pack)
        }

        const val WALK_YAW_STEPS = 27
        const val WALK_FRAMES = 486

        /** Nearest rank. */
        fun List<Double>.pct(p: Double): Double {
            val sorted = sorted()
            return sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
        }
    }
}
