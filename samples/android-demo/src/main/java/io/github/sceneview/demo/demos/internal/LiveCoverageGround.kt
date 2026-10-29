package io.github.sceneview.demo.demos.internal

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The floor of the live 3D map: the coverage grid's cells as a [ScanGround], so the map draws it
 * the way the result's ground is drawn — a slab under the room's floor, its missing tiles hatched.
 * A covered cell is [ScanGround.SEEN]; a cell whose centre lies inside the room's estimated floor
 * ([CoverageGrid.floorExtent]) and is not covered is [ScanGround.MISSING], still to scan; every
 * other cell is outside.
 *
 * Pure Kotlin, off the main thread: a room of 10 × 10 m is 1,600 cells of 25 cm.
 */
internal object LiveCoverageGround {

    /** The most cells along a side: a hall still builds in a few milliseconds. */
    const val MAX_CELLS_PER_SIDE = 200

    /**
     * The ground of [covered] cells (keys of [CoverageGrid.key], [cellM] on a side) and of the
     * floor [extent] ((x, z), any winding), at [floorY]. `null` with nothing covered and no extent.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun of(covered: LongArray, cellM: Float, extent: List<Pair<Float, Float>>, floorY: Float): ScanGround? {
        val polygon = extent.takeIf { it.size >= MIN_POLYGON }
        if (covered.isEmpty() && polygon == null) return null
        var minC = Int.MAX_VALUE
        var maxC = Int.MIN_VALUE
        var minR = Int.MAX_VALUE
        var maxR = Int.MIN_VALUE
        for (key in covered) {
            val c = CoverageGrid.cxOf(key)
            val r = CoverageGrid.czOf(key)
            minC = minOf(minC, c)
            maxC = maxOf(maxC, c)
            minR = minOf(minR, r)
            maxR = maxOf(maxR, r)
        }
        polygon?.forEach { (x, z) ->
            minC = minOf(minC, floor(x / cellM).toInt())
            maxC = maxOf(maxC, ceil(x / cellM).toInt() - 1)
            minR = minOf(minR, floor(z / cellM).toInt())
            maxR = maxOf(maxR, ceil(z / cellM).toInt() - 1)
        }
        if (minC > maxC || minR > maxR) return null
        // A runaway extent (a stray floor plane) is cut, rather than a hall of empty cells built.
        maxC = minOf(maxC, minC + MAX_CELLS_PER_SIDE - 1)
        maxR = minOf(maxR, minR + MAX_CELLS_PER_SIDE - 1)
        val columns = maxC - minC + 1
        val rows = maxR - minR + 1
        val cells = ByteArray(columns * rows)
        if (polygon != null) {
            for (r in 0 until rows) {
                val z = (minR + r + HALF) * cellM
                for (c in 0 until columns) {
                    val x = (minC + c + HALF) * cellM
                    if (contains(polygon, x, z)) cells[r * columns + c] = ScanGround.MISSING
                }
            }
        }
        for (key in covered) {
            val c = CoverageGrid.cxOf(key) - minC
            val r = CoverageGrid.czOf(key) - minR
            if (c in 0 until columns && r in 0 until rows) cells[r * columns + c] = ScanGround.SEEN
        }
        return ScanGround.ofCells(floorY, cellM, minC * cellM, minR * cellM, columns, rows, cells)
    }

    /** Whether (x, z) lies inside [polygon], by the even-odd rule. */
    fun contains(polygon: List<Pair<Float, Float>>, x: Float, z: Float): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val (xi, zi) = polygon[i]
            val (xj, zj) = polygon[j]
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private const val MIN_POLYGON = 3
    private const val HALF = 0.5f
}
