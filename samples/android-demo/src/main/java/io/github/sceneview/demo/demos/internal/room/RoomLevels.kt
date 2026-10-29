package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.stopIfInterrupted
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The room's levels and turn: the floor (the lowest well-seen layer of upward-facing surfels
 * under the camera, not the lowest horizontal plane nor the densest one), the ceiling above it,
 * and the turn that squares the room's main walls.
 */
internal object RoomLevels {
    /**
     * The lowest horizontal layer holding this share of the densest one's surfels is the floor: a
     * bed or a table scanned closely can outweigh the floor around it, a stray patch under the
     * floor cannot weigh a quarter of it.
     */
    const val FLOOR_RIVAL_SHARE = 0.25f

    /** A ceiling is a height only once this much of it was seen. */
    const val MIN_CEILING_AREA_M2 = 1f

    /**
     * The lowest a ceiling is looked for above the floor. A wall cupboard's underside sits lower,
     * so an attic ceiling under it gives no height ([RoomGap.Ceiling]) rather than a wrong one.
     */
    const val MIN_CEILING_M = 2f

    /** Ceiling seen this far from the height that is given, over [MIN_CEILING_AREA_M2], is a second level. */
    const val CEILING_LEVEL_M = 0.2f

    /** No figure is given tighter than this, however thin the layer read. */
    const val MIN_SPREAD_M = 0.02f

    const val UP = 0.8f
    const val VERTICAL = 0.26f
    const val WALL_FROM_M = 0.2f
    const val CEILING_MAX_M = 6f
    const val MIN_SURFELS = 500

    private const val BIN_M = 0.02f
    private const val SMOOTH_BINS = 5
    private const val FLOOR_BAND_BINS = 13
    private const val FLOOR_ABOVE_CAMERA_M = 0.25f
    private const val FLOOR_BELOW_CAMERA_M = 2.5f
    private const val CEILING_BAND_M = 0.1f
    private const val HALF = 0.5f
    private const val TAIL = 0.05
    private const val QUARTERS = 4f
    private const val MAX_BINS = 1 shl 17

    /** The floor: its height, the thickness of its layer, and whether a denser layer lies above it. */
    class Floor(val y: Float, val thickness: Float, val rival: Boolean)

    /**
     * The lowest layer of upward-facing surfels under the camera that holds [FLOOR_RIVAL_SHARE]
     * of the densest one. Its height is the layer's mean over a band wide enough for the layer a
     * drifting scan thickens, narrower than the lowest seat. `rival`: a denser layer lies above
     * it — a bed, a table — so the height read from it is an estimate.
     */
    @Suppress("LoopWithTooManyJumpStatements", "CyclomaticComplexMethod", "ReturnCount")
    fun floorOf(positions: FloatArray, normals: FloatArray, trail: FloatArray): Floor? {
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
        // A layer this wide is a broken cloud, not a room: no histogram is sized on it.
        if (bins < 2 || bins > MAX_BINS) return null
        val hist = IntArray(bins)
        for (i in 0 until n) {
            if (normals[i * 3 + 1] < UP) continue
            val y = positions[i * 3 + 1]
            if (y < lo || y >= hi) continue
            hist[((y - lo) / BIN_M).toInt().coerceIn(0, bins - 1)]++
        }
        val windows = windowsOf(hist, SMOOTH_BINS)
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
        return Floor((sum / weight).toFloat(), (min(high, to) - low + 1) * BIN_M, rival)
    }

    /** The turn that squares the room: the direction the vertical surfels face, folded over a quarter turn. */
    @Suppress("LoopWithTooManyJumpStatements")
    fun yawOf(positions: FloatArray, normals: FloatArray, floorY: Float): Float {
        var sx = 0.0
        var sy = 0.0
        for (i in 0 until positions.size / 3) {
            stopIfInterrupted()
            if (abs(normals[i * 3 + 1]) > VERTICAL) continue
            val h = positions[i * 3 + 1] - floorY
            if (h < WALL_FROM_M || h > CEILING_MAX_M) continue
            val a = atan2(normals[i * 3 + 2], normals[i * 3]) * QUARTERS
            sx += cos(a)
            sy += sin(a)
        }
        return (atan2(sy, sx) / QUARTERS).toFloat()
    }

    /**
     * The ceiling's height above the [floor]: the densest layer of downward-facing surfels
     * ([heights] above the floor, each over the plan cell [cells]) at [MIN_CEILING_M] or more,
     * once [MIN_CEILING_AREA_M2] of it was seen over the room ([inside]). Its spread carries the
     * floor's thickness: the height is read between two thick layers. It is an estimate when the
     * room has more than one to give — a second level [CEILING_LEVEL_M] away, or a floor that was
     * not the densest layer under the camera.
     */
    @Suppress("LoopWithTooManyJumpStatements", "ReturnCount")
    fun ceilingOf(
        heights: FloatArray,
        cells: IntArray,
        count: Int,
        cellArea: Float,
        floor: Floor,
        inside: (Int) -> Boolean,
    ): RoomFigure? {
        if (count == 0) return null
        val hist = IntArray(((CEILING_MAX_M - MIN_CEILING_M) / BIN_M).toInt() + 1)
        for (i in 0 until count) {
            if (inside(cells[i])) hist[((heights[i] - MIN_CEILING_M) / BIN_M).toInt().coerceIn(0, hist.size - 1)]++
        }
        val peak = peakOf(windowsOf(hist, SMOOTH_BINS), 0, hist.size - 1)
            ?.let { MIN_CEILING_M + (it + HALF) * BIN_M } ?: return null
        val seen = HashSet<Int>()
        val elsewhere = HashSet<Int>()
        var sum = 0.0
        var squares = 0.0
        var n = 0
        for (i in 0 until count) {
            if (!inside(cells[i])) continue
            if (abs(heights[i] - peak) > CEILING_LEVEL_M) elsewhere += cells[i]
            if (abs(heights[i] - peak) > CEILING_BAND_M) continue
            seen += cells[i]
            sum += heights[i]
            squares += heights[i].toDouble() * heights[i]
            n++
        }
        if (seen.size * cellArea < MIN_CEILING_AREA_M2) return null
        val mean = sum / n
        val spread = sqrt(max(0.0, squares / n - mean * mean)).toFloat()
        val levels = elsewhere.size * cellArea >= MIN_CEILING_AREA_M2
        return RoomFigure.of(mean.toFloat(), max(MIN_SPREAD_M, floor.thickness / 2 + spread), levels || floor.rival)
    }

    /** [hist] summed over ± [radius] bins around each. */
    fun windowsOf(hist: IntArray, radius: Int): LongArray {
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
    fun peakOf(windows: LongArray, from: Int, to: Int): Int? {
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
}
