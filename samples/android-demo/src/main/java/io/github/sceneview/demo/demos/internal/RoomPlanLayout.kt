package io.github.sceneview.demo.demos.internal

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * A room survey as a dimensioned floor plan (#4540): the outline to scale, each wall's length on
 * a dimension line outside it, the area in the middle and a scale bar. Pure geometry — the
 * screen and the PDF page paint the same marks, each with its own ink.
 */

/** How a survey's figures are written: metric, rounded to what a phone's scan can stand behind. */
object SurveyFormat {
    /** Lengths are given to the nearest 5 cm. */
    const val LENGTH_STEP_M = 0.05f

    /**
     * Areas are given to the nearest tenth of a square metre, beside sides given to 5 cm: a
     * coarser step would move the area further than its sides do. How far the figure can be
     * trusted is what its "±" says, not its last digit.
     */
    const val AREA_STEP_M2 = 0.1f

    /** The mark in front of a figure that was not measured end to end. */
    const val ESTIMATE = "≈ "

    fun length(metres: Float): String = "%.2f".format(Locale.US, (metres / LENGTH_STEP_M).roundToInt() * LENGTH_STEP_M)

    fun area(squareMetres: Float): String =
        "%.1f".format(Locale.US, (squareMetres / AREA_STEP_M2).roundToInt() * AREA_STEP_M2)

    /** A length's spread, rounded up: never tighter than a step. */
    fun lengthSpread(metres: Float): String =
        "%.2f".format(Locale.US, max(1, ceil(metres / LENGTH_STEP_M - EPSILON).toInt()) * LENGTH_STEP_M)

    /** An area's spread, rounded up: never tighter than a step. */
    fun areaSpread(squareMetres: Float): String =
        "%.1f".format(Locale.US, max(1, ceil(squareMetres / AREA_STEP_M2 - EPSILON).toInt()) * AREA_STEP_M2)

    /** A wall's length as the plan labels it: `≈ 4.00` when part of it was not seen. */
    fun wall(wall: SurveyWall): String = (if (wall.length.estimate) ESTIMATE else "") + length(wall.length.value)

    private const val EPSILON = 1e-3f
}

/** What a line of the plan is, so each surface paints it its own way. */
enum class PlanStroke { Wall, WallUnseen, Dimension, Extension, Tick, Scale }

/** What a text of the plan is. */
enum class PlanTextRole { Dimension, Area, AreaNote, Tag, Scale }

class PlanLine(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val stroke: PlanStroke)

/**
 * A text centred on ([cx], [cy]), [size] high and [width] long; [vertical] ones read upwards.
 * [left]..[bottom] is the box it covers on the page.
 */
class PlanText(
    val text: String,
    val cx: Float,
    val cy: Float,
    val size: Float,
    val width: Float,
    val role: PlanTextRole,
    val vertical: Boolean = false,
) {
    private val across: Float get() = size * RoomPlanLayout.LINE_HEIGHT
    val left: Float get() = cx - (if (vertical) across else width) / 2
    val right: Float get() = cx + (if (vertical) across else width) / 2
    val top: Float get() = cy - (if (vertical) width else across) / 2
    val bottom: Float get() = cy + (if (vertical) width else across) / 2

    fun overlaps(other: PlanText): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/** The page's ruler: [unit] is one dp (or one point on paper), [measure] a text's length at a size. */
class PlanMetrics(val unit: Float, val measure: (text: String, size: Float) -> Float)

/** The words on a plan: one label per wall, the area (`null`: none is given) and how a scale bar reads. */
class PlanTexts(
    val walls: List<String>,
    val area: String?,
    val areaNote: String?,
    val scale: (metres: Float) -> String,
)

/** A plan laid out in a [width] × [height] box, at [pxPerMetre]. [floor] is the outline, x then y. */
class RoomPlanDrawing(
    val width: Float,
    val height: Float,
    val pxPerMetre: Float,
    val floor: FloatArray,
    val lines: List<PlanLine>,
    val texts: List<PlanText>,
)

object RoomPlanLayout {
    /** A text's line is this many times its size. */
    const val LINE_HEIGHT = 1.25f

    /** Sizes and distances, in [PlanMetrics.unit]s. */
    const val DIMENSION_TEXT = 11f
    const val AREA_TEXT = 20f
    const val NOTE_TEXT = 11f
    const val TAG_TEXT = 10f
    private const val CHAIN_OFFSET = 20f
    private const val CHAIN_PITCH = 24f
    private const val EXTENSION_GAP = 5f
    private const val EXTENSION_OVER = 4f
    private const val TICK = 3.5f
    private const val LABEL_GAP = 3f
    private const val PADDING = 8f
    private const val BARE_SIDE = 14f
    private const val SCALE_BAND = 30f
    private const val SCALE_MIN = 56f
    private const val TAG_INSET = 11f
    private const val TAG_MIN_WALL = 26f
    private const val TOP = 0
    private const val RIGHT = 1
    private const val BOTTOM = 2
    private const val LEFT = 3
    private const val SIDES = 4
    private const val MAX_PASSES = 4
    private const val POLE_STEPS = 40

    /** The round lengths a scale bar is drawn at, metres. */
    private val SCALE_BARS = floatArrayOf(0.5f, 1f, 2f, 5f, 10f)

    /**
     * Lays [survey] out in a [width] × [height] box. With [scales] (pixels per metre, any
     * order) the plan is drawn at the largest of them that fits — a paper plan is at 1:50, not
     * at 1:43; without, it fills the box.
     */
    fun layout(
        survey: RoomSurvey,
        texts: PlanTexts,
        width: Float,
        height: Float,
        metrics: PlanMetrics,
        scales: List<Float>? = null,
    ): RoomPlanDrawing {
        if (!survey.hasPlan) return RoomPlanDrawing(width, height, 0f, FloatArray(0), emptyList(), emptyList())
        var rows = IntArray(SIDES)
        var drawing: RoomPlanDrawing? = null
        // A label that does not fit its wall moves a row out, which takes room: laid out again until it holds.
        repeat(MAX_PASSES) {
            if (drawing != null && rows.all { it == 0 }) return@repeat
            val needed = IntArray(SIDES)
            val pass = Pass(survey, texts, width, height, metrics, scales, rows.copyOf(), needed)
            val settled = drawing != null && (0 until SIDES).all { needed[it] <= rows[it] }
            drawing = pass.drawing
            if (settled || needed.all { it == 0 }) return drawing!!
            rows = IntArray(SIDES) { max(rows[it], needed[it]) }
        }
        return drawing!!
    }

    /** Which side of the outline a wall's dimension goes on: the one its outer face looks at. */
    private fun sideOf(wall: SurveyWall): Int = when {
        wall.horizontal && wall.bx > wall.ax -> TOP
        wall.horizontal -> BOTTOM
        wall.by > wall.ay -> RIGHT
        else -> LEFT
    }

    @Suppress("LongParameterList") // one layout pass: its inputs, and the rows it found it needs
    private class Pass(
        private val survey: RoomSurvey,
        private val texts: PlanTexts,
        width: Float,
        height: Float,
        private val metrics: PlanMetrics,
        scales: List<Float>?,
        private val rows: IntArray,
        private val needed: IntArray,
    ) {
        private val u = metrics.unit
        private val lines = ArrayList<PlanLine>()
        private val placed = ArrayList<PlanText>()
        private val levels = IntArray(SIDES)
        private val levelOf = IntArray(survey.walls.size)
        private val scale: Float
        private val originX: Float
        private val originY: Float
        val drawing: RoomPlanDrawing

        init {
            assignLevels()
            val rowHeight = DIMENSION_TEXT * LINE_HEIGHT * u
            val margins = FloatArray(SIDES) { side ->
                if (levels[side] == 0) {
                    BARE_SIDE * u
                } else {
                    (CHAIN_OFFSET + (levels[side] - 1) * CHAIN_PITCH + LABEL_GAP + PADDING) * u +
                        rowHeight * (1 + rows[side])
                }
            }
            margins[BOTTOM] += SCALE_BAND * u
            val roomW = width - margins[LEFT] - margins[RIGHT]
            val roomH = height - margins[TOP] - margins[BOTTOM]
            val fit = max(1f, min(roomW / survey.planLength, roomH / survey.planWidth))
            scale = scales?.filter { it <= fit }?.maxOrNull() ?: fit
            originX = margins[LEFT] + (roomW - survey.planLength * scale) / 2
            originY = margins[TOP] + (roomH - survey.planWidth * scale) / 2
            walls()
            dimensions()
            centre()
            tags()
            scaleBar(height)
            val floor = FloatArray(survey.walls.size * 2)
            survey.walls.forEachIndexed { i, wall ->
                floor[i * 2] = x(wall.ax)
                floor[i * 2 + 1] = y(wall.ay)
            }
            drawing = RoomPlanDrawing(width, height, scale, floor, lines, placed)
        }

        private fun x(metres: Float) = originX + metres * scale
        private fun y(metres: Float) = originY + metres * scale

        /** Two walls dimensioned on the same side, one behind the other, take two lines. */
        private fun assignLevels() {
            for (side in 0 until SIDES) {
                val taken = ArrayList<MutableList<ClosedFloatingPointRange<Float>>>()
                survey.walls.indices.filter { sideOf(survey.walls[it]) == side }.forEach { i ->
                    val wall = survey.walls[i]
                    val span = when {
                        wall.horizontal -> min(wall.ax, wall.bx)..max(wall.ax, wall.bx)
                        else -> min(wall.ay, wall.by)..max(wall.ay, wall.by)
                    }
                    var level = 0
                    fun crowded(row: List<ClosedFloatingPointRange<Float>>) =
                        row.any { it.start < span.endInclusive - OVERLAP && span.start < it.endInclusive - OVERLAP }
                    while (level < taken.size && crowded(taken[level])) level++
                    if (level == taken.size) taken += ArrayList<ClosedFloatingPointRange<Float>>()
                    taken[level] += span
                    levelOf[i] = level
                }
                levels[side] = taken.size
            }
        }

        private fun walls() {
            for (wall in survey.walls) {
                val length = wall.length.value
                if (length <= 0f) continue
                val dx = (wall.bx - wall.ax) / length
                val dy = (wall.by - wall.ay) / length
                var at = 0f
                fun run(from: Float, to: Float, stroke: PlanStroke) {
                    if (to - from <= 0f) return
                    lines += PlanLine(
                        x(wall.ax + dx * from),
                        y(wall.ay + dy * from),
                        x(wall.ax + dx * to),
                        y(wall.ay + dy * to),
                        stroke,
                    )
                }
                for (gap in wall.unseen) {
                    run(at, gap.start, PlanStroke.Wall)
                    run(gap.start, gap.endInclusive, PlanStroke.WallUnseen)
                    at = gap.endInclusive
                }
                run(at, length, PlanStroke.Wall)
            }
        }

        @Suppress("LongMethod") // a dimension is its line, two ticks, two extension lines and a label
        private fun dimensions() {
            val left = x(0f)
            val right = x(survey.planLength)
            val top = y(0f)
            val bottom = y(survey.planWidth)
            val rowHeight = DIMENSION_TEXT * LINE_HEIGHT * u
            survey.walls.forEachIndexed { i, wall ->
                val side = sideOf(wall)
                val out = if (side == TOP || side == LEFT) -1f else 1f
                val edge = when (side) {
                    TOP -> top
                    BOTTOM -> bottom
                    LEFT -> left
                    else -> right
                }
                val line = edge + out * (CHAIN_OFFSET + levelOf[i] * CHAIN_PITCH) * u
                val outermost = edge + out * (CHAIN_OFFSET + (levels[side] - 1) * CHAIN_PITCH) * u
                val text = texts.walls.getOrElse(i) { "" }
                val length = metrics.measure(text, DIMENSION_TEXT * u)
                if (wall.horizontal) {
                    val from = x(min(wall.ax, wall.bx))
                    val to = x(max(wall.ax, wall.bx))
                    lines += PlanLine(from, line, to, line, PlanStroke.Dimension)
                    for (at in floatArrayOf(from, to)) {
                        val root = y(wall.ay) + out * EXTENSION_GAP * u
                        lines += PlanLine(at, root, at, line + out * EXTENSION_OVER * u, PlanStroke.Extension)
                        val tick = TICK * u
                        lines += PlanLine(at - tick, line + tick, at + tick, line - tick, PlanStroke.Tick)
                    }
                    place(side, text, length, fits = length <= to - from - 2 * TICK * u) { row ->
                        val base = if (row == 0) line else outermost
                        val across = base + out * (LABEL_GAP * u + rowHeight / 2 + row * rowHeight)
                        PlanText(text, (from + to) / 2, across, DIMENSION_TEXT * u, length, PlanTextRole.Dimension)
                    }
                } else {
                    val from = y(min(wall.ay, wall.by))
                    val to = y(max(wall.ay, wall.by))
                    lines += PlanLine(line, from, line, to, PlanStroke.Dimension)
                    for (at in floatArrayOf(from, to)) {
                        val root = x(wall.ax) + out * EXTENSION_GAP * u
                        lines += PlanLine(root, at, line + out * EXTENSION_OVER * u, at, PlanStroke.Extension)
                        val tick = TICK * u
                        lines += PlanLine(line - tick, at + tick, line + tick, at - tick, PlanStroke.Tick)
                    }
                    place(side, text, length, fits = length <= to - from - 2 * TICK * u) { row ->
                        val base = if (row == 0) line else outermost
                        PlanText(
                            text, base + out * (LABEL_GAP * u + rowHeight / 2 + row * rowHeight), (from + to) / 2,
                            DIMENSION_TEXT * u, length, PlanTextRole.Dimension, vertical = true,
                        )
                    }
                }
            }
        }

        /** Puts a label on the first row out where it covers no other; a row past 0 is asked of the next pass. */
        private fun place(side: Int, text: String, length: Float, fits: Boolean, at: (row: Int) -> PlanText) {
            if (text.isEmpty() || length <= 0f) return
            var row = if (fits) 0 else 1
            var label = at(row)
            while (row < MAX_ROWS && placed.any { it.overlaps(label) }) label = at(++row)
            needed[side] = max(needed[side], row)
            placed += label
        }

        /** The area, where the room is widest: the point of the floor furthest from every wall. */
        private fun centre() {
            val area = texts.area ?: texts.areaNote ?: return
            val note = texts.areaNote.takeIf { texts.area != null }
            var bestX = survey.planLength / 2
            var bestY = survey.planWidth / 2
            var best = -1f
            for (j in 1 until POLE_STEPS) {
                for (i in 1 until POLE_STEPS) {
                    val px = survey.planLength * i / POLE_STEPS
                    val py = survey.planWidth * j / POLE_STEPS
                    if (!inside(px, py)) continue
                    // A label is wider than it is high: the room to its sides counts half.
                    var reach = Float.MAX_VALUE
                    for (wall in survey.walls) {
                        reach = min(reach, if (wall.horizontal) abs(py - wall.ay) * 2 else abs(px - wall.ax))
                    }
                    if (reach > best + EPSILON) {
                        best = reach
                        bestX = px
                        bestY = py
                    }
                }
            }
            val size = (if (texts.area != null) AREA_TEXT else NOTE_TEXT) * u
            val shift = if (note != null) NOTE_TEXT * LINE_HEIGHT * u / 2 else 0f
            val role = if (texts.area != null) PlanTextRole.Area else PlanTextRole.AreaNote
            placed += PlanText(area, x(bestX), y(bestY) - shift, size, metrics.measure(area, size), role)
            if (note != null) {
                val at = y(bestY) - shift + (size * LINE_HEIGHT + NOTE_TEXT * LINE_HEIGHT * u) / 2
                val noteWidth = metrics.measure(note, NOTE_TEXT * u)
                placed += PlanText(note, x(bestX), at, NOTE_TEXT * u, noteWidth, PlanTextRole.AreaNote)
            }
        }

        private fun inside(px: Float, py: Float): Boolean {
            var crossings = 0
            for (wall in survey.walls) {
                if (wall.horizontal) continue
                val low = min(wall.ay, wall.by)
                val high = max(wall.ay, wall.by)
                if (py >= low && py < high && wall.ax > px) crossings++
            }
            return crossings % 2 == 1
        }

        /** Each wall's letter, just inside it — where it covers nothing else. */
        private fun tags() {
            for (wall in survey.walls) {
                if (wall.length.value * scale < TAG_MIN_WALL * u) continue
                val mx = x((wall.ax + wall.bx) / 2)
                val my = y((wall.ay + wall.by) / 2)
                val inset = TAG_INSET * u
                val (tx, ty) = when (sideOf(wall)) {
                    TOP -> mx to my + inset
                    BOTTOM -> mx to my - inset
                    LEFT -> mx + inset to my
                    else -> mx - inset to my
                }
                val size = TAG_TEXT * u
                val tag = PlanText(wall.tag, tx, ty, size, metrics.measure(wall.tag, size), PlanTextRole.Tag)
                if (placed.none { it.overlaps(tag) }) placed += tag
            }
        }

        /** A bar of a round length under the plan's left corner, its length written at its end. */
        private fun scaleBar(height: Float) {
            val metres = SCALE_BARS.firstOrNull { it * scale >= SCALE_MIN * u } ?: SCALE_BARS.last()
            val from = min(x(0f), drawingLeft())
            val to = from + metres * scale
            val at = height - PADDING * u - TICK * u
            lines += PlanLine(from, at, to, at, PlanStroke.Scale)
            lines += PlanLine(from, at - TICK * u, from, at + TICK * u, PlanStroke.Scale)
            lines += PlanLine((from + to) / 2, at - TICK * u, (from + to) / 2, at, PlanStroke.Scale)
            lines += PlanLine(to, at - TICK * u, to, at + TICK * u, PlanStroke.Scale)
            val text = texts.scale(metres)
            val length = metrics.measure(text, DIMENSION_TEXT * u)
            val labelX = to + LABEL_GAP * 2 * u + length / 2
            placed += PlanText(text, labelX, at - TICK * u / 2, DIMENSION_TEXT * u, length, PlanTextRole.Scale)
        }

        private fun drawingLeft() = x(0f)

        private companion object {
            const val OVERLAP = 0.01f
            const val MAX_ROWS = 6
            const val EPSILON = 1e-4f
        }
    }
}
