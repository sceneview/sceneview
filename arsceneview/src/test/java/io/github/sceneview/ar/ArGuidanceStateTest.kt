package io.github.sceneview.ar

import io.github.sceneview.ar.ArGuidanceState.Companion.FOUND_HOLD_MS
import io.github.sceneview.ar.ArGuidanceState.Companion.INITIALIZING_DELAY_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coaching cue, pinned on the JVM: which glyph the overlay shows for every placement
 * phase, when the "surface found" beat plays (and when it must not), and the event clock
 * that lets the driver sleep instead of polling.
 */
class ArGuidanceStateTest {

    private fun ArGuidanceState.at(phase: PlacementPhase, now: Long, placedAt: Long = 0L) =
        apply { update(phase, placedAt, now) }.cue

    @Test
    fun `a fast start shows nothing, a slow one shows the initializing glyph`() {
        val g = ArGuidanceState()
        assertEquals(ArGuidanceCue.NONE, g.at(PlacementPhase.INITIALIZING, 1_000L))
        assertEquals(INITIALIZING_DELAY_MS, g.nextTransitionDelayMillis(1_000L))
        assertEquals(ArGuidanceCue.NONE, g.at(PlacementPhase.INITIALIZING, 1_000L + INITIALIZING_DELAY_MS - 1))
        assertEquals(ArGuidanceCue.INITIALIZING, g.at(PlacementPhase.INITIALIZING, 1_000L + INITIALIZING_DELAY_MS))
        assertTrue(g.isCoaching)
        // Nothing left to wait for on the clock.
        assertNull(g.nextTransitionDelayMillis(1_000L + INITIALIZING_DELAY_MS))
    }

    @Test
    fun `scanning shows the sweep and hides the chrome`() {
        val g = ArGuidanceState(PlacementSurface.WALL)
        assertEquals(ArGuidanceCue.SCAN, g.at(PlacementPhase.SCANNING, 0L))
        assertTrue(g.isCoaching)
        assertEquals(PlacementSurface.WALL, g.surface)
    }

    @Test
    fun `a new placement plays the found beat, then clears`() {
        val g = ArGuidanceState()
        g.at(PlacementPhase.SCANNING, 0L)
        assertEquals(ArGuidanceCue.SURFACE_FOUND, g.at(PlacementPhase.PLACED, 1_000L, placedAt = 777L))
        assertEquals(FOUND_HOLD_MS, g.nextTransitionDelayMillis(1_000L))
        assertEquals(ArGuidanceCue.SURFACE_FOUND, g.at(PlacementPhase.ADJUSTING, 1_000L + FOUND_HOLD_MS - 1, 777L))
        assertEquals(ArGuidanceCue.NONE, g.at(PlacementPhase.PLACED, 1_000L + FOUND_HOLD_MS, 777L))
        assertFalse(g.isCoaching)
        assertNull(g.nextTransitionDelayMillis(1_000L + FOUND_HOLD_MS))
    }

    @Test
    fun `a placement that already stood when coaching started gets no found beat`() {
        val g = ArGuidanceState()
        assertEquals(ArGuidanceCue.NONE, g.at(PlacementPhase.PLACED, 5_000L, placedAt = 42L))
        assertNull(g.nextTransitionDelayMillis(5_000L))
    }

    @Test
    fun `re-tracking after a loss is not a new placement`() {
        val g = ArGuidanceState()
        g.at(PlacementPhase.SCANNING, 0L)
        g.at(PlacementPhase.PLACED, 100L, placedAt = 100L)
        g.at(PlacementPhase.PLACED, 100L + FOUND_HOLD_MS, placedAt = 100L)
        assertEquals(ArGuidanceCue.TRACKING_LIMITED, g.at(PlacementPhase.TRACKING_LOST, 2_000L, 100L))
        assertEquals(ArGuidanceCue.RELOCALIZING, g.at(PlacementPhase.RECOVERING, 3_000L, 100L))
        assertEquals(ArGuidanceCue.NONE, g.at(PlacementPhase.PLACED, 4_000L, 100L))
    }

    @Test
    fun `tracking loss during the found beat wins, and the beat does not resume`() {
        val g = ArGuidanceState()
        g.at(PlacementPhase.SCANNING, 0L)
        g.at(PlacementPhase.PLACED, 100L, placedAt = 100L)
        assertEquals(ArGuidanceCue.TRACKING_LIMITED, g.at(PlacementPhase.TRACKING_LOST, 200L, 100L))
        assertEquals(ArGuidanceCue.NONE, g.at(PlacementPhase.PLACED, 300L, 100L))
    }

    @Test
    fun `reset then place again plays a second found beat`() {
        val g = ArGuidanceState()
        g.at(PlacementPhase.SCANNING, 0L)
        g.at(PlacementPhase.PLACED, 100L, placedAt = 100L)
        g.at(PlacementPhase.PLACED, 1_000L, placedAt = 100L)
        assertEquals(ArGuidanceCue.SCAN, g.at(PlacementPhase.SCANNING, 2_000L, placedAt = 0L))
        assertEquals(ArGuidanceCue.SURFACE_FOUND, g.at(PlacementPhase.PLACED, 3_000L, placedAt = 3_000L))
    }

    @Test
    fun `states with a card keep the overlay silent`() {
        listOf(
            PlacementPhase.NO_SURFACE,
            PlacementPhase.RECOVERY_FAILED,
            PlacementPhase.CAMERA_ERROR,
        ).forEach { phase ->
            val g = ArGuidanceState()
            assertEquals(phase.name, ArGuidanceCue.NONE, g.at(phase, 10_000L))
            assertFalse(g.isCoaching)
        }
    }

    @Test
    fun `every phase maps to a cue`() {
        PlacementPhase.entries.forEach { phase ->
            ArGuidanceState().update(phase, 0L, 10_000L)
        }
    }

    @Test
    fun `the clock never asks for a zero-delay spin`() {
        val g = ArGuidanceState()
        g.at(PlacementPhase.INITIALIZING, 0L)
        assertEquals(ArGuidanceState.MIN_DELAY_MS, g.nextTransitionDelayMillis(10_000L))
    }

    @Test
    fun `the state machine and the cue agree on a real start`() {
        val placement = AutoPlacementState()
        val g = ArGuidanceState()
        placement.requestPlacement()
        fun step(now: Long, tracking: Boolean, surface: Boolean): ArGuidanceCue {
            placement.onFrame(FrameInput(now, tracking, surface))
            g.update(placement.phase, placement.placedAtMillis, now)
            return g.cue
        }
        // Untracked start-up frames: never "paused", and the initializing glyph after 500 ms.
        assertEquals(ArGuidanceCue.NONE, step(0L, tracking = false, surface = false))
        assertEquals(ArGuidanceCue.INITIALIZING, step(600L, tracking = false, surface = false))
        assertEquals(ArGuidanceCue.SCAN, step(700L, tracking = true, surface = false))
        assertEquals(ArGuidanceCue.SURFACE_FOUND, step(900L, tracking = true, surface = true))
        assertEquals(ArGuidanceCue.NONE, step(900L + FOUND_HOLD_MS, tracking = true, surface = true))
    }
}
