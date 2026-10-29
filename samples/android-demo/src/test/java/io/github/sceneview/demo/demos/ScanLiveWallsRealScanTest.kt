package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.DebugMesh
import io.github.sceneview.demo.demos.internal.DebugPlaneKind
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.LiveWall
import io.github.sceneview.demo.demos.internal.LiveWallSnapshot
import io.github.sceneview.demo.demos.internal.LiveWallsCorpus
import io.github.sceneview.demo.demos.internal.OpenedCapture
import io.github.sceneview.demo.demos.internal.PlaneWallSource
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunScanFile
import io.github.sceneview.demo.demos.internal.RerunSyntheticRoom
import io.github.sceneview.demo.demos.internal.ScanDevice
import io.github.sceneview.demo.demos.internal.ScanPreset
import io.github.sceneview.demo.demos.internal.ScanWalkthrough
import io.github.sceneview.demo.demos.internal.WallExtent
import io.github.sceneview.demo.demos.internal.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 3D Scan's live walls over real scans (#4540), run by hand on a desktop: each scan's walls, as
 * its planes give them at its end ([PlaneWallSource]), cut back ([WallExtent.clip]) to the map
 * seen from the scan's own camera path once a second — to the points the live view draws
 * ([DenseFusion.liveCloud]), as the walls were cut before, and to what the walls read of the map
 * now ([ScanWallsState.depthOf]) — each wall's ends set against the same wall cut to every point
 * of the saved cloud. Depth points only: the feature points the live view adds are the same for
 * both.
 *
 * `SCENEVIEW_SCAN_CORPUS` names a `.svscan` or `.rrd` file, or a directory holding them;
 * `SCENEVIEW_SCAN_OUT` the directory the report ([REPORT]) goes to. Without both the run is
 * skipped, as on CI:
 *
 * ```
 * SCENEVIEW_SCAN_CORPUS=/path/to/scans SCENEVIEW_SCAN_OUT=/tmp/scan-live-walls \
 *   ./gradlew :samples:android-demo:testDebugUnitTest --tests '*ScanLiveWallsRealScanTest*'
 * ```
 *
 * A scan file holds the map's confirmed surfels at its end, not the map each live snapshot saw:
 * they are fused again, once each ([ScanLiveRealScanTest.refuse]), and both clouds are taken from
 * that map — like for like, only which of its points the cut reads changes. The walls are the
 * ones found at the end, tracking assumed throughout.
 */
class ScanLiveWallsRealScanTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the live walls over the scans of SCENEVIEW_SCAN_CORPUS`() {
        val corpus = System.getenv(CORPUS)?.takeIf { it.isNotBlank() }?.let(::File)
        val out = System.getenv(OUT)?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("$CORPUS names no scan file or directory", corpus != null && corpus.exists())
        assumeTrue("$OUT names no output directory", out != null)
        val scans = ScanLiveRealScanTest.scanFiles(corpus!!)
        assertTrue("no .svscan or .rrd under $corpus", scans.isNotEmpty())
        val results = run(scans, out!!)
        assertTrue("no scan of $corpus held walls and a dense cloud", results.isNotEmpty())
    }

    @Test
    fun `a synthetic recording runs through to a report`() {
        val scan = folder.newFile("walkthrough.svscan").apply { writeBytes(syntheticScan()) }
        val out = folder.newFolder("out")
        val result = run(listOf(scan), out).single()
        assertEquals(SYNTHETIC_ROOMS * WALLS_PER_ROOM, result.walls)
        assertTrue("${result.eyes} eyes along the walk", result.eyes >= MIN_EYES)
        assertTrue(
            "read ${result.read.error.max()} m off the map's cut at worst, drawn ${result.drawn.error.max()} m",
            result.read.error.max() <= result.drawn.error.max(),
        )
        val report = File(out, REPORT).readText()
        assertTrue(report.contains("walkthrough.svscan"))
        println(report)
    }

    /** One cloud's figures, an entry per wall per eye ([error]) or per eye ([points], [ms]). */
    private class Figures {
        val error = ArrayList<Double>()
        val points = ArrayList<Double>()
        val ms = ArrayList<Double>()
    }

    private class Result(val name: String, val walls: Int, val eyes: Int, val drawn: Figures, val read: Figures)

    private companion object {
        const val CORPUS = "SCENEVIEW_SCAN_CORPUS"
        const val OUT = "SCENEVIEW_SCAN_OUT"
        const val REPORT = "scan-live-walls.md"

        /** One eye a second of the scan's path: the live snapshot's own cadence. */
        const val EYE_SPACING_S = 1f
        const val MIN_EYES = 30

        const val P50 = 0.5
        const val P90 = 0.9
        const val NANOS_PER_MS = 1e6

        /** Every scan of [scans] through both clouds; the report goes to [out]. */
        fun run(scans: List<File>, out: File): List<Result> {
            out.mkdirs()
            val report = StringBuilder("# 3D Scan live walls over real scans\n\n")
            report.append("Each wall's ends against the same wall cut to every point of the saved cloud, ")
            report.append("in metres along the wall, an entry a wall an eye. Drawn: the cloud the live view ")
            report.append("draws, ${ScanDenseMap.LIVE_DRAWN_SURFELS} surfels at most, near ones whole and far ")
            report.append("ones merged (before). Read: the map's even stride, ${WallExtent.MAX_READ} points at ")
            report.append("most (after). Host times, one eye a second of each scan's path.\n")
            val results = scans.mapNotNull { file ->
                val opened = ScanLiveRealScanTest.open(file)
                val dense = opened?.let(ScanLiveRealScanTest::denseOf)
                val walls = opened?.let(::wallsOf)?.takeIf { it.walls.isNotEmpty() }
                if (opened == null || dense == null || walls == null) {
                    report.append("\n## ${file.name}\n\nNo walls or no dense cloud: skipped.\n")
                    return@mapNotNull null
                }
                measure(file, opened.trace, dense.first, dense.second, walls).also { report.append(it.second) }.first
            }
            File(out, REPORT).writeText(report.toString())
            return results
        }

        /** The walls [opened]'s planes give at its end, replayed every [LiveWallsCorpus.STEP_S] as the live view finds them. */
        fun wallsOf(opened: OpenedCapture): LiveWallSnapshot {
            val trace = opened.trace
            val source = PlaneWallSource()
            var t = 0f
            while (true) {
                val time = min(t, trace.duration)
                val snapshot = source.update(trace.frameAt(time))
                if (time >= trace.duration) return snapshot
                t += LiveWallsCorpus.STEP_S
            }
        }

        fun measure(
            file: File,
            trace: ArDebugTrace,
            saved: DenseCloud,
            voxelM: Float,
            found: LiveWallSnapshot,
        ): Pair<Result, String> {
            val fusion = ScanLiveRealScanTest.refuse(saved, voxelM)
            val walls = found.walls
            val map = WallExtent.clip(walls, pieces(saved.positions), found.floorY, found.height)
            val drawn = Figures()
            val read = Figures()
            val thinMs = ArrayList<Double>()
            var eyes = 0
            var next = 0f
            for (i in 0 until trace.poseCount) {
                if (trace.poseTime(i) < next) continue
                next = trace.poseTime(i) + EYE_SPACING_S
                eyes++
                val eye = trace.pose(i)
                val live = fusion.liveCloud(eye.x, eye.y, eye.z, ScanDenseMap.LIVE_DRAWN_SURFELS, minViews = 1)
                val whole = fusion.cloudPositions(minViews = 1, maxPoints = ScanDenseMap.LIVE_MAX_SURFELS)
                val layer = ReplayDenseLayer(live, voxelM, DebugMesh(), ByteArray(0), whole = whole)
                val start = System.nanoTime()
                val depth = ScanWallsState.depthOf(layer)
                thinMs += (System.nanoTime() - start) / NANOS_PER_MS
                cut(found, map, live.positions, drawn)
                cut(found, map, depth, read)
            }
            val section = buildString {
                append("\n## ${file.name}\n\n")
                append("${walls.size} walls found; ${saved.count} surfels saved at ${"%.1f".format(voxelM * 100)} cm; ")
                append("$eyes eyes over ${"%.1f".format(trace.duration)} s.\n\n")
                append("| | Drawn p50 | Drawn p90 | Drawn max | Read p50 | Read p90 | Read max |\n")
                append("|---|---:|---:|---:|---:|---:|---:|\n")
                row("End off the map's cut, m", drawn.error, read.error, "%.3f")
                row("Points the cut read", drawn.points, read.points, "%.0f")
                row("Cut, ms", drawn.ms, read.ms, "%.2f")
                if (thinMs.isNotEmpty()) {
                    append("\nThe map thinned for the read, once a snapshot: p50 ${"%.2f".format(thinMs.pct(P50))} ms, ")
                    append("p90 ${"%.2f".format(thinMs.pct(P90))} ms.\n")
                }
            }
            return Result(file.name, walls.size, eyes, drawn, read) to section
        }

        /** [found]'s walls cut back to [cloud], each end set against the same wall's [map] cut, into [into]. */
        fun cut(found: LiveWallSnapshot, map: List<LiveWall>, cloud: FloatArray, into: Figures) {
            val start = System.nanoTime()
            val cut = WallExtent.clip(found.walls, listOf(cloud), found.floorY, found.height)
            into.ms += (System.nanoTime() - start) / NANOS_PER_MS
            into.points += cloud.size / 3.0
            cut.forEachIndexed { k, wall ->
                val f = found.walls[k]
                val error = max(
                    abs(along(f, wall.ax, wall.az) - along(f, map[k].ax, map[k].az)),
                    abs(along(f, wall.bx, wall.bz) - along(f, map[k].bx, map[k].bz)),
                )
                into.error += error.toDouble()
            }
        }

        /** [cloud] in pieces the cut reads whole ([WallExtent.MAX_READ]): every point counts. */
        fun pieces(cloud: FloatArray): List<FloatArray> = (0 until cloud.size step WallExtent.MAX_READ * 3).map {
            cloud.copyOfRange(it, minOf(cloud.size, it + WallExtent.MAX_READ * 3))
        }

        /** How far along [wall], from its start, [x], [z] lies. */
        fun along(wall: LiveWall, x: Float, z: Float): Float =
            ((x - wall.ax) * (wall.bx - wall.ax) + (z - wall.az) * (wall.bz - wall.az)) / wall.length

        fun StringBuilder.row(label: String, before: List<Double>, after: List<Double>, format: String) {
            if (before.isEmpty()) return
            append("| $label | ${format.format(before.pct(P50))} | ${format.format(before.pct(P90))} | ")
            append("${format.format(before.max())} | ${format.format(after.pct(P50))} | ")
            append("${format.format(after.pct(P90))} | ${format.format(after.max())} |\n")
        }

        /**
         * The walkthrough's first [SYNTHETIC_ROOMS] rooms as the recorder saves them: camera path,
         * dense cloud, and each room's floor and four walls as ARCore grows a plane, [OVER_M] past
         * the room at each end.
         */
        fun syntheticScan(): ByteArray {
            val walk = ScanWalkthrough(yawSteps = WALK_YAW_STEPS, revisit = false)
            val dense = ScanDenseMap(maxPoints = DenseFusion.budgetFor(PHONE_BYTES), clock = { 0L })
            val events = ArrayList<ArDebugEvent>()
            var now = 0L
            for (i in 0 until walk.frameCount / ScanWalkthrough.ROOMS * SYNTHETIC_ROOMS) {
                now += ScanCapture.DEPTH_INTERVAL_NS
                val frame = walk.frame(i)
                events += ArDebugEvent.CameraPose(now, frame.pose)
                dense.add(dense.project(frame, now, farM = ScanPreset.ROOM.farM))
                if (i == 0) events += planes(walk, now)
            }
            val device = ScanDevice("android", "synthetic", ScanDevice.TIER_DEPTH, ScanDevice.SOURCE_RAW_DEPTH)
            val pack: RerunCapturePack = runBlocking {
                RerunCaptureBuilder.build(
                    events, ReplayLens.Default, emptyList(), device, dense.savedCloud(), dense.fusion.voxelM,
                )
            }
            return RerunScanFile.write(pack)
        }

        /** Each of the first [SYNTHETIC_ROOMS] rooms' floor and walls, as planes at [nanos]. */
        fun planes(walk: ScanWalkthrough, nanos: Long): List<ArDebugEvent> = (0 until SYNTHETIC_ROOMS).flatMap { room ->
            val o = walk.offset(room)
            val x0 = o.x + RerunSyntheticRoom.MIN.x
            val x1 = o.x + RerunSyntheticRoom.MAX.x
            val z0 = o.z + RerunSyntheticRoom.MIN.z
            val z1 = o.z + RerunSyntheticRoom.MAX.z
            val bottom = RerunSyntheticRoom.MIN.y + PLANE_INSET_M
            val top = RerunSyntheticRoom.MAX.y - PLANE_INSET_M
            val id = PLANE_IDS * (room + 1)
            val floorY = RerunSyntheticRoom.MIN.y
            listOf(
                ArDebugEvent.Plane(
                    nanos, id, DebugPlaneKind.Floor,
                    floatArrayOf(x0, floorY, z0, x1, floorY, z0, x1, floorY, z1, x0, floorY, z1),
                ),
                ArDebugEvent.Plane(nanos, id + 1, DebugPlaneKind.Wall, alongX(z0, x0 - OVER_M, x1 + OVER_M, bottom, top)),
                ArDebugEvent.Plane(nanos, id + 2, DebugPlaneKind.Wall, alongX(z1, x0 - OVER_M, x1 + OVER_M, bottom, top)),
                ArDebugEvent.Plane(nanos, id + 3, DebugPlaneKind.Wall, alongZ(x0, z0 - OVER_M, z1 + OVER_M, bottom, top)),
                ArDebugEvent.Plane(nanos, id + 4, DebugPlaneKind.Wall, alongZ(x1, z0 - OVER_M, z1 + OVER_M, bottom, top)),
            )
        }

        fun alongX(z: Float, x0: Float, x1: Float, y0: Float, y1: Float) =
            floatArrayOf(x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z)

        fun alongZ(x: Float, z0: Float, z1: Float, y0: Float, y1: Float) =
            floatArrayOf(x, y0, z0, x, y0, z1, x, y1, z1, x, y1, z0)

        const val SYNTHETIC_ROOMS = 2
        const val WALLS_PER_ROOM = 4
        const val WALK_YAW_STEPS = 27
        const val PHONE_BYTES = 256L shl 20
        /**
         * How far past its room each wall plane grows, at each end: under half the 2 m between
         * rooms less one wall's gap ([PlaneWallSource.SAME_WALL_GAP_M]), so the rooms' walls in
         * line stay two walls.
         */
        const val OVER_M = 0.5f
        const val PLANE_INSET_M = 0.15f
        const val PLANE_IDS = 10

        /** Nearest rank. */
        fun List<Double>.pct(p: Double): Double {
            val sorted = sorted()
            return sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
        }
    }
}
