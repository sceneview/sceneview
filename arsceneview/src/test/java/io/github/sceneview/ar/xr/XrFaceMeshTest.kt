package io.github.sceneview.ar.xr

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [XrFaceMesh] / [XrFaceMeshData] — the runtime-free
 * face-mesh layout, validation and pose math behind [XrFaceNode].
 *
 * No Robolectric, no Filament, no `androidx.xr.arcore`: this file only touches
 * SceneView's own [Position] type and plain primitive arrays, which is exactly
 * the point of keeping the mesh math in its own file. These are the JVM tests
 * the #1903 acceptance criteria asks for ("JVM unit tests for the face-mesh
 * adapter — vertex / normal layout, pose conversion").
 */
class XrFaceMeshTest {

    private val tolerance = 1e-5f

    /** A small, valid 2-triangle quad mesh used across several tests. */
    private fun quadMesh(withNormals: Boolean = true): XrFaceMeshData {
        val vertices = floatArrayOf(
            0f, 0f, 0f, // 0
            1f, 0f, 0f, // 1
            1f, 1f, 0f, // 2
            0f, 1f, 0f, // 3
        )
        val normals = if (withNormals) {
            floatArrayOf(
                0f, 0f, 1f,
                0f, 0f, 1f,
                0f, 0f, 1f,
                0f, 0f, 1f,
            )
        } else {
            null
        }
        val indices = intArrayOf(0, 1, 2, 0, 2, 3)
        return XrFaceMeshData(vertices = vertices, normals = normals, indices = indices)
    }

    @Test
    fun `regionCount matches the XrFaceRegion enum size`() {
        assertEquals(XrFaceRegion.entries.size, XrFaceMesh.regionCount)
    }

    @Test
    fun `CENTER region has no upstream name, the others do`() {
        assertNull(XrFaceRegion.CENTER.upstreamName)
        assertEquals("NOSE_TIP", XrFaceRegion.NOSE_TIP.upstreamName)
        assertEquals("FOREHEAD_LEFT", XrFaceRegion.FOREHEAD_LEFT.upstreamName)
        assertEquals("FOREHEAD_RIGHT", XrFaceRegion.FOREHEAD_RIGHT.upstreamName)
    }

    @Test
    fun `vertexCount and triangleCount derive from buffer sizes`() {
        val mesh = quadMesh()
        assertEquals(4, mesh.vertexCount)
        assertEquals(2, mesh.triangleCount)
    }

    @Test
    fun `vertexCount of an empty mesh is zero`() {
        val empty = XrFaceMeshData(FloatArray(0), null, IntArray(0))
        assertEquals(0, empty.vertexCount)
        assertEquals(0, empty.triangleCount)
    }

    @Test
    fun `a well-formed mesh is valid`() {
        assertTrue(XrFaceMesh.isValid(quadMesh()))
        assertTrue(XrFaceMesh.isValid(quadMesh(withNormals = false)))
    }

    @Test
    fun `an empty mesh is valid`() {
        assertTrue(XrFaceMesh.isValid(XrFaceMeshData(FloatArray(0), null, IntArray(0))))
    }

    @Test
    fun `a vertex buffer not a multiple of the stride is invalid`() {
        val mesh = XrFaceMeshData(floatArrayOf(0f, 0f, 0f, 1f), null, IntArray(0))
        assertFalse(XrFaceMesh.isValid(mesh))
    }

    @Test
    fun `a normal buffer of a different length than vertices is invalid`() {
        val mesh = XrFaceMeshData(
            vertices = floatArrayOf(0f, 0f, 0f),
            normals = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
            indices = IntArray(0),
        )
        assertFalse(XrFaceMesh.isValid(mesh))
    }

    @Test
    fun `an index buffer not a multiple of three is invalid`() {
        val mesh = XrFaceMeshData(
            vertices = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f),
            normals = null,
            indices = intArrayOf(0, 1),
        )
        assertFalse(XrFaceMesh.isValid(mesh))
    }

    @Test
    fun `an out-of-range index is invalid`() {
        val mesh = XrFaceMeshData(
            vertices = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f),
            normals = null,
            indices = intArrayOf(0, 1, 9), // 9 >= vertexCount 3
        )
        assertFalse(XrFaceMesh.isValid(mesh))
    }

    @Test
    fun `a negative index is invalid`() {
        val mesh = XrFaceMeshData(
            vertices = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f),
            normals = null,
            indices = intArrayOf(0, 1, -1),
        )
        assertFalse(XrFaceMesh.isValid(mesh))
    }

    @Test
    fun `vertexAt reads the requested vertex`() {
        val mesh = quadMesh()
        val v2 = XrFaceMesh.vertexAt(mesh, 2)
        assertNotNull(v2)
        assertEquals(1f, v2!!.x, tolerance)
        assertEquals(1f, v2.y, tolerance)
        assertEquals(0f, v2.z, tolerance)
    }

    @Test
    fun `vertexAt returns null for an out-of-range index`() {
        val mesh = quadMesh()
        assertNull(XrFaceMesh.vertexAt(mesh, -1))
        assertNull(XrFaceMesh.vertexAt(mesh, 4))
    }

    @Test
    fun `centroid is the average of every vertex`() {
        val mesh = quadMesh()
        val c = XrFaceMesh.centroid(mesh)
        assertNotNull(c)
        assertEquals(0.5f, c!!.x, tolerance)
        assertEquals(0.5f, c.y, tolerance)
        assertEquals(0f, c.z, tolerance)
    }

    @Test
    fun `centroid of an empty mesh is null`() {
        assertNull(XrFaceMesh.centroid(XrFaceMeshData(FloatArray(0), null, IntArray(0))))
    }

    @Test
    fun `extent is the per-axis bounding box span`() {
        val mesh = quadMesh()
        val e = XrFaceMesh.extent(mesh)
        assertEquals(1f, e.x, tolerance)
        assertEquals(1f, e.y, tolerance)
        assertEquals(0f, e.z, tolerance)
    }

    @Test
    fun `extent of an empty mesh is zero`() {
        val e = XrFaceMesh.extent(XrFaceMeshData(FloatArray(0), null, IntArray(0)))
        assertEquals(0f, e.x, tolerance)
        assertEquals(0f, e.y, tolerance)
        assertEquals(0f, e.z, tolerance)
    }

    @Test
    fun `regionDistance is the euclidean distance`() {
        val a = Position(0f, 0f, 0f)
        val b = Position(3f, 4f, 0f)
        assertEquals(5f, XrFaceMesh.regionDistance(a, b), tolerance)
    }

    @Test
    fun `regionDistance returns zero when an endpoint is untracked`() {
        val p = Position(1f, 2f, 3f)
        assertEquals(0f, XrFaceMesh.regionDistance(null, p), tolerance)
        assertEquals(0f, XrFaceMesh.regionDistance(p, null), tolerance)
        assertEquals(0f, XrFaceMesh.regionDistance(null, null), tolerance)
    }

    @Test
    fun `lerp at zero returns the start position`() {
        val a = Position(1f, 2f, 3f)
        val b = Position(9f, 9f, 9f)
        val r = XrFaceMesh.lerp(a, b, 0f)!!
        assertEquals(a.x, r.x, tolerance)
        assertEquals(a.y, r.y, tolerance)
        assertEquals(a.z, r.z, tolerance)
    }

    @Test
    fun `lerp at one returns the end position`() {
        val a = Position(1f, 2f, 3f)
        val b = Position(9f, 9f, 9f)
        val r = XrFaceMesh.lerp(a, b, 1f)!!
        assertEquals(b.x, r.x, tolerance)
        assertEquals(b.y, r.y, tolerance)
        assertEquals(b.z, r.z, tolerance)
    }

    @Test
    fun `lerp at half returns the midpoint`() {
        val a = Position(0f, 0f, 0f)
        val b = Position(4f, 8f, 12f)
        val r = XrFaceMesh.lerp(a, b, 0.5f)!!
        assertEquals(2f, r.x, tolerance)
        assertEquals(4f, r.y, tolerance)
        assertEquals(6f, r.z, tolerance)
    }

    @Test
    fun `lerp clamps t outside the unit interval`() {
        val a = Position(0f, 0f, 0f)
        val b = Position(10f, 0f, 0f)
        assertEquals(0f, XrFaceMesh.lerp(a, b, -2f)!!.x, tolerance)
        assertEquals(10f, XrFaceMesh.lerp(a, b, 5f)!!.x, tolerance)
    }

    @Test
    fun `lerp returns null when an endpoint is untracked`() {
        assertNull(XrFaceMesh.lerp(null, Position(1f, 1f, 1f), 0.5f))
        assertNull(XrFaceMesh.lerp(Position(1f, 1f, 1f), null, 0.5f))
    }

    @Test
    fun `trackedRegionCount counts non-null slots`() {
        val regions = arrayOfNulls<Position>(XrFaceMesh.regionCount)
        assertEquals(0, XrFaceMesh.trackedRegionCount(regions))

        regions[XrFaceRegion.CENTER.ordinal] = Position(0f, 0f, 0f)
        regions[XrFaceRegion.NOSE_TIP.ordinal] = Position(0f, 0f, 0.05f)
        assertEquals(2, XrFaceMesh.trackedRegionCount(regions))

        for (i in regions.indices) regions[i] = Position(0f, 0f, 0f)
        assertEquals(XrFaceMesh.regionCount, XrFaceMesh.trackedRegionCount(regions))
    }

    @Test
    fun `XrFaceMeshData equality is structural across arrays`() {
        assertEquals(quadMesh(), quadMesh())
        assertEquals(quadMesh().hashCode(), quadMesh().hashCode())
        assertFalse(quadMesh() == quadMesh(withNormals = false))
    }
}
