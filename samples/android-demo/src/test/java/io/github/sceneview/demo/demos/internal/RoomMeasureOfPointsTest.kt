package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 3D Scan's live model measures the place it has seen: the rectangle squared to the walls that
 * holds its points, not every plane ARCore found past a door (2026-11-13).
 */
class RoomMeasureOfPointsTest {

    /** A [w]×[d] m floor of points, turned by [yaw], with [strays] far points past one side. */
    private fun floor(w: Float, d: Float, yaw: Float, strays: Int = 0): FloatArray {
        val out = ArrayList<Float>()
        val c = cos(yaw)
        val s = sin(yaw)
        var a = 0f
        while (a <= w) {
            var b = 0f
            while (b <= d) {
                out += a * c - b * s; out += 0f; out += a * s + b * c
                b += 0.05f
            }
            a += 0.05f
        }
        repeat(strays) { out += 9f * c; out += 0f; out += 9f * s }
        return out.toFloatArray()
    }

    @Test
    fun `a room's points give its width and depth along its walls`() {
        val yaw = (PI / 7).toFloat()
        val room = RoomMeasure.ofPoints(floor(3.8f, 3.0f, yaw), yaw)!!
        assertEquals(3.8f, room.width, 0.2f)
        assertEquals(3.0f, room.depth, 0.2f)
        assertEquals(yaw, room.yaw, 0f)
    }

    @Test
    fun `a few stray points past a door do not stretch it`() {
        val room = RoomMeasure.ofPoints(floor(3f, 3f, 0f, strays = 20), 0f)!!
        assertEquals(3f, room.width, 0.2f)
    }

    @Test
    fun `too few points, or a sliver, measure nothing`() {
        assertNull(RoomMeasure.ofPoints(FloatArray(30), 0f))
        assertNull(RoomMeasure.ofPoints(floor(3f, 0.3f, 0f), 0f))
    }
}
