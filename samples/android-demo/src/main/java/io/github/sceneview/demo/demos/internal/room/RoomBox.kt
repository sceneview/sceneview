package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.DenseCloud
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * A room as a box square to its walls: [yaw] turns the world about Y into the room's own frame —
 * `a = x·cos + z·sin`, `b = −x·sin + z·cos` — where its walls stand at `a = [a0]`, `a = [a1]`,
 * `b = [b0]` and `b = [b1]`, its floor at `y = [floorY]` and its ceiling at `y = [ceilingY]`.
 */
internal class RoomBox(
    val yaw: Float,
    val a0: Float,
    val a1: Float,
    val b0: Float,
    val b1: Float,
    val floorY: Float,
    val ceilingY: Float,
) {
    private val c = cos(yaw)
    private val s = sin(yaw)

    /** World ([x], [z]) along the room's first axis. */
    fun a(x: Float, z: Float): Float = x * c + z * s

    /** World ([x], [z]) along the room's second axis. */
    fun b(x: Float, z: Float): Float = -x * s + z * c

    /** World x of the room-frame point ([a], [b]). */
    fun worldX(a: Float, b: Float): Float = a * c - b * s

    /** World z of the room-frame point ([a], [b]). */
    fun worldZ(a: Float, b: Float): Float = a * s + b * c

    val width: Float get() = a1 - a0
    val depth: Float get() = b1 - b0
    val height: Float get() = ceilingY - floorY

    override fun toString(): String =
        "RoomBox(yaw ${"%.1f".format(Math.toDegrees(yaw.toDouble()))}°, " +
            "${"%.2f".format(width)} × ${"%.2f".format(depth)} × ${"%.2f".format(height)} m)"

    companion object {
        /**
         * The box of [cloud], its floor at [floorY] (the manifest's; read off the cloud's floor when
         * `null`), or `null` when the cloud does not stand as a room: no normals, fewer than two
         * strong wall peaks along an axis, or a side or height under [MIN_SIDE_M].
         *
         * 1. The room's frame: the dominant direction of the points facing sideways, folded by a
         *    quarter turn (a wall and the wall beside it say the same).
         * 2. Its walls: along each axis, the histogram of the points facing that way ([BIN_M]
         *    bins, smoothed over three), its outermost bins over [PEAK_SHARE] of its peak, each
         *    moved to the local peak within [REFINE_BINS].
         * 3. Its ceiling: the median height of the points facing down over [CEILING_MIN_M] above
         *    the floor, or the cloud's [TOP_QUANTILE] when fewer than [MIN_CEILING_POINTS] are.
         */
        @Suppress("ReturnCount")
        fun of(cloud: DenseCloud, floorY: Float?): RoomBox? {
            val normals = cloud.normals ?: return null
            val n = cloud.count
            if (n < MIN_POINTS) return null
            val p = cloud.positions
            val floor = floorY ?: floorOf(cloud) ?: return null
            val yaw = yawOf(normals, n)
            val c = cos(yaw)
            val s = sin(yaw)
            val qa = FloatArray(n) { p[it * 3] * c + p[it * 3 + 2] * s }
            val qb = FloatArray(n) { -p[it * 3] * s + p[it * 3 + 2] * c }
            val na = FloatArray(n) { normals[it * 3] * c + normals[it * 3 + 2] * s }
            val nb = FloatArray(n) { -normals[it * 3] * s + normals[it * 3 + 2] * c }
            val wallsA = walls(qa, na, p, floor) ?: return null
            val wallsB = walls(qb, nb, p, floor) ?: return null
            val ceiling = ceilingOf(cloud, floor)
            val box = RoomBox(yaw, wallsA.first, wallsA.second, wallsB.first, wallsB.second, floor, ceiling)
            if (box.width < MIN_SIDE_M || box.depth < MIN_SIDE_M || box.height < MIN_SIDE_M) return null
            return box
        }

        /** The dominant sideways normal's angle, folded into [0, π/2), at its bin's centre. */
        private fun yawOf(normals: FloatArray, n: Int): Float {
            val bins = IntArray(YAW_BINS)
            val quarter = PI / 2
            for (i in 0 until n) {
                if (abs(normals[i * 3 + 1]) >= SIDEWAYS_MAX_NY) continue
                var angle = atan2(normals[i * 3 + 2].toDouble(), normals[i * 3].toDouble()) % quarter
                if (angle < 0) angle += quarter
                bins[(angle / quarter * YAW_BINS).toInt().coerceIn(0, YAW_BINS - 1)]++
            }
            var best = 0
            var bestCount = -1
            for (k in 0 until YAW_BINS) {
                val count = bins[k] + bins[(k + 1) % YAW_BINS] + bins[(k + YAW_BINS - 1) % YAW_BINS]
                if (count > bestCount) {
                    bestCount = count
                    best = k
                }
            }
            return ((best + 0.5) * quarter / YAW_BINS).toFloat()
        }

        /**
         * The two outer walls along one axis: [q] the points' coordinate on it, [nq] their normals'.
         * `null` with fewer than two peaks, or none at all.
         */
        @Suppress("ReturnCount")
        private fun walls(q: FloatArray, nq: FloatArray, p: FloatArray, floorY: Float): Pair<Float, Float>? {
            val n = q.size
            val sorted = q.copyOf().also { it.sort() }
            val lo = quantile(sorted, LOW_QUANTILE) - BOX_PAD_M
            val hi = quantile(sorted, 1f - LOW_QUANTILE) + BOX_PAD_M
            val bins = floor((hi - lo) / BIN_M).toInt()
            if (bins < 2) return null
            val counts = FloatArray(bins)
            var facing = 0
            for (i in 0 until n) {
                if (abs(nq[i]) <= WALL_MIN_NORMAL || p[i * 3 + 1] <= floorY + WALL_MIN_HEIGHT_M) continue
                val k = floor((q[i] - lo) / BIN_M).toInt()
                if (k in 0 until bins) {
                    counts[k]++
                    facing++
                }
            }
            if (facing < MIN_WALL_POINTS) return null
            val smooth = FloatArray(bins) { k ->
                (counts[(k - 1).coerceAtLeast(0)] + counts[k] + counts[(k + 1).coerceAtMost(bins - 1)]) / 3f
            }
            val peak = smooth.max()
            val threshold = PEAK_SHARE * peak
            var first = -1
            var last = -1
            var runs = 0
            var inRun = false
            for (k in 0 until bins) {
                val over = smooth[k] > threshold
                if (over && !inRun) runs++
                inRun = over
                if (over) {
                    if (first < 0) first = k
                    last = k
                }
            }
            if (runs < 2 || first == last) return null
            fun refined(k: Int): Float {
                var best = (k - REFINE_BINS).coerceAtLeast(0)
                for (j in best..(k + REFINE_BINS).coerceAtMost(bins - 1)) if (smooth[j] > smooth[best]) best = j
                return lo + best * BIN_M + BIN_M / 2
            }
            return refined(first) to refined(last)
        }

        private fun ceilingOf(cloud: DenseCloud, floorY: Float): Float {
            val normals = checkNotNull(cloud.normals)
            val p = cloud.positions
            val down = ArrayList<Float>()
            for (i in 0 until cloud.count) {
                if (normals[i * 3 + 1] < CEILING_MAX_NY && p[i * 3 + 1] > floorY + CEILING_MIN_M) down += p[i * 3 + 1]
            }
            if (down.size > MIN_CEILING_POINTS) return down.sorted()[down.size / 2]
            val heights = FloatArray(cloud.count) { p[it * 3 + 1] }.also { it.sort() }
            return quantile(heights, TOP_QUANTILE)
        }

        /** The lowest strong band of points facing up: a cloud's floor, for a scan with none in its manifest. */
        private fun floorOf(cloud: DenseCloud): Float? {
            val normals = cloud.normals ?: return null
            val p = cloud.positions
            val b = cloud.bounds()
            val bins = floor((b[4] - b[1]) / BIN_M).toInt() + 1
            val counts = IntArray(bins)
            for (i in 0 until cloud.count) {
                if (normals[i * 3 + 1] > FLOOR_MIN_NY) counts[((p[i * 3 + 1] - b[1]) / BIN_M).toInt().coerceIn(0, bins - 1)]++
            }
            val peak = counts.max()
            if (peak == 0) return null
            val k = counts.indexOfFirst { it > peak * PEAK_SHARE }
            return b[1] + (k + 0.5f) * BIN_M
        }

        /** [sorted]'s [share] quantile, linearly interpolated between its neighbours (numpy's default). */
        fun quantile(sorted: FloatArray, share: Float): Float {
            if (sorted.isEmpty()) return 0f
            val at = share * (sorted.size - 1)
            val i = at.toInt().coerceIn(0, sorted.size - 1)
            val j = (i + 1).coerceAtMost(sorted.size - 1)
            return sorted[i] + (sorted[j] - sorted[i]) * (at - i)
        }

        /** Fewer points than this make no room. */
        const val MIN_POINTS = 1_000

        /** A room's sides and height are at least this: a box smaller is no room to stand in. */
        const val MIN_SIDE_M = 1.5f

        /** A wall histogram's bin. */
        const val BIN_M = 0.02f

        /** The share of the histogram's peak a wall's bin passes. */
        const val PEAK_SHARE = 0.25f

        /** How far, in bins, an outer wall moves to its local peak. */
        const val REFINE_BINS = 5

        /** A point faces a wall's way when its normal is at least this along the axis. */
        const val WALL_MIN_NORMAL = 0.8f

        /** Only points this far over the floor count for a wall: skirting, rugs and feet left out. */
        const val WALL_MIN_HEIGHT_M = 0.3f

        /** A point faces sideways for the room's frame when its normal's Y is under this. */
        const val SIDEWAYS_MAX_NY = 0.3f

        /** A point faces down, for the ceiling, when its normal's Y is under this. */
        const val CEILING_MAX_NY = -0.85f

        /** A ceiling point is at least this far over the floor. */
        const val CEILING_MIN_M = 1.8f

        /** Fewer ceiling points than this and the cloud's top stands for it. */
        const val MIN_CEILING_POINTS = 500

        /** The cloud's top, for a ceiling not seen. */
        const val TOP_QUANTILE = 0.995f
        private const val LOW_QUANTILE = 0.005f
        private const val BOX_PAD_M = 0.1f
        private const val YAW_BINS = 90
        private const val MIN_WALL_POINTS = 200
        private const val FLOOR_MIN_NY = 0.85f
    }
}
