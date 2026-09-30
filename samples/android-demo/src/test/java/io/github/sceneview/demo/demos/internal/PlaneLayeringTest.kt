package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pins where the replay draws each plane: the shimmer of overlapping plane photos was a z-fight,
 * two depth-writing surfaces at one depth, so no two planes may share one.
 */
class PlaneLayeringTest {

    private val floorY = -1.3f

    /** A horizontal square at height [y], [size] metres wide, corner at ([x], [z]). */
    private fun square(id: Int, y: Float, x: Float = 0f, z: Float = 0f, size: Float = 2f, kind: DebugPlaneKind = DebugPlaneKind.Floor) =
        DebugPlane(id, kind, floatArrayOf(x, y, z, x + size, y, z, x + size, y, z + size, x, y, z + size))

    /** A wall in the plane x = [x], from the floor up 2.4 m. */
    private fun wall(id: Int, x: Float) = DebugPlane(
        id, DebugPlaneKind.Wall,
        floatArrayOf(x, floorY, 0f, x, floorY, 2f, x, floorY + 2.4f, 2f, x, floorY + 2.4f, 0f),
    )

    private fun ys(polygon: FloatArray) = (0 until polygon.size / 3).map { polygon[it * 3 + 1] }.toSet()

    @Test
    fun `two overlapping floor patches are laid flat under the grid, a step apart`() {
        // ARCore's two patches of one floor, a millimetre apart: the photos z-fought.
        val a = square(3, floorY + 0.001f)
        val b = square(8, floorY - 0.0005f, x = 1f)
        val layering = PlaneLayering(floorY, Vec3(1f, 0f, 1f), listOf(a.id, b.id))
        val ya = ys(layering.fill(a)).single()
        val yb = ys(layering.fill(b)).single()
        assertTrue("under the grid", ya < floorY && yb < floorY)
        assertEquals(PlaneLayering.STEP_M, abs(ya - yb), 1e-6f)
    }

    @Test
    fun `a table top keeps its own height instead of landing on the floor's photo`() {
        val floor = square(1, floorY)
        val table = square(2, floorY + 0.45f, x = 0.5f, z = 0.5f, size = 0.8f)
        val layering = PlaneLayering(floorY, Vec3(1f, 0f, 1f), listOf(1, 2))
        assertTrue(PlaneLayering.isGround(floor, floorY))
        assertFalse(PlaneLayering.isGround(table, floorY))
        val y = ys(layering.fill(table)).single()
        assertEquals(floorY + 0.45f, y, 0.01f)
        assertNotEquals(ys(layering.fill(floor)).single(), y, 0.1f)
    }

    @Test
    fun `a ceiling is never laid on the floor`() {
        val ceiling = square(4, floorY + 0.03f, kind = DebugPlaneKind.Ceiling)
        assertFalse(PlaneLayering.isGround(ceiling, floorY))
    }

    @Test
    fun `two walls in one plane step towards the room, by rank`() {
        val a = wall(5, x = 2f)
        val b = wall(9, x = 2f)
        // The camera walked at x = 0.5: the room is on the -x side of both walls.
        val layering = PlaneLayering(floorY, Vec3(0.5f, 0f, 1f), listOf(a.id, b.id))
        val xa = layering.fill(a).filterIndexed { i, _ -> i % 3 == 0 }.toSet().single()
        val xb = layering.fill(b).filterIndexed { i, _ -> i % 3 == 0 }.toSet().single()
        assertEquals(2f, xa, 1e-6f) // rank 0 stays put
        assertEquals(2f - PlaneLayering.STEP_M, xb, 1e-6f) // rank 1 comes a step into the room
    }

    @Test
    fun `a wall wound the other way still steps towards the room`() {
        val wall = wall(9, x = 2f)
        val n = wall.vertexCount
        val reversed = DebugPlane(9, wall.kind, FloatArray(wall.polygon.size) { i -> wall.polygon[(n - 1 - i / 3) * 3 + i % 3] })
        val layering = PlaneLayering(floorY, Vec3(0.5f, 0f, 1f), listOf(5, 9))
        val x = layering.fill(reversed).filterIndexed { i, _ -> i % 3 == 0 }.toSet().single()
        assertEquals(2f - PlaneLayering.STEP_M, x, 1e-6f)
    }

    @Test
    fun `an outline sits in front of its own fill`() {
        val floor = square(1, floorY)
        val wall = wall(2, x = 2f)
        val layering = PlaneLayering(floorY, Vec3(0.5f, 0f, 1f), listOf(1, 2))
        assertTrue(ys(layering.outline(floor)).single() > ys(layering.fill(floor)).single())
        assertTrue(layering.outline(wall)[0] < layering.fill(wall)[0])
    }

    @Test
    fun `a plane keeps its rank as others come and go`() {
        val early = PlaneLayering(floorY, null, listOf(2, 7))
        val later = PlaneLayering(floorY, null, listOf(2, 7, 11))
        assertEquals(early.rankOf(7), later.rankOf(7))
        assertEquals(PlaneLayering.RANKS - 1, PlaneLayering(floorY, null, (1..PlaneLayering.RANKS).toList()).rankOf(PlaneLayering.RANKS))
        assertEquals(0, PlaneLayering(floorY, null, (1..PlaneLayering.RANKS + 1).toList()).rankOf(PlaneLayering.RANKS + 1))
    }

    @Test
    fun `the layering leaves the recorded polygon untouched`() {
        val floor = square(1, floorY + 0.01f)
        val before = floor.polygon.copyOf()
        PlaneLayering(floorY, null, listOf(1)).fill(floor)
        assertTrue(before.contentEquals(floor.polygon))
    }

    @Test
    fun `layering of a frame walks towards its path`() {
        val frame = ArDebugFrame(
            time = 0f,
            trail = floatArrayOf(0f, 0f, 1f, 1f, 0f, 1f),
            camera = null,
            mapPoints = FloatArray(0),
            livePoints = FloatArray(0),
            planes = listOf(wall(5, x = 2f), wall(9, x = 2f)),
            anchors = emptyList(),
        )
        val x = PlaneLayering.of(frame, floorY).fill(frame.planes[1])[0]
        assertTrue("towards x = 0.5", x < 2f)
    }
}
