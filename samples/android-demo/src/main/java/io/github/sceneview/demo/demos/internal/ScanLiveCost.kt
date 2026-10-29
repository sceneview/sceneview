package io.github.sceneview.demo.demos.internal

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * What keeps 3D Scan's live screen at the Pixel 4a's frame rate (#4743): the live view hears a
 * frame only when it is a new one, and the floor map's plan paper is cut once per coverage change
 * instead of clipped by two paths on every draw.
 */

/**
 * Lets the live view's `observe` through only when it has something new (#4743). The 3D view
 * rebuilds its frame from the trace at most 20 times a second and hands the same frame on to
 * every render in between: about half the calls repeated the coverage, the walls and the guide
 * on a frame they had already seen. A call is new when the [frame] is another object, the phone's
 * [pose] moved (paused, the live pose moves under a frame that does not), or a new dense
 * [snapshot] came in.
 */
class ScanObserveGate {
    private var frame: Any? = null
    private var pose: Any? = null
    private var snapshot: Any? = null
    private var seen = false

    /** `true` when this call differs from the last one let through, which it then becomes. */
    fun isNew(frame: Any, pose: Any?, snapshot: Any?): Boolean {
        if (seen && frame === this.frame && pose == this.pose && snapshot === this.snapshot) return false
        seen = true
        this.frame = frame
        this.pose = pose
        this.snapshot = snapshot
        return true
    }

    /** Forgets the last call: the next one goes through. */
    fun reset() {
        seen = false
        frame = null
        pose = null
        snapshot = null
    }
}

/**
 * The floor map's plan paper (#4540), as plain segments in world metres (#4743): grid lines every
 * `stepCells` coverage cells, cut to the room's convex floor outline and stopped where the cells
 * on both sides are covered — what clipping the grid by the outline and then by the covered cells
 * drew, without a path clip on every frame. [minor] and [major] hold `x0, z0, x1, z1` per
 * segment; every `majorEvery`th line is major, the way a plan marks its metres.
 */
class ScanMapHatch(val minor: FloatArray, val major: FloatArray) {

    companion object {
        val Empty = ScanMapHatch(FloatArray(0), FloatArray(0))

        /**
         * The grid over [extent] (a convex outline, x z), [cellMetres] a cell, a line every
         * [stepCells] cells anchored on whole multiples of it so it stays put as the room grows.
         * [covered] says whether cell `(cx, cz)` is covered.
         */
        @Suppress("LongParameterList") // the grid's pitch and the coverage it is cut by
        fun of(
            extent: List<Pair<Float, Float>>,
            cellMetres: Float,
            stepCells: Int,
            majorEvery: Int,
            covered: (cx: Int, cz: Int) -> Boolean,
        ): ScanMapHatch {
            if (extent.size < 3 || stepCells <= 0 || cellMetres <= 0f) return Empty
            val minor = Segments()
            val major = Segments()
            val step = cellMetres * stepCells
            // Lines of constant x, then of constant z: the same walk with the axes swapped.
            for (across in 0..1) {
                val lo = extent.minOf { if (across == 0) it.first else it.second }
                val hi = extent.maxOf { if (across == 0) it.first else it.second }
                var i = floor(lo / step).toInt()
                while (i * step <= hi) {
                    val k = i * stepCells
                    val at = k * cellMetres
                    val span = spanAt(extent, at, across)
                    if (span != null) {
                        val out = if (Math.floorMod(i, majorEvery) == 0) major else minor
                        cut(out, at, span.first, span.second, k, cellMetres, across, covered)
                    }
                    i++
                }
            }
            return ScanMapHatch(minor.toArray(), major.toArray())
        }

        /**
         * Where the line `axis = at` crosses the convex [extent]: the interval along the other
         * axis, `null` when it misses it. [across] `0` is a line of constant x, `1` of constant z.
         */
        internal fun spanAt(extent: List<Pair<Float, Float>>, at: Float, across: Int): Pair<Float, Float>? {
            var lo = Float.POSITIVE_INFINITY
            var hi = Float.NEGATIVE_INFINITY
            for (n in extent.indices) {
                val (ax, az) = extent[n]
                val (bx, bz) = extent[(n + 1) % extent.size]
                val a = if (across == 0) ax else az
                val b = if (across == 0) bx else bz
                val aOther = if (across == 0) az else ax
                val bOther = if (across == 0) bz else bx
                if ((a - at) * (b - at) > 0f) continue
                if (a == b) {
                    if (a != at) continue
                    lo = min(lo, min(aOther, bOther))
                    hi = max(hi, max(aOther, bOther))
                } else {
                    val t = (at - a) / (b - a)
                    val v = aOther + (bOther - aOther) * t
                    lo = min(lo, v)
                    hi = max(hi, v)
                }
            }
            return if (lo <= hi) lo to hi else null
        }

        /**
         * The line `axis = at`, between cell lines `k - 1` and `k`, from [from] to [to] along the
         * other axis: kept cell by cell, except where both cells it runs between are covered.
         */
        @Suppress("LongParameterList")
        private fun cut(
            out: Segments,
            at: Float,
            from: Float,
            to: Float,
            k: Int,
            cellMetres: Float,
            across: Int,
            covered: (cx: Int, cz: Int) -> Boolean,
        ) {
            var start = Float.NaN
            var j = floor(from / cellMetres).toInt()
            while (j * cellMetres < to) {
                val hidden = if (across == 0) {
                    covered(k - 1, j) && covered(k, j)
                } else {
                    covered(j, k - 1) && covered(j, k)
                }
                val a = max(from, j * cellMetres)
                if (hidden) {
                    if (!start.isNaN()) out.add(at, start, a, across)
                    start = Float.NaN
                } else if (start.isNaN()) {
                    start = a
                }
                j++
            }
            if (!start.isNaN() && to > start) out.add(at, start, to, across)
        }
    }

    private class Segments {
        private var values = FloatArray(INITIAL)
        private var size = 0

        fun add(at: Float, from: Float, to: Float, across: Int) {
            if (size + SEGMENT > values.size) values = values.copyOf(values.size * 2)
            if (across == 0) {
                values[size] = at
                values[size + 1] = from
                values[size + 2] = at
                values[size + 3] = to
            } else {
                values[size] = from
                values[size + 1] = at
                values[size + 2] = to
                values[size + 3] = at
            }
            size += SEGMENT
        }

        fun toArray(): FloatArray = values.copyOf(size)

        private companion object {
            const val SEGMENT = 4
            const val INITIAL = 64
        }
    }
}
