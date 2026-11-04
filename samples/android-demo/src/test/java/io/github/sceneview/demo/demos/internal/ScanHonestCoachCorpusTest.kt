package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.RerunCaptureBuilder
import io.github.sceneview.demo.demos.ScanDenseMap
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
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the live scan says against what the scan holds, before and after the floor-band rule
 * ([LiveCoverageGround.weakCells]) and the turn-aware coach ([ScanCoach]): on every `.svscan` and
 * `.rrd` under `SCENEVIEW_SCAN_CORPUS`, written to `SCENEVIEW_SCAN_OUT`.
 *
 * - `<scan>-floor.png`: the floor from above at the scan's last pose, the cells the live map paints
 *   "to redo" on the left before, on the right after; covered cells blue, to redo soft red, the
 *   phone black. Same pose, same framing, at most 1,000 px wide.
 * - `honest-coach-summary.txt`: per scan, the cells painted to redo over a floor the saved cloud
 *   does hold (false), before and after; and how often "Moving too fast" shows on frames a sharp
 *   photo would be taken on ([ScanPhotoMotion]) and on frames it would not, before and after.
 *
 * Skipped when either variable is unset: scans stay off the repository. Run with
 * `SCENEVIEW_SCAN_CORPUS=<dir> SCENEVIEW_SCAN_OUT=<dir> ./gradlew :samples:android-demo:testDebugUnitTest
 * --tests '*ScanHonestCoachCorpusTest' --rerun`.
 */
class ScanHonestCoachCorpusTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `every scan in the corpus, before and after`() {
        val corpus = System.getenv(CORPUS)?.takeIf { it.isNotBlank() }?.let(::File)
        val out = System.getenv(OUT)?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("$CORPUS and $OUT not both set", corpus != null && out != null)
        val results = ScanHonestCoachCorpus.run(corpus!!, out!!)
        // A corpus set and nothing read from it is a failure, not a skip.
        assertTrue("no readable .svscan or .rrd with a dense cloud under $corpus", results.isNotEmpty())
        val flagged = results.sumOf { it.walk.after }
        val falseCells = results.sumOf { it.walk.falseAfter }
        assertTrue(
            "after: $falseCells of $flagged cells red over a floor the scans hold",
            falseCells <= MAX_FALSE_SHARE * flagged,
        )
        assertEquals("after: \"Moving too fast\" on a calm camera", 0, results.sumOf { it.after.coach.falseOnsets })
    }

    @Test
    fun `a synthetic room swept too fast in one patch, end to end`() {
        val corpus = temp.newFolder("corpus")
        val out = System.getenv(SYNTHETIC_OUT)?.takeIf { it.isNotBlank() }?.let(::File) ?: temp.newFolder("out")
        File(corpus, "synthetic-living-room.svscan").writeBytes(ScanHonestCoachCorpus.syntheticRecording())
        val result = ScanHonestCoachCorpus.run(corpus, out).single()
        assertTrue(File(out, "synthetic-living-room.svscan-floor.png").length() > 0)
        assertTrue(File(out, ScanHonestCoachCorpus.SUMMARY).readText().contains("synthetic-living-room"))
        // The truth, from the generator, not from the rule: which floor the fused depth frames
        // saw (ray cast), and which patches it thinned.
        val truth = ScanHonestCoachCorpus.syntheticTruth()
        val report = buildString {
            appendLine(result)
            appendLine("  independent truth: ${truth.healthy.size} floor cells seen whole, ${truth.unseen.size} barely seen")
            for ((k, eye) in result.eyes.withIndex()) {
                appendLine(
                    String.format(
                        java.util.Locale.ROOT,
                        "  refresh %d at (%.1f, %.1f): before %d red, %d over a healthy floor; " +
                            "after %d red, %d over a healthy floor, patch 10 %% %d/4, patch 30 %% %d/4",
                        k, eye.eye.x, eye.eye.z, eye.before.size, eye.before.count { it in truth.healthy },
                        eye.after.size, eye.after.count { it in truth.healthy },
                        eye.after.count { it in truth.thin }, eye.after.count { it in truth.thirty },
                    ),
                )
            }
        }
        // The whole room seen from its centre and from its three stops, at the live card's budget.
        val saved = requireNotNull(RescanZonesCorpusTest.cloudOf(File(corpus, "synthetic-living-room.svscan")))
        val map = ScanHonestCoachCorpus.seeded(saved, DenseFusion.VOXEL_M)
        val covered = CoverageGrid.coveredKeys(saved.positions)
        val cell = CoverageGrid.CELL_METRES
        val views = listOf("centre" to Vec3(0f, 1.4f, 0f)) + RerunSyntheticRoom.STOPS.mapIndexed { k, v -> "stop ${k + 1}" to v }
        val still = views.map { (name, eye) ->
            val live = map.liveCloud(eye.x, eye.y, eye.z, maxPoints = ScanDenseMap.LIVE_DRAWN_SURFELS)
            val before = ScanHonestCoachCorpus.legacyWeakCells(live.positions, covered, cell)
            val after = LiveCoverageGround.weakCells(map.surfels(), DenseFusion.VOXEL_M, covered, cell, eye.y - LiveCoverageGround.PHONE_HEIGHT_M).toSet()
            Triple(name, before, after)
        }
        val full = report + still.joinToString("") { (name, before, after) ->
            String.format(
                java.util.Locale.ROOT,
                "  %s, the live card at %d surfels: before %d red, %d over a healthy floor; after %d red, %d over a healthy floor, " +
                    "patch 10 %% %d/4, patch 30 %% %d/4\n",
                name, ScanDenseMap.LIVE_DRAWN_SURFELS, before.size,
                before.count { it in truth.healthy }, after.size, after.count { it in truth.healthy },
                after.count { it in truth.thin }, after.count { it in truth.thirty },
            )
        }
        println(full)
        File(out, "synthetic-truth.txt").writeText(full)
        for ((_, _, after) in still) {
            assertEquals(full, 0, after.count { it in truth.healthy })
            assertTrue(full, after.count { it in truth.thin } >= 3 && after.count { it in truth.thirty } >= 3)
        }
        // After: never red over a floor the frames saw whole.
        for (eye in result.eyes) assertEquals(report, 0, eye.after.count { it in truth.healthy })
        // At the last pose, by the patches: both are red.
        val last = result.eyes.last()
        assertTrue(report, last.after.count { it in truth.thin } >= 3)
        assertTrue(report, last.after.count { it in truth.thirty } >= 3)
        // The coach: the whip pan is told off now, the calm walk still is not.
        assertTrue(result.toString(), result.after.coach.shakyCoached > result.before.coach.shakyCoached)
        assertEquals(result.toString(), 0, result.after.coach.falseOnsets)
        assertTrue(result.toString(), result.after.coach.onsets >= 2)
        // Over the walk, the merged far floor no longer turns red.
        assertEquals(result.toString(), 0, result.walk.falseAfter)
    }

    @Test
    fun `the map read in place judges as its cloud does, and what it costs`() {
        // The live map judges the whole map in place on the snapshot's worker; the corpus run, a
        // map seeded with the saved cloud ([ScanHonestCoachCorpus.seeded]). All three agree.
        val fusion = DenseFusion()
        RerunSyntheticRoom.frames().forEach { fusion.add(DepthBackProjection.project(it)) }
        val cloud = fusion.cloud(minViews = DenseFusion.MIN_VIEWS)
        val seeded = ScanHonestCoachCorpus.seeded(cloud, DenseFusion.VOXEL_M)
        val cell = CoverageGrid.CELL_METRES
        val covered = CoverageGrid.coveredKeys(cloud.positions)
        val hint = RerunSyntheticRoom.STOPS[0].y - LiveCoverageGround.PHONE_HEIGHT_M
        val inPlace = LiveCoverageGround.weakCells(fusion.surfels(), DenseFusion.VOXEL_M, covered, cell, hint).toSet()
        val copied = LiveCoverageGround.weakCells(cloud, DenseFusion.VOXEL_M, covered, cell, hint).toSet()
        val again = LiveCoverageGround.weakCells(seeded.surfels(), DenseFusion.VOXEL_M, covered, cell, hint).toSet()
        assertEquals(copied, inPlace)
        assertEquals(copied, again)

        // The cost per snapshot on the biggest synthetic map: the QA walkthrough's flat.
        val flat = DenseFusion()
        ScanWalkthrough().frames().forEach { flat.add(DepthBackProjection.project(it)) }
        val flatCovered = CoverageGrid.coveredKeys(flat.cloudPositions(minViews = DenseFusion.MIN_VIEWS))
        // The JVM's per-thread allocation counter, by reflection: android.jar has no java.lang.management.
        val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val allocated = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val id = Thread.currentThread().id
        val times = ArrayList<Double>()
        var bytes = 0L
        repeat(COST_RUNS) { run ->
            val before = (allocated.invoke(threads, id) as Long)
            val started = System.nanoTime()
            LiveCoverageGround.weakCells(flat.surfels(), DenseFusion.VOXEL_M, flatCovered, cell, hint)
            times += (System.nanoTime() - started) / 1e6
            if (run == COST_RUNS - 1) bytes = (allocated.invoke(threads, id) as Long) - before
        }
        val median = times.sorted()[COST_RUNS / 2]
        val line = String.format(
            java.util.Locale.ROOT,
            "flat: %d surfels, %d covered cells; weakCells in place %.1f ms median of %d (first %.1f ms), %d kB allocated",
            flat.count, flatCovered.size, median, COST_RUNS, times.first(), bytes / BYTES_PER_KB,
        )
        println(line)
        System.getenv(SYNTHETIC_OUT)?.takeIf { it.isNotBlank() }?.let { File(it, "cost.txt").writeText(line + "\n") }
        // Allocation stays per cell and per tile, never per surfel.
        assertTrue(line, bytes < flatCovered.size * MAX_BYTES_PER_CELL + MAX_FIXED_BYTES)
    }

    private companion object {
        const val CORPUS = "SCENEVIEW_SCAN_CORPUS"
        const val OUT = "SCENEVIEW_SCAN_OUT"

        const val COST_RUNS = 7
        const val BYTES_PER_KB = 1024L
        const val MAX_BYTES_PER_CELL = 1024
        const val MAX_FIXED_BYTES = 4L * 1024 * 1024

        /** The most cells painted to redo over a floor the scans hold, as a share of those painted. */
        const val MAX_FALSE_SHARE = 0.05f

        /** Where the synthetic run's board goes when set, to look at; a temp folder otherwise. */
        const val SYNTHETIC_OUT = "SCENEVIEW_SYNTHETIC_OUT"
    }
}

/** The run behind [ScanHonestCoachCorpusTest], and the synthetic recording it is checked on. */
internal object ScanHonestCoachCorpus {
    const val SUMMARY = "honest-coach-summary.txt"

    /** The floor cells painted to redo, and how many of them lie over a floor the scan holds. */
    class Floor(val weak: Set<Long>, val falseCells: Int, val judged: Int, val coach: Coach) {
        override fun toString() = "${weak.size} to redo, $falseCells false of $judged floor cells; coach $coach"
    }

    /**
     * Frames ([total]) of a scan's camera, those a sharp photo would not be taken on ([shaky]),
     * how many of those heard "Moving too fast" ([shakyCoached]); the times it came on
     * ([onsets]), and of those, the times it came on with three quarters of the last [ONSET_WINDOW_S] calm
     * ([falseOnsets]) — a false alarm. Once on, the line holds to be read: calm frames under it are
     * not counted against it.
     */
    class Coach(val total: Int, val shaky: Int, val shakyCoached: Int, val onsets: Int, val falseOnsets: Int) {
        val shakyCoachedShare: Float get() = if (shaky == 0) 0f else shakyCoached.toFloat() / shaky

        override fun toString() = String.format(
            Locale.ROOT,
            "%d frames, %d shaky, told on %.0f %% of them; came on %d times, %d on a calm camera",
            total, shaky, shakyCoachedShare * PERCENT, onsets, falseOnsets,
        )
    }

    /**
     * The live map refreshed [refreshes] times along the walk: the cells it painted to redo, summed
     * over the refreshes, and how many of them lay over a floor the scan holds, before and after.
     */
    class Walk(val refreshes: Int, val before: Int, val falseBefore: Int, val after: Int, val falseAfter: Int) {
        private fun rate(f: Int, n: Int) = if (n == 0) 0f else f * PERCENT / n

        override fun toString() = String.format(
            Locale.ROOT,
            "%d refreshes: before %d to redo, %d false (%.0f %%); after %d to redo, %d false (%.0f %%)",
            refreshes, before, falseBefore, rate(falseBefore, before), after, falseAfter, rate(falseAfter, after),
        )
    }

    /** One refresh of the live map: where from, red before and after. */
    class Eye(val eye: Vec3, val before: Set<Long>, val after: Set<Long>)

    class Result(
        val name: String,
        val before: Floor,
        val after: Floor,
        val walk: Walk,
        val turnCaps: String,
        val eyes: List<Eye>,
    ) {
        override fun toString() =
            "$name\n  at the last pose, before: $before\n  at the last pose, after:  $after\n" +
                "  over the walk: $walk\n  turn caps at 0.6 m/s: $turnCaps"
    }

    fun run(corpus: File, out: File): List<Result> {
        val scans = corpus.walkTopDown()
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in RescanZonesCorpusTest.EXTENSIONS }
            .sortedBy { it.path }
            .toList()
        if (scans.isEmpty()) return emptyList()
        out.mkdirs()
        val summary = StringBuilder()
        summary.appendLine("Floor: cells painted to redo; false = over a floor the saved cloud holds at full detail.")
        summary.appendLine("Coach: shaky = a frame ScanPhotoMotion would not take a sharp photo on; a false onset comes on")
        summary.appendLine("  with the last second mostly calm. Before = 1.2 m/s, no turn cap; after = 0.6 m/s, 60°/s.")
        val results = scans.mapNotNull { scan ->
            val result = runCatching { runOne(scan, out) }.getOrElse {
                summary.appendLine("${scan.name}: not read (${it.message})")
                null
            }
            result?.also { summary.appendLine(it.toString()) }
        }
        File(out, SUMMARY).writeText(summary.toString())
        println(summary)
        return results
    }

    @Suppress("LongMethod")
    private fun runOne(scan: File, out: File): Result? {
        val bytes = scan.readBytes()
        val pack = when (RerunFileKind.of(scan.name, bytes.copyOf(min(bytes.size, HEAD_BYTES)))) {
            RerunFileKind.Scan -> RerunScanFile.read(bytes)
            RerunFileKind.Rrd -> RerunRrdReader.capturePack(bytes, scan.name, FakeRerunImageCodec)
            null -> null
        } ?: return null
        val opened = pack.open() ?: return null
        val dense = opened.manifest.dense ?: return null
        val saved = opened.bytesOf(dense.path)?.let { SvpcCodec.decode(it) }?.takeIf { it.count > 0 } ?: return null
        val voxelM = dense.voxelM
        val poses = parseArDebugLog(pack.log).filterIsInstance<ArDebugEvent.CameraPose>().sortedBy { it.nanos }
        val eyePose = poses.lastOrNull()?.pose ?: return null
        val eye = Vec3(eyePose.x, eyePose.y, eyePose.z)
        val trace = opened.trace
        val hint = ArDebugGeometry.floorHeight(trace.frameAt(trace.duration))
        val cell = CoverageGrid.CELL_METRES

        // What the scan holds: each cell's floor at full detail.
        val floorY = LiveCoverageGround.floorOf(saved, voxelM, hint)
        val all = CoverageGrid.coveredKeys(saved.positions)
        val shares = LiveCoverageGround.shares(saved, voxelM, all, cell, floorY).floor
        val held = all.indices.associate { all[it] to shares[it] }
        val floorCells = shares.count { it > 0f }
        fun falseOf(weak: Set<Long>) = weak.count { (held[it] ?: 0f) >= LiveCoverageGround.WEAK_SHARE }

        // The live map's view from the phone every [EYE_STEP_S] of the walk and at its last pose:
        // the saved cloud added to a new map, drawn as the app draws it, at its budget, judged
        // both ways. The grid's covered cells come from the whole map, as in the app.
        val eyes = poses.fold(ArrayList<ArDebugEvent.CameraPose>()) { kept, e ->
            if (kept.isEmpty() || (e.nanos - kept.last().nanos) / NS_PER_S >= EYE_STEP_S || e === poses.last()) kept += e
            kept
        }
        var walkBefore = 0
        var walkAfter = 0
        var walkFalseBefore = 0
        var walkFalseAfter = 0
        val covered = all
        var before = emptySet<Long>()
        var after = emptySet<Long>()
        val map = seeded(saved, voxelM)
        val seen = ArrayList<Eye>()
        for (e in eyes) {
            val at = Vec3(e.pose.x, e.pose.y, e.pose.z)
            // Before: the old count on the snapshot the live card drew. After: the whole map, as
            // the snapshot's worker judges it.
            val live = map.liveCloud(at.x, at.y, at.z, maxPoints = ScanDenseMap.LIVE_DRAWN_SURFELS)
            before = legacyWeakCells(live.positions, covered, cell)
            after = LiveCoverageGround.weakCells(map.surfels(), voxelM, covered, cell, at.y - LiveCoverageGround.PHONE_HEIGHT_M).toSet()
            seen += Eye(at, before, after)
            walkBefore += before.size
            walkAfter += after.size
            walkFalseBefore += falseOf(before)
            walkFalseAfter += falseOf(after)
        }
        val walk = Walk(eyes.size, walkBefore, walkFalseBefore, walkAfter, walkFalseAfter)

        val old = coach(poses, ScanCoach(maxSpeed = LEGACY_MAX_SPEED, maxTurn = Float.POSITIVE_INFINITY))
        val new = coach(poses, ScanCoach())
        val caps = TURN_CAPS.joinToString("; ") { cap ->
            val c = coach(poses, ScanCoach(maxTurn = cap))
            String.format(Locale.ROOT, "%.0f°/s told on %.0f %% of shaky, %d/%d false", cap, c.shakyCoachedShare * PERCENT, c.falseOnsets, c.onsets)
        }
        File(out, "${scan.name}-floor.png").writeBytes(board(covered, before, after, eye))
        return Result(
            scan.name,
            Floor(before, falseOf(before), floorCells, old),
            Floor(after, falseOf(after), floorCells, new),
            walk,
            caps,
            seen,
        )
    }

    /** "Moving too fast" over [poses], frame by frame, against [ScanPhotoMotion]'s calm. */
    fun coach(poses: List<ArDebugEvent.CameraPose>, coach: ScanCoach): Coach {
        if (poses.isEmpty()) return Coach(0, 0, 0, 0, 0)
        val motion = ScanPhotoMotion()
        val start = poses.first().nanos
        var shaky = 0
        var shakyCoached = 0
        var onsets = 0
        var falseOnsets = 0
        var was = false
        val recent = ArrayDeque<Pair<Long, Boolean>>()
        for (event in poses) {
            val calm = motion.isCalm(event.nanos, event.pose)
            val told = coach.update((event.nanos - start) / NS_PER_S, event.pose, grew = true) == ScanCoach.Line.Slower
            recent.addLast(event.nanos to calm)
            while (event.nanos - recent.first().first > ONSET_WINDOW_S * NS_PER_S) recent.removeFirst()
            if (!calm) shaky++
            if (told && !calm) shakyCoached++
            if (told && !was) {
                onsets++
                if (recent.count { !it.second } * SHAKY_SHARE_MIN < recent.size) falseOnsets++
            }
            was = told
        }
        return Coach(poses.size, shaky, shakyCoached, onsets, falseOnsets)
    }

    /**
     * The rule the live map had before the floor band: a covered cell holding fewer than a quarter
     * of the median covered cell's points, every point above it counted, walls and all.
     */
    fun legacyWeakCells(positions: FloatArray, covered: LongArray, cellM: Float): Set<Long> {
        if (covered.size < LEGACY_MIN_CELLS) return emptySet()
        val counts = HashMap<Long, Int>(covered.size * 2)
        for (key in covered) counts[key] = 0
        for (i in 0 until positions.size / 3) {
            val key = CoverageGrid.key(floor(positions[i * 3] / cellM).toInt(), floor(positions[i * 3 + 2] / cellM).toInt())
            val n = counts[key] ?: continue
            counts[key] = n + 1
        }
        val sorted = counts.values.sorted()
        val median = sorted[sorted.size / 2]
        if (median < LEGACY_MIN_MEDIAN) return emptySet()
        return covered.filter { (counts[it] ?: 0) < median * LEGACY_SHARE }.toSet()
    }

    /**
     * A map holding [cloud] as a scan's map held it: each surfel added twice (seen by two depth
     * frames, [DenseFusion.MIN_VIEWS]) at full detail, a coarser one as the full-detail squares of
     * its footprint in its own plane. Its [DenseFusion.liveCloud] is what the live map drew.
     */
    fun seeded(cloud: DenseCloud, voxelM: Float): DenseFusion {
        val positions = ArrayList<Float>(cloud.count * 3)
        val normals = ArrayList<Float>(cloud.count * 3)
        val colors = ArrayList<Int>(cloud.count)
        val p = cloud.positions
        for (i in 0 until cloud.count) {
            val n = cloud.normals?.let { Vec3(it[i * 3], it[i * 3 + 1], it[i * 3 + 2]) }?.takeIf { it.length() > TINY }
                ?.normalized() ?: Vec3(0f, 1f, 0f)
            val span = 1 shl cloud.levelOf(i)
            // Two directions across the surfel's plane.
            val t1 = (if (kotlin.math.abs(n.y) < UPRIGHT) Vec3(0f, 1f, 0f) else Vec3(1f, 0f, 0f)).cross(n).normalized()
            val t2 = n.cross(t1)
            for (a in 0 until span) for (b in 0 until span) {
                val u = ((a + HALF) / span - HALF) * voxelM * span
                val v = ((b + HALF) / span - HALF) * voxelM * span
                positions += p[i * 3] + t1.x * u + t2.x * v
                positions += p[i * 3 + 1] + t1.y * u + t2.y * v
                positions += p[i * 3 + 2] + t1.z * u + t2.z * v
                normals += n.x
                normals += n.y
                normals += n.z
                colors += cloud.colors[i]
            }
        }
        val count = colors.size
        val samples = DenseSamples(
            count,
            positions.toFloatArray(),
            normals.toFloatArray(),
            colors.toIntArray(),
            ByteArray(count) { FULL_CONFIDENCE },
        )
        return DenseFusion(voxelM).apply {
            add(samples)
            add(samples)
        }
    }

    /** The floor from above, x right and z down: before on the left, after on the right, [eye] in black. */
    private fun board(covered: LongArray, before: Set<Long>, after: Set<Long>, eye: Vec3): ByteArray {
        val cell = CoverageGrid.CELL_METRES
        val minC = covered.minOf { CoverageGrid.cxOf(it) } - 1
        val maxC = covered.maxOf { CoverageGrid.cxOf(it) } + 1
        val minR = covered.minOf { CoverageGrid.czOf(it) } - 1
        val maxR = covered.maxOf { CoverageGrid.czOf(it) } + 1
        val px = max(MIN_CELL_PX, min(MAX_CELL_PX, (PANEL_PX / max(maxC - minC + 1, maxR - minR + 1).toFloat()).toInt()))
        val panelW = (maxC - minC + 1) * px
        val panelH = (maxR - minR + 1) * px
        val canvas = PlanCanvas(2 * panelW + 3 * MARGIN, panelH + 2 * MARGIN + LABEL_PX)
        for ((k, weak) in listOf(before, after).withIndex()) {
            val left = MARGIN + k * (panelW + MARGIN)
            for (key in covered) {
                val x0 = (left + (CoverageGrid.cxOf(key) - minC) * px).toFloat()
                val y0 = (MARGIN + (CoverageGrid.czOf(key) - minR) * px).toFloat()
                val x1 = x0 + px - 1
                val y1 = y0 + px - 1
                canvas.fillPolygon(floatArrayOf(x0, x1, x1, x0), floatArrayOf(y0, y0, y1, y1), if (key in weak) WEAK else SEEN)
            }
            val ex = left + (eye.x / cell - minC) * px
            val ey = MARGIN + (eye.z / cell - minR) * px
            canvas.line(ex - EYE_PX, ey, ex + EYE_PX, ey, EYE_PX.toFloat(), INK)
            canvas.text(left, MARGIN + panelH + LABEL_GAP, "${weak.size}", TEXT_SCALE, INK)
        }
        return canvas.png()
    }

    // ── The synthetic recording ──────────────────────────────────────────────────────────────

    /**
     * The synthetic floor's two patches swept too fast, 2 × 2 cells each by where the walk ends,
     * clear of the furniture: one kept at a tenth ([THIN_KEEP]), one at three tenths
     * ([THIRTY_KEEP]), just under [LiveCoverageGround.WEAK_SHARE], in 2 cm stripes.
     */
    private val THIN = floatArrayOf(0.5f, 0.5f, 1f, 1f)
    private val THIRTY = floatArrayOf(1f, 0.5f, 1.5f, 1f)

    private fun cellsOf(box: FloatArray): Set<Long> = buildSet {
        val cell = CoverageGrid.CELL_METRES
        for (c in floor(box[0] / cell).toInt() until floor(box[2] / cell).toInt()) {
            for (r in floor(box[1] / cell).toInt() until floor(box[3] / cell).toInt()) add(CoverageGrid.key(c, r))
        }
    }

    private fun inBox(box: FloatArray, x: Float, z: Float) = x >= box[0] && x < box[2] && z >= box[1] && z < box[3]

    /**
     * [RerunSyntheticRoom] fused as the app fuses it, the floor of [THIN] and [THIRTY] kept in
     * stripes (a patch swept too fast: a frame here and there stuck), and a camera walk of calm
     * sweeps, a whip pan and a brisk walk at 30 frames a second.
     */
    fun syntheticRecording(): ByteArray {
        val fusion = DenseFusion()
        RerunSyntheticRoom.frames().forEach { fusion.add(DepthBackProjection.project(it)) }
        val full = fusion.cloud()
        val keep = BooleanArray(full.count) { i ->
            val x = full.positions[i * 3]
            val y = full.positions[i * 3 + 1]
            val z = full.positions[i * 3 + 2]
            val stripe = floor(x / STRIPE_M).toInt().mod(STRIPES)
            when {
                y >= LiveCoverageGround.FLOOR_BAND_M -> true
                inBox(THIN, x, z) -> stripe < THIN_KEEP
                inBox(THIRTY, x, z) -> stripe < THIRTY_KEEP
                else -> true
            }
        }
        val cloud = full.select(keep)
        val lens = ReplayLens.of(
            RerunSyntheticRoom.DEPTH_WIDTH.toFloat(), RerunSyntheticRoom.DEPTH_HEIGHT.toFloat(),
            RerunSyntheticRoom.FOCAL, RerunSyntheticRoom.FOCAL,
        )!!
        val device = ScanDevice("android", "Pixel 9", ScanDevice.TIER_DEPTH, ScanDevice.SOURCE_RAW_DEPTH)
        return RerunScanFile.write(runBlocking { RerunCaptureBuilder.build(walk(), lens, emptyList(), device, cloud) })
    }

    /**
     * What the synthetic scan really holds, from its generator and never from the rule: the floor
     * cells clear of furniture that at least two of its depth frames saw whole ([healthy], at
     * least [SEEN_WHOLE] of [SAMPLES]² points on the cell, ray-cast through the room) or barely
     * ([unseen], under [SEEN_BARELY]); and the two patches it thinned.
     */
    class Truth(val healthy: Set<Long>, val unseen: Set<Long>, val thin: Set<Long>, val thirty: Set<Long>)

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    fun syntheticTruth(): Truth {
        val cell = CoverageGrid.CELL_METRES
        val scene = RerunSyntheticRoom.scene
        val poses = RerunSyntheticRoom.poses()
        val inverse = poses.map { DebugPose(0f, 0f, 0f, qx = -it.qx, qy = -it.qy, qz = -it.qz, qw = it.qw) }
        val halfW = RerunSyntheticRoom.DEPTH_WIDTH / 2f
        val halfH = RerunSyntheticRoom.DEPTH_HEIGHT / 2f
        val min = RerunSyntheticRoom.MIN
        val max = RerunSyntheticRoom.MAX
        val healthy = HashSet<Long>()
        val unseen = HashSet<Long>()
        val patches = cellsOf(THIN) + cellsOf(THIRTY)
        for (c in floor(min.x / cell).toInt() until floor(max.x / cell).toInt()) {
            for (r in floor(min.z / cell).toInt() until floor(max.z / cell).toInt()) {
                val x0 = c * cell
                val z0 = r * cell
                val inside = x0 >= min.x && x0 + cell <= max.x && z0 >= min.z && z0 + cell <= max.z
                val box = floatArrayOf(x0, z0, x0 + cell, z0 + cell)
                val clear = FURNITURE.none { f -> f[0] < box[2] && f[2] > box[0] && f[1] < box[3] && f[3] > box[1] }
                val key = CoverageGrid.key(c, r)
                if (!inside || !clear || key in patches) continue
                var seen = 0
                for (a in 0 until SAMPLES) for (b in 0 until SAMPLES) {
                    val px = x0 + (a + HALF) / SAMPLES * cell
                    val pz = z0 + (b + HALF) / SAMPLES * cell
                    var views = 0
                    for ((k, pose) in poses.withIndex()) {
                        val vx = px - pose.x
                        val vy = FLOOR_LIFT - pose.y
                        val vz = pz - pose.z
                        val local = inverse[k].rotate(vx, vy, vz)
                        val depth = -local.z
                        if (depth < DepthBackProjection.NEAR_M || depth > DepthBackProjection.FAR_M) continue
                        val u = local.x / depth * RerunSyntheticRoom.FOCAL
                        val v = local.y / depth * RerunSyntheticRoom.FOCAL
                        if (kotlin.math.abs(u) > halfW || kotlin.math.abs(v) > halfH) continue
                        val dist = sqrt(vx * vx + vy * vy + vz * vz)
                        val hit = scene.shapes.minOf { it.hit(pose.x, pose.y, pose.z, vx / dist, vy / dist, vz / dist) }
                        if (hit < dist - OCCLUSION_M) continue
                        if (++views >= DenseFusion.MIN_VIEWS) break
                    }
                    if (views >= DenseFusion.MIN_VIEWS) seen++
                }
                val share = seen.toFloat() / (SAMPLES * SAMPLES)
                if (share >= SEEN_WHOLE) healthy += key
                if (share < SEEN_BARELY) unseen += key
            }
        }
        return Truth(healthy, unseen, cellsOf(THIN), cellsOf(THIRTY))
    }

    /** Furniture footprints in the synthetic room, (x0, z0, x1, z1): sofa, table, cabinet, ball. */
    private val FURNITURE = listOf(
        floatArrayOf(-1.7f, -1.8f, -0.3f, -0.95f),
        floatArrayOf(0.3f, -0.3f, 1.3f, 0.4f),
        floatArrayOf(1.55f, 0.6f, 2f, 1.6f),
        floatArrayOf(-1.4f, 0.7f, -0.8f, 1.3f),
    )

    /**
     * A phone's walk, 30 frames a second, a hand's tremor on it: a calm pan (30°/s, 0.2 m/s), a
     * whip pan (120°/s), a calm pan, a brisk walk (0.9 m/s, no turn), then standing.
     */
    private fun walk(): List<ArDebugEvent> {
        val events = ArrayList<ArDebugEvent>()
        val random = java.util.Random(SEED)
        var x = 1.2f
        var z = -1f
        var yaw = 0f
        val legs = listOf(
            Leg(4f, turn = 30f, speed = 0.2f),
            Leg(1.5f, turn = 120f, speed = 0f),
            Leg(4f, turn = -30f, speed = 0.2f),
            Leg(2f, turn = 0f, speed = 0.9f),
            Leg(3f, turn = 0f, speed = 0f),
        )
        var k = 0
        for (leg in legs) {
            val frames = (leg.seconds * FPS).toInt()
            repeat(frames) {
                yaw += leg.turn / FPS
                x += leg.speed / FPS * sin(yaw * PI.toFloat() / HALF_TURN)
                z -= leg.speed / FPS * cos(yaw * PI.toFloat() / HALF_TURN)
                val tremor = (random.nextGaussian() * TREMOR_DEG).toFloat()
                val pose = RerunSyntheticRoom.lookPose(
                    Vec3(x, 1.4f, z),
                    (yaw + tremor) * PI.toFloat() / HALF_TURN,
                    PITCH_DEG * PI.toFloat() / HALF_TURN,
                )
                events += ArDebugEvent.CameraPose((++k) * FRAME_NANOS, pose)
            }
        }
        return events
    }

    private class Leg(val seconds: Float, val turn: Float, val speed: Float)

    private fun DenseCloud.select(keep: BooleanArray): DenseCloud {
        val n = keep.count { it }
        val pos = FloatArray(n * 3)
        val col = IntArray(n)
        val nor = normals?.let { FloatArray(n * 3) }
        val lev = levels?.let { ByteArray(n) }
        var j = 0
        for (i in 0 until count) {
            if (!keep[i]) continue
            for (a in 0 until 3) {
                pos[j * 3 + a] = positions[i * 3 + a]
                nor?.set(j * 3 + a, normals!![i * 3 + a])
            }
            col[j] = colors[i]
            lev?.set(j, levels!![i])
            j++
        }
        return DenseCloud(pos, col, nor, null, lev)
    }

    private const val HEAD_BYTES = 64
    private const val NS_PER_S = 1e9f
    private const val ONSET_WINDOW_S = 0.5f

    /** How often the corpus run looks at the live map along a walk, in seconds. */
    private const val EYE_STEP_S = 2f

    /** An onset is false with under a quarter of the last [ONSET_WINDOW_S] shaky. */
    private const val SHAKY_SHARE_MIN = 4
    private const val PERCENT = 100f
    private const val LN2 = 0.6931472f

    /** The coach's speed cap before the turn cap: 1.2 m/s, and no cap on turning. */
    private const val LEGACY_MAX_SPEED = 1.2f
    private const val LEGACY_SHARE = 0.25f
    private const val LEGACY_MIN_CELLS = 4
    private const val LEGACY_MIN_MEDIAN = 6
    private val TURN_CAPS = listOf(45f, 60f, 90f)

    private const val STRIPE_M = 0.02f
    private const val STRIPES = 10
    private const val THIN_KEEP = 1
    private const val THIRTY_KEEP = 3
    private const val SAMPLES = 5
    private const val SEEN_WHOLE = 0.65f
    private const val SEEN_BARELY = 0.35f
    private const val FLOOR_LIFT = 0.001f
    private const val OCCLUSION_M = 0.01f
    private const val TINY = 1e-6f
    private const val UPRIGHT = 0.9f
    private const val HALF = 0.5f
    private val FULL_CONFIDENCE = 255.toByte()
    private const val FPS = 30
    private const val FRAME_NANOS = 33_333_333L
    private const val SEED = 4_2L
    private const val TREMOR_DEG = 0.15
    private const val PITCH_DEG = -20f
    private const val HALF_TURN = 180f

    private const val PANEL_PX = 470
    private const val MIN_CELL_PX = 4
    private const val MAX_CELL_PX = 24
    private const val MARGIN = 16
    private const val LABEL_PX = 28
    private const val LABEL_GAP = 6
    private const val TEXT_SCALE = 3
    private const val EYE_PX = 5
    private const val SEEN = 0x8EC5E8
    private const val WEAK = 0xF28B82
    private const val INK = 0x1F1F1F
}
