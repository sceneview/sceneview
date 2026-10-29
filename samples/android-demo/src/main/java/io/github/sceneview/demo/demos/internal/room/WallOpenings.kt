package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.stopIfInterrupted
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A hole found in a wall line: [t0]..[t1] along it, [bottom]..[top] above the floor. */
internal class FoundOpening(
    val kind: OpeningKind,
    val t0: Float,
    val t1: Float,
    val bottom: Float,
    val top: Float,
    val confidence: Float,
)

/**
 * The doors and windows of a wall: holes in its surfels where the wall was seen all around them —
 * on both sides and above (and below, for a window). A hole the scan could not see into because
 * something stood before it is not one; a stretch the wall was not seen on from floor to top is
 * not one either: it is an unseen stretch of the wall.
 */
internal object WallOpenings {
    private const val CELL_M = 0.05f
    private const val FROM_M = RoomLevels.WALL_FROM_M
    private const val BELOW_TOP_M = 0.1f
    private const val MIN_SIDE_M = 0.4f
    private const val MIN_FILL = 0.7f
    private const val FRONT_FROM_M = 0.05f
    private const val FRONT_TO_M = 1f
    private const val BEHIND_FROM_M = 0.1f
    private const val BEHIND_TO_M = 3f
    private const val MAX_FRONT_SHARE = 0.25f
    private const val DOOR_MIN_HIGH_M = 1.7f
    private const val DOOR_MAX_HIGH_M = 2.6f
    private const val DOOR_MIN_WIDE_M = 0.5f
    private const val DOOR_MAX_WIDE_M = 2.5f
    private const val MIN_SILL_M = 0.3f
    private const val FILL_WEIGHT = 0.6f
    private const val THROUGH_WEIGHT = 0.4f

    /**
     * The openings of [line] between [lo] and [hi] along it, on a wall [height] tall. [front] are
     * the cloud's surfels to check for something standing before a hole and for what was seen
     * through it.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    fun of(cloud: FrameCloud, line: WallLine, lo: Float, hi: Float, height: Float): List<FoundOpening> {
        val top = min(height, line.top) - BELOW_TOP_M
        val nc = ceil((hi - lo) / CELL_M).toInt()
        val nr = ceil((top - FROM_M) / CELL_M).toInt()
        if (nc < 3 || nr < 3) return emptyList()
        val wall = BooleanArray(nc * nr)
        for (i in line.members) {
            val t = line.tOf(cloud.u[i], cloud.v[i])
            val h = cloud.h[i]
            if (t < lo || t >= hi || h < FROM_M || h >= top) continue
            val c = ((t - lo) / CELL_M).toInt().coerceIn(0, nc - 1)
            val r = ((h - FROM_M) / CELL_M).toInt().coerceIn(0, nr - 1)
            wall[r * nc + c] = true
        }
        val label = IntArray(nc * nr) { -1 }
        val queue = IntArray(nc * nr)
        val found = ArrayList<IntArray>() // c0, c1, r0, r1, cells, label
        var components = 0
        for (start in 0 until nc * nr) {
            if (wall[start] || label[start] >= 0) continue
            var head = 0
            var tail = 0
            label[start] = components
            queue[tail++] = start
            var c0 = nc
            var c1 = -1
            var r0 = nr
            var r1 = -1
            while (head < tail) {
                stopIfInterrupted()
                val k = queue[head++]
                val c = k % nc
                val r = k / nc
                c0 = min(c0, c)
                c1 = max(c1, c)
                r0 = min(r0, r)
                r1 = max(r1, r)
                for (m in intArrayOf(
                    if (c > 0) k - 1 else -1,
                    if (c < nc - 1) k + 1 else -1,
                    if (r > 0) k - nc else -1,
                    if (r < nr - 1) k + nc else -1,
                )) {
                    if (m < 0 || wall[m] || label[m] >= 0) continue
                    label[m] = components
                    queue[tail++] = m
                }
            }
            found += intArrayOf(c0, c1, r0, r1, tail, components)
            components++
        }
        val result = ArrayList<FoundOpening>()
        for (f in found) {
            val (c0, c1, r0, r1) = f
            val cells = f[4]
            // Seen on both sides and above: a hole, not the end of what was seen.
            if (c0 == 0 || c1 == nc - 1 || r1 == nr - 1) continue
            val wide = (c1 - c0 + 1) * CELL_M
            val high = (r1 - r0 + 1) * CELL_M
            if (wide < MIN_SIDE_M || high < MIN_SIDE_M) continue
            val box = (c1 - c0 + 1) * (r1 - r0 + 1)
            val fill = cells.toFloat() / box
            if (fill < MIN_FILL) continue
            // Something seen before the hole hides the wall: a shadow, not an opening. Something
            // seen beyond it says the scan looked through.
            val front = HashSet<Int>()
            val through = HashSet<Int>()
            for (i in 0 until cloud.count) {
                val d = line.oOf(cloud.u[i], cloud.v[i]) - line.offset
                val isFront = d in FRONT_FROM_M..FRONT_TO_M
                val isThrough = d in -BEHIND_TO_M..-BEHIND_FROM_M
                if (!isFront && !isThrough) continue
                val t = line.tOf(cloud.u[i], cloud.v[i])
                val h = cloud.h[i]
                val c = floor((t - lo) / CELL_M).toInt()
                val r = floor((h - FROM_M) / CELL_M).toInt()
                if (c < c0 || c > c1 || r < r0 || r > r1) continue
                val k = r * nc + c
                if (label[k] != f[5]) continue
                if (isFront) front += k else through += k
            }
            if (front.size > MAX_FRONT_SHARE * cells) continue
            // The hole's edges, read on the surfels around it rather than on the grid's cells.
            var t0 = lo + c0 * CELL_M
            var t1 = lo + (c1 + 1) * CELL_M
            var bottom = FROM_M + r0 * CELL_M
            var topEdge = FROM_M + (r1 + 1) * CELL_M
            var left = Float.NEGATIVE_INFINITY
            var right = Float.POSITIVE_INFINITY
            var below = Float.NEGATIVE_INFINITY
            var above = Float.POSITIVE_INFINITY
            for (i in line.members) {
                val t = line.tOf(cloud.u[i], cloud.v[i])
                val h = cloud.h[i]
                val inRows = h >= bottom && h < topEdge
                val inColumns = t >= t0 && t < t1
                if (inRows && t < t0 && t >= t0 - CELL_M) left = max(left, t)
                if (inRows && t >= t1 && t < t1 + CELL_M) right = min(right, t)
                if (inColumns && h < bottom && h >= bottom - CELL_M) below = max(below, h)
                if (inColumns && h >= topEdge && h < topEdge + CELL_M) above = min(above, h)
            }
            if (left.isFinite()) t0 = left
            if (right.isFinite()) t1 = right
            if (above.isFinite()) topEdge = above
            val door = r0 == 0
            if (door) {
                bottom = 0f
            } else if (below.isFinite()) {
                bottom = below
            }
            val width = t1 - t0
            val kind = if (door) {
                if (topEdge !in DOOR_MIN_HIGH_M..DOOR_MAX_HIGH_M || width !in DOOR_MIN_WIDE_M..DOOR_MAX_WIDE_M) continue
                OpeningKind.Door
            } else {
                if (bottom < MIN_SILL_M) continue
                OpeningKind.Window
            }
            val seenThrough = through.size.toFloat() / cells
            val confidence = (FILL_WEIGHT * fill + THROUGH_WEIGHT * seenThrough).coerceIn(0f, 1f)
            result += FoundOpening(kind, t0, t1, bottom, topEdge, confidence)
        }
        return result.sortedBy { it.t0 }
    }
}
