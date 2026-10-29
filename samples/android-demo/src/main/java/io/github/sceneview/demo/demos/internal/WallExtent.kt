package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * How much of each live wall the scan has actually seen (QA 2026-10-22, remark 3): a wall drawn
 * where no point stands on it is a guess, and a rectangle wider than the wall reads as a wrong
 * one. ARCore grows a vertical plane past what the camera saw, two fits joined span both, and a
 * corner join stretches a wall toward its neighbour: the drawn wall is cut back to the span of the
 * scan's points lying on it, [MARGIN_M] past each end.
 *
 * A wall with fewer than [MIN_POINTS] points on it — a plain wall the depth missed, a phone
 * without depth — is kept as ARCore found it: no points is no evidence either way. A wall cut
 * back gets the confidence of what is left ([LiveWall.confidence]), which is what asks the user
 * to move along it ([facingUnfinished]).
 *
 * Pure geometry, run in a JVM test.
 */
object WallExtent {
    /** A point this close to a wall's plane, in metres, is on the wall. */
    const val ACROSS_M = 0.10f

    /** A point this far over the floor, and under the wall's top, counts: the floor's own points do not. */
    const val ABOVE_FLOOR_M = 0.15f

    /** Points needed on a wall before its span is believed. */
    const val MIN_POINTS = 12

    /** How far past the outermost points the wall is still drawn: a mark's own size, a little more. */
    const val MARGIN_M = 0.15f

    /** A gap along the wall this wide, with no point, splits the points into two runs; the fuller one wins. */
    const val GAP_M = 0.8f

    /** The share of points dropped at each end of a run: one stray point does not stretch the wall. */
    const val TRIM = 0.02f

    /** At most this many points of a cloud are read: a dense cloud is strided down to it. */
    const val MAX_READ = 40_000

    /** A wall is unfinished when it is drawn this much shorter, in metres, than the plane ARCore fitted. */
    const val UNSEEN_M = 0.3f

    /** …or when it is a sliver: under this confidence ([LiveWall.confidence]), 0.75 m seen. */
    const val SLIVER = 0.5f

    /** How far ahead of the phone, in metres, a wall it faces may stand for the hint. */
    const val FACING_RANGE_M = 5f

    /**
     * [walls] cut back to the [clouds]' points lying on them (flat xyz each), over a floor at
     * [floorY], [height] tall. The same list when no wall changed, the same [LiveWall] for a wall
     * left as it was, so the caller can keep its snapshot.
     */
    fun clip(walls: List<LiveWall>, clouds: List<FloatArray>, floorY: Float, height: Float): List<LiveWall> {
        if (walls.isEmpty()) return walls
        val along = Array(walls.size) { FloatList() }
        val bottom = floorY + ABOVE_FLOOR_M
        val top = floorY + max(height, ABOVE_FLOOR_M * 2f)
        // Per wall: unit direction and length, flat, for the point loop.
        val ux = FloatArray(walls.size)
        val uz = FloatArray(walls.size)
        val len = FloatArray(walls.size)
        walls.forEachIndexed { i, w ->
            val l = w.length
            len[i] = l
            if (l > EPSILON) {
                ux[i] = (w.bx - w.ax) / l
                uz[i] = (w.bz - w.az) / l
            }
        }
        for (cloud in clouds) {
            val n = cloud.size / 3
            val stride = max(1, n / MAX_READ)
            var p = 0
            while (p < n) {
                val y = cloud[p * 3 + 1]
                if (y in bottom..top) {
                    val x = cloud[p * 3]
                    val z = cloud[p * 3 + 2]
                    for (i in walls.indices) {
                        if (len[i] <= EPSILON) continue
                        val w = walls[i]
                        val dx = x - w.ax
                        val dz = z - w.az
                        if (abs(-uz[i] * dx + ux[i] * dz) > ACROSS_M) continue
                        val t = ux[i] * dx + uz[i] * dz
                        if (t < -MARGIN_M || t > len[i] + MARGIN_M) continue
                        along[i].add(t)
                    }
                }
                p += stride
            }
        }
        var changed = false
        val out = walls.mapIndexed { i, w ->
            val span = span(along[i]) ?: return@mapIndexed w
            val from = max(0f, span.first - MARGIN_M)
            val to = min(len[i], span.second + MARGIN_M)
            if (from <= CUT_EPSILON_M && to >= len[i] - CUT_EPSILON_M) return@mapIndexed w
            changed = true
            val kept = max(to - from, 0f)
            w.copy(
                ax = w.ax + ux[i] * from,
                az = w.az + uz[i] * from,
                bx = w.ax + ux[i] * to,
                bz = w.az + uz[i] * to,
                confidence = min(w.confidence, (kept / PlaneWallSource.CONFIDENT_M).coerceAtMost(1f)),
            )
        }
        return if (changed) out else walls
    }

    /**
     * The span, along the wall, of its fullest run of points, trimmed of [TRIM] at each end;
     * `null` under [MIN_POINTS].
     */
    internal fun span(points: FloatList): Pair<Float, Float>? {
        if (points.size < MIN_POINTS) return null
        val sorted = points.sorted()
        // The fullest run, split at gaps over GAP_M.
        var bestStart = 0
        var bestEnd = 0
        var start = 0
        for (k in 1..sorted.size) {
            if (k == sorted.size || sorted[k] - sorted[k - 1] > GAP_M) {
                if (k - start > bestEnd - bestStart) {
                    bestStart = start
                    bestEnd = k
                }
                start = k
            }
        }
        val count = bestEnd - bestStart
        if (count < MIN_POINTS) return null
        val drop = (count * TRIM).toInt()
        return sorted[bestStart + drop] to sorted[bestEnd - 1 - drop]
    }

    /**
     * Whether [drawn], a wall cut back by [clip] from [found], is still to finish: a part of what
     * ARCore fitted has no point on it yet ([UNSEEN_M]), or so little of it was seen ([SLIVER]).
     */
    fun unfinished(found: LiveWall, drawn: LiveWall): Boolean =
        found.length - drawn.length >= UNSEEN_M || drawn.confidence < SLIVER

    /**
     * Of the [found] walls, the one the phone at [pose] faces within [FACING_RANGE_M] ahead, when
     * it is still to finish as [drawn] ([unfinished]); `null` otherwise. The phone's view axis,
     * flat on the floor, meets the wall's floor line as ARCore fitted it.
     */
    fun facingUnfinished(pose: DebugPose?, found: List<LiveWall>, drawn: List<LiveWall>): LiveWall? {
        val wall = facing(pose, found) ?: return null
        val shown = drawn.firstOrNull { it.id == wall.id } ?: return null
        return shown.takeIf { unfinished(wall, it) }
    }

    /** The wall of [walls] the phone at [pose] looks at, within [FACING_RANGE_M]; `null` for none. */
    fun facing(pose: DebugPose?, walls: List<LiveWall>): LiveWall? {
        pose ?: return null
        val f = pose.rotate(0f, 0f, -1f)
        val fl = hypot(f.x, f.z)
        if (fl < FLAT_MIN) return null // looking at the floor or the ceiling
        val dx = f.x / fl
        val dz = f.z / fl
        var best: LiveWall? = null
        var bestT = FACING_RANGE_M
        for (w in walls) {
            val ex = w.bx - w.ax
            val ez = w.bz - w.az
            val denom = dx * ez - dz * ex
            if (abs(denom) < EPSILON) continue
            val ox = w.ax - pose.x
            val oz = w.az - pose.z
            val t = (ox * ez - oz * ex) / denom // along the view
            val s = (ox * dz - oz * dx) / denom // along the wall, 0..1
            if (t <= 0f || t >= bestT || s < 0f || s > 1f) continue
            bestT = t
            best = w
        }
        return best
    }

    private const val EPSILON = 1e-6f
    private const val CUT_EPSILON_M = 0.01f
    private const val FLAT_MIN = 0.2f
}

/** A growable float list: the points along one wall, without boxing. */
internal class FloatList {
    private var data = FloatArray(INITIAL)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun sorted(): FloatArray = data.copyOf(size).also { it.sort() }

    private companion object {
        const val INITIAL = 32
    }
}
