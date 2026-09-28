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
    fun `the scroll glides the camera from the flight onto the fox, and back, with no memory`() {
        val period = 40f
        val t = 7.3
        val flight = heroFlightPose(t, period)
        val landed = heroFlightPose(t, period, glide = 1f)
        // At rest the camera looks ahead; the fox is out of frame, far below the gaze.
        assertTrue("flight looks far ahead", flight.targetZ < -8f)
        // Landed: the camera looks at the fox from its lit left side, a little above,
        // aiming just ahead of it along the run, wings level.
        assertEquals(landed.foxX, landed.targetX, 1e-4f)
        assertTrue("aims just ahead", landed.targetZ < landed.foxZ && landed.targetZ > landed.foxZ - 1f)
        assertTrue("above the fox", landed.eyeY > landed.foxY)
        assertTrue("on its left", landed.eyeX < landed.foxX - 1.5f)
        assertEquals(0f, landed.rollDegrees, 1e-4f)
        // Glide is eased and monotone: the target only ever comes closer as p grows.
        var previous = Float.MAX_VALUE
        for (i in 0..20) {
            val pose = heroFlightPose(t, period, glide = i / 20f)
            val distance = kotlin.math.abs(pose.targetZ - landed.targetZ)
            assertTrue("p=${i / 20f} distance=$distance", distance <= previous + 1e-5f)
            previous = distance
        }
        // No memory: after any trip down, p = 0 is exactly the flight's own pose.
        heroFlightPose(t, period, glide = 0.8f)
        assertEquals(flight, heroFlightPose(t, period, glide = 0f))
        // Out of range values clamp instead of overshooting.
        assertEquals(landed, heroFlightPose(t, period, glide = 3f))
        assertEquals(flight, heroFlightPose(t, period, glide = -1f))
    }

    @Test
    fun `the fox stands on the valley floor wherever the strip has slid`() {
        val period = 40f
        for (i in 0 until 200) {
            val pose = heroFlightPose(i * 0.37, period, terrainStart = 0.0, intro = false)
            val ground = heroTerrainHeight(pose.foxX, pose.foxZ - pose.terrainOffsetZ, period)
            assertEquals(ground, pose.foxY, 1e-5f)
            assertTrue("fox in the corridor, x=${pose.foxX}", kotlin.math.abs(pose.foxX) < 2f)
        }
    }

    @Test
    fun `reduced motion never glides`() {
        val still = heroFlightPose(0.0, 40f, motion = false)
        assertEquals(still, heroFlightPose(5.0, 40f, glide = 1f, motion = false))
    }

    @Test
    fun `coming back after the opening played shows the helmet and the valley in place`() {
        // The helmet's first textured frame after a return: no zoom-in, no rise.
        val back = heroFlightPose(80.0, 40f, entranceStart = 80.0, terrainStart = 80.0, intro = false)
        assertEquals(1f, back.helmetEntrance, 0f)
        assertEquals(1f, back.terrainRise, 0f)
        // Still hidden until the model is textured, whatever the intro says.
        assertEquals(0f, heroFlightPose(80.0, 40f, entranceStart = null, intro = false).helmetEntrance, 0f)
        // The flight resumes from where it was, not from zero.
        assertTrue(
            heroFlightPose(81.0, 40f).terrainOffsetZ != heroFlightPose(0.0, 40f).terrainOffsetZ,
        )
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
    fun `low-RAM devices get the light tier, the rest the cinematic one`() {
        val low = HeroTier.forDevice(lowRam = true)
        val full = HeroTier.forDevice(lowRam = false)
        assertFalse(low.cinematic)
        assertTrue(full.cinematic)
        assertEquals(HeroTerrainSpec.Light, low.terrain)
        assertEquals(HeroTerrainSpec.Full, full.terrain)
    }
}
