package io.github.sceneview.geometries

import io.github.sceneview.math.Color
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryBufferRebuildTest {
    private val vertices = List(4) { Geometry.Vertex(position = Position(x = it.toFloat())) }
    private val indices = listOf(listOf(0, 1, 2), listOf(1, 2, 3))

    @Test
    fun `same counts and layout reuse even when positions and indices change`() {
        assertFalse(requiresBufferRebuild(
            vertices, indices,
            vertices.map { it.copy(position = it.position * 2f) },
            listOf(listOf(2, 1, 0), listOf(3, 2, 1))
        ))
    }

    @Test
    fun `more or fewer vertices rebuild`() {
        assertTrue(requiresBufferRebuild(vertices, indices, vertices + vertices.first(), indices))
        assertTrue(requiresBufferRebuild(vertices, indices, vertices.dropLast(1), indices))
    }

    @Test
    fun `more or fewer indices rebuild`() {
        assertTrue(requiresBufferRebuild(vertices, indices, vertices,
            listOf(indices.first() + 0, indices.last())))
        assertTrue(requiresBufferRebuild(vertices, indices, vertices,
            listOf(indices.first().dropLast(1), indices.last())))
    }

    @Test
    fun `index redistribution rebuilds even with the same total count`() {
        assertTrue(requiresBufferRebuild(vertices, indices, vertices,
            listOf(listOf(0, 1), listOf(0, 1, 2, 3))))
    }

    @Test
    fun `adding or removing a primitive rebuilds even with the same total count`() {
        assertTrue(requiresBufferRebuild(vertices, indices, vertices,
            listOf(listOf(0, 1), listOf(1, 2), listOf(2, 3))))
        assertTrue(requiresBufferRebuild(vertices, indices, vertices, listOf(indices.flatten())))
    }

    @Test
    fun `normals UVs and colours gained or lost rebuild`() {
        val layouts = listOf(
            vertices.map { it.copy(normal = Direction(y = 1f)) },
            vertices.map { it.copy(uvCoordinate = UvCoordinate(0f, 0f)) },
            vertices.map { it.copy(color = Color(1f, 0f, 0f, 1f)) }
        )
        layouts.forEach { layout ->
            assertTrue(requiresBufferRebuild(vertices, indices, layout, indices))
            assertTrue(requiresBufferRebuild(layout, indices, vertices, indices))
            assertFalse(requiresBufferRebuild(layout, indices, layout.toList(), indices))
        }
        // Equal slot counts do not imply equal layouts: UV must not overwrite tangents.
        assertTrue(requiresBufferRebuild(layouts[0], indices, layouts[1], indices))
    }

    @Test
    fun `two primitive offsets and counts follow growth and shrinkage`() {
        val grown = listOf(listOf(0, 1, 2, 2, 3, 0), listOf(1, 2, 3))
        assertTrue(requiresBufferRebuild(vertices, indices, vertices, grown))
        assertEquals(listOf(0 until 6, 6 until 9), grown.getOffsets())
        assertEquals(listOf(6, 3), grown.getOffsets().map { it.count() })
        val shrunk = listOf(listOf(0, 1), listOf(2, 3))
        assertTrue(requiresBufferRebuild(vertices, grown, vertices, shrunk))
        assertEquals(listOf(0 until 2, 2 until 4), shrunk.getOffsets())
        assertEquals(listOf(2, 2), shrunk.getOffsets().map { it.count() })
    }

    @Test
    fun `shape gains and loses colour without changing its counts`() {
        val polygon = listOf(UvCoordinate(0f, 0f), UvCoordinate(1f, 0f), UvCoordinate(0f, 1f))
        val plain = Shape.getVertices(polygon)
        val coloured = Shape.getVertices(polygon, color = Color(1f, 0f, 0f, 1f))
        val triangles = listOf(listOf(0, 1, 2))
        assertTrue(requiresBufferRebuild(plain, triangles, coloured, triangles))
        assertTrue(requiresBufferRebuild(coloured, triangles, plain, triangles))
    }
}
