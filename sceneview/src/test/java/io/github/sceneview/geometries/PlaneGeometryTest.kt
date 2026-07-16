package io.github.sceneview.geometries

import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [Plane] companion object geometry generation.
 *
 * [Plane.getVertices], [Plane.INDICES], and [Plane.UV_COORDINATES] are pure values/functions
 * with no Filament dependency.
 */
class PlaneGeometryTest {

    @Test
    fun `default size is 1x1`() {
        assertEquals(Float3(1f, 1f, 0f), Plane.DEFAULT_SIZE)
    }

    @Test
    fun `default center is origin`() {
        assertEquals(Float3(0f, 0f, 0f), Plane.DEFAULT_CENTER)
    }

    @Test
    fun `default normal points up (y=1)`() {
        assertEquals(Float3(0f, 1f, 0f), Plane.DEFAULT_NORMAL)
    }

    @Test
    fun `UV_COORDINATES has 4 entries covering unit square`() {
        assertEquals(4, Plane.UV_COORDINATES.size)
        assertTrue(Plane.UV_COORDINATES.contains(Float2(0f, 0f)))
        assertTrue(Plane.UV_COORDINATES.contains(Float2(0f, 1f)))
        assertTrue(Plane.UV_COORDINATES.contains(Float2(1f, 1f)))
        assertTrue(Plane.UV_COORDINATES.contains(Float2(1f, 0f)))
    }

    @Test
    fun `INDICES has 1 primitive with 6 indices (2 triangles)`() {
        assertEquals(1, Plane.INDICES.size)
        assertEquals(6, Plane.INDICES[0].size)
    }

    @Test
    fun `getVertices returns exactly 4 vertices`() {
        val vertices = Plane.getVertices(
            Plane.DEFAULT_SIZE,
            Plane.DEFAULT_CENTER,
            Plane.DEFAULT_NORMAL
        )
        assertEquals(4, vertices.size)
    }

    @Test
    fun `all vertices have the specified normal`() {
        val normal = Float3(0f, 1f, 0f)
        val vertices = Plane.getVertices(Float3(2f, 2f, 0f), Float3(0f), normal)

        for (v in vertices) {
            assertEquals(normal, v.normal)
        }
    }

    @Test
    fun `vertices span the correct size`() {
        val size = Float3(4f, 6f, 0f)
        val vertices = Plane.getVertices(size, Float3(0f), Float3(0f, 1f, 0f))

        val minX = vertices.minOf { it.position.x }
        val maxX = vertices.maxOf { it.position.x }
        val minY = vertices.minOf { it.position.y }
        val maxY = vertices.maxOf { it.position.y }

        assertEquals(size.x, maxX - minX, 0.001f)
        assertEquals(size.y, maxY - minY, 0.001f)
    }

    @Test
    fun `vertices shift with center`() {
        val center = Float3(5f, 10f, 15f)
        val vertices = Plane.getVertices(Float3(2f, 2f, 0f), center, Float3(0f, 1f, 0f))

        val avgX = vertices.map { it.position.x }.average().toFloat()
        val avgY = vertices.map { it.position.y }.average().toFloat()

        assertEquals(center.x, avgX, 0.001f)
        assertEquals(center.y, avgY, 0.001f)
    }

    @Test
    fun `all vertices have UV coordinates`() {
        val vertices = Plane.getVertices(
            Plane.DEFAULT_SIZE,
            Plane.DEFAULT_CENTER,
            Plane.DEFAULT_NORMAL
        )
        for (v in vertices) {
            assertTrue("Vertex missing UV", v.uvCoordinate != null)
        }
    }

    @Test
    fun `UV coordinates scale with uvScale parameter`() {
        val uvScale = Float2(2f, 3f)
        val vertices = Plane.getVertices(
            Plane.DEFAULT_SIZE,
            Plane.DEFAULT_CENTER,
            Plane.DEFAULT_NORMAL,
            uvScale
        )

        // At least one UV coordinate should exceed 1.0 because of the scale
        val maxU = vertices.maxOf { it.uvCoordinate!!.x }
        val maxV = vertices.maxOf { it.uvCoordinate!!.y }

        assertTrue("Max U ($maxU) should be scaled", maxU > 1f)
        assertTrue("Max V ($maxV) should be scaled", maxV > 1f)
    }

    @Test
    fun `index values are within vertex range`() {
        for (primitive in Plane.INDICES) {
            for (idx in primitive) {
                assertTrue("Index $idx out of range [0, 4)", idx in 0 until 4)
            }
        }
    }

    // region shadow-receiver quad orientation contract (#2581)
    //
    // plane_renderer_shadow.mat's vertex shader FORCES `pos.y = 0.005` (it assigns, not
    // adds). A shadow-receiver quad mounted with that material must therefore be built in the
    // XZ plane — Size(x, y = 0, z) — so its footprint varies in x AND z and survives the
    // forced-y flattening. An XY quad — Size(x, y), z = 0 — varies only in x and y, so forcing
    // y constant collapses all four corners onto a single line (zero area → no shadow), which
    // is exactly the SketchfabModelViewerScreen bug in #2581. These tests lock the geometry
    // convention the demo fix relies on.

    /** Emulates plane_renderer_shadow's `pos.y = 0.005f` (assignment, not addition). */
    private fun forceShaderY(vertices: List<Geometry.Vertex>, y: Float = 0.005f) =
        vertices.map { Float3(it.position.x, y, it.position.z) }

    /** Twice the area of the XZ-projected quad via the shoelace formula over its 4 corners. */
    private fun xzDoubleArea(corners: List<Float3>): Float {
        var sum = 0f
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            sum += a.x * b.z - b.x * a.z
        }
        return kotlin.math.abs(sum)
    }

    @Test
    fun `XZ shadow quad keeps a non-degenerate footprint under the forced-y shadow shader`() {
        // Size(x, y = 0, z) — the ShadowReceiverPlaneNode / correct convention.
        val vertices = Plane.getVertices(Float3(4f, 0f, 4f), Float3(0f), Float3(0f, 1f, 0f))
        val flattened = forceShaderY(vertices)

        // x and z still span the full 4 m footprint after y is forced constant.
        assertEquals(4f, flattened.maxOf { it.x } - flattened.minOf { it.x }, 0.001f)
        assertEquals(4f, flattened.maxOf { it.z } - flattened.minOf { it.z }, 0.001f)
        // Non-zero area → the quad still catches shadow. (16 m² footprint → 2*area = 32.)
        assertTrue(
            "XZ shadow quad collapsed under the forced-y shader",
            xzDoubleArea(flattened) > 1f
        )
    }

    @Test
    fun `XY shadow quad degenerates to a line under the forced-y shadow shader (the bug)`() {
        // Size(x, y), z = 0 — the buggy convention: PlaneNode(size = Size(x, y)).
        val vertices = Plane.getVertices(Float3(4f, 4f, 0f), Float3(0f), Float3(0f, 1f, 0f))
        val flattened = forceShaderY(vertices)

        // z never varied (z-extent 0) and y is now constant → only x varies → a line segment.
        assertEquals(0f, flattened.maxOf { it.z } - flattened.minOf { it.z }, 0.001f)
        assertEquals(
            "XY shadow quad should collapse to zero area under the forced-y shader",
            0f,
            xzDoubleArea(flattened),
            0.001f
        )
    }
    // endregion
}
