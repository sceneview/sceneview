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
        assumeTrue("no .svscan or .rrd under $corpus", results.isNotEmpty())
    }

    @Test
    fun `a synthetic room swept too fast in one patch, end to end`() {
        val corpus = temp.newFolder("corpus")
        val out = System.getenv(SYNTHETIC_OUT)?.takeIf { it.isNotBlank() }?.let(::File) ?: temp.newFolder("out")
        File(corpus, "synthetic-living-room.svscan").writeBytes(ScanHonestCoachCorpus.syntheticRecording())
        val result = ScanHonestCoachCorpus.run(corpus, out).single()
        assertTrue(File(out, "synthetic-living-room.svscan-floor.png").length() > 0)
        assertTrue(File(out, ScanHonestCoachCorpus.SUMMARY).readText().contains("synthetic-living-room"))
        // After: nothing red over a floor the scan holds, and the patch swept too fast is red.
        assertEquals(result.toString(), 0, result.after.falseCells)
        val patch = ScanHonestCoachCorpus.patchCells()
        assertTrue(result.toString(), result.after.weak.count { it in patch } >= patch.size - 1)
        // The coach: the whip pan is told off now, the calm walk still is not.
        assertTrue(result.toString(), result.after.coach.shakyCoached > result.before.coach.shakyCoached)
        assertEquals(result.toString(), 0, result.after.coach.falseOnsets)
        assertTrue(result.toString(), result.after.coach.onsets >= 2)
        // Over the walk, the merged far floor no longer turns red.
        assertEquals(result.toString(), 0, result.walk.falseAfter)
    }

    @Test
    fun `the live cloud's merging is what the corpus run stands in for`() {
        // The corpus has the saved cloud, not the live one: [ScanHonestCoachCorpus.liveLike]
        // merges it by distance as [DenseFusion.liveCloud] does. On the synthetic room, both
        // judge the same cells.
        val fusion = DenseFusion()
        RerunSyntheticRoom.frames().forEach { fusion.add(DepthBackProjection.project(it)) }
        val eye = RerunSyntheticRoom.STOPS[2]
        val live = fusion.liveCloud(eye.x, eye.y, eye.z, maxPoints = Int.MAX_VALUE)
        val like = ScanHonestCoachCorpus.liveLike(fusion.cloud(minViews = DenseFusion.MIN_VIEWS), DenseFusion.VOXEL_M, eye)
        val cell = CoverageGrid.CELL_METRES
        val weakLive = LiveCoverageGround.weakCells(live, DenseFusion.VOXEL_M, CoverageGrid.coveredKeys(live.positions), cell, 0f)
        val weakLike = LiveCoverageGround.weakCells(like, DenseFusion.VOXEL_M, CoverageGrid.coveredKeys(like.positions), cell, 0f)
        println("live ${live.count} surfels, ${weakLive.size} weak; like ${like.count} surfels, ${weakLike.size} weak")
        assertEquals(weakLive.toSet(), weakLike.toSet())
    }

    private companion object {
        const val CORPUS = "SCENEVIEW_SCAN_CORPUS"
        const val OUT = "SCENEVIEW_SCAN_OUT"

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

    class Result(val name: String, val before: Floor, val after: Floor, val walk: Walk, val turnCaps: String) {
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

        // The live map's view of the whole cloud from the phone every [EYE_STEP_S] of the walk and
        // at its last pose: the saved cloud merged by distance, judged both ways.
        val eyes = poses.fold(ArrayList<ArDebugEvent.CameraPose>()) { kept, e ->
            if (kept.isEmpty() || (e.nanos - kept.last().nanos) / NS_PER_S >= EYE_STEP_S || e === poses.last()) kept += e
            kept
        }
        var walkBefore = 0
        var walkAfter = 0
        var walkFalseBefore = 0
        var walkFalseAfter = 0
        var covered = LongArray(0)
        var before = emptySet<Long>()
        var after = emptySet<Long>()
        for (e in eyes) {
            val live = liveLike(saved, voxelM, Vec3(e.pose.x, e.pose.y, e.pose.z))
            covered = CoverageGrid.coveredKeys(live.positions)
            before = legacyWeakCells(live.positions, covered, cell)
            after = LiveCoverageGround.weakCells(live, voxelM, covered, cell, hint).toSet()
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
     * [cloud] as the live map would hold it seen from [eye] ([DenseFusion.liveCloud]): surfels
     * nearer than [DenseFusion.LIVE_FIRST_LEVEL_M] as they are, farther ones merged one level
     * coarser each time the distance doubles, up to [DenseFusion.LIVE_MAX_LEVEL], positions
     * averaged by the surface each stands for.
     */
    fun liveLike(cloud: DenseCloud, voxelM: Float, eye: Vec3): DenseCloud {
        class Cell(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f, var w: Float = 0f, var color: Int = 0)
        val cells = LinkedHashMap<Triple<Int, Long, Int>, Cell>()
        val p = cloud.positions
        for (i in 0 until cloud.count) {
            val x = p[i * 3]
            val y = p[i * 3 + 1]
            val z = p[i * 3 + 2]
            val dx = x - eye.x
            val dy = y - eye.y
            val dz = z - eye.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            val far = if (d < DenseFusion.LIVE_FIRST_LEVEL_M) 0 else 1 + floor(ln(d / DenseFusion.LIVE_FIRST_LEVEL_M) / LN2).toInt()
            val level = max(cloud.levelOf(i), min(far, DenseFusion.LIVE_MAX_LEVEL))
            val side = voxelM * (1 shl level)
            val key = Triple(
                level,
                CoverageGrid.key(floor(x / side).toInt(), floor(z / side).toInt()),
                floor(y / side).toInt(),
            )
            val w = (voxelM * (1 shl cloud.levelOf(i))).let { it * it }
            val c = cells.getOrPut(key) { Cell(color = cloud.colors[i]) }
            c.x += x * w
            c.y += y * w
            c.z += z * w
            c.w += w
        }
        val positions = FloatArray(cells.size * 3)
        val colors = IntArray(cells.size)
        val levels = ByteArray(cells.size)
        var n = 0
        for ((key, c) in cells) {
            positions[n * 3] = c.x / c.w
            positions[n * 3 + 1] = c.y / c.w
            positions[n * 3 + 2] = c.z / c.w
            colors[n] = c.color
            levels[n] = key.first.toByte()
            n++
        }
        return DenseCloud(positions, colors, levels = levels)
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

    /** The patch of floor the synthetic scan sweeps too fast: 3 × 3 cells clear of the furniture. */
    private const val PATCH_MIN_X = -0.5f
    private const val PATCH_MIN_Z = 0.5f
    private const val PATCH_CELLS = 3

    fun patchCells(): Set<Long> {
        val c0 = floor(PATCH_MIN_X / CoverageGrid.CELL_METRES).toInt()
        val r0 = floor(PATCH_MIN_Z / CoverageGrid.CELL_METRES).toInt()
        return buildSet { for (c in 0 until PATCH_CELLS) for (r in 0 until PATCH_CELLS) add(CoverageGrid.key(c0 + c, r0 + r)) }
    }

    /**
     * [RerunSyntheticRoom] fused as the app fuses it, with the floor of [patchCells] kept in
     * [STRIPE_M] stripes one in [STRIPE_EVERY] (a patch swept too fast: only a frame here and there
     * stuck), and a camera
     * walk of calm sweeps, a whip pan and a brisk walk at 30 frames a second.
     */
    fun syntheticRecording(): ByteArray {
        val fusion = DenseFusion()
        RerunSyntheticRoom.frames().forEach { fusion.add(DepthBackProjection.project(it)) }
        val full = fusion.cloud()
        val patchMaxX = PATCH_MIN_X + PATCH_CELLS * CoverageGrid.CELL_METRES
        val patchMaxZ = PATCH_MIN_Z + PATCH_CELLS * CoverageGrid.CELL_METRES
        val keep = BooleanArray(full.count) { i ->
            val x = full.positions[i * 3]
            val y = full.positions[i * 3 + 1]
            val z = full.positions[i * 3 + 2]
            val inPatch = x in PATCH_MIN_X..patchMaxX && z in PATCH_MIN_Z..patchMaxZ && y < LiveCoverageGround.FLOOR_BAND_M
            !inPatch || floor(x / STRIPE_M).toInt().mod(STRIPE_EVERY) == 0
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
    private const val STRIPE_EVERY = 10
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
