package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatterPrewarmTest {
    private val all: (FeelMaterial) -> Boolean = { true }
    private val foam = FeelMaterial.MemoryFoam

    /** Runs idle frames until the scheduler stops, returning the order of warm draws. */
    private fun MatterPrewarm.drain(available: (FeelMaterial) -> Boolean = all, frames: Int = 400): List<FeelMaterial> {
        val drawn = mutableListOf<FeelMaterial>()
        repeat(frames) {
            next(foam, null, available, idle = true)?.let { drawn += it }
        }
        return drawn
    }

    @Test fun warmsEveryOtherMaterialInOrderThenStops() {
        val prewarm = MatterPrewarm()
        val drawn = prewarm.drain()
        // Each material is drawn for DRAW_FRAMES consecutive frames, the shown one never.
        val order = drawn.distinct()
        assertEquals(FeelMaterial.entries - foam, order)
        assertEquals(order.size * MatterPrewarm.DRAW_FRAMES, drawn.size)
        assertTrue(prewarm.done)
        assertTrue(prewarm.retired)
        assertFalse(prewarm.needsFrames(all))
        assertNull(prewarm.next(foam, null, all, idle = true))
    }

    @Test fun waitsGapFramesBeforeAndAfterEachDraw() {
        val prewarm = MatterPrewarm()
        val frames = (0 until 80).map { prewarm.next(foam, null, all, idle = true) }
        val first = frames.indexOfFirst { it != null }
        assertEquals(MatterPrewarm.GAP_FRAMES, first)
        val second = frames.indexOfFirst { it != null && it != frames[first] }
        assertEquals(first + MatterPrewarm.DRAW_FRAMES + MatterPrewarm.GAP_FRAMES, second)
        // The measured stall lands 1-3 frames after a draw: the gap leaves room for it.
        assertTrue(MatterPrewarm.GAP_FRAMES >= MatterWarmFrames.TAIL_FRAMES)
    }

    @Test fun theLastWarmStillOwesItsTailFrames() {
        val prewarm = MatterPrewarm()
        val ready: (FeelMaterial) -> Boolean = { it == FeelMaterial.Gel }
        while (prewarm.next(foam, null, ready, idle = true) == null) Unit
        assertEquals(FeelMaterial.Gel, prewarm.next(foam, null, ready, idle = true))
        // The call after the last draw frame completes the warm.
        assertNull(prewarm.next(foam, null, ready, idle = true))
        // Nothing else is available, but the frames after Gel are still owed, idle or not:
        // its stall must reach a presented frame, and the debug measure its tail.
        var owed = 0
        while (prewarm.needsFrames(ready)) {
            owed++
            assertNull(prewarm.next(foam, null, ready, idle = owed % 2 == 0))
        }
        assertEquals(MatterPrewarm.GAP_FRAMES, owed)
        assertFalse(prewarm.retired)
    }

    @Test fun neverDrawsDuringAPressOrASwitch() {
        val prewarm = MatterPrewarm()
        repeat(50) { assertNull(prewarm.next(foam, null, all, idle = false)) }
        assertTrue(prewarm.needsFrames(all))
    }

    @Test fun anInterruptedDrawStopsAtOnceAndIsRetried() {
        val prewarm = MatterPrewarm()
        var target: FeelMaterial? = null
        while (target == null) target = prewarm.next(foam, null, all, idle = true)
        // A press lands on the next frame: nothing hidden is drawn under it.
        assertNull(prewarm.next(foam, null, all, idle = false))
        assertEquals(target, prewarm.drain().first())
    }

    @Test fun backToIdleAfterAPressWithNoDrawPendingWaitsTheWholeGap() {
        val prewarm = MatterPrewarm()
        // Idle, part of the way through the gap before the first draw.
        repeat(MatterPrewarm.GAP_FRAMES - 2) { assertNull(prewarm.next(foam, null, all, idle = true)) }
        // One busy frame, no draw in progress: the gap starts over, it does not resume.
        assertNull(prewarm.next(foam, null, all, idle = false))
        val frames = (0 until 40).map { prewarm.next(foam, null, all, idle = true) }
        assertEquals(MatterPrewarm.GAP_FRAMES, frames.indexOfFirst { it != null })
    }

    @Test fun materialsAlreadyOnStageCountAsWarmed() {
        val prewarm = MatterPrewarm()
        prewarm.next(foam, FeelMaterial.Gel, all, idle = false)
        assertFalse(FeelMaterial.Gel in prewarm.drain())
    }

    @Test fun onlyAvailableSamplesAreDrawnAndLateOnesAreStillWarmed() {
        val prewarm = MatterPrewarm()
        var ready = setOf(FeelMaterial.Gel)
        assertEquals(listOf(FeelMaterial.Gel), prewarm.drain({ it in ready }).distinct())
        assertFalse(prewarm.needsFrames { it in ready })
        assertFalse(prewarm.done)
        ready = FeelMaterial.entries.toSet()
        assertTrue(prewarm.needsFrames { it in ready })
        assertEquals(FeelMaterial.entries - foam - FeelMaterial.Gel,
            prewarm.drain({ it in ready }).distinct())
        assertTrue(prewarm.done)
    }

    // --- MatterCalm: when the stage is idle enough for a hidden first draw ---

    private fun busy(
        focused: Boolean = true, revealed: Boolean = true, openingFinished: Boolean = true,
        switching: Boolean = false, settling: Boolean = false, pressing: Boolean = false,
        touching: Boolean = false,
    ) = MatterCalm.busy(focused, revealed, openingFinished, switching, settling, pressing, touching)

    @Test fun everyActivityMakesTheStageBusy() {
        assertFalse(busy())
        assertTrue(busy(focused = false))
        assertTrue(busy(revealed = false))
        assertTrue(busy(openingFinished = false))
        assertTrue(busy(switching = true))
        assertTrue(busy(settling = true))
        assertTrue(busy(pressing = true))
        assertTrue(busy(touching = true))
    }

    @Test fun idleOnlyOnceCalmForTheWholeDelayAfterARelease() {
        val calm = MatterCalm()
        // A press, then the release at t = 1 000.
        assertFalse(calm.idle(0L, busy = true, moving = false))
        assertFalse(calm.idle(1_000L, busy = true, moving = false))
        assertFalse(calm.idle(1_016L, busy = false, moving = false))
        assertFalse(calm.idle(1_000L + MatterCalm.CALM_MILLIS - 1L, busy = false, moving = false))
        assertTrue(calm.idle(1_000L + MatterCalm.CALM_MILLIS, busy = false, moving = false))
    }

    @Test fun aRiseARippleOrTheOrbitDefersTheDrawWithoutRestartingTheClock() {
        val calm = MatterCalm()
        assertFalse(calm.idle(0L, busy = true, moving = false))
        // Foam still rising long after the release: never idle while it moves.
        assertFalse(calm.idle(3_000L, busy = false, moving = true))
        // Still at last: the calm already counted since the release.
        assertTrue(calm.idle(3_016L, busy = false, moving = false))
        // A new press restarts the calm from its own release.
        assertFalse(calm.idle(4_000L, busy = true, moving = false))
        assertFalse(calm.idle(4_016L, busy = false, moving = false))
        assertTrue(calm.idle(4_000L + MatterCalm.CALM_MILLIS, busy = false, moving = false))
    }

    @Test fun backToIdleAfterAPressWaitsTheCalmDelayThenTheGap() {
        val prewarm = MatterPrewarm()
        val calm = MatterCalm()
        var now = 0L
        fun frame(busy: Boolean): FeelMaterial? {
            now += 16L
            return prewarm.next(foam, null, all, calm.idle(now, busy, moving = false))
        }
        repeat(10) { assertNull(frame(busy = true)) }
        val released = now
        var first: FeelMaterial? = null
        while (first == null) first = frame(busy = false)
        val waited = now - released
        assertTrue("$waited ms", waited >= MatterCalm.CALM_MILLIS + MatterPrewarm.GAP_FRAMES * 16L)
        assertTrue("$waited ms", waited <= MatterCalm.CALM_MILLIS + (MatterPrewarm.GAP_FRAMES + 2) * 16L)
    }

    // --- MatterWarmFrames: the debug measure in the timing log ---

    @Test fun theWarmMeasureSeesAStallThatLandsFramesAfterTheDraw() {
        val frames = MatterWarmFrames()
        val ms = 1_000_000L
        var t = 0L
        assertNull(frames.frame(t, null))
        // Two draw frames at 16 ms, then the link stalls the second frame after them.
        t += 16 * ms; assertNull(frames.frame(t, FeelMaterial.Gel))
        t += 16 * ms; assertNull(frames.frame(t, FeelMaterial.Gel))
        t += 16 * ms; assertNull(frames.frame(t, null))
        t += 280 * ms; assertNull(frames.frame(t, null))
        repeat(MatterWarmFrames.TAIL_FRAMES - 3) { t += 16 * ms; assertNull(frames.frame(t, null)) }
        t += 16 * ms
        val warm = frames.frame(t, null)!!
        assertEquals(FeelMaterial.Gel, warm.material)
        assertEquals(280L, warm.worstMillis)
        assertEquals(1 + MatterWarmFrames.TAIL_FRAMES, warm.intervals)
        // Then silent until the next warm.
        t += 16 * ms; assertNull(frames.frame(t, null))
    }
}
