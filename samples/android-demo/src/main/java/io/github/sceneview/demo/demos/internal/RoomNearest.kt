package io.github.sceneview.demo.demos.internal

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The wall and the opening nearest to where the phone is, from what a room scan found.
 *
 * Everything here is in the scan's frame: the ARCore world of the recording, in metres, +Y up and
 * the floor level in x/z, the frame of the point cloud, the camera trail and [DebugPose]. Pure
 * Kotlin with no Android in it, so synthetic rooms pin every answer in a unit test.
 */

/**
 * A wall: an upright plane standing on the floor line from ([ax], [az]) to ([bx], [bz]). It
 * reaches from the floor to the ceiling, whatever height the scan saw it at.
 *
 * ([nx], [nz]) points into the room, and only its side of the line counts: the normal the query
 * hands back is rebuilt square to the wall, so a reader's rough normal does not tilt it. With no
 * side given (zero), the room is on the right of a→b for someone walking from a to b, +Y up: along
 * +X, the room is towards +Z. A side that is not finite makes the wall unreadable, and the query
 * skips it. [confidence] is how sure the reader is of the wall, 0..1, when it says.
 */
data class ShellWall(
    val ax: Float,
    val az: Float,
    val bx: Float,
    val bz: Float,
    val nx: Float,
    val nz: Float,
    val confidence: Float? = null,
)

/**
 * A door or a window: an upright rectangle whose bottom edge runs from ([ax], [az]) to
 * ([bx], [bz]) on the floor plan, from the height [bottom] up to [top]. Both are the scan's y,
 * not heights above the floor. [confidence] is how sure the reader is of it, 0..1, when it says.
 */
data class ShellOpening(
    val ax: Float,
    val az: Float,
    val bx: Float,
    val bz: Float,
    val bottom: Float,
    val top: Float,
    val confidence: Float? = null,
) {
    /** How wide it is, in metres. */
    val width: Float get() = hypot(bx - ax, bz - az)

    /** How tall it is, in metres. */
    val height: Float get() = top - bottom

    companion object {
        /**
         * The opening [from] metres along [wall] from its start (`ax`, `az`), [width] wide, from
         * the height [bottom] up to [top].
         *
         * [from] and [width] are taken as given along the wall's line, not cut to its ends: an
         * opening may start before the wall or run past it, so a wall seen shorter than its door
         * keeps the door's width. A negative width is no width. An opening with no width, which is
         * also what a wall with no length gives, is noise and the query skips it.
         */
        fun onWall(
            wall: ShellWall,
            from: Float,
            width: Float,
            bottom: Float,
            top: Float,
            confidence: Float? = null,
        ): ShellOpening {
            val dx = wall.bx - wall.ax
            val dz = wall.bz - wall.az
            val length = hypot(dx, dz).coerceAtLeast(Float.MIN_VALUE)
            val ux = dx / length
            val uz = dz / length
            val to = from + width.coerceAtLeast(0f)
            return ShellOpening(
                wall.ax + ux * from, wall.az + uz * from,
                wall.ax + ux * to, wall.az + uz * to,
                bottom, top, confidence,
            )
        }

        /**
         * The opening a box standing in a wall's plane marks: as wide as the box is long, from its
         * base up its height.
         */
        fun of(box: ScanBox, confidence: Float? = null): ShellOpening {
            val hx = cos(box.yaw) * box.length / 2
            val hz = sin(box.yaw) * box.length / 2
            return ShellOpening(
                box.centerX - hx, box.centerZ - hz,
                box.centerX + hx, box.centerZ + hz,
                box.baseY, box.baseY + box.height, confidence,
            )
        }
    }
}

/**
 * The wall nearest a point, [distance] metres away on the floor plan, at [point]: the nearest
 * point of its floor line, raised to the query's height.
 *
 * [normal] is a level unit vector into the room, and the wall's plane is `normal · p = offset`.
 * [fromBehind] when the point is on the far side of that plane from the room: outside it, or
 * through a doorway. It is the side of the whole plane, so past the end of a wall it says which
 * side of the wall's line the point is on, not whether the wall's face can be seen from there.
 */
data class NearestWall(
    val wall: ShellWall,
    val normal: Vec3,
    val offset: Float,
    val point: Vec3,
    val distance: Float,
    val fromBehind: Boolean,
)

/**
 * The opening nearest a point, [distance] metres away in the room, at [point]: the nearest point
 * of its rectangle, so 0 in the doorway.
 *
 * Whether it is a door or a window, and its sill above the floor, are not known here: a
 * [ShellOpening] carries no kind and the shell no floor level. Its `bottom` is the scan's y.
 */
data class NearestOpening(val opening: ShellOpening, val point: Vec3, val distance: Float) {
    /** The opening's width, in metres. */
    val width: Float get() = opening.width

    /** The opening's height, in metres. */
    val height: Float get() = opening.height

    /** The opening's confidence, 0..1, when its reader gave one. */
    val confidence: Float? get() = opening.confidence
}

/** What [RoomShell.nearest] finds: either half is null when the scan found none. */
data class RoomNearest(val wall: NearestWall?, val opening: NearestOpening?)

/**
 * The walls and openings a scan found, ready to say which is nearest a point.
 *
 * Nothing builds one from a scan yet. [RoomSurvey] hands its walls back on the plan's page,
 * shifted and maybe turned a quarter, and drops that shift and turn, so they cannot be put back in
 * the scan's frame from it. The feeds to come: the room structure's walls and openings
 * through [ShellOpening.onWall], an opening's `bottom` being the floor's y plus its sill; and the
 * wall openings found as boxes through [ShellOpening.of].
 *
 * - The nearest wall is the one with the shortest distance on the floor plan to its floor line,
 *   a finite segment: the point's height does not count, since a wall stands floor to ceiling.
 * - The nearest opening is the one with the shortest straight-line distance to its rectangle: a
 *   window above the phone counts the climb to it.
 * - An opening does not cut its wall: in a doorway, the door's wall is 0 m away too.
 * - Distances within [TIE_M] of the shortest are a tie, settled by a wall faced over one seen from
 *   behind, then by the higher confidence, then by list order. A confidence counts between 0 and
 *   1: below 0 counts as 0, above 1 as 1, and none (null or `NaN`) as 0, so any confidence above
 *   0 beats none and a confidence of 0 ties with none.
 * - Walls and openings narrower than [MIN_M], or lower for an opening, are noise and skipped, as
 *   is one with any coordinate, side, bottom or top not finite; a point not finite finds nothing.
 */
data class RoomShell(
    val walls: List<ShellWall> = emptyList(),
    val openings: List<ShellOpening> = emptyList(),
) {
    /** The nearest wall and opening to where [pose] is; where it looks does not matter. */
    fun nearest(pose: DebugPose): RoomNearest = nearest(pose.x, pose.y, pose.z)

    /** The nearest wall and opening to ([x], [y], [z]). */
    fun nearest(x: Float, y: Float, z: Float): RoomNearest {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return RoomNearest(null, null)
        val wall = settle(
            walls.mapNotNull { measure(it, x, y, z) },
            { it.distance },
            compareBy<NearestWall> { it.fromBehind }.thenByDescending { rank(it.wall.confidence) },
        )
        val opening = settle(
            openings.mapNotNull { measure(it, x, y, z) },
            { it.distance },
            compareByDescending<NearestOpening> { rank(it.opening.confidence) },
        )
        return RoomNearest(wall, opening)
    }

    companion object {
        /** Two distances this close, in metres, are a tie. */
        const val TIE_M = 0.001f

        /** Anything narrower than this, in metres, is noise. */
        const val MIN_M = 0.01f

        private fun measure(wall: ShellWall, x: Float, y: Float, z: Float): NearestWall? {
            if (!wall.nx.isFinite() || !wall.nz.isFinite()) return null
            val dx = wall.bx - wall.ax
            val dz = wall.bz - wall.az
            val length = hypot(dx, dz)
            if (!length.isFinite() || length < MIN_M) return null
            var nx = -dz / length
            var nz = dx / length
            if (nx * wall.nx + nz * wall.nz < 0f) {
                nx = -nx
                nz = -nz
            }
            val t = (((x - wall.ax) * dx + (z - wall.az) * dz) / (length * length)).coerceIn(0f, 1f)
            val px = wall.ax + t * dx
            val pz = wall.az + t * dz
            val distance = hypot(x - px, z - pz)
            if (!distance.isFinite()) return null
            val side = (x - wall.ax) * nx + (z - wall.az) * nz
            return NearestWall(wall, Vec3(nx, 0f, nz), nx * wall.ax + nz * wall.az, Vec3(px, y, pz), distance, side < 0f)
        }

        private fun measure(opening: ShellOpening, x: Float, y: Float, z: Float): NearestOpening? {
            if (!opening.bottom.isFinite() || !opening.top.isFinite()) return null
            val dx = opening.bx - opening.ax
            val dz = opening.bz - opening.az
            val width = hypot(dx, dz)
            if (!width.isFinite() || width < MIN_M || !(opening.height >= MIN_M)) return null
            val t = (((x - opening.ax) * dx + (z - opening.az) * dz) / (width * width)).coerceIn(0f, 1f)
            val px = opening.ax + t * dx
            val pz = opening.az + t * dz
            val py = y.coerceIn(opening.bottom, opening.top)
            val distance = sqrt((x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz))
            if (!distance.isFinite()) return null
            return NearestOpening(opening, Vec3(px, py, pz), distance)
        }

        /** The nearest of [found]; among those within [TIE_M] of it, the first [order] puts first. */
        private fun <T> settle(found: List<T>, distance: (T) -> Float, order: Comparator<T>): T? {
            val least = found.minOfOrNull(distance) ?: return null
            return found.filter { distance(it) <= least + TIE_M }.minWithOrNull(order)
        }

        /** A confidence between 0 and 1; none, `NaN` or below 0 counts as 0, above 1 as 1. */
        private fun rank(confidence: Float?): Float = confidence?.takeIf { !it.isNaN() }?.coerceIn(0f, 1f) ?: 0f
    }
}
