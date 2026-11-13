package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 3D Scan's live dimensions only grow as more of the room is seen: a sparser snapshot never
 * narrows them by a few centimetres (2026-11-13).
 */
class RoomMeasureGrowTest {

    /** A [w] × [d] m rectangle from ([a0], [b0]) in the frame turned by [yaw]. */
    private fun rect(a0: Float, b0: Float, w: Float, d: Float, yaw: Float): RoomMeasure {
        val c = cos(yaw)
        val s = sin(yaw)
        fun corner(a: Float, b: Float) = floatArrayOf(a * c - b * s, a * s + b * c)
        val corners = corner(a0, b0) + corner(a0 + w, b0) + corner(a0 + w, b0 + d) + corner(a0, b0 + d)
        return RoomMeasure(corners, w, d, yaw)
    }

    @Test
    fun `a smaller snapshot keeps the room seen so far`() {
        val yaw = (PI / 9).toFloat()
        val grown = RoomMeasure.grow(rect(0f, 0f, 4f, 3f, yaw), rect(0.5f, 0f, 3f, 3.5f, yaw))!!
        assertEquals(4f, grown.width, 1e-3f)
        assertEquals(3.5f, grown.depth, 1e-3f)
    }

    @Test
    fun `walls that turned start a new measure`() {
        val next = rect(0f, 0f, 2f, 2f, (PI / 4).toFloat())
        assertSame(next, RoomMeasure.grow(rect(0f, 0f, 4f, 3f, 0f), next))
    }

    @Test
    fun `no new measure keeps the last, none at all is none`() {
        val last = rect(0f, 0f, 4f, 3f, 0f)
        assertSame(last, RoomMeasure.grow(last, null))
        assertNull(RoomMeasure.grow(null, null))
    }
}
