package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * A room's plan from the planes ARCore found while it was scanned: the walls the capture screen
 * counted ([PlaneWallSource]) standing on the rectangle the replay dimensions ([RoomMeasure]).
 *
 * The survey reads the dense cloud ([RoomSurveyor]); the wall count and the replay's dimensions
 * read ARCore's planes. When the depth stalls — "No depth yet" for minutes while ARCore keeps
 * finding walls — the cloud holds a floor and no wall, and the export said "No plan yet: scan the
 * walls to get one" under a capture that had counted ten. The planes then give the plan.
 *
 * Nothing here is measured end to end nor read with a scatter: every figure is an estimate (≈),
 * and a side no counted wall stands on is drawn dashed, as the survey draws a wall it did not see.
 */

/** Reads a [RoomSurvey] from a scan's planes. Pure Kotlin. */
object PlaneRoomSurvey {
    /** A counted wall stands on a side of the room's rectangle when its line is this close to it. */
    const val SIDE_REACH_M = 0.5f

    /** An unseen stretch of a side shorter than this is not drawn: the gap between two planes. */
    const val UNSEEN_RUN_M = 0.3f

    /**
     * The plan of the room [planes] stand in, the floor at [floorY]; `null` when they hold fewer
     * than [ArDebugSession.MIN_ROOM_WALLS] walls — the capture screen's own count — or no room.
     */
    @Suppress("ReturnCount")
    fun of(planes: List<DebugPlane>, floorY: Float): RoomSurvey? {
        val snapshot = PlaneWallSource().update(planes.map { it.toLivePlane() }, floorY, time = 0f)
        if (snapshot.walls.size < ArDebugSession.MIN_ROOM_WALLS) return null
        val room = RoomMeasure.of(planes, floorY) ?: return null
        val c = cos(room.yaw)
        val s = sin(room.yaw)
        fun u(x: Float, z: Float) = x * c + z * s
        fun v(x: Float, z: Float) = -x * s + z * c
        val us = FloatArray(4) { u(room.corners[it * 2], room.corners[it * 2 + 1]) }
        val vs = FloatArray(4) { v(room.corners[it * 2], room.corners[it * 2 + 1]) }
        val minU = us.min()
        val maxU = us.max()
        val minV = vs.min()
        val maxV = vs.max()
        val extentU = maxU - minU
        val extentV = maxV - minV

        // The four sides, from corner i to corner i + 1: v = minV (+u), u = maxU (+v), v = maxV (-u), u = minU (-v).
        val cu = floatArrayOf(minU, maxU, maxU, minU)
        val cv = floatArrayOf(minV, minV, maxV, maxV)
        val covered = List(SIDES) { ArrayList<ClosedFloatingPointRange<Float>>() }
        for (wall in snapshot.walls) {
            val au = u(wall.ax, wall.az)
            val av = v(wall.ax, wall.az)
            val bu = u(wall.bx, wall.bz)
            val bv = v(wall.bx, wall.bz)
            val alongU = abs(bu - au) >= abs(bv - av)
            val across = if (alongU) (av + bv) / 2f else (au + bu) / 2f
            for (side in 0 until SIDES) {
                if ((side % 2 == 0) != alongU) continue
                val line = if (alongU) cv[side] else cu[side]
                if (abs(across - line) > SIDE_REACH_M) continue
                // Offsets from the side's own start corner, the way its wall runs on the plan.
                val (lo, hi) = if (alongU) min(au, bu) to max(au, bu) else min(av, bv) to max(av, bv)
                val (from, to) = when (side) {
                    0 -> lo - minU to hi - minU
                    1 -> lo - minV to hi - minV
                    2 -> maxU - hi to maxU - lo
                    else -> maxV - hi to maxV - lo
                }
                val length = if (alongU) extentU else extentV
                val a = from.coerceIn(0f, length)
                val b = to.coerceIn(0f, length)
                if (b > a) covered[side] += a..b
            }
        }
        val lengths = FloatArray(SIDES) { if (it % 2 == 0) extentU else extentV }
        val shares = FloatArray(SIDES) { side -> coveredLength(covered[side]) / lengths[side] }
        val seen = BooleanArray(SIDES) { shares[it] >= RoomSurveyor.MIN_SEEN_SHARE }

        // The page frame, as the survey's: the longer side along x, y down, clockwise from the top-left.
        val turned = extentV > extentU
        val px = FloatArray(SIDES) { if (turned) extentV - (cv[it] - minV) else cu[it] - minU }
        val py = FloatArray(SIDES) { if (turned) cu[it] - minU else cv[it] - minV }
        val first = (0 until SIDES).minWith(compareBy({ py[it] }, { px[it] }))
        val walls = (0 until SIDES).map { k ->
            val i = (first + k) % SIDES
            val next = (i + 1) % SIDES
            SurveyWall(
                tag = RoomSurveyor.tagOf(k),
                ax = px[i], ay = py[i], bx = px[next], by = py[next],
                length = estimate(lengths[i]),
                seenShare = shares[i],
                seen = seen[i],
                unseen = unseenOf(covered[i], lengths[i]),
            )
        }
        val whole = seen.all { it }
        val alongU = estimate(extentU).takeIf { seen[1] && seen[3] }
        val alongV = estimate(extentV).takeIf { seen[0] && seen[2] }
        val height = estimate(snapshot.height).takeIf { snapshot.ceilingSeen }
        return RoomSurvey(
            walls = walls,
            planLength = max(extentU, extentV),
            planWidth = min(extentU, extentV),
            length = if (turned) alongV else alongU,
            width = if (turned) alongU else alongV,
            area = estimate(extentU * extentV).takeIf { whole },
            perimeter = estimate(2f * (extentU + extentV)).takeIf { whole },
            ceilingHeight = height,
            gaps = listOfNotNull(
                SurveyGap.MissingWall.takeIf { !whole },
                SurveyGap.Ceiling.takeIf { height == null },
            ),
            floorY = snapshot.floorY,
            floorThickness = 0f,
            yaw = room.yaw,
        )
    }

    private fun estimate(value: Float) = SurveyFigure(value, 0f, estimate = true)

    /** The length [ranges] cover together, overlaps counted once. */
    private fun coveredLength(ranges: List<ClosedFloatingPointRange<Float>>): Float {
        var total = 0f
        var end = Float.NEGATIVE_INFINITY
        for (r in ranges.sortedBy { it.start }) {
            val start = max(r.start, end)
            if (r.endInclusive > start) total += r.endInclusive - start
            end = max(end, r.endInclusive)
        }
        return total
    }

    /** The stretches of a side [length] long that [ranges] leave uncovered, [UNSEEN_RUN_M] or longer. */
    private fun unseenOf(ranges: List<ClosedFloatingPointRange<Float>>, length: Float): List<ClosedFloatingPointRange<Float>> {
        val out = ArrayList<ClosedFloatingPointRange<Float>>()
        var at = 0f
        for (r in ranges.sortedBy { it.start }) {
            if (r.start - at >= UNSEEN_RUN_M) out += at..r.start
            at = max(at, r.endInclusive)
        }
        if (length - at >= UNSEEN_RUN_M) out += at..length
        return out
    }

    private const val SIDES = 4
}

/**
 * This survey, or — when the dense cloud showed no wall ([SurveyGap.NoWalls]) — the one [planes]
 * give ([PlaneRoomSurvey]), keeping the ceiling the cloud measured when the planes saw none.
 */
fun RoomSurvey.orPlanes(planes: List<DebugPlane>, floorY: Float): RoomSurvey {
    if (hasPlan || SurveyGap.NoWalls !in gaps) return this
    val fromPlanes = PlaneRoomSurvey.of(planes, floorY) ?: return this
    val height = ceilingHeight ?: return fromPlanes
    if (fromPlanes.ceilingHeight != null) return fromPlanes
    return RoomSurvey(
        fromPlanes.walls, fromPlanes.planLength, fromPlanes.planWidth, fromPlanes.length, fromPlanes.width,
        fromPlanes.area, fromPlanes.perimeter, height, fromPlanes.gaps - SurveyGap.Ceiling,
        fromPlanes.floorY, fromPlanes.floorThickness, fromPlanes.yaw,
    )
}
