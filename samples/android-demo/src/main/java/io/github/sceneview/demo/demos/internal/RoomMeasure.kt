package io.github.sceneview.demo.demos.internal

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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
         * The room around [planes]' floor and walls, `null` when they hold no floor and no wall,
         * or span less than [MIN_SIDE_M].
         *
         * The floor is the level of the largest floor patch near [floorY] (see [groundOf]): on a
         * phone, the lowest patch ARCore found once sat 42 cm under the real floor, and the
         * measure, standing on it, kept none of the floor's own patches.
         *
         * The walls give the directions: each votes for its own, weighted by its width, modulo a
         * quarter turn — a room's walls stand square, so a wall and the one across the corner
         * agree. Without a wall, the floor patches' edges vote the same way.
         *
         * A side the floor overflows stops at its wall (see [clampToWalls]): ARCore grows a floor
         * plane past the wall it meets, by a metre on a phone, and the rectangle around that
         * floor read 56 m² for a room of under 40.
         */
        fun of(planes: List<DebugPlane>, floorY: Float): RoomMeasure? {
            val walls = planes.filter { it.kind == DebugPlaneKind.Wall && it.vertexCount >= 3 }
            val groundY = groundOf(planes, floorY)
            val floors = planes.filter { PlaneLayering.isGround(it, groundY) }
            if (walls.isEmpty() && floors.isEmpty()) return null
            val yaw = squareYaw(if (walls.isNotEmpty()) walls else floors)
            val c = cos(yaw)
            val s = sin(yaw)
            val box = floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE)
            for (plane in walls + floors) for (i in 0 until plane.vertexCount) {
                val x = plane.polygon[i * 3]
                val z = plane.polygon[i * 3 + 2]
                // In the room's own axes: a along the walls' direction, b across it.
                val a = x * c + z * s
                val b = -x * s + z * c
                box[0] = minOf(box[0], a); box[1] = maxOf(box[1], a)
                box[2] = minOf(box[2], b); box[3] = maxOf(box[3], b)
            }
            clampToWalls(box, walls, c, s)
            val minA = box[0]
            val minB = box[2]
            val width = box[1] - minA
            val depth = box[3] - minB
            if (width < MIN_SIDE_M || depth < MIN_SIDE_M) return null
            fun corner(a: Float, b: Float) = floatArrayOf(a * c - b * s, a * s + b * c)
            val maxA = box[1]
            val maxB = box[3]
            val corners = corner(minA, minB) + corner(maxA, minB) + corner(maxA, maxB) + corner(minA, maxB)
            return RoomMeasure(corners, width, depth, yaw)
        }

        /**
         * The floor's height: [floorY], unless a floor patch of at least [MIN_GROUND_M2] lies up
         * to [GROUND_RISE_M] over it and covers more than the patches at [floorY] together — the
         * floor is where most of it was found, not its lowest stray patch. A table top is smaller
         * than that, a seat higher.
         */
        internal fun groundOf(planes: List<DebugPlane>, floorY: Float): Float {
            val floors = planes.filter { it.kind == DebugPlaneKind.Floor && it.vertexCount >= 3 }
            val atFloor = floors.filter { PlaneLayering.isGround(it, floorY) }.sumOf { areaXz(it).toDouble() }
            val largest = floors
                .filter { meanY(it) - floorY in 0f..GROUND_RISE_M }
                .maxByOrNull { areaXz(it) } ?: return floorY
            val area = areaXz(largest)
            return if (area >= MIN_GROUND_M2 && area > atFloor) meanY(largest) else floorY
        }

        /**
         * Pulls each side of [box] (`[minA, maxA, minB, maxB]`, the room's axes `a = x·c + z·s`,
         * `b = -x·s + z·c`) in to the outermost wall standing on that side, when one stands
         * inside the box's outer half there: what lies past the walls is floor ARCore grew
         * through them. A wall is read as the line it stands on — its mean across, over its
         * length along — and only a wall at least [MIN_SIDE_M] long counts.
         */
        private fun clampToWalls(box: FloatArray, walls: List<DebugPlane>, c: Float, s: Float) {
            val midA = (box[0] + box[1]) / 2f
            val midB = (box[2] + box[3]) / 2f
            var lowA = Float.NaN
            var highA = Float.NaN
            var lowB = Float.NaN
            var highB = Float.NaN
            for (wall in walls) {
                var a0 = Float.MAX_VALUE
                var a1 = -Float.MAX_VALUE
                var b0 = Float.MAX_VALUE
                var b1 = -Float.MAX_VALUE
                var sumA = 0f
                var sumB = 0f
                for (i in 0 until wall.vertexCount) {
                    val x = wall.polygon[i * 3]
                    val z = wall.polygon[i * 3 + 2]
                    val a = x * c + z * s
                    val b = -x * s + z * c
                    a0 = minOf(a0, a); a1 = maxOf(a1, a)
                    b0 = minOf(b0, b); b1 = maxOf(b1, b)
                    sumA += a
                    sumB += b
                }
                val n = wall.vertexCount
                val alongA = a1 - a0 >= b1 - b0
                // A wall shorter than a room's side is a stub, not the room's edge.
                if (maxOf(a1 - a0, b1 - b0) < MIN_SIDE_M) continue
                if (alongA) {
                    // Along a: a wall across b, on the line b = its mean.
                    val at = sumB / n
                    if (at < midB) lowB = if (lowB.isNaN()) at else minOf(lowB, at)
                    else highB = if (highB.isNaN()) at else maxOf(highB, at)
                } else {
                    val at = sumA / n
                    if (at < midA) lowA = if (lowA.isNaN()) at else minOf(lowA, at)
                    else highA = if (highA.isNaN()) at else maxOf(highA, at)
                }
            }
            if (!lowA.isNaN()) box[0] = maxOf(box[0], lowA)
            if (!highA.isNaN()) box[1] = minOf(box[1], highA)
            if (!lowB.isNaN()) box[2] = maxOf(box[2], lowB)
            if (!highB.isNaN()) box[3] = minOf(box[3], highB)
        }

        /** [plane]'s area seen from above, m²: its polygon's shoelace over x and z. */
        private fun areaXz(plane: DebugPlane): Float {
            var twice = 0f
            val p = plane.polygon
            val n = plane.vertexCount
            for (i in 0 until n) {
                val j = (i + 1) % n
                twice += p[i * 3] * p[j * 3 + 2] - p[j * 3] * p[i * 3 + 2]
            }
            return abs(twice) / 2f
        }

        private fun meanY(plane: DebugPlane): Float {
            var sum = 0f
            for (i in 0 until plane.vertexCount) sum += plane.polygon[i * 3 + 1]
            return sum / plane.vertexCount
        }

        /** A floor patch smaller than this is a table top or a step, not where the floor is. */
        const val MIN_GROUND_M2 = 1.5f

        /** The floor is looked for up to this far over the lowest floor patch: under a seat. */
        const val GROUND_RISE_M = 0.6f

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
 * The room to show while it is still being scanned. ARCore merges and regrows its planes
 * mid-walk, so the figure found on one frame can jump, or vanish, and be back on the next:
 * a figure that differs from the one shown replaces it only once it has differed for a while.
 * The first room shows at once, and a room that is only being refined (each side within
 * [CHANGE_TOLERANCE_M]) is followed without delay.
 *
 * A scan only ever adds to a room, so the two ways a figure can differ do not wait alike. A
 * room that grew — a wall just found — shows after [CHANGE_HOLD_SECONDS]. A room that shrank,
 * or was lost, waits [SHRINK_HOLD_SECONDS]: on a phone, planes dropped while ARCore merged them
 * read 3.7 × 3.9 m, then 2.4 × 2.3 m for two and a half seconds, then 3.7 × 3.9 m again, and
 * the short hold showed all three.
 */
class SteadyRoomMeasure {
    private var shown: RoomMeasure? = null
    private var differsSince = Float.NaN
    private var differsSmaller = false
    private var lastTime = Float.NaN

    /** The room to show at [time] seconds, [found] being the one measured there. */
    fun update(found: RoomMeasure?, time: Float): RoomMeasure? {
        // The clock ran back: another scan, nothing of the last one holds.
        if (time < lastTime) {
            shown = null
            differsSince = Float.NaN
        }
        lastTime = time
        val current = shown
        if (current == null || sameFigure(found, current)) {
            // A room refined keeps its latest figure.
            if (found != null) shown = found
            differsSince = Float.NaN
            return shown
        }
        val smaller = isSmaller(found, current)
        // A room that stops shrinking to start growing, or the reverse, starts its wait over.
        if (differsSince.isNaN() || smaller != differsSmaller) {
            differsSince = time
            differsSmaller = smaller
        }
        if (time - differsSince >= (if (smaller) SHRINK_HOLD_SECONDS else CHANGE_HOLD_SECONDS)) {
            // A room lost for good is shown as lost.
            shown = found
            differsSince = Float.NaN
        }
        return shown
    }

    private fun sameFigure(a: RoomMeasure?, b: RoomMeasure): Boolean =
        a != null && abs(a.width - b.width) <= CHANGE_TOLERANCE_M && abs(a.depth - b.depth) <= CHANGE_TOLERANCE_M

    /**
     * Whether [found] is [shown] with something taken away: no room at all, or neither its long
     * nor its short side past [shown]'s — whichever of the two is named the width, since a room
     * near a diagonal trades them from one frame to the next.
     */
    private fun isSmaller(found: RoomMeasure?, shown: RoomMeasure): Boolean {
        found ?: return true
        val long = max(found.width, found.depth) - max(shown.width, shown.depth)
        val short = min(found.width, found.depth) - min(shown.width, shown.depth)
        return long <= CHANGE_TOLERANCE_M && short <= CHANGE_TOLERANCE_M
    }

    companion object {
        /** A plane merge settles in a few frames; a wall just found stays. */
        const val CHANGE_HOLD_SECONDS = 0.75f

        /**
         * A room that shrinks is ARCore taking planes back, which it gives back within seconds;
         * the walls are still there. Past this, it is believed.
         */
        const val SHRINK_HOLD_SECONDS = 5f

        /** Under this on each side, two measures are the same room, refined. */
        const val CHANGE_TOLERANCE_M = 0.15f
    }
}

/**
 * How large a room's dimensions are drawn where a pixel covers a given length of floor: the
 * label [textHeight] metres tall, its dimension line [offset] metres outside the wall. Both are
 * sized in pixels, within metres that keep them readable from very near and very far.
 */
class MeasureSize(val textHeight: Float, val offset: Float) {
    companion object {
        fun at(metresPerPixel: Float): MeasureSize = MeasureSize(
            textHeight = (TEXT_PX * metresPerPixel).coerceIn(TEXT_MIN_M, TEXT_MAX_M),
            offset = (OFFSET_PX * metresPerPixel).coerceIn(OFFSET_MIN_M, OFFSET_MAX_M),
        )

        /**
         * The largest the dimensions are drawn on a view where a pixel covers [metresPerPixel]:
         * the drawing is rebuilt in steps of zoom ([ZOOM_STEP]), so it can stand half a step over
         * the exact size. This is the size a framing has to leave room for.
         */
        fun atMost(metresPerPixel: Float): MeasureSize = at(metresPerPixel * (1f + ZOOM_STEP))

        /**
         * The label's box, in pixels: its figures' capitals are ~40 % of it, and the floor seen
         * at a slant shortens it further.
         */
        const val TEXT_PX = 72f
        const val TEXT_MIN_M = 0.04f
        const val TEXT_MAX_M = 0.9f
        const val OFFSET_PX = 28f
        const val OFFSET_MIN_M = 0.06f
        const val OFFSET_MAX_M = 0.9f

        /** The dimensions are rebuilt at every 5 % of zoom. */
        const val ZOOM_STEP = 0.05f
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

    /** The atlas's figures: [FONT_PX] bold in a [ROW_HEIGHT] row, with [PAD_PX] of margin. */
    const val FONT_PX = 76f
    const val PAD_PX = 10f

    /**
     * How wide [text]'s label is in the atlas, in pixels, without drawing it: an upper estimate
     * (a bold figure is under [GLYPH_ADVANCE] of the font size wide, a point and a space far
     * less), for a framing that has to leave the label its room before it exists.
     */
    fun labelWidthPx(text: String): Float =
        (text.length * FONT_PX * GLYPH_ADVANCE + 2 * PAD_PX).coerceAtMost(ATLAS_WIDTH.toFloat())

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
     * Appends [side]'s dimension at height [y], drawn at [size]: the line outside the side,
     * [halfWidth] wide, and its label, sampling the first [labelWidthPx] pixels of its atlas row
     * (0 for a width side, 1 for a depth side).
     */
    @Suppress("LongParameterList")
    fun addDimension(
        mesh: DebugMesh,
        measure: RoomMeasure,
        side: Int,
        y: Float,
        size: MeasureSize,
        halfWidth: Float,
        labelWidthPx: Float,
    ) {
        val c = measure.corners
        val a = side % 4
        val b = (side + 1) % 4
        val (ox, oz) = outward(measure, side)
        val ax = c[a * 2]
        val az = c[a * 2 + 1]
        val bx = c[b * 2]
        val bz = c[b * 2 + 1]
        val offset = size.offset
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
        val row = side % 2
        val q = labelQuad(measure, side, size, labelWidthPx)
        val u1 = (labelWidthPx / ATLAS_WIDTH).coerceAtMost(1f)
        val v0 = vOf(row * ROW_HEIGHT.toFloat())
        val v1 = vOf((row + 1) * ROW_HEIGHT.toFloat())
        val tl = mesh.vertex(q[0], y, q[1], 0f, v0)
        val tr = mesh.vertex(q[2], y, q[3], u1, v0)
        val br = mesh.vertex(q[4], y, q[5], u1, v1)
        val bl = mesh.vertex(q[6], y, q[7], 0f, v1)
        mesh.quad(tl, tr, br, bl)
    }

    /**
     * How far the dimensions of [sides] reach on the floor at height [y], drawn at [size]: for
     * each, its label's four corners and the outer ends of its line, flat xyz. A view that fits
     * these with the room shows every figure whole. [labelWidthsPx] are the labels' widths in
     * the atlas, the width's then the depth's; their [labelWidthPx] estimates by default.
     */
    fun reach(
        measure: RoomMeasure,
        sides: IntArray,
        y: Float,
        size: MeasureSize,
        labelWidthsPx: FloatArray = floatArrayOf(
            labelWidthPx(RoomMeasure.metres(measure.width)),
            labelWidthPx(RoomMeasure.metres(measure.depth)),
        ),
    ): FloatArray {
        val out = FloatArray(sides.size * REACH_POINTS * 3)
        var at = 0
        fun add(x: Float, z: Float) {
            out[at++] = x
            out[at++] = y
            out[at++] = z
        }
        val c = measure.corners
        for (side in sides) {
            val a = side % 4
            val b = (side + 1) % 4
            val (ox, oz) = outward(measure, side)
            val ax = c[a * 2]
            val az = c[a * 2 + 1]
            val bx = c[b * 2]
            val bz = c[b * 2 + 1]
            val length = hypot(bx - ax, bz - az).coerceAtLeast(1e-6f)
            val far = size.offset * (1f + EXTENSION_OVERSHOOT)
            // The slashes run past each end of the line, along the side.
            val sx = (bx - ax) / length * SLASH * size.offset
            val sz = (bz - az) / length * SLASH * size.offset
            add(ax + ox * far - sx, az + oz * far - sz)
            add(bx + ox * far + sx, bz + oz * far + sz)
            val q = labelQuad(measure, side, size, labelWidthsPx[side % 2])
            for (corner in 0 until 4) add(q[corner * 2], q[corner * 2 + 1])
        }
        return out
    }

    /**
     * [side]'s label, as its four corners (x, z) — top left, top right, bottom right, bottom
     * left: beyond the line, its top towards the room, reading from outside (its right is
     * (-outside) × up), [labelWidthPx] of its [ROW_HEIGHT] px row wide.
     */
    private fun labelQuad(measure: RoomMeasure, side: Int, size: MeasureSize, labelWidthPx: Float): FloatArray {
        val (ox, oz) = outward(measure, side)
        val (sideX, sideZ) = midpoint(measure, side)
        val rx = oz
        val rz = -ox
        val out = size.offset * (1f + EXTENSION_GAP) + size.textHeight / 2f
        val mx = sideX + ox * out
        val mz = sideZ + oz * out
        val hw = size.textHeight * labelWidthPx / ROW_HEIGHT / 2f
        val hh = size.textHeight / 2f
        return floatArrayOf(
            mx - rx * hw - ox * hh, mz - rz * hw - oz * hh,
            mx + rx * hw - ox * hh, mz + rz * hw - oz * hh,
            mx + rx * hw + ox * hh, mz + rz * hw + oz * hh,
            mx - rx * hw + ox * hh, mz - rz * hw + oz * hh,
        )
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

    /** A bold figure's advance, as a share of the font size, rounded up. */
    private const val GLYPH_ADVANCE = 0.62f

    /** [reach] names this many points per side: the line's two outer ends, the label's corners. */
    private const val REACH_POINTS = 6
}
