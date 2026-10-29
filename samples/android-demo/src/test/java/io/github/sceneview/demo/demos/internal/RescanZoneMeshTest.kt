package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The frames the result draws the parts to scan again with (#4736): twelve beams round each box. */
class RescanZoneMeshTest {
    private val floor = RescanZone(RescanKind.FloorHole, floatArrayOf(0f, 0f, 0f, 1f, 0.05f, 2f), 2f, "a hole")
    private val wall = RescanZone(RescanKind.MissingWall, floatArrayOf(1.85f, 0f, -1f, 2.15f, 2.4f, 1f), 2f, "a wall")

    @Test
    fun `no part, no frame`() {
        assertTrue(RescanZoneMesh.of(emptyList()).isEmpty)
    }

    @Test
    fun `each part is framed by twelve beams just round its box`() {
        val mesh = RescanZoneMesh.of(listOf(floor, wall))
        assertEquals(2 * RescanZoneMesh.TRIANGLES_PER_ZONE, mesh.triangleCount)
        val h = RescanZoneMesh.BEAM_M / 2f
        assertArrayEquals(floatArrayOf(-h, -h, -1f - h, 2.15f + h, 2.4f + h, 2f + h), mesh.bounds()!!, 1e-5f)
    }

    @Test
    fun `the frame leaves the box open, every corner on one of its edges`() {
        val mesh = RescanZoneMesh.of(listOf(wall))
        val h = RescanZoneMesh.BEAM_M / 2f + 1e-5f
        val box = wall.box
        for (v in 0 until mesh.vertexCount) {
            // On an edge: two of its three coordinates within half a beam of one of the box's faces.
            val onFaces = (0 until 3).count { axis ->
                val p = mesh.positions[v * 3 + axis]
                abs(p - box[axis]) <= h || abs(p - box[axis + 3]) <= h
            }
            assertTrue("vertex $v is off the box's edges", onFaces >= 2)
        }
    }
}
