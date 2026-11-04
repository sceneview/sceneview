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
    fun of(
        covered: LongArray,
        cellM: Float,
        extent: List<Pair<Float, Float>>,
        floorY: Float,
        weak: LongArray = LongArray(0),
    ): ScanGround? {
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
        for (key in weak) {
            val c = CoverageGrid.cxOf(key) - minC
            val r = CoverageGrid.czOf(key) - minR
            if (c !in 0 until columns || r !in 0 until rows) continue
            if (cells[r * columns + c] == ScanGround.SEEN) cells[r * columns + c] = ScanGround.WEAK
        }
        return ScanGround.ofCells(floorY, cellM, minC * cellM, minR * cellM, columns, rows, cells)
    }

    /**
     * The [covered] cells (keys of [CoverageGrid.key], [cellM] on a side) whose floor the dense
     * [cloud] barely holds: the camera passed there, but the floor did not stick — a cell to scan
     * again. A cell is judged on its floor alone, as the share of its area the surfels of the floor
     * band ([floorOf] near [floorY], from [FLOOR_BELOW_M] under it to [FLOOR_BAND_M] over it)
     * cover seen from above, each `voxelM · 2^level` wide ([shares]): the live cloud merges far
     * surfels into coarser ones, so a point count read distance, not coverage, and the walls'
     * points over a cell counted as its floor. Weak under [WEAK_SHARE]. Never judged: a cell on the scan's edge (an
     * uncovered 4-neighbour: a wall's foot, the frontier), and a cell something stands in (more
     * than [OCCUPIED_SHARE] of its area of surfels between the band and [STANDING_M]) — the floor
     * under a sofa is not there to scan. Nothing is weak before [MIN_JUDGED_CELLS] cells are covered.
     */
    fun weakCells(cloud: DenseCloud, voxelM: Float, covered: LongArray, cellM: Float, floorY: Float): LongArray {
        if (covered.size < MIN_JUDGED_CELLS || cellM <= 0f || voxelM <= 0f || cloud.count == 0) return LongArray(0)
        val shares = shares(cloud, voxelM, covered, cellM, floorOf(cloud, voxelM, floorY))
        val coveredSet = HashSet<Long>(covered.size * 2).apply { covered.forEach { add(it) } }
        return covered.filterIndexed { k, key ->
            val cx = CoverageGrid.cxOf(key)
            val cz = CoverageGrid.czOf(key)
            val inner = CoverageGrid.key(cx - 1, cz) in coveredSet && CoverageGrid.key(cx + 1, cz) in coveredSet &&
                CoverageGrid.key(cx, cz - 1) in coveredSet && CoverageGrid.key(cx, cz + 1) in coveredSet
            inner && shares.standing[k] <= OCCUPIED_SHARE && shares.floor[k] < WEAK_SHARE
        }.toLongArray()
    }

    /**
     * Each of [covered]'s cells, by index: the share of its area the floor band's surfels cover
     * seen from above ([floor], `0..1`: a surfel `voxelM · 2^level` wide covers its footprint
     * once, however many layers of depth noise stack it), and the area of what stands in it over
     * its own ([standing]).
     */
    class Shares(val floor: FloatArray, val standing: FloatArray)

    /** [Shares] of [covered] in [cloud], the floor at [floorY] (see [weakCells]). */
    fun shares(cloud: DenseCloud, voxelM: Float, covered: LongArray, cellM: Float, floorY: Float): Shares {
        val index = HashMap<Long, Int>(covered.size * 2)
        covered.forEachIndexed { i, key -> index[key] = i }
        val floorShare = FloatArray(covered.size)
        val standing = FloatArray(covered.size)
        val footprint = HashSet<Long>()
        val cellArea = cellM * cellM
        val p = cloud.positions
        for (i in 0 until cloud.count) {
            val x = p[i * 3]
            val z = p[i * 3 + 2]
            val above = p[i * 3 + 1] - floorY
            if (above < -FLOOR_BELOW_M || above > STANDING_M) continue
            val span = 1 shl cloud.levelOf(i)
            if (above > FLOOR_BAND_M) {
                val k = index[CoverageGrid.key(floor(x / cellM).toInt(), floor(z / cellM).toInt())] ?: continue
                val side = voxelM * span
                standing[k] += side * side / cellArea
                continue
            }
            // The surfel's footprint, in voxel squares: one for a full-detail surfel.
            val sx = floor(x / voxelM).toInt() - span / 2
            val sz = floor(z / voxelM).toInt() - span / 2
            for (a in 0 until span) for (b in 0 until span) footprint += CoverageGrid.key(sx + a, sz + b)
        }
        val square = voxelM * voxelM / cellArea
        for (key in footprint) {
            val cx = floor((CoverageGrid.cxOf(key) + HALF) * voxelM / cellM).toInt()
            val cz = floor((CoverageGrid.czOf(key) + HALF) * voxelM / cellM).toInt()
            val k = index[CoverageGrid.key(cx, cz)] ?: continue
            floorShare[k] += square
        }
        return Shares(floorShare, standing)
    }

    /**
     * The floor's height in [cloud]: the height within [FLOOR_SEARCH_M] of [hint] the most surfel
     * area lies at, in [voxelM] steps — the floor is the widest flat thing near where it is
     * thought to be. [hint] itself when nothing lies near it. The live map's floor height is a
     * floor plane's, or a guess from the phone's height without one.
     */
    fun floorOf(cloud: DenseCloud, voxelM: Float, hint: Float): Float {
        val bins = (2 * FLOOR_SEARCH_M / voxelM).toInt() + 1
        val area = FloatArray(bins)
        val p = cloud.positions
        for (i in 0 until cloud.count) {
            val b = floor((p[i * 3 + 1] - hint + FLOOR_SEARCH_M) / voxelM).toInt()
            if (b !in 0 until bins) continue
            val side = voxelM * (1 shl cloud.levelOf(i))
            area[b] += side * side
        }
        val best = area.indices.maxByOrNull { area[it] }?.takeIf { area[it] > 0f } ?: return hint
        return hint - FLOOR_SEARCH_M + (best + HALF) * voxelM
    }

    /** Whether the live map paints [weakCells] in soft red, "to redo". */
    const val SHOW_WEAK_CELLS = true

    /**
     * A judged cell whose floor surfels stand for less than this share of its area is weak. Under
     * one merged surfel's share: the live cloud merges the floor 4 m away into 16 cm surfels, a
     * 25 cm cell holds at least one, 0.41 of it, and a fully seen far cell is never weak.
     */
    const val WEAK_SHARE = 0.35f

    /**
     * The floor band over the floor's height, in metres: depth noise and a rug's or a skirting
     * board's height, under a chair's seat or a bed's frame.
     */
    const val FLOOR_BAND_M = 0.15f

    /** The floor band under the floor's height, in metres: depth noise. */
    const val FLOOR_BELOW_M = 0.05f

    /** Up to this height over the floor, a surfel is something standing on it; the ceiling is not. */
    const val STANDING_M = 2f

    /** A cell with more than this share of its area in standing surfels has something in it. */
    const val OCCUPIED_SHARE = 0.1f

    /** How far from the floor height it is given [floorOf] looks for the floor, in metres. */
    const val FLOOR_SEARCH_M = 0.5f

    /** Covered cells before any is judged weak. */
    const val MIN_JUDGED_CELLS = 4

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
