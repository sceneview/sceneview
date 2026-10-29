package io.github.sceneview.demo.demos.internal

import java.io.OutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The floor plan as a DXF drawing, for a CAD tool to take it up where the PDF stops.
 *
 * DXF `AC1015` (R2000, the version AutoCAD 2000 introduced) in ASCII: the oldest version with the
 * light polyline. Its structure is that of the R2000 file ezdxf writes, plot styles, classes and
 * dictionaries included, which the unit tests check by reading both back. Its y runs up, as CAD has it. One drawing unit is a millimetre, metric
 * (`$INSUNITS` 4), or an inch, imperial (`$INSUNITS` 1), when the figures are in feet and inches:
 * a CAD tool set to either system then takes the drawing in at its true size, and reads the
 * distances it measures in metres, or in feet and inches (`$LUNITS` and, for its dimensions,
 * `$DIMLUNIT` 4, architectural). Five layers:
 * - [WALLS]: the room's outline, one closed polyline;
 * - [OPENINGS]: each door with its leaf and swing, each window with its frame, and its size;
 * - [DIMENSIONS]: each wall's length as plain lines and text. No DIMENSION entity: a CAD tool
 *   redraws one its own way and recomputes its figure from the line, which would drop the
 *   plan's `≈` and its rounding;
 * - [TEXT]: the room's name and floor area;
 * - [LEGEND]: what the drawing is, and that it is to be checked on site.
 *
 * The figures are the plan's own, the PDF's, in the wording's units.
 */
object RoomPlanDxf {
    const val WALLS = "WALLS"
    const val OPENINGS = "OPENINGS"
    const val DIMENSIONS = "DIMENSIONS"
    const val TEXT = "TEXT"
    const val LEGEND = "LEGEND"

    enum class OpeningKind { Door, Window }

    /**
     * A door or a window in the survey's wall number [wall], starting [from] metres along it and
     * [width] metres wide; its [height] and, for a window, its [sill] (its bottom above the
     * floor) in metres, when known.
     */
    class Opening(
        val kind: OpeningKind,
        val wall: Int,
        val from: Float,
        val width: Float,
        val height: Float? = null,
        val sill: Float? = null,
    )

    /**
     * What the drawing says besides its figures: [door] and [window] name an opening around its
     * size (`Door %1$s`), [sill] gives a window's sill height (`sill %1$s`), and [legend] are the
     * lines under the plan, the warning to check it on site among them.
     */
    class Words(
        val legend: List<String>,
        val door: String,
        val window: String,
        val sill: String,
    )

    /**
     * Writes [survey] with its [openings] to [out] as a DXF drawing, its figures worded by [wording].
     *
     * @param labels each wall's dimension, in the order of [RoomSurvey.walls]: the plan's own. A
     *   blank one, a wall with no length to give, leaves its wall undimensioned: no line without its figure.
     * @throws IllegalArgumentException when a figure to draw is not a finite number.
     */
    fun write(
        out: OutputStream,
        survey: RoomSurvey,
        wording: SurveyWording,
        words: Words,
        openings: List<Opening> = emptyList(),
        labels: List<String> = SurveyReport.planTexts(survey, wording).walls,
    ) {
        val drawing = Drawing(survey.planWidth, DrawingUnit.of(wording.units))
        if (survey.hasPlan) {
            drawing.outline(survey)
            drawing.dimensions(survey, labels)
            for (opening in openings) drawing.opening(survey, opening, label(opening, words, wording.units))
            drawing.room(survey, wording)
        }
        drawing.legend(words.legend)
        out.write(drawing.file().toByteArray(Charsets.US_ASCII))
    }

    /** `Door 0.90 × 2.10`, `Window 1.20 × 1.20, sill 0.90`: the size the plan knows, in its units. */
    private fun label(opening: Opening, words: Words, units: MeasureUnitSystem): String {
        fun length(metres: Float) = when (units) {
            MeasureUnitSystem.Metric -> SurveyFormat.length(metres)
            MeasureUnitSystem.Imperial -> SurveyFormat.feetAndInchesMark(metres)
        }
        val size = length(opening.width) + (opening.height?.let { " × " + length(it) } ?: "")
        val name = (if (opening.kind == OpeningKind.Door) words.door else words.window).format(size)
        return name + (opening.sill?.let { ", " + words.sill.format(length(it)) } ?: "")
    }

    /**
     * The drawing's unit: a millimetre in metric, an inch in imperial, [perMillimetre] of it to a
     * millimetre. [insUnits] and [measurement] name it to a CAD tool; [linearUnits] and
     * [precision] say how the tool shows the distances it measures: in decimals to the
     * millimetre, or in feet and inches to the sixteenth of an inch. [dimensionUnits], when set,
     * says it of the tool's dimensions and measuring tools too (`$DIMLUNIT`): architectural for the
     * inch, which they would otherwise give in decimal inches. The millimetre leaves it out, and
     * a CAD tool then takes it as 2, decimal.
     */
    private enum class DrawingUnit(
        val perMillimetre: Double,
        val insUnits: Int,
        val measurement: Int,
        val linearUnits: Int,
        val precision: Int,
        val dimensionUnits: Int? = null,
    ) {
        Millimetre(perMillimetre = 1.0, insUnits = 4, measurement = 1, linearUnits = 2, precision = 0),
        Inch(perMillimetre = 1 / MM_PER_INCH, insUnits = 1, measurement = 0, linearUnits = 4, precision = 4, dimensionUnits = 4),
        ;

        companion object {
            fun of(units: MeasureUnitSystem) = when (units) {
                MeasureUnitSystem.Metric -> Millimetre
                MeasureUnitSystem.Imperial -> Inch
            }
        }
    }

    /** A point or a direction on the drawing, in millimetres, written out in the [DrawingUnit]. */
    private class V(val x: Double, val y: Double) {
        operator fun plus(o: V) = V(x + o.x, y + o.y)
        operator fun minus(o: V) = V(x - o.x, y - o.y)
        operator fun times(k: Double) = V(x * k, y * k)
        operator fun unaryMinus() = V(-x, -y)
        val length get() = hypot(x, y)
    }

    /**
     * The drawing being written: its entities, the extents they cover, and the handles given out.
     * The plan's y runs down the page from its top edge at [top] metres; the drawing's runs up.
     * Everything is laid out in millimetres, and written in [unit].
     */
    @Suppress("TooManyFunctions") // one small function per DXF object
    private class Drawing(private val top: Float, private val unit: DrawingUnit) {
        private var handles = 0
        private fun handle() = (++handles).toString(HEX).uppercase(Locale.US)

        // What other objects point at, given out before any entity.
        private val vportTable = handle()
        private val ltypeTable = handle()
        private val layerTable = handle()
        private val styleTable = handle()
        private val viewTable = handle()
        private val ucsTable = handle()
        private val appidTable = handle()
        private val dimstyleTable = handle()
        private val blockTable = handle()
        private val modelRecord = handle()
        private val paperRecord = handle()
        private val root = handle()
        private val groups = handle()
        private val layouts = handle()
        private val modelLayout = handle()
        private val paperLayout = handle()
        private val mlineStyles = handle()
        private val mlineStandard = handle()
        private val plotSettings = handle()
        private val plotStyles = handle()
        private val normalPlotStyle = handle()

        private val entities = StringBuilder()
        private var minX = Double.MAX_VALUE
        private var minY = Double.MAX_VALUE
        private var maxX = -Double.MAX_VALUE
        private var maxY = -Double.MAX_VALUE
        private val covered get() = minX <= maxX

        fun at(x: Float, y: Float) = V(x * MM, (top - y) * MM)

        /** A length laid out in millimetres, in the drawing's unit. */
        private fun drawn(length: Double) = length * unit.perMillimetre

        /** A point laid out in millimetres, in the drawing's unit. */
        private fun drawn(p: V) = p * unit.perMillimetre

        /** The walls, one closed polyline through each wall's start — and its end, where the next wall does not start there. */
        fun outline(survey: RoomSurvey) {
            val corners = ArrayList<V>()
            survey.walls.forEachIndexed { i, wall ->
                corners += at(wall.ax, wall.ay)
                val next = survey.walls[(i + 1) % survey.walls.size]
                val end = at(wall.bx, wall.by)
                if ((end - at(next.ax, next.ay)).length > JOIN) corners += end
            }
            entity("LWPOLYLINE", WALLS)
            entities.pair(100, "AcDbPolyline")
            entities.pair(90, corners.size)
            entities.pair(70, 1)
            entities.pair(43, 0.0)
            for (corner in corners) {
                entities.pair(10, drawn(corner.x))
                entities.pair(20, drawn(corner.y))
                cover(corner)
            }
        }

        /**
         * Each wall's length, laid out as the PDF lays it: a wall square to the page on a chain
         * outside the outline, on the side it faces, a second chain behind the first where two
         * walls on that side overlap; an oblique wall along itself.
         */
        fun dimensions(survey: RoomSurvey, labels: List<String>) {
            val ends = survey.walls.map { at(it.ax, it.ay) to at(it.bx, it.by) }
            val low = V(ends.minOf { min(it.first.x, it.second.x) }, ends.minOf { min(it.first.y, it.second.y) })
            val high = V(ends.maxOf { max(it.first.x, it.second.x) }, ends.maxOf { max(it.first.y, it.second.y) })
            val chains = HashMap<Edge, MutableList<MutableList<ClosedFloatingPointRange<Double>>>>()
            ends.forEachIndexed { i, (a, b) ->
                val label = labels.getOrElse(i) { "" }
                val d = b - a
                if (label.isBlank() || d.length < JOIN) return@forEachIndexed
                if (min(abs(d.x), abs(d.y)) > ALIGNED * max(abs(d.x), abs(d.y))) {
                    dimension(a, b, label, DIMENSION_OFFSET)
                    return@forEachIndexed
                }
                val out = outward(d * (1 / d.length))
                val across = abs(out.y) >= abs(out.x)
                val edge = when {
                    across && out.y > 0 -> Edge.Top
                    across -> Edge.Bottom
                    out.x > 0 -> Edge.Right
                    else -> Edge.Left
                }
                val span = if (across) min(a.x, b.x)..max(a.x, b.x) else min(a.y, b.y)..max(a.y, b.y)
                val rows = chains.getOrPut(edge) { ArrayList() }
                var row = rows.indexOfFirst { taken ->
                    taken.none { it.start < span.endInclusive - JOIN && span.start < it.endInclusive - JOIN }
                }
                if (row < 0) {
                    row = rows.size
                    rows += ArrayList<ClosedFloatingPointRange<Double>>()
                }
                rows[row] += span
                val reach = DIMENSION_OFFSET + row * CHAIN_PITCH
                // Squared to the page, measured from the wall's start out to its chain.
                when (edge) {
                    Edge.Top -> dimension(a, V(b.x, a.y), label, high.y - a.y + reach)
                    Edge.Bottom -> dimension(a, V(b.x, a.y), label, a.y - low.y + reach)
                    Edge.Right -> dimension(a, V(a.x, b.y), label, high.x - a.x + reach)
                    Edge.Left -> dimension(a, V(a.x, b.y), label, a.x - low.x + reach)
                }
            }
        }

        /**
         * The wall from [a] to [b] dimensioned [offset] outside it: extension lines, the dimension
         * line with an oblique tick at each end, and [label] over its middle.
         */
        private fun dimension(a: V, b: V, label: String, offset: Double) {
            val length = (b - a).length
            if (length < JOIN) return
            val u = (b - a) * (1 / length)
            val out = outward(u)
            val from = a + out * offset
            val to = b + out * offset
            line(DIMENSIONS, a + out * EXTENSION_GAP, a + out * (offset + EXTENSION_OVER))
            line(DIMENSIONS, b + out * EXTENSION_GAP, b + out * (offset + EXTENSION_OVER))
            line(DIMENSIONS, from, to)
            val tick = (u + out) * (TICK / sqrt(2.0))
            line(DIMENSIONS, from - tick, from + tick)
            line(DIMENSIONS, to - tick, to + tick)
            val middle = (from + to) * 0.5
            text(DIMENSIONS, middle + out * (LABEL_GAP + DIMENSION_TEXT / 2), DIMENSION_TEXT, label, readable(u))
        }

        /**
         * [opening] on its wall: its jambs; a door's leaf open square into the room and its swing,
         * a window's frame; [label] just outside the wall, under the wall's dimension.
         */
        fun opening(survey: RoomSurvey, opening: Opening, label: String) {
            val wall = survey.walls.getOrNull(opening.wall) ?: return
            val a = at(wall.ax, wall.ay)
            val b = at(wall.bx, wall.by)
            val length = (b - a).length
            if (length < JOIN) return
            val u = (b - a) * (1 / length)
            val out = outward(u)
            val from = (opening.from * MM).coerceIn(0.0, length)
            val width = min(opening.width * MM, length - from)
            if (width < JOIN) return
            val start = a + u * from
            val end = start + u * width
            for (jamb in listOf(start, end)) line(OPENINGS, jamb + out * JAMB, jamb - out * JAMB)
            when (opening.kind) {
                OpeningKind.Door -> {
                    line(OPENINGS, start, start - out * width)
                    // Counter-clockwise from the open leaf to the closed one: the quarter inside the room.
                    arc(OPENINGS, start, width, degrees(-out), degrees(u))
                }
                OpeningKind.Window -> {
                    line(OPENINGS, start + out * FRAME, end + out * FRAME)
                    line(OPENINGS, start - out * FRAME, end - out * FRAME)
                }
            }
            text(OPENINGS, (start + end) * 0.5 + out * OPENING_LABEL, OPENING_TEXT, label, readable(u))
        }

        /** The room's name, its floor area under it, where the room is widest. */
        fun room(survey: RoomSurvey, wording: SurveyWording) {
            val room = SurveyReport.areas(survey, wording).rooms.firstOrNull() ?: return
            val centre = widest(survey)
            val area = room.area?.let { SurveyReport.area(it, wording.units) }
            if (area == null) {
                text(TEXT, centre, ROOM_TEXT, room.name)
            } else {
                text(TEXT, centre + V(0.0, ROOM_GAP), ROOM_TEXT, room.name)
                text(TEXT, centre - V(0.0, ROOM_GAP), AREA_TEXT, area)
            }
        }

        /** [lines] down from under everything drawn, flush with its left edge. */
        fun legend(lines: List<String>) {
            val left = if (covered) minX else 0.0
            var y = (if (covered) minY else 0.0) - LEGEND_GAP
            for (line in lines) {
                text(LEGEND, V(left, y), LEGEND_TEXT, line, centred = false)
                y -= LEGEND_PITCH
            }
        }

        /** The point of the plan furthest from its walls, as the PDF places the area there. */
        private fun widest(survey: RoomSurvey): V {
            var bestX = survey.planLength / 2
            var bestY = survey.planWidth / 2
            var best = -1f
            for (j in 1 until POLE_STEPS) {
                for (i in 1 until POLE_STEPS) {
                    val px = survey.planLength * i / POLE_STEPS
                    val py = survey.planWidth * j / POLE_STEPS
                    if (!inside(survey, px, py)) continue
                    val reach = survey.walls.minOf { distance(px, py, it) }
                    if (reach > best) {
                        best = reach
                        bestX = px
                        bestY = py
                    }
                }
            }
            return at(bestX, bestY)
        }

        private fun inside(survey: RoomSurvey, px: Float, py: Float): Boolean {
            var crossings = 0
            for (wall in survey.walls) {
                if ((wall.ay > py) == (wall.by > py)) continue
                if (wall.ax + (py - wall.ay) * (wall.bx - wall.ax) / (wall.by - wall.ay) > px) crossings++
            }
            return crossings % 2 == 1
        }

        private fun distance(px: Float, py: Float, wall: SurveyWall): Float {
            val dx = wall.bx - wall.ax
            val dy = wall.by - wall.ay
            val squared = dx * dx + dy * dy
            val t = if (squared > 0f) (((px - wall.ax) * dx + (py - wall.ay) * dy) / squared).coerceIn(0f, 1f) else 0f
            return hypot(px - (wall.ax + t * dx), py - (wall.ay + t * dy))
        }

        private fun entity(type: String, layer: String) {
            entities.pair(0, type)
            entities.pair(5, handle())
            entities.pair(330, modelRecord)
            entities.pair(100, "AcDbEntity")
            entities.pair(8, layer)
        }

        private fun line(layer: String, a: V, b: V) {
            entity("LINE", layer)
            entities.pair(100, "AcDbLine")
            entities.point(10, drawn(a))
            entities.point(11, drawn(b))
            cover(a)
            cover(b)
        }

        private fun arc(layer: String, centre: V, radius: Double, startDegrees: Double, endDegrees: Double) {
            entity("ARC", layer)
            entities.pair(100, "AcDbCircle")
            entities.point(10, drawn(centre))
            entities.pair(40, drawn(radius))
            entities.pair(100, "AcDbArc")
            entities.pair(50, startDegrees)
            entities.pair(51, endDegrees)
            cover(centre - V(radius, radius))
            cover(centre + V(radius, radius))
        }

        /** A line of text at [at]: its middle there when [centred], else its baseline's start. */
        private fun text(layer: String, at: V, height: Double, value: String, degrees: Double = 0.0, centred: Boolean = true) {
            entity("TEXT", layer)
            entities.pair(100, "AcDbText")
            entities.point(10, drawn(at))
            entities.pair(40, drawn(height))
            entities.pair(1, encode(value))
            entities.pair(50, degrees)
            entities.pair(7, "Standard")
            if (centred) {
                entities.pair(72, 1)
                entities.point(11, drawn(at))
            }
            entities.pair(100, "AcDbText")
            if (centred) entities.pair(73, 2)
            // The extents only frame the view: a text's width is its length at a typical glyph's.
            val width = value.length * height * GLYPH
            val along = V(cos(Math.toRadians(degrees)), sin(Math.toRadians(degrees)))
            val up = V(-along.y, along.x)
            val (back, ahead) = if (centred) -width / 2 to width / 2 else 0.0 to width
            val (under, over) = if (centred) -height / 2 to height / 2 else -height / 4 to height
            for (s in listOf(back, ahead)) for (t in listOf(under, over)) cover(at + along * s + up * t)
        }

        private fun cover(p: V) {
            minX = min(minX, p.x)
            minY = min(minY, p.y)
            maxX = max(maxX, p.x)
            maxY = max(maxY, p.y)
        }

        /** The whole file: its header last written, once every handle is given out. */
        fun file(): String {
            val body = StringBuilder()
            body.section("CLASSES") { classes() }
            body.section("TABLES") { tables() }
            body.section("BLOCKS") { blocks() }
            body.section("ENTITIES") { append(entities) }
            body.section("OBJECTS") { objects() }
            body.pair(0, "EOF")
            val head = StringBuilder()
            head.section("HEADER") { header(handle()) }
            return head.append(body).toString()
        }

        private fun extentMin() = if (covered) V(minX, minY) else V(0.0, 0.0)
        private fun extentMax() = if (covered) V(maxX, maxY) else V(EMPTY, EMPTY)

        private fun StringBuilder.header(seed: String) {
            variable("\$ACADVER") { pair(1, "AC1015") }
            variable("\$DWGCODEPAGE") { pair(3, "ANSI_1252") }
            variable("\$INSBASE") { point(10, V(0.0, 0.0)) }
            variable("\$EXTMIN") { point(10, drawn(extentMin())) }
            variable("\$EXTMAX") { point(10, drawn(extentMax())) }
            // Before $LUNITS, where AutoCAD and ezdxf write it.
            unit.dimensionUnits?.let { format -> variable("\$DIMLUNIT") { pair(70, format) } }
            variable("\$LUNITS") { pair(70, unit.linearUnits) }
            variable("\$LUPREC") { pair(70, unit.precision) }
            variable("\$INSUNITS") { pair(70, unit.insUnits) }
            variable("\$MEASUREMENT") { pair(70, unit.measurement) }
            variable("\$HANDSEED") { pair(5, seed) }
        }

        /** The classes of the objects that are not R12's: each declared before the file uses it. */
        private fun StringBuilder.classes() {
            for ((name, subclass) in CLASSES) {
                pair(0, "CLASS")
                pair(1, name)
                pair(2, subclass)
                pair(3, "ObjectDBX Classes")
                pair(90, 0)
                pair(280, 0)
                pair(281, 0)
            }
        }

        private fun StringBuilder.tables() {
            table("VPORT", vportTable, 1) {
                record("VPORT", vportTable, "AcDbViewportTableRecord", "*ACTIVE")
                viewport()
            }
            table("LTYPE", ltypeTable, 3) {
                for (name in listOf("ByBlock", "ByLayer", "Continuous")) {
                    record("LTYPE", ltypeTable, "AcDbLinetypeTableRecord", name)
                    pair(3, "")
                    pair(72, 65)
                    pair(73, 0)
                    pair(40, 0.0)
                }
            }
            table("LAYER", layerTable, LAYERS.size) {
                for ((name, color, weight) in LAYERS) {
                    record("LAYER", layerTable, "AcDbLayerTableRecord", name)
                    pair(62, color)
                    pair(6, "Continuous")
                    pair(370, weight)
                    pair(390, normalPlotStyle)
                }
            }
            table("STYLE", styleTable, 1) {
                record("STYLE", styleTable, "AcDbTextStyleTableRecord", "Standard")
                pair(40, 0.0)
                pair(41, 1.0)
                pair(50, 0.0)
                pair(71, 0)
                pair(42, drawn(DIMENSION_TEXT))
                // A TrueType font: the standard shape font has no ≈, ² or ×.
                pair(3, "arial.ttf")
                pair(4, "")
            }
            table("VIEW", viewTable, 0) {}
            table("UCS", ucsTable, 0) {}
            table("APPID", appidTable, 1) { record("APPID", appidTable, "AcDbRegAppTableRecord", "ACAD") }
            table("DIMSTYLE", dimstyleTable, 1, "AcDbDimStyleTable") {
                record("DIMSTYLE", dimstyleTable, "AcDbDimStyleTableRecord", "Standard", handleCode = 105)
            }
            table("BLOCK_RECORD", blockTable, 2) {
                record("BLOCK_RECORD", blockTable, "AcDbBlockTableRecord", "*Model_Space", modelRecord, flags = false)
                pair(340, modelLayout)
                record("BLOCK_RECORD", blockTable, "AcDbBlockTableRecord", "*Paper_Space", paperRecord, flags = false)
                pair(340, paperLayout)
            }
        }

        /** The view the drawing opens on: everything drawn, with a margin. */
        private fun StringBuilder.viewport() {
            val low = drawn(extentMin())
            val high = drawn(extentMax())
            val width = max(high.x - low.x, drawn(1.0)) * VIEW_MARGIN
            val height = max(high.y - low.y, drawn(1.0)) * VIEW_MARGIN
            pair(10, 0.0)
            pair(20, 0.0)
            pair(11, 1.0)
            pair(21, 1.0)
            pair(12, (low.x + high.x) / 2)
            pair(22, (low.y + high.y) / 2)
            pair(13, 0.0)
            pair(23, 0.0)
            pair(14, 10.0)
            pair(24, 10.0)
            pair(15, 10.0)
            pair(25, 10.0)
            pair(16, 0.0)
            pair(26, 0.0)
            pair(36, 1.0)
            pair(17, 0.0)
            pair(27, 0.0)
            pair(37, 0.0)
            pair(40, height)
            pair(41, width / height)
            pair(42, 50.0)
            pair(43, 0.0)
            pair(44, 0.0)
            pair(50, 0.0)
            pair(51, 0.0)
            pair(71, 0)
            pair(72, 1000)
            pair(73, 1)
            pair(74, 3)
            pair(75, 0)
            pair(76, 0)
            pair(77, 0)
            pair(78, 0)
            pair(281, 0)
            pair(65, 0)
            pair(146, 0.0)
        }

        private fun StringBuilder.blocks() {
            for ((record, name) in listOf(modelRecord to "*Model_Space", paperRecord to "*Paper_Space")) {
                pair(0, "BLOCK")
                pair(5, handle())
                pair(330, record)
                pair(100, "AcDbEntity")
                pair(8, "0")
                pair(100, "AcDbBlockBegin")
                pair(2, name)
                pair(70, 0)
                point(10, V(0.0, 0.0))
                pair(3, name)
                pair(1, "")
                pair(0, "ENDBLK")
                pair(5, handle())
                pair(330, record)
                pair(100, "AcDbEntity")
                pair(8, "0")
                pair(100, "AcDbBlockEnd")
            }
        }

        /**
         * The dictionaries an R2000 reader looks for, as ezdxf writes them: groups, the model's
         * and the sheet's layouts, the standard multiline style, page setups, and the plot styles
         * with the Normal one every layer plots in.
         */
        private fun StringBuilder.objects() {
            dictionary(
                root,
                "0",
                "ACAD_GROUP" to groups,
                "ACAD_LAYOUT" to layouts,
                "ACAD_MLINESTYLE" to mlineStyles,
                "ACAD_PLOTSETTINGS" to plotSettings,
                "ACAD_PLOTSTYLENAME" to plotStyles,
            )
            dictionary(groups, root)
            dictionary(layouts, root, "Model" to modelLayout, "Layout1" to paperLayout)
            dictionary(mlineStyles, root, "Standard" to mlineStandard)
            dictionary(plotSettings, root)
            plotStyles()
            layout(modelLayout, "Model", 0, modelRecord, PLOT_MODEL)
            layout(paperLayout, "Layout1", 1, paperRecord, 0)
            mlineStyle()
        }

        /** The plot styles by name, Normal alone and the default: a placeholder, as no style table is attached. */
        private fun StringBuilder.plotStyles() {
            pair(0, "ACDBDICTIONARYWDFLT")
            pair(5, plotStyles)
            pair(330, root)
            pair(100, "AcDbDictionary")
            pair(281, 1)
            pair(3, "Normal")
            pair(350, normalPlotStyle)
            pair(100, "AcDbDictionaryWithDefault")
            pair(340, normalPlotStyle)
            pair(0, "ACDBPLACEHOLDER")
            pair(5, normalPlotStyle)
            pair(330, plotStyles)
        }

        /** The standard multiline style: two lines half a unit either side, in the layer's colour and line type. */
        private fun StringBuilder.mlineStyle() {
            pair(0, "MLINESTYLE")
            pair(5, mlineStandard)
            pair(102, "{ACAD_REACTORS")
            pair(330, mlineStyles)
            pair(102, "}")
            pair(330, mlineStyles)
            pair(100, "AcDbMlineStyle")
            pair(2, "Standard")
            pair(70, 0)
            pair(3, "")
            pair(62, BY_LAYER)
            pair(51, QUARTER_TURN)
            pair(52, QUARTER_TURN)
            pair(71, 2)
            for (offset in listOf(0.5, -0.5)) {
                pair(49, offset)
                pair(62, BY_LAYER)
                pair(6, "BYLAYER")
            }
        }

        private fun StringBuilder.dictionary(handle: String, owner: String, vararg entries: Pair<String, String>) {
            pair(0, "DICTIONARY")
            pair(5, handle)
            pair(330, owner)
            pair(100, "AcDbDictionary")
            pair(281, 1)
            for ((name, entry) in entries) {
                pair(3, name)
                pair(350, entry)
            }
        }

        private fun StringBuilder.layout(handle: String, name: String, order: Int, record: String, plotFlags: Int) {
            pair(0, "LAYOUT")
            pair(5, handle)
            pair(330, layouts)
            pair(100, "AcDbPlotSettings")
            pair(1, "")
            pair(4, "A3")
            pair(6, "")
            for ((code, value) in listOf(40 to 7.5, 41 to 20.0, 42 to 7.5, 43 to 20.0, 44 to 420.0, 45 to 297.0)) pair(code, value)
            for (code in listOf(46, 47, 48, 49, 140, 141)) pair(code, 0.0)
            pair(142, 1.0)
            pair(143, 1.0)
            pair(70, plotFlags)
            pair(72, 1)
            pair(73, 0)
            pair(74, 5)
            pair(7, "")
            pair(75, 16)
            pair(76, 0)
            pair(77, 2)
            pair(78, 300)
            pair(147, 1.0)
            pair(148, 0.0)
            pair(149, 0.0)
            pair(100, "AcDbLayout")
            pair(1, name)
            pair(70, 1)
            pair(71, order)
            pair(10, 0.0)
            pair(20, 0.0)
            pair(11, 420.0)
            pair(21, 297.0)
            point(12, V(0.0, 0.0))
            point(14, V(EMPTY_EXTENT, EMPTY_EXTENT), EMPTY_EXTENT)
            point(15, V(-EMPTY_EXTENT, -EMPTY_EXTENT), -EMPTY_EXTENT)
            pair(146, 0.0)
            point(13, V(0.0, 0.0))
            point(16, V(1.0, 0.0))
            point(17, V(0.0, 1.0))
            pair(76, 1)
            pair(330, record)
        }

        private fun StringBuilder.table(
            name: String,
            handle: String,
            count: Int,
            subclass: String? = null,
            records: StringBuilder.() -> Unit,
        ) {
            pair(0, "TABLE")
            pair(2, name)
            pair(5, handle)
            pair(330, "0")
            pair(100, "AcDbSymbolTable")
            pair(70, count)
            if (subclass != null) pair(100, subclass)
            records()
            pair(0, "ENDTAB")
        }

        @Suppress("LongParameterList") // a symbol table record's fixed head
        private fun StringBuilder.record(
            type: String,
            owner: String,
            subclass: String,
            name: String,
            handle: String = handle(),
            flags: Boolean = true,
            handleCode: Int = 5,
        ) {
            pair(0, type)
            pair(handleCode, handle)
            pair(330, owner)
            pair(100, "AcDbSymbolTableRecord")
            pair(100, subclass)
            pair(2, name)
            if (flags) pair(70, 0)
        }

        private companion object {
            const val HEX = 16
            const val EMPTY = 1000.0
            const val EMPTY_EXTENT = 1e20
            const val VIEW_MARGIN = 1.15
            const val PLOT_MODEL = 1024

            /** The colour index (ACI) that takes the layer's colour. */
            const val BY_LAYER = 256

            /** The class objects the file holds, by DXF name and C++ class, as ezdxf declares them. */
            val CLASSES = listOf(
                "ACDBDICTIONARYWDFLT" to "AcDbDictionaryWithDefault",
                "ACDBPLACEHOLDER" to "AcDbPlaceHolder",
                "LAYOUT" to "AcDbLayout",
            )

            /** Name, colour index (ACI) and line weight (hundredths of a millimetre) of each layer. */
            val LAYERS = listOf(
                Triple("0", 7, -3),
                Triple(WALLS, 7, 50),
                Triple(OPENINGS, 4, 25),
                Triple(DIMENSIONS, 3, 18),
                Triple(TEXT, 7, 25),
                Triple(LEGEND, 8, 18),
            )
        }
    }

    // Sizes and distances on the drawing, millimetres: a 1:50 print reads its dimensions at 3 mm.
    private const val MM = 1000.0
    private const val MM_PER_INCH = 25.4
    private const val JOIN = 1.0
    private const val DIMENSION_OFFSET = 600.0
    private const val CHAIN_PITCH = 450.0
    private const val EXTENSION_GAP = 100.0
    private const val EXTENSION_OVER = 100.0
    private const val TICK = 150.0
    private const val LABEL_GAP = 60.0
    private const val DIMENSION_TEXT = 150.0
    private const val OPENING_TEXT = 100.0
    private const val OPENING_LABEL = 250.0
    private const val JAMB = 100.0
    private const val FRAME = 50.0
    private const val ROOM_TEXT = 250.0
    private const val AREA_TEXT = 200.0
    private const val ROOM_GAP = 200.0
    private const val LEGEND_TEXT = 150.0
    private const val LEGEND_PITCH = 250.0
    private const val LEGEND_GAP = 600.0
    private const val GLYPH = 0.6
    private const val POLE_STEPS = 40

    /** A wall within this slope of the page's axes is dimensioned square to them, as on the PDF. */
    private const val ALIGNED = 0.01

    /** The side of the outline a chain of dimensions runs along. */
    private enum class Edge { Top, Bottom, Left, Right }

    /** The left of [u]: out of the room, the walls running clockwise on the drawing as on the page. */
    private fun outward(u: V) = V(-u.y, u.x)

    /** [v]'s direction, degrees counter-clockwise from the x axis, in [0, 360). */
    private fun degrees(v: V) = (Math.toDegrees(atan2(v.y, v.x)) + FULL_TURN) % FULL_TURN

    /** The angle text along [u] is turned to, so that it reads left to right or bottom to top. */
    private fun readable(u: V): Double {
        val degrees = Math.toDegrees(atan2(u.y, u.x))
        return when {
            degrees > QUARTER_TURN + ANGLE_EPSILON -> degrees - HALF_TURN
            degrees <= -QUARTER_TURN + ANGLE_EPSILON -> degrees + HALF_TURN
            else -> degrees
        }
    }

    private const val FULL_TURN = 360.0
    private const val HALF_TURN = 180.0
    private const val QUARTER_TURN = 90.0
    private const val ANGLE_EPSILON = 1e-3

    /**
     * [text] as an R2000 file carries it: ASCII, anything else as its `\U+XXXX` escape. So are
     * `%`, `^` and `\`, which a CAD tool would read as codes, `%%d` as a degree sign, `^I` as a tab
     * and `\U+0041` as an A, rather than show them as typed.
     */
    private fun encode(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val code = text.codePointAt(i)
            when {
                code in PRINTABLE && code.toChar() !in CODES -> append(code.toChar())
                code < PRINTABLE.first -> append(' ')
                code <= Char.MAX_VALUE.code -> append("\\U+%04X".format(Locale.US, code))
                else -> append('?')
            }
            i += Character.charCount(code)
        }
    }

    private val PRINTABLE = 0x20..0x7E

    /** What starts a code in a DXF text: `%%`, a caret and a backslash. */
    private const val CODES = "%^\\"

    private fun StringBuilder.pair(code: Int, value: String) {
        append(code.toString().padStart(CODE_WIDTH)).append('\n').append(value).append('\n')
    }

    private fun StringBuilder.pair(code: Int, value: Int) = pair(code, value.toString())

    private fun StringBuilder.pair(code: Int, value: Double) = pair(code, number(value))

    /** [p] under [code] and the two codes after it: x, y and a z of [z]. */
    private fun StringBuilder.point(code: Int, p: V, z: Double = 0.0) {
        pair(code, p.x)
        pair(code + 10, p.y)
        pair(code + 20, z)
    }

    private fun StringBuilder.section(name: String, content: StringBuilder.() -> Unit) {
        pair(0, "SECTION")
        pair(2, name)
        content()
        pair(0, "ENDSEC")
    }

    private fun StringBuilder.variable(name: String, value: StringBuilder.() -> Unit) {
        pair(9, name)
        value()
    }

    /**
     * To the thousandth of a drawing unit, its trailing zeros dropped. A figure that is not finite
     * stops the drawing: written as `NaN` or `Infinity`, it would make the whole file unreadable.
     */
    private fun number(value: Double): String {
        require(value.isFinite()) { "a DXF figure must be a finite number, not $value" }
        val text = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
        return if (text == "-0") "0" else text
    }

    private const val CODE_WIDTH = 3
}
