package io.github.sceneview.demo.demos.internal

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The one object a scan was made of ([ScanScope.Object]): what the phone aimed at as it went
 * round it. [AIMS] poses spread over the capture each look into the scan as a tap does
 * ([ScanObjects.pick]); the object most of them land on is the scan's own, when at least
 * [MIN_SHARE] of them agree on it. `null` when they do not: a room walked through, a thing the
 * scan holds too little of to box, or a capture with no pose kept.
 *
 * On the first object scanned by hand (2026-11-14), 21 aims in 24 landed on the same 74 × 68 ×
 * 53 cm; on a room and three outdoor areas of the same day, never more than 7, and no box.
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
            val box = (objects.pick(aim.eye, aim.ahead) as? ScanPick.Found)?.box ?: continue
            // The same object whichever of its points the ray met: its box stands in the same place.
            val same = seen.firstOrNull { hypot(it.box.centerX - box.centerX, it.box.centerZ - box.centerZ) <= SAME_M }
            if (same != null) same.count++ else seen += Votes(box)
        }
        val most = seen.maxByOrNull { it.count } ?: return null
        return most.box.takeIf { most.count >= aims.size * MIN_SHARE }
    }

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

    private class Votes(val box: ScanBox, var count: Int = 1)

    /** Enough to outvote the looks at the floor and past the object on the way round it. */
    const val AIMS = 24

    /** The share of the aims that land on the same object for it to be the scan's. */
    const val MIN_SHARE = 0.4f

    /** How much of what stands round an object its result shows with it: the floor it stands on, to place it. */
    const val FRAME_MARGIN_M = 0.12f

    /** Two boxes whose centres are this close on the floor are the same object, grown from two of its points. */
    const val SAME_M = 0.2f

    private const val QUARTER = 90f
    private const val FULL_TURN = 360f
}
