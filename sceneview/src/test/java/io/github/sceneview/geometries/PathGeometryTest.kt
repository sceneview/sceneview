package io.github.sceneview.geometries

import dev.romainguy.kotlin.math.Float3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [Path] companion object geometry generation.
 *
 * [Path.getVertices] and [Path.getIndices] are pure functions with no Filament dependency,
 * so they are fully testable as JVM unit tests.
 *
 * [Path.Builder.build] and [Path.update] require a Filament [Engine] and are NOT tested here.
 */
class PathGeometryTest {

    @Test
    fun `default points has 2 entries`() {
        assertEquals(2, Path.DEFAULT_POINTS.size)
    }

    @Test
    fun `default points start at origin`() {
        assertEquals(Float3(0f, 0f, 0f), Path.DEFAULT_POINTS[0])
    }

    @Test
    fun `default points end at (1, 0, 0)`() {
        assertEquals(Float3(1f, 0f, 0f), Path.DEFAULT_POINTS[1])
    }

    @Test
    fun `getVertices count matches input points`() {
        val points = listOf(Float3(0f), Float3(1f), Float3(2f), Float3(3f))
        val vertices = Path.getVertices(points)
        assertEquals(4, vertices.size)
    }

    @Test
    fun `getVertices preserves position values`() {
        val points = listOf(Float3(1f, 2f, 3f), Float3(4f, 5f, 6f), Float3(7f, 8f, 9f))
        val vertices = Path.getVertices(points)
        for (i in points.indices) {
            assertEquals(points[i], vertices[i].position)
        }
    }

    @Test
    fun `getVertices for minimum 2 points`() {
        val points = listOf(Float3(0f), Float3(1f))
        val vertices = Path.getVertices(points)
        assertEquals(2, vertices.size)
    }

    @Test
    fun `getIndices open path with 2 points produces 1 segment`() {
        val indices = Path.getIndices(2, closed = false)
        assertEquals(1, indices.size)
        assertEquals(2, indices[0].size) // [0, 1]
    }

    @Test
    fun `getIndices open path with 3 points produces 2 segments`() {
        val indices = Path.getIndices(3, closed = false)
        val flat = indices[0]
        // (0,1), (1,2) → 4 entries
        assertEquals(4, flat.size)
    }

    @Test
    fun `getIndices open path with N points has (N-1)*2 indices`() {
        val n = 7
        val indices = Path.getIndices(n, closed = false)
        assertEquals((n - 1) * 2, indices[0].size)
    }

    @Test
    fun `getIndices closed path with 3 points has 3*2 indices`() {
        val indices = Path.getIndices(3, closed = true)
        // (0,1), (1,2), (2,0) → 6 entries
        assertEquals(6, indices[0].size)
    }

    @Test
    fun `getIndices closed path with N points has N*2 indices`() {
        val n = 5
        val indices = Path.getIndices(n, closed = true)
        assertEquals(n * 2, indices[0].size)
    }

    @Test
    fun `getIndices closed path last segment connects back to 0`() {
        val n = 4
        val indices = Path.getIndices(n, closed = true)
        val flat = indices[0]
        // Last two entries should be (n-1, 0)
        assertEquals(n - 1, flat[flat.size - 2])
        assertEquals(0, flat[flat.size - 1])
    }

    @Test
    fun `getIndices open path does NOT connect last to first`() {
        val n = 4
        val indices = Path.getIndices(n, closed = false)
        val flat = indices[0]
        // Last two entries should be (n-2, n-1)
        assertEquals(n - 2, flat[flat.size - 2])
        assertEquals(n - 1, flat[flat.size - 1])
    }

    @Test
    fun `getIndices returns single primitive list`() {
        val indices = Path.getIndices(4, closed = false)
        assertEquals(1, indices.size)
    }

    @Test
    fun `all index values are within vertex range for open path`() {
        val n = 6
        val indices = Path.getIndices(n, closed = false)
        for (primitive in indices) {
            for (idx in primitive) {
                assertTrue("Index $idx out of range [0, $n)", idx in 0 until n)
            }
        }
    }

    @Test
    fun `all index values are within vertex range for closed path`() {
        val n = 6
        val indices = Path.getIndices(n, closed = true)
        for (primitive in indices) {
            for (idx in primitive) {
                assertTrue("Index $idx out of range [0, $n)", idx in 0 until n)
            }
        }
    }

    @Test
    fun `vertices have null normals (path has no surface normal)`() {
        val points = listOf(Float3(0f), Float3(1f), Float3(2f))
        val vertices = Path.getVertices(points)
        for (v in vertices) {
            assertEquals("Path vertex should have no normal", null, v.normal)
        }
    }

    @Test
    fun `vertices have null UV coordinates`() {
        val points = listOf(Float3(0f), Float3(1f), Float3(2f))
        val vertices = Path.getVertices(points)
        for (v in vertices) {
            assertEquals("Path vertex should have no UV", null, v.uvCoordinate)
        }
    }

    @Test
    fun `consecutive segment pairs are contiguous in open path`() {
        val n = 5
        val indices = Path.getIndices(n, closed = false)
        val flat = indices[0]
        // (0,1), (1,2), (2,3), (3,4)
        for (i in 0 until n - 1) {
            assertEquals(i, flat[i * 2])
            assertEquals(i + 1, flat[i * 2 + 1])
        }
    }

    // ── Degenerate paths are refused before they reach Filament (#4344) ──────

    @Test
    fun `getVertices refuses an empty path`() {
        // Zero vertices would be a zero-sized VertexBuffer: a native abort, not an exception.
        val failure = assertThrows(IllegalArgumentException::class.java) {
            Path.getVertices(emptyList())
        }
        assertEquals("Path requires at least 2 points", failure.message)
    }

    @Test
    fun `getVertices refuses a single point`() {
        // One point is one vertex and no segment: an empty IndexBuffer.
        assertThrows(IllegalArgumentException::class.java) {
            Path.getVertices(listOf(Float3(0f)))
        }
    }

    @Test
    fun `update validates its points before it uploads anything`() {
        // Path.update cannot run on the JVM (it needs an Engine), so pin the order in the source:
        // the vertices — and with them the guard above — are computed as an argument, before the
        // base update touches a buffer and before this path's own state is overwritten.
        val body = java.io.File("src/main/java/io/github/sceneview/geometries/Path.kt").readText()
            .substringAfter("fun update(", missingDelimiterValue = "")
            .substringBefore("\n    }")
        assertTrue("Path.update must exist", body.isNotEmpty())
        val guard = body.indexOf("vertices = getVertices(points)")
        val stateWrite = body.indexOf("this.points = points")
        assertTrue("Path.update must derive its vertices through getVertices", guard >= 0)
        assertTrue("the guard must run before the path's state is overwritten", guard < stateWrite)
    }

    @Test
    fun `large path with 100 points produces correct index count`() {
        val n = 100
        val indices = Path.getIndices(n, closed = false)
        assertEquals((n - 1) * 2, indices[0].size)
    }
}
