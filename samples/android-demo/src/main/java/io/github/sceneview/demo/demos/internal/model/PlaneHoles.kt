package io.github.sceneview.demo.demos.internal.model

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Triangulates a hole whose rim lies on one plane, by ear clipping in that plane: no vertex is
 * added, so the patch is as flat as the wall around it, and a concave hole — lace is rarely
 * round — is covered without a fan spilling over its rim.
 */
internal object PlaneHoles {
    /**
     * Adds the triangles closing [loop]'s first [length] vertices (a border loop, in the
     * direction its faces run it) to [out], turned as the faces around it are, and returns
     * `true`; returns `false`, adding nothing, when the rim folds over itself in the plane.
     */
    fun triangulate(positions: FloatArray, loop: IntArray, length: Int, plane: Plane, out: IntList): Boolean {
        // Two axes in the plane.
        val ax: Float
        val ay: Float
        val az: Float
        if (abs(plane.nx) < AXIS_PICK) {
            ax = 0f
            ay = plane.nz
            az = -plane.ny
        } else {
            ax = -plane.nz
            ay = 0f
            az = plane.nx
        }
        val a = sqrt(ax * ax + ay * ay + az * az)
        val ux = ax / a
        val uy = ay / a
        val uz = az / a
        val vx = plane.ny * uz - plane.nz * uy
        val vy = plane.nz * ux - plane.nx * uz
        val vz = plane.nx * uy - plane.ny * ux
        val xs = FloatArray(length)
        val ys = FloatArray(length)
        var area = 0.0
        for (i in 0 until length) {
            val p = loop[i] * 3
            xs[i] = positions[p] * ux + positions[p + 1] * uy + positions[p + 2] * uz
            ys[i] = positions[p] * vx + positions[p + 1] * vy + positions[p + 2] * vz
        }
        for (i in 0 until length) {
            val j = (i + 1) % length
            area += xs[i].toDouble() * ys[j] - xs[j].toDouble() * ys[i]
        }
        if (area == 0.0) return false
        val turn = if (area > 0.0) 1f else -1f
        val previous = IntArray(length) { (it + length - 1) % length }
        val following = IntArray(length) { (it + 1) % length }
        val start = out.size
        var remaining = length
        var i = 0
        var misses = 0
        while (remaining > 3) {
            val p = previous[i]
            val q = following[i]
            if (isEar(xs, ys, p, i, q, turn, following)) {
                emit(out, loop[p], loop[i], loop[q])
                following[p] = q
                previous[q] = p
                remaining--
                i = q
                misses = 0
            } else {
                i = q
                if (++misses > remaining) {
                    out.truncate(start)
                    return false
                }
            }
        }
        emit(out, loop[previous[i]], loop[i], loop[following[i]])
        return true
    }

    /** The rim runs the faces' way round; the patch runs it the other way, as a neighbour would. */
    private fun emit(out: IntList, a: Int, b: Int, c: Int) {
        out.add(c)
        out.add(b)
        out.add(a)
    }

    @Suppress("LongParameterList")
    private fun isEar(xs: FloatArray, ys: FloatArray, p: Int, i: Int, q: Int, turn: Float, following: IntArray): Boolean {
        val cross = (xs[i] - xs[p]) * (ys[q] - ys[p]) - (ys[i] - ys[p]) * (xs[q] - xs[p])
        if (cross * turn <= 0f) return false
        var j = following[q]
        while (j != p) {
            if (inside(xs[j], ys[j], xs[p], ys[p], xs[i], ys[i], xs[q], ys[q], turn)) return false
            j = following[j]
        }
        return true
    }

    @Suppress("LongParameterList")
    private fun inside(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, turn: Float): Boolean {
        val d1 = ((bx - ax) * (y - ay) - (by - ay) * (x - ax)) * turn
        val d2 = ((cx - bx) * (y - by) - (cy - by) * (x - bx)) * turn
        val d3 = ((ax - cx) * (y - cy) - (ay - cy) * (x - cx)) * turn
        return d1 >= 0f && d2 >= 0f && d3 >= 0f
    }

    private const val AXIS_PICK = 0.9f
}
