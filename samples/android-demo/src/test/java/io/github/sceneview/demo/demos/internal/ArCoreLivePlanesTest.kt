package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** #4732: ARCore's plane polygons, local to `centerPose`, handed to the live walls in world space. */
class ArCoreLivePlanesTest {

    /** A pose turned [angle] about +Y then moved by (tx, ty, tz), as `Pose.transformPoint` applies it. */
    private fun pose(angle: Float, tx: Float, ty: Float, tz: Float): (FloatArray) -> FloatArray = { p ->
        val c = cos(angle)
        val s = sin(angle)
        floatArrayOf(c * p[0] + s * p[2] + tx, p[1] + ty, -s * p[0] + c * p[2] + tz)
    }

    @Test
    fun `local polygon goes through the pose, not read as world`() {
        // A 2 m x 1 m rectangle around the plane's centre, local x/z pairs.
        val local = floatArrayOf(-1f, -0.5f, 1f, -0.5f, 1f, 0.5f, -1f, 0.5f)
        val world = livePlanePolygon(local, pose(angle = (Math.PI / 2).toFloat(), tx = 3f, ty = -1.4f, tz = -2f))

        // Turned a quarter about +Y: local x runs along world -z, local z along world +x.
        val expected = floatArrayOf(
            2.5f, -1.4f, -1f,
            2.5f, -1.4f, -3f,
            3.5f, -1.4f, -3f,
            3.5f, -1.4f, -1f,
        )
        assertArrayEquals(expected, world, 1e-5f)
    }

    @Test
    fun `local y is zero on the plane`() {
        val seen = mutableListOf<Float>()
        livePlanePolygon(floatArrayOf(0.2f, 0.3f, -0.2f, 0.1f)) { p ->
            seen += p[1]
            p.copyOf()
        }
        assertEquals(listOf(0f, 0f), seen)
    }

    @Test
    fun `odd trailing value is ignored`() {
        val world = livePlanePolygon(floatArrayOf(1f, 2f, 3f)) { it.copyOf() }
        assertArrayEquals(floatArrayOf(1f, 0f, 2f), world, 0f)
    }

    @Test
    fun `floor is the lowest floor plane, walls and ceilings ignored`() {
        val table = LivePlane(1, LivePlane.Kind.Floor, floatArrayOf(0f, -0.7f, 0f, 1f, -0.7f, 0f, 1f, -0.7f, 1f))
        val floor = LivePlane(2, LivePlane.Kind.Floor, floatArrayOf(0f, -1.4f, 0f, 2f, -1.4f, 0f, 2f, -1.4f, 2f))
        val wall = LivePlane(3, LivePlane.Kind.Wall, floatArrayOf(0f, -3f, 0f, 1f, -3f, 0f, 1f, 0f, 0f))
        val ceiling = LivePlane(4, LivePlane.Kind.Ceiling, floatArrayOf(0f, -5f, 0f, 1f, -5f, 0f, 1f, -5f, 1f))
        assertEquals(-1.4f, livePlanesFloorY(listOf(table, wall, floor, ceiling))!!, 1e-5f)
    }

    @Test
    fun `a table just under the camera is never the floor`() {
        // Phone held at 0 m: the table 0.7 m under it is furniture, the floor 1.4 m under it is not.
        val table = LivePlane(1, LivePlane.Kind.Floor, floatArrayOf(0f, -0.7f, 0f, 1f, -0.7f, 0f, 1f, -0.7f, 1f))
        val floor = LivePlane(2, LivePlane.Kind.Floor, floatArrayOf(0f, -1.4f, 0f, 2f, -1.4f, 0f, 2f, -1.4f, 2f))
        // Only the table tracked so far: no floor, the walls wait (a lowest-plane pick would take it).
        assertNull(livePlanesFloorY(listOf(table), cameraY = 0f))
        // The table is the lowest plane here too: only the 0.8 m drop keeps it out.
        val lowTable = LivePlane(3, LivePlane.Kind.Floor, floatArrayOf(0f, -0.5f, 0f, 1f, -0.5f, 0f, 1f, -0.5f, 1f))
        assertNull(livePlanesFloorY(listOf(lowTable, table), cameraY = 0f))
        assertEquals(-1.4f, livePlanesFloorY(listOf(table, floor), cameraY = 0f)!!, 1e-5f)
    }

    @Test
    fun `no floor before one is tracked`() {
        val wall = LivePlane(3, LivePlane.Kind.Wall, floatArrayOf(0f, -1f, 0f, 1f, -1f, 0f, 1f, 0f, 0f))
        assertNull(livePlanesFloorY(listOf(wall)))
        assertNull(livePlanesFloorY(emptyList()))
    }
}
