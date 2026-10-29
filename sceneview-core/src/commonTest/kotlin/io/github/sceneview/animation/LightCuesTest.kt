package io.github.sceneview.animation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LightCuesTest {

    private val epsilon = 1e-4f

    private fun samples(from: Float, to: Float, count: Int = 400): List<Float> =
        (0..count).map { from + (to - from) * it / count }

    @Test
    fun ignitionIsDarkBeforeAndSteadyAfter() {
        assertEquals(0f, LightCues.ignition(0.39f, startSeconds = 0.4f), epsilon)
        assertEquals(1f, LightCues.ignition(1.2f, startSeconds = 0.4f, durationSeconds = 0.8f), epsilon)
        assertEquals(1f, LightCues.ignition(60f, startSeconds = 0.4f), epsilon)
    }

    @Test
    fun ignitionFlickersBetweenDarkAndLit() {
        val levels = samples(0f, 0.8f).map { LightCues.ignition(it, durationSeconds = 0.8f, seed = 3) }
        assertTrue(levels.all { it in 0f..1f })
        assertTrue(levels.any { it < 0.1f }, "an ignition starts with dark slots")
        assertTrue(levels.any { it > 0.75f }, "an ignition catches before it settles")
    }

    @Test
    fun ignitionIsDeterministicAndSeeded() {
        val a = samples(0f, 0.8f).map { LightCues.ignition(it, seed = 1) }
        val again = samples(0f, 0.8f).map { LightCues.ignition(it, seed = 1) }
        val other = samples(0f, 0.8f).map { LightCues.ignition(it, seed = 2) }
        assertEquals(a, again)
        assertTrue(a != other, "two seeds give two flicker patterns")
    }

    @Test
    fun ignitionWithZeroDurationIsASwitch() {
        assertEquals(0f, LightCues.ignition(0.9f, startSeconds = 1f, durationSeconds = 0f), epsilon)
        assertEquals(1f, LightCues.ignition(1f, startSeconds = 1f, durationSeconds = 0f), epsilon)
    }

    @Test
    fun fadeInRampsMonotonically() {
        assertEquals(0f, LightCues.fadeIn(0.5f, startSeconds = 1f), epsilon)
        assertEquals(1f, LightCues.fadeIn(2f, startSeconds = 1f, durationSeconds = 0.6f), epsilon)
        var previous = 0f
        samples(1f, 1.6f).forEach {
            val level = LightCues.fadeIn(it, startSeconds = 1f, durationSeconds = 0.6f)
            assertTrue(level >= previous - epsilon)
            previous = level
        }
    }

    @Test
    fun breatheStaysBetweenFloorAndOne() {
        assertEquals(0.6f, LightCues.breathe(0f, periodSeconds = 3f, floor = 0.6f), epsilon)
        assertEquals(1f, LightCues.breathe(1.5f, periodSeconds = 3f, floor = 0.6f), epsilon)
        assertTrue(samples(0f, 9f).all { LightCues.breathe(it, 3f, 0.6f) in 0.6f - epsilon..1f + epsilon })
    }

    @Test
    fun blinkIsLitForItsDuty() {
        val levels = samples(0f, 10f, 10_000).map {
            LightCues.blink(it, periodSeconds = 1f, duty = 0.25f, softness = 0f)
        }
        val lit = levels.count { it > 0.5f } / levels.size.toFloat()
        assertEquals(0.25f, lit, 0.01f)
        assertEquals(1f, LightCues.blink(0.1f, 1f, 0.25f, 0f), epsilon)
        assertEquals(0f, LightCues.blink(0.5f, 1f, 0.25f, 0f), epsilon)
    }

    @Test
    fun blinkSoftEdgesStayInRange() {
        assertTrue(samples(-3f, 3f, 2_000).all { LightCues.blink(it, 0.8f, 0.4f, 0.1f) in 0f..1f })
        assertEquals(0f, LightCues.blink(0f, 1f, 0.5f, 0.1f), epsilon)
        assertEquals(1f, LightCues.blink(0.25f, 1f, 0.5f, 0.1f), epsilon)
    }

    @Test
    fun flashPeaksOncePerPeriod() {
        assertEquals(0f, LightCues.flash(1f, periodSeconds = 4f, offsetSeconds = 2f), epsilon)
        assertEquals(1f, LightCues.flash(2.05f, periodSeconds = 4f, offsetSeconds = 2f, attackSeconds = 0.05f), epsilon)
        assertEquals(1f, LightCues.flash(6.05f, periodSeconds = 4f, offsetSeconds = 2f, attackSeconds = 0.05f), epsilon)
        assertEquals(0f, LightCues.flash(3f, periodSeconds = 4f, offsetSeconds = 2f), epsilon)
    }

    @Test
    fun glitchDropsOutOncePerWindow() {
        val every = 6f
        val levels = samples(0f, every, 6_000).map { LightCues.glitch(it, everySeconds = every, seed = 5) }
        val dark = levels.count { it < 0.5f } / levels.size.toFloat()
        // Three dark slots of five in a 0.22 s drop-out, once in 6 s.
        assertEquals(0.22f * 3f / 5f / every, dark, 0.005f)
        assertTrue(levels.all { it == 1f || it == 0.15f })
    }
}
