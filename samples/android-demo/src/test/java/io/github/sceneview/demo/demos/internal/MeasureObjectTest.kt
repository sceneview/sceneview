package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.*
import org.junit.Test

class MeasureObjectTest {
    private val origin = Position(0f, 0f, 0f)
    private fun pair(measure: ObjectMeasure<Position>, end: Position) = measure.capture(origin) { it }.capture(end) { it }

    @Test fun `three independent pairs yield L W H in millimeters then review`() {
        var measure = ObjectMeasure<Position>()
        assertEquals(measure, measure.capture(origin) { it })
        measure = pair(measure.start(), Position(0.22f, 0f, 0f)).next()
        assertEquals(ObjectDimension.Width, measure.selected)
        measure = pair(measure, Position(0f, 0f, 0.052f)).next()
        assertEquals(ObjectDimension.Height, measure.selected)
        measure = pair(measure, Position(0f, 0.035f, 0f)).next()
        assertEquals(ObjectMeasureStage.Review, measure.stage)
        assertTrue(measure.complete)
        assertEquals(220.0, measure.dimension(ObjectDimension.Length).value!!.millimeters, 0.001)
        assertEquals(52.0, measure.dimension(ObjectDimension.Width).value!!.millimeters, 0.001)
        assertEquals(35.0, measure.dimension(ObjectDimension.Height).value!!.millimeters, 0.001)
    }

    @Test fun `miss invalid position and lost first endpoint do not advance`() {
        val measure = ObjectMeasure<Position>().start().capture(origin) { it }
        assertEquals(measure, measure.capture(null) { it })
        assertEquals(measure, measure.capture(Position(Float.NaN, 0f, 0f)) { it })
        assertEquals(measure, measure.capture(Position(1f, 0f, 0f)) { if (it == origin) null else it })
        assertEquals(measure, measure.capture(origin) { it })
        assertEquals(measure, measure.next())
    }

    @Test fun `undo repeat and corrections affect only the selected dimension`() {
        val length = pair(ObjectMeasure<Position>().start(), Position(0.2f, 0f, 0f))
        val width = pair(length.next(), Position(0f, 0f, 0.05f))
        val corrected = width.correct(ObjectDimension.Length, 212.5)
        assertEquals(ObjectMeasureProvenance.Manual, corrected.dimension(ObjectDimension.Length).value!!.provenance)
        val undone = corrected.undo(ObjectDimension.Width)
        assertEquals(1, undone.dimension().endpoints.size)
        assertNull(undone.dimension().value)
        assertEquals(corrected.dimension(ObjectDimension.Length), undone.dimension(ObjectDimension.Length))
        val repeated = corrected.repeat(ObjectDimension.Length)
        assertTrue(repeated.dimension().endpoints.isEmpty())
        assertNull(repeated.dimension().value)
        assertEquals(width.dimension(ObjectDimension.Width), repeated.dimension(ObjectDimension.Width))
        assertEquals(ObjectMeasureProvenance.Measured, pair(repeated, Position(0.21f, 0f, 0f)).dimension().value!!.provenance)
    }

    @Test fun `manual only fallback can finish without depth`() {
        var measure = ObjectMeasure<Position>().start()
        ObjectDimension.entries.forEach { measure = measure.correct(it, 1.0).next() }
        assertTrue(measure.complete)
        assertEquals(ObjectMeasureStage.Review, measure.stage)
    }

    @Test fun `candidate misses expiry and double taps never reuse the last hit`() {
        val candidate = ObjectMeasureCandidate<String>()
        candidate.update("first", 1000)
        candidate.update(null, 1010)
        assertNull(candidate.consume(1011))
        candidate.update("second", 1100)
        assertEquals("second", candidate.consume(1101))
        assertNull(candidate.consume(1102))
        candidate.update("stale", 1200)
        assertNull(candidate.consume(1451))
        candidate.update("future", 1600)
        assertNull(candidate.consume(1599))
    }

    @Test fun `depth point wins image is fallback and no depth means no hit`() {
        assertEquals("foreground", objectMeasureDepthHit("foreground") { error("Should not acquire image") })
        assertEquals("image", objectMeasureDepthHit(null) { "image" })
        assertNull(objectMeasureDepthHit<String>(null) { null })
    }

    @Test fun `switching through object mode preserves the other modes and cannot add outline points`() {
        listOf(MeasureMode.Distance, MeasureMode.Wall, MeasureMode.Room).forEach { mode ->
            val board = MeasureBoard<Int>(mode = mode).tap(1, false).tap(2, false)
            val objectBoard = board.withMode(MeasureMode.Object)
            assertEquals(objectBoard, objectBoard.tap(3, false))
            assertEquals(board, objectBoard.withMode(mode))
        }
    }

    @Test fun `manual input rejects invalid numeric values`() {
        assertEquals(52.5, parseObjectMillimeters(" 52,5 ")!!, 0.0)
        listOf("", "0", "0.0", "-1", "NaN", "Infinity", "1e309", "1,2,3", "2mm").forEach { assertNull(parseObjectMillimeters(it)) }
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertThrows(IllegalArgumentException::class.java) { ObjectMeasureValue(it, ObjectMeasureProvenance.Manual) }
        }
    }

    @Test fun `status describes each dimensions provenance honestly`() {
        ObjectDimension.entries.forEach { axis ->
            assertEquals("${axis.name} entered manually. Continue, correct or repeat.",
                objectMeasureStatus(axis, ObjectMeasureValue(182.0, ObjectMeasureProvenance.Manual)))
            assertEquals("${axis.name} captured. Continue, correct or repeat.",
                objectMeasureStatus(axis, ObjectMeasureValue(182.0, ObjectMeasureProvenance.Measured)))
        }
    }

    @Test fun `capture chips include units only for present values`() {
        ObjectDimension.entries.forEach { axis ->
            assertEquals("${axis.symbol}: —", objectMeasureChip(axis, null))
            assertEquals("${axis.symbol}: 182 mm", objectMeasureChip(axis, ObjectMeasureValue(182.0, ObjectMeasureProvenance.Manual)))
            assertEquals("${axis.symbol}: 53.5 mm", objectMeasureChip(axis, ObjectMeasureValue(53.5, ObjectMeasureProvenance.Measured)))
        }
    }

    @Test fun `editor shows errors only after invalid edits`() {
        assertFalse(objectMeasureInputError("", false))
        listOf("", "0", "-2", "abc").forEach { assertTrue(objectMeasureInputError(it, true)) }
        assertFalse(objectMeasureInputError("0.1", true))
    }

    @Test fun `share includes dimensions approximation and mixed provenance`() {
        val values = mapOf(
            ObjectDimension.Length to ObjectMeasureValue(182.0, ObjectMeasureProvenance.Manual),
            ObjectDimension.Width to ObjectMeasureValue(120.0, ObjectMeasureProvenance.Measured),
            ObjectDimension.Height to ObjectMeasureValue(35.0, ObjectMeasureProvenance.Manual),
        )
        assertEquals("Object 182 × 120 × 35 mm (L × W × H) — approximate; L entered manually; W measured; H entered manually.",
            objectMeasureShareText(values))
        assertEquals("Object 182 × — × — mm (L × W × H) — approximate; L entered manually.",
            objectMeasureShareText(values.filterKeys { it == ObjectDimension.Length }))
        assertEquals("Object — × — × — mm (L × W × H) — approximate.", objectMeasureShareText(emptyMap()))
    }

    @Test fun `JSON preserves numbers provenance unknowns and uncalibrated uncertainty`() {
        val json = Json.parseToJsonElement(objectMeasureJson(mapOf(
            ObjectDimension.Length to ObjectMeasureValue(220.25, ObjectMeasureProvenance.Measured),
            ObjectDimension.Width to ObjectMeasureValue(52.0, ObjectMeasureProvenance.Manual),
        ), "Phone \"test\"\nmodel")).jsonObject
        assertEquals("sceneview.measure/1", json.getValue("schema").jsonPrimitive.content)
        assertEquals("mm", json.getValue("units").jsonPrimitive.content)
        val dimensions = json.getValue("dimensions").jsonObject
        assertEquals(220.25, dimensions.getValue("L").jsonObject.getValue("value").jsonPrimitive.double, 0.0)
        assertEquals("measured", dimensions.getValue("L").jsonObject.getValue("provenance").jsonPrimitive.content)
        assertEquals("manual", dimensions.getValue("W").jsonObject.getValue("provenance").jsonPrimitive.content)
        assertEquals(JsonNull, dimensions.getValue("H").jsonObject.getValue("value"))
        dimensions.values.forEach { assertEquals(JsonNull, it.jsonObject.getValue("u95")) }
        val quality = json.getValue("quality").jsonObject
        assertEquals("uncalibrated", quality.getValue("uncertainty_status").jsonPrimitive.content)
        assertEquals("false", quality.getValue("scale_verified").jsonPrimitive.content)
        assertEquals("Phone \"test\"\nmodel", quality.getValue("device").jsonPrimitive.content)
        assertTrue(json.getValue("features").jsonArray.isEmpty())
        assertEquals(JsonNull, json.getValue("mesh"))
    }
}
