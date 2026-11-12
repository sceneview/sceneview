package io.github.sceneview.demo.demos.internal.measure

import io.github.sceneview.demo.demos.internal.spatial.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class ObjectMeasureTest {
    private val support = BoxSupport(BoxPoint(0.0, 0.8, 0.0), BoxPoint(0.0, 2.0, 0.0))
    private val a = BoxPoint(0.0, 0.81, 0.0)
    private val b = BoxPoint(0.42, 0.79, 0.0)
    // Along-line displacement must not inflate the short dimension.
    private val c = BoxPoint(0.1, 0.83, -0.18)
    private val top = BoxPoint(0.15, 0.91, -0.1)
    private fun box() = ObjectBox.fromPoints(support, a, b, c, top)

    @Test fun `projection and dimensions ignore off-surface corner height`() {
        assertEquals(BoxPoint(0.0, 0.8, 0.0), support.project(a))
        val box = box()
        assertEquals(0.42, box.lengthM, 1e-9)
        assertEquals(0.18, box.widthM, 1e-9)
        assertEquals(0.11, box.heightM, 1e-9)
        val vertices = box.vertices(support.unitNormal)
        assertEquals(8, vertices.size)
        assertEquals(0.91, vertices[6].y, 1e-9)
        assertEquals(-0.18, vertices[6].z, 1e-9)
    }

    @Test fun `tilted translated support uses its normal for projection and height`() {
        val n = BoxPoint(0.0, 1 / sqrt(2.0), 1 / sqrt(2.0))
        val plane = BoxSupport(BoxPoint(2.0, 3.0, 4.0), n)
        val start = plane.origin
        val end = start + BoxPoint(0.4, 0.0, 0.0)
        val across = start + BoxPoint(0.0, 0.2 / sqrt(2.0), -0.2 / sqrt(2.0))
        val result = ObjectBox.fromPoints(plane, start + n * 0.02, end, across, start + n * 0.1)
        assertEquals(0.4, result.lengthM, 1e-9)
        assertEquals(0.2, result.widthM, 1e-9)
        assertEquals(0.1, result.heightM, 1e-9)
    }

    private fun refuses(reason: ObjectBoxError, action: () -> Unit) {
        try { action(); fail("Expected $reason") } catch (e: ObjectBoxException) { assertEquals(reason, e.reason) }
    }

    @Test fun `reject coincident length collinear width and zero height`() {
        refuses(ObjectBoxError.TOO_CLOSE) { ObjectBox.along(support, a, a) }
        refuses(ObjectBoxError.TOO_CLOSE) { ObjectBox.fromPoints(support, a, b, b) }
        refuses(ObjectBoxError.TOO_CLOSE) { ObjectBox.fromPoints(support, a, b, c, support.project(top)) }
        refuses(ObjectBoxError.BELOW_SUPPORT) { ObjectBox.fromPoints(support, a, b, c, BoxPoint(0.1, 0.7, 0.0)) }
        refuses(ObjectBoxError.TOO_LARGE) { ObjectBox.along(support, a, BoxPoint(11.0, 0.8, 0.0)) }
        refuses(ObjectBoxError.INVALID_SUPPORT) { BoxSupport(a, BoxPoint(0.0, 0.0, 0.0)) }
    }

    @Test fun `footprint preview has no invented height`() {
        assertEquals(0.0, ObjectBox.fromPoints(support, a, b, c).heightM, 0.0)
    }

    @Test fun `uncertainty follows source and distance without claiming calibration`() {
        for (distance in listOf(0.5, 0.55, 0.6)) {
            val estimates = ObjectHitType.entries.map { MeasureUncertainty.estimate(it, distance) }
            assertTrue(estimates.all { it in 1.0..3.0 })
            assertTrue(estimates[0] < estimates[1] && estimates[1] < estimates[2])
        }
        assertTrue(MeasureUncertainty.estimate(ObjectHitType.PLANE, 0.2) > MeasureUncertainty.estimate(ObjectHitType.PLANE, 0.55))
        assertTrue(MeasureUncertainty.estimate(ObjectHitType.PLANE, 1.5) > MeasureUncertainty.estimate(ObjectHitType.PLANE, 1.0))
        fun tap(type: ObjectHitType, distance: Double) = ObjectTap(a, type, distance)
        val plane = tap(ObjectHitType.PLANE, 0.55)
        val feature = tap(ObjectHitType.FEATURE, 1.5)
        val estimate = MeasureUncertainty.estimate(plane, plane, plane, feature, tap(ObjectHitType.DEPTH, 0.55))
        assertEquals(1.0, estimate.lengthCm, 0.0)
        assertEquals(6.0, estimate.widthCm, 0.0)
        assertEquals(2.0, estimate.heightCm, 0.0)
        assertEquals(6, estimate.displayEachCm)
    }

    @Test fun `JSON declares millimetres null confidence interval and per-dimension estimates`() {
        val json = Json.parseToJsonElement(MeasureBoxJson.encode(box(), DimensionHalfWidths(1.0, 2.0, 3.0))).jsonObject
        assertEquals("sceneview.measure/1", json.getValue("schema").jsonPrimitive.content)
        assertEquals("mm", json.getValue("units").jsonPrimitive.content)
        val dims = json.getValue("dimensions").jsonObject
        listOf("L" to 420.0, "W" to 180.0, "H" to 110.0).forEachIndexed { i, (name, value) ->
            val dimension = dims.getValue(name).jsonObject
            assertEquals(value, dimension.getValue("value").jsonPrimitive.double, 1e-6)
            assertEquals(JsonNull, dimension["u95"])
            assertEquals((i + 1) * 10.0, dimension.getValue("halfWidthEstimate").jsonPrimitive.double, 0.0)
        }
        assertEquals("uncalibrated", json.getValue("quality").jsonObject.getValue("uncertainty_status").jsonPrimitive.content)
        assertEquals("plane-constrained-taps", json.getValue("quality").jsonObject.getValue("method").jsonPrimitive.content)
    }

    @Test fun `measured answer maps L H W to width height depth in metres`() {
        val spec = box().toSpatialAnswer(SpatialPlacement.TABLE, "Measured object")
        assertEquals(listOf(0.42f, 0.11f, 0.18f), (spec.subject as SpatialObject.Box).sizeM)
        assertEquals(DimensionSource.MEASURED, spec.dimensionSource)
        assertNull(spec.validate())
        assertEquals(SpatialPlacement.TABLE, objectPlacement(0.8, null))
        assertEquals(SpatialPlacement.TABLE, objectPlacement(0.8, 0.0))
        assertEquals(SpatialPlacement.FLOOR, objectPlacement(0.03, 0.0))
    }
}
