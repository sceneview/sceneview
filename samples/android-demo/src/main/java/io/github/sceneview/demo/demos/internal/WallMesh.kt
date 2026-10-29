package io.github.sceneview.demo.demos.internal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The walls Measure raises on its outline and 3D Scan raises on the walls it recognises: the same
 * translucent quad standing on a floor line, the same look, the same squaring of a room's corners.
 *
 * Pure geometry, so both demos' walls run in a JVM test. World space is ARCore's: metres, `+Y` up.
 */
object WallMesh {

    /** The strength of a wall's tint: the room behind it, and the points on it, still read. */
    const val FILL_ALPHA = 0.32f

    /**
     * How far off square a corner may be and still be squared, in degrees. Rooms are square far
     * more often than a hand-held phone's aim or ARCore's plane fit is; magicplan and RoomPlan
     * square corners the same way.
     */
    const val SQUARE_TOLERANCE_DEGREES = 6f

    /**
     * Emits the four corners of the wall standing on ([ax], [ay], [az]) → ([bx], [by], [bz]),
     * [height] metres tall, in the order bottom-start, bottom-end, top-end, top-start: the order
     * [indices] triangulates.
     */
    inline fun quad(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        height: Float,
        emit: (x: Float, y: Float, z: Float) -> Unit,
    ) {
        emit(ax, ay, az)
        emit(bx, by, bz)
        emit(bx, by + height, bz)
        emit(ax, ay + height, az)
    }

    /** Triangle indices for [wallCount] quads from [quad]: two triangles per wall. */
    fun indices(wallCount: Int): IntArray {
        val indices = IntArray(wallCount * INDICES_PER_WALL)
        for (w in 0 until wallCount) {
            val base = w * VERTICES_PER_WALL
            val o = w * INDICES_PER_WALL
            indices[o] = base
            indices[o + 1] = base + 1
            indices[o + 2] = base + 2
            indices[o + 3] = base
            indices[o + 4] = base + 2
            indices[o + 5] = base + 3
        }
        return indices
    }

    /**
     * The direction of a wall, in radians on the floor (XZ), squared onto [reference] — the room's
     * main axis — when it is within [toleranceDegrees] of it or of its perpendicular. A wall's
     * direction has no sign: the result is in `[0, π)`.
     */
    fun square(direction: Float, reference: Float, toleranceDegrees: Float = SQUARE_TOLERANCE_DEGREES): Float {
        val quarter = (PI / 2).toFloat()
        val off = wrapHalfTurn(direction - reference)
        val step = Math.round(off / quarter).toFloat()
        val residual = off - step * quarter
        val squared = if (abs(Math.toDegrees(residual.toDouble())) <= toleranceDegrees) direction - residual else direction
        return wrapHalfTurn(squared).let { if (it < 0f) it + PI.toFloat() else it }
    }

    /** [angle] brought into `(-π/2, π/2]`: a line's direction, whichever way it is walked. */
    fun wrapHalfTurn(angle: Float): Float {
        var a = atan2(sin(angle * 2.0), cos(angle * 2.0)).toFloat() / 2f
        if (a <= -(PI / 2).toFloat()) a += PI.toFloat()
        return a
    }

    const val VERTICES_PER_WALL = 4
    const val INDICES_PER_WALL = 6
}
