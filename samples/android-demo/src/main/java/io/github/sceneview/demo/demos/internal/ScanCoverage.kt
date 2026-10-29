package io.github.sceneview.demo.demos.internal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a scan has covered, on the floor: the points binned into [cellMetres] squares of the XZ
 * plane (#4631). A cell with [minPoints] or more is **covered** — there is surface there; a
 * covered cell with an empty neighbour is the **frontier** — the edge of what the scan has,
 * where to walk next. This is the "what is scanned and what is not" of the live mini-map and
 * the `Floor NN % covered` figure. What is not covered yet is the rest of [floorExtent]: the room's
 * floor as the floor planes and the covered cells outline it, which the map hatches.
 *
 * Fed with [ArDebugFrame.mapPoints], which grows as a prefix of one array: only the points past
 * the last [update] are binned, so a frame costs its new points, not the whole cloud. A shorter
 * array (a replay scrubbed back) starts the grid again.
 */
internal class CoverageGrid(
    val cellMetres: Float = CELL_METRES,
    private val minPoints: Int = MIN_POINTS,
) {
    private val counts = HashMap<Long, Int>()
    private var consumed = 0
    private var cellsConsumed = 0

    /** Covered cells, `(cx, cz)` packed by [key]. */
    private val coveredCells = LinkedHashSet<Long>()

    /** How many cells are covered. */
    val covered: Int get() = coveredCells.size

    /** How many times the covered set changed: a cheap "redraw?" for whoever draws it. */
    var version = 0
        private set

    /** How many times the grid started again ([reset]): a replay scrubbed back. */
    var resets = 0
        private set

    /** The floor covered, in m² ([ScanFloorArea.squareMetres] of [covered]). */
    val squareMetres: Float get() = ScanFloorArea.squareMetres(covered, cellMetres)

    var minCx = 0
        private set
    var maxCx = -1
        private set
    var minCz = 0
        private set
    var maxCz = -1
        private set

    /**
     * Bins the first [count] points of [mapPoints] (xyz triples) not seen yet — all of them by
     * default; a dense cloud revealed as a prefix passes its revealed count. Returns `true` when a
     * cell became covered — the scan grew.
     */
    fun update(mapPoints: FloatArray, count: Int = mapPoints.size / 3): Boolean {
        val total = count.coerceIn(0, mapPoints.size / 3)
        if (total < consumed) reset()
        var grew = false
        for (i in consumed until total) {
            val cx = cellOf(mapPoints[i * 3])
            val cz = cellOf(mapPoints[i * 3 + 2])
            val key = key(cx, cz)
            val count = (counts[key] ?: 0) + 1
            counts[key] = count
            if (count == minPoints) {
                coveredCells += key
                include(cx, cz)
                grew = true
            }
        }
        consumed = total
        if (grew) version++
        return grew
    }

    /**
     * Covers the cells [keys] ([key]-packed) names, a live depth snapshot's covered cells found
     * off the main thread ([coveredKeys]): the live dense cloud is a whole new snapshot each time,
     * not a prefix of the last, so [update]'s prefix would bin the wrong points (#4540 live walls —
     * the live figure froze at the feature map's 12,000-point cap while the dense cloud still
     * grew). A cell once covered stays covered. Returns `true` when a cell became covered.
     */
    fun addCells(keys: LongArray): Boolean {
        var grew = false
        for (key in keys) {
            if (coveredCells.add(key)) {
                include(cxOf(key), czOf(key))
                grew = true
            }
        }
        if (grew) version++
        return grew
    }

    /**
     * Covers the first [count] floor cells [trace] recorded ([ArDebugTrace.addFloorCells]) not
     * covered yet: a saved scan's own record, so its replay covers what its capture covered,
     * cell for cell (#4734). A smaller [count] (a replay scrubbed back) starts the grid again.
     */
    fun updateRecorded(trace: ArDebugTrace, count: Int): Boolean {
        if (count < cellsConsumed) reset()
        var grew = false
        for (i in cellsConsumed until count) {
            val key = trace.floorCell(i)
            if (coveredCells.add(key)) {
                include(cxOf(key), czOf(key))
                grew = true
            }
        }
        cellsConsumed = maxOf(cellsConsumed, count)
        if (grew) version++
        return grew
    }

    /**
     * Covers what a saved scan had covered at [time], the one way the replay and the result
     * both read it (#4734): the cells its capture recorded, or, for a scan saved before they
     * were, its dense cloud's first [denseCount] surfels ([dense]) or else its feature points.
     */
    fun updateSaved(
        trace: ArDebugTrace,
        time: Float,
        mapPoints: () -> FloatArray,
        dense: FloatArray?,
        denseCount: Int,
    ): Boolean =
        when {
            trace.hasFloorCells -> updateRecorded(trace, trace.floorCellsAt(time))
            dense != null -> update(dense, denseCount)
            else -> update(mapPoints())
        }

    fun reset() {
        counts.clear()
        coveredCells.clear()
        consumed = 0
        cellsConsumed = 0
        resets++
        minCx = 0
        maxCx = -1
        minCz = 0
        maxCz = -1
        version++
    }

    fun isCovered(cx: Int, cz: Int): Boolean = key(cx, cz) in coveredCells

    /** A covered cell with an uncovered 4-neighbour: the edge of the scan. */
    fun isFrontier(cx: Int, cz: Int): Boolean =
        isCovered(cx, cz) &&
            (!isCovered(cx - 1, cz) || !isCovered(cx + 1, cz) || !isCovered(cx, cz - 1) || !isCovered(cx, cz + 1))

    /** Every covered cell, in the order they were covered. */
    inline fun forEachCovered(block: (cx: Int, cz: Int) -> Unit) {
        for (key in keys()) block(cxOf(key), czOf(key))
    }

    @PublishedApi
    internal fun keys(): Iterable<Long> = coveredCells

    /**
     * The covered share of the scan's own footprint, `0..1`: covered cells over the cells of the
     * box around them. A room swept wall to wall reads near 1; a single wall reads low, which is
     * right — most of the floor between the walls is not there yet.
     */
    val coveredShare: Float
        get() {
            if (coveredCells.isEmpty()) return 0f
            val box = (maxCx - minCx + 1).toLong() * (maxCz - minCz + 1).toLong()
            return (coveredCells.size.toFloat() / box).coerceIn(0f, 1f)
        }

    /**
     * The room's floor as far as the scan can tell, (x, z) counter-clockwise: the convex hull of
     * the floor planes' outlines ([floorPolygons], world xyz triples) and of every covered cell.
     * What lies inside it and is not covered is what is still to scan. Empty before anything is
     * covered or found.
     */
    fun floorExtent(floorPolygons: List<FloatArray>): List<Pair<Float, Float>> {
        val points = ArrayList<Pair<Float, Float>>()
        for (polygon in floorPolygons) {
            for (i in 0 until polygon.size / 3) points += polygon[i * 3] to polygon[i * 3 + 2]
        }
        // A covered cell's corners only matter on the frontier: an inner cell's are shared.
        forEachCovered { cx, cz ->
            if (isFrontier(cx, cz)) {
                val x = cx * cellMetres
                val z = cz * cellMetres
                points += x to z
                points += x + cellMetres to z
                points += x to z + cellMetres
                points += x + cellMetres to z + cellMetres
            }
        }
        return RoomDollhouse.convexHull(points)
    }

    /**
     * The covered share of [extent] (see [floorExtent]), `0..1`: covered cells over the cells
     * whose centre lies inside it. `0` for an extent with no area.
     */
    fun coveredShareOf(extent: List<Pair<Float, Float>>): Float {
        if (extent.size < 3) return 0f
        val minX = extent.minOf { it.first }
        val maxX = extent.maxOf { it.first }
        val minZ = extent.minOf { it.second }
        val maxZ = extent.maxOf { it.second }
        var inside = 0
        var covered = 0
        for (cx in cellOf(minX)..cellOf(maxX)) for (cz in cellOf(minZ)..cellOf(maxZ)) {
            val x = (cx + HALF) * cellMetres
            val z = (cz + HALF) * cellMetres
            if (!insideConvex(extent, x, z)) continue
            inside++
            if (isCovered(cx, cz)) covered++
        }
        return if (inside == 0) 0f else (covered.toFloat() / inside).coerceIn(0f, 1f)
    }

    /** The cell index of a world coordinate. */
    fun cellOf(metres: Float): Int = kotlin.math.floor(metres / cellMetres).toInt()

    private fun include(cx: Int, cz: Int) {
        if (maxCx < minCx) {
            minCx = cx
            maxCx = cx
            minCz = cz
            maxCz = cz
            return
        }
        if (cx < minCx) minCx = cx
        if (cx > maxCx) maxCx = cx
        if (cz < minCz) minCz = cz
        if (cz > maxCz) maxCz = cz
    }

    companion object {
        /** A quarter of a metre: a chair's seat is a cell, a room is a few hundred. */
        const val CELL_METRES = 0.25f

        /** Points before a cell counts: one stray depth sample is not a surface. */
        const val MIN_POINTS = 3

        /**
         * The cells [points]' first [count] surfels (xyz triples) fill with [minPoints] or more,
         * [key]-packed, each once: a sort, no map, so it costs a worker a few milliseconds for a
         * live snapshot's 150,000 surfels. Off the main thread.
         */
        fun coveredKeys(
            points: FloatArray,
            count: Int = points.size / 3,
            cellMetres: Float = CELL_METRES,
            minPoints: Int = MIN_POINTS,
        ): LongArray {
            val total = count.coerceIn(0, points.size / 3)
            val keys = LongArray(total) { i ->
                key(floor(points[i * 3] / cellMetres).toInt(), floor(points[i * 3 + 2] / cellMetres).toInt())
            }
            keys.sort()
            val covered = LongArray(total)
            var n = 0
            var i = 0
            while (i < total) {
                var j = i
                while (j < total && keys[j] == keys[i]) j++
                if (j - i >= minPoints) covered[n++] = keys[i]
                i = j
            }
            return covered.copyOf(n)
        }

        @PublishedApi
        internal fun key(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)

        @PublishedApi
        internal fun cxOf(key: Long): Int = (key shr 32).toInt()

        @PublishedApi
        internal fun czOf(key: Long): Int = key.toInt()

        private const val HALF = 0.5f

        /** Whether (x, z) lies inside the counter-clockwise convex [polygon], its edge included. */
        internal fun insideConvex(polygon: List<Pair<Float, Float>>, x: Float, z: Float): Boolean {
            for (i in polygon.indices) {
                val (ax, az) = polygon[i]
                val (bx, bz) = polygon[(i + 1) % polygon.size]
                if ((bx - ax) * (z - az) - (bz - az) * (x - ax) < 0f) return false
            }
            return true
        }
    }
}

/**
 * The floor a scan covered, in m² (#4734): covered cells times a cell's area, to a tenth. The
 * one figure the live map, the replay and the result say, each off the same cells — the live
 * capture records them in its trace ([ArDebugTrace.addFloorCells]) and the other two read them
 * back ([CoverageGrid.updateSaved]). Never of how much: a share of a room whose outline grows
 * with the scan fell as the scan went on.
 */
object ScanFloorArea {
    fun squareMetres(cells: Int, cellMetres: Float = CoverageGrid.CELL_METRES): Float =
        (cells * cellMetres * cellMetres * TENTHS).roundToInt() / TENTHS

    /** A saved scan's floor covered at its end: what its replay reaches, what its result says. */
    fun ofSaved(trace: ArDebugTrace, dense: FloatArray?, denseCount: Int): Float {
        val grid = CoverageGrid()
        val end = trace.duration
        grid.updateSaved(trace, end, { trace.frameAt(end).mapPoints }, dense, denseCount)
        return grid.squareMetres
    }

    private const val TENTHS = 10f
}

/**
 * One coach line at a time while scanning, RoomPlan's way (#4631): short, imperative, and silent
 * by default. Two things it says, from the phone's own movement and whether the grid still grows:
 *
 * - [Line.Slower] — the phone moves faster than [MAX_SPEED_M_PER_S]; depth does not keep up.
 * - [Line.NothingNew] — moving or turning, yet no cell became covered for [STALL_SECONDS]: the
 *   surfaces are too close, too far, too plain, or it is pointed at nothing.
 * - [Line.Still] — standing still, not turning, and nothing new for [STILL_SECONDS]: the scan waits for the
 *   phone to move (#4540 — "0 points" sat frozen for a minute with nothing said).
 *
 * A line holds [holdSeconds] once shown so it can be read, and goes when its cause has gone.
 */
class ScanCoach(private val holdSeconds: Float = HOLD_SECONDS) {

    enum class Line { Slower, NothingNew, Still }

    /** The line to show now, `null` for none. */
    var line: Line? = null
        private set

    /** The phone's speed over the floor, smoothed, in m/s. */
    var speed = 0f
        private set

    /**
     * How fast the way the phone looks turns, smoothed, in degrees a second — left and right, up
     * and down: sweeping a wall top to bottom is moving too. The view direction itself, not a
     * heading: pointed at the floor, a heading spins with the slightest roll and faked a turn.
     */
    var turnRate = 0f
        private set

    /**
     * Whether the phone looks at the walls rather than at the floor or the ceiling: more than
     * [LEVEL_DEGREES] up or down, a plain wall is not what it sees.
     */
    var facingWall = true
        private set

    private var lastTime = Float.NaN
    private var lastX = 0f
    private var lastZ = 0f
    private val lastLook = FloatArray(3)
    private var looked = false
    private var grewAt = Float.NaN
    private var shownAt = Float.NaN

    /** A frame at [time] with the phone at [camera]; [grew] when the coverage grid grew on it. */
    fun update(time: Float, camera: DebugPose?, grew: Boolean): Line? {
        if (camera == null || !time.isFinite()) return line
        if (lastTime.isNaN()) {
            lastTime = time
            lastX = camera.x
            lastZ = camera.z
            look(camera)
            grewAt = time
            return line
        }
        val dt = time - lastTime
        if (dt < 0f) {
            reset()
            return update(time, camera, grew)
        }
        if (dt > 0f) {
            val dx = camera.x - lastX
            val dz = camera.z - lastZ
            val instant = sqrt(dx * dx + dz * dz) / dt
            val blend = (dt / SMOOTHING_SECONDS).coerceAtMost(1f)
            speed += (instant - speed) * blend
            turnRate += (look(camera) / dt - turnRate) * blend
            lastTime = time
            lastX = camera.x
            lastZ = camera.z
        }
        if (grew) grewAt = time
        val wanted = when {
            speed > MAX_SPEED_M_PER_S -> Line.Slower
            (speed > MOVING_M_PER_S || turnRate > TURNING_DEGREES_PER_S) && time - grewAt > STALL_SECONDS ->
                Line.NothingNew
            time - grewAt > STILL_SECONDS -> Line.Still
            else -> null
        }
        val held = line != null && !shownAt.isNaN() && time - shownAt < holdSeconds
        if (wanted != line && !held) {
            line = wanted
            shownAt = if (wanted == null) Float.NaN else time
        }
        return line
    }

    /**
     * Takes [pose]'s view direction as the last one, sets [facingWall] off it, and returns the
     * angle it turned from the one before, in degrees (`0` for the first).
     */
    private fun look(pose: DebugPose): Float {
        val f = pose.rotate(0f, 0f, -1f)
        val norm = sqrt(f.x * f.x + f.y * f.y + f.z * f.z)
        if (norm < TINY) return 0f
        val x = f.x / norm
        val y = f.y / norm
        val z = f.z / norm
        facingWall = abs(y) <= sin(LEVEL_DEGREES / DEGREES_PER_RADIAN)
        val turned = if (looked) {
            acos((x * lastLook[0] + y * lastLook[1] + z * lastLook[2]).coerceIn(-1f, 1f)) * DEGREES_PER_RADIAN
        } else {
            0f
        }
        lastLook[0] = x
        lastLook[1] = y
        lastLook[2] = z
        looked = true
        return turned
    }

    fun reset() {
        line = null
        speed = 0f
        turnRate = 0f
        facingWall = true
        lastTime = Float.NaN
        looked = false
        grewAt = Float.NaN
        shownAt = Float.NaN
    }

    companion object {
        /** `duration-long` of DESIGN.md three times over: a line is read, not glimpsed. */
        const val HOLD_SECONDS = 2.1f

        /** Faster than this, depth frames smear: a brisk walk is 1.4 m/s. */
        const val MAX_SPEED_M_PER_S = 1.2f

        /** Slower than this the phone is standing still, and nothing new is expected. */
        const val MOVING_M_PER_S = 0.15f

        private const val TINY = 1e-6f

        /** Turning faster than this on the spot is looking round, not standing still. */
        const val TURNING_DEGREES_PER_S = 8f

        /** Moving this long without a new cell is pointing at nothing. */
        const val STALL_SECONDS = 3f

        /** Still this long with nothing new, the scan is waiting on the phone to move. */
        const val STILL_SECONDS = 5f

        /** The speed's time constant: a hand's tremor out, a stride in. */
        const val SMOOTHING_SECONDS = 0.5f

        /** Further up or down than this, the phone looks at the floor or the ceiling. */
        const val LEVEL_DEGREES = 45f

        private const val DEGREES_PER_RADIAN = (180.0 / PI).toFloat()
    }
}
