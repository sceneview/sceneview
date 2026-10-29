package io.github.sceneview.demo.demos.internal.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.abs

/** The outline read off the region's own edge, when the walls' sides did not close into one. */
class RoomOutlineTest {

    /** A wall line facing ([nu], [nv]) at [offset], seen whole. */
    private fun wall(family: Int, nu: Float, nv: Float, offset: Float): WallLine {
        val columns = (SPAN_M / COLUMN_M).toInt()
        return WallLine(
            family, nu, nv, offset, SPREAD_M, -SPAN_M / 2, COLUMN_M,
            BooleanArray(columns) { true }, FloatArray(columns) { HEIGHT_M }, HEIGHT_M, IntArray(0),
        )
    }

    /** The 5 × 4 m rectangle's edge [inset] inside it, a point every [COLUMN_M]: a region stops short of its walls' cells. */
    private fun edge(inset: Float): FloatArray {
        val corners = listOf(inset to inset, 5f - inset to inset, 5f - inset to 4f - inset, inset to 4f - inset)
        val points = ArrayList<Float>()
        for (c in corners.indices) {
            val (au, av) = corners[c]
            val (bu, bv) = corners[(c + 1) % corners.size]
            val steps = (maxOf(abs(bu - au), abs(bv - av)) / COLUMN_M).toInt()
            for (k in 0 until steps) {
                points += au + (bu - au) * k / steps
                points += av + (bv - av) * k / steps
            }
        }
        return points.toFloatArray()
    }

    @Test
    fun `a region's edge along the walls is read on the walls, not inside them`() {
        val lines = listOf(wall(0, 0f, 1f, 0f), wall(1, -1f, 0f, -5f), wall(2, 0f, -1f, -4f), wall(3, 1f, 0f, 0f))
        val outline = RoomOutline.alongRegion(edge(INSET_M), lines)
        assertNotNull("an outline", outline)
        val (u, v, sides) = outline!!
        assertEquals(4, sides.size)
        sides.forEach { side -> assertNotNull("every side runs along a wall", side.line) }
        val area = RoomOutline.signedArea(u, v)
        assertEquals("area $area", 20f, area, 20f * MAX_ERROR)
    }

    private companion object {
        const val SPAN_M = 6f
        const val COLUMN_M = 0.05f
        const val SPREAD_M = 0.01f
        const val HEIGHT_M = 2.5f

        /** One 5 cm cell: how far short of a wall drawn as a barrier the room's region can stop. */
        const val INSET_M = 0.05f
        const val MAX_ERROR = 0.001f
    }
}
