package io.github.sceneview.geometries

import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class TubeCapacityTest {
    private val points = List(8) { Position(it.toFloat(), 0f, 0f) }

    @Test
    fun `legacy overloads equal new flat and none overloads`() {
        listOf(false, true).forEach { closed ->
            listOf(false, true).forEach { caps ->
                @Suppress("DEPRECATION")
                val oldVertices = Tube.getVertices(points, 0.2f, 7, closed, caps)
                val style = if (caps) Tube.CapStyle.Flat else Tube.CapStyle.None
                assertEquals(
                    oldVertices,
                    Tube.getVertices(points, 0.2f, 7, closed, style, null, 0),
                )
                @Suppress("DEPRECATION")
                val oldIndices = Tube.getIndices(points.size, 7, closed, caps)
                assertEquals(oldIndices, Tube.getIndices(points.size, 7, closed, style, 0))
            }
        }
    }

    @Test
    fun `uniform per-point radii equal scalar radius`() {
        val scalar = Tube.getVertices(points, 0.2f, 8, false, Tube.CapStyle.Flat)
        val perPoint = Tube.getVertices(
            points, 0.2f, 8, false, Tube.CapStyle.Flat, List(points.size) { 0.2f }
        )
        assertEquals(scalar, perPoint)
    }

    @Test
    fun `per-point radii set ring distance and cone normal slope`() {
        val radii = List(points.size) { 0.1f + it * 0.1f }
        val segments = 8
        val vertices = Tube.getVertices(
            points, radialSegments = segments, capStyle = Tube.CapStyle.None, radii = radii
        )
        val tangent = Position(x = 1f)
        points.indices.forEach { i ->
            for (j in 0..segments) {
                val vertex = vertices[i * (segments + 1) + j]
                val normal = requireNotNull(vertex.normal)
                assertEquals(radii[i], length(vertex.position - points[i]), 1e-4f)
                assertEquals(1f, length(normal), 1e-4f)
                assertEquals(-0.1f / sqrt(1.01f), dot(normal, tangent), 1e-4f)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `radii count must match points`() {
        Tube.getVertices(points, radii = listOf(0.1f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `radii must be non-negative`() {
        Tube.getVertices(points, radii = List(points.size) { if (it == 2) -0.1f else 0.1f })
    }

    @Test
    fun `round caps use endpoint radius and sphere normals`() {
        val segments = 12
        val radii = List(points.size) { 0.1f + it * 0.02f }
        val vertices = Tube.getVertices(
            points, radialSegments = segments, capStyle = Tube.CapStyle.Round, radii = radii
        )
        assertEquals(4, Tube.roundCapRings(segments))
        val bodyCount = points.size * (segments + 1)
        val capSize = (Tube.roundCapRings(segments) + 1) * (segments + 1)
        listOf(0 to points.first(), capSize to points.last()).forEach { (offset, center) ->
            vertices.subList(bodyCount + offset, bodyCount + offset + capSize).forEach { vertex ->
                val expectedRadius = if (offset == 0) radii.first() else radii.last()
                val normal = requireNotNull(vertex.normal)
                assertEquals(expectedRadius, length(vertex.position - center), 1e-4f)
                assertEquals(1f, length(normal), 1e-4f)
                assertTrue(dot(normal, vertex.position - center) > 0f)
            }
        }
        assertEquals(points.first().x - radii.first(), vertices[bodyCount + capSize - 1].position.x, 1e-4f)
        assertEquals(points.last().x + radii.last(), vertices.last().position.x, 1e-4f)
    }

    @Test
    fun `round cap equators match tapered body end rings`() {
        val segments = 12
        val taperedPoints = points.take(5)
        val radii = listOf(0.35f, 0.24f, 0.2f, 0.16f, 0.08f)
        val vertices = Tube.getVertices(
            taperedPoints,
            radialSegments = segments,
            capStyle = Tube.CapStyle.Round,
            radii = radii,
        )
        val stride = segments + 1
        val bodyCount = taperedPoints.size * stride
        val capSize = (Tube.roundCapRings(segments) + 1) * stride
        listOf(0 to 0, taperedPoints.lastIndex to capSize).forEach { (bodyRing, capOffset) ->
            for (j in 0..segments) {
                val body = vertices[bodyRing * stride + j]
                val cap = vertices[bodyCount + capOffset + j]
                assertVectorEquals(body.position, cap.position)
                assertVectorEquals(requireNotNull(body.normal), requireNotNull(cap.normal))
            }
        }
    }

    @Test
    fun `coincident points with round caps stay finite on a sphere`() {
        val point = Position(2f, 3f, 4f)
        Tube.getVertices(
            listOf(point, point), radius = 0.3f, radialSegments = 12,
            capStyle = Tube.CapStyle.Round,
        ).forEachIndexed { index, vertex ->
            val normal = requireNotNull(vertex.normal)
            val uv = requireNotNull(vertex.uvCoordinate)
            assertTrue(
                "vertex $index has non-finite position ${vertex.position}",
                vertex.position.x.isFinite() && vertex.position.y.isFinite() && vertex.position.z.isFinite(),
            )
            assertTrue(normal.x.isFinite() && normal.y.isFinite() && normal.z.isFinite())
            assertTrue(uv.x.isFinite() && uv.y.isFinite())
            assertEquals(0.3f, length(vertex.position - point), 1e-4f)
        }
    }

    @Test
    fun `capacity keeps indices and buffer layout stable`() {
        val capacity = 8
        val segmentCount = 9
        val baselineVertices = Tube.getVertices(
            points.take(2), radialSegments = segmentCount, capStyle = Tube.CapStyle.Round,
            capacity = capacity,
        )
        val baselineIndices = Tube.getIndices(
            2, segmentCount, capStyle = Tube.CapStyle.Round, capacity = capacity
        )
        val baselineLayout = GeometryBufferLayout.of(baselineVertices, baselineIndices)
        for (count in 2..points.size) {
            val vertices = Tube.getVertices(
                points.take(count), radialSegments = segmentCount,
                capStyle = Tube.CapStyle.Round, capacity = capacity,
            )
            val indices = Tube.getIndices(
                count, segmentCount, capStyle = Tube.CapStyle.Round, capacity = capacity
            )
            assertEquals(baselineVertices.size, vertices.size)
            assertEquals(baselineIndices, indices)
            assertEquals(baselineLayout, GeometryBufferLayout.of(vertices, indices))
            assertFalse(requiresBufferRebuild(baselineVertices, baselineIndices, vertices, indices))
            val liveCount = Tube.liveIndexCount(count, segmentCount, false, Tube.CapStyle.Round)
            assertTrue(liveCount <= indices.single().size)
            indices.single().take(liveCount).forEach { assertTrue(it in vertices.indices) }
            val stride = segmentCount + 1
            for (ring in count until capacity) {
                vertices.subList(ring * stride, (ring + 1) * stride).forEach {
                    assertEquals(points[count - 1], it.position)
                }
            }
        }
    }

    @Test
    fun `padding does not enlarge the live bounding box`() {
        val count = 4
        val capacity = 8
        val segments = 9
        val vertices = Tube.getVertices(
            points.take(count), radialSegments = segments,
            capStyle = Tube.CapStyle.Round, capacity = capacity,
        )
        val stride = segments + 1
        val capBase = capacity * stride
        val live = vertices.take(count * stride) + vertices.drop(capBase)
        fun bounds(source: List<Geometry.Vertex>) = listOf(
            source.minOf { it.position.x }, source.maxOf { it.position.x },
            source.minOf { it.position.y }, source.maxOf { it.position.y },
            source.minOf { it.position.z }, source.maxOf { it.position.z },
        )
        assertEquals(bounds(live), bounds(vertices))
    }

    @Test
    fun `capacity growth doubles only explicit allocations`() {
        assertEquals(9, Tube.grownCapacity(9, 6, 6, explicit = false))
        assertEquals(12, Tube.grownCapacity(9, 6, 6, explicit = true))
        assertEquals(15, Tube.grownCapacity(9, 6, 15, explicit = true))
        assertEquals(8, Tube.grownCapacity(4, 8, 4, explicit = true))
    }

    @Test
    fun `zero capacity request keeps exact allocation mode`() {
        val currentCapacity = 4
        val largerPointCount = 5
        val largerExplicit = Tube.isExplicitCapacityRequest(largerPointCount, 0)
        assertFalse(largerExplicit)
        assertEquals(
            largerPointCount,
            Tube.grownCapacity(largerPointCount, currentCapacity, 0, largerExplicit),
        )

        val sameExplicit = Tube.isExplicitCapacityRequest(currentCapacity, 0)
        assertFalse(sameExplicit)
        assertEquals(
            currentCapacity,
            Tube.grownCapacity(currentCapacity, currentCapacity, 0, sameExplicit),
        )
    }

    @Test
    fun `explicit allocation stays explicit after zero capacity request`() {
        val currentCapacity = 8
        val pointCount = 9
        val remainsExplicit = Tube.isCapacityExplicit(true, pointCount, 0)
        assertTrue(remainsExplicit)
        assertEquals(
            16,
            Tube.grownCapacity(pointCount, currentCapacity, 0, remainsExplicit),
        )
    }

    @Test
    fun `closed capacity repeats the seam and draws exactly live segments`() {
        val loop = listOf(
            Position(1f, 0f, 0f), Position(0f, 1f, 0f),
            Position(-1f, 0f, 0f), Position(0f, -1f, 0f),
        )
        val segments = 6
        val vertices = Tube.getVertices(
            loop, radialSegments = segments, closed = true,
            capStyle = Tube.CapStyle.None, capacity = 8,
        )
        val stride = segments + 1
        assertEquals(
            vertices.subList(0, stride).map { it.position },
            vertices.subList(loop.size * stride, (loop.size + 1) * stride).map { it.position },
        )
        assertEquals(
            loop.size * segments * 6,
            Tube.liveIndexCount(loop.size, segments, true, Tube.CapStyle.None),
        )
    }

    @Test
    fun `all live round-cap triangles wind outward`() {
        val segments = 8
        val live = points.take(4)
        val vertices = Tube.getVertices(
            live, radialSegments = segments, capStyle = Tube.CapStyle.Round, capacity = 7
        )
        val indices = Tube.getIndices(
            live.size, segments, capStyle = Tube.CapStyle.Round, capacity = 7
        ).single().take(Tube.liveIndexCount(live.size, segments, false, Tube.CapStyle.Round))
        var checked = 0
        for (i in indices.indices step 3) {
            val a = vertices[indices[i]]
            val b = vertices[indices[i + 1]]
            val c = vertices[indices[i + 2]]
            val geometric = cross(b.position - a.position, c.position - a.position)
            if (length(geometric) < 1e-8f) continue
            val normal = normalize(a.normal!! + b.normal!! + c.normal!!)
            assertTrue("triangle $i winds inward", dot(normalize(geometric), normal) > 0f)
            checked++
        }
        assertTrue(checked > 0)
    }


    private fun assertVectorEquals(expected: Position, actual: Position) {
        assertEquals(expected.x, actual.x, 1e-5f)
        assertEquals(expected.y, actual.y, 1e-5f)
        assertEquals(expected.z, actual.z, 1e-5f)
    }
}
