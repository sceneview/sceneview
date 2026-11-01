package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.ModelReveal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `a model under the origin sweeps the same, the search keyed as the sort is`() {
        // The cube lowered to y -2..-1: heights above the floor, not absolute ones, are searched.
        val low = FloatArray(cubePositions.size) { i -> if (i % 3 == 1) cubePositions[i] - 2f else cubePositions[i] }
        val wire = RevealWire.of(low, 8, cubeIndices)!!
        assertEquals(-2f, wire.minY, 0f)
        assertEquals(13 * 2, wire.indicesBelow(0f))
        assertEquals(13 * 2, wire.indicesBelow(0.5f))
        assertEquals(18 * 2, wire.indicesBelow(1f))
    }

    @Test
    fun `a dense model is capped to a wire that reads as lines, spread over its height`() {
        // A 300 x 300 grid of quads standing up (x, y): 180k triangles, ~270k unique edges.
        val n = 300
        val positions = FloatArray((n + 1) * (n + 1) * 3)
        for (r in 0..n) for (c in 0..n) {
            val v = r * (n + 1) + c
            positions[v * 3] = c.toFloat() / n
            positions[v * 3 + 1] = r.toFloat() / n
        }
        val indices = IntArray(n * n * 6)
        var k = 0
        for (r in 0 until n) for (c in 0 until n) {
            val a = r * (n + 1) + c
            val b = a + 1
            val d = a + n + 1
            intArrayOf(a, b, d, b, d + 1, d).forEach { indices[k++] = it }
        }
        val wire = RevealWire.of(positions, (n + 1) * (n + 1), indices)!!
        assertTrue("capped: ${wire.edgeCount}", wire.edgeCount <= RevealWire.MAX_EDGES)
        assertTrue("not starved: ${wire.edgeCount}", wire.edgeCount > RevealWire.MAX_EDGES / 2)
        // Half the height sweeps about half the kept edges: the cap thins evenly, not from the top.
        val half = wire.indicesBelow(0.5f) / 2
        assertTrue("half way: $half of ${wire.edgeCount}", half in (wire.edgeCount * 4 / 10)..(wire.edgeCount * 6 / 10))
        assertEquals(wire.edgeCount * 2, wire.indicesBelow(1f))
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

    @Test
    fun `only a finished model the result opens on, revealed in motion, gets a wire`() {
        // The normal case: the finished model, the one the result opens on, in motion.
        assertTrue(ModelReveal.buildsWire(final = true, opensOn = true, animate = true))
        // A big scan's raw surface, or a retry after running out of memory: its finish needs the heap.
        assertFalse(ModelReveal.buildsWire(final = false, opensOn = true, animate = true))
        // The finished model that later takes a raw surface's place is not revealed again.
        assertFalse(ModelReveal.buildsWire(final = true, opensOn = false, animate = true))
        // Reduced motion: the wire would never be drawn, so it is never built.
        assertFalse(ModelReveal.buildsWire(final = true, opensOn = true, animate = false))
    }

    @Test
    fun `a build nobody waits for stops at its next check`() {
        var checks = 0
        val stopped = runCatching {
            RevealWire.of(cubePositions, 8, cubeIndices) {
                checks++
                throw IllegalStateException("cancelled")
            }
        }
        assertTrue(stopped.isFailure)
        assertEquals(1, checks)
        // Never asked to stop: the same wire as without a check.
        assertEquals(18, RevealWire.of(cubePositions, 8, cubeIndices) { }!!.edgeCount)
    }
}
