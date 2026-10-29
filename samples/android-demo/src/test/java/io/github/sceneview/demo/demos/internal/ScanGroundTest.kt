package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ground under a scan's result (#4719): the floor's height, and the floor it saw and missed. */
class ScanGroundTest {
    private val positions = ArrayList<Float>()
    private val normals = ArrayList<Float>()

    private fun vertex(x: Float, y: Float, z: Float, ny: Float) {
        positions += listOf(x, y, z)
        normals += listOf(0f, ny, 0f)
    }

    /**
     * A 4 × 4 m room at [floorY]: a floor sampled every 5 cm except a [hole] square in the
     * middle, and four 2.5 m walls round it.
     */
    private fun room(floorY: Float, hole: Float): ScanGround {
        val step = 0.05f
        val n = (4f / step).toInt()
        for (i in 0..n) for (j in 0..n) {
            val x = -2f + i * step
            val z = -2f + j * step
            if (kotlin.math.abs(x) < hole / 2 && kotlin.math.abs(z) < hole / 2) continue
            vertex(x, floorY, z, 1f)
        }
        for (i in 0..n) for (k in 0..(2.5f / step).toInt()) {
            val a = -2f + i * step
            val y = floorY + k * step
            vertex(a, y, -2f, 0f)
            vertex(a, y, 2f, 0f)
            vertex(-2f, y, a, 0f)
            vertex(2f, y, a, 0f)
        }
        return requireNotNull(ScanGround.of(positions.toFloatArray(), normals.toFloatArray())) { "no ground" }
    }

    @Test
    fun `the floor is found at its own height, not at the lowest stray point`() {
        vertex(0.5f, -0.6f, 0.5f, 0f)
        val ground = room(floorY = 0.3f, hole = 0f)
        assertEquals(0.3f, ground.floorY, 0.02f)
    }

    @Test
    fun `a whole floor is all seen`() {
        val ground = room(floorY = 0f, hole = 0f)
        assertEquals(0, ground.missing)
        assertEquals(1f, ground.seenShare, 0f)
    }

    @Test
    fun `a hole in the floor is missing, and only the hole`() {
        val ground = room(floorY = 0f, hole = 1f)
        // A 1 m hole in 10 cm cells: about 100 cells, the room's 16 m² about 1 600.
        assertTrue("missing ${ground.missing}", ground.missing in 64..121)
        assertTrue("seen ${ground.seen}", ground.seen in 1_500..1_900)
        val column = (-ground.originX / ground.cellM).toInt()
        val row = (-ground.originZ / ground.cellM).toInt()
        assertEquals(ScanGround.MISSING, ground.at(column, row))
    }

    @Test
    fun `nothing stands on nothing`() {
        assertNull(ScanGround.of(FloatArray(0), null))
        assertNull(ScanGround.of(floatArrayOf(Float.NaN, 0f, 0f), null))
    }
}
