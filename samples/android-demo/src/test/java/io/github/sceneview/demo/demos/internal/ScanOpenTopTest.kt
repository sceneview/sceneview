package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The room's lid comes off at the height of its floor (#4742), and a live view's cloud, merged
 * far from the eye, reads that floor from the map it was taken from (#4792).
 */
class ScanOpenTopTest {

    /** A 2 × 2 m floor at 0 and a wall up to 2.5 m, a point every 10 cm. */
    private val room: FloatArray = buildList {
        for (i in 0 until SIDE) for (k in 0 until SIDE) addAll(listOf(i * STEP_M, 0f, k * STEP_M))
        for (i in 0 until SIDE) for (j in 1..WALL_ROWS) addAll(listOf(i * STEP_M, j * STEP_M, 0f))
    }.toFloatArray()

    /** The same room as a live view draws it from over a stairwell: the treads by the eye, the rest thinner. */
    private val drawn: DenseCloud = run {
        val stairs = buildList {
            for (i in 0 until SIDE) for (k in 0 until SIDE) addAll(listOf(i * STEP_M, STAIRS_Y_M, k * STEP_M))
        }
        val positions = (room.toList() + stairs).toFloatArray()
        DenseCloud(positions, IntArray(positions.size / 3) { GREY })
    }

    @Test
    fun `the lid comes off at the floor the map holds`() {
        val open = ScanOpenTop.cloud(drawn, floorFrom = room)
        val top = maxY(open.positions)
        assertTrue("cut at $top m", top <= RoomDollhouse.CUTAWAY_HEIGHT_M + 1e-4f)
        assertTrue("cut at $top m", top > RoomDollhouse.CUTAWAY_HEIGHT_M - STEP_M - 1e-4f)
        // The treads are under the room's floor: the cut drops them, as the saved scan's dollhouse does.
        assertTrue(open.positions.indices.filter { it % 3 == 1 }.none { open.positions[it] < -RoomDollhouse.UNDER_FLOOR_M })
    }

    @Test
    fun `without the map, the floor is the cloud's own`() {
        assertEquals(STAIRS_Y_M, ScanOpenTop.floorOf(drawn.positions)!!, 1e-6f)
        val top = maxY(ScanOpenTop.cloud(drawn).positions)
        assertTrue("cut at $top m", top <= STAIRS_Y_M + RoomDollhouse.CUTAWAY_HEIGHT_M + 1e-4f)
    }

    private fun maxY(points: FloatArray): Float = (1 until points.size step 3).maxOf { points[it] }

    private companion object {
        const val SIDE = 20
        const val STEP_M = 0.1f
        const val WALL_ROWS = 25
        const val STAIRS_Y_M = -0.6f
        const val GREY = 0xFF808080.toInt()
    }
}
