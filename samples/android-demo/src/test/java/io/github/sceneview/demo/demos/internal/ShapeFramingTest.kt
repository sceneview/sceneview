package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * JVM tests for [ShapeFraming] — the framing arithmetic behind the *Shape Extrude* sub-mode of
 * [io.github.sceneview.demo.demos.CustomGeometryDemo].
 *
 * Same reason [GeometryLayoutTest] exists: the defect guarded against
 * ([#2937](https://github.com/sceneview/sceneview/issues/2937)) is a *number* — an outline wider
 * than the frame it is viewed in — which compiles, renders and passes every blank-frame guard.
 */
class ShapeFramingTest {

    private val portraitFillCeiling = 0.80f
    private val worstCaseFillCeiling = 0.92f

    @Test
    fun `outline extents are measured from the vertices the demo extrudes`() {
        // Triangle: 1 m wide, apex at 0.5, base at -0.3.
        assertEquals(0.5f, ShapeFraming.halfWidth(ShapeFraming.trianglePath), 1e-5f)
        assertEquals(0.4f, ShapeFraming.halfHeight(ShapeFraming.trianglePath), 1e-5f)
        // Hexagon with a vertex on +X: circumradius wide, apothem tall.
        assertEquals(0.4f, ShapeFraming.halfWidth(ShapeFraming.hexagonPath), 1e-5f)
        assertEquals(0.4f * sqrt(3f) / 2f, ShapeFraming.halfHeight(ShapeFraming.hexagonPath), 1e-5f)
        // The triangle is the widest outline, so it is the one the distance is derived for.
        assertEquals(0.5f, ShapeFraming.halfWidth, 1e-5f)
    }

    @Test
    fun `the old framing really was clipped`() {
        // eyePosition = (0, 0, 1.5) → 1.5 m out, not the 2.5 m the target suggested.
        val fill = ShapeFraming.horizontalFillRatio(1.5f, GeometryLayout.PHONE_PORTRAIT_ASPECT)
        assertTrue("expected the 1.5 m framing to clip, it fills $fill", fill > 1f)
    }

    @Test
    fun `orbit home offset has the requested length and no elevation`() {
        val offset = ShapeFraming.orbitHomeOffset(ShapeFraming.CAMERA_DISTANCE)
        val length = sqrt(offset.x * offset.x + offset.y * offset.y + offset.z * offset.z)
        assertEquals(ShapeFraming.CAMERA_DISTANCE, length, 1e-5f)
        assertEquals(0f, offset.y, 0f)
    }

    @Test
    fun `widest outline fits a phone-portrait frame with visible margin at the default distance`() {
        val fill = ShapeFraming.horizontalFillRatio(
            ShapeFraming.CAMERA_DISTANCE,
            GeometryLayout.PHONE_PORTRAIT_ASPECT,
        )
        assertTrue("outline fills $fill of the frame width — clipped", fill < 1f)
        assertTrue("outline fills $fill of the frame width — no visible margin", fill < portraitFillCeiling)
        assertTrue("outline fills only $fill of the frame width — too small to read", fill > 0.6f)
    }

    @Test
    fun `widest outline still clears the narrowest frame it could be rendered in`() {
        val fill = ShapeFraming.horizontalFillRatio(
            ShapeFraming.CAMERA_DISTANCE,
            GeometryLayout.NARROWEST_EXPECTED_ASPECT,
        )
        assertTrue("outline fills $fill of the worst-case frame width — clipped", fill < 1f)
        assertTrue("outline fills $fill of the worst-case frame width — margin too thin", fill < worstCaseFillCeiling)
    }

    @Test
    fun `tallest outline is nowhere near the vertical limit`() {
        val fill = ShapeFraming.verticalFillRatio(ShapeFraming.CAMERA_DISTANCE)
        assertTrue("outline fills $fill of the frame height", fill < 0.5f)
    }
}
