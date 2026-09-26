package io.github.sceneview.demo.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the home hero's flight (#3948) and the clock that makes it resume
 * where it stopped (#3949). No Filament, no Compose, no Robolectric.
 */
class HomeHeroFlightTest {

    private val second = 1_000_000_000L

    @Test
    fun `clock advances only on presented frames and never catches up after a pause`() {
        val clock = HeroClock()
        assertEquals(0.0, clock.frame(0L, moving = true), 0.0)
        assertEquals(0.05, clock.frame(second / 20, moving = true), 1e-9)
        // Parked for a minute (scrolled away, backgrounded): the first frame back
        // carries no delta, the flight is exactly where it was.
        clock.pause()
        assertEquals(0.05, clock.frame(61 * second, moving = true), 1e-9)
        assertEquals(0.1, clock.frame(61 * second + second / 20, moving = true), 1e-9)
    }

    @Test
    fun `a stalled frame is clamped so the terrain cannot teleport`() {
        val clock = HeroClock()
        clock.frame(0L, moving = true)
        assertEquals(HeroClock.MAX_FRAME_SECONDS, clock.frame(3 * second, moving = true), 1e-9)
    }

    @Test
    fun `not moving freezes the clock and the next moving frame carries no delta`() {
        val clock = HeroClock(initialSeconds = 2.0)
        assertEquals(2.0, clock.frame(0L, moving = false), 0.0)
        assertEquals(2.0, clock.frame(5 * second, moving = false), 0.0)
        assertEquals(2.0, clock.frame(9 * second, moving = true), 0.0)
        assertEquals(2.05, clock.frame(9 * second + second / 20, moving = true), 1e-9)
    }

    @Test
    fun `terrain offset wraps inside one period so the strip never runs out`() {
        val period = 40f
        for (t in listOf(0.0, 1.0, 7.27, 100.0, 1234.5, 100_000.0)) {
            val offset = heroFlightPose(t, period).terrainOffsetZ
            assertTrue("t=$t offset=$offset", offset >= 0f && offset < period)
        }
        assertEquals(HERO_FLIGHT_SPEED, heroFlightPose(1.0, period).terrainOffsetZ, 1e-4f)
        assertEquals(0f, heroFlightPose((period / HERO_FLIGHT_SPEED).toDouble(), period).terrainOffsetZ, 1e-2f)
    }

    @Test
    fun `the camera stays above the valley, looking ahead and slightly down, banking a few degrees`() {
        for (t in 0 until 600) {
            val pose = heroFlightPose(t * 0.1, period = 40f)
            assertTrue("eyeY ${pose.eyeY}", pose.eyeY > 1.5f && pose.eyeY < 3f)
            assertTrue("eyeX ${pose.eyeX}", kotlin.math.abs(pose.eyeX) < 1f)
            assertTrue("looks ahead", pose.targetZ < pose.eyeZ)
            assertTrue("looks slightly down", pose.targetY < pose.eyeY)
            assertTrue("roll ${pose.rollDegrees}", kotlin.math.abs(pose.rollDegrees) < 5f)
            assertTrue("helmet ahead", pose.helmetZ < pose.eyeZ && pose.helmetZ > pose.targetZ)
        }
    }

    @Test
    fun `tilt steers the gaze the way the phone leans`() {
        val level = heroFlightPose(3.0, 40f)
        val right = heroFlightPose(3.0, 40f, tiltX = 1f)
        val forward = heroFlightPose(3.0, 40f, tiltY = 1f)
        assertTrue(right.targetX > level.targetX)
        assertTrue(right.rollDegrees > level.rollDegrees)
        assertTrue(forward.targetY > level.targetY)
    }

    @Test
    fun `the helmet is scaled away until textured, then eases in, and is simply there under reduced motion`() {
        assertEquals(0f, heroFlightPose(5.0, 40f, entranceStart = null).helmetEntrance, 0f)
        assertEquals(0f, heroFlightPose(5.0, 40f, entranceStart = 5.0).helmetEntrance, 0f)
        val mid = heroFlightPose(5.0 + HERO_ENTRANCE_SECONDS / 2.0, 40f, entranceStart = 5.0).helmetEntrance
        assertTrue("mid $mid", mid > 0.5f && mid < 1f)
        assertEquals(1f, heroFlightPose(5.0 + HERO_ENTRANCE_SECONDS + 1.0, 40f, entranceStart = 5.0).helmetEntrance, 0f)
        assertEquals(1f, heroFlightPose(5.0, 40f, entranceStart = 5.0, motion = false).helmetEntrance, 0f)
        assertEquals(0f, heroFlightPose(5.0, 40f, entranceStart = null, motion = false).helmetEntrance, 0f)
    }

    @Test
    fun `the terrain rises into place from its first frame, and is in place under reduced motion`() {
        assertEquals(0f, heroFlightPose(2.0, 40f, terrainStart = null).terrainRise, 0f)
        assertEquals(0f, heroFlightPose(2.0, 40f, terrainStart = 2.0).terrainRise, 0f)
        val mid = heroFlightPose(2.0 + HERO_TERRAIN_RISE_SECONDS / 2.0, 40f, terrainStart = 2.0).terrainRise
        assertTrue("mid $mid", mid > 0.5f && mid < 1f)
        assertEquals(1f, heroFlightPose(2.0 + HERO_TERRAIN_RISE_SECONDS + 0.5, 40f, terrainStart = 2.0).terrainRise, 0f)
        assertEquals(1f, heroFlightPose(2.0, 40f, terrainStart = 2.0, motion = false).terrainRise, 0f)
    }

    @Test
    fun `reduced motion holds the opening frame whatever the clock says`() {
        val opening = heroFlightPose(0.0, 40f)
        val later = heroFlightPose(42.0, 40f, motion = false)
        assertEquals(opening.terrainOffsetZ, later.terrainOffsetZ, 0f)
        assertEquals(opening.eyeX, later.eyeX, 0f)
        assertEquals(opening.rollDegrees, later.rollDegrees, 0f)
        assertEquals(opening.helmetYawDegrees, later.helmetYawDegrees, 0f)
    }

    @Test
    fun `tilt settles to zero at any resting posture and follows a change of lean`() {
        val tilt = HeroTilt()
        // Held at a steady 30° lean for ten seconds: the slow filter learns it.
        tilt.feed(gravityX = 4.9f, gravityY = 8.5f, gravityZ = 0f)
        repeat(600) { tilt.update(1f / 60f) }
        assertEquals(0f, tilt.x, 0.02f)
        assertEquals(0f, tilt.y, 0.02f)
        // A quick lean the other way deflects, then decays back as it becomes the posture.
        tilt.feed(gravityX = -1f, gravityY = 9.7f, gravityZ = 0f)
        repeat(12) { tilt.update(1f / 60f) }
        assertTrue("deflects right, x=${tilt.x}", tilt.x > 0.3f)
        repeat(1800) { tilt.update(1f / 60f) }
        assertEquals(0f, tilt.x, 0.05f)
        tilt.reset()
        assertEquals(0f, tilt.x, 0f)
    }

    @Test
    fun `low-RAM devices get the light tier, the rest the cinematic one`() {
        val low = HeroTier.forDevice(lowRam = true)
        val full = HeroTier.forDevice(lowRam = false)
        assertFalse(low.cinematic)
        assertTrue(full.cinematic)
        assertEquals(HeroTerrainSpec.Light, low.terrain)
        assertEquals(HeroTerrainSpec.Full, full.terrain)
    }
}
