package io.github.sceneview.demo.demos.internal

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The scan's frame laid on the plan's page (#4735): the turn and the shift [RoomSurveyor.of] gave
 * the survey's walls, so a box read off the scan — a part still to scan — lands where the plan
 * draws its room. Page metres, x along the page, y down it.
 */
class PlanPage private constructor(
    private val cos: Float,
    private val sin: Float,
    private val minU: Float,
    private val minV: Float,
    private val extentV: Float,
    private val turned: Boolean,
) {
    /** The scan's point ([x], [z]) on the page: `[x, y]`, metres. */
    fun toPage(x: Float, z: Float): FloatArray {
        val u = cos * x + sin * z - minU
        val v = -sin * x + cos * z - minV
        return if (turned) floatArrayOf(extentV - v, u) else floatArrayOf(u, v)
    }

    /**
     * [box]'s footprint on the page (`[minX, _, minZ, maxX, _, maxZ]` in the scan's frame): its
     * four corners, (x0, z0), (x1, z0), (x1, z1), (x0, z1), as `[x, y, …]`.
     */
    fun quad(box: FloatArray): FloatArray {
        val out = FloatArray(QUAD_FLOATS)
        val xs = floatArrayOf(box[0], box[3], box[3], box[0])
        val zs = floatArrayOf(box[2], box[2], box[5], box[5])
        for (i in 0 until QUAD_CORNERS) {
            val p = toPage(xs[i], zs[i])
            out[i * 2] = p[0]
            out[i * 2 + 1] = p[1]
        }
        return out
    }

    companion object {
        private const val QUAD_CORNERS = 4
        private const val QUAD_FLOATS = QUAD_CORNERS * 2

        /**
         * [survey]'s page, `null` when no scan's walls stand behind it — a room traced with a tape,
         * or one read off ARCore's planes: nothing read off the scan can be placed on it.
         */
        fun of(survey: RoomSurvey): PlanPage? {
            val structure = survey.structure ?: return null
            if (structure.walls.isEmpty() || survey.walls.isEmpty()) return null
            val c = cos(structure.yaw)
            val s = sin(structure.yaw)
            val u = structure.walls.map { c * it.ax + s * it.az }
            val v = structure.walls.map { -s * it.ax + c * it.az }
            val minU = u.min()
            val minV = v.min()
            val extentU = u.max() - minU
            val extentV = v.max() - minV
            return PlanPage(c, s, minU, minV, extentV, turned = extentV > extentU)
        }
    }
}

/**
 * The floor every result view stands its room on (#4735): the dimensioned grid — a line every
 * [ArDebugGeometry.GRID_CELL_M], a bolder one every metre — under the plan, the 3D model and the
 * dollhouse, and the room's dimensions on it.
 */
object ResultFloor {
    /** How far under the dollhouse's plinth its grid lies, metres: clear of its underside. */
    const val UNDER_M = 0.005f

    /** How far over the grid the dimensions lie, metres: on it, never through it. */
    const val MEASURE_LIFT_M = 0.003f

    /** The plan's grid in page metres: segments `[x0, y0, x1, y1, …]`, [minor] and [major]. */
    class PlanGrid(val minor: FloatArray, val major: FloatArray)

    /**
     * The room's dimensions read off the scan's walls: the rectangle squared to them that holds
     * them, `null` without walls read off a scan, or for a room under [RoomMeasure.MIN_SIDE_M].
     */
    fun measureOf(survey: RoomSurvey): RoomMeasure? {
        val structure = survey.structure ?: return null
        if (structure.walls.isEmpty()) return null
        val c = cos(structure.yaw)
        val s = sin(structure.yaw)
        var minA = Float.MAX_VALUE
        var maxA = -Float.MAX_VALUE
        var minB = Float.MAX_VALUE
        var maxB = -Float.MAX_VALUE
        for (wall in structure.walls) {
            val a = c * wall.ax + s * wall.az
            val b = -s * wall.ax + c * wall.az
            minA = min(minA, a)
            maxA = max(maxA, a)
            minB = min(minB, b)
            maxB = max(maxB, b)
        }
        val width = maxA - minA
        val depth = maxB - minB
        if (min(width, depth) < RoomMeasure.MIN_SIDE_M) return null
        val own = floatArrayOf(minA, minB, maxA, minB, maxA, maxB, minA, maxB)
        val corners = FloatArray(own.size) { k ->
            val a = own[k / 2 * 2]
            val b = own[k / 2 * 2 + 1]
            if (k % 2 == 0) a * c - b * s else a * s + b * c
        }
        return RoomMeasure(corners, width, depth, structure.yaw)
    }

    /**
     * Where the page's origin falls in [drawing], pixels: `[x, y]`, so a page point `(px, py)` is
     * drawn at `origin + p × pxPerMetre`. `null` when [survey] has no walls on it.
     */
    fun planOrigin(drawing: RoomPlanDrawing, survey: RoomSurvey): FloatArray? {
        val first = survey.walls.firstOrNull() ?: return null
        if (drawing.floor.size < 2 || drawing.pxPerMetre <= 0f) return null
        return floatArrayOf(
            drawing.floor[0] - first.ax * drawing.pxPerMetre,
            drawing.floor[1] - first.ay * drawing.pxPerMetre,
        )
    }

    /**
     * The plan's grid over its room, from the room's top-left corner: squared to its walls, as
     * the plan is, where the 3D views' grid is squared to the scan's axes. The plan clips it to
     * the floor.
     */
    fun planGrid(survey: RoomSurvey): PlanGrid {
        if (survey.walls.isEmpty()) return PlanGrid(FloatArray(0), FloatArray(0))
        val width = survey.walls.maxOf { max(it.ax, it.bx) }
        val height = survey.walls.maxOf { max(it.ay, it.by) }
        val cell = ArDebugGeometry.GRID_CELL_M
        val minor = ArrayList<Float>()
        val major = ArrayList<Float>()
        val across = floor(width / cell + EPS).toInt()
        val down = floor(height / cell + EPS).toInt()
        for (i in 0..across) {
            val x = i * cell
            (if (i % ArDebugGeometry.GRID_MAJOR_EVERY == 0) major else minor).addAll(listOf(x, 0f, x, height))
        }
        for (k in 0..down) {
            val y = k * cell
            (if (k % ArDebugGeometry.GRID_MAJOR_EVERY == 0) major else minor).addAll(listOf(0f, y, width, y))
        }
        return PlanGrid(minor.toFloatArray(), major.toFloatArray())
    }

    /** Where the dollhouse's plinth stands, its underside, in the scan's frame. */
    fun dollhouseFoot(fit: DollhouseFit): Float =
        fit.floorY - RoomDollhouse.BASE_DROP_M - RoomDollhouse.plinthThickness(fit.scale)

    /**
     * The bounds the dollhouse's grid is laid round (`[minX, y, minZ, maxX, y, maxZ]`): its
     * plinth's footprint, [UNDER_M] under it. The grid runs on past it and fades out.
     */
    fun dollhouseGridBounds(fit: DollhouseFit): FloatArray {
        val y = dollhouseFoot(fit) - UNDER_M
        val halfWidth = fit.width / 2f + RoomDollhouse.BASE_MARGIN_M
        val halfDepth = fit.depth / 2f + RoomDollhouse.BASE_MARGIN_M
        return floatArrayOf(
            fit.centerX - halfWidth, y, fit.centerZ - halfDepth,
            fit.centerX + halfWidth, y, fit.centerZ + halfDepth,
        )
    }

    /** The height of the dollhouse's dimensions: on its grid, round the plinth's foot. */
    fun dollhouseMeasureY(fit: DollhouseFit): Float = dollhouseFoot(fit) - UNDER_M + MEASURE_LIFT_M

    private const val EPS = 1e-4f
}
