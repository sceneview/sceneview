package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.RoomPlanDxf.Opening
import io.github.sceneview.demo.demos.internal.RoomPlanDxf.OpeningKind
import io.github.sceneview.demo.demos.internal.RoomPlanLayoutTest.Companion.WORDING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The plan's DXF read back pair by pair, with a reader of its own: what a CAD tool finds in it.
 * DXF AC1015 (R2000) in millimetres, or in inches when the figures are in feet and inches; the
 * outline one closed polyline on WALLS; each wall's length as lines and text on DIMENSIONS, never
 * a DIMENSION entity; the room's name and area on TEXT; each opening with its size on OPENINGS;
 * the warning to check it on site on LEGEND.
 */
class RoomPlanDxfTest {

    private val room = planSurvey(0f, 0f, 4f, 0f, 4f, 5f, 0f, 5f)
    private val lShape = planSurvey(0f, 0f, 6f, 0f, 6f, 3f, 3f, 3f, 3f, 5f, 0f, 5f)

    @Test
    fun `a DXF AC1015 (R2000) drawing in millimetres, in plain ASCII`() {
        val dxf = dxf(room)
        assertTrue("pairs of a code and a value", dxf.wellFormed && dxf.pairs.isNotEmpty())
        assertEquals("AC1015", dxf.header("\$ACADVER"))
        assertEquals("4", dxf.header("\$INSUNITS")?.trim())
        assertTrue("ASCII only", dxf.ascii)
        assertEquals(listOf("HEADER", "CLASSES", "TABLES", "BLOCKS", "ENTITIES", "OBJECTS"), dxf.sections.keys.toList())
        assertEquals(0 to "EOF", dxf.pairs.lastOrNull())
    }

    @Test
    fun `the five layers are declared and every entity is on one of them`() {
        val dxf = dxf(room, openings = OPENINGS)
        val layers = dxf.objects("TABLES", "LAYER").map { it.first(2) }
        for (name in LAYERS) assertTrue("$name in $layers", name in layers)
        val entities = dxf.objects("ENTITIES")
        assertTrue("entities", entities.isNotEmpty())
        for (entity in entities) assertTrue("${entity.type} on ${entity.layer}", entity.layer in LAYERS)
    }

    @Test
    fun `the walls are one closed polyline, in millimetres, y up`() {
        for (survey in listOf(room, lShape)) {
            val outlines = dxf(survey).entities("LWPOLYLINE", "WALLS")
            assertEquals("outlines", 1, outlines.size)
            val outline = outlines.single()
            assertEquals("closed", 1, (outline.first(70)?.trim()?.toInt() ?: 0) and 1)
            val expected = survey.walls.map { at(survey, it.ax, it.ay) }
            val vertices = outline.vertices
            assertEquals("vertices", expected.size, vertices.size)
            expected.zip(vertices).forEach { (e, v) ->
                assertTrue("${v.toList()} for ${e.toList()}", hypot(e[0] - v[0], e[1] - v[1]) < 0.5)
            }
        }
    }

    @Test
    fun `each wall is dimensioned outside it with lines and text, never a DIMENSION entity`() {
        for (survey in listOf(room, lShape)) {
            val dxf = dxf(survey)
            assertTrue("no DIMENSION entity", dxf.entities("DIMENSION").isEmpty())
            val labels = SurveyReport.planTexts(survey, WORDING).walls
            val lines = dxf.entities("LINE", "DIMENSIONS")
            val texts = dxf.entities("TEXT", "DIMENSIONS")
            survey.walls.forEachIndexed { i, wall ->
                val side = Side(at(survey, wall.ax, wall.ay), at(survey, wall.bx, wall.by))
                val line = lines.any { line ->
                    val a = line.point(10)
                    val b = line.point(11)
                    val mid = doubleArrayOf((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
                    side.parallel(a, b) && abs(hypot(b[0] - a[0], b[1] - a[1]) - side.length) < 1.0 &&
                        side.out(mid) > 0 && abs(side.along(mid) - side.length / 2) < 1.0
                }
                assertTrue("wall ${wall.tag}: its dimension line", line)
                val text = texts.any { text ->
                    val p = text.point(11)
                    text.text == labels[i] && side.out(p) > 0 && abs(side.along(p) - side.length / 2) < 1.0 &&
                        side.readsAlong(text.number(50) ?: 0.0)
                }
                assertTrue("wall ${wall.tag}: ${labels[i]} in ${texts.map { it.text }}", text)
            }
        }
    }

    @Test
    fun `a wall with a blank label is left undimensioned, the others dimensioned as before`() {
        val labels = SurveyReport.planTexts(room, WORDING).walls
        val blank = 1
        val dxf = dxf(room, labels = labels.mapIndexed { i, label -> if (i == blank) "" else label })
        val texts = dxf.entities("TEXT", "DIMENSIONS").map { it.text }
        assertEquals("no empty text", labels.filterIndexed { i, _ -> i != blank }.sorted(), texts.map { it.orEmpty() }.sorted())
        // Nothing drawn outside the unlabelled wall: no extension line, dimension line, tick or text.
        val wall = room.walls[blank]
        val side = Side(at(room, wall.ax, wall.ay), at(room, wall.bx, wall.by))
        val outside = dxf.entities("LINE", "DIMENSIONS").filter { min(side.out(it.point(10)), side.out(it.point(11))) > 1.0 }
        assertTrue("lines outside wall ${wall.tag}: ${outside.size}", outside.isEmpty())
        assertTrue(dxf.entities("TEXT", "DIMENSIONS").none { side.out(it.point(11)) > 1.0 })
    }

    @Test
    fun `numbers keep a decimal point under a French locale`() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.FRANCE)
        try {
            val survey = planSurvey(0f, 0f, 3.0005f, 0f, 3.0005f, 2f, 0f, 2f)
            val dxf = dxf(survey)
            val numbers = dxf.pairs.filter { it.first in 10..59 }.map { it.second.trim() }
            assertTrue("numbers", numbers.isNotEmpty())
            assertTrue("a comma in ${numbers.filter { ',' in it }}", numbers.none { ',' in it })
            val xs = dxf.entities("LWPOLYLINE", "WALLS").single().all(10).map { it.trim() }
            assertTrue("3000.5 in $xs", "3000.5" in xs)
            val dimensions = dxf.entities("TEXT", "DIMENSIONS").map { it.text }
            assertTrue("$dimensions", dimensions.containsAll(listOf("3.00", "2.00")))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `a figure that is not a number stops the drawing rather than reach the file`() {
        val astray = Opening(OpeningKind.Door, wall = 0, from = Float.NaN, width = 0.9f, height = 2.1f)
        assertThrows(IllegalArgumentException::class.java) { dxf(room, openings = listOf(astray)) }
    }

    @Test
    fun `the room's name and floor area stand inside it, on TEXT`() {
        for (survey in listOf(room, lShape)) {
            val texts = dxf(survey).entities("TEXT", "TEXT")
            val outline = survey.walls.map { at(survey, it.ax, it.ay) }
            for (label in listOf(WORDING.room.format(1), SurveyReport.area(survey.area!!, WORDING.units))) {
                val text = texts.singleOrNull { it.text == label }
                assertNotNull("$label in ${texts.map { it.text }}", text)
                assertTrue("$label inside the room", inside(outline, text!!.point(11)))
            }
        }
    }

    @Test
    fun `openings carry their width, and their height and sill when known`() {
        val dxf = dxf(room, openings = OPENINGS)
        assertEquals(
            setOf("Door 0.90 × 2.10", "Window 1.20 × 1.20, sill 0.90", "Door 0.80"),
            dxf.entities("TEXT", "OPENINGS").map { it.text }.toSet(),
        )
        // The door swings into the room: a quarter circle as wide as the door, hinged on its wall.
        val top = Side(at(room, 0f, 0f), at(room, 4f, 0f))
        val swings = dxf.entities("ARC", "OPENINGS").filter { abs((it.number(40) ?: 0.0) - 900.0) < 1.0 }
        assertEquals("the door's swing", 1, swings.size)
        val swing = swings.single()
        val hinge = swing.point(10)
        assertTrue("hinged on its wall", abs(top.out(hinge)) < 1.0)
        assertTrue("at one of its jambs", abs(top.along(hinge) - 1000.0) < 1.0 || abs(top.along(hinge) - 1900.0) < 1.0)
        val start = swing.number(50)!!
        assertEquals("a quarter turn", 90.0, ((swing.number(51)!! - start) % 360 + 360) % 360, 0.5)
        val half = Math.toRadians(start + 45)
        assertTrue("into the room", top.out(doubleArrayOf(hinge[0] + 900 * cos(half), hinge[1] + 900 * sin(half))) < 0)
        // The window's frame runs along its wall, over its width.
        val right = Side(at(room, 4f, 0f), at(room, 4f, 5f))
        val frame = dxf.entities("LINE", "OPENINGS").any { line ->
            val a = line.point(10)
            val b = line.point(11)
            right.parallel(a, b) && abs(hypot(b[0] - a[0], b[1] - a[1]) - 1200.0) < 1.0 &&
                abs(min(right.along(a), right.along(b)) - 2000.0) < 1.0 && abs(right.out(a)) < 100.0
        }
        assertTrue("the window's frame", frame)
    }

    @Test
    fun `the legend says to check the plan on site, under it`() {
        val dxf = dxf(room)
        val legend = dxf.entities("TEXT", "LEGEND")
        assertEquals(WORDS.legend, legend.map { it.text })
        val plan = dxf.objects("ENTITIES").filter { it.layer != "LEGEND" }
        assertTrue("the plan", plan.isNotEmpty())
        val bottom = plan.minOf { entity ->
            (entity.all(20) + entity.all(21)).mapNotNull { it.trim().toDoubleOrNull() }.minOrNull() ?: 0.0
        }
        for (line in legend) assertTrue("${line.text} under the plan", line.point(10)[1] < bottom)
    }

    @Test
    fun `an estimate keeps its mark, escaped for DXF AC1015 (R2000)`() {
        val base = room
        val walls = base.walls.mapIndexed { i, wall ->
            if (i != 0) {
                wall
            } else {
                SurveyWall(wall.tag, wall.ax, wall.ay, wall.bx, wall.by, SurveyFigure(4f, 0f, estimate = true), 0f, false, listOf(0f..1f))
            }
        }
        val survey = RoomSurvey(
            walls, base.planLength, base.planWidth, base.length, base.width, SurveyFigure(20f, 0f, estimate = true),
            base.perimeter, base.ceilingHeight, base.gaps, 0f, 0f, 0f,
        )
        val dxf = dxf(survey)
        assertTrue("ASCII only", dxf.ascii)
        val dimensions = dxf.entities("TEXT", "DIMENSIONS").map { it.first(1) }
        assertTrue("$dimensions", "\\U+2248 4.00" in dimensions)
        val texts = dxf.entities("TEXT", "TEXT").map { it.first(1) }
        assertTrue("$texts", "\\U+2248 20.0 m\\U+00B2" in texts)
    }

    @Test
    fun `figures in metres give a drawing in millimetres, metric`() {
        val dxf = dxf(room, openings = OPENINGS)
        assertEquals("millimetres", "4", dxf.header("\$INSUNITS")?.trim())
        assertEquals("metric", "1", dxf.header("\$MEASUREMENT")?.trim())
        assertEquals("decimal", "2", dxf.header("\$LUNITS")?.trim())
        assertEquals("to the millimetre", "0", dxf.header("\$LUPREC")?.trim())
        assertDrawnAt(dxf, room, MILLIMETRES_PER_METRE)
        val dimensions = dxf.entities("TEXT", "DIMENSIONS").map { it.text }
        assertTrue("$dimensions", dimensions.containsAll(listOf("4.00", "5.00")))
    }

    @Test
    fun `figures in feet and inches give a drawing in inches, imperial, read in feet and inches`() {
        val wording = imperialWording()
        val dxf = dxf(room, wording, openings = OPENINGS)
        assertEquals("inches", "1", dxf.header("\$INSUNITS")?.trim())
        assertEquals("imperial", "0", dxf.header("\$MEASUREMENT")?.trim())
        // A distance taken in the CAD tool reads in feet and inches, to the sixteenth of an inch.
        assertEquals("architectural", "4", dxf.header("\$LUNITS")?.trim())
        assertEquals("to 1/16\"", "4", dxf.header("\$LUPREC")?.trim())
        assertDrawnAt(dxf, room, INCHES_PER_METRE)
        val dimensions = dxf.entities("TEXT", "DIMENSIONS").map { it.text }
        assertTrue("$dimensions", dimensions.containsAll(listOf("13' 1\"", "16' 5\"")))
        val area = SurveyReport.area(room.area!!, MeasureUnitSystem.Imperial)
        assertTrue(area, dxf.entities("TEXT", "TEXT").any { it.text == area })
    }

    @Test
    fun `figures in feet and inches set the dimension units to architectural, DIMLUNIT 4`() {
        val dxf = dxf(room, imperialWording(), openings = OPENINGS)
        // Without it, a CAD tool dimensions and measures the inch drawing in decimal inches, 157.48 rather than 13'-1 1/2".
        assertEquals("architectural, under the integer code 70", 70 to "4", dxf.headerPair("\$DIMLUNIT"))
        assertEquals("written once", 1, dxf.headerNames.count { it == "\$DIMLUNIT" })
        assertEquals("the metric header's variables besides", METRIC_HEADER, dxf.headerNames - "\$DIMLUNIT")
    }

    @Test
    fun `figures in metres leave the header as it was, its dimension units the decimal default`() {
        val dxf = dxf(room, openings = OPENINGS)
        assertEquals(METRIC_HEADER, dxf.headerNames)
        assertNull("no DIMLUNIT, which a CAD tool then reads as 2, decimal", dxf.headerPair("\$DIMLUNIT"))
    }

    /**
     * [dxf] draws [survey] and [OPENINGS] at [perMetre] drawing units a metre: the outline's
     * corners, each wall's dimension line, each door's swing, the texts' heights and the extents.
     */
    private fun assertDrawnAt(dxf: DxfRead, survey: RoomSurvey, perMetre: Double) {
        val corners = survey.walls.map { at(survey, it.ax, it.ay, perMetre) }
        val vertices = dxf.entities("LWPOLYLINE", "WALLS").single().vertices
        assertEquals("vertices", corners.size, vertices.size)
        corners.zip(vertices).forEach { (e, v) ->
            assertTrue("${v.toList()} for ${e.toList()}", hypot(e[0] - v[0], e[1] - v[1]) < UNIT_TOLERANCE)
        }
        val lines = dxf.entities("LINE", "DIMENSIONS").map { hypot(it.point(11)[0] - it.point(10)[0], it.point(11)[1] - it.point(10)[1]) }
        for (wall in survey.walls) {
            val length = hypot(wall.bx - wall.ax, wall.by - wall.ay) * perMetre
            assertTrue("wall ${wall.tag}: a $length line in $lines", lines.any { abs(it - length) < UNIT_TOLERANCE })
        }
        val swings = dxf.entities("ARC", "OPENINGS").mapNotNull { it.number(40) }.sorted()
        val doors = OPENINGS.filter { it.kind == OpeningKind.Door }.map { it.width * perMetre }.sorted()
        assertEquals("swings", doors.size, swings.size)
        doors.zip(swings).forEach { (e, r) -> assertEquals("swing", e, r, UNIT_TOLERANCE) }
        // Text sizes keep their print size: 150 mm on the drawing, 3 mm at 1:50.
        for (layer in listOf("DIMENSIONS", "LEGEND")) {
            for (text in dxf.entities("TEXT", layer)) assertEquals("$layer text height", 0.15 * perMetre, text.number(40)!!, UNIT_TOLERANCE)
        }
        val low = dxf.headerPoint("\$EXTMIN")
        val high = dxf.headerPoint("\$EXTMAX")
        for (corner in corners) {
            assertTrue("${corner.toList()} within the extents", corner[0] in low[0]..high[0] && corner[1] in low[1]..high[1])
        }
        assertTrue("extents of a 4 x 5 m room: ${high[0] - low[0]}", high[0] - low[0] < survey.planLength * 2 * perMetre)
    }

    @Test
    fun `handles are unique and under the seed, entities owned by the model space`() {
        val dxf = dxf(room, openings = OPENINGS)
        val handles = dxf.sections.filterKeys { it != "HEADER" }.values.flatten().mapNotNull { it.handle }
        assertTrue("handles", handles.isNotEmpty())
        assertEquals("unique", handles.size, handles.toSet().size)
        val seed = dxf.header("\$HANDSEED")?.toLongOrNull(16)
        assertNotNull("\$HANDSEED", seed)
        for (handle in handles) assertTrue("$handle under $seed", handle.toLong(16) in 1 until seed!!)
        assertEquals(listOf("*Model_Space", "*Paper_Space"), dxf.objects("TABLES", "BLOCK_RECORD").map { it.first(2) })
        assertEquals(listOf("*Model_Space", "*Paper_Space"), dxf.objects("BLOCKS", "BLOCK").map { it.first(2) })
        val model = dxf.objects("TABLES", "BLOCK_RECORD").first().handle
        for (entity in dxf.objects("ENTITIES")) assertEquals("${entity.type}'s owner", model, entity.first(330))
    }

    @Test
    fun `its sections, tables and records are those of the R2000 file ezdxf writes`() {
        for ((what, dxf) in drawings()) {
            assertEquals("$what: sections", REFERENCE.sections.keys.toList(), dxf.sections.keys.toList())
            assertEquals("$what: tables", REFERENCE.tables.keys.toList(), dxf.tables.keys.toList())
            for ((table, records) in REFERENCE.tables) {
                val ours = dxf.tables[table].orEmpty().mapNotNull { it.first(2)?.uppercase(Locale.ROOT) }
                for (name in records.mapNotNull { it.first(2)?.uppercase(Locale.ROOT) } - EZDXF_ONLY) {
                    assertTrue("$what: $table $name in $ours", name in ours)
                }
            }
        }
    }

    @Test
    fun `each record and object carries the group codes ezdxf writes for its type`() {
        val reference = REFERENCE.codes()
        val missing = ArrayList<String>()
        for ((what, dxf) in drawings()) {
            for ((section, objects) in dxf.sections) {
                for (o in objects) {
                    val expected = reference[section to o.type] ?: continue
                    val wanted = if (o.type == "DIMSTYLE") expected intersect DIMSTYLE_HEAD else expected
                    val lacks = wanted - o.pairs.map { it.first }.toSet()
                    if (lacks.isNotEmpty()) missing += "$what: $section ${o.type} ${o.first(2).orEmpty()} lacks $lacks"
                }
            }
        }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `every owner and pointer names an object of the file`() {
        for ((what, dxf) in drawings() + ("ezdxf" to REFERENCE)) {
            for ((section, objects) in dxf.sections) {
                if (section == "HEADER") continue
                for (o in objects) {
                    for ((code, value) in o.pairs) {
                        if (code !in POINTERS || (code == 330 && value == "0")) continue
                        assertNotNull("$what: ${o.type} ${o.handle} $code -> $value", dxf.byHandle[value])
                    }
                }
            }
        }
    }

    @Test
    fun `the root dictionary names what an R2000 reader looks for, each owned where it is named`() {
        val reference = REFERENCE.root.entries.filter { (name, _) -> name !in LATER_THAN_R2000 }
        for ((what, dxf) in drawings()) {
            val root = dxf.root
            assertEquals("$what: root", reference.map { it.first }, root.entries.map { it.first })
            for ((name, handle) in root.entries) {
                val expected = REFERENCE.byHandle[reference.first { it.first == name }.second]!!
                val entry = dxf.byHandle[handle]
                assertEquals("$what: $name", expected.type, entry?.type)
                assertEquals("$what: $name's owner", root.handle, entry?.owner)
                val inner = expected.entries.map { (key, at) -> key to REFERENCE.byHandle[at]?.type }
                assertEquals("$what: $name's entries", inner, entry!!.entries.map { (key, at) -> key to dxf.byHandle[at]?.type })
                for ((key, at) in entry.entries) assertEquals("$what: $name $key's owner", handle, dxf.byHandle[at]?.owner)
            }
        }
    }

    @Test
    fun `every layer plots in the Normal style that ACAD_PLOTSTYLENAME names and defaults to`() {
        for ((what, dxf) in drawings() + ("ezdxf" to REFERENCE)) {
            val styles = dxf.root.entries.firstOrNull { it.first == "ACAD_PLOTSTYLENAME" }?.let { dxf.byHandle[it.second] }
            assertEquals("$what: ACAD_PLOTSTYLENAME", "ACDBDICTIONARYWDFLT", styles?.type)
            val normal = styles!!.entries.firstOrNull { it.first == "Normal" }?.second
            assertEquals("$what: Normal", "ACDBPLACEHOLDER", dxf.byHandle[normal]?.type)
            assertEquals("$what: the default style", normal, styles.first(340))
            val layers = dxf.tables["LAYER"].orEmpty()
            assertTrue("$what: layers", layers.isNotEmpty())
            for (layer in layers) assertEquals("$what: layer ${layer.first(2)}'s 390", normal, layer.first(390))
        }
    }

    @Test
    fun `each class of object it holds is declared as ezdxf declares it`() {
        val declared = REFERENCE.objects("CLASSES", "CLASS").associateBy { it.first(1) }
        for ((what, dxf) in drawings()) {
            val used = dxf.objects("OBJECTS").map { it.type }.filter { it in declared }.toSet()
            assertTrue("$what: a LAYOUT among $used", "LAYOUT" in used)
            val classes = dxf.objects("CLASSES", "CLASS").associateBy { it.first(1) }
            assertEquals("$what: classes", used, classes.keys)
            for (type in used) assertEquals("$what: $type", declared[type]?.pairs, classes[type]?.pairs)
        }
    }

    @Test
    fun `a typed title keeps its percent signs, carets and backslashes as typed, not as DXF codes`() {
        // %%d is a degree sign to a CAD tool, ^I a tab and \U+0041 an A, unless each is escaped.
        val title = "Den %%d ^I C:\\plans \\U+0041"
        val words = RoomPlanDxf.Words(listOf("$title plan") + WORDS.legend.drop(1), WORDS.door, WORDS.window, WORDS.sill)
        val legend = dxf(room, words = words).entities("TEXT", "LEGEND").first()
        assertEquals("Den \\U+0025\\U+0025d \\U+005EI C:\\U+005Cplans \\U+005CU+0041 plan", legend.first(1))
        assertEquals("$title plan", legend.text)
    }

    @Test
    fun `a survey with no plan still gives a drawing, its legend alone`() {
        val dxf = dxf(RoomSurvey.empty(SurveyGap.NoWalls))
        assertEquals("AC1015", dxf.header("\$ACADVER"))
        assertEquals(WORDS.legend, dxf.entities("TEXT", "LEGEND").map { it.text })
        assertTrue("the legend alone", dxf.objects("ENTITIES").all { it.layer == "LEGEND" })
    }

    private fun dxf(
        survey: RoomSurvey,
        wording: SurveyWording = WORDING,
        openings: List<Opening> = emptyList(),
        labels: List<String> = SurveyReport.planTexts(survey, wording).walls,
        words: RoomPlanDxf.Words = WORDS,
    ): DxfRead {
        val out = ByteArrayOutputStream()
        RoomPlanDxf.write(out, survey, wording, words, openings, labels)
        return DxfRead(out.toByteArray())
    }

    /** The drawings held to ezdxf's: metric and imperial, with openings, an L-shaped room, and no plan at all. */
    private fun drawings() = listOf(
        "metric" to dxf(room, openings = OPENINGS),
        "imperial" to dxf(room, imperialWording(), OPENINGS),
        "L-shaped" to dxf(lShape),
        "no plan" to dxf(RoomSurvey.empty(SurveyGap.NoWalls)),
    )

    /** The group codes every object of each type carries, by its section and type; extended data left out. */
    private fun DxfRead.codes(): Map<Pair<String, String>, Set<Int>> {
        val codes = LinkedHashMap<Pair<String, String>, Set<Int>>()
        for ((section, objects) in sections) {
            if (section == "HEADER") continue
            for (o in objects) {
                if (o.type.isEmpty()) continue
                val own = o.pairs.map { it.first }.filter { it < EXTENDED_DATA }.toSet()
                codes[section to o.type] = codes[section to o.type]?.intersect(own) ?: own
            }
        }
        return codes
    }

    /**
     * A point of the plan (metres, y down the page) where the drawing has it: [perMetre] drawing
     * units a metre, millimetres by default, y up.
     */
    private fun at(survey: RoomSurvey, x: Float, y: Float, perMetre: Double = MILLIMETRES_PER_METRE) =
        doubleArrayOf(x * perMetre, (survey.planWidth - y) * perMetre)

    /** Whether [p] is inside the outline [corners]. */
    private fun inside(corners: List<DoubleArray>, p: DoubleArray): Boolean {
        var crossings = 0
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            if ((a[1] > p[1]) == (b[1] > p[1])) continue
            if (a[0] + (p[1] - a[1]) * (b[0] - a[0]) / (b[1] - a[1]) > p[0]) crossings++
        }
        return crossings % 2 == 1
    }

    /** A wall on the drawing, from [a] to [b]: clockwise there as on the page, the outside on its left. */
    private class Side(val a: DoubleArray, b: DoubleArray) {
        val length = hypot(b[0] - a[0], b[1] - a[1])
        private val ux = (b[0] - a[0]) / length
        private val uy = (b[1] - a[1]) / length

        /** How far out of the room [p] is from the wall's line. */
        fun out(p: DoubleArray) = -uy * (p[0] - a[0]) + ux * (p[1] - a[1])

        /** How far along the wall [p] is from its start. */
        fun along(p: DoubleArray) = ux * (p[0] - a[0]) + uy * (p[1] - a[1])

        fun parallel(p: DoubleArray, q: DoubleArray): Boolean {
            val l = hypot(q[0] - p[0], q[1] - p[1])
            return l > 0 && abs(ux * (q[1] - p[1]) - uy * (q[0] - p[0])) / l < 1e-3
        }

        /** Whether a text turned [degrees] reads along the wall, either way. */
        fun readsAlong(degrees: Double): Boolean {
            val wall = Math.toDegrees(atan2(uy, ux))
            val off = ((degrees - wall) % 180 + 360) % 180
            return off < 0.5 || off > 179.5
        }
    }

    /** A DXF file read back: its pairs of a group code and a value, then each section's objects. */
    private class DxfRead(bytes: ByteArray) {
        /** One object of a section, from its group code 0 to the next; a section's leading pairs have no [type]. */
        class Obj(val type: String, val pairs: List<Pair<Int, String>>) {
            fun first(code: Int) = pairs.firstOrNull { it.first == code }?.second
            fun all(code: Int) = pairs.filter { it.first == code }.map { it.second }
            fun number(code: Int) = first(code)?.trim()?.toDoubleOrNull()
            fun point(code: Int) = doubleArrayOf(number(code) ?: Double.NaN, number(code + 10) ?: Double.NaN)
            val layer get() = first(8)
            val handle get() = first(if (type == "DIMSTYLE") 105 else 5)
            val vertices get() = all(10).zip(all(20)) { x, y -> doubleArrayOf(x.trim().toDouble(), y.trim().toDouble()) }

            /** A dictionary's entries: each name and the handle it names. */
            val entries get() = all(3).zip(all(350))

            /** What owns it: its 330 outside a `102` group, such as its reactors'. */
            val owner: String?
                get() {
                    var grouped = false
                    for ((code, value) in pairs) {
                        if (code == 102) grouped = value.startsWith("{") else if (code == 330 && !grouped) return value
                    }
                    return null
                }

            /** The text's string, its `\U+XXXX` escapes read back. */
            val text get() = first(1)?.let { s -> ESCAPE.replace(s) { it.groupValues[1].toInt(16).toChar().toString() } }
        }

        val ascii = bytes.all { it >= 0 }
        private val lines = String(bytes, Charsets.US_ASCII).split("\n").map { it.trimEnd('\r') }
            .let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        val wellFormed = lines.size % 2 == 0 && (lines.indices step 2).all { lines[it].trim().toIntOrNull() != null }
        val pairs = (0 until lines.size / 2).mapNotNull { i -> lines[i * 2].trim().toIntOrNull()?.let { it to lines[i * 2 + 1] } }
        val sections: Map<String, List<Obj>> = LinkedHashMap<String, List<Obj>>().also { sections ->
            var i = 0
            while (i < pairs.size) {
                if (pairs[i] != (0 to "SECTION") || i + 1 >= pairs.size) {
                    i++
                    continue
                }
                val name = pairs[i + 1].second
                val objects = ArrayList<Obj>()
                var type = ""
                var current = ArrayList<Pair<Int, String>>()
                i += 2
                while (i < pairs.size && pairs[i] != (0 to "ENDSEC")) {
                    if (pairs[i].first == 0) {
                        if (type.isNotEmpty() || current.isNotEmpty()) objects += Obj(type, current)
                        type = pairs[i].second
                        current = ArrayList()
                    } else {
                        current += pairs[i]
                    }
                    i++
                }
                if (type.isNotEmpty() || current.isNotEmpty()) objects += Obj(type, current)
                sections[name] = objects
            }
        }

        fun objects(section: String, type: String? = null) =
            sections[section].orEmpty().filter { type == null || it.type == type }

        /** Each table's records under its name, in the file's order. */
        val tables: Map<String, List<Obj>> = LinkedHashMap<String, MutableList<Obj>>().also { tables ->
            var records: MutableList<Obj>? = null
            for (o in objects("TABLES")) {
                when (o.type) {
                    "TABLE" -> records = ArrayList<Obj>().also { tables[o.first(2).orEmpty()] = it }
                    "ENDTAB" -> records = null
                    else -> records?.add(o)
                }
            }
        }

        /** Every object with a handle, under it. */
        val byHandle: Map<String, Obj> = sections.filterKeys { it != "HEADER" }.values.flatten()
            .mapNotNull { o -> o.handle?.let { it to o } }.toMap()

        /** The root dictionary: owned by nothing, it names every other one. */
        val root get() = objects("OBJECTS").first { it.type == "DICTIONARY" && it.owner == "0" }

        fun entities(type: String, layer: String? = null) =
            objects("ENTITIES", type).filter { layer == null || it.layer == layer }

        /** The value of the header variable [name]. */
        fun header(name: String): String? {
            val variables = sections["HEADER"]?.firstOrNull { it.type.isEmpty() }?.pairs ?: return null
            val at = variables.indexOf(9 to name)
            return variables.getOrNull(at + 1)?.takeIf { at >= 0 && it.first != 9 }?.second
        }

        /** The header's variables, in the file's order. */
        val headerNames: List<String>
            get() = sections["HEADER"]?.firstOrNull { it.type.isEmpty() }?.pairs.orEmpty().filter { it.first == 9 }.map { it.second }

        /** The group code and value the header variable [name] holds, its value trimmed; null when it is not there. */
        fun headerPair(name: String): Pair<Int, String>? {
            val variables = sections["HEADER"]?.firstOrNull { it.type.isEmpty() }?.pairs ?: return null
            val at = variables.indexOf(9 to name)
            return variables.getOrNull(at + 1)?.takeIf { at >= 0 && it.first != 9 }?.let { it.first to it.second.trim() }
        }

        /** The point the header variable [name] holds: its x and y. */
        fun headerPoint(name: String): DoubleArray {
            val variables = sections["HEADER"]?.firstOrNull { it.type.isEmpty() }?.pairs.orEmpty()
            val at = variables.indexOf(9 to name)
            val x = variables.getOrNull(at + 1)?.takeIf { at >= 0 && it.first == 10 }?.second?.trim()?.toDoubleOrNull()
            val y = variables.getOrNull(at + 2)?.takeIf { at >= 0 && it.first == 20 }?.second?.trim()?.toDoubleOrNull()
            return doubleArrayOf(x ?: Double.NaN, y ?: Double.NaN)
        }

        private companion object {
            val ESCAPE = Regex("""\\U\+([0-9A-Fa-f]{4})""")
        }
    }

    private companion object {
        val LAYERS = listOf("WALLS", "OPENINGS", "DIMENSIONS", "TEXT", "LEGEND")

        /** The metric drawing's header variables, in order, as they were before the dimension units. */
        val METRIC_HEADER = listOf(
            "\$ACADVER", "\$DWGCODEPAGE", "\$INSBASE", "\$EXTMIN", "\$EXTMAX",
            "\$LUNITS", "\$LUPREC", "\$INSUNITS", "\$MEASUREMENT", "\$HANDSEED",
        )

        /**
         * The empty R2000 drawing ezdxf 1.4.2 writes, `ezdxf.new("R2000").saveas("ezdxf-new-R2000.dxf")`:
         * the structure a CAD tool expects of an AC1015 file, which this drawing is held to.
         */
        val REFERENCE by lazy {
            val stream = RoomPlanDxfTest::class.java.getResourceAsStream("ezdxf-new-R2000.dxf")
            DxfRead(requireNotNull(stream) { "ezdxf-new-R2000.dxf is not among the test resources" }.use { it.readBytes() })
        }

        /**
         * Records ezdxf adds that this drawing has no use for: its own application, the hatch
         * background's (no hatch here), and Defpoints, the layer a DIMENSION's points go on (none here).
         */
        val EZDXF_ONLY = setOf("EZDXF", "HATCHBACKGROUNDCOLOR", "DEFPOINTS")

        /**
         * Root dictionary entries ezdxf writes whatever the version, but that came after R2000:
         * colours (2004), table styles (2005), materials and visual styles (2007), multileader
         * styles and scale lists (2008), and ezdxf's own.
         */
        val LATER_THAN_R2000 = setOf(
            "ACAD_COLOR", "ACAD_TABLESTYLE", "ACAD_MATERIAL", "ACAD_VISUALSTYLE", "ACAD_MLEADERSTYLE", "ACAD_SCALELIST", "EZDXF_META",
        )

        /**
         * The dimension style's head, the only codes held to ezdxf's: its variables are ISO, in
         * millimetres, and no DIMENSION entity here would use them.
         */
        val DIMSTYLE_HEAD = setOf(105, 330, 100, 2, 70)

        /** An owner, a soft pointer, an entry, a hard owner and a plot style: each names a handle. */
        val POINTERS = setOf(330, 340, 350, 360, 390)

        /** Group codes from 1000 up carry an application's extended data. */
        const val EXTENDED_DATA = 1000

        const val MILLIMETRES_PER_METRE = 1000.0
        const val INCHES_PER_METRE = 1000.0 / 25.4

        /** A thousandth of a drawing unit is written; a hundredth is the slack read back. */
        const val UNIT_TOLERANCE = 0.01

        val WORDS = RoomPlanDxf.Words(
            legend = listOf(
                "Living room plan",
                "Indicative plan: verify every figure on site.",
                "Drawing in millimetres, figures in metres.",
            ),
            door = "Door %1\$s",
            window = "Window %1\$s",
            sill = "sill %1\$s",
        )

        val OPENINGS = listOf(
            Opening(OpeningKind.Door, wall = 0, from = 1f, width = 0.9f, height = 2.1f),
            Opening(OpeningKind.Window, wall = 1, from = 2f, width = 1.2f, height = 1.2f, sill = 0.9f),
            Opening(OpeningKind.Door, wall = 2, from = 0.5f, width = 0.8f),
        )
    }
}
