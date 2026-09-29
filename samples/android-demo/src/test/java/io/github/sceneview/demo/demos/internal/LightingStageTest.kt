package io.github.sceneview.demo.demos.internal

import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Lighting demos' stage (#4072): the running Sun clock and the fade that hides the floor's
 * edge. Pure math — the rendering is checked by capture.
 */
class LightingStageTest {

    @Test
    fun `the sun clock moves forward while it runs`() {
        val start = 16.5f
        val next = LightingStage.advanceSunClock(start, elapsedSeconds = 1f)
        assertEquals(start + LightingStage.SUN_CLOCK_HOURS_PER_SECOND, next, 1e-4f)
    }

    @Test
    fun `the sun clock wraps from dusk back to dawn`() {
        val next = LightingStage.advanceSunClock(LightingStage.SUN_CLOCK_DUSK - 0.01f, 1f)
        assertEquals(LightingStage.SUN_CLOCK_DAWN, next, 0f)
    }

    @Test
    fun `an hour outside the clock's day resumes at dawn`() {
        assertEquals(LightingStage.SUN_CLOCK_DAWN, LightingStage.advanceSunClock(23f, 0.016f), 0f)
        assertEquals(LightingStage.SUN_CLOCK_DAWN, LightingStage.advanceSunClock(1f, 0.016f), 0f)
    }

    @Test
    fun `the fade is opaque before the floor's nearest edge at every zoom`() {
        // From a close-up to far past any pinch the orbit allows.
        for (distance in listOf(0.5f, 1f, 2.5f, 3.5f, 6f, 10f, 15f)) {
            val start = LightingStage.stageFadeStart(distance)
            val density = LightingStage.stageFadeDensity(distance)
            val nearestEdge = LightingStage.FLOOR_SIZE / 2f - distance
            val path = (nearestEdge - start).coerceAtLeast(2f)
            val opacity = 1f - exp(-density * path)
            assertTrue(
                "opacity $opacity at eye distance $distance",
                opacity >= LightingStage.STAGE_FADE_OPACITY_AT_EDGE - 1e-4f,
            )
        }
    }

    @Test
    fun `the fade starts behind the subject`() {
        val distance = 3.5f
        // Hero and probes sit within 0.4 m of the orbit target.
        assertTrue(LightingStage.stageFadeStart(distance) > distance + 0.4f)
    }
}
