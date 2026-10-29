package io.github.sceneview.demo.ui.home

import io.github.sceneview.utils.GlRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure-JVM tests for the home hero's night circuit (#3948), the clock that makes it resume
 * where it stopped (#3949), and the ladder that keeps it at frame rate. No Filament, no
 * Compose, no Robolectric.
 */
class HomeHeroFlightTest {

    private val second = 1_000_000_000L

    // ── Clock ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `clock advances only on presented frames and never catches up after a pause`() {
        val clock = HeroClock()
        assertEquals(0.0, clock.frame(0L, moving = true), 0.0)
        assertEquals(0.05, clock.frame(second / 20, moving = true), 1e-9)
        // Parked for a minute (scrolled away, backgrounded): the first frame back
        // carries no delta, the film is exactly where it was.
        clock.pause()
        assertEquals(0.05, clock.frame(61 * second, moving = true), 1e-9)
        assertEquals(0.1, clock.frame(61 * second + second / 20, moving = true), 1e-9)
    }

    @Test
    fun `a stalled frame is clamped so the film cannot jump`() {
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

    // ── Tiers and frame budget ───────────────────────────────────────────────────────────

    @Test
    fun `low-RAM devices start at the bottom rung, the rest at the top`() {
        assertEquals(HeroTier.Light, HeroTier.forDevice(lowRam = true))
        assertEquals(HeroTier.Cinematic, HeroTier.forDevice(lowRam = false))
    }

    @Test
    fun `a rung this install settled on is where it starts, never above the device's pick`() {
        assertEquals(HeroTier.Standard, HeroTier.forDevice(lowRam = false, settled = HeroTier.Standard))
        assertEquals(HeroTier.Light, HeroTier.forDevice(lowRam = true, settled = HeroTier.High))
        assertEquals(HeroTier.Cinematic, HeroTier.forDevice(lowRam = false, settled = null))
    }

    @Test
    fun `a GPU drawing a rung at 2 fps leaves it within five seconds, two rungs down`() {
        val budget = HeroFrameBudget()
        var nanos = 0L
        budget.frame(nanos)
        var flaggedAt = -1.0
        while (nanos < 10 * second && flaggedAt < 0) {
            nanos += second / 2
            if (budget.frame(nanos)) flaggedAt = nanos.toDouble() / second
        }
        assertTrue("flagged at $flaggedAt s", flaggedAt in 0.0..5.0)
        assertTrue(budget.farBelow)
    }

    @Test
    fun `a stall after the settle is a few long frames, not a slow rung`() {
        val budget = HeroFrameBudget()
        var nanos = 0L
        budget.frame(nanos)
        repeat(120) { nanos += second / 60; budget.frame(nanos) }
        // A late texture upload: three long frames, then 60 fps again.
        val flagged = (0 until 3).any { nanos += 6 * second / 10; budget.frame(nanos) } ||
            (0 until 120).any { nanos += second / 60; budget.frame(nanos) }
        assertFalse(flagged)
    }

    @Test
    fun `a slow compile frame is settled, not judged`() {
        val budget = HeroFrameBudget()
        var nanos = 0L
        budget.frame(nanos)
        // One 1.5 s compile, then a steady 60 fps: never too slow.
        nanos += 3 * second / 2
        assertFalse(budget.frame(nanos))
        repeat(300) {
            nanos += second / 60
            assertFalse(budget.frame(nanos))
        }
    }

    @Test
    fun `the ladder drops reflections, then the shadow, then the venue lights and bloom`() {
        val ladder = generateSequence(HeroTier.Cinematic) { it.lower }.toList()
        assertEquals(listOf(HeroTier.Cinematic, HeroTier.High, HeroTier.Standard, HeroTier.Light), ladder)
        assertTrue(HeroTier.Cinematic.reflections)
        assertFalse(HeroTier.High.reflections)
        assertTrue(HeroTier.High.shadows)
        assertFalse(HeroTier.Standard.shadows)
        assertTrue(HeroTier.Standard.venueLights && HeroTier.Standard.bloom)
        assertFalse(HeroTier.Light.venueLights || HeroTier.Light.bloom)
        assertTrue(HeroTier.Light.performancePreset)
        assertNull(HeroTier.Light.lower)
    }

    @Test
    fun `the budget says nothing at 60 fps and flags a slow window once, after the settle`() {
        val budget = HeroFrameBudget(settleFrames = 5, windowFrames = 10, minFps = 48f)
        var nanos = 0L
        repeat(100) {
            nanos += second / 60
            assertFalse(budget.frame(nanos))
        }
        // At 30 fps: one flag per measure, each after a fresh settle. A window closes as soon
        // as it overruns 10 frames at 48 fps (7 frames at 30), so a measure is 5 + 7 frames.
        val flags = (0 until 32).count {
            nanos += second / 30
            budget.frame(nanos)
        }
        assertEquals(3, flags)
        assertFalse(budget.farBelow)
    }

    @Test
    fun `a reset budget forgets the window under way`() {
        val budget = HeroFrameBudget(settleFrames = 0, windowFrames = 4, minFps = 48f)
        var nanos = 0L
        budget.frame(nanos)
        repeat(3) { nanos += second / 10; budget.frame(nanos) }
        budget.reset()
        // A long pause, then fast frames: the pause is not counted.
        nanos += 60 * second
        val flagged = (0 until 5).any { nanos += second / 60; budget.frame(nanos) }
        assertFalse(flagged)
    }

    // ── Camera ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `the camera hits every shot of the website's film on time`() {
        for (shot in NightFilm.shots) {
            val at = nightShotAt(shot.t)
            assertEquals("angle at ${shot.t}", shot.angle, at.angle, 1e-4f)
            assertEquals("height at ${shot.t}", shot.height, at.height, 1e-4f)
            assertEquals("radius at ${shot.t}", shot.radius, at.radius, 1e-4f)
            assertEquals("fov at ${shot.t}", shot.fov, at.fov, 1e-4f)
        }
    }

    @Test
    fun `the lap closes on the first shot one turn on, with no seam`() {
        val loop = NightFilm.LOOP_SECONDS
        val end = nightShotAt(loop - 1e-3f)
        val next = nightShotAt(loop)
        // The angle restarts one turn back: the same place on the orbit.
        assertEquals(NightFilm.shots[0].angle, next.angle, 1e-3f)
        assertEquals(next.angle + 2f * Math.PI.toFloat(), end.angle, 1e-2f)
        assertEquals(next.height, end.height, 1e-2f)
        assertEquals(next.radius, end.radius, 1e-2f)
        // And the eye never jumps anywhere, the seam included.
        var previous = nightCameraPose(0f)
        for (i in 1..2_800) {
            val pose = nightCameraPose(i * 0.01f)
            val step = hypot(pose.eyeX - previous.eyeX, pose.eyeZ - previous.eyeZ)
            assertTrue("step $step at ${i * 0.01f}", step < 0.2f)
            previous = pose
        }
    }

    @Test
    fun `the camera orbits the car, above the wet asphalt, looking at it`() {
        for (i in 0 until 1_400) {
            val pose = nightCameraPose(i * 0.02f, aspect = 0.5f)
            val distance = hypot(pose.eyeX - NightFilm.CAR_X, pose.eyeZ - NightFilm.CAR_Z)
            assertTrue("eye height ${pose.eyeY}", pose.eyeY >= NightFilm.MIN_EYE_HEIGHT)
            assertTrue("distance $distance", distance > 5f && distance < 16f)
            assertEquals(NightFilm.CAR_X, pose.targetX, 0f)
            assertEquals(NightFilm.CAR_Z, pose.targetZ, 0f)
            assertTrue("fov ${pose.verticalFovDegrees}", pose.verticalFovDegrees in 27f..31f)
        }
    }

    @Test
    fun `a narrow frame backs the camera off, as the website does`() {
        fun distance(aspect: Float) = nightCameraPose(0f, aspect = aspect).let {
            hypot(it.eyeX - NightFilm.CAR_X, it.eyeZ - NightFilm.CAR_Z)
        }
        val wide = distance(2f)
        val phone = distance(0.6f)
        assertEquals(NightFilm.shots[0].radius * 1.2f, wide, 1e-3f)
        assertEquals(NightFilm.shots[0].radius * 1.9f, phone, 1e-3f)
    }

    @Test
    fun `scrolling the band away turns the camera round the car, pushes in and sinks`() {
        val rest = nightCameraPose(3f, scroll = 0f)
        val gone = nightCameraPose(3f, scroll = 1f)
        val restDistance = hypot(rest.eyeX, rest.eyeZ - NightFilm.CAR_Z)
        val goneDistance = hypot(gone.eyeX, gone.eyeZ - NightFilm.CAR_Z)
        assertTrue(goneDistance < restDistance)
        assertTrue(gone.eyeY < rest.eyeY)
        val restAngle = kotlin.math.atan2(rest.eyeX, rest.eyeZ - NightFilm.CAR_Z)
        val goneAngle = kotlin.math.atan2(gone.eyeX, gone.eyeZ - NightFilm.CAR_Z)
        assertTrue(abs(goneAngle - restAngle) > 0.5f)
        // Past the end of the travel nothing more happens.
        assertEquals(gone, nightCameraPose(3f, scroll = 3f))
    }

    @Test
    fun `reduced motion holds the opening shot whatever the clock or the scroll say`() {
        val opening = nightCameraPose(0f)
        assertEquals(opening, nightCameraPose(42f, scroll = 0.7f, motion = false))
    }

    @Test
    fun `tilt steers the camera a few degrees, never more`() {
        val level = nightCameraPose(5f)
        val leaned = nightCameraPose(5f, tiltX = 1f, tiltY = 1f)
        assertTrue(leaned.eyeY > level.eyeY)
        val turned = abs(
            kotlin.math.atan2(leaned.eyeX, leaned.eyeZ - NightFilm.CAR_Z) -
                kotlin.math.atan2(level.eyeX, level.eyeZ - NightFilm.CAR_Z),
        )
        assertTrue("turned $turned", turned > 0.01f && turned < 0.1f)
    }

    @Test
    fun `the focal length gives the shot's vertical field of view`() {
        val pose = nightCameraPose(0f)
        val fov = Math.toDegrees(2.0 * kotlin.math.atan(12.0 / pose.focalLengthMillimetres))
        assertEquals(pose.verticalFovDegrees.toDouble(), fov, 1e-4)
    }

    // ── Lamps ────────────────────────────────────────────────────────────────────────────

    private fun levels(film: Float, motion: Boolean = true) = nightCueLevels(film, motion)

    @Test
    fun `the venue powers up group by group, once, and is all lit from two seconds`() {
        val dark = levels(0f)
        assertEquals(0f, dark.flood, 0f)
        assertEquals(0f, dark.screens, 0f)
        assertEquals(0f, dark.headlights, 0f)
        for (film in listOf(2.5f, 5f, 16f, 30f)) {
            val lit = levels(film)
            assertEquals(1f, lit.garage, 0f)
            assertEquals(1f, lit.windows, 0f)
            assertEquals(1f, lit.city, 0f)
            assertEquals(1f, lit.flood, 0f)
        }
    }

    @Test
    fun `the gantry is lit as the lap opens, dark, then one column a second, out at seven`() {
        assertTrue(levels(0.5f).startColumns.all { it == 1f })
        assertTrue(levels(1.0f).startColumns.all { it == 0f })
        val third = levels(4.0f).startColumns
        assertEquals(listOf(1f, 1f, 1f, 0f, 0f), third.toList())
        assertTrue(levels(6.9f).startColumns.all { it == 1f })
        assertEquals(1f, levels(6.9f).startLight, 0f)
        assertTrue(levels(7.05f).startColumns.all { it == 0f })
        assertEquals(0f, levels(10f).startLight, 0f)
    }

    @Test
    fun `the gantry has relit before the lap closes, so the next one opens on it`() {
        assertTrue(levels(13.9f).startColumns.all { it == 1f })
        assertTrue(levels(14.2f).startColumns.all { it == 1f })
        assertTrue(levels(14f + 4f).startColumns.take(3).all { it == 1f })
    }

    @Test
    fun `lights out fires two high-beam pulses`() {
        val steady = levels(6.5f)
        assertEquals(1f, steady.headlights, 0f)
        val first = levels(7f + NightCues.FLASH_LENGTH / 2f)
        val second = levels(7.45f + NightCues.FLASH_LENGTH / 2f)
        assertEquals(NightCues.FLASH_LIGHT_PEAK, first.headlights, 1e-3f)
        assertEquals(NightCues.FLASH_BEAM_PEAK, second.beams, 1e-3f)
        assertEquals(1f, levels(8.5f).headlights, 0f)
        // Every lap.
        assertEquals(NightCues.FLASH_LIGHT_PEAK, levels(14f + 7f + NightCues.FLASH_LENGTH / 2f).headlights, 1e-3f)
    }

    @Test
    fun `the brakes glow from lights out until the camera has passed the tail`() {
        assertEquals(1f, levels(6.5f).brake, 0f)
        assertEquals(NightCues.BRAKE_LIGHT_PEAK, levels(8f).brake, 1e-3f)
        assertEquals(NightCues.BRAKE_LAMP_PEAK, levels(8f).brakeLamps, 1e-3f)
        assertEquals(1f, levels(10.5f).brake, 0f)
    }

    @Test
    fun `the beacon blinks on a short duty and never goes fully dark`() {
        val samples = (0 until 1_200).map { levels(5f + it * 0.001f).beacon }
        assertTrue(samples.min() >= NightCues.BEACON_FLOOR - 1e-6f)
        assertEquals(1f, samples.max(), 1e-6f)
        val onShare = samples.count { it > 0.9f } / samples.size.toFloat()
        assertTrue("on $onShare", onShare in 0.05f..0.15f)
    }

    @Test
    fun `every level stays finite and non-negative over a long run`() {
        for (i in 0 until 6_000) {
            val l = levels(i * 0.01f)
            val all = listOf(
                l.garage, l.windows, l.city, l.seats, l.led, l.ledWarm, l.screens, l.flood,
                l.beacon, l.startLight, l.headlights, l.headlightLamps, l.beams, l.signals,
                l.brake, l.brakeLamps,
            ) + l.startColumns.toList()
            assertTrue("film ${i * 0.01f}", all.all { it.isFinite() && it >= 0f })
        }
    }

    @Test
    fun `reduced motion lights every lamp as authored`() {
        val l = levels(0f, motion = false)
        val all = listOf(
            l.garage, l.windows, l.city, l.seats, l.led, l.ledWarm, l.screens, l.flood,
            l.beacon, l.startLight, l.headlights, l.headlightLamps, l.beams, l.signals,
            l.brake, l.brakeLamps,
        ) + l.startColumns.toList()
        assertTrue(all.all { it == 1f })
    }

    @Test
    fun `the lap clock wraps every fourteen seconds`() {
        assertEquals(0f, nightLap(0f), 0f)
        assertEquals(3f, nightLap(17f), 1e-4f)
        assertEquals(0f, nightLap(28f), 1e-4f)
    }

    // ── Tilt and surface ─────────────────────────────────────────────────────────────────

    @Test
    fun `tilt settles to zero at any resting posture and follows a change of lean`() {
        val tilt = HeroTilt()
        // Held at a steady 30° lean for ten seconds: the slow filter learns it.
        tilt.feed(gravityX = 4.9f, gravityZ = 0f)
        repeat(600) { tilt.update(1f / 60f) }
        assertEquals(0f, tilt.x, 0.02f)
        assertEquals(0f, tilt.y, 0.02f)
        // A quick lean the other way deflects, then decays back as it becomes the posture.
        tilt.feed(gravityX = -1f, gravityZ = 0f)
        repeat(12) { tilt.update(1f / 60f) }
        assertTrue("deflects right, x=${tilt.x}", tilt.x > 0.3f)
        repeat(1800) { tilt.update(1f / 60f) }
        assertEquals(0f, tilt.x, 0.05f)
        tilt.reset()
        assertEquals(0f, tilt.x, 0f)
    }

    @Test
    fun `the stage is not started on the legacy guest SwiftShader, nor without a GL context`() {
        // What the x86_64 virtual devices of #4411 report: Android 11's own SwiftShader GLES.
        val guestSwiftShader =
            GlRenderer("Google Inc.", "Google SwiftShader", "OpenGL ES 3.0 SwiftShader 4.1.0.7")
        assertEquals(HeroSurface.Still, HeroSurface.forRenderer(guestSwiftShader))
        assertEquals(HeroSurface.Still, HeroSurface.forRenderer(null))
    }

    @Test
    fun `every other renderer runs it, the emulator's software ones included`() {
        val renderers = listOf(
            GlRenderer("Qualcomm", "Adreno (TM) 740", "OpenGL ES 3.2 V@0676.42"),
            GlRenderer("ARM", "Mali-G78", "OpenGL ES 3.2 v1.r44p0-01eac0"),
            // `-gpu swiftshader_indirect`: the device-QA emulator and the CI golden leg.
            GlRenderer(
                "Google (Google Inc.)",
                "Android Emulator OpenGL ES Translator (Google SwiftShader)",
                "OpenGL ES 3.0 (OpenGL ES 3.0 SwiftShader 4.0.0.1)",
            ),
            GlRenderer(
                "Google (Apple)",
                "Android Emulator OpenGL ES Translator (Apple M2)",
                "OpenGL ES 3.0 (4.1 Metal - 89.3)",
            ),
        )
        renderers.forEach { assertEquals(it.renderer, HeroSurface.Live, HeroSurface.forRenderer(it)) }
    }

    // ── Flashes ──────────────────────────────────────────────────────────────────────────

    /** Opposing swings of [level] by more than [threshold] inside the 1 s window from [from], halved. */
    private fun flashesPerSecond(from: Float, threshold: Float, level: (Float) -> Float): Int {
        var swings = 0
        var lastSign = 0
        var previous = level(from)
        var t = from
        while (t < from + 1f) {
            t += 1f / 120f
            val current = level(t)
            val delta = current - previous
            if (abs(delta) > threshold) {
                val sign = if (delta > 0f) 1 else -1
                if (sign != lastSign) swings++
                lastSign = sign
                previous = current
            }
        }
        return swings / 2
    }

    @Test
    fun `no lamp of the set strobes more than three times a second`() {
        // Every window of the first two laps, at 0.1 s steps; a swing is a 20 % change.
        var start = 0f
        while (start < 28f) {
            val pairs = listOf<(NightCueLevels) -> Float>(
                { it.screens }, { it.ledWarm }, { it.flood }, { it.garage }, { it.city },
                { it.headlights }, { it.beams },
            )
            for (pick in pairs) {
                val flashes = flashesPerSecond(start, 0.2f) { pick(levels(it)) }
                assertTrue("$flashes flashes/s from $start s", flashes <= 3)
            }
            start += 0.1f
        }
    }

    @Test
    fun `the venue powers up without blacking out once a group has begun to rise`() {
        // The floods drive the key light and the image-based light: the whole set.
        var t = NightCues.FLOOD.first + 0.05f
        var previous = levels(t).flood
        while (t < NightCues.FLOOD.first + NightCues.FLOOD.second) {
            t += 1f / 60f
            val flood = levels(t).flood
            assertTrue("flood fell from $previous to $flood at $t s", flood > previous * 0.6f)
            previous = flood
        }
    }

    @Test
    fun `the high beams flash at most to the softened peak, and the cones fade head-on`() {
        val peak = levels(7f + NightCues.FLASH_LENGTH / 2f)
        assertTrue(peak.beams <= 2f && peak.headlightLamps <= 2f && peak.headlights <= 2f)
        val headOn = nightCueLevels(5f, headOn = 1f)
        assertEquals(NightCues.HEAD_ON_BEAMS, headOn.beams, 1e-4f)
        assertEquals(NightCues.HEAD_ON_LAMPS, headOn.headlightLamps, 1e-4f)
        assertEquals(1f, nightCueLevels(5f, headOn = 0f).beams, 0f)
    }

    @Test
    fun `head-on is 1 in front of the nose and 0 from the side and the tail`() {
        fun pose(angle: Float) = NightCameraPose(
            eyeX = NightFilm.CAR_X + kotlin.math.sin(angle) * 7f,
            eyeY = 1f,
            eyeZ = NightFilm.CAR_Z + kotlin.math.cos(angle) * 7f,
            targetX = 0f, targetY = 0f, targetZ = 0f, verticalFovDegrees = 30f,
        )
        assertEquals(1f, nightHeadOn(pose(0f)), 1e-4f)
        assertEquals(0f, nightHeadOn(pose((Math.PI / 2).toFloat())), 1e-4f)
        assertEquals(0f, nightHeadOn(pose(Math.PI.toFloat())), 1e-4f)
    }

    // ── Scroll inertia ───────────────────────────────────────────────────────────────────

    @Test
    fun `a fling back to the top eases the camera in instead of snapping it`() {
        val follow = HeroScrollFollow()
        follow.follow(1f, 0L)
        // One 60 Hz frame after the scroll jumped to 0: still nearly all the way out.
        val next = follow.follow(0f, second / 60)
        assertTrue("moved to $next in one frame", next > 0.95f)
        var t = second / 60
        var previous = next
        repeat(29) {
            t += second / 60
            val v = follow.follow(0f, t)
            assertTrue("never overshoots: $v", v >= 0f && v <= previous)
            assertTrue("never faster than its cap", previous - v <= 1.6f / 60f + 1e-4f)
            previous = v
        }
        // Half a second on, well on its way; a second and a half on, there.
        assertTrue("at 0.5 s: $previous", previous < 0.5f)
        repeat(60) { t += second / 60; previous = follow.follow(0f, t) }
        assertEquals(0f, previous, 0.02f)
    }

    @Test
    fun `the follow curve is the same at 30 and at 60 fps`() {
        val at60 = HeroScrollFollow().apply { follow(0f, 0L) }
        val at30 = HeroScrollFollow().apply { follow(0f, 0L) }
        var v60 = 0f
        var v30 = 0f
        for (frame in 1..36) v60 = at60.follow(0.4f, frame * second / 60)
        for (frame in 1..18) v30 = at30.follow(0.4f, frame * second / 30)
        assertEquals(v60, v30, 0.01f)
    }

    @Test
    fun `two frames at the same timestamp keep the curve and its speed`() {
        val plain = HeroScrollFollow().apply { follow(1f, 0L) }
        val doubled = HeroScrollFollow().apply { follow(1f, 0L) }
        var vPlain = 0f
        var vDoubled = 0f
        for (frame in 1..30) {
            val nanos = frame * second / 60
            vPlain = plain.follow(0f, nanos)
            vDoubled = doubled.follow(0f, nanos)
            // A duplicate frame holds its value, then the run carries on as if it were not there.
            if (frame == 6) assertEquals(vDoubled, doubled.follow(0f, nanos), 0f)
        }
        assertEquals(vPlain, vDoubled, 1e-5f)
    }

    @Test
    fun `a paused follow resumes from rest at the frame it paused on`() {
        val follow = HeroScrollFollow()
        follow.follow(1f, 0L)
        for (frame in 1..6) follow.follow(0f, frame * second / 60)
        val held = follow.value
        follow.pause()
        assertEquals(held, follow.follow(0f, 10 * second), 0f)
        val fresh = HeroScrollFollow().apply { follow(held, 0L) }
        assertEquals(fresh.follow(0f, second / 60), follow.follow(0f, 10 * second + second / 60), 1e-5f)
    }

    @Test
    fun `a parked stage resumes from the frame it parked on`() {
        val follow = HeroScrollFollow()
        follow.follow(0.8f, 0L)
        // Ten seconds off screen, then back at the top: the first frame holds 0.8.
        assertEquals(0.8f, follow.follow(0f, 10 * second), 0f)
        assertTrue(follow.follow(0f, 10 * second + second / 60) < 0.8f)
    }

    @Test
    fun `snap follows the target exactly, for QA captures and reduced motion`() {
        val follow = HeroScrollFollow()
        follow.follow(1f, 0L)
        assertEquals(0f, follow.follow(0f, second / 60, snap = true), 0f)
        assertEquals(0.3f, follow.follow(0.3f, second / 30, snap = true), 0f)
    }
}
