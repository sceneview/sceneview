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
     * again. A cell is judged on its floor alone ([shares]): the share of its area the floor
     * band's surfels cover seen from above, each `voxelM · 2^level` wide — the live cloud merges
     * far surfels into coarser ones, so a point count read distance, not coverage, and the walls'
     * points over a cell counted as its floor. Weak under [WEAK_SHARE].
     *
     * Never judged: a cell on the scan's edge (an uncovered 4-neighbour: a wall's foot, the
     * frontier); a cell something stands in (more than [OCCUPIED_SHARE] of its area in surfels
     * between its floor band and [STANDING_M]) — the floor under a sofa is not there to scan; a
     * cell whose floor is merged coarser than [MAX_JUDGED_LEVEL]; and, in a live snapshot past its budget, a cell not wholly within [intactM] of the [eye]
     * the snapshot was taken from ([DenseFusion.liveIntactM]): farther out it dropped surfels at
     * random, and a floor with holes cut in it is not a floor swept too fast. Nothing is weak
     * before [MIN_JUDGED_CELLS] cells are covered.
     */
    @Suppress("LongParameterList")
    fun weakCells(
        cloud: DenseCloud,
        voxelM: Float,
        covered: LongArray,
        cellM: Float,
        floorY: Float,
        eye: Vec3? = null,
        intactM: Float = Float.POSITIVE_INFINITY,
    ): LongArray {
        if (covered.size < MIN_JUDGED_CELLS || cellM <= 0f || voxelM <= 0f || cloud.count == 0) return LongArray(0)
        if (intactM.isFinite() && (eye == null || intactM <= 0f)) return LongArray(0)
        val shares = shares(cloud, voxelM, covered, cellM, floorOf(cloud, voxelM, floorY))
        val coveredSet = HashSet<Long>(covered.size * 2).apply { covered.forEach { add(it) } }
        return covered.filterIndexed { k, key ->
            val cx = CoverageGrid.cxOf(key)
            val cz = CoverageGrid.czOf(key)
            val inner = CoverageGrid.key(cx - 1, cz) in coveredSet && CoverageGrid.key(cx + 1, cz) in coveredSet &&
                CoverageGrid.key(cx, cz - 1) in coveredSet && CoverageGrid.key(cx, cz + 1) in coveredSet
            inner && shares.standing[k] <= OCCUPIED_SHARE && shares.floor[k] < WEAK_SHARE &&
                shares.coarsest[k] <= MAX_JUDGED_LEVEL &&
                (!intactM.isFinite() || intact(eye!!, intactM, cx, cz, cellM, shares.floorY[k]))
        }.toLongArray()
    }

    /** Whether cell ([cx], [cz]) lies within [radius] of [eye], from its floor at [floorY] up to [STANDING_M]. */
    private fun intact(eye: Vec3, radius: Float, cx: Int, cz: Int, cellM: Float, floorY: Float): Boolean {
        val r2 = radius * radius
        for (corner in 0 until CORNERS) {
            val dx = (cx + (corner and 1)) * cellM - eye.x
            val dz = (cz + (corner shr 1 and 1)) * cellM - eye.z
            val dy = floorY + (if (corner shr 2 == 0) 0f else STANDING_M) - eye.y
            if (dx * dx + dy * dy + dz * dz > r2) return false
        }
        return true
    }

    /**
     * Each of [covered]'s cells, by index: the share of its area the floor band's surfels cover
     * seen from above ([floor], `0..1`: a surfel `voxelM · 2^level` wide covers its footprint
     * once, however many layers of depth noise stack it), the area of what stands in it over its
     * own ([standing]), and the floor height it was judged at ([floorY]).
     */
    class Shares(val floor: FloatArray, val standing: FloatArray, val floorY: FloatArray, val coarsest: IntArray)

    /**
     * [Shares] of [covered] in [cloud]. The floor's height is found again in every [TILE_M] tile,
     * within [TILE_SEARCH_M] of [floorY] ([peakOf]): a floor sloping a centimetre a metre, a step
     * down into another room or ARCore drifting over a long scan keeps its band. A tile with too
     * little floor to tell keeps [floorY].
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun shares(cloud: DenseCloud, voxelM: Float, covered: LongArray, cellM: Float, floorY: Float): Shares {
        val index = HashMap<Long, Int>(covered.size * 2)
        covered.forEachIndexed { i, key -> index[key] = i }
        val p = cloud.positions
        // The floor of each tile: the surfel area at each height near the room's floor.
        val bins = (2 * TILE_SEARCH_M / voxelM).toInt() + 1
        val tiles = HashMap<Long, FloatArray>()
        for (i in 0 until cloud.count) {
            val b = floor((p[i * 3 + 1] - floorY + TILE_SEARCH_M) / voxelM).toInt()
            if (b !in 0 until bins) continue
            val tile = CoverageGrid.key(floor(p[i * 3] / TILE_M).toInt(), floor(p[i * 3 + 2] / TILE_M).toInt())
            val side = voxelM * (1 shl cloud.levelOf(i))
            tiles.getOrPut(tile) { FloatArray(bins) }[b] += side * side
        }
        val minTileArea = TILE_MIN_SHARE * TILE_M * TILE_M
        val tileFloor = HashMap<Long, Float>(tiles.size * 2)
        for ((tile, area) in tiles) {
            val peak = if (area.sum() < minTileArea) null else peakOf(area)
            tileFloor[tile] = peak?.let { floorY - TILE_SEARCH_M + (it + HALF) * voxelM } ?: floorY
        }
        fun floorAt(x: Float, z: Float): Float =
            tileFloor[CoverageGrid.key(floor(x / TILE_M).toInt(), floor(z / TILE_M).toInt())] ?: floorY

        // Each cell's footprint, one bit per voxel square whose centre lies in the cell.
        val side = ceil(cellM / voxelM).toInt() + 1
        val words = (side * side + Long.SIZE_BITS - 1) / Long.SIZE_BITS
        val bits = LongArray(covered.size * words)
        val standing = FloatArray(covered.size)
        val coarsest = IntArray(covered.size)
        val cellArea = cellM * cellM
        for (i in 0 until cloud.count) {
            val x = p[i * 3]
            val z = p[i * 3 + 2]
            val above = p[i * 3 + 1] - floorAt(x, z)
            if (above < -FLOOR_BELOW_M || above > STANDING_M) continue
            val span = 1 shl cloud.levelOf(i)
            if (above > FLOOR_BAND_M) {
                val k = index[CoverageGrid.key(floor(x / cellM).toInt(), floor(z / cellM).toInt())] ?: continue
                val w = voxelM * span
                standing[k] += w * w / cellArea
                continue
            }
            index[CoverageGrid.key(floor(x / cellM).toInt(), floor(z / cellM).toInt())]?.let {
                coarsest[it] = maxOf(coarsest[it], cloud.levelOf(i))
            }
            // The surfel's footprint, in voxel squares: one for a full-detail surfel.
            val sx = floor(x / voxelM).toInt() - span / 2
            val sz = floor(z / voxelM).toInt() - span / 2
            for (a in 0 until span) for (b in 0 until span) {
                val vx = sx + a
                val vz = sz + b
                val cx = floor((vx + HALF) * voxelM / cellM).toInt()
                val cz = floor((vz + HALF) * voxelM / cellM).toInt()
                val k = index[CoverageGrid.key(cx, cz)] ?: continue
                val bit = (vx - firstVoxel(cx, cellM, voxelM)) * side + (vz - firstVoxel(cz, cellM, voxelM))
                if (bit !in 0 until side * side) continue
                bits[k * words + bit / Long.SIZE_BITS] = bits[k * words + bit / Long.SIZE_BITS] or (1L shl bit % Long.SIZE_BITS)
            }
        }
        val square = voxelM * voxelM / cellArea
        val floorShare = FloatArray(covered.size) { k ->
            var n = 0
            for (w in 0 until words) n += java.lang.Long.bitCount(bits[k * words + w])
            n * square
        }
        val floors = FloatArray(covered.size) { k ->
            floorAt((CoverageGrid.cxOf(covered[k]) + HALF) * cellM, (CoverageGrid.czOf(covered[k]) + HALF) * cellM)
        }
        return Shares(floorShare, standing, floors, coarsest)
    }

    /** The first voxel square whose centre lies in cell [c]. */
    private fun firstVoxel(c: Int, cellM: Float, voxelM: Float): Int = ceil(c * cellM / voxelM - HALF).toInt()

    /**
     * The floor's height in [cloud]: the lowest height within [FLOOR_SEARCH_M] of [hint] that
     * holds at least half the most surfel area at any ([peakOf]), in [voxelM] steps — the floor
     * is the lowest wide flat thing near where it is thought to be, not a sofa's seat or a low
     * table wider than the floor seen round it. [hint] itself when nothing lies near it. The
     * live map's floor height is a floor plane's, or a guess from the phone's height without one.
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
        val best = peakOf(area) ?: return hint
        return hint - FLOOR_SEARCH_M + (best + HALF) * voxelM
    }

    /**
     * The floor's bin of [area] (by height): the lowest that holds, smoothed over
     * [PEAK_SMOOTH_BINS] on each side, at least [PEAK_SHARE] of the most any does, then the
     * fullest raw bin of the smoothing's width above it; `null` when all are empty. Depth noise
     * spreads a floor over a few bins, and a wide flat seat above it can outweigh what is seen of
     * the floor without being half as much again.
     */
    fun peakOf(area: FloatArray): Int? {
        val smooth = FloatArray(area.size) { b ->
            var sum = 0f
            for (d in -PEAK_SMOOTH_BINS..PEAK_SMOOTH_BINS) sum += area.getOrElse(b + d) { 0f }
            sum
        }
        val most = smooth.maxOrNull()?.takeIf { it > 0f } ?: return null
        val low = smooth.indices.first { smooth[it] >= PEAK_SHARE * most }
        // Smoothing pulled the edge down by its width: the floor is the fullest bin just above it.
        val window = low..minOf(area.lastIndex, low + 2 * PEAK_SMOOTH_BINS)
        return window.maxBy { area[it] }
    }

    /** Whether the live map paints [weakCells] in soft red, "to redo". */
    const val SHOW_WEAK_CELLS = true

    /**
     * A judged cell whose floor surfels cover less than this share of its area is weak: half.
     * Merged surfels up to [MAX_JUDGED_LEVEL] (8 cm) cut a seen cell finely enough — a 25 cm cell
     * holds the centres of at least nine, 0.92 of it — and a cell whose floor is merged coarser
     * is not judged ([weakCells]).
     */
    const val WEAK_SHARE = 0.5f

    /**
     * The coarsest floor surfel a judged cell may hold: 8 cm. The live cloud merges 16 cm surfels
     * only 8 m from the eye (4 m past its budget), and one of them is 0.41 of a cell.
     */
    const val MAX_JUDGED_LEVEL = 2

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

    /** The side of the tiles the floor's height is found again in, in metres. */
    const val TILE_M = 1f

    /** How far from the room's floor a tile's floor may lie, in metres: a step, a slope, drift. */
    const val TILE_SEARCH_M = 0.3f

    /** Floor area under this share of a tile's, it keeps the room's floor height. */
    const val TILE_MIN_SHARE = 0.1f

    /** Bins [peakOf] smooths over on each side: depth noise spreads a floor over a few. */
    const val PEAK_SMOOTH_BINS = 2

    /** The share of the widest height's area the floor's own must reach ([peakOf]). */
    const val PEAK_SHARE = 0.5f

    /** Covered cells before any is judged weak. */
    const val MIN_JUDGED_CELLS = 4

    private const val CORNERS = 8

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
