package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatterSwitchTest {
    private fun MatterSwitch.frames(from: Long, count: Int, step: Long = 16L, motion: Boolean = true): Long {
        var now = from
        repeat(count) { now += step; advance(now, ready = true, motion = motion) }
        return now
    }

    private fun MatterSwitch.untilLanded(from: Long): Long {
        var now = from
        while (outgoing != null) { now += 16L; advance(now, ready = true, motion = true) }
        return now
    }

    @Test fun aStalledFirstDrawCannotSwallowTheSlide() {
        val switch = MatterSwitch()
        assertTrue(switch.request(FeelMaterial.Leather))
        assertTrue(switch.advance(1_000L, ready = true, motion = true))
        assertEquals(0f, switch.progress, 0f)
        assertTrue(switch.priming)
        // Emulator-5554 measured 1.5 s between the presented frames around the first Leather
        // draw. The old 450 ms wall-clock tween was over before the next frame: a pop at rest.
        switch.advance(2_500L, ready = true, motion = true)
        val perStep = MatterSwitch.MAX_STEP_MILLIS / MatterSwitch.DURATION_MILLIS.toFloat()
        assertEquals(perStep, switch.progress, 1e-6f)
        assertEquals(FeelMaterial.MemoryFoam, switch.outgoing)
        // Another hitch mid-slide costs at most one capped step too.
        var now = switch.frames(2_500L, 5)
        val before = switch.progress
        now += 1_000L
        switch.advance(now, ready = true, motion = true)
        assertEquals(before + perStep, switch.progress, 1e-6f)
        // At 60 Hz the rest of the slide still takes its presented frames.
        var frames = 0
        while (switch.outgoing != null) { now += 16L; frames++; switch.advance(now, ready = true, motion = true) }
        assertTrue("$frames frames", frames >= 15)
        assertEquals(1f, switch.progress, 0f)
        assertEquals(FeelMaterial.Leather, switch.displayed)
        assertFalse(switch.switching)
    }

    @Test fun theStageWaitsEmptyUntilTheIncomingSolidHasBeenDrawn() {
        val switch = MatterSwitch()
        switch.request(FeelMaterial.Gel)
        switch.advance(0L, ready = true, motion = true)
        var now = 0L
        while (switch.progress < .35f) { now += 16L; switch.advance(now, ready = true, motion = true) }
        // A late replacement: the foam has nearly left, velvet has never been drawn.
        switch.request(FeelMaterial.Velvet)
        now += 34L; switch.advance(now, ready = true, motion = true)
        assertEquals(FeelMaterial.Velvet, switch.displayed)
        assertEquals(MatterTransition.HIDDEN, switch.progress, 1e-6f)
        now += 34L; switch.advance(now, ready = true, motion = true)
        assertEquals(MatterTransition.HIDDEN, switch.progress, 1e-6f)
        assertFalse(switch.priming)
        now += 34L; switch.advance(now, ready = true, motion = true)
        assertTrue(switch.progress > MatterTransition.HIDDEN)
    }

    @Test fun requestsAreNeverRefusedAndReplaceATargetStillUnderTheFloor() {
        val switch = MatterSwitch()
        switch.request(FeelMaterial.Gel)
        switch.advance(0L, ready = true, motion = true)
        var now = switch.frames(0L, 3)
        assertTrue(switch.progress < MatterTransition.HIDDEN)
        // Two quick taps while the foam is still leaving: the chip follows each one, and the
        // solid that rises is the last one, primed again before it may rise.
        assertTrue(switch.request(FeelMaterial.Velvet))
        assertTrue(switch.request(FeelMaterial.Marble))
        now += 16L
        switch.advance(now, ready = true, motion = true)
        assertEquals(FeelMaterial.Marble, switch.displayed)
        assertEquals(FeelMaterial.MemoryFoam, switch.outgoing)
        assertEquals(1f, switch.direction, 0f)
        assertTrue(switch.priming)
        now = switch.untilLanded(now)
        assertEquals(FeelMaterial.Marble, switch.displayed)
        assertFalse(switch.switching)
        assertFalse(switch.request(FeelMaterial.Marble))
    }

    @Test fun aRequestOnceTheSolidRisesWaitsForItToLand() {
        val switch = MatterSwitch()
        switch.request(FeelMaterial.Gel)
        switch.advance(0L, ready = true, motion = true)
        var now = 0L
        while (switch.progress < .6f) { now += 16L; switch.advance(now, ready = true, motion = true) }
        switch.request(FeelMaterial.Leather)
        now = switch.untilLanded(now)
        assertEquals(FeelMaterial.Gel, switch.displayed)
        assertTrue(switch.switching)
        now += 16L
        assertTrue(switch.advance(now, ready = true, motion = true))
        assertEquals(FeelMaterial.Gel, switch.outgoing)
        assertEquals(FeelMaterial.Leather, switch.displayed)
        // Back to the solid that is leaving queues too: it cannot leave and rise at once.
        switch.request(FeelMaterial.Gel)
        now = switch.frames(now, 3)
        assertEquals(FeelMaterial.Leather, switch.displayed)
        now = switch.untilLanded(now)
        now += 16L
        assertTrue(switch.advance(now, ready = true, motion = true))
        assertEquals(FeelMaterial.Gel, switch.displayed)
        assertEquals(-1f, switch.direction, 0f)
    }

    @Test fun aSampleNotYetPreparedWaitsWithTheNewTitleAndNeverStartsHalfway() {
        val switch = MatterSwitch()
        switch.request(FeelMaterial.BrushedSteel)
        repeat(10) { assertFalse(switch.advance(it * 16L, ready = false, motion = true)) }
        assertTrue(switch.switching)
        assertNull(switch.outgoing)
        assertEquals(FeelMaterial.MemoryFoam, switch.displayed)
        // Changing one's mind back before it is ready cancels the switch outright.
        switch.request(FeelMaterial.MemoryFoam)
        assertFalse(switch.switching)
        assertFalse(switch.advance(200L, ready = true, motion = true))
    }

    @Test fun withoutMotionNothingSlidesAndTheSwapFollowsThePrimingFrames() {
        val switch = MatterSwitch()
        switch.request(FeelMaterial.Velvet)
        switch.advance(0L, ready = true, motion = false)
        switch.frames(0L, MatterSwitch.PRIME_FRAMES, motion = false)
        assertEquals(FeelMaterial.MemoryFoam, switch.outgoing)
        assertEquals(0f, switch.progress, 0f)
        switch.frames(100L, 1, motion = false)
        assertNull(switch.outgoing)
        assertEquals(1f, switch.progress, 0f)
    }

    @Test fun landingIsStampedOnceWhenTheSlideReachesRestHeight() {
        val switch = MatterSwitch()
        switch.request(FeelMaterial.Marble)
        switch.advance(0L, ready = true, motion = true)
        var now = 0L
        assertEquals(-1f, switch.landingAge(now), 0f)
        while (switch.progress < MatterSwitch.ARRIVE) {
            assertNull(switch.landedAt)
            now += 16L; switch.advance(now, ready = true, motion = true)
        }
        val landed = switch.landedAt!!
        assertEquals(now, landed)
        switch.frames(now, 30)
        assertEquals(landed, switch.landedAt)
        assertEquals(.5f, switch.landingAge(landed + 500L), 0f)
    }

    @Test fun flingsStepFromTheLatestRequestAndStopAtTheEnds() {
        assertEquals(FeelMaterial.Gel, MatterSwitch.flingTarget(FeelMaterial.MemoryFoam, -900f))
        assertEquals(FeelMaterial.MemoryFoam, MatterSwitch.flingTarget(FeelMaterial.MemoryFoam, 900f))
        assertEquals(FeelMaterial.BrushedSteel, MatterSwitch.flingTarget(FeelMaterial.BrushedSteel, -900f))
        // Two quick flings during one slide land two materials away, not one.
        val switch = MatterSwitch()
        switch.request(MatterSwitch.flingTarget(switch.selected, -900f))
        switch.advance(0L, ready = true, motion = true)
        switch.request(MatterSwitch.flingTarget(switch.selected, -900f))
        assertEquals(FeelMaterial.Velvet, switch.selected)
    }

    @Test fun aVisibleChipNeverScrollsAndAClippedOneScrollsJustIntoView() {
        // Viewport 0..1000 px with a 48 px margin.
        assertEquals(0f, MatterSwitch.chipScroll(100, 300, 0, 1000, 48), 0f)
        assertEquals(-48f, MatterSwitch.chipScroll(0, 200, 0, 1000, 48), 0f)
        assertEquals(-148f, MatterSwitch.chipScroll(-100, 100, 0, 1000, 48), 0f)
        assertEquals(148f, MatterSwitch.chipScroll(900, 1100, 0, 1000, 48), 0f)
        // Content padding shows up as a negative viewport start: honour it.
        assertEquals(0f, MatterSwitch.chipScroll(0, 200, -48, 952, 48), 0f)
    }

    @Test fun aRequestWhilePinnedAtHiddenForPrimingReplacesTheIncomingSolid() {
        val switch = MatterSwitch()
        switch.request(FeelMaterial.Leather)
        var now = 1_000L
        switch.advance(now, ready = true, motion = true)
        while (switch.progress + 16f / MatterSwitch.DURATION_MILLIS < MatterTransition.HIDDEN) {
            now += 16L; switch.advance(now, ready = true, motion = true)
        }
        // Velvet replaces Leather just under HIDDEN; its priming pins the slide at HIDDEN.
        switch.request(FeelMaterial.Velvet)
        now += MatterSwitch.MAX_STEP_MILLIS; switch.advance(now, ready = true, motion = true)
        assertEquals(FeelMaterial.Velvet, switch.displayed)
        assertEquals(MatterTransition.HIDDEN, switch.progress, 0f)
        assertTrue(switch.priming)
        // Still under the floor: the next request replaces it instead of queueing behind it.
        switch.request(FeelMaterial.Marble)
        now += 16L; switch.advance(now, ready = true, motion = true)
        assertEquals(FeelMaterial.Marble, switch.displayed)
        assertEquals(FeelMaterial.MemoryFoam, switch.outgoing)
    }

    @Test fun slideDurationIsTheMotionToken() {
        assertEquals(io.github.sceneview.demo.theme.SceneViewTokens.Motion.containerMillis.toLong(),
            MatterSwitch.DURATION_MILLIS)
    }
}
