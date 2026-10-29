package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.LiveDrawLoad
import io.github.sceneview.demo.demos.ScanCapture
import io.github.sceneview.demo.demos.ScanDenseMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Which far surfels [DenseFusion.liveCloud] draws once its merged cells still outnumber the
 * budget (#4801): from one snapshot to the next a far wall should keep the same ones rather than
 * shimmer, and keep about the cover an even spread gives it.
 *
 * Set `SCENEVIEW_LIVE_FAR_OUT` to a folder to get the walk's figures (`live-far.md`) and a far
 * view of the rooms (`live-far.png`) there.
 */
class DenseFusionFarKeyTest {

    @Test
    fun `past the budget, the live cloud holds the whole budget`() {
        val fusion = floor()
        for (budget in BUDGETS) {
            val cloud = fusion.liveCloud(EYE.x, EYE.y, EYE.z, maxPoints = budget)
            assertEquals("budget $budget", budget, cloud.count)
            assertEquals("budget $budget: surfels a merge made", budget, made(cloud))
        }
    }

    /** At the same merge layout: the floor merges from the same distance whatever the budget. */
    @Test
    fun `past the budget, a smaller budget keeps a part of what a larger one keeps`() {
        val fusion = floor()
        val kept = BUDGETS.map { budget ->
            cells(fusion.liveCloud(EYE.x, EYE.y, EYE.z, maxPoints = budget), fusion.voxelM, EYE, fromM = 0f)
        }
        for (b in 1 until kept.size) {
            val smaller = kept[b - 1]
            val dropped = smaller.keys.count { kept[b].set.get(it) < 0 }
            assertEquals("budget ${BUDGETS[b - 1]}, then ${BUDGETS[b]}: cells it dropped", 0, dropped)
        }
    }

    @Test
    fun `through four rooms past the budget, far surfels hold still and far walls keep their cover`() {
        val walk = Walk.figures
        System.getenv(OUT_ENV)?.takeIf { it.isNotBlank() }?.let { out ->
            File(out).apply { mkdirs() }.resolve(REPORT_FILE).writeText(walk.report)
        }
        println(walk.report)
        for (f in listOf(walk.turning, walk.walking)) {
            assertTrue("${f.name}: snapshots past the budget, ${f.drawn.size}", f.drawn.size >= MIN_SNAPSHOTS)
            assertTrue("${f.name}: drawn ${f.drawn.distinct()}", f.drawn.all { it == LIVE_BUDGET })
            assertTrue("${f.name}: far surfels held ${f.held.mean()}", f.held.mean() >= f.minHeld)
            assertTrue(
                "${f.name}: cover ${f.cover.mean()}, the stride's ${f.strideCover}",
                f.cover.mean() >= f.strideCover * COVER_SHARE,
            )
        }
    }

    /** One way of looking at the map: the figures of each snapshot past the budget, in order. */
    private class Figures(val name: String, val minHeld: Double, val strideCover: Double) {
        val drawn = ArrayList<Int>()
        val held = ArrayList<Double>()
        val cover = ArrayList<Double>()
        val ms = ArrayList<Double>()
        private var last: Cells? = null

        fun add(fusion: DenseFusion, eye: Vec3, cloud: DenseCloud, ms: Double) {
            drawn += made(cloud)
            this.ms += ms
            val far = cells(cloud, fusion.voxelM, eye, fromM = FAR_FROM_M)
            last?.let { before ->
                if (before.keys.isNotEmpty()) {
                    held += before.keys.count { far.set.get(it) >= 0 }.toDouble() / before.keys.size
                }
            }
            last = far
            cover += cover(fusion, cloud, eye)
        }
    }

    /** Merged cells, each as one key: [keys] in the cloud's order, [set] to look one up. */
    private class Cells(val keys: LongArray, val set: LongIntMap)

    private class WalkFigures(val turning: Figures, val walking: Figures, val report: String)

    /**
     * [ScanWalkthrough]'s four rooms scanned once, and [DenseFusion.liveCloud] asked for
     * [LIVE_BUDGET] surfels from the scan's eye every second ([SNAPSHOT_FRAMES]) once the first
     * room and the second's first stop are done — the map past the budget from then on, growing
     * as the phone turns at each stop — then from an eye walked along the finished rooms,
     * [WALK_STEP_M] a snapshot.
     */
    private object Walk {
        val figures: WalkFigures by lazy { run() }

        private fun run(): WalkFigures {
            val walk = ScanWalkthrough(yawSteps = YAW_STEPS, revisit = false)
            var now = 0L
            val budget = DenseFusion.budgetFor(PHONE_HEAP)
            val dense = ScanDenseMap(maxPoints = budget, clock = { now })
            val fusion = DenseFusion(maxPoints = budget)
            val turning = Figures("turning at the stops", MIN_HELD, STRIDE_COVER_TURNING)
            val roomFrames = walk.frameCount / ScanWalkthrough.ROOMS
            val from = roomFrames + roomFrames / RerunSyntheticRoom.STOPS.size
            for (i in 0 until walk.frameCount) {
                now += ScanCapture.DEPTH_INTERVAL_NS
                val frame = walk.frame(i)
                fusion.add(dense.project(frame, now, farM = ScanPreset.ROOM.farM))
                if (i < from || (i + 1) % SNAPSHOT_FRAMES != 0) continue
                val eye = Vec3(frame.pose.x, frame.pose.y, frame.pose.z)
                val start = System.nanoTime()
                val cloud = fusion.liveCloud(eye.x, eye.y, eye.z, maxPoints = LIVE_BUDGET)
                turning.add(fusion, eye, cloud, (System.nanoTime() - start) / NANOS_PER_MS)
            }
            walkAlong(fusion, Figures("warm-up", 0.0, 0.0)) // the JIT, before the timed walk
            val walking = walkAlong(fusion, Figures("walking past the rooms", MIN_HELD, STRIDE_COVER_WALKING))
            System.getenv(OUT_ENV)?.takeIf { it.isNotBlank() }?.let { out ->
                val eye = walk.offset(LAST_ROOM).let { Vec3(it.x + STOP.x, it.y + STOP.y, it.z + STOP.z) }
                val cloud = fusion.liveCloud(eye.x, eye.y, eye.z, maxPoints = LIVE_BUDGET)
                File(out).apply { mkdirs() }.resolve(IMAGE_FILE)
                    .writeBytes(png(draw(cloud, fusion.voxelM, RerunSyntheticRoom.lookPose(VIEW_AT, 0f, 0f))))
            }
            return WalkFigures(turning, walking, report(fusion, turning, walking))
        }

        private fun walkAlong(fusion: DenseFusion, figures: Figures): Figures {
            var x = WALK_FROM_X
            while (x <= WALK_TO_X) {
                val eye = Vec3(x, WALK_Y, WALK_Z)
                var cloud = DenseCloud.Empty
                var best = Double.MAX_VALUE
                repeat(TIMED_RUNS) {
                    val start = System.nanoTime()
                    cloud = fusion.liveCloud(eye.x, eye.y, eye.z, maxPoints = LIVE_BUDGET)
                    best = minOf(best, (System.nanoTime() - start) / NANOS_PER_MS)
                }
                figures.add(fusion, eye, cloud, best)
                x += WALK_STEP_M
            }
            return figures
        }

        private fun report(fusion: DenseFusion, turning: Figures, walking: Figures) = buildString {
            append("${fusion.count} voxels, ${fusion.confirmed} confirmed; $LIVE_BUDGET surfels drawn a snapshot.\n\n")
            append("| Snapshots | Count | Drawn | Far surfels held | Cover | Lowest cover | liveCloud ms p50 | p90 |\n")
            append("|---|---:|---:|---:|---:|---:|---:|---:|\n")
            for (f in listOf(turning, walking)) {
                append("| ${f.name} | ${f.drawn.size} | ${f.drawn.average().roundToInt()} | ${pct(f.held.mean())} | ")
                append("${pct(f.cover.mean())} | ")
                append("${pct(f.cover.minOrNull() ?: 0.0)} | ${ms(f.ms.pct(P50))} | ${ms(f.ms.pct(P90))} |\n")
            }
        }
    }

    private companion object {
        const val OUT_ENV = "SCENEVIEW_LIVE_FAR_OUT"
        const val REPORT_FILE = "live-far.md"
        const val IMAGE_FILE = "live-far.png"

        // The floor: 16 m square, a sample every 8 cm, seen from 1.6 m above a corner, so that
        // even merged from 1 m it is several times the budgets.
        const val FLOOR_M = 16f
        const val FLOOR_STEP_M = 0.08f
        val EYE = Vec3(0.5f, 1.6f, 0.5f)
        val BUDGETS = listOf(2_000, 3_000, 5_000)

        // The walk: a quicker turn at each stop than the bundled room's, a snapshot a second.
        const val YAW_STEPS = 12
        const val SNAPSHOT_FRAMES = 10
        const val PHONE_HEAP = 256L shl 20
        const val LIVE_BUDGET = 8_000
        const val MIN_SNAPSHOTS = 20

        /** How far from 1 a made surfel's squared normal length may be. */
        const val UNIT_SLACK = 1e-3f

        const val WALK_FROM_X = -1.5f
        const val WALK_TO_X = 19.5f
        const val WALK_STEP_M = 0.5f
        const val WALK_Y = 1.4f
        const val WALK_Z = 0f

        /** Surfels this far from the eye are merged whatever the budget: those a pick thins. */
        const val FAR_FROM_M = 2f

        /** Cover: of the map's cells of this side this far from the eye, the share a surfel shows. */
        const val COVER_CELL_M = 0.32f
        const val COVER_FROM_M = 2.5f

        // The cover the stride this replaced gave on this walk (67.0 % and 63.1 %, measured with
        // it), and the share of it the pick must keep: a far wall may not open up for stillness.
        // Measure again if the walk, the budget or the cover cells change.
        const val STRIDE_COVER_TURNING = 0.67
        const val STRIDE_COVER_WALKING = 0.63
        const val COVER_SHARE = 0.9

        /** The far surfels a snapshot keeps of the one before: the stride kept 37 % and 22 %. */
        const val MIN_HELD = 0.6

        // The far view: the live cloud from the last room's first stop, looked at from outside.
        const val LAST_ROOM = ScanWalkthrough.ROOMS - 1
        val STOP = RerunSyntheticRoom.STOPS[0]
        val VIEW_AT = Vec3(5f, 1.3f, 11f)
        const val SHRINK = 2
        const val BACKGROUND = 0x202020
        const val UNCOLOURED = 0xB0B0B0
        const val RGB = 0xFFFFFF

        /** A walked eye's time is the least of this many runs: the map is the same each time. */
        const val TIMED_RUNS = 3
        const val NANOS_PER_MS = 1e6
        const val P50 = 0.5
        const val P90 = 0.9
        const val AXIS_MASK = (1L shl 20) - 1

        fun floor(): DenseFusion {
            val side = (FLOOR_M / FLOOR_STEP_M).toInt()
            val samples = samples(side * side) { i ->
                floatArrayOf((i / side + 0.5f) * FLOOR_STEP_M, 0f, (i % side + 0.5f) * FLOOR_STEP_M)
            }
            val fusion = DenseFusion()
            fusion.add(samples)
            fusion.add(samples)
            return fusion
        }

        fun samples(count: Int, at: (Int) -> FloatArray): DenseSamples {
            val positions = FloatArray(count * 3)
            for (i in 0 until count) at(i).copyInto(positions, i * 3)
            return DenseSamples(
                count = count,
                positions = positions,
                normals = FloatArray(count * 3) { if (it % 3 == 1) 1f else 0f },
                colors = IntArray(count) { 0xFF000000.toInt() or (it * 7919 and 0xFFFFFF) },
                confidences = ByteArray(count) { (128 + it % 128).toByte() },
            )
        }

        fun key(level: Int, x: Float, y: Float, z: Float, side: Float): Long =
            (level.toLong() shl 60) or ((floor(x / side).toLong() and AXIS_MASK) shl 40) or
                ((floor(y / side).toLong() and AXIS_MASK) shl 20) or (floor(z / side).toLong() and AXIS_MASK)

        /** [cloud]'s merged cells at least [fromM] from [eye]: a surfel stands inside its own. */
        fun cells(cloud: DenseCloud, voxelM: Float, eye: Vec3, fromM: Float): Cells {
            val keys = LongArray(cloud.count)
            val set = LongIntMap()
            var size = 0
            val p = cloud.positions
            for (i in 0 until cloud.count) {
                val x = p[i * 3]
                val y = p[i * 3 + 1]
                val z = p[i * 3 + 2]
                if (distance2(x, y, z, eye) < fromM * fromM) continue
                val level = cloud.levelOf(i)
                val k = key(level, x, y, z, voxelM * (1 shl level))
                keys[size++] = k
                set.put(k, i)
            }
            return Cells(keys.copyOf(size), set)
        }

        /** Of the map's [COVER_CELL_M] cells [COVER_FROM_M] or more from [eye], the share [cloud] shows. */
        fun cover(fusion: DenseFusion, cloud: DenseCloud, eye: Vec3): Double {
            val shown = LongIntMap()
            val c = cloud.positions
            for (i in 0 until cloud.count) shown.put(key(0, c[i * 3], c[i * 3 + 1], c[i * 3 + 2], COVER_CELL_M), 1)
            val map = fusion.cloudPositions(minViews = DenseFusion.MIN_VIEWS, maxPoints = Int.MAX_VALUE)
            val counted = LongIntMap()
            var cells = 0
            var covered = 0
            for (i in 0 until map.size / 3) {
                val x = map[i * 3]
                val y = map[i * 3 + 1]
                val z = map[i * 3 + 2]
                if (distance2(x, y, z, eye) < COVER_FROM_M * COVER_FROM_M) continue
                val k = key(0, x, y, z, COVER_CELL_M)
                if (counted.get(k) >= 0) continue
                counted.put(k, 1)
                cells++
                if (shown.get(k) >= 0) covered++
            }
            return if (cells == 0) 1.0 else covered.toDouble() / cells
        }

        /** Of [cloud]'s surfels, those a merge made: each has a unit normal, an empty slot none. */
        fun made(cloud: DenseCloud): Int {
            val n = checkNotNull(cloud.normals) { "a live cloud has normals" }
            var made = 0
            for (i in 0 until cloud.count) {
                val l = n[i * 3] * n[i * 3] + n[i * 3 + 1] * n[i * 3 + 1] + n[i * 3 + 2] * n[i * 3 + 2]
                if (abs(l - 1f) < UNIT_SLACK) made++
            }
            return made
        }

        fun distance2(x: Float, y: Float, z: Float, eye: Vec3): Float {
            val dx = x - eye.x
            val dy = y - eye.y
            val dz = z - eye.z
            return dx * dx + dy * dy + dz * dz
        }

        /** [cloud] seen from [eye], far surfels first and near ones over them: `0xRRGGBB` pixels. */
        fun draw(cloud: DenseCloud, voxelM: Float, eye: DebugPose): IntArray {
            val w = LiveDrawLoad.VIEW_LONG_PX / SHRINK
            val h = LiveDrawLoad.VIEW_SHORT_PX / SHRINK
            val pixels = IntArray(w * h) { BACKGROUND }
            val splats = ArrayList<FloatArray>()
            LiveDrawLoad.forEachSplat(cloud, voxelM, eye) { i, u, v, depth, area ->
                splats += floatArrayOf(i.toFloat(), u, v, depth, area)
            }
            splats.sortByDescending { it[3] }
            for ((index, u, v, _, area) in splats) {
                val i = index.toInt()
                val half = sqrt(area) / SHRINK / 2f
                val cx = (u + LiveDrawLoad.HALF_LONG) / SHRINK
                val cy = (LiveDrawLoad.HALF_SHORT - v) / SHRINK
                val x0 = (cx - half).roundToInt().coerceAtLeast(0)
                val x1 = maxOf((cx + half).roundToInt(), x0 + 1).coerceAtMost(w)
                val y0 = (cy - half).roundToInt().coerceAtLeast(0)
                val y1 = maxOf((cy + half).roundToInt(), y0 + 1).coerceAtMost(h)
                val c = if (cloud.colors[i] == 0) UNCOLOURED else cloud.colors[i] and RGB
                for (y in y0 until y1) for (x in x0 until x1) pixels[y * w + x] = c
            }
            return pixels
        }

        /** [draw]'s pixels as an 8-bit RGB PNG. */
        fun png(pixels: IntArray): ByteArray {
            val w = LiveDrawLoad.VIEW_LONG_PX / SHRINK
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
            val header = ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(2).put(0).put(0).put(0).array()
            val file = ByteArrayOutputStream()
            file.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
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

        fun List<Double>.mean(): Double = if (isEmpty()) 0.0 else sum() / size

        fun List<Double>.pct(p: Double): Double =
            if (isEmpty()) 0.0 else sorted()[(ceil(p * size).toInt() - 1).coerceIn(0, size - 1)]

        fun pct(v: Double) = "%.1f %%".format(v * 100)
        fun ms(v: Double) = "%.2f".format(v)
    }
}
