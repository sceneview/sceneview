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
     * Surfels read in place: a [DenseCloud]'s ([of]) or the live map's own ([DenseFusion.surfels]),
     * so the worker judges the whole map every snapshot without copying it. [kept] is `false` for
     * a slot to skip.
     */
    interface Surfels {
        val count: Int

        fun kept(i: Int): Boolean

        fun x(i: Int): Float

        fun y(i: Int): Float

        fun z(i: Int): Float

        /** The surfel is `voxelM · 2^level` wide. */
        fun level(i: Int): Int

        companion object {
            fun of(cloud: DenseCloud): Surfels = object : Surfels {
                override val count get() = cloud.count

                override fun kept(i: Int) = true

                override fun x(i: Int) = cloud.positions[i * 3]

                override fun y(i: Int) = cloud.positions[i * 3 + 1]

                override fun z(i: Int) = cloud.positions[i * 3 + 2]

                override fun level(i: Int) = cloud.levelOf(i)
            }
        }
    }

    /**
     * The [covered] cells (keys of [CoverageGrid.key], [cellM] on a side) whose floor the dense
     * map barely holds: the camera passed there, but the floor did not stick — a cell to scan
     * again. A cell is judged on its floor alone ([shares]): the share of its area the floor
     * band's surfels cover seen from above, each `voxelM · 2^level` wide — a point count read the
     * map's merging, not coverage, and the walls' points over a cell counted as its floor. Weak
     * under [WEAK_SHARE].
     *
     * Judged on the whole map ([DenseFusion.surfels]) on the snapshot's worker, never on the
     * snapshot the live card draws: past its budget that one drops far surfels at random, and a
     * floor with holes cut in it is not a floor swept too fast.
     *
     * Never judged: a cell on the scan's edge (an uncovered 4-neighbour: a wall's foot, the
     * frontier); a cell something stands in (more than [OCCUPIED_SHARE] of its area in surfels
     * between its floor band and [STANDING_M]) — the floor under a sofa is not there to scan; and
     * a cell whose floor is merged coarser than [MAX_JUDGED_LEVEL]. Nothing is weak before
     * [MIN_JUDGED_CELLS] cells are covered.
     */
    fun weakCells(surfels: Surfels, voxelM: Float, covered: LongArray, cellM: Float, floorY: Float): LongArray {
        if (covered.size < MIN_JUDGED_CELLS || cellM <= 0f || voxelM <= 0f || surfels.count == 0) return LongArray(0)
        val shares = shares(surfels, voxelM, covered, cellM, floorOf(surfels, voxelM, floorY))
        val index = indexOf(covered)
        fun isCovered(cx: Int, cz: Int) = index.get(CoverageGrid.key(cx, cz)) >= 0
        var n = 0
        val weak = LongArray(covered.size)
        for ((k, key) in covered.withIndex()) {
            val cx = CoverageGrid.cxOf(key)
            val cz = CoverageGrid.czOf(key)
            val inner = isCovered(cx - 1, cz) && isCovered(cx + 1, cz) && isCovered(cx, cz - 1) && isCovered(cx, cz + 1)
            if (inner && shares.standing[k] <= OCCUPIED_SHARE && shares.floor[k] < WEAK_SHARE &&
                shares.coarsest[k] <= MAX_JUDGED_LEVEL
            ) {
                weak[n++] = key
            }
        }
        return weak.copyOf(n)
    }

    /** [weakCells] of a [DenseCloud]. */
    fun weakCells(cloud: DenseCloud, voxelM: Float, covered: LongArray, cellM: Float, floorY: Float): LongArray =
        weakCells(Surfels.of(cloud), voxelM, covered, cellM, floorY)

    /**
     * Each of [covered]'s cells, by index: the share of its area the floor band's surfels cover
     * seen from above ([floor], `0..1`: a surfel `voxelM · 2^level` wide covers its footprint
     * once, however many layers of depth noise stack it), the area of what stands in it over its
     * own ([standing]), the floor height it was judged at ([floorY]) and its coarsest floor
     * surfel's level ([coarsest]).
     */
    class Shares(val floor: FloatArray, val standing: FloatArray, val floorY: FloatArray, val coarsest: IntArray)

    /**
     * [Shares] of [covered] in [surfels]. The floor's height is found again in every [TILE_M] tile,
     * within [TILE_SEARCH_M] of [floorY] ([peakOf]): a floor sloping a centimetre a metre, a step
     * down into another room or ARCore drifting over a long scan keeps its band. A tile with too
     * little floor to tell keeps [floorY]. Two passes over the surfels, no allocation per surfel.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    fun shares(surfels: Surfels, voxelM: Float, covered: LongArray, cellM: Float, floorY: Float): Shares {
        val index = indexOf(covered)
        // The floor of each tile: the surfel area at each height near the room's floor.
        val bins = (2 * TILE_SEARCH_M / voxelM).toInt() + 1
        val tileSlot = LongIntMap()
        val tileAreas = ArrayList<FloatArray>()
        for (i in 0 until surfels.count) {
            if (!surfels.kept(i)) continue
            val b = floor((surfels.y(i) - floorY + TILE_SEARCH_M) / voxelM).toInt()
            if (b !in 0 until bins) continue
            val tile = CoverageGrid.key(floor(surfels.x(i) / TILE_M).toInt(), floor(surfels.z(i) / TILE_M).toInt())
            var slot = tileSlot.get(tile)
            if (slot < 0) {
                slot = tileAreas.size
                tileSlot.put(tile, slot)
                tileAreas += FloatArray(bins)
            }
            val side = voxelM * (1 shl surfels.level(i))
            tileAreas[slot][b] += side * side
        }
        val minTileArea = TILE_MIN_SHARE * TILE_M * TILE_M
        val tileFloors = FloatArray(tileAreas.size) { t ->
            val area = tileAreas[t]
            val peak = if (area.sum() < minTileArea) null else peakOf(area, minTileArea)
            peak?.let { floorY - TILE_SEARCH_M + (it + HALF) * voxelM } ?: floorY
        }
        fun floorAt(x: Float, z: Float): Float {
            val slot = tileSlot.get(CoverageGrid.key(floor(x / TILE_M).toInt(), floor(z / TILE_M).toInt()))
            return if (slot < 0) floorY else tileFloors[slot]
        }

        // Each cell's footprint, one bit per voxel square whose centre lies in the cell.
        val side = ceil(cellM / voxelM).toInt() + 1
        val words = (side * side + Long.SIZE_BITS - 1) / Long.SIZE_BITS
        val bits = LongArray(covered.size * words)
        val standing = FloatArray(covered.size)
        val coarsest = IntArray(covered.size)
        val cellArea = cellM * cellM
        for (i in 0 until surfels.count) {
            if (!surfels.kept(i)) continue
            val x = surfels.x(i)
            val z = surfels.z(i)
            val above = surfels.y(i) - floorAt(x, z)
            if (above < -FLOOR_BELOW_M || above > STANDING_M) continue
            val level = surfels.level(i)
            val span = 1 shl level
            val own = index.get(CoverageGrid.key(floor(x / cellM).toInt(), floor(z / cellM).toInt()))
            if (above > FLOOR_BAND_M) {
                if (own < 0) continue
                val w = voxelM * span
                standing[own] += w * w / cellArea
                continue
            }
            if (own >= 0 && level > coarsest[own]) coarsest[own] = level
            // The surfel's footprint, in voxel squares: one for a full-detail surfel.
            val sx = floor(x / voxelM).toInt() - span / 2
            val sz = floor(z / voxelM).toInt() - span / 2
            for (a in 0 until span) for (b in 0 until span) {
                val vx = sx + a
                val vz = sz + b
                val k = if (span == 1) own else {
                    index.get(CoverageGrid.key(floor((vx + HALF) * voxelM / cellM).toInt(), floor((vz + HALF) * voxelM / cellM).toInt()))
                }
                if (k < 0) continue
                val key = covered[k]
                val bit = (vx - firstVoxel(CoverageGrid.cxOf(key), cellM, voxelM)) * side +
                    (vz - firstVoxel(CoverageGrid.czOf(key), cellM, voxelM))
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

    /** [shares] of a [DenseCloud]. */
    fun shares(cloud: DenseCloud, voxelM: Float, covered: LongArray, cellM: Float, floorY: Float): Shares =
        shares(Surfels.of(cloud), voxelM, covered, cellM, floorY)

    private fun indexOf(covered: LongArray): LongIntMap =
        LongIntMap(Integer.highestOneBit(covered.size.coerceAtLeast(1)) shl 2).apply {
            covered.forEachIndexed { i, key -> put(key, i) }
        }

    /** The first voxel square whose centre lies in cell [c]. */
    private fun firstVoxel(c: Int, cellM: Float, voxelM: Float): Int = ceil(c * cellM / voxelM - HALF).toInt()

    /**
     * The floor's height in [surfels]: the lowest height within [FLOOR_SEARCH_M] of [hint] that
     * holds at least [FLOOR_MIN_AREA_M2] of surfel area ([peakOf]), in [voxelM] steps — the floor
     * is the lowest flat thing near where it is thought to be, not a sofa's seat, a low bed or a
     * low table, however much wider than the floor seen round it. [hint] itself when nothing lies
     * near it: on the live map's worker, ARCore's floor plane ([floorHint]).
     */
    fun floorOf(surfels: Surfels, voxelM: Float, hint: Float): Float {
        val bins = (2 * FLOOR_SEARCH_M / voxelM).toInt() + 1
        val area = FloatArray(bins)
        for (i in 0 until surfels.count) {
            if (!surfels.kept(i)) continue
            val b = floor((surfels.y(i) - hint + FLOOR_SEARCH_M) / voxelM).toInt()
            if (b !in 0 until bins) continue
            val side = voxelM * (1 shl surfels.level(i))
            area[b] += side * side
        }
        val best = peakOf(area, FLOOR_MIN_AREA_M2) ?: return hint
        return hint - FLOOR_SEARCH_M + (best + HALF) * voxelM
    }

    /** [floorOf] a [DenseCloud]. */
    fun floorOf(cloud: DenseCloud, voxelM: Float, hint: Float): Float = floorOf(Surfels.of(cloud), voxelM, hint)

    /**
     * The floor's bin of [area] (by height): the lowest that holds, smoothed over
     * [PEAK_SMOOTH_BINS] on each side, at least [minArea] (an area, never a share of the widest
     * bin), then the fullest raw bin of the smoothing's width above it; `null` when none does.
     * Depth noise spreads a floor over a few bins, and a low bed or a pouf can hold twice the
     * floor seen round it: what is lowest and wide enough is the floor.
     */
    fun peakOf(area: FloatArray, minArea: Float): Int? {
        val smooth = FloatArray(area.size) { b ->
            var sum = 0f
            for (d in -PEAK_SMOOTH_BINS..PEAK_SMOOTH_BINS) sum += area.getOrElse(b + d) { 0f }
            sum
        }
        val low = smooth.indices.firstOrNull { smooth[it] > 0f && smooth[it] >= minArea } ?: return null
        // Smoothing pulled the edge down by its width: the floor is the fullest bin just above it.
        val window = low..minOf(area.lastIndex, low + 2 * PEAK_SMOOTH_BINS)
        return window.maxBy { area[it] }
    }

    /**
     * The phone's height over the floor the worker takes for a first guess, as
     * [ArDebugGeometry.floorHeight] does without a floor plane: [floorOf] looks
     * [FLOOR_SEARCH_M] either side of it, a phone held 0.8 to 1.8 m up.
     */
    const val PHONE_HEIGHT_M = 1.3f

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

    /** The surfel area, in square metres, a height must hold to be the room's floor ([floorOf]). */
    const val FLOOR_MIN_AREA_M2 = 0.1f

    /**
     * The floor height the live map's worker judges from: ARCore's floor plane ([planeY]) once it
     * has one, else [PHONE_HEIGHT_M] under the scan's first pose ([firstEyeY]) — never under the
     * current one, which a phone raised over the head or lowered to the knees would move off the
     * floor and onto a seat, or into nothing.
     */
    fun floorHint(planeY: Float?, firstEyeY: Float): Float = planeY ?: (firstEyeY - PHONE_HEIGHT_M)

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
