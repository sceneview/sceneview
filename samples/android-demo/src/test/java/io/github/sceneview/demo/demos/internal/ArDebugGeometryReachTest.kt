package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** How far a cloud's body reaches on the ground, its strays left out: what the live model is framed on. */
class ArDebugGeometryReachTest {

    @Test
    fun aRoomAskewInTheWorldReachesItsOwnCornersNotItsBoxs() {
        // The outline of a 4 × 2 m room turned 45°: its box's half diagonal is 3 m, its own 2.24 m.
        val points = ArrayList<Float>()
        val turn = Math.toRadians(45.0)
        for (i in 0 until 400) {
            val t = i / 400f * 4f
            val (u, v) = when {
                t < 1f -> (-2f + 4f * t) to -1f
                t < 2f -> 2f to (-1f + 2f * (t - 1f))
                t < 3f -> (2f - 4f * (t - 2f)) to 1f
                else -> -2f to (1f - 2f * (t - 3f))
            }
            points += (u * cos(turn) - v * sin(turn)).toFloat()
            points += 0f
            points += (u * sin(turn) + v * cos(turn)).toFloat()
        }
        val reach = ArDebugGeometry.robustReach(points.toFloatArray(), 0f, 0f)!!
        assertEquals(2.24f, reach, 0.08f)
    }

    @Test
    fun strayPointsFarAwayAreLeftOut() {
        val points = FloatArray(1000 * 3) { i -> if (i % 3 == 0) (i / 3 % 10) * 0.1f else 0f }
        // Five points 30 m away among a thousand within a metre.
        for (i in 0 until 5) points[i * 200 * 3] = 30f
        assertEquals(0.9f, ArDebugGeometry.robustReach(points, 0f, 0f)!!, 1e-3f)
    }

    @Test
    fun tooFewPointsToTell() {
        assertNull(ArDebugGeometry.robustReach(FloatArray(9), 0f, 0f))
    }
}
