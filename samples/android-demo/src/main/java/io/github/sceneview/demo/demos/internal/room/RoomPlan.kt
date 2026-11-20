package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.DenseCloud
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * One wall of a [RoomPlan], in its room's frame: along the first axis at `b = [line]` from
 * `a = [from]` to `a = [to]` when [alongA], else along the second axis at `a = [line]` from
 * `b = [from]` to `b = [to]`. [inward] (+1 or −1) is the sign, along the other axis, of the way
 * it faces: into the room.
 */
internal class PlanWall(val alongA: Boolean, val line: Float, val from: Float, val to: Float, val inward: Int) {
    val length: Float get() = to - from

    /** The distance, in the plan, from the room-frame point ([a], [b]) to this wall. */
    fun distance(a: Float, b: Float): Float {
        val along = if (alongA) a else b
        val across = if (alongA) b else a
        val past = when {
            along < from -> from - along
            along > to -> along - to
            else -> 0f
        }
        return hypot(past, across - line)
    }

    override fun toString(): String =
        "${if (alongA) "b" else "a"}=${"%.2f".format(line)} [${"%.2f".format(from)}, ${"%.2f".format(to)}] ${if (inward > 0) "+" else "−"}"
}

/** A box cut into cells at its wall peaks: [cover] the share of each cell (row-major, as [RoomPlan.inside]) the scan shows. */
internal class PlanCells(val cutsA: FloatArray, val cutsB: FloatArray, val cover: FloatArray) {
    override fun toString(): String {
        fun f(x: FloatArray) = x.joinToString(", ", "[", "]") { "%.2f".format(it) }
        return "cuts a ${f(cutsA)}, b ${f(cutsB)}, cover ${f(cover)}"
    }
}

/** A rectangle of a [RoomPlan]'s floor, in its room's frame: `[a0, a1] × [b0, b1]`. */
internal class PlanRect(val a0: Float, val b0: Float, val a1: Float, val b1: Float)

/**
 * A room's footprint as a rectilinear polygon in its [box]'s frame: the box cut along each axis at
 * its strong wall peaks ([cutsA], [cutsB], the box's own walls first and last) into a grid of
 * cells, [inside] or not (row `j` along the second axis, column `i` along the first:
 * `inside[j · columns + i]`). Its [walls] are the edges between a cell inside and one outside or
 * off the grid, merged along their line; its [floor], the inside cells as few rectangles.
 */
internal class RoomPlan(val box: RoomBox, val cutsA: FloatArray, val cutsB: FloatArray, val inside: BooleanArray) {
    val columns: Int get() = cutsA.size - 1
    val rows: Int get() = cutsB.size - 1

    fun isInside(i: Int, j: Int): Boolean = i in 0 until columns && j in 0 until rows && inside[j * columns + i]

    val walls: List<PlanWall> by lazy { wallsOf() }

    val floor: List<PlanRect> by lazy { floorOf() }

    /** Whether the room-frame point ([a], [b]) stands in the room. */
    fun contains(a: Float, b: Float): Boolean {
        val i = interval(cutsA, a)
        val j = interval(cutsB, b)
        return i >= 0 && j >= 0 && inside[j * columns + i]
    }

    /** How far the room-frame point ([a], [b]) stands inside the room from its nearest wall; −1 outside. */
    fun toWall(a: Float, b: Float): Float {
        if (!contains(a, b)) return -1f
        var nearest = Float.POSITIVE_INFINITY
        for (wall in walls) nearest = minOf(nearest, wall.distance(a, b))
        return nearest
    }

    override fun toString(): String =
        "RoomPlan(${columns}×$rows cells, ${inside.count { !it }} out, ${walls.size} walls, ${floor.size} floor rects)"

    private fun wallsOf(): List<PlanWall> {
        val out = ArrayList<PlanWall>()
        // Along a: the edges at b = cutsB[j], between row j − 1 and row j.
        for (j in 0..rows) {
            run(columns, { i -> facing(isInside(i, j), isInside(i, j - 1)) }) { from, to, inward ->
                out += PlanWall(alongA = true, line = cutsB[j], from = cutsA[from], to = cutsA[to], inward = inward)
            }
        }
        // Along b: the edges at a = cutsA[i], between column i − 1 and column i.
        for (i in 0..columns) {
            run(rows, { j -> facing(isInside(i, j), isInside(i - 1, j)) }) { from, to, inward ->
                out += PlanWall(alongA = false, line = cutsA[i], from = cutsB[from], to = cutsB[to], inward = inward)
            }
        }
        return out
    }

    /** +1 when only the cell on the high side is inside, −1 when only the low one, 0 when no wall. */
    private fun facing(high: Boolean, low: Boolean): Int = when {
        high && !low -> 1
        low && !high -> -1
        else -> 0
    }

    /** The runs of equal non-zero [kind] over `0 until count`, each as `[from, to)` in cut indices. */
    private inline fun run(count: Int, kind: (Int) -> Int, emit: (Int, Int, Int) -> Unit) {
        var k = 0
        while (k < count) {
            val first = kind(k)
            var end = k + 1
            while (end < count && kind(end) == first) end++
            if (first != 0) emit(k, end, first)
            k = end
        }
    }

    /** The inside cells as rectangles: each row's runs, grown over the rows above while they match. */
    private fun floorOf(): List<PlanRect> {
        val taken = BooleanArray(inside.size)
        val out = ArrayList<PlanRect>()
        for (j in 0 until rows) {
            var i = 0
            while (i < columns) {
                if (!isInside(i, j) || taken[j * columns + i]) {
                    i++
                    continue
                }
                var i1 = i + 1
                while (i1 < columns && isInside(i1, j) && !taken[j * columns + i1]) i1++
                var j1 = j + 1
                while (j1 < rows && (i until i1).all { isInside(it, j1) && !taken[j1 * columns + it] }) j1++
                for (jj in j until j1) for (ii in i until i1) taken[jj * columns + ii] = true
                out += PlanRect(cutsA[i], cutsB[j], cutsA[i1], cutsB[j1])
                i = i1
            }
        }
        return out
    }

    companion object {
        /**
         * The footprint of [cloud] within [box] — [cameras] the scan's camera positions, xyz — or
         * `null` when it is the box itself: no strong wall peak inside it, or no cell of the grid
         * those peaks cut that the scan shows to be outside the room.
         *
         * 1. The cuts: along each axis, every local peak of the wall histogram ([RoomBox.wallHistogram])
         *    over [RoomBox.PEAK_SHARE] of its highest, the stronger of two closer than [MERGE_M]
         *    kept, between the box's own walls by [MERGE_M] or more.
         * 2. The evidence: a [GRID_M] grid over the box, a cell taken when a cloud point under
         *    [EVIDENCE_HEIGHT_M] over the floor or a camera falls in it, closed over
         *    [CLOSING_STEPS] cells and its holes filled.
         * 3. A cell is out of the room only when it is [MIN_OUT_SIDE_M] or more on both sides, less
         *    than [MAX_OUT_COVER] of it taken, and joined to the box's edge through such cells:
         *    anything in doubt stays in, so a doubtful room keeps its box. When the cells left
         *    inside do not hold together, the box too.
         */
        @Suppress("ReturnCount")
        fun of(cloud: DenseCloud, box: RoomBox, cameras: FloatArray): RoomPlan? {
            val cells = cells(cloud, box, cameras) ?: return null
            val cutsA = cells.cutsA
            val cutsB = cells.cutsB
            val columns = cutsA.size - 1
            val rows = cutsB.size - 1
            val candidate = BooleanArray(columns * rows)
            for (j in 0 until rows) for (i in 0 until columns) {
                val wide = cutsA[i + 1] - cutsA[i] >= MIN_OUT_SIDE_M && cutsB[j + 1] - cutsB[j] >= MIN_OUT_SIDE_M
                candidate[j * columns + i] = wide && cells.cover[j * columns + i] < MAX_OUT_COVER
            }
            val out = reachedFromEdge(candidate, columns, rows)
            if (out.none { it }) return null
            val inside = BooleanArray(out.size) { !out[it] }
            if (!connected(inside, columns, rows)) return null
            return RoomPlan(box, cutsA, cutsB, inside)
        }

        /**
         * The grid [of] weighs: the cuts along each axis and each cell's share seen. `null` when
         * no strong peak stands inside the box.
         */
        fun cells(cloud: DenseCloud, box: RoomBox, cameras: FloatArray): PlanCells? {
            val cutsA = cuts(cloud, box, alongA = true) ?: return null
            val cutsB = cuts(cloud, box, alongA = false) ?: return null
            if (cutsA.size == 2 && cutsB.size == 2) return null
            val evidence = Evidence.of(cloud, box, cameras)
            val columns = cutsA.size - 1
            val cover = FloatArray(columns * (cutsB.size - 1)) { at ->
                val i = at % columns
                val j = at / columns
                evidence.cover(cutsA[i], cutsA[i + 1], cutsB[j], cutsB[j + 1])
            }
            return PlanCells(cutsA, cutsB, cover)
        }

        /**
         * The cuts along one axis: the box's walls and every strong peak between them. `null` when
         * the histogram is not there (it is when the box is).
         */
        fun cuts(cloud: DenseCloud, box: RoomBox, alongA: Boolean): FloatArray? {
            val normals = cloud.normals ?: return null
            val n = cloud.count
            val p = cloud.positions
            val c = cos(box.yaw)
            val s = sin(box.yaw)
            val q = FloatArray(n) {
                if (alongA) p[it * 3] * c + p[it * 3 + 2] * s else -p[it * 3] * s + p[it * 3 + 2] * c
            }
            val nq = FloatArray(n) {
                if (alongA) normals[it * 3] * c + normals[it * 3 + 2] * s else -normals[it * 3] * s + normals[it * 3 + 2] * c
            }
            val histogram = RoomBox.wallHistogram(q, nq, p, box.floorY) ?: return null
            val lo = if (alongA) box.a0 else box.b0
            val hi = if (alongA) box.a1 else box.b1
            val inner = peaks(histogram).filter { it >= lo + MERGE_M && it <= hi - MERGE_M }
            return (listOf(lo) + inner + listOf(hi)).toFloatArray()
        }

        /** The histogram's local peaks over [RoomBox.PEAK_SHARE] of its highest, the stronger of two closer than [MERGE_M]. */
        fun peaks(histogram: WallHistogram): List<Float> {
            val h = histogram.smooth
            val threshold = RoomBox.PEAK_SHARE * h.max()
            val kept = ArrayList<Int>()
            for (k in 1 until h.size - 1) {
                if (h[k] <= threshold || h[k] < h[k - 1] || h[k] < h[k + 1]) continue
                val last = kept.lastOrNull()
                if (last != null && histogram.at(k) - histogram.at(last) < MERGE_M) {
                    if (h[k] > h[last]) kept[kept.size - 1] = k
                } else {
                    kept += k
                }
            }
            return kept.map(histogram::at)
        }

        /** The [candidate] cells joined to the grid's edge through candidate cells (4-connected). */
        private fun reachedFromEdge(candidate: BooleanArray, columns: Int, rows: Int): BooleanArray {
            val reached = BooleanArray(candidate.size)
            val stack = ArrayDeque<Int>()
            for (j in 0 until rows) for (i in 0 until columns) {
                val edge = i == 0 || j == 0 || i == columns - 1 || j == rows - 1
                if (edge && candidate[j * columns + i]) {
                    reached[j * columns + i] = true
                    stack.addLast(j * columns + i)
                }
            }
            flood(stack, reached, columns, rows) { candidate[it] }
            return reached
        }

        /** Whether the [inside] cells form one 4-connected piece. */
        private fun connected(inside: BooleanArray, columns: Int, rows: Int): Boolean {
            val start = inside.indexOfFirst { it }
            if (start < 0) return false
            val reached = BooleanArray(inside.size).also { it[start] = true }
            flood(ArrayDeque(listOf(start)), reached, columns, rows) { inside[it] }
            return inside.indices.all { !inside[it] || reached[it] }
        }

        private inline fun flood(stack: ArrayDeque<Int>, reached: BooleanArray, columns: Int, rows: Int, open: (Int) -> Boolean) {
            while (stack.isNotEmpty()) {
                val at = stack.removeLast()
                val i = at % columns
                val j = at / columns
                for ((di, dj) in NEIGHBOURS) {
                    val ni = i + di
                    val nj = j + dj
                    if (ni !in 0 until columns || nj !in 0 until rows) continue
                    val next = nj * columns + ni
                    if (reached[next] || !open(next)) continue
                    reached[next] = true
                    stack.addLast(next)
                }
            }
        }

        /** The finest cell of the evidence grid. */
        const val GRID_M = 0.05f

        /** A point counts as the room's floor seen, or furniture standing on it, under this over the floor. */
        const val EVIDENCE_HEIGHT_M = 2f

        /** How many cells the evidence is closed over (dilated, then eroded). */
        const val CLOSING_STEPS = 4

        /** Two wall peaks closer than this are one. */
        const val MERGE_M = 0.25f

        /** A cell narrower than this on either side never leaves the room: a strip, a recess, a wardrobe's depth. */
        const val MIN_OUT_SIDE_M = 0.6f

        /** A cell with this share of its area seen or more is in the room. */
        const val MAX_OUT_COVER = 0.15f

        private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}

/**
 * Where a scan shows a room's floor, on a [RoomPlan.GRID_M] grid over its box, `cells[z · nx + x]`:
 * the cells a low point or a camera fell in, closed and with their holes filled.
 */
private class Evidence(val box: RoomBox, val nx: Int, val nz: Int, val cells: BooleanArray) {
    /** The share of the cells whose centres lie in `[a0, a1) × [b0, b1)` that are taken. */
    fun cover(a0: Float, a1: Float, b0: Float, b1: Float): Float {
        var taken = 0
        var all = 0
        for (z in 0 until nz) {
            val b = box.b0 + (z + HALF) * RoomPlan.GRID_M
            if (b < b0 || b >= b1) continue
            for (x in 0 until nx) {
                val a = box.a0 + (x + HALF) * RoomPlan.GRID_M
                if (a < a0 || a >= a1) continue
                all++
                if (cells[z * nx + x]) taken++
            }
        }
        return if (all == 0) 1f else taken.toFloat() / all
    }

    companion object {
        fun of(cloud: DenseCloud, box: RoomBox, cameras: FloatArray): Evidence {
            val r = RoomPlan.GRID_M
            val nx = max(1, ceil((box.a1 - box.a0) / r).toInt())
            val nz = max(1, ceil((box.b1 - box.b0) / r).toInt())
            // Padded so the closing does not eat the grid's own edge.
            val pad = RoomPlan.CLOSING_STEPS + 1
            val w = nx + 2 * pad
            val h = nz + 2 * pad
            var grid = BooleanArray(w * h)
            fun mark(x: Float, z: Float) {
                val gx = floor((box.a(x, z) - box.a0) / r).toInt()
                val gz = floor((box.b(x, z) - box.b0) / r).toInt()
                if (gx in 0 until nx && gz in 0 until nz) grid[(gz + pad) * w + gx + pad] = true
            }
            val p = cloud.positions
            val top = box.floorY + RoomPlan.EVIDENCE_HEIGHT_M
            for (i in 0 until cloud.count) if (p[i * 3 + 1] < top) mark(p[i * 3], p[i * 3 + 2])
            for (k in 0 until cameras.size / 3) mark(cameras[k * 3], cameras[k * 3 + 2])
            repeat(RoomPlan.CLOSING_STEPS) { grid = step(grid, w, h, grow = true) }
            repeat(RoomPlan.CLOSING_STEPS) { grid = step(grid, w, h, grow = false) }
            grid = filled(grid, w, h)
            val cells = BooleanArray(nx * nz) { grid[(it / nx + pad) * w + it % nx + pad] }
            return Evidence(box, nx, nz, cells)
        }

        /** One dilation ([grow]) or erosion of [g] by the 4-neighbour cross; off the grid is empty. */
        private fun step(g: BooleanArray, w: Int, h: Int, grow: Boolean): BooleanArray = BooleanArray(g.size) { at ->
            val x = at % w
            val y = at / w
            fun get(nx: Int, ny: Int) = nx in 0 until w && ny in 0 until h && g[ny * w + nx]
            val around = listOf(get(x, y), get(x - 1, y), get(x + 1, y), get(x, y - 1), get(x, y + 1))
            if (grow) around.any { it } else around.all { it }
        }

        /** [g] with every empty region the grid's edge cannot reach (4-connected) filled. */
        private fun filled(g: BooleanArray, w: Int, h: Int): BooleanArray {
            val outside = BooleanArray(g.size)
            val stack = ArrayDeque<Int>()
            for (y in 0 until h) for (x in 0 until w) {
                if ((x == 0 || y == 0 || x == w - 1 || y == h - 1) && !g[y * w + x]) {
                    outside[y * w + x] = true
                    stack.addLast(y * w + x)
                }
            }
            while (stack.isNotEmpty()) {
                val at = stack.removeLast()
                val x = at % w
                val y = at / w
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val next = ny * w + nx
                    if (outside[next] || g[next]) continue
                    outside[next] = true
                    stack.addLast(next)
                }
            }
            return BooleanArray(g.size) { !outside[it] }
        }

        private const val HALF = 0.5f
    }
}

/** The index `k` of the interval `[cuts[k], cuts[k + 1])` that holds [x] (the last one closed), −1 off them. */
private fun interval(cuts: FloatArray, x: Float): Int {
    if (x < cuts.first() || x > cuts.last()) return -1
    for (k in 0 until cuts.size - 1) if (x < cuts[k + 1]) return k
    return cuts.size - 2
}
