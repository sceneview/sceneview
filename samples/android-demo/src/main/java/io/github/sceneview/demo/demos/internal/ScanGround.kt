package io.github.sceneview.demo.demos.internal

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * The ground a scan's result stands on (#4719), read off the model's own vertices: the floor's
 * height, the footprint of what was scanned on it, and which of that footprint's cells the scan
 * saw the floor in — the "map to complete" the owner liked in the dollhouse, where a slab under
 * the cloud shows at a glance what is missing.
 *
 * - **Floor**: the height most upward-facing vertices sit at, in the lower part of the model —
 *   the floor of a room, the lawn of a garden, the table under an object. A model with no
 *   upward surface stands on its lowest point.
 * - **Footprint**: every [cellM] square of the floor that anything of the model stands over,
 *   closed by [CLOSE_CELLS] and with its holes filled — the area the scan covers, gaps included.
 * - **Seen**: a footprint cell with floor in it, or with something standing on the floor (a wall
 *   foot, a sofa, a rock): there the scan has the ground. Every other footprint cell is
 *   **missing** — a hole in the floor the slab would otherwise hide.
 *
 * Pure Kotlin, off the main thread; [ScanGroundMeshes] turns it into geometry.
 */
class ScanGround private constructor(
    /** The floor's height, metres. */
    val floorY: Float,
    /** The side of a cell, metres. */
    val cellM: Float,
    /** The grid's origin cell: cell (0, 0) spans `[originX, originX + cellM)` on X, same on Z. */
    val originX: Float,
    val originZ: Float,
    val columns: Int,
    val rows: Int,
    /** One byte per cell, row-major (z then x): [OUTSIDE], [SEEN], [MISSING] or [WEAK]. */
    private val cells: ByteArray,
) {
    fun at(column: Int, row: Int): Byte =
        if (column !in 0 until columns || row !in 0 until rows) OUTSIDE else cells[row * columns + column]

    /** Footprint cells the scan has the ground in, the [WEAK] ones among them. */
    val seen: Int = cells.count { it == SEEN || it == WEAK }

    /** Seen cells too thin to keep, on the live map: worth scanning again. */
    val weak: Int = cells.count { it == WEAK }

    /** Footprint cells it does not: the holes to fill. */
    val missing: Int = cells.count { it == MISSING }

    /** Share of the footprint seen, `0..1`; `0` for an empty footprint. */
    val seenShare: Float get() = if (seen + missing == 0) 0f else seen.toFloat() / (seen + missing)

    /** The footprint's centre on the floor, metres: where a tour aims. */
    fun centre(): FloatArray {
        var sx = 0.0
        var sz = 0.0
        var n = 0
        for (r in 0 until rows) for (c in 0 until columns) {
            if (cells[r * columns + c] == OUTSIDE) continue
            sx += originX + (c + HALF) * cellM
            sz += originZ + (r + HALF) * cellM
            n++
        }
        if (n == 0) return floatArrayOf(originX + columns * cellM / 2f, floorY, originZ + rows * cellM / 2f)
        return floatArrayOf((sx / n).toFloat(), floorY, (sz / n).toFloat())
    }

    companion object {
        const val OUTSIDE: Byte = 0
        const val SEEN: Byte = 1
        const val MISSING: Byte = 2

        /** A seen cell with too few points to keep (live map only, [LiveCoverageGround.weakCells]). */
        const val WEAK: Byte = 3

        /** A cell for a room: 10 cm, about a floor tile — fine enough to show a missed corner. */
        const val ROOM_CELL_M = 0.1f

        /** The most cells along the footprint's longer side: a garden still fits a few thousand. */
        const val MAX_CELLS_PER_SIDE = 160

        /** The finest cell, for an object on a table. */
        const val MIN_CELL_M = 0.02f

        /** A vertex this upright (normal's Y) is floor, if it sits at the floor's height. */
        const val UP_NORMAL = 0.8f

        /** How far a floor vertex may sit off the floor's height: the depth's own scatter. */
        const val FLOOR_BAND_M = 0.08f

        /** What stands this low over the floor stands on it: a wall's foot, a sofa's base. */
        const val STANDING_M = 0.25f

        /** The floor is looked for in the lower share of the model's height. */
        const val FLOOR_SEARCH_SHARE = 0.45f

        /** Floor heights are binned this fine to find the floor. */
        const val FLOOR_BIN_M = 0.02f

        /** The footprint is closed by this many cells, so a scan's scatter reads as one area. */
        const val CLOSE_CELLS = 2

        private const val HALF = 0.5f

        /**
         * A ground laid out by its caller, [cells] row-major (z then x), one of [OUTSIDE], [SEEN] or
         * [MISSING] each: the live 3D map's floor, from the coverage grid.
         */
        @Suppress("LongParameterList")
        fun ofCells(
            floorY: Float,
            cellM: Float,
            originX: Float,
            originZ: Float,
            columns: Int,
            rows: Int,
            cells: ByteArray,
        ): ScanGround {
            require(cells.size == columns * rows) { "cells: ${cells.size} for $columns x $rows" }
            return ScanGround(floorY, cellM, originX, originZ, columns, rows, cells)
        }

        /**
         * The ground under the [count] vertices of [positions] (xyz triples), their [normals]
         * (unit, xyz triples; `null` when there are none — no floor is found then, the model
         * stands on its lowest point). `null` when there is nothing to stand.
         */
        @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
        fun of(positions: FloatArray, normals: FloatArray?, count: Int = positions.size / 3): ScanGround? {
            val n = count.coerceIn(0, positions.size / 3)
            if (n == 0) return null
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var minZ = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            var maxZ = Float.NEGATIVE_INFINITY
            for (i in 0 until n) {
                val x = positions[i * 3]
                val y = positions[i * 3 + 1]
                val z = positions[i * 3 + 2]
                if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                minZ = minOf(minZ, z)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
                maxZ = maxOf(maxZ, z)
            }
            if (!minX.isFinite()) return null
            val floorY = floorOf(positions, normals, n, minY, maxY)
            val side = max(maxX - minX, maxZ - minZ)
            val cellM = max(max(ROOM_CELL_M.coerceAtMost(side / 24f), MIN_CELL_M), side / MAX_CELLS_PER_SIDE)
            // A rim of empty cells all round, so closing and the outside flood never reach an edge.
            val rim = CLOSE_CELLS + 1
            val originX = floor(minX / cellM) * cellM - rim * cellM
            val originZ = floor(minZ / cellM) * cellM - rim * cellM
            val columns = ceil((maxX - originX) / cellM).toInt() + rim + 1
            val rows = ceil((maxZ - originZ) / cellM).toInt() + rim + 1
            val occupied = BooleanArray(columns * rows)
            val ground = BooleanArray(columns * rows)
            for (i in 0 until n) {
                val x = positions[i * 3]
                val y = positions[i * 3 + 1]
                val z = positions[i * 3 + 2]
                if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
                val c = ((x - originX) / cellM).toInt().coerceIn(0, columns - 1)
                val r = ((z - originZ) / cellM).toInt().coerceIn(0, rows - 1)
                val at = r * columns + c
                occupied[at] = true
                val ny = normals?.getOrNull(i * 3 + 1) ?: 0f
                val onFloor = ny >= UP_NORMAL && kotlin.math.abs(y - floorY) <= FLOOR_BAND_M
                val standing = y >= floorY - FLOOR_BAND_M && y <= floorY + STANDING_M
                if (onFloor || standing) ground[at] = true
            }
            val footprint = filled(closed(occupied, columns, rows), columns, rows)
            val cells = ByteArray(columns * rows) { at ->
                when {
                    !footprint[at] -> OUTSIDE
                    ground[at] -> SEEN
                    else -> MISSING
                }
            }
            return ScanGround(floorY, cellM, originX, originZ, columns, rows, cells)
        }

        /** The floor's height: the most crowded height bin of upward vertices in the model's lower part. */
        private fun floorOf(positions: FloatArray, normals: FloatArray?, n: Int, minY: Float, maxY: Float): Float {
            normals ?: return minY
            val top = minY + (maxY - minY) * FLOOR_SEARCH_SHARE
            val bins = IntArray(((top - minY) / FLOOR_BIN_M).toInt() + 2)
            for (i in 0 until n) {
                val y = positions[i * 3 + 1]
                if (!y.isFinite() || y > top) continue
                if ((normals.getOrNull(i * 3 + 1) ?: 0f) < UP_NORMAL) continue
                bins[((y - minY) / FLOOR_BIN_M).toInt().coerceIn(0, bins.size - 1)]++
            }
            var best = -1
            for (b in bins.indices) if (bins[b] > 0 && (best < 0 || bins[b] > bins[best])) best = b
            if (best < 0) return minY
            // The bin's own vertices, averaged with its neighbours', weighted by their counts.
            var sum = 0.0
            var weight = 0
            for (b in maxOf(0, best - 1)..minOf(bins.size - 1, best + 1)) {
                sum += (minY + (b + HALF) * FLOOR_BIN_M) * bins[b]
                weight += bins[b]
            }
            return (sum / weight).toFloat()
        }

        /** Dilate then erode by [CLOSE_CELLS] (square neighbourhood): the scan's scatter as one area. */
        private fun closed(cells: BooleanArray, columns: Int, rows: Int): BooleanArray =
            morph(morph(cells, columns, rows, grow = true), columns, rows, grow = false)

        private fun morph(cells: BooleanArray, columns: Int, rows: Int, grow: Boolean): BooleanArray {
            val k = CLOSE_CELLS
            // Separable: rows, then columns.
            val pass = BooleanArray(cells.size)
            for (r in 0 until rows) for (c in 0 until columns) {
                var v = !grow
                for (d in -k..k) {
                    val cc = c + d
                    val cell = cc in 0 until columns && cells[r * columns + cc]
                    if (grow && cell) { v = true; break }
                    if (!grow && !cell) { v = false; break }
                }
                pass[r * columns + c] = v
            }
            val out = BooleanArray(cells.size)
            for (r in 0 until rows) for (c in 0 until columns) {
                var v = !grow
                for (d in -k..k) {
                    val rr = r + d
                    val cell = rr in 0 until rows && pass[rr * columns + c]
                    if (grow && cell) { v = true; break }
                    if (!grow && !cell) { v = false; break }
                }
                out[r * columns + c] = v
            }
            return out
        }

        /** [cells] with every hole filled: what the outside cannot reach is inside. */
        private fun filled(cells: BooleanArray, columns: Int, rows: Int): BooleanArray {
            val outside = BooleanArray(cells.size)
            val stack = IntArray(cells.size)
            var top = 0
            fun push(at: Int) {
                if (!cells[at] && !outside[at]) {
                    outside[at] = true
                    stack[top++] = at
                }
            }
            for (c in 0 until columns) {
                push(c)
                push((rows - 1) * columns + c)
            }
            for (r in 0 until rows) {
                push(r * columns)
                push(r * columns + columns - 1)
            }
            while (top > 0) {
                val at = stack[--top]
                val r = at / columns
                val c = at % columns
                if (c > 0) push(at - 1)
                if (c < columns - 1) push(at + 1)
                if (r > 0) push(at - columns)
                if (r < rows - 1) push(at + columns)
            }
            return BooleanArray(cells.size) { !outside[it] }
        }
    }
}
