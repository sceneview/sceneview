package io.github.sceneview.demo.demos.internal

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * A recorded room, measured like a floor plan: the rectangle its floor and walls stand in,
 * squared to the walls, [width] × [depth] metres.
 *
 * [corners] are its four corners on the floor, (x, z) pairs in order around the room, 0→1
 * running along [width] and 1→2 along [depth]; side `i` runs from corner `i` to corner `i + 1`. [yaw] is the walls' direction, the
 * angle of the 0→1 side from +X.
 */
class RoomMeasure(val corners: FloatArray, val width: Float, val depth: Float, val yaw: Float) {
    /** The rectangle's area, m². */
    val area: Float get() = width * depth

    /** `3.4 × 4.1 m · 14 m²`: the room as a floor plan names it. */
    val summary: String get() = "${metres(width, unit = false)} × ${metres(depth)} · ${squareMetres(area)}"

    companion object {
        /** A room is at least this wide on each side: under it, a table top is not a room. */
        const val MIN_SIDE_M = 0.6f

        /**
         * The room around [planes]' floor (the patches at [floorY], see [PlaneLayering.isGround])
         * and walls, `null` when they hold no floor and no wall, or span less than [MIN_SIDE_M].
         *
         * The walls give the directions: each votes for its own, weighted by its width, modulo a
         * quarter turn — a room's walls stand square, so a wall and the one across the corner
         * agree. Without a wall, the floor patches' edges vote the same way.
         */
        fun of(planes: List<DebugPlane>, floorY: Float): RoomMeasure? {
            val walls = planes.filter { it.kind == DebugPlaneKind.Wall && it.vertexCount >= 3 }
            val floors = planes.filter { PlaneLayering.isGround(it, floorY) }
            if (walls.isEmpty() && floors.isEmpty()) return null
            val yaw = squareYaw(if (walls.isNotEmpty()) walls else floors)
            val c = cos(yaw)
            val s = sin(yaw)
            var minA = Float.MAX_VALUE
            var maxA = -Float.MAX_VALUE
            var minB = Float.MAX_VALUE
            var maxB = -Float.MAX_VALUE
            for (plane in walls + floors) for (i in 0 until plane.vertexCount) {
                val x = plane.polygon[i * 3]
                val z = plane.polygon[i * 3 + 2]
                // In the room's own axes: a along the walls' direction, b across it.
                val a = x * c + z * s
                val b = -x * s + z * c
                minA = minOf(minA, a); maxA = maxOf(maxA, a)
                minB = minOf(minB, b); maxB = maxOf(maxB, b)
            }
            val width = maxA - minA
            val depth = maxB - minB
            if (width < MIN_SIDE_M || depth < MIN_SIDE_M) return null
            fun corner(a: Float, b: Float) = floatArrayOf(a * c - b * s, a * s + b * c)
            val corners = corner(minA, minB) + corner(maxA, minB) + corner(maxA, maxB) + corner(minA, maxB)
            return RoomMeasure(corners, width, depth, yaw)
        }

        /**
         * The direction [planes] stand square to, in `[-π/4, π/4]`: each plane's edges vote
         * `4·angle` weighted by their length, so edges a quarter turn apart vote alike.
         */
        internal fun squareYaw(planes: List<DebugPlane>): Float {
            var sx = 0.0
            var sy = 0.0
            for (plane in planes) {
                val n = plane.vertexCount
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val dx = plane.polygon[j * 3] - plane.polygon[i * 3]
                    val dz = plane.polygon[j * 3 + 2] - plane.polygon[i * 3 + 2]
                    val length = hypot(dx, dz)
                    if (length < 1e-4f) continue
                    val angle = atan2(dz, dx) * 4f
                    sx += length * cos(angle)
                    sy += length * sin(angle)
                }
            }
            if (abs(sx) < 1e-9 && abs(sy) < 1e-9) return 0f
            return (atan2(sy, sx) / 4.0).toFloat()
        }

        /** `3.4 m`, `12 m`: a tenth of a metre under 10 m, the metre past it. */
        fun metres(m: Float, unit: Boolean = true): String {
            val value = if (m < 10f) String.format(Locale.US, "%.1f", m) else String.format(Locale.US, "%.0f", m)
            return if (unit) "$value m" else value
        }

        /** `14 m²`, `2.5 m²`. */
        fun squareMetres(m2: Float): String =
            if (m2 < 10f) String.format(Locale.US, "%.1f m²", m2) else String.format(Locale.US, "%.0f m²", max(m2, 0f))
    }
}

/**
 * A [RoomMeasure] drawn on the floor as an architect's plan draws it: on the two sides facing
 * the viewer, a dimension line a little outside the room, extension lines out from its corners,
 * a slash at each end, and the length written beyond the line, reading from outside.
 *
 * Everything is one textured mesh over one atlas ([ATLAS_WIDTH] × [ATLAS_HEIGHT]): the width's
 * label on row 0, the depth's on row 1, both [ROW_HEIGHT] px tall, and a solid strip at the
 * bottom that the lines sample.
 */
object MeasureDrawing {
    const val ATLAS_WIDTH = 512
    const val ATLAS_HEIGHT = 288
    const val ROW_HEIGHT = 128

    /** The solid strip the lines sample: the atlas's last [SOLID_HEIGHT] rows. */
    const val SOLID_HEIGHT = 32

    private const val SOLID_U = 0.5f

    /**
     * V of the atlas's pixel row [y], counted from the top of the drawn bitmap: this atlas is
     * sampled bottom-up, like [PointColorAtlas]. Counted from the top, as first written, the
     * emulator drew every label mirrored from the other row, and the lines sampled a
     * transparent row, so the floor showed a stray strip and no dimension line.
     */
    fun vOf(y: Float): Float = 1f - y / ATLAS_HEIGHT

    /** V of the solid strip's middle row. */
    private val solidV: Float = vOf(ATLAS_HEIGHT - SOLID_HEIGHT / 2f)

    /**
     * The width side (0 or 2) and the depth side (1 or 3) of [measure] whose outside faces the eye
     * at ([eyeX], [eyeZ]) most: the two a viewer reads without looking through the room.
     */
    fun sidesFacing(measure: RoomMeasure, eyeX: Float, eyeZ: Float): IntArray {
        fun facing(side: Int): Float {
            val (ox, oz) = outward(measure, side)
            val (mx, mz) = midpoint(measure, side)
            return ox * (eyeX - mx) + oz * (eyeZ - mz)
        }
        return intArrayOf(if (facing(0) >= facing(2)) 0 else 2, if (facing(1) >= facing(3)) 1 else 3)
    }

    /**
     * Appends [side]'s dimension at height [y]: the line [offset] outside the side, [halfWidth]
     * wide, and its label [textHeight] tall and [textWidth] wide, sampling its atlas row (0 for
     * a width side, 1 for a depth side) over its first [textWidth] / [textHeight] × [ROW_HEIGHT] px.
     */
    @Suppress("LongParameterList")
    fun addDimension(
        mesh: DebugMesh,
        measure: RoomMeasure,
        side: Int,
        y: Float,
        offset: Float,
        halfWidth: Float,
        textHeight: Float,
        textWidth: Float,
    ) {
        val c = measure.corners
        val a = side % 4
        val b = (side + 1) % 4
        val (ox, oz) = outward(measure, side)
        val ax = c[a * 2]
        val az = c[a * 2 + 1]
        val bx = c[b * 2]
        val bz = c[b * 2 + 1]
        val gap = offset * EXTENSION_GAP
        val over = offset * EXTENSION_OVERSHOOT
        // The dimension line, and an extension line from each corner out past it.
        ribbon(mesh, ax + ox * offset, az + oz * offset, bx + ox * offset, bz + oz * offset, y, halfWidth)
        ribbon(mesh, ax + ox * gap, az + oz * gap, ax + ox * (offset + over), az + oz * (offset + over), y, halfWidth)
        ribbon(mesh, bx + ox * gap, bz + oz * gap, bx + ox * (offset + over), bz + oz * (offset + over), y, halfWidth)
        // A slash across each end, at 45° between the side and the outside.
        val length = hypot(bx - ax, bz - az).coerceAtLeast(1e-6f)
        val dx = (bx - ax) / length
        val dz = (bz - az) / length
        val sx = (dx + ox) * SLASH * offset
        val sz = (dz + oz) * SLASH * offset
        for ((px, pz) in listOf(ax + ox * offset to az + oz * offset, bx + ox * offset to bz + oz * offset)) {
            ribbon(mesh, px - sx, pz - sz, px + sx, pz + sz, y, halfWidth * SLASH_WEIGHT)
        }
        // The label beyond the line, its top towards the room, reading from outside: its right
        // is (-outside) × up.
        val row = side % 2
        val rx = oz
        val rz = -ox
        val mx = (ax + bx) / 2f + ox * (offset + gap + textHeight / 2f)
        val mz = (az + bz) / 2f + oz * (offset + gap + textHeight / 2f)
        val hw = textWidth / 2f
        val hh = textHeight / 2f
        val u1 = (textWidth / textHeight * ROW_HEIGHT / ATLAS_WIDTH).coerceAtMost(1f)
        val v0 = vOf(row * ROW_HEIGHT.toFloat())
        val v1 = vOf((row + 1) * ROW_HEIGHT.toFloat())
        val tl = mesh.vertex(mx - rx * hw - ox * hh, y, mz - rz * hw - oz * hh, 0f, v0)
        val tr = mesh.vertex(mx + rx * hw - ox * hh, y, mz + rz * hw - oz * hh, u1, v0)
        val br = mesh.vertex(mx + rx * hw + ox * hh, y, mz + rz * hw + oz * hh, u1, v1)
        val bl = mesh.vertex(mx - rx * hw + ox * hh, y, mz - rz * hw + oz * hh, 0f, v1)
        mesh.quad(tl, tr, br, bl)
    }

    /** The unit outside direction of [side], (x, z). */
    fun outward(measure: RoomMeasure, side: Int): Pair<Float, Float> {
        val c = measure.corners
        val cx = (c[0] + c[2] + c[4] + c[6]) / 4f
        val cz = (c[1] + c[3] + c[5] + c[7]) / 4f
        val (mx, mz) = midpoint(measure, side)
        val length = hypot(mx - cx, mz - cz).coerceAtLeast(1e-6f)
        return (mx - cx) / length to (mz - cz) / length
    }

    private fun midpoint(measure: RoomMeasure, side: Int): Pair<Float, Float> {
        val c = measure.corners
        val a = side % 4
        val b = (side + 1) % 4
        return (c[a * 2] + c[b * 2]) / 2f to (c[a * 2 + 1] + c[b * 2 + 1]) / 2f
    }

    /** A flat ribbon on the floor from ([x0], [z0]) to ([x1], [z1]), sampling the solid strip. */
    @Suppress("LongParameterList")
    private fun ribbon(mesh: DebugMesh, x0: Float, z0: Float, x1: Float, z1: Float, y: Float, halfWidth: Float) {
        val length = hypot(x1 - x0, z1 - z0)
        if (length < 1e-6f) return
        val px = -(z1 - z0) / length * halfWidth
        val pz = (x1 - x0) / length * halfWidth
        val a = mesh.vertex(x0 + px, y, z0 + pz, SOLID_U, solidV)
        val b = mesh.vertex(x1 + px, y, z1 + pz, SOLID_U, solidV)
        val c = mesh.vertex(x1 - px, y, z1 - pz, SOLID_U, solidV)
        val d = mesh.vertex(x0 - px, y, z0 - pz, SOLID_U, solidV)
        mesh.quad(a, b, c, d)
    }

    /** Extension lines start this fraction of the offset away from the corner… */
    private const val EXTENSION_GAP = 0.25f

    /** …and run this fraction of it past the dimension line. */
    private const val EXTENSION_OVERSHOOT = 0.3f

    /** An end slash's half length, as a fraction of the offset, along each of its two axes. */
    private const val SLASH = 0.18f

    /** End slashes are drawn this much bolder than the lines. */
    private const val SLASH_WEIGHT = 1.6f
}
