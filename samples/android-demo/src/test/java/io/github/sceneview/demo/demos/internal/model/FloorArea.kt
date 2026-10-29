package io.github.sceneview.demo.demos.internal.model

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * A model's floor as the eye reads it: how much of it there is, and how many holes it has.
 *
 * The floor is the faces turned up ([MIN_UP] of their normal along +y) whose centroid lies
 * within [BAND_M] of the floor's height — given, or the height most of the upward area lies at
 * within [SEARCH_M] of the lowest ([height]). Its [area] is theirs projected onto the floor, so a
 * floor rippled by the scan's noise and the same floor made flat weigh the same. Its holes are
 * the loops of its open edges, vertices welded within [WELD_M], less one — the rim around the
 * rest: [innerLoops]. The ones the eye sees through are those with a free edge, no face of the
 * model across it: [visibleHoles]. What it leaves open of another floor: [openedOn].
 */
internal class FloorArea private constructor(
    val height: Float,
    val area: Float,
    val innerLoops: Int,
    /**
     * The loops, the rim aside, with at least one edge no face of the whole model has across it:
     * holes the eye sees through. A loop whose every edge has a face across — a face of the floor
     * the smoothing tipped past [MIN_UP] at the foot of a wall, a crease — is a hole in the
     * selection, not in the model, and is not one of them.
     */
    val visibleHoles: Int,
    private val cells: Set<Long>,
) {
    /**
     * The share of [raw]'s floor this one leaves open: the [COVER_CELL_M] cells [raw]'s floor
     * faces cover and this one's do not — where the eye sees through a floor the scan had.
     */
    fun openedOn(raw: FloorArea): Float = if (raw.cells.isEmpty()) 0f else opened(raw).size.toFloat() / raw.cells.size

    /** The cells of [raw]'s floor this one leaves open, each `x index shl 32 or z index`, [COVER_CELL_M] a side. */
    fun opened(raw: FloorArea): Set<Long> = raw.cells.filterTo(HashSet()) { it !in cells }

    /** How many [COVER_CELL_M] cells this floor covers. */
    val cellCount: Int get() = cells.size

    /** Whether this floor covers [cell] ([cellAt]). */
    fun covers(cell: Long): Boolean = cell in cells

    companion object {
        /** The floor of the triangle soup [positions] / [indices], at [height] when known, [bandM] either side of it. */
        fun of(positions: FloatArray, indices: IntArray, height: Float? = null, bandM: Float = BAND_M): FloorArea {
            val count = indices.size / 3
            val up = FloatArray(count)
            val centreY = FloatArray(count)
            val area = FloatArray(count)
            for (t in 0 until count) {
                val a = indices[t * 3] * 3
                val b = indices[t * 3 + 1] * 3
                val c = indices[t * 3 + 2] * 3
                val ux = positions[b] - positions[a]
                val uy = positions[b + 1] - positions[a + 1]
                val uz = positions[b + 2] - positions[a + 2]
                val vx = positions[c] - positions[a]
                val vy = positions[c + 1] - positions[a + 1]
                val vz = positions[c + 2] - positions[a + 2]
                val nx = uy * vz - uz * vy
                val ny = uz * vx - ux * vz
                val nz = ux * vy - uy * vx
                val length = sqrt(nx * nx + ny * ny + nz * nz)
                if (length <= 0f) continue
                area[t] = length / 2f
                up[t] = ny / length
                centreY[t] = (positions[a + 1] + positions[b + 1] + positions[c + 1]) / 3f
            }
            val floorY = height ?: height(up, centreY, area) ?: return FloorArea(Float.NaN, 0f, 0, 0, emptySet())
            val floor = BooleanArray(count) { up[it] >= MIN_UP && abs(centreY[it] - floorY) <= bandM }
            var projected = 0.0
            for (t in 0 until count) if (floor[t]) projected += area[t] * up[t]
            val loops = loops(positions, indices, floor)
            return FloorArea(floorY, projected.toFloat(), loops[0], loops[1], cells(positions, indices, floor))
        }

        /** The [COVER_CELL_M] cell holding ([x], [z]). */
        fun cellAt(x: Float, z: Float): Long =
            (floor(x / COVER_CELL_M).toLong() shl Int.SIZE_BITS) or (floor(z / COVER_CELL_M).toLong() and LOW_INT)

        /** [cell]'s centre along x, in metres. */
        fun centreX(cell: Long): Float = ((cell shr Int.SIZE_BITS).toInt() + HALF) * COVER_CELL_M

        /** [cell]'s centre along z, in metres. */
        fun centreZ(cell: Long): Float = ((cell and LOW_INT).toInt() + HALF) * COVER_CELL_M

        /** How many cells the largest group of [cells] touching each other (by a side or a corner) holds: the widest hole. */
        fun largestPatch(cells: Set<Long>): Int {
            val seen = HashSet<Long>()
            var largest = 0
            for (start in cells) {
                if (!seen.add(start)) continue
                var size = 0
                val stack = ArrayDeque(listOf(start))
                while (stack.isNotEmpty()) {
                    val cell = stack.removeLast()
                    size++
                    val i = (cell shr Int.SIZE_BITS).toInt()
                    val k = (cell and LOW_INT).toInt()
                    for (di in -1..1) for (dk in -1..1) {
                        val next = ((i + di).toLong() shl Int.SIZE_BITS) or ((k + dk).toLong() and LOW_INT)
                        if (next in cells && seen.add(next)) stack.addLast(next)
                    }
                }
                largest = maxOf(largest, size)
            }
            return largest
        }

        /** The [COVER_CELL_M] cells whose sample point ([SAMPLE_AT]) one of the faces [kept] covers, seen from above. */
        private fun cells(positions: FloatArray, indices: IntArray, kept: BooleanArray): Set<Long> {
            val cells = HashSet<Long>()
            for (t in kept.indices) {
                if (!kept[t]) continue
                val ax = positions[indices[t * 3] * 3]
                val az = positions[indices[t * 3] * 3 + 2]
                val bx = positions[indices[t * 3 + 1] * 3]
                val bz = positions[indices[t * 3 + 1] * 3 + 2]
                val cx = positions[indices[t * 3 + 2] * 3]
                val cz = positions[indices[t * 3 + 2] * 3 + 2]
                val det = (bx - ax) * (cz - az) - (cx - ax) * (bz - az)
                if (det == 0f) continue
                val i0 = floor(minOf(ax, bx, cx) / COVER_CELL_M).toInt()
                val i1 = floor(maxOf(ax, bx, cx) / COVER_CELL_M).toInt()
                val k0 = floor(minOf(az, bz, cz) / COVER_CELL_M).toInt()
                val k1 = floor(maxOf(az, bz, cz) / COVER_CELL_M).toInt()
                for (i in i0..i1) for (k in k0..k1) {
                    val px = (i + SAMPLE_AT) * COVER_CELL_M
                    val pz = (k + SAMPLE_AT) * COVER_CELL_M
                    val u = ((px - ax) * (cz - az) - (cx - ax) * (pz - az)) / det
                    val v = ((bx - ax) * (pz - az) - (px - ax) * (bz - az)) / det
                    if (u >= 0f && v >= 0f && u + v <= 1f) cells += (i.toLong() shl Int.SIZE_BITS) or (k.toLong() and LOW_INT)
                }
            }
            return cells
        }

        /**
         * The height the most upward area lies at, in [BIN_M] bins, within [SEARCH_M] of the
         * lowest bin holding [MIN_LOWEST_SHARE] of it: the floor, not a stray under it nor a
         * table on it. `null` with nothing turned up.
         */
        private fun height(up: FloatArray, centreY: FloatArray, area: FloatArray): Float? {
            val bins = HashMap<Long, Double>()
            var total = 0.0
            for (t in up.indices) {
                if (up[t] < MIN_UP) continue
                val bin = (centreY[t] / BIN_M).roundToLong()
                bins[bin] = (bins[bin] ?: 0.0) + area[t]
                total += area[t]
            }
            if (total <= 0.0) return null
            val sorted = bins.keys.sorted()
            val lowest = sorted.first { bins.getValue(it) >= total * MIN_LOWEST_SHARE }
            val reach = (SEARCH_M / BIN_M).toLong()
            val floorBin = sorted.filter { it in lowest..lowest + reach }.maxByOrNull { bins.getValue(it) } ?: lowest
            return floorBin * BIN_M
        }

        /**
         * The loops of the open edges of the faces [kept], less one: the rim; and how many of
         * them, the rim (the loop of the most edges) aside, have a free edge — one no face of
         * the whole soup has across it.
         */
        @Suppress("CyclomaticComplexMethod")
        private fun loops(positions: FloatArray, indices: IntArray, kept: BooleanArray): IntArray {
            // Welded ids: a model split at its seams has its vertices twice in the same place.
            val weld = HashMap<Triple<Long, Long, Long>, Int>()
            val id = IntArray(positions.size / 3) { -1 }
            fun welded(v: Int): Int {
                if (id[v] >= 0) return id[v]
                val key = Triple(
                    (positions[v * 3] / WELD_M).roundToLong(),
                    (positions[v * 3 + 1] / WELD_M).roundToLong(),
                    (positions[v * 3 + 2] / WELD_M).roundToLong(),
                )
                return weld.getOrPut(key) { weld.size }.also { id[v] = it }
            }
            val uses = HashMap<Long, Int>()
            for (t in kept.indices) {
                if (!kept[t]) continue
                for (k in 0 until 3) {
                    val a = welded(indices[t * 3 + k])
                    val b = welded(indices[t * 3 + (k + 1) % 3])
                    if (a == b) continue
                    val key = (minOf(a, b).toLong() shl Int.SIZE_BITS) or maxOf(a, b).toLong()
                    uses[key] = (uses[key] ?: 0) + 1
                }
            }
            val parent = IntArray(weld.size) { it }
            fun root(v: Int): Int {
                var r = v
                while (parent[r] != r) {
                    parent[r] = parent[parent[r]]
                    r = parent[r]
                }
                return r
            }
            val open = uses.filterValues { it == 1 }.keys
            for (key in open) {
                val a = root((key shr Int.SIZE_BITS).toInt())
                val b = root((key and LOW_INT).toInt())
                if (a != b) parent[a] = b
            }
            // How many faces of the whole soup share each open edge: one, the floor's own, is a free edge.
            val across = HashMap<Long, Int>()
            for (t in kept.indices) {
                if (kept[t]) continue
                for (k in 0 until 3) {
                    val a = onFloor(indices[t * 3 + k], positions, weld, id) ?: continue
                    val b = onFloor(indices[t * 3 + (k + 1) % 3], positions, weld, id) ?: continue
                    if (a == b) continue
                    val key = (minOf(a, b).toLong() shl Int.SIZE_BITS) or maxOf(a, b).toLong()
                    if (key in open) across[key] = (across[key] ?: 0) + 1
                }
            }
            val edges = HashMap<Int, Int>()
            val free = HashSet<Int>()
            for (key in open) {
                val r = root((key shr Int.SIZE_BITS).toInt())
                edges[r] = (edges[r] ?: 0) + 1
                if (key !in across) free += r
            }
            val rim = edges.maxByOrNull { it.value }?.key
            return intArrayOf((edges.size - 1).coerceAtLeast(0), free.count { it != rim })
        }

        /** [v]'s welded id when a vertex of the floor lies where it does, else `null`; caches in [id] (-2: none). */
        private fun onFloor(v: Int, positions: FloatArray, weld: Map<Triple<Long, Long, Long>, Int>, id: IntArray): Int? {
            if (id[v] >= 0) return id[v]
            if (id[v] == NOT_ON_FLOOR) return null
            val key = Triple(
                (positions[v * 3] / WELD_M).roundToLong(),
                (positions[v * 3 + 1] / WELD_M).roundToLong(),
                (positions[v * 3 + 2] / WELD_M).roundToLong(),
            )
            val found = weld[key]
            id[v] = found ?: NOT_ON_FLOOR
            return found
        }

        /** cos 60°: a face of the floor rippled by the scan still faces up. */
        const val MIN_UP = 0.5f
        const val BAND_M = 0.05f
        const val BIN_M = 0.01f
        const val SEARCH_M = 0.5f
        const val MIN_LOWEST_SHARE = 0.01f
        const val WELD_M = 0.001f

        /** Finer than a lace hole of the scan's (about 8 cm), coarser than a face of its floor. */
        const val COVER_CELL_M = 0.04f

        /**
         * Where in its cell a cell is sampled, off the voxel grids (2 and 2.5 cm): the marching
         * cubes put a floor's vertices on that grid, so a sample there lies on the very edge of a
         * hole's rim, and a rim moved a tenth of a millimetre would open it.
         */
        const val SAMPLE_AT = 0.33f
        private const val LOW_INT = 0xFFFFFFFFL
        private const val HALF = 0.5f
        private const val NOT_ON_FLOOR = -2
    }
}
