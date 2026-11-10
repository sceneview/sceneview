package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.model.ModelQualityGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelQualityGateTest {

    @Test
    fun `a floor grid is measured whole, at its own height`() {
        val floor = grid(cells = 10, size = 4f, y = -1f)
        val figures = MeshFigures.of(floor.first, floor.second)
        assertEquals(200, figures.triangles)
        assertEquals(16f, figures.area, 0.01f)
        assertEquals(16f, figures.floorArea, 0.01f)
        assertEquals(-1f, figures.floorY, MeshFigures.FLOOR_BAND_M)
    }

    @Test
    fun `a table top above the floor is not floor`() {
        val floor = grid(cells = 10, size = 4f, y = -1f)
        val table = grid(cells = 2, size = 1f, y = -0.25f)
        val (positions, indices) = merge(floor, table)
        val figures = MeshFigures.of(positions, indices)
        assertEquals(17f, figures.area, 0.01f)
        assertEquals(16f, figures.floorArea, 0.01f)
    }

    @Test
    fun `a model that keeps the room passes`() {
        val raw = MeshFigures(triangles = 370_000, vertices = 200_000, area = 60f, floorArea = 12f, floorY = -1f)
        val finished = MeshFigures(triangles = 50_000, vertices = 40_000, area = 55f, floorArea = 12.5f, floorY = -1f)
        assertEquals(Verdict.Passes, ModelQualityGate.judge(raw, finished))
    }

    @Test
    fun `the shredded model of 2026-11-10 is kept out`() {
        val raw = MeshFigures(triangles = 370_495, vertices = 201_152, area = 60f, floorArea = 12f, floorY = -1f)
        val finished = MeshFigures(triangles = 51_006, vertices = 71_828, area = 58f, floorArea = 11.5f, floorY = -1f)
        val verdict = ModelQualityGate.judge(raw, finished)
        assertEquals(Verdict.Shredded, verdict)
        assertFalse(verdict.passes)
    }

    @Test
    fun `a model that lost or grew the room is kept out`() {
        val raw = MeshFigures(triangles = 100_000, vertices = 50_000, area = 60f, floorArea = 12f, floorY = -1f)
        fun judge(area: Float, floor: Float) =
            ModelQualityGate.judge(raw, MeshFigures(20_000, 12_000, area, floor, -1f))
        assertEquals(Verdict.AreaLost, judge(area = 30f, floor = 12f))
        assertEquals(Verdict.AreaGrown, judge(area = 90f, floor = 12f))
        assertEquals(Verdict.FloorLost, judge(area = 58f, floor = 6f))
        assertEquals(Verdict.FloorGrown, judge(area = 62f, floor = 20f))
        assertTrue(judge(area = 58f, floor = 12f).passes)
        assertEquals(Verdict.Empty, ModelQualityGate.judge(raw, MeshFigures(0, 0, 0f, 0f, -1f)))
    }

    /** A flat [cells] × [cells] grid [size] metres wide at height [y], facing up, shared vertices. */
    private fun grid(cells: Int, size: Float, y: Float): Pair<FloatArray, IntArray> {
        val side = cells + 1
        val positions = FloatArray(side * side * 3)
        for (i in 0 until side) for (k in 0 until side) {
            val v = (i * side + k) * 3
            positions[v] = i * size / cells
            positions[v + 1] = y
            positions[v + 2] = k * size / cells
        }
        val indices = ArrayList<Int>()
        for (i in 0 until cells) for (k in 0 until cells) {
            val a = i * side + k
            val b = a + 1
            val c = a + side
            val d = c + 1
            // Counter-clockwise seen from above: the normal points up (+y).
            indices += listOf(a, b, c, b, d, c)
        }
        return positions to indices.toIntArray()
    }

    private fun merge(a: Pair<FloatArray, IntArray>, b: Pair<FloatArray, IntArray>): Pair<FloatArray, IntArray> {
        val offset = a.first.size / 3
        return (a.first + b.first) to (a.second + b.second.map { it + offset })
    }
}
