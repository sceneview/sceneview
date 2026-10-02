package io.github.sceneview.ar

import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ArGuidanceState.Companion.FOUND_HOLD_MS
import io.github.sceneview.ar.ArGuidanceState.Companion.HINT_MIN_SHOWN_MS
import io.github.sceneview.ar.ArGuidanceState.Companion.HINT_STABLE_MS
import io.github.sceneview.ar.ArGuidanceState.Companion.INITIALIZING_DELAY_MS
import io.github.sceneview.ar.ArGuidanceState.Companion.MIN_SHOWN_MS
import io.github.sceneview.ar.ArGuidanceState.Companion.RECOVER_STABLE_MS
import io.github.sceneview.ar.ArGuidanceState.Companion.REENTER_STABLE_MS
import io.github.sceneview.ar.ArGuidanceState.Companion.SCAN_LINGER_MS
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

    private fun ArGuidanceState.at(
        phase: PlacementPhase,
        now: Long,
        placedAt: Long = 0L,
        failure: TrackingFailureReason? = null,
    ) = apply { update(phase, placedAt, failure, now) }.cue

    private fun ArGuidanceState.at(phase: PlaneDiscoveryPhase, now: Long, failure: TrackingFailureReason? = null) =
        apply { update(phase, failure, now) }.cue

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
        // The card just left: a loss must hold before it comes back (no flash on a hiccup).
        assertEquals(ArGuidanceCue.NONE, g.at(PlacementPhase.TRACKING_LOST, 2_000L, 100L))
        assertEquals(REENTER_STABLE_MS, g.nextTransitionDelayMillis(2_000L))
        val back = 2_000L + REENTER_STABLE_MS
        assertEquals(ArGuidanceCue.TRACKING_LIMITED, g.at(PlacementPhase.TRACKING_LOST, back, 100L))
        assertEquals(ArGuidanceCue.RELOCALIZING, g.at(PlacementPhase.RECOVERING, 3_000L, 100L))
        // Recovered: the card leaves once it has been up its minimum time, not as a flash.
        assertEquals(ArGuidanceCue.RELOCALIZING, g.at(PlacementPhase.PLACED, back + MIN_SHOWN_MS - 1, 100L))
        assertEquals(ArGuidanceCue.NONE, g.at(PlacementPhase.PLACED, back + MIN_SHOWN_MS, 100L))
    }

    @Test
    fun `tracking loss during the found beat wins, and the beat does not resume`() {
        val g = ArGuidanceState()
        g.at(PlacementPhase.SCANNING, 0L)
        g.at(PlacementPhase.PLACED, 100L, placedAt = 100L)
        assertEquals(ArGuidanceCue.TRACKING_LIMITED, g.at(PlacementPhase.TRACKING_LOST, 200L, 100L))
        // Back on track at once, but the card holds its minimum time, and no found beat follows.
        assertEquals(ArGuidanceCue.TRACKING_LIMITED, g.at(PlacementPhase.PLACED, 300L, 100L))
        assertEquals(ArGuidanceCue.NONE, g.at(PlacementPhase.PLACED, MIN_SHOWN_MS, 100L))
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

    @Test
    fun `a card due takes the screen at once, whatever the minimum time`() {
        val g = ArGuidanceState()
        assertEquals(ArGuidanceCue.SCAN, g.at(PlacementPhase.SCANNING, 0L))
        assertEquals(ArGuidanceCue.NONE, g.at(PlacementPhase.NO_SURFACE, 100L))
    }

    @Test
    fun `a paused camera must track a moment before the card goes back to scanning`() {
        val g = ArGuidanceState()
        g.at(PlacementPhase.SCANNING, 0L)
        assertEquals(ArGuidanceCue.TRACKING_LIMITED, g.at(PlacementPhase.TRACKING_LOST, 100L))
        assertEquals(ArGuidanceCue.TRACKING_LIMITED, g.at(PlacementPhase.SCANNING, 200L))
        assertEquals(RECOVER_STABLE_MS, g.nextTransitionDelayMillis(200L))
        assertEquals(ArGuidanceCue.SCAN, g.at(PlacementPhase.SCANNING, 200L + RECOVER_STABLE_MS))
    }

    @Test
    fun `the reason chip waits for a steady reason and stays up its minimum time`() {
        val g = ArGuidanceState()
        val dark = TrackingFailureReason.INSUFFICIENT_LIGHT
        g.at(PlacementPhase.SCANNING, 0L)
        g.at(PlacementPhase.TRACKING_LOST, 100L, failure = dark)
        assertEquals(ArTrackingHint.NONE, g.hint)
        assertEquals(HINT_STABLE_MS, g.nextTransitionDelayMillis(100L))
        val shownAt = 100L + HINT_STABLE_MS
        g.at(PlacementPhase.TRACKING_LOST, shownAt, failure = dark)
        assertEquals(ArTrackingHint.TOO_DARK, g.hint)
        // The reason clears right away: the chip still holds its minimum time.
        g.at(PlacementPhase.TRACKING_LOST, shownAt + 100L)
        assertEquals(ArTrackingHint.TOO_DARK, g.hint)
        g.at(PlacementPhase.TRACKING_LOST, shownAt + HINT_MIN_SHOWN_MS)
        assertEquals(ArTrackingHint.NONE, g.hint)
    }

    @Test
    fun `a reason flicker shorter than the debounce never shows a chip`() {
        val g = ArGuidanceState()
        val features = TrackingFailureReason.INSUFFICIENT_FEATURES
        g.at(PlacementPhase.SCANNING, 0L)
        g.at(PlacementPhase.TRACKING_LOST, 100L, failure = TrackingFailureReason.EXCESSIVE_MOTION)
        g.at(PlacementPhase.TRACKING_LOST, 300L, failure = features)
        assertEquals(ArTrackingHint.NONE, g.hint)
        g.at(PlacementPhase.TRACKING_LOST, 300L + HINT_STABLE_MS, failure = features)
        assertEquals(ArTrackingHint.LOW_DETAIL, g.hint)
    }

    @Test
    fun `the start-up card names the reason too`() {
        val g = ArGuidanceState()
        val dark = TrackingFailureReason.INSUFFICIENT_LIGHT
        g.at(PlacementPhase.INITIALIZING, 0L, failure = dark)
        assertEquals(ArGuidanceCue.INITIALIZING, g.at(PlacementPhase.INITIALIZING, HINT_STABLE_MS, failure = dark))
        assertEquals(ArTrackingHint.TOO_DARK, g.hint)
    }

    @Test
    fun `scanning never carries a chip`() {
        val g = ArGuidanceState()
        g.at(PlacementPhase.SCANNING, 0L, failure = TrackingFailureReason.INSUFFICIENT_LIGHT)
        g.at(PlacementPhase.SCANNING, 5_000L, failure = TrackingFailureReason.INSUFFICIENT_LIGHT)
        assertEquals(ArTrackingHint.NONE, g.hint)
    }

    @Test
    fun `a long scan suggests a better spot`() {
        val g = ArGuidanceState()
        g.at(PlacementPhase.SCANNING, 0L)
        assertFalse(g.scanLingering)
        assertEquals(SCAN_LINGER_MS, g.nextTransitionDelayMillis(0L))
        g.at(PlacementPhase.SCANNING, SCAN_LINGER_MS)
        assertTrue(g.scanLingering)
        g.at(PlacementPhase.TRACKING_LOST, SCAN_LINGER_MS + 100L)
        assertFalse(g.scanLingering)
    }

    @Test
    fun `ARCore reasons map to the three chips`() {
        assertEquals(ArTrackingHint.TOO_DARK, ArTrackingHint.from(TrackingFailureReason.INSUFFICIENT_LIGHT))
        assertEquals(ArTrackingHint.TOO_FAST, ArTrackingHint.from(TrackingFailureReason.EXCESSIVE_MOTION))
        assertEquals(ArTrackingHint.LOW_DETAIL, ArTrackingHint.from(TrackingFailureReason.INSUFFICIENT_FEATURES))
        assertEquals(ArTrackingHint.NONE, ArTrackingHint.from(TrackingFailureReason.NONE))
        assertEquals(ArTrackingHint.NONE, ArTrackingHint.from(null))
    }

    @Test
    fun `tracking path - start, scan, found, gone`() {
        val g = ArGuidanceState()
        assertEquals(ArGuidanceCue.NONE, g.at(PlaneDiscoveryPhase.WAITING, 0L))
        assertEquals(ArGuidanceCue.INITIALIZING, g.at(PlaneDiscoveryPhase.WAITING, INITIALIZING_DELAY_MS))
        assertEquals(ArGuidanceCue.SCAN, g.at(PlaneDiscoveryPhase.SILENT, 1_000L))
        assertEquals(ArGuidanceCue.SCAN, g.at(PlaneDiscoveryPhase.HAND_HINT, 4_000L))
        assertEquals(ArGuidanceCue.SURFACE_FOUND, g.at(PlaneDiscoveryPhase.DONE, 5_000L))
        assertEquals(ArGuidanceCue.SURFACE_FOUND, g.at(PlaneDiscoveryPhase.DONE, 5_000L + FOUND_HOLD_MS - 1))
        assertEquals(ArGuidanceCue.NONE, g.at(PlaneDiscoveryPhase.DONE, 5_000L + FOUND_HOLD_MS))
        // Tracking lost later in the session: the card comes back once the loss holds.
        g.at(PlaneDiscoveryPhase.LOST, 10_000L)
        assertEquals(ArGuidanceCue.TRACKING_LIMITED, g.at(PlaneDiscoveryPhase.LOST, 10_000L + REENTER_STABLE_MS))
        assertEquals(ArGuidanceCue.TRACKING_LIMITED, g.at(PlaneDiscoveryPhase.WAITING, 11_000L))
    }

    @Test
    fun `tracking path - the guide's help tip becomes the long-scan line`() {
        val g = ArGuidanceState()
        g.at(PlaneDiscoveryPhase.SILENT, 0L)
        assertFalse(g.scanLingering)
        g.at(PlaneDiscoveryPhase.HELP_OFFERED, 1_000L)
        assertTrue(g.scanLingering)
    }

    @Test
    fun `tracking path - a failure before the first tracked frame is still start-up`() {
        val g = ArGuidanceState()
        val dark = TrackingFailureReason.INSUFFICIENT_LIGHT
        g.at(PlaneDiscoveryPhase.LOST, 0L, dark)
        assertEquals(ArGuidanceCue.INITIALIZING, g.at(PlaneDiscoveryPhase.LOST, HINT_STABLE_MS, dark))
        assertEquals(ArTrackingHint.TOO_DARK, g.hint)
    }

    @Test
    fun `tracking path - a screen that needs no surface gets no found beat`() {
        val discovery = PlaneDiscoveryGuideState()
        val g = ArGuidanceState()
        fun step(now: Long, ready: Boolean, tracking: Boolean): ArGuidanceCue {
            g.update(discovery.update(ready, tracking, true, null, now), null, now)
            return g.cue
        }
        assertEquals(ArGuidanceCue.NONE, step(0L, ready = false, tracking = false))
        assertEquals(ArGuidanceCue.INITIALIZING, step(600L, ready = true, tracking = false))
        // Tracking: nothing to find, so the start-up card simply leaves after its minimum time.
        assertEquals(ArGuidanceCue.INITIALIZING, step(700L, ready = true, tracking = true))
        assertEquals(ArGuidanceCue.NONE, step(600L + MIN_SHOWN_MS, ready = true, tracking = true))
    }

    @Test
    fun `tracking path - a plane found while scanning plays the beat`() {
        val discovery = PlaneDiscoveryGuideState()
        val g = ArGuidanceState()
        fun step(now: Long, plane: Boolean): ArGuidanceCue {
            g.update(discovery.update(true, true, plane, null, now), null, now)
            return g.cue
        }
        assertEquals(ArGuidanceCue.SCAN, step(0L, plane = false))
        assertEquals(ArGuidanceCue.SURFACE_FOUND, step(2_000L, plane = true))
        assertEquals(ArGuidanceCue.NONE, step(2_000L + FOUND_HOLD_MS, plane = true))
    }
}
