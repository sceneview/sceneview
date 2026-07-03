package io.github.sceneview.ar

import com.google.ar.core.TrackingFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless truth-table tests for the [PlaneDiscoveryGuideState] onboarding state machine
 * (#2241 — port of the ARCore Elements `PlaneDiscoveryGuide` UX).
 *
 * The state machine takes the caller's clock on every [PlaneDiscoveryGuideState.update], so
 * every timed transition (3 s hand hint, 8 s help, 750 ms fade-out) is driven here with a
 * fake monotonic clock — no ARCore session, no Compose runtime idling, no real time.
 */
class PlaneDiscoveryGuideStateTest {

    /** Arbitrary non-zero epoch — transitions must depend on deltas, not absolute values. */
    private val t0 = 10_000L

    private fun PlaneDiscoveryGuideState.updateAt(
        nowMillis: Long,
        cameraReady: Boolean = true,
        isTracking: Boolean = true,
        anyPlaneTracked: Boolean = false,
        trackingFailureReason: TrackingFailureReason? = null,
    ) = update(cameraReady, isTracking, anyPlaneTracked, trackingFailureReason, nowMillis)

    // ── Initial / waiting ────────────────────────────────────────────────────────────────

    @Test
    fun `initial phase is WAITING`() {
        assertEquals(PlaneDiscoveryPhase.WAITING, PlaneDiscoveryGuideState().phase)
    }

    @Test
    fun `camera not ready stays WAITING regardless of elapsed time`() {
        val state = PlaneDiscoveryGuideState()
        assertEquals(PlaneDiscoveryPhase.WAITING, state.updateAt(t0, cameraReady = false))
        assertEquals(
            PlaneDiscoveryPhase.WAITING,
            state.updateAt(t0 + 60_000, cameraReady = false)
        )
    }

    @Test
    fun `tracking pause without a failure reason resets to WAITING`() {
        val state = PlaneDiscoveryGuideState()
        state.updateAt(t0)
        state.updateAt(t0 + 4_000) // HAND_HINT
        assertEquals(PlaneDiscoveryPhase.WAITING, state.updateAt(t0 + 5_000, isTracking = false))
        // Recovery restarts the ramp from zero: 2 s after recovery still SILENT.
        assertEquals(PlaneDiscoveryPhase.SILENT, state.updateAt(t0 + 7_000))
        assertEquals(PlaneDiscoveryPhase.SILENT, state.updateAt(t0 + 9_999))
        assertEquals(PlaneDiscoveryPhase.HAND_HINT, state.updateAt(t0 + 10_000))
    }

    // ── Timed onboarding ramp ────────────────────────────────────────────────────────────

    @Test
    fun `silent for the first 3 seconds of tracking`() {
        val state = PlaneDiscoveryGuideState()
        assertEquals(PlaneDiscoveryPhase.SILENT, state.updateAt(t0))
        assertEquals(PlaneDiscoveryPhase.SILENT, state.updateAt(t0 + 2_999))
    }

    @Test
    fun `hand hint appears at 3 seconds`() {
        val state = PlaneDiscoveryGuideState()
        state.updateAt(t0)
        assertEquals(PlaneDiscoveryPhase.HAND_HINT, state.updateAt(t0 + 3_000))
        assertEquals(PlaneDiscoveryPhase.HAND_HINT, state.updateAt(t0 + 7_999))
    }

    @Test
    fun `help offered at 8 seconds`() {
        val state = PlaneDiscoveryGuideState()
        state.updateAt(t0)
        assertEquals(PlaneDiscoveryPhase.HELP_OFFERED, state.updateAt(t0 + 8_000))
        assertEquals(PlaneDiscoveryPhase.HELP_OFFERED, state.updateAt(t0 + 60_000))
    }

    @Test
    fun `the ramp clock starts at first tracking update not at construction`() {
        val state = PlaneDiscoveryGuideState()
        state.updateAt(t0, cameraReady = false)
        // Camera becomes ready at t0 + 10s — hint fires 3 s after THAT.
        assertEquals(PlaneDiscoveryPhase.SILENT, state.updateAt(t0 + 10_000))
        assertEquals(PlaneDiscoveryPhase.SILENT, state.updateAt(t0 + 12_999))
        assertEquals(PlaneDiscoveryPhase.HAND_HINT, state.updateAt(t0 + 13_000))
    }

    @Test
    fun `custom durations are honored`() {
        val state = PlaneDiscoveryGuideState(
            PlaneDiscoveryGuideDurations(
                handHintAfterMs = 1_000L,
                helpAfterMs = 2_000L,
                fadeOutMs = 100L,
            )
        )
        state.updateAt(t0)
        assertEquals(PlaneDiscoveryPhase.SILENT, state.updateAt(t0 + 999))
        assertEquals(PlaneDiscoveryPhase.HAND_HINT, state.updateAt(t0 + 1_000))
        assertEquals(PlaneDiscoveryPhase.HELP_OFFERED, state.updateAt(t0 + 2_000))
        state.updateAt(t0 + 2_100, anyPlaneTracked = true) // FADING_OUT
        assertEquals(
            PlaneDiscoveryPhase.DONE,
            state.updateAt(t0 + 2_200, anyPlaneTracked = true)
        )
    }

    // ── Plane found → fade-out → DONE ────────────────────────────────────────────────────

    @Test
    fun `plane found while guide visible fades out for 750ms then latches DONE`() {
        val state = PlaneDiscoveryGuideState()
        state.updateAt(t0)
        state.updateAt(t0 + 4_000) // HAND_HINT
        assertEquals(
            PlaneDiscoveryPhase.FADING_OUT,
            state.updateAt(t0 + 5_000, anyPlaneTracked = true)
        )
        assertEquals(
            PlaneDiscoveryPhase.FADING_OUT,
            state.updateAt(t0 + 5_749, anyPlaneTracked = true)
        )
        assertFalse(state.isDone)
        assertEquals(
            PlaneDiscoveryPhase.DONE,
            state.updateAt(t0 + 5_750, anyPlaneTracked = true)
        )
        assertTrue(state.isDone)
    }

    @Test
    fun `plane found while silent skips the fade and latches DONE immediately`() {
        val state = PlaneDiscoveryGuideState()
        state.updateAt(t0)
        assertEquals(
            PlaneDiscoveryPhase.DONE,
            state.updateAt(t0 + 1_000, anyPlaneTracked = true)
        )
        assertTrue(state.isDone)
    }

    @Test
    fun `plane already tracked on the very first update latches DONE without showing anything`() {
        val state = PlaneDiscoveryGuideState()
        assertEquals(PlaneDiscoveryPhase.DONE, state.updateAt(t0, anyPlaneTracked = true))
        assertTrue(state.isDone)
    }

    @Test
    fun `DONE is latched - losing the plane later never re-onboards`() {
        val state = PlaneDiscoveryGuideState()
        state.updateAt(t0, anyPlaneTracked = true) // DONE
        assertEquals(PlaneDiscoveryPhase.DONE, state.updateAt(t0 + 1_000))
        assertEquals(PlaneDiscoveryPhase.DONE, state.updateAt(t0 + 60_000))
    }

    @Test
    fun `plane lost mid-fade returns to the ramp with a restarted clock`() {
        val state = PlaneDiscoveryGuideState()
        state.updateAt(t0)
        state.updateAt(t0 + 4_000) // HAND_HINT
        state.updateAt(t0 + 5_000, anyPlaneTracked = true) // FADING_OUT
        // Plane drops out before the fade completes — not DONE, ramp restarts from zero.
        assertEquals(PlaneDiscoveryPhase.SILENT, state.updateAt(t0 + 5_500))
        assertFalse(state.isDone)
        assertEquals(PlaneDiscoveryPhase.HAND_HINT, state.updateAt(t0 + 5_500 + 3_000))
    }

    // ── Tracking lost ────────────────────────────────────────────────────────────────────

    @Test
    fun `tracking failure shows LOST and resets the onboarding clock`() {
        val state = PlaneDiscoveryGuideState()
        state.updateAt(t0)
        state.updateAt(t0 + 4_000) // HAND_HINT
        assertEquals(
            PlaneDiscoveryPhase.LOST,
            state.updateAt(
                t0 + 5_000,
                isTracking = false,
                trackingFailureReason = TrackingFailureReason.EXCESSIVE_MOTION,
            )
        )
        // Recovery restarts the silent window from zero (ARCore Elements timer reset).
        assertEquals(PlaneDiscoveryPhase.SILENT, state.updateAt(t0 + 6_000))
        assertEquals(PlaneDiscoveryPhase.SILENT, state.updateAt(t0 + 8_999))
        assertEquals(PlaneDiscoveryPhase.HAND_HINT, state.updateAt(t0 + 9_000))
    }

    @Test
    fun `every actionable failure reason maps to LOST`() {
        listOf(
            TrackingFailureReason.BAD_STATE,
            TrackingFailureReason.INSUFFICIENT_LIGHT,
            TrackingFailureReason.EXCESSIVE_MOTION,
            TrackingFailureReason.INSUFFICIENT_FEATURES,
            TrackingFailureReason.CAMERA_UNAVAILABLE,
        ).forEach { reason ->
            val state = PlaneDiscoveryGuideState()
            assertEquals(
                "reason=$reason",
                PlaneDiscoveryPhase.LOST,
                state.updateAt(t0, isTracking = false, trackingFailureReason = reason),
            )
        }
    }

    @Test
    fun `failure reason NONE is not a failure`() {
        val state = PlaneDiscoveryGuideState()
        assertEquals(
            PlaneDiscoveryPhase.WAITING,
            state.updateAt(
                t0,
                isTracking = false,
                trackingFailureReason = TrackingFailureReason.NONE,
            )
        )
    }

    @Test
    fun `LOST still surfaces after DONE and recovery returns to DONE not to onboarding`() {
        val state = PlaneDiscoveryGuideState()
        state.updateAt(t0, anyPlaneTracked = true) // DONE
        assertEquals(
            PlaneDiscoveryPhase.LOST,
            state.updateAt(
                t0 + 1_000,
                isTracking = false,
                trackingFailureReason = TrackingFailureReason.INSUFFICIENT_LIGHT,
            )
        )
        assertEquals(PlaneDiscoveryPhase.DONE, state.updateAt(t0 + 2_000))
        assertTrue(state.isDone)
    }

    // ── nextTransitionDelayMillis — the event-driven scheduler contract ─────────────────

    @Test
    fun `SILENT schedules a wake-up at the hand-hint deadline`() {
        val state = PlaneDiscoveryGuideState()
        state.updateAt(t0)
        assertEquals(3_000L, state.nextTransitionDelayMillis(t0))
        assertEquals(1_000L, state.nextTransitionDelayMillis(t0 + 2_000))
    }

    @Test
    fun `HAND_HINT schedules a wake-up at the help deadline`() {
        val state = PlaneDiscoveryGuideState()
        state.updateAt(t0)
        state.updateAt(t0 + 3_000)
        assertEquals(5_000L, state.nextTransitionDelayMillis(t0 + 3_000))
    }

    @Test
    fun `FADING_OUT schedules a wake-up at the fade deadline`() {
        val state = PlaneDiscoveryGuideState()
        state.updateAt(t0)
        state.updateAt(t0 + 4_000) // HAND_HINT
        state.updateAt(t0 + 5_000, anyPlaneTracked = true) // FADING_OUT
        assertEquals(750L, state.nextTransitionDelayMillis(t0 + 5_000))
    }

    @Test
    fun `phases without a timed transition schedule nothing`() {
        val waiting = PlaneDiscoveryGuideState()
        waiting.updateAt(t0, cameraReady = false)
        assertNull(waiting.nextTransitionDelayMillis(t0))

        val helpOffered = PlaneDiscoveryGuideState()
        helpOffered.updateAt(t0)
        helpOffered.updateAt(t0 + 8_000)
        assertNull(helpOffered.nextTransitionDelayMillis(t0 + 8_000))

        val lost = PlaneDiscoveryGuideState()
        lost.updateAt(
            t0, isTracking = false,
            trackingFailureReason = TrackingFailureReason.BAD_STATE,
        )
        assertNull(lost.nextTransitionDelayMillis(t0))

        val done = PlaneDiscoveryGuideState()
        done.updateAt(t0, anyPlaneTracked = true)
        assertNull(done.nextTransitionDelayMillis(t0))
    }

    @Test
    fun `scheduled delay is floored so a late clock can never produce a zero-delay spin`() {
        val state = PlaneDiscoveryGuideState()
        state.updateAt(t0)
        // Ask AFTER the deadline already passed — must still be a positive delay.
        val delay = state.nextTransitionDelayMillis(t0 + 10_000)
        assertTrue("expected positive floor, got $delay", delay != null && delay > 0)
    }
}
