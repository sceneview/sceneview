package io.github.sceneview.ar

import io.github.sceneview.haptic.ARHapticEvent
import io.github.sceneview.ar.ARHapticTransitions.Snapshot
import io.github.sceneview.ar.PlacementPhase.ADJUSTING
import io.github.sceneview.ar.PlacementPhase.INITIALIZING
import io.github.sceneview.ar.PlacementPhase.NO_SURFACE
import io.github.sceneview.ar.PlacementPhase.PLACED
import io.github.sceneview.ar.PlacementPhase.RECOVERING
import io.github.sceneview.ar.PlacementPhase.RECOVERY_FAILED
import io.github.sceneview.ar.PlacementPhase.SCANNING
import io.github.sceneview.ar.PlacementPhase.TRACKING_LOST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ARHapticTransitionsTest {

    private val t = ARHapticTransitions()
    private var clock = 0L

    /** Feeds [snapshot] one second after the previous one (never throttled). */
    private fun feed(phase: PlacementPhase, placements: Int = 0, selected: Boolean = false): List<ARHapticEvent> {
        clock += 1_000
        return t.next(Snapshot(phase, placements, selected), clock)
    }

    @Test
    fun firstSnapshot_onlyRecords() {
        assertEquals(emptyList<ARHapticEvent>(), feed(PLACED, placements = 1, selected = true))
    }

    @Test
    fun placement_playsPlacedOnce_andSwallowsItsSelection() {
        feed(INITIALIZING)
        feed(SCANNING)
        assertEquals(listOf(ARHapticEvent.Placed), feed(PLACED, placements = 1, selected = true))
        assertEquals(emptyList<ARHapticEvent>(), feed(PLACED, placements = 1, selected = true))
    }

    /** Drives the real state machine frame by frame and feeds every phase it publishes. */
    private fun AutoPlacementState.frame(ms: Long, tracking: Boolean): List<ARHapticEvent> {
        onFrame(FrameInput(ms, tracking, surfaceAvailable = false))
        clock += 1_000
        return t.next(Snapshot(phase, placementsCreated, isSelected), clock)
    }

    @Test
    fun trackingLostAtSessionStart_isSilent() {
        // ARCore delivers untracked frames before the first tracked one: not a loss the user
        // caused. The state machine keeps them INITIALIZING (the coaching overlay's cue source
        // too), so neither a warning haptic nor a "tracking limited" cue plays.
        val state = AutoPlacementState()
        t.next(Snapshot(state.phase, state.placementsCreated, state.isSelected), clock)
        repeat(10) { assertEquals(emptyList<ARHapticEvent>(), state.frame(it * 16L, tracking = false)) }
        assertEquals(INITIALIZING, state.phase)
        assertEquals(emptyList<ARHapticEvent>(), state.frame(160L, tracking = true))
        assertEquals(listOf(ARHapticEvent.TrackingLost), state.frame(176L, tracking = false))
    }

    @Test
    fun startUpFrames_ofAReenteredSession_areSilentToo() {
        // One state and one ARHapticFeedback survive a chooser/camera round trip: tracking
        // was established in the first session, yet the next session's start-up is silent.
        val state = AutoPlacementState()
        t.next(Snapshot(state.phase, state.placementsCreated, state.isSelected), clock)
        state.frame(0L, tracking = true)
        state.dismiss()
        state.selectModel()
        state.requestPlacement()
        repeat(5) { assertEquals(emptyList<ARHapticEvent>(), state.frame(100L + it * 16L, tracking = false)) }
        assertEquals(INITIALIZING, state.phase)
    }

    @Test
    fun trackingLost_afterTrackingWasEstablished_plays() {
        feed(INITIALIZING)
        feed(SCANNING)
        assertEquals(listOf(ARHapticEvent.TrackingLost), feed(TRACKING_LOST))
    }

    @Test
    fun recovery_playsRecovered_fromEveryLostPhase() {
        feed(SCANNING)
        feed(PLACED, placements = 1, selected = true)
        for (lost in listOf(TRACKING_LOST, RECOVERING, RECOVERY_FAILED)) {
            feed(lost, placements = 1, selected = true)
            assertEquals("$lost", listOf(ARHapticEvent.Recovered), feed(PLACED, placements = 1, selected = true))
        }
    }

    @Test
    fun trackingLostWhileScanning_thenScanningAgain_isNotARecovery() {
        feed(SCANNING)
        feed(TRACKING_LOST)
        assertEquals(emptyList<ARHapticEvent>(), feed(SCANNING))
    }

    @Test
    fun helpCards_playHelpNeeded() {
        feed(SCANNING)
        assertEquals(listOf(ARHapticEvent.HelpNeeded), feed(NO_SURFACE))
        feed(PLACED, placements = 1, selected = true)
        feed(RECOVERING, placements = 1, selected = true)
        assertEquals(listOf(ARHapticEvent.HelpNeeded), feed(RECOVERY_FAILED, placements = 1, selected = true))
    }

    @Test
    fun tapOnTheStandingObject_playsSelected() {
        feed(SCANNING)
        feed(PLACED, placements = 1, selected = true)
        feed(PLACED, placements = 1, selected = false)
        assertEquals(listOf(ARHapticEvent.Selected), feed(PLACED, placements = 1, selected = true))
    }

    @Test
    fun adjustingAndBack_isSilent() {
        feed(SCANNING)
        feed(PLACED, placements = 1, selected = true)
        assertEquals(emptyList<ARHapticEvent>(), feed(ADJUSTING, placements = 1, selected = true))
        assertEquals(emptyList<ARHapticEvent>(), feed(PLACED, placements = 1, selected = true))
    }

    @Test
    fun sameEvent_within400ms_isThrottled() {
        assertTrue(t.accept(ARHapticEvent.ScaleSnapped, 1_000))
        assertFalse(t.accept(ARHapticEvent.ScaleSnapped, 1_399))
        assertTrue("another event is independent", t.accept(ARHapticEvent.LimitReached, 1_399))
        assertTrue(t.accept(ARHapticEvent.ScaleSnapped, 1_400))
    }

    @Test
    fun flappingTracking_isThrottled() {
        feed(SCANNING)
        feed(PLACED, placements = 1, selected = true)
        clock += 1_000
        assertEquals(listOf(ARHapticEvent.TrackingLost), t.next(Snapshot(TRACKING_LOST, 1, true), clock))
        t.next(Snapshot(PLACED, 1, true), clock + 50)
        assertEquals(emptyList<ARHapticEvent>(), t.next(Snapshot(TRACKING_LOST, 1, true), clock + 100))
    }
}
