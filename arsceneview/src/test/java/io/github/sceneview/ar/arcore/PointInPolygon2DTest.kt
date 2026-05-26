package io.github.sceneview.ar.arcore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ray-casting [pointInPolygon2D] helper used by the V2 plane renderer to clip
 * depth-driven vertices to the ARCore plane polygon (#2203 PR #2). The helper is `internal`
 * so this JVM test owns its correctness — a sign error or an off-by-one would only surface
 * as a stretched-out depth mesh on a device otherwise.
 */
class PointInPolygon2DTest {

    @Test
    fun `empty polygon never contains anything`() {
        assertFalse(pointInPolygon2D(0f, 0f, FloatArray(0)))
        assertFalse(pointInPolygon2D(1f, 2f, FloatArray(0)))
    }

    @Test
    fun `single-point polygon is degenerate`() {
        assertFalse(pointInPolygon2D(0f, 0f, floatArrayOf(0f, 0f)))
    }

    @Test
    fun `two-point polygon is degenerate`() {
        assertFalse(pointInPolygon2D(0.5f, 0.5f, floatArrayOf(0f, 0f, 1f, 0f)))
    }

    // ── Convex unit square ─────────────────────────────────────────────────────────────────

    private val unitSquare = floatArrayOf(
        0f, 0f,
        1f, 0f,
        1f, 1f,
        0f, 1f,
    )

    @Test
    fun `centre of unit square is inside`() {
        assertTrue(pointInPolygon2D(0.5f, 0.5f, unitSquare))
    }

    @Test
    fun `far outside the unit square is outside`() {
        assertFalse(pointInPolygon2D(-5f, -5f, unitSquare))
        assertFalse(pointInPolygon2D(10f, 0.5f, unitSquare))
        assertFalse(pointInPolygon2D(0.5f, 10f, unitSquare))
    }

    @Test
    fun `four near-corner points inside the unit square`() {
        assertTrue(pointInPolygon2D(0.01f, 0.01f, unitSquare))
        assertTrue(pointInPolygon2D(0.99f, 0.01f, unitSquare))
        assertTrue(pointInPolygon2D(0.99f, 0.99f, unitSquare))
        assertTrue(pointInPolygon2D(0.01f, 0.99f, unitSquare))
    }

    // ── Concave L-shape ────────────────────────────────────────────────────────────────────

    /**
     * An L-shape: the unit 2×2 square with the top-right 1×1 quadrant carved out.
     *
     *     +---+
     *     |   |
     *     |   +---+
     *     |       |
     *     +-------+
     *
     * Vertices in CCW order:
     *   (0,0) → (2,0) → (2,1) → (1,1) → (1,2) → (0,2)
     */
    private val lShape = floatArrayOf(
        0f, 0f,
        2f, 0f,
        2f, 1f,
        1f, 1f,
        1f, 2f,
        0f, 2f,
    )

    @Test
    fun `inside the L's bottom horizontal bar`() {
        assertTrue(pointInPolygon2D(1.5f, 0.5f, lShape))
    }

    @Test
    fun `inside the L's left vertical bar`() {
        assertTrue(pointInPolygon2D(0.5f, 1.5f, lShape))
    }

    @Test
    fun `inside the L's corner where the bars meet`() {
        assertTrue(pointInPolygon2D(0.5f, 0.5f, lShape))
    }

    @Test
    fun `inside the carved-out top-right bay is outside the L`() {
        assertFalse(pointInPolygon2D(1.5f, 1.5f, lShape))
    }

    @Test
    fun `far outside the L is outside`() {
        assertFalse(pointInPolygon2D(-1f, 0.5f, lShape))
        assertFalse(pointInPolygon2D(3f, 0.5f, lShape))
        assertFalse(pointInPolygon2D(0.5f, 3f, lShape))
    }

    // ── Half-open interval (vertex-on-ray) ────────────────────────────────────────────────

    @Test
    fun `query along a shared horizontal edge does not double-count`() {
        // Triangle (0,0)-(1,0)-(0.5,1). Query along y=0 should not "see" the shared edge
        // (zi > z) != (zj > z) → false for two endpoints with z = 0. Centroid test:
        assertTrue(pointInPolygon2D(0.5f, 0.3f, floatArrayOf(0f, 0f, 1f, 0f, 0.5f, 1f)))
        // Above the triangle apex.
        assertFalse(pointInPolygon2D(0.5f, 1.5f, floatArrayOf(0f, 0f, 1f, 0f, 0.5f, 1f)))
    }

    @Test
    fun `tiny ARCore-shaped polygon admits its centroid`() {
        // ARCore polygons are simple, small, CCW (or CW) — this is a 30 cm × 50 cm rectangle.
        val poly = floatArrayOf(
            -0.15f, -0.25f,
            0.15f, -0.25f,
            0.15f, 0.25f,
            -0.15f, 0.25f,
        )
        assertTrue(pointInPolygon2D(0f, 0f, poly))
        // Just outside on every axis.
        assertFalse(pointInPolygon2D(0.20f, 0f, poly))
        assertFalse(pointInPolygon2D(0f, 0.30f, poly))
        assertFalse(pointInPolygon2D(-0.20f, 0f, poly))
    }
}
