package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [MeasureMath][measureDistanceMeters] — the arithmetic behind the
 * `ar-measure` demo's on-screen numbers.
 *
 * A wrong distance formula produces a plausible-looking centimetre figure that no
 * screenshot, emulator run or device QA pass would flag: the label renders, the app does
 * not crash, and the number is simply false. These tests are the only place that failure
 * mode is caught, so they pin the value, not merely the shape.
 */
class MeasureMathTest {

    private fun p(x: Float, y: Float, z: Float) = Position(x = x, y = y, z = z)

    // ── measureDistanceMeters ───────────────────────────────────────────────

    @Test
    fun `distance is euclidean across all three axes`() {
        // 3-4-5 in the XZ plane, lifted 12 on Y: sqrt(25 + 144) = 13.
        assertEquals(13f, measureDistanceMeters(p(0f, 0f, 0f), p(3f, 12f, 4f)), 1e-4f)
    }

    @Test
    fun `distance is symmetric and zero for a degenerate segment`() {
        val a = p(0.3f, -1.2f, 2f)
        val b = p(-0.7f, 0.5f, 1f)
        assertEquals(measureDistanceMeters(a, b), measureDistanceMeters(b, a), 1e-6f)
        assertEquals(0f, measureDistanceMeters(a, a), 0f)
    }

    @Test
    fun `distance ignores sign of the offset`() {
        // A point behind the origin must read the same as one in front of it — a bug that
        // dropped the sign would still render a positive-looking centimetre label.
        assertEquals(2f, measureDistanceMeters(p(0f, 0f, 0f), p(0f, 0f, -2f)), 1e-6f)
    }

    // ── measureMidpoint ─────────────────────────────────────────────────────

    @Test
    fun `midpoint is the average of both ends`() {
        val mid = measureMidpoint(p(0f, 0f, 0f), p(1f, 3f, -5f))
        assertEquals(0.5f, mid.x, 1e-6f)
        assertEquals(1.5f, mid.y, 1e-6f)
        assertEquals(-2.5f, mid.z, 1e-6f)
    }

    // ── formatCentimeters ───────────────────────────────────────────────────

    @Test
    fun `formatting converts metres to centimetres with one decimal`() {
        assertEquals("83.4 cm", formatCentimeters(0.834f))
        assertEquals("100.0 cm", formatCentimeters(1f))
    }

    @Test
    fun `formatting stays in centimetres well past one metre`() {
        // No unit switch at 1 m: a chain of segments must stay visually comparable.
        assertEquals("312.7 cm", formatCentimeters(3.127f))
    }

    @Test
    fun `formatting never renders sub-millimetre precision`() {
        // 0.83449 m is 83.449 cm — the tenth-of-a-millimetre digit must not survive,
        // because the underlying pose has nothing like that precision.
        assertEquals("83.4 cm", formatCentimeters(0.83449f))
    }

    @Test
    fun `formatting uses a dot regardless of the device locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            // fr-FR formats decimals with a comma; the label must not follow it, so a
            // screenshot or a copied figure reads the same everywhere.
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            assertEquals("83.4 cm", formatCentimeters(0.834f))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    // ── measurePerimeterMeters ──────────────────────────────────────────────

    @Test
    fun `perimeter of fewer than two points is zero`() {
        assertEquals(0f, measurePerimeterMeters(emptyList(), closed = false), 0f)
        assertEquals(0f, measurePerimeterMeters(listOf(p(1f, 1f, 1f)), closed = true), 0f)
    }

    @Test
    fun `open chain sums only the consecutive segments`() {
        val chain = listOf(p(0f, 0f, 0f), p(1f, 0f, 0f), p(1f, 0f, 1f))
        assertEquals(2f, measurePerimeterMeters(chain, closed = false), 1e-5f)
    }

    @Test
    fun `closing a square adds the fourth side`() {
        val square = listOf(
            p(0f, 0f, 0f), p(2f, 0f, 0f), p(2f, 0f, 2f), p(0f, 0f, 2f),
        )
        assertEquals(6f, measurePerimeterMeters(square, closed = false), 1e-5f)
        assertEquals(8f, measurePerimeterMeters(square, closed = true), 1e-5f)
    }

    @Test
    fun `closing a two-point chain does not double-count the single segment`() {
        // Two points make a segment, not a loop: closing must not walk back along it.
        val pair = listOf(p(0f, 0f, 0f), p(1.5f, 0f, 0f))
        assertEquals(1.5f, measurePerimeterMeters(pair, closed = true), 1e-5f)
    }

    // ── measureBoundingBox ──────────────────────────────────────────────────

    @Test
    fun `bounding box needs at least two points`() {
        assertNull(measureBoundingBox(emptyList()))
        assertNull(measureBoundingBox(listOf(p(1f, 2f, 3f))))
    }

    @Test
    fun `bounding box spans the extremes on every axis`() {
        val box = measureBoundingBox(
            listOf(p(-1f, 0f, 0.5f), p(2f, 1.5f, -0.5f), p(0f, -0.5f, 0f)),
        )!!
        assertEquals(3f, box.widthMeters, 1e-5f)   // -1 → 2
        assertEquals(2f, box.heightMeters, 1e-5f)  // -0.5 → 1.5
        assertEquals(1f, box.depthMeters, 1e-5f)   // -0.5 → 0.5
    }

    @Test
    fun `a flat point set reports a zero extent rather than failing`() {
        // Every point on one horizontal plane — a floor outline. Height must be 0, not NaN.
        val box = measureBoundingBox(listOf(p(0f, 1f, 0f), p(1f, 1f, 0f), p(1f, 1f, 1f)))!!
        assertEquals(0f, box.heightMeters, 0f)
    }

    // ── measurePointsMoved ──────────────────────────────────────────────────

    @Test
    fun `a resized point list always counts as moved`() {
        assertTrue(measurePointsMoved(emptyList(), listOf(p(0f, 0f, 0f))))
        assertTrue(measurePointsMoved(listOf(p(0f, 0f, 0f)), emptyList()))
    }

    @Test
    fun `sub-millimetre anchor jitter does not count as movement`() {
        val before = listOf(p(0f, 0f, 0f), p(1f, 0f, 0f))
        val after = listOf(p(0.0002f, 0f, 0f), p(1f, 0.0003f, 0f))
        assertFalse(measurePointsMoved(before, after))
    }

    @Test
    fun `a real anchor correction counts as movement`() {
        val before = listOf(p(0f, 0f, 0f), p(1f, 0f, 0f))
        val after = listOf(p(0f, 0f, 0f), p(1.01f, 0f, 0f))
        assertTrue(measurePointsMoved(before, after))
    }
}
