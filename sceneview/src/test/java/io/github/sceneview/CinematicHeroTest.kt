package io.github.sceneview

import androidx.compose.foundation.ScrollState
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Pure-JVM pins for [CinematicHero]'s maths: the scroll-to-camera choreography, the progress
 * clamp, the turntable clock and the scroll anchor. The composable itself needs a GPU; these are
 * the parts that decide what it draws.
 */
class CinematicHeroTest {

    private val profile = CinematicCameraProfile.Default

    private fun Position.length() = sqrt(x * x + y * y + z * z)
    private fun Position.elevation() = asin(y / length())
    private fun Position.azimuth() = atan2(x, z)

    @Test
    fun atRestTheHeroIsExactlyTheCinematicTurntable() {
        for (t in listOf(0f, 0.7f, 3f, 11f)) {
            val hero = cinematicHeroEye(t, 0f, profile, 0.5f)
            val turntable = cinematicCameraEye(t, profile, cinematicDistance(0.5f, profile))
            assertEquals(turntable.x, hero.x, 1e-5f)
            assertEquals(turntable.y, hero.y, 1e-5f)
            assertEquals(turntable.z, hero.z, 1e-5f)
        }
    }

    @Test
    fun scrollingOneHeroHeightPushesInByTheDollyFraction() {
        val rest = cinematicHeroEye(0f, 0f, profile, 0.5f).length()
        val scrolled = cinematicHeroEye(0f, 1f, profile, 0.5f).length()
        assertEquals(rest * (1f - HERO_SCROLL_DOLLY), scrolled, 1e-4f)
    }

    @Test
    fun scrollingRaisesTheCameraAndSwingsTheOrbitRound() {
        val rest = cinematicHeroEye(0f, 0f, profile, 0.5f)
        val scrolled = cinematicHeroEye(0f, 1f, profile, 0.5f)
        val rise = Math.toDegrees((scrolled.elevation() - rest.elevation()).toDouble()).toFloat()
        val swing = Math.toDegrees((scrolled.azimuth() - rest.azimuth()).toDouble()).toFloat()
        assertEquals(HERO_SCROLL_RISE_DEGREES, rise, 1e-2f)
        assertEquals(HERO_SCROLL_ORBIT_DEGREES, swing, 1e-2f)
    }

    @Test
    fun theCameraFollowsTheScrollLinearlyAndStopsAtOneHeroHeight() {
        val half = cinematicHeroEye(0f, 0.5f, profile, 0.5f).length()
        val rest = cinematicHeroEye(0f, 0f, profile, 0.5f).length()
        assertEquals(rest * (1f - HERO_SCROLL_DOLLY / 2f), half, 1e-4f)
        val past = cinematicHeroEye(0f, 3f, profile, 0.5f)
        val end = cinematicHeroEye(0f, 1f, profile, 0.5f)
        assertEquals(end, past)
    }

    @Test
    fun scrollProgressIsClampedToOneHeroHeight() {
        assertEquals(0f, heroScrollProgress(0f, 1000f), 0f)
        assertEquals(0.25f, heroScrollProgress(250f, 1000f), 1e-6f)
        assertEquals(1f, heroScrollProgress(5000f, 1000f), 0f)
        assertEquals(1f, heroScrollProgress(Float.MAX_VALUE, 1000f), 0f)
        assertEquals(0f, heroScrollProgress(-20f, 1000f), 0f)
        // A zero-height hero (first measure) must not divide by zero.
        assertEquals(0f, heroScrollProgress(100f, 0f), 0f)
    }

    @Test
    fun clockGivesTheFirstFrameNoDeltaAndClampsStalls() {
        val clock = CinematicHeroClock()
        assertEquals(0f, clock.frame(5_000_000_000L), 0f)
        assertEquals(0.016f, clock.frame(5_016_000_000L), 1e-6f)
        // A two-second stall advances by the clamp, not by two seconds.
        assertEquals(0.016f + CinematicHeroClock.MAX_FRAME_SECONDS, clock.frame(7_016_000_000L), 1e-6f)
    }

    @Test
    fun aPausedClockResumesWhereItStopped() {
        val clock = CinematicHeroClock()
        clock.frame(0L)
        clock.frame(50_000_000L)
        val before = clock.seconds
        clock.pause()
        // Back on screen a minute later: the first frame adds nothing.
        assertEquals(before, clock.frame(60_000_000_000L), 0f)
        assertEquals(before + 0.02f, clock.frame(60_020_000_000L), 1e-6f)
    }

    @Test
    fun focalLengthGivesTheProfileVerticalFov() {
        for (fov in listOf(28f, 30f, 45f)) {
            val back = verticalFovDegreesForFocalLength(focalLengthForVerticalFov(fov))
            assertEquals(fov.toDouble(), back, 1e-6)
        }
    }

    @Test
    fun scrollAnchorFollowsTheColumnScroll() {
        val state = ScrollState(initial = 0)
        val anchor = scrollHeroAnchor(state)
        assertEquals(0f, anchor.top(), 0f)
        assertEquals(0f, anchor.scrolled(), 0f)
        val scrolled = ScrollState(initial = 120)
        val moved = scrollHeroAnchor(scrolled)
        assertEquals(-120f, moved.top(), 0f)
        assertEquals(120f, moved.scrolled(), 0f)
        assertTrue(!moved.top().isNaN())
    }
}
