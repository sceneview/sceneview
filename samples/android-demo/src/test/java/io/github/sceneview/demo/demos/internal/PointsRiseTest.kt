package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointsRiseTest {

    @Test
    fun `points that rose within the window are rising`() {
        val rise = PointsRise(windowSeconds = WINDOW_S)
        rise.rising(points = 100, seconds = 10f)
        assertTrue(rise.rising(points = 150, seconds = 11f))
        assertTrue(rise.rising(points = 150, seconds = 11f + WINDOW_S))
    }

    @Test
    fun `points flat for longer than the window are not rising`() {
        val rise = PointsRise(windowSeconds = WINDOW_S)
        rise.rising(points = 100, seconds = 10f)
        rise.rising(points = 150, seconds = 11f)
        assertFalse(rise.rising(points = 150, seconds = 11.5f + WINDOW_S))
    }

    @Test
    fun `the first count says nothing of a rise`() {
        assertFalse(PointsRise(windowSeconds = WINDOW_S).rising(points = 5_000, seconds = 30f))
    }

    @Test
    fun `a new scan starts over`() {
        val rise = PointsRise(windowSeconds = WINDOW_S)
        rise.rising(points = 100, seconds = 10f)
        rise.rising(points = 150, seconds = 11f)
        assertFalse("count fell", rise.rising(points = 10, seconds = 11.5f))
        rise.rising(points = 20, seconds = 12f)
        assertFalse("clock ran back", rise.rising(points = 20, seconds = 1f))
    }

    private companion object {
        const val WINDOW_S = 3f
    }
}
