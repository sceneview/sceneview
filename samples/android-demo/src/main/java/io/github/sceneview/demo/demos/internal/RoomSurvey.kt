package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * A room's survey, read from a scan's dense cloud (#4540): where its walls stand, how long each
 * is, the floor's area and the ceiling's height.
 *
 * The walls are measured on the cloud's vertical surfels — one line per wall, each facing the
 * room — never on the extent of the cloud or of ARCore's planes: a plane grows past the wall it
 * lies on and a stray point beyond a window is still a point, so a bounding rectangle reads a
 * room larger than its walls (#4328). The floor is the lowest well-seen layer of upward-facing
 * surfels under the camera, not the lowest horizontal plane nor the densest one.
 *
 * What the scan did not see is not invented: a wall seen on less than [MIN_SEEN_SHARE] of its
 * length is an estimate, and so is every length that ends on it; no area is given unless two
 * pairs of facing walls were seen. A measured figure carries the scatter of the surfels it was
 * read from — a scatter, not an accuracy — and an estimate carries none: it was not measured.
 * Same cloud, same figures: nothing here is random or order-dependent beyond floating-point sums
 * over the cloud's own order.
 *
 * What it cannot tell, it says or documents: walls are read at right angles to each other (one
 * that is not gives [SurveyGap.ObliqueWall]); a tall piece of furniture is told from the wall
 * only when the wall was seen above it; a floor seen on less than [RoomSurveyor.FLOOR_RIVAL_SHARE]
 * of the densest horizontal layer above it is taken for noise.
 */

/** A scan as the survey reads it: the dense cloud's surfels ([normals] per point) and the camera's path. */
class RoomSurveyInput(
    val positions: FloatArray,
    val normals: FloatArray?,
    val trail: FloatArray = FloatArray(0),
)

/**
 * One figure: its [value], the scatter of the surfels it was read from ([plusMinus] — not an
 * accuracy), and whether part of it was not seen. An [estimate] has no scatter: [plusMinus] is 0.
 */
data class SurveyFigure(val value: Float, val plusMinus: Float, val estimate: Boolean = false) {
    companion object {
        /** A figure read with [spread] — which an [estimate], not being measured, does not carry. */
        fun of(value: Float, spread: Float, estimate: Boolean) =
            SurveyFigure(value, if (estimate) 0f else spread, estimate)
    }
}

/**
 * One wall of the outline, from ([ax], [ay]) to ([bx], [by]) in the plan's own frame: metres, x
 * to the right along the room's longer side, y down the page, the room kept on the right of the
 * walk (clockwise on the page).
 *
 * @property seenShare the part of its length the scan saw a wall on, 0..1.
 * @property seen `false` under [RoomSurveyor.MIN_SEEN_SHARE]: drawn dashed, its length an estimate.
 * @property unseen the stretches the scan saw no wall on, in metres from the wall's start.
 */
class SurveyWall(
    val tag: String,
    val ax: Float,
    val ay: Float,
    val bx: Float,
    val by: Float,
    val length: SurveyFigure,
    val seenShare: Float,
    val seen: Boolean,
    val unseen: List<ClosedFloatingPointRange<Float>>,
) {
    val horizontal: Boolean get() = abs(by - ay) < abs(bx - ax)
}

/** What a scan lacks for a full survey, each a thing its owner can go and scan. */
enum class SurveyGap {
    /** No dense cloud with normals: a scan made without depth. */
    NoDepth,

    /** Not one wall was seen high enough to be told from furniture. */
    NoWalls,

    /** Fewer than two pairs of facing walls: no area is given. */
    MissingWall,

    /** A wall stands square to no other: the plan draws right angles only, so its area is an estimate. */
    ObliqueWall,

    /** Too little ceiling seen for a height. */
    Ceiling,
}

/**
 * The survey of one scan. [walls] is the outline, clockwise from its top-left corner — empty
 * when no wall was seen. [area], [perimeter], [length] and [width] are `null` when the walls
 * that bound them were not seen ([gaps] says what is missing); [ceilingHeight] is `null` when
 * the ceiling was not.
 */
class RoomSurvey(
    val walls: List<SurveyWall>,
    val planLength: Float,
    val planWidth: Float,
    val length: SurveyFigure?,
    val width: SurveyFigure?,
    val area: SurveyFigure?,
    val perimeter: SurveyFigure?,
    val ceilingHeight: SurveyFigure?,
    val gaps: List<SurveyGap>,
    /** The floor's height in the scan's frame, and how thick its layer of surfels is. */
    val floorY: Float,
    val floorThickness: Float,
    /** The room's turn about +Y from the scan's axes: the plan is drawn squared to the walls. */
    val yaw: Float,
) {
    val hasPlan: Boolean get() = walls.isNotEmpty()

    /** `true` when any figure or wall is an estimate: the exports then explain the mark. */
    val hasEstimates: Boolean
        get() = walls.any { it.length.estimate } || area?.estimate == true || length?.estimate == true ||
            width?.estimate == true || perimeter?.estimate == true || ceilingHeight?.estimate == true

    /** `true` when any figure is measured, and so carries a ±: the exports then say what the ± is. */
    val hasSpread: Boolean
        get() = (walls.map { it.length } + listOfNotNull(area, length, width, perimeter, ceilingHeight))
            .any { !it.estimate }

    companion object {
        fun empty(gap: SurveyGap) =
            RoomSurvey(emptyList(), 0f, 0f, null, null, null, null, null, listOf(gap, SurveyGap.Ceiling), 0f, 0f, 0f)
    }
}

/** Reads a [RoomSurvey] from a scan. Pure Kotlin; a few hundred thousand surfels take well under a second. */
@Suppress("TooManyFunctions", "LargeClass") // one pass over the cloud, then small steps that each own one decision
object RoomSurveyor {
    /** The grid the cloud is read on. */
    const val CELL_M = 0.05f

    /** A wall seen on less than this share of its length is drawn dashed and its length is an estimate. */
    const val MIN_SEEN_SHARE = 0.5f

    /** A vertical face has to reach this high above the floor to be a wall and not furniture. */
    const val WALL_MIN_TOP_M = 1.3f

    /** A wall is a line seen on at least this much of its length. */
    const val MIN_WALL_SEEN_M = 0.6f

    /**
     * Layers of one wall are one wall within this: a scan that drifted draws a wall twice, and
     * 0.3 m is under the thinnest gap two real facing walls leave between them.
     */
    const val WALL_MERGE_M = 0.3f

    /** A ceiling is a height only once this much of it was seen. */
    const val MIN_CEILING_AREA_M2 = 1f

    /** The lowest a ceiling is looked for above the floor. */
    const val MIN_CEILING_M = 2f

    /**
     * The lowest horizontal layer holding this share of the densest one's surfels is the floor: a
     * bed or a table scanned closely can outweigh the floor around it, a stray patch under the
     * floor cannot weigh a quarter of it.
     */
    const val FLOOR_RIVAL_SHARE = 0.25f

    /** A vertical face is furniture when the same wall is seen this much higher, within [STRIP_M] behind it. */
    const val FURNITURE_UNDER_M = 0.2f

    /** Ceiling seen this far from the height that is given, over [MIN_CEILING_AREA_M2], is a second level. */
    const val CEILING_LEVEL_M = 0.2f

    /** A wall square to no other is said once this much of it bounds the room. */
    const val OBLIQUE_MIN_M = 1f

    /** No figure is given tighter than this, however thin the wall read. */
    const val MIN_SPREAD_M = 0.02f

    private const val MIN_SURFELS = 500
    private const val UP = 0.8f
    private const val VERTICAL = 0.26f
    private const val AXIS = 0.9f
    private const val REACH_M = 8f
    private const val WALL_CELL_SURFELS = 5
    private const val WALL_CELL_SPAN_M = 0.4f
    private const val WALL_FROM_M = 0.2f
    private const val INSIDE_BELOW_M = 0.3f
    private const val INSIDE_ABOVE_M = 2.2f
    private const val BIN_M = 0.02f
    private const val FLOOR_SMOOTH_BINS = 5
    private const val FLOOR_BAND_BINS = 13
    private const val FLOOR_ABOVE_CAMERA_M = 0.25f
    private const val FLOOR_BELOW_CAMERA_M = 2.5f
    private const val CEILING_BAND_M = 0.1f
    private const val CEILING_MAX_M = 6f
    private const val BLOCK_SHARE = 0.5f
    private const val FILL_SHARE = 0.5f
    private const val STRIP_M = 1f
    private const val UNSEEN_RUN_M = 0.3f
    private const val CLOSURE_SNAP_M = 0.3f
    private const val MAX_LINES = 12
    private const val CLASSES = 4

    /** The fifth kind of vertical surfel: one facing none of the four ways the room is squared to. */
    private const val OBLIQUE = 4
    private const val U_PLUS = 0
    private const val U_MINUS = 1
    private const val V_PLUS = 2
    private const val V_MINUS = 3

    /** A wall line: a side of the room at [pos] on its axis, its surfels spread by [spread], facing [facing]. */
    private class Line(val pos: Float, val spread: Float, val facing: Int, val support: Int, val along: BooleanArray)

    /** A coordinate of the cell complex: the wall lines that stand on it (one per facing at most matters). */
    private class Coord(val pos: Float, val lines: List<Line>) {
        fun line(facing: Int): Line? = lines.filter { it.facing == facing }.maxByOrNull { it.support }
        fun blocked(from: Int, to: Int): Float {
            if (to <= from || lines.isEmpty()) return 0f
            var hit = 0
            for (k in from until to) if (lines.any { k < it.along.size && it.along[k] }) hit++
            return hit.toFloat() / (to - from)
        }
    }

    /** One side of the traced outline: on coordinate [coord] of its axis, between two of the other's. */
    private class Edge(val vertical: Boolean, val coord: Int, val from: Int, val to: Int, val facing: Int)

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod") // the steps of one reading, in order
    fun survey(input: RoomSurveyInput): RoomSurvey {
        val normals = input.normals
        val count = input.positions.size / 3
        if (normals == null || normals.size < count * 3 || count < MIN_SURFELS) {
            return RoomSurvey.empty(SurveyGap.NoDepth)
        }
        val floor = floorOf(input.positions, normals, input.trail) ?: return RoomSurvey.empty(SurveyGap.NoWalls)
        val floorY = floor[0]
        val yaw = yawOf(input.positions, normals, floorY)
        val c = cos(yaw)
        val s = sin(yaw)
        val grid = Grid.of(input, c, s) ?: return RoomSurvey.empty(SurveyGap.NoWalls)
        val ceilingH = ArrayList<Float>()
        val ceilingCell = ArrayList<Int>()
        grid.fill(input.positions, normals, floorY, c, s, ceilingH, ceilingCell)
        grid.sortFurniture()

        val linesU = linesOf(grid, U_PLUS, vertical = true) + linesOf(grid, U_MINUS, vertical = true)
        val linesV = linesOf(grid, V_PLUS, vertical = false) + linesOf(grid, V_MINUS, vertical = false)
        if (linesU.isEmpty() && linesV.isEmpty()) {
            val height = ceilingOf(ceilingH, ceilingCell, grid, floor, null)
            return RoomSurvey(
                emptyList(), 0f, 0f, null, null, null, null, height,
                listOfNotNull(SurveyGap.NoWalls, SurveyGap.Ceiling.takeIf { height == null }), floorY, floor[1], yaw,
            )
        }
        val reach = grid.reach(input.trail, c, s)
        val oblique = grid.obliqueReached(reach) * CELL_M >= OBLIQUE_MIN_M
        val coordsU = coordsOf(linesU, grid.closures(reach, vertical = true))
        val coordsV = coordsOf(linesV, grid.closures(reach, vertical = false))
        val edges = outlineOf(grid, reach, coordsU, coordsV, input.trail, c, s)
        if (edges.size < CLASSES) return RoomSurvey.empty(SurveyGap.NoWalls)
        return assemble(edges, coordsU, coordsV, grid, floor, yaw, ceilingH, ceilingCell, oblique)
    }

    // ── Floor and orientation ────────────────────────────────────────────────────────────────

    /**
     * `[y, thickness, rival]` of the floor: the lowest layer of upward-facing surfels under the
     * camera that holds [FLOOR_RIVAL_SHARE] of the densest one. Its height is the layer's mean,
     * over a band of ± [FLOOR_BAND_BINS] bins: wide enough for the layer a drifting scan
     * thickens, narrower than the lowest seat. `rival` is 1 when a denser layer lies above it —
     * a bed, a table: the floor is then the lower one, and the height read from it an estimate.
     */
    @Suppress("LoopWithTooManyJumpStatements", "CyclomaticComplexMethod") // one skip per surfel of another kind
    private fun floorOf(positions: FloatArray, normals: FloatArray, trail: FloatArray): FloatArray? {
        val n = positions.size / 3
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        if (trail.size >= 3) {
            var sum = 0.0
            for (i in 0 until trail.size / 3) sum += trail[i * 3 + 1]
            val camera = (sum / (trail.size / 3)).toFloat()
            lo = camera - FLOOR_BELOW_CAMERA_M
            hi = camera - FLOOR_ABOVE_CAMERA_M
        } else {
            for (i in 0 until n) {
                lo = min(lo, positions[i * 3 + 1])
                hi = max(hi, positions[i * 3 + 1])
            }
            hi = lo + (hi - lo) / 2
        }
        val bins = ((hi - lo) / BIN_M).toInt() + 1
        if (bins < 2) return null
        val hist = IntArray(bins)
        for (i in 0 until n) {
            if (normals[i * 3 + 1] < UP) continue
            val y = positions[i * 3 + 1]
            if (y < lo || y >= hi) continue
            hist[((y - lo) / BIN_M).toInt().coerceIn(0, bins - 1)]++
        }
        val windows = windowsOf(hist, FLOOR_SMOOTH_BINS)
        val densest = peakOf(windows, 0, bins - 1) ?: return null
        val need = windows[densest] * FLOOR_RIVAL_SHARE
        val lowest = (0 until bins).first { windows[it] >= need }
        val peak = peakOf(windows, lowest, min(bins - 1, lowest + FLOOR_BAND_BINS)) ?: return null
        val rival = densest - peak > FLOOR_BAND_BINS
        val from = max(0, peak - FLOOR_BAND_BINS)
        val to = min(bins - 1, peak + FLOOR_BAND_BINS)
        var weight = 0L
        var sum = 0.0
        for (b in from..to) {
            weight += hist[b]
            sum += hist[b] * (lo + (b + HALF) * BIN_M).toDouble()
        }
        if (weight < MIN_SURFELS / 2) return null
        // The layer's thickness: what holds its middle 90 %.
        var acc = 0L
        var low = from
        var high = to
        for (b in from..to) {
            acc += hist[b]
            if (acc <= weight * TAIL) low = b
            if (acc < weight * (1 - TAIL)) high = b + 1
        }
        return floatArrayOf((sum / weight).toFloat(), (min(high, to) - low + 1) * BIN_M, if (rival) 1f else 0f)
    }

    /** [hist] summed over ± [radius] bins around each. */
    private fun windowsOf(hist: IntArray, radius: Int): LongArray {
        val windows = LongArray(hist.size)
        var window = 0L
        for (b in 0 until min(radius, hist.size)) window += hist[b]
        for (b in hist.indices) {
            if (b + radius < hist.size) window += hist[b + radius]
            if (b - radius - 1 >= 0) window -= hist[b - radius - 1]
            windows[b] = window
        }
        return windows
    }

    /**
     * The index in [from]..[to] where [windows] is highest; `null` when empty. A thin layer makes
     * that sum a plateau as wide as the window: its middle is the layer, not its edge.
     */
    private fun peakOf(windows: LongArray, from: Int, to: Int): Int? {
        var best = 0L
        var first = -1
        var last = -1
        for (b in from..to) {
            if (windows[b] > best) {
                best = windows[b]
                first = b
                last = b
            } else if (windows[b] == best && last == b - 1) {
                last = b
            }
        }
        return if (first < 0) null else (first + last) / 2
    }

    /** The turn that squares the room: the direction the vertical surfels face, folded over a quarter turn. */
    @Suppress("LoopWithTooManyJumpStatements") // one skip per surfel that is not of this kind
    private fun yawOf(positions: FloatArray, normals: FloatArray, floorY: Float): Float {
        var sx = 0.0
        var sy = 0.0
        for (i in 0 until positions.size / 3) {
            if (abs(normals[i * 3 + 1]) > VERTICAL) continue
            val h = positions[i * 3 + 1] - floorY
            if (h < WALL_FROM_M || h > CEILING_MAX_M) continue
            val a = atan2(normals[i * 3 + 2], normals[i * 3]) * QUARTERS
            sx += cos(a)
            sy += sin(a)
        }
        return (atan2(sy, sx) / QUARTERS).toFloat()
    }

    // ── The grid ─────────────────────────────────────────────────────────────────────────────

    /** The cloud on a [CELL_M] grid squared to the room: what stands in each cell, per facing. */
    private class Grid(val minU: Float, val minV: Float, val nu: Int, val nv: Int) {
        val occupied = BooleanArray(nu * nv)
        val counts = Array(CLASSES + 1) { IntArray(nu * nv) }
        val lows = Array(CLASSES + 1) { FloatArray(nu * nv) { Float.POSITIVE_INFINITY } }
        val highs = Array(CLASSES + 1) { FloatArray(nu * nv) { Float.NEGATIVE_INFINITY } }
        val sums = Array(CLASSES + 1) { FloatArray(nu * nv) }

        /** Faces that are furniture: the same wall was seen higher behind them. */
        val fronted = Array(CLASSES + 1) { BooleanArray(nu * nv) }

        /** Walls seen over furniture: a wall there however short the stretch that shows. */
        val backed = Array(CLASSES + 1) { BooleanArray(nu * nv) }

        fun cell(u: Float, v: Float): Int {
            val iu = floor((u - minU) / CELL_M).toInt()
            val iv = floor((v - minV) / CELL_M).toInt()
            return if (iu in 0 until nu && iv in 0 until nv) iv * nu + iu else -1
        }

        /** Enough surfels of facing [k], reaching high enough, to be a wall if they span enough of it. */
        private fun stands(k: Int, cell: Int): Boolean =
            counts[k][cell] >= WALL_CELL_SURFELS && highs[k][cell] >= WALL_MIN_TOP_M

        fun wall(k: Int, cell: Int): Boolean = stands(k, cell) && !fronted[k][cell] &&
            (highs[k][cell] - lows[k][cell] >= WALL_CELL_SPAN_M || backed[k][cell])

        fun anyWall(cell: Int): Boolean = (0..CLASSES).any { wall(it, cell) }

        /**
         * Tells tall furniture from the wall behind it: a face is furniture when the same facing
         * is seen [FURNITURE_UNDER_M] higher within [STRIP_M] behind it — the wall, showing over
         * a wardrobe. A face with no wall seen over it cannot be told from one, and stays a wall.
         */
        fun sortFurniture() {
            val depth = (STRIP_M / CELL_M).toInt()
            for (k in 0 until CLASSES) {
                // Behind a face is the way it does not look.
                val back = (if (k == U_PLUS || k == V_PLUS) -1 else 1) * (if (k < V_PLUS) 1 else nu)
                for (at in 0 until nu * nv) {
                    if (!stands(k, at)) continue
                    val behind = (1..depth).map { at + it * back }
                        .takeWhile { it in 0 until nu * nv && (k >= V_PLUS || it / nu == at / nu) }
                        .firstOrNull { stands(k, it) && highs[k][it] >= highs[k][at] + FURNITURE_UNDER_M }
                    if (behind != null) {
                        fronted[k][at] = true
                        backed[k][behind] = true
                    }
                }
            }
        }

        /** How many cells of a wall square to no other the room reaches — none stands where a squared wall does. */
        fun obliqueReached(reach: BooleanArray): Int =
            reach.indices.count { reach[it] && wall(OBLIQUE, it) && (0 until CLASSES).none { k -> wall(k, it) } }

        // The one pass over the cloud, one skip per surfel that is not of this kind.
        @Suppress("LongParameterList", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
        fun fill(
            positions: FloatArray,
            normals: FloatArray,
            floorY: Float,
            c: Float,
            s: Float,
            ceilingH: MutableList<Float>,
            ceilingCell: MutableList<Int>,
        ) {
            for (i in 0 until positions.size / 3) {
                val x = positions[i * 3]
                val z = positions[i * 3 + 2]
                val u = x * c + z * s
                val v = -x * s + z * c
                val at = cell(u, v)
                if (at < 0) continue
                val h = positions[i * 3 + 1] - floorY
                if (h >= -INSIDE_BELOW_M && h <= INSIDE_ABOVE_M) occupied[at] = true
                val ny = normals[i * 3 + 1]
                if (ny <= -UP && h >= MIN_CEILING_M && h <= CEILING_MAX_M) {
                    ceilingH += h
                    ceilingCell += at
                }
                if (abs(ny) > VERTICAL || h < WALL_FROM_M) continue
                val nx = normals[i * 3]
                val nz = normals[i * 3 + 2]
                val nu = nx * c + nz * s
                val nv = -nx * s + nz * c
                val flat = sqrt(nu * nu + nv * nv)
                val k = when {
                    nu >= AXIS * flat -> U_PLUS
                    -nu >= AXIS * flat -> U_MINUS
                    nv >= AXIS * flat -> V_PLUS
                    -nv >= AXIS * flat -> V_MINUS
                    else -> OBLIQUE
                }
                counts[k][at]++
                lows[k][at] = min(lows[k][at], h)
                highs[k][at] = max(highs[k][at], h)
                sums[k][at] += if (k < V_PLUS) u else v
            }
        }

        /**
         * The cells the camera's room reaches: flooded from where the camera went, through what
         * the scan saw, and never through a wall. What lies behind a wall is not this room.
         */
        fun reach(trail: FloatArray, c: Float, s: Float): BooleanArray {
            val inside = BooleanArray(nu * nv)
            for (iv in 0 until nv) {
                for (iu in 0 until nu) {
                    val at = iv * nu + iu
                    inside[at] = occupied[at] || (iu > 0 && occupied[at - 1]) || (iu < nu - 1 && occupied[at + 1]) ||
                        (iv > 0 && occupied[at - nu]) || (iv < nv - 1 && occupied[at + nu])
                }
            }
            val reached = BooleanArray(nu * nv)
            val queue = IntArray(nu * nv)
            var tail = 0
            for (i in 0 until trail.size / 3) {
                val at = cell(trail[i * 3] * c + trail[i * 3 + 2] * s, -trail[i * 3] * s + trail[i * 3 + 2] * c)
                if (at >= 0 && !reached[at]) {
                    reached[at] = true
                    queue[tail++] = at
                }
            }
            if (tail == 0) {
                // No camera path: the room is taken to be around the middle of what was seen.
                val seen = inside.indices.filter { inside[it] }
                seen.getOrNull(seen.size / 2)?.let {
                    reached[it] = true
                    queue[tail++] = it
                }
            }
            var head = 0
            while (head < tail) {
                val at = queue[head++]
                if (anyWall(at)) continue
                val iu = at % nu
                val iv = at / nu
                if (iu > 0) tail = visit(at - 1, inside, reached, queue, tail)
                if (iu < nu - 1) tail = visit(at + 1, inside, reached, queue, tail)
                if (iv > 0) tail = visit(at - nu, inside, reached, queue, tail)
                if (iv < nv - 1) tail = visit(at + nu, inside, reached, queue, tail)
            }
            return reached
        }

        private fun visit(at: Int, inside: BooleanArray, reached: BooleanArray, queue: IntArray, tail: Int): Int {
            if (!inside[at] || reached[at]) return tail
            reached[at] = true
            queue[tail] = at
            return tail + 1
        }

        /** Where the reached floor stops on an axis: the sides the outline is closed on where no wall was seen. */
        fun closures(reach: BooleanArray, vertical: Boolean): List<Float> {
            val n = if (vertical) nu else nv
            val need = (MIN_WALL_SEEN_M / CELL_M).toInt()
            var first = -1
            var last = -1
            for (i in 0 until n) {
                var cells = 0
                for (j in 0 until if (vertical) nv else nu) if (reach[if (vertical) j * nu + i else i * nu + j]) cells++
                if (cells >= need) {
                    if (first < 0) first = i
                    last = i
                }
            }
            if (first < 0) return emptyList()
            val origin = if (vertical) minU else minV
            return listOf(origin + first * CELL_M, origin + (last + 1) * CELL_M)
        }

        companion object {
            /** A grid around the camera's path, [REACH_M] each way: a room's walls are not further. */
            fun of(input: RoomSurveyInput, c: Float, s: Float): Grid? {
                val from = if (input.trail.size >= 3) input.trail else input.positions
                var minU = Float.POSITIVE_INFINITY
                var maxU = Float.NEGATIVE_INFINITY
                var minV = Float.POSITIVE_INFINITY
                var maxV = Float.NEGATIVE_INFINITY
                for (i in 0 until from.size / 3) {
                    val u = from[i * 3] * c + from[i * 3 + 2] * s
                    val v = -from[i * 3] * s + from[i * 3 + 2] * c
                    minU = min(minU, u)
                    maxU = max(maxU, u)
                    minV = min(minV, v)
                    maxV = max(maxV, v)
                }
                if (minU > maxU) return null
                val reach = if (input.trail.size >= 3) REACH_M else CELL_M
                val nu = ceil((maxU - minU + 2 * reach) / CELL_M).toInt() + 1
                val nv = ceil((maxV - minV + 2 * reach) / CELL_M).toInt() + 1
                if (nu.toLong() * nv > MAX_CELLS) return null
                // Snapped to the grid's own step, so a cloud read twice lands in the same cells.
                return Grid(floor((minU - reach) / CELL_M) * CELL_M, floor((minV - reach) / CELL_M) * CELL_M, nu, nv)
            }

            private const val MAX_CELLS = 4_000_000L
        }
    }

    // ── Walls ────────────────────────────────────────────────────────────────────────────────

    /**
     * The walls of one facing: the strongest line first, then the next outside its
     * [WALL_MERGE_M], each kept only when seen on [MIN_WALL_SEEN_M]. A wall's position is the
     * mean of its surfels and its spread their deviation — two layers of one wall are one wall,
     * given half their distance as its scatter.
     */
    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod", "LongMethod", "LoopWithTooManyJumpStatements")
    private fun linesOf(grid: Grid, k: Int, vertical: Boolean): List<Line> {
        val across = if (vertical) grid.nu else grid.nv
        val along = if (vertical) grid.nv else grid.nu
        fun at(a: Int, l: Int) = if (vertical) l * grid.nu + a else a * grid.nu + l
        val support = IntArray(across)
        for (a in 0 until across) for (l in 0 until along) if (grid.wall(k, at(a, l))) support[a]++
        val radius = (WALL_MERGE_M / CELL_M).toInt()
        val need = (MIN_WALL_SEEN_M / CELL_M).toInt()
        val taken = BooleanArray(across)
        val lines = ArrayList<Line>()
        while (lines.size < MAX_LINES) {
            var peak = -1
            for (a in 0 until across) {
                val free = !taken[a] && support[a] > 0
                if (free && (peak < 0 || support[a] > support[peak])) peak = a
            }
            if (peak < 0) break
            // The window grows over what touches the peak, so both layers of a doubled wall fall in it.
            var from = peak
            var to = peak
            while (from > 0 && peak - from < radius && !taken[from - 1]) from--
            while (to < across - 1 && to - peak < radius && !taken[to + 1]) to++
            val seenAlong = BooleanArray(along)
            var weight = 0.0
            var sum = 0.0
            var squares = 0.0
            for (a in from..to) {
                taken[a] = true
                for (l in 0 until along) {
                    val cell = at(a, l)
                    if (!grid.wall(k, cell)) continue
                    seenAlong[l] = true
                    val w = grid.counts[k][cell]
                    val mean = grid.sums[k][cell] / w
                    weight += w
                    sum += w * mean.toDouble()
                    squares += w * mean.toDouble() * mean
                }
            }
            val seen = seenAlong.count { it }
            if (seen < need) {
                if (support[peak] < need / 2) break
                continue
            }
            val pos = sum / weight
            val spread = sqrt(max(0.0, squares / weight - pos * pos)).toFloat()
            val facing = if (k == U_PLUS || k == V_PLUS) 1 else -1
            lines += Line(pos.toFloat(), max(spread, MIN_SPREAD_M), facing, seen, seenAlong)
        }
        return lines
    }

    /** The complex's coordinates on one axis: the wall lines, plus the [closures] no wall stands near. */
    private fun coordsOf(lines: List<Line>, closures: List<Float>): List<Coord> {
        val sorted = lines.sortedWith(compareBy({ it.pos }, { it.facing }))
        val coords = ArrayList<Coord>()
        var group = ArrayList<Line>()
        for (line in sorted) {
            if (group.isNotEmpty() && line.pos - group.first().pos > WALL_MERGE_M / 2) {
                coords += Coord(group.maxBy { it.support }.pos, group)
                group = ArrayList()
            }
            group += line
        }
        if (group.isNotEmpty()) coords += Coord(group.maxBy { it.support }.pos, group)
        for (closure in closures) {
            if (coords.none { abs(it.pos - closure) < CLOSURE_SNAP_M }) coords += Coord(closure, emptyList())
        }
        return coords.sortedBy { it.pos }
    }

    // ── The outline ──────────────────────────────────────────────────────────────────────────

    /**
     * The room's outline on the complex the wall lines cut: the cells the camera stood in, grown
     * through every side that is not a wall into cells the scan saw into, then traced clockwise.
     */
    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    private fun outlineOf(
        grid: Grid,
        reach: BooleanArray,
        coordsU: List<Coord>,
        coordsV: List<Coord>,
        trail: FloatArray,
        c: Float,
        s: Float,
    ): List<Edge> {
        val nx = coordsU.size - 1
        val ny = coordsV.size - 1
        if (nx < 1 || ny < 1) return emptyList()
        val iu = IntArray(coordsU.size) { ((coordsU[it].pos - grid.minU) / CELL_M + HALF).toInt().coerceIn(0, grid.nu) }
        val iv = IntArray(coordsV.size) { ((coordsV[it].pos - grid.minV) / CELL_M + HALF).toInt().coerceIn(0, grid.nv) }
        fun share(i: Int, j: Int): Float {
            var hit = 0
            var all = 0
            for (v in iv[j] until iv[j + 1]) {
                for (u in iu[i] until iu[i + 1]) {
                    all++
                    if (reach[v * grid.nu + u]) hit++
                }
            }
            return if (all == 0) 0f else hit.toFloat() / all
        }
        val selected = BooleanArray(nx * ny)
        val queue = ArrayDeque<Int>()
        fun rectOf(u: Float, v: Float): Int {
            val i = (0 until nx).firstOrNull { u >= coordsU[it].pos && u < coordsU[it + 1].pos } ?: return -1
            val j = (0 until ny).firstOrNull { v >= coordsV[it].pos && v < coordsV[it + 1].pos } ?: return -1
            return j * nx + i
        }
        for (t in 0 until trail.size / 3) {
            val at = rectOf(trail[t * 3] * c + trail[t * 3 + 2] * s, -trail[t * 3] * s + trail[t * 3 + 2] * c)
            if (at >= 0 && !selected[at]) {
                selected[at] = true
                queue += at
            }
        }
        if (queue.isEmpty()) {
            val best = (0 until nx * ny).maxByOrNull { share(it % nx, it / nx) } ?: return emptyList()
            selected[best] = true
            queue += best
        }
        // Grows into a neighbour when the side between them is not a wall and the scan saw into it —
        // or it is a strip up to a wall that was seen, its floor hidden behind what stands on it.
        fun grow(i: Int, j: Int, wall: Float, far: Float, depth: Float) {
            val open = i in 0 until nx && j in 0 until ny && !selected[j * nx + i] && wall < BLOCK_SHARE
            if (!open) return
            val strip = depth <= STRIP_M && far >= BLOCK_SHARE && share(i, j) > 0f
            if (share(i, j) >= FILL_SHARE || strip) {
                selected[j * nx + i] = true
                queue += j * nx + i
            }
        }
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            val i = at % nx
            val j = at / nx
            if (i > 0) {
                val depth = coordsU[i].pos - coordsU[i - 1].pos
                grow(i - 1, j, coordsU[i].blocked(iv[j], iv[j + 1]), coordsU[i - 1].blocked(iv[j], iv[j + 1]), depth)
            }
            if (i < nx - 1) {
                val depth = coordsU[i + 2].pos - coordsU[i + 1].pos
                val near = coordsU[i + 1].blocked(iv[j], iv[j + 1])
                val far = coordsU[i + 2].blocked(iv[j], iv[j + 1])
                grow(i + 1, j, near, far, depth)
            }
            if (j > 0) {
                val depth = coordsV[j].pos - coordsV[j - 1].pos
                grow(i, j - 1, coordsV[j].blocked(iu[i], iu[i + 1]), coordsV[j - 1].blocked(iu[i], iu[i + 1]), depth)
            }
            if (j < ny - 1) {
                val depth = coordsV[j + 2].pos - coordsV[j + 1].pos
                val near = coordsV[j + 1].blocked(iu[i], iu[i + 1])
                val far = coordsV[j + 2].blocked(iu[i], iu[i + 1])
                grow(i, j + 1, near, far, depth)
            }
        }
        fillHoles(selected, nx, ny)
        return trace(selected, nx, ny)
    }

    /** A cell the outline surrounds is part of the room: a plan has no islands. */
    private fun fillHoles(selected: BooleanArray, nx: Int, ny: Int) {
        val outside = BooleanArray(nx * ny)
        val queue = ArrayDeque<Int>()
        fun seed(at: Int) {
            if (!selected[at] && !outside[at]) {
                outside[at] = true
                queue += at
            }
        }
        for (i in 0 until nx) {
            seed(i)
            seed((ny - 1) * nx + i)
        }
        for (j in 0 until ny) {
            seed(j * nx)
            seed(j * nx + nx - 1)
        }
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            val i = at % nx
            val j = at / nx
            if (i > 0) seed(at - 1)
            if (i < nx - 1) seed(at + 1)
            if (j > 0) seed(at - nx)
            if (j < ny - 1) seed(at + nx)
        }
        for (at in selected.indices) if (!outside[at]) selected[at] = true
    }

    /** The boundary of the [selected] cells, clockwise on the page from its top-left corner, sides merged. */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
    private fun trace(selected: BooleanArray, nx: Int, ny: Int): List<Edge> {
        fun on(i: Int, j: Int) = i in 0 until nx && j in 0 until ny && selected[j * nx + i]
        val w = nx + 1
        // Unit steps out of each vertex: dx, dy.
        val steps = HashMap<Int, MutableList<IntArray>>()
        fun step(x: Int, y: Int, dx: Int, dy: Int) {
            steps.getOrPut(y * w + x) { ArrayList() } += intArrayOf(dx, dy)
        }
        for (j in 0 until ny) {
            for (i in 0 until nx) {
                if (!on(i, j)) continue
                if (!on(i, j - 1)) step(i, j, 1, 0)
                if (!on(i + 1, j)) step(i + 1, j, 0, 1)
                if (!on(i, j + 1)) step(i + 1, j + 1, -1, 0)
                if (!on(i - 1, j)) step(i, j + 1, 0, -1)
            }
        }
        val start = steps.keys.minOrNull() ?: return emptyList()
        val edges = ArrayList<Edge>()
        var x = start % w
        var y = start / w
        var dx = 1
        var dy = 0
        var runX = x
        var runY = y
        var guard = 0
        fun close(toX: Int, toY: Int) {
            if (toX == runX && toY == runY) return
            edges += if (dx != 0) {
                Edge(vertical = false, coord = runY, from = runX, to = toX, facing = if (dx > 0) 1 else -1)
            } else {
                Edge(vertical = true, coord = runX, from = runY, to = toY, facing = if (dy > 0) -1 else 1)
            }
            runX = toX
            runY = toY
        }
        do {
            val out = steps[y * w + x] ?: break
            // Where two cells touch by a corner, the walk turns left and takes both in one loop.
            val next = out.minByOrNull { d ->
                when {
                    d[0] == dy && d[1] == -dx -> 0
                    d[0] == dx && d[1] == dy -> 1
                    else -> 2
                }
            } ?: break
            out.remove(next)
            if (next[0] != dx || next[1] != dy) {
                close(x, y)
                dx = next[0]
                dy = next[1]
            }
            x += dx
            y += dy
        } while ((x != start % w || y != start / w) && ++guard < MAX_STEPS)
        close(x, y)
        // The first and last runs are one side when the walk started in its middle.
        if (edges.size > CLASSES && edges.first().vertical == edges.last().vertical &&
            edges.first().coord == edges.last().coord
        ) {
            val last = edges.removeAt(edges.size - 1)
            val first = edges.removeAt(0)
            edges.add(0, Edge(first.vertical, first.coord, last.from, first.to, first.facing))
        }
        return edges
    }

    // ── Figures ──────────────────────────────────────────────────────────────────────────────

    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    private fun assemble(
        edges: List<Edge>,
        coordsU: List<Coord>,
        coordsV: List<Coord>,
        grid: Grid,
        floor: FloatArray,
        yaw: Float,
        ceilingH: List<Float>,
        ceilingCell: List<Int>,
        oblique: Boolean,
    ): RoomSurvey {
        val n = edges.size
        val lines = edges.map { (if (it.vertical) coordsU else coordsV)[it.coord].line(it.facing) }
        val pos = FloatArray(n) {
            lines[it]?.pos ?: (if (edges[it].vertical) coordsU else coordsV)[edges[it].coord].pos
        }
        // Corner i is where side i starts: the meeting of side i - 1 and side i.
        val cu = FloatArray(n)
        val cv = FloatArray(n)
        for (i in 0 until n) {
            val before = (i + n - 1) % n
            cu[i] = if (edges[i].vertical) pos[i] else pos[before]
            cv[i] = if (edges[i].vertical) pos[before] else pos[i]
        }
        val shares = FloatArray(n)
        val unseen = ArrayList<List<ClosedFloatingPointRange<Float>>>()
        for (i in 0 until n) {
            val next = (i + 1) % n
            val a = if (edges[i].vertical) cv[i] else cu[i]
            val b = if (edges[i].vertical) cv[next] else cu[next]
            val origin = if (edges[i].vertical) grid.minV else grid.minU
            unseen += seenAlong(lines[i], a, b, origin).let { (share, runs) ->
                shares[i] = share
                runs
            }
        }
        val seen = BooleanArray(n) { lines[it] != null && shares[it] >= MIN_SEEN_SHARE }
        if (seen.none { it }) {
            // An outline with no wall seen on it would only be the extent of the floor: not drawn.
            val height = ceilingOf(ceilingH, ceilingCell, grid, floor, null)
            return RoomSurvey(
                emptyList(), 0f, 0f, null, null, null, null, height,
                listOfNotNull(SurveyGap.NoWalls, SurveyGap.Ceiling.takeIf { height == null }), floor[0], floor[1], yaw,
            )
        }
        // A side that was not seen has no scatter to give: what is read from it is an estimate.
        val spread = FloatArray(n) { if (seen[it]) lines[it]!!.spread else 0f }

        // The page frame: the room's longer side along x, its top-left corner at the origin.
        val minU = cu.min()
        val minV = cv.min()
        val extentU = cu.max() - minU
        val extentV = cv.max() - minV
        val turned = extentV > extentU
        val px = FloatArray(n) { if (turned) extentV - (cv[it] - minV) else cu[it] - minU }
        val py = FloatArray(n) { if (turned) cu[it] - minU else cv[it] - minV }
        val first = (0 until n).minWith(compareBy({ py[it] }, { px[it] }))
        val walls = (0 until n).map { k ->
            val i = (first + k) % n
            val next = (i + 1) % n
            val before = (i + n - 1) % n
            val length = abs(px[next] - px[i]) + abs(py[next] - py[i])
            SurveyWall(
                tag = tagOf(k),
                ax = px[i], ay = py[i], bx = px[next], by = py[next],
                // A wall runs from one neighbour to the other: it is measured when all three were seen.
                length = SurveyFigure.of(
                    length, spread[before] + spread[next], estimate = !seen[i] || !seen[before] || !seen[next],
                ),
                seenShare = shares[i],
                seen = seen[i],
                unseen = if (seen[i]) unseen[i] else listOf(0f..length),
            )
        }
        val pairU = pairSeen(edges, seen, vertical = true)
        val pairV = pairSeen(edges, seen, vertical = false)
        val alongU = extentOf(edges, pos, seen, spread, vertical = true).takeIf { pairU }
        val alongV = extentOf(edges, pos, seen, spread, vertical = false).takeIf { pairV }
        var area = 0.0
        var areaSpread = 0f
        for (i in 0 until n) {
            val next = (i + 1) % n
            area += px[i].toDouble() * py[next] - px[next].toDouble() * py[i]
            areaSpread += (abs(px[next] - px[i]) + abs(py[next] - py[i])) * spread[i]
        }
        val whole = pairU && pairV
        val bounds = floatArrayOf(minU, minV, minU + extentU, minV + extentV)
        val height = ceilingOf(ceilingH, ceilingCell, grid, floor, bounds)
        val rough = oblique || seen.any { !it }
        return RoomSurvey(
            walls = walls,
            planLength = max(extentU, extentV),
            planWidth = min(extentU, extentV),
            length = if (turned) alongV else alongU,
            width = if (turned) alongU else alongV,
            area = SurveyFigure.of(abs(area / 2).toFloat(), areaSpread, rough).takeIf { whole },
            perimeter = SurveyFigure.of(
                walls.sumOf { it.length.value.toDouble() }.toFloat(),
                walls.sumOf { it.length.plusMinus.toDouble() }.toFloat(),
                rough,
            ).takeIf { whole },
            ceilingHeight = height,
            gaps = listOfNotNull(
                SurveyGap.MissingWall.takeIf { !whole },
                SurveyGap.ObliqueWall.takeIf { oblique },
                SurveyGap.Ceiling.takeIf { height == null },
            ),
            floorY = floor[0],
            floorThickness = floor[1],
            yaw = yaw,
        )
    }

    /** `A`…`Z`, then `AA`…: the name a wall has on the plan and in the table. */
    internal fun tagOf(index: Int): String {
        val letter = ('A' + index % LETTERS).toString()
        return if (index < LETTERS) letter else ('A' + index / LETTERS - 1).toString() + letter
    }

    /** The share of the side from [a] to [b] that [line] was seen on, and its unseen stretches from [a]. */
    private fun seenAlong(
        line: Line?,
        a: Float,
        b: Float,
        origin: Float,
    ): Pair<Float, List<ClosedFloatingPointRange<Float>>> {
        val length = abs(b - a)
        if (line == null || length < CELL_M) return 0f to listOf(0f..length)
        val cells = max(1, (length / CELL_M + HALF).toInt())
        val step = (b - a) / cells
        val runs = ArrayList<ClosedFloatingPointRange<Float>>()
        var hit = 0
        var runFrom = -1
        for (k in 0..cells) {
            val index = if (k < cells) floor((a + (k + HALF) * step - origin) / CELL_M).toInt() else -1
            val seen = k < cells && index in line.along.indices && line.along[index]
            if (seen) hit++
            if (!seen && k < cells && runFrom < 0) runFrom = k
            if ((seen || k == cells) && runFrom >= 0) {
                if ((k - runFrom) * CELL_M >= UNSEEN_RUN_M) runs += (runFrom * CELL_M)..min(length, k * CELL_M)
                runFrom = -1
            }
        }
        return hit.toFloat() / cells to runs
    }

    /** A pair of facing walls on one axis: one seen wall looking each way. */
    private fun pairSeen(edges: List<Edge>, seen: BooleanArray, vertical: Boolean): Boolean {
        val facings = edges.indices.filter { edges[it].vertical == vertical && seen[it] }.map { edges[it].facing }
        return 1 in facings && -1 in facings
    }

    /** The outline's extent across the sides of one direction, from its two outermost. */
    private fun extentOf(
        edges: List<Edge>,
        pos: FloatArray,
        seen: BooleanArray,
        spread: FloatArray,
        vertical: Boolean,
    ): SurveyFigure {
        val sides = edges.indices.filter { edges[it].vertical == vertical }
        val low = sides.minBy { pos[it] }
        val high = sides.maxBy { pos[it] }
        // Every side on the outermost coordinates has to be seen for the extent to be measured.
        val outer = sides.filter { pos[it] == pos[low] || pos[it] == pos[high] }
        return SurveyFigure.of(pos[high] - pos[low], spread[low] + spread[high], outer.any { !seen[it] })
    }

    /**
     * The ceiling's height above the [floor] (`[y, thickness, rival]`): the densest layer of
     * downward-facing surfels at [MIN_CEILING_M] or more, once [MIN_CEILING_AREA_M2] of it was
     * seen over the room ([bounds], grid frame). Its spread carries the floor's thickness: the
     * height is read between two thick layers. It is an estimate when the room has more than one
     * to give — a second ceiling level [CEILING_LEVEL_M] away, or a floor that was not the
     * densest layer under the camera.
     */
    @Suppress("LoopWithTooManyJumpStatements") // one skip per surfel outside the room
    private fun ceilingOf(
        heights: List<Float>,
        cells: List<Int>,
        grid: Grid,
        floor: FloatArray,
        bounds: FloatArray?,
    ): SurveyFigure? {
        if (heights.isEmpty()) return null
        val hist = IntArray(((CEILING_MAX_M - MIN_CEILING_M) / BIN_M).toInt() + 1)
        fun inside(cell: Int): Boolean {
            if (bounds == null) return true
            val u = grid.minU + (cell % grid.nu + HALF) * CELL_M
            val v = grid.minV + (cell / grid.nu + HALF) * CELL_M
            return u >= bounds[0] && u <= bounds[2] && v >= bounds[1] && v <= bounds[3]
        }
        for (i in heights.indices) {
            if (inside(cells[i])) hist[((heights[i] - MIN_CEILING_M) / BIN_M).toInt().coerceIn(0, hist.size - 1)]++
        }
        val peak = peakOf(windowsOf(hist, FLOOR_SMOOTH_BINS), 0, hist.size - 1)
            ?.let { MIN_CEILING_M + (it + HALF) * BIN_M } ?: return null
        val seen = HashSet<Int>()
        val elsewhere = HashSet<Int>()
        var sum = 0.0
        var squares = 0.0
        var count = 0
        for (i in heights.indices) {
            if (!inside(cells[i])) continue
            if (abs(heights[i] - peak) > CEILING_LEVEL_M) elsewhere += cells[i]
            if (abs(heights[i] - peak) > CEILING_BAND_M) continue
            seen += cells[i]
            sum += heights[i]
            squares += heights[i].toDouble() * heights[i]
            count++
        }
        if (seen.size * CELL_M * CELL_M < MIN_CEILING_AREA_M2) return null
        val mean = sum / count
        val spread = sqrt(max(0.0, squares / count - mean * mean)).toFloat()
        val levels = elsewhere.size * CELL_M * CELL_M >= MIN_CEILING_AREA_M2
        return SurveyFigure.of(mean.toFloat(), max(MIN_SPREAD_M, floor[1] / 2 + spread), levels || floor[2] > 0f)
    }

    private const val HALF = 0.5f
    private const val TAIL = 0.05
    private const val QUARTERS = 4f
    private const val LETTERS = 26
    private const val MAX_STEPS = 100_000
}
