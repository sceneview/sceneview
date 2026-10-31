package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The look the result gives the parts still to scan (#4735): each box drawn as a dashed frame —
 * twelve edges of short square dashes, closed at the corners — over a translucent veil of the box
 * itself, so a zone reads as "not scanned yet", not as a glitch on the model.
 */
class RescanZoneMeshTest {
    private val floor = RescanZone(RescanKind.FloorHole, floatArrayOf(0f, 0f, 0f, 1f, 0.05f, 2f), 2f, "a hole")
    private val wall = RescanZone(RescanKind.MissingWall, floatArrayOf(1.85f, 0f, -1f, 2.15f, 2.4f, 1f), 2f, "a wall")

    @Test
    fun `no part, no frame and no veil`() {
        assertTrue(RescanZoneMesh.of(emptyList()).isEmpty)
        assertTrue(RescanZoneMesh.veil(emptyList()).isEmpty)
    }

    @Test
    fun `an edge shorter than a dash is one dash`() {
        assertEquals(1, RescanZoneMesh.dashes(0.05f))
        assertEquals(1, RescanZoneMesh.dashes(0f))
    }

    @Test
    fun `each edge is cut into dashes, the frame just round its box`() {
        val mesh = RescanZoneMesh.of(listOf(floor, wall))
        val expected = listOf(floor, wall).sumOf { zone ->
            val b = zone.box
            4 * (
                RescanZoneMesh.dashes(b[3] - b[0]) + RescanZoneMesh.dashes(b[4] - b[1]) +
                    RescanZoneMesh.dashes(b[5] - b[2])
                )
        } * RescanZoneMesh.TRIANGLES_PER_DASH
        assertEquals(expected, mesh.triangleCount)
        // More than the twelve plain beams of a solid frame: it is dashed.
        assertTrue(mesh.triangleCount > 2 * 12 * RescanZoneMesh.TRIANGLES_PER_DASH)
        val h = RescanZoneMesh.BEAM_M / 2f
        assertArrayEquals(floatArrayOf(-h, -h, -1f - h, 2.15f + h, 2.4f + h, 2f + h), mesh.bounds()!!, 1e-5f)
    }

    @Test
    fun `the frame leaves the box open, every dash on one of its edges`() {
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

    @Test
    fun `every corner is closed by the three edges that meet there`() {
        val dashes = dashBoxes(RescanZoneMesh.of(listOf(wall)))
        val b = wall.box
        for (x in floatArrayOf(b[0], b[3])) for (y in floatArrayOf(b[1], b[4])) for (z in floatArrayOf(b[2], b[5])) {
            val holding = dashes.count { d ->
                x >= d[0] - EPS && x <= d[3] + EPS && y >= d[1] - EPS && y <= d[4] + EPS && z >= d[2] - EPS && z <= d[5] + EPS
            }
            assertTrue("corner ($x, $y, $z) is held by $holding dashes", holding >= 3)
        }
    }

    @Test
    fun `a tall edge is drawn on part of its length, the gaps between its dashes left open`() {
        val dashes = dashBoxes(RescanZoneMesh.of(listOf(wall)))
        val beam = RescanZoneMesh.BEAM_M
        // The vertical edge at (1.85, −1), 2.4 m tall: its dashes are the boxes long along y there.
        val upright = dashes.filter { d ->
            d[4] - d[1] > beam * 1.5f && 1.85f in d[0]..d[3] && -1f in d[2]..d[5]
        }
        assertTrue("${upright.size} dashes", upright.size >= 8)
        val drawn = upright.sumOf { (it[4] - it[1]).toDouble() }.toFloat()
        val share = drawn / (2.4f + beam)
        assertTrue("$share of the edge is drawn", share in 0.5f..0.75f)
    }

    @Test
    fun `a dash is about a dash long whatever the edge`() {
        for (side in floatArrayOf(1f, 1.7f, 2.45f, 3.3f, 5f)) {
            val cube = RescanZone(RescanKind.LowDensity, floatArrayOf(0f, 0f, 0f, side, side, side), side * side, "a cube")
            for (d in dashBoxes(RescanZoneMesh.of(listOf(cube)))) {
                val long = maxOf(d[3] - d[0], d[4] - d[1], d[5] - d[2])
                assertTrue("a $long m dash on a $side m edge", abs(long - RescanZoneMesh.DASH_M) <= 0.2f * RescanZoneMesh.DASH_M)
            }
        }
    }

    @Test
    fun `the veil is each box itself, closed`() {
        val veil = RescanZoneMesh.veil(listOf(floor, wall))
        assertEquals(2 * RescanZoneMesh.TRIANGLES_PER_VEIL, veil.triangleCount)
        assertArrayEquals(floatArrayOf(0f, 0f, -1f, 2.15f, 2.4f, 2f), veil.bounds()!!, 1e-6f)
        for (v in 0 until veil.vertexCount) {
            val p = FloatArray(3) { veil.positions[v * 3 + it] }
            val corner = listOf(floor, wall).any { zone ->
                (0 until 3).all { axis -> p[axis] == zone.box[axis] || p[axis] == zone.box[axis + 3] }
            }
            assertTrue("veil vertex $v is no box corner", corner)
        }
    }

    /** Each dash's box, `[minX, minY, minZ, maxX, maxY, maxZ]`: a dash is one cuboid of eight vertices. */
    private fun dashBoxes(mesh: DebugMesh): List<FloatArray> {
        assertEquals(0, mesh.vertexCount % 8)
        return (0 until mesh.vertexCount / 8).map { k ->
            val box = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (v in k * 8 until k * 8 + 8) for (axis in 0 until 3) {
                val p = mesh.positions[v * 3 + axis]
                box[axis] = minOf(box[axis], p)
                box[axis + 3] = maxOf(box[axis + 3], p)
            }
            box
        }
    }

    private companion object {
        const val EPS = 1e-5f
    }
}
