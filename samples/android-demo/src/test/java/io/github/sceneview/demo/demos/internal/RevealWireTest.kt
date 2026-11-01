package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.ModelReveal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RevealWireTest {

    /** A unit cube, 8 corners, 12 triangles: 18 unique edges (12 sides, 6 face diagonals). */
    private val cubePositions = floatArrayOf(
        0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f,
        0f, 1f, 0f, 1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f, 1f,
    )
    private val cubeIndices = intArrayOf(
        0, 1, 2, 0, 2, 3, // bottom
        4, 6, 5, 4, 7, 6, // top
        0, 5, 1, 0, 4, 5, // front
        3, 2, 6, 3, 6, 7, // back
        0, 3, 7, 0, 7, 4, // left
        1, 5, 6, 1, 6, 2, // right
    )

    @Test
    fun `every edge is kept once, whichever triangle and winding it came from`() {
        val wire = RevealWire.of(cubePositions, 8, cubeIndices)!!
        assertEquals(18, wire.edgeCount)
        val keys = (0 until wire.edgeCount).map { e ->
            val a = wire.indices[e * 2]
            val b = wire.indices[e * 2 + 1]
            minOf(a, b) to maxOf(a, b)
        }
        assertEquals("no edge twice", keys.size, keys.toSet().size)
        assertEquals(0f, wire.minY, 0f)
        assertEquals(1f, wire.maxY, 0f)
    }

    @Test
    fun `edges are sorted from the floor up, so a sweep is a prefix`() {
        val wire = RevealWire.of(cubePositions, 8, cubeIndices)!!
        val lows = (0 until wire.edgeCount).map { e ->
            minOf(cubePositions[wire.indices[e * 2] * 3 + 1], cubePositions[wire.indices[e * 2 + 1] * 3 + 1])
        }
        assertEquals(lows.sorted(), lows)
        // The floor edges and the uprights start at y 0: 5 on the bottom face, 4 uprights, 4 side
        // diagonals; the 5 of the top face start at y 1.
        assertEquals(13 * 2, wire.indicesBelow(0f))
        assertEquals(13 * 2, wire.indicesBelow(0.5f))
        assertEquals(18 * 2, wire.indicesBelow(1f))
        assertEquals(18 * 2, wire.indicesBelow(2f))
    }

    @Test
    fun `degenerate triangles and empty meshes draw nothing`() {
        assertNull(RevealWire.of(FloatArray(0), 0, IntArray(0)))
        val flat = RevealWire.of(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f), 3, intArrayOf(0, 0, 1))!!
        assertEquals(1, flat.edgeCount)
    }

    @Test
    fun `past the cap a model keeps edges spread over its whole height`() {
        val wire = RevealWire.of(cubePositions, 8, cubeIndices, maxEdges = 6)!!
        assertEquals(6, wire.edgeCount)
        assertEquals(wire.edgeCount * 2, wire.indicesBelow(1f))
        assertTrue("the top is still reached", wire.indicesBelow(0f) < wire.indicesBelow(1f))
    }

    @Test
    fun `the line mesh holds the model's vertices and two indices an edge`() {
        val wire = RevealWire.of(cubePositions, 8, cubeIndices)!!
        val mesh = wire.toMesh()
        assertEquals(8, mesh.vertexCount)
        assertEquals(36, mesh.indexCount)
    }

    @Test
    fun `the reveal sweeps the wire, raises the model into it, then lets the wire go`() {
        assertEquals(0f, ModelReveal.wireSweep(0f), 0f)
        assertEquals(1f, ModelReveal.wireSweep(ModelReveal.WIRE_SWEEP_MS), 1e-6f)
        assertEquals(0f, ModelReveal.solidRise(ModelReveal.SOLID_START_MS), 0f)
        assertEquals(1f, ModelReveal.solidRise(ModelReveal.SOLID_START_MS + ModelReveal.SOLID_RISE_MS), 1e-6f)
        // The wire stands at least half way when the model starts rising into it.
        assertTrue(ModelReveal.wireSweep(ModelReveal.SOLID_START_MS) > 0.5f)
        assertEquals(ModelReveal.WIRE_ALPHA, ModelReveal.wireAlpha(ModelReveal.WIRE_FADE_START_MS), 0f)
        assertEquals(0f, ModelReveal.wireAlpha(ModelReveal.TOTAL_MS), 1e-6f)
        // Over by the time the camera's reveal lands (CameraPath.REVEAL_SECONDS, 2.8 s).
        assertTrue(ModelReveal.TOTAL_MS <= 2800f)
        // Monotonic, no jump back.
        var last = -1f
        for (t in 0..ModelReveal.TOTAL_MS.toInt() step 50) {
            val rise = ModelReveal.solidRise(t.toFloat())
            assertTrue(rise >= last)
            last = rise
        }
    }
}
