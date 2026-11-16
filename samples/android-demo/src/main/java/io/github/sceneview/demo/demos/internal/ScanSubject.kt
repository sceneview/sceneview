package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The one object a scan was made of ([ScanScope.Object]): what the phone aimed at as it went
 * round it. [AIMS] poses spread over the capture each look into the scan as a tap does
 * ([ScanObjects.pick]); the object most of them land on is the scan's own, when at least
 * [MIN_SHARE] of them agree on it. `null` when they do not: a room walked through, a thing the
 * scan holds too little of to box, or a capture with no pose kept.
 *
 * On the first object scanned by hand (2026-11-14), 21 aims in 24 landed on the same one; on a
 * room and three outdoor areas of the same day, never more than 7, and no box.
 *
 * The box is of all that stands there ([ScanObjects.whole]), not of the part the looks landed
 * on. That first object was an office chair: the looks landed on its seat, the scan held nothing
 * of the column under it, and the seat's own box hung over the floor, half as high as the chair.
 */
object ScanSubject {
    /** Where the phone was and which way it looked. */
    class Aim(val eye: Vec3, val ahead: Vec3)

    /** [AIMS] aims spread evenly over [trace]'s capture; empty with no pose. */
    fun aims(trace: ArDebugTrace): List<Aim> {
        val count = trace.poseCount
        if (count == 0) return emptyList()
        val aims = minOf(AIMS, count)
        return List(aims) { i ->
            val pose = trace.pose((i.toLong() * (count - 1) / (aims - 1).coerceAtLeast(1)).toInt())
            // A camera looks down its own -Z.
            Aim(Vec3(pose.x, pose.y, pose.z), pose.rotate(0f, 0f, -1f))
        }
    }

    /** The object [aims] agree on in [cloud], a scan's dense map of [voxelM] voxels. Off the main thread. */
    fun of(cloud: DenseCloud, voxelM: Float, aims: List<Aim>): ScanBox? {
        if (aims.isEmpty() || cloud.count == 0) return null
        val objects = ScanObjects.of(cloud, voxelM)
        val seen = ArrayList<Votes>()
        for (aim in aims) {
            val found = objects.pick(aim.eye, aim.ahead) as? ScanPick.Found ?: continue
            val box = found.box
            // The same object whichever of its points the ray met: its box stands in the same place.
            val same = seen.firstOrNull { hypot(it.found.box.centerX - box.centerX, it.found.box.centerZ - box.centerZ) <= SAME_M }
            if (same != null) same.count++ else seen += Votes(found)
        }
        val most = seen.maxByOrNull { it.count }?.takeIf { it.count >= aims.size * MIN_SHARE }
        val whole = most?.let { objects.whole(it.found) }
        val looked = lookedAt(aims) ?: return whole
        // What the looks landed on is the object when the phone could go round it: no wider than
        // the circle it walked. Else it is the table a bottle stands on, or a chair and its table.
        if (whole != null && whole.length <= looked.orbit * MAX_SIZE_PER_ORBIT) return whole
        return objects.around(looked.at, looked.reach)?.box
    }

    /** Where the looks meet ([at]), and how far the phone stayed from there on the plan ([orbit]). */
    class Looked(val at: Vec3, val orbit: Float) {
        /**
         * How far from [at] the object may reach: to the circle the phone walked, and a little past
         * it, since a small thing is filmed from above its own edge.
         */
        val reach: Float get() = (orbit + REACH_PAST_ORBIT_M).coerceIn(MIN_REACH_M, MAX_REACH_M)
    }

    /**
     * The point nearest to all of [aims]' lines of sight (least squares), when at least
     * [MIN_SHARE] of them pass within [MEET_M] of it, or [MEET_PER_ORBIT] of the phone's distance,
     * and no more than [MAX_BEHIND_SHARE] have it behind the phone. `null` for a walk through a
     * room, whose looks meet nowhere.
     */
    fun lookedAt(aims: List<Aim>): Looked? {
        val at = meeting(aims) ?: return null
        val orbits = aims.map { hypot(it.eye.x - at.x, it.eye.z - at.z) }.sorted()
        val orbit = orbits[orbits.size / 2]
        val meet = max(MEET_M, orbit * MEET_PER_ORBIT)
        val misses = aims.map { missed(it, at) }
        // A walk through a room looks every way: a good part of its looks have the point behind them.
        if (misses.count { it == null } > aims.size * MAX_BEHIND_SHARE) return null
        val agree = misses.count { it != null && it <= meet }
        return Looked(at, orbit).takeIf { agree >= aims.size * MIN_SHARE }
    }

    /** How far [aim]'s line of sight passes from [at]; `null` when [at] is behind the phone. */
    fun missed(aim: Aim, at: Vec3): Float? {
        val d = aim.ahead.normalized()
        val v = at - aim.eye
        val along = v.dot(d)
        return if (along <= 0f) null else (v - d * along).length()
    }

    /** The point nearest to all of [aims]' lines of sight, least squares; `null` when they are near parallel. */
    fun meeting(aims: List<Aim>): Vec3? {
        if (aims.size < MIN_AIMS) return null
        val a = DoubleArray(9)
        val b = DoubleArray(3)
        for (aim in aims) {
            val d = aim.ahead.normalized()
            val dv = doubleArrayOf(d.x.toDouble(), d.y.toDouble(), d.z.toDouble())
            val o = doubleArrayOf(aim.eye.x.toDouble(), aim.eye.y.toDouble(), aim.eye.z.toDouble())
            for (i in 0..2) for (j in 0..2) {
                val m = (if (i == j) 1.0 else 0.0) - dv[i] * dv[j]
                a[i * 3 + j] += m
                b[i] += m * o[j]
            }
        }
        val det = det3(a)
        if (abs(det) < MIN_DET * aims.size * aims.size * aims.size) return null
        val p = DoubleArray(3) { col -> det3(a.copyOf().also { m -> for (r in 0..2) m[r * 3 + col] = b[r] }) / det }
        return Vec3(p[0].toFloat(), p[1].toFloat(), p[2].toFloat())
    }

    private fun det3(m: DoubleArray): Double =
        m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])

    /**
     * The frame [box]'s result is shown in ([ScanResultFrame]): the object, square to its own
     * sides, and [FRAME_MARGIN_M] of what stands round it. What was measured is what is looked
     * at, not the whole of a scan that holds the floor it went over too.
     *
     * It opens on the side of the box nearest the one the scan itself opens on ([like], the
     * frame read off its model): the side it was scanned from. Its +b side without one.
     */
    fun frame(box: ScanBox, like: ScanResultFrame? = null): ScanResultFrame {
        // A ScanBox turns as a ScanResultFrame does, from +X towards +Z: `a` along its length.
        val c = cos(box.yaw)
        val s = sin(box.yaw)
        val a = box.centerX * c + box.centerZ * s
        val b = -box.centerX * s + box.centerZ * c
        val halfA = box.length / 2 + FRAME_MARGIN_M
        val halfB = box.depth / 2 + FRAME_MARGIN_M
        val yaw = Math.toDegrees(box.yaw.toDouble()).toFloat()
        // The scan's opening as the world sees it, then as the box's own frame does.
        val opening = like?.let { it.frontDegrees - it.yawDegrees + yaw } ?: 0f
        val front = ((opening / QUARTER).roundToInt() * QUARTER % FULL_TURN + FULL_TURN) % FULL_TURN
        return ScanResultFrame(
            yawDegrees = yaw,
            local = floatArrayOf(
                a - halfA, box.baseY, b - halfB,
                a + halfA, box.baseY + box.height + FRAME_MARGIN_M, b + halfB,
            ),
            frontDegrees = front,
            turn = like?.turn ?: 1f,
        )
    }

    private class Votes(val found: ScanPick.Found, var count: Int = 1)

    /** Enough to outvote the looks at the floor and past the object on the way round it. */
    const val AIMS = 24

    /** The share of the aims that land on the same object for it to be the scan's. */
    const val MIN_SHARE = 0.4f

    /** How much of what stands round an object its result shows with it: the floor it stands on, to place it. */
    const val FRAME_MARGIN_M = 0.12f

    /** Two boxes whose centres are this close on the floor are the same object, grown from two of its points. */
    const val SAME_M = 0.2f

    /** An object is no wider than this many times the phone's distance from its middle: it was gone round. */
    const val MAX_SIZE_PER_ORBIT = 2f

    /** And it reaches no further from its middle than the phone's distance and this. */
    const val REACH_PAST_ORBIT_M = 0.3f
    const val MIN_REACH_M = 0.3f
    const val MAX_REACH_M = 1.5f

    /**
     * A look passes "through" the point where they meet within this, or 0.7 of the phone's
     * distance from it. On Thomas's object scans of 2026-11-14 and 15 (two chairs, two pairs of
     * shoes, a bottle) the looks miss it by 0.4 of that distance, the median; 10 to 24 looks in 24
     * pass within 0.7, and none has it behind.
     */
    const val MEET_M = 0.15f
    const val MEET_PER_ORBIT = 0.7f

    /**
     * More of the looks than this with the point behind the phone is a walk, not a turn round
     * one thing: 7 to 24 in 24 on a room and three outdoor scans of the same days.
     */
    const val MAX_BEHIND_SHARE = 0.125f
    private const val MIN_AIMS = 6
    private const val MIN_DET = 1e-4

    private const val QUARTER = 90f
    private const val FULL_TURN = 360f
}
