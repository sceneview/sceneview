package io.github.sceneview.ar

import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ARTrackingStateLogTest {

    private val second = 1_000_000_000L

    /** One frame every 33 ms from [fromNanos], for [frames] frames; the lines that came out. */
    private fun ARTrackingStateLog.frames(
        state: TrackingState,
        reason: TrackingFailureReason,
        fromNanos: Long,
        frames: Int,
    ): List<String> = (0 until frames).mapNotNull { index ->
        transition(state, reason, fromNanos + index * 33_000_000L)
    }

    @Test
    fun `the first frame is logged, a camera that never tracks included`() {
        val log = ARTrackingStateLog()

        assertEquals(
            "AR tracking: state=PAUSED reason=NONE",
            log.transition(TrackingState.PAUSED, TrackingFailureReason.NONE, 5 * second)
        )
    }

    @Test
    fun `an unchanged state is logged once, not once per frame`() {
        val log = ARTrackingStateLog()

        // 300 frames is 9.9 s: one line, the first.
        val lines = log.frames(TrackingState.PAUSED, TrackingFailureReason.NONE, 0L, frames = 300)

        assertEquals(listOf("AR tracking: state=PAUSED reason=NONE"), lines)
    }

    @Test
    fun `a reason that changes under the same state is logged`() {
        val log = ARTrackingStateLog()
        log.transition(TrackingState.PAUSED, TrackingFailureReason.NONE, 0L)

        assertEquals(
            "AR tracking: state=PAUSED reason=CAMERA_UNAVAILABLE",
            log.transition(TrackingState.PAUSED, TrackingFailureReason.CAMERA_UNAVAILABLE, second)
        )
        assertEquals(
            "AR tracking: state=PAUSED reason=BAD_STATE",
            log.transition(TrackingState.PAUSED, TrackingFailureReason.BAD_STATE, 2 * second)
        )
    }

    @Test
    fun `reaching tracking and losing it again are both logged`() {
        val log = ARTrackingStateLog()
        log.transition(TrackingState.PAUSED, TrackingFailureReason.INSUFFICIENT_FEATURES, 0L)

        assertEquals(
            "AR tracking: state=TRACKING reason=NONE",
            log.transition(TrackingState.TRACKING, TrackingFailureReason.NONE, second)
        )
        assertNull(log.transition(TrackingState.TRACKING, TrackingFailureReason.NONE, 2 * second))
        assertEquals(
            "AR tracking: state=PAUSED reason=EXCESSIVE_MOTION",
            log.transition(TrackingState.PAUSED, TrackingFailureReason.EXCESSIVE_MOTION, 3 * second)
        )
    }

    @Test
    fun `a camera that stays out of tracking says so again every ten seconds, with how long`() {
        val log = ARTrackingStateLog()

        // 35 s of display frames in the same state, from an arbitrary time on the injected clock.
        // The clock is the render loop's, not the camera image's: a session whose images stopped
        // coming would freeze the latter, and that is a state the repeat has to keep reporting.
        val lines =
            log.frames(TrackingState.PAUSED, TrackingFailureReason.NONE, 100 * second, frames = 1061)

        assertEquals(
            listOf(
                "AR tracking: state=PAUSED reason=NONE",
                "AR tracking: state=PAUSED reason=NONE for=10s",
                "AR tracking: state=PAUSED reason=NONE for=20s",
                "AR tracking: state=PAUSED reason=NONE for=30s",
            ),
            lines
        )
    }

    @Test
    fun `a tracking camera repeats nothing, however long it lasts`() {
        val log = ARTrackingStateLog()

        // Ten minutes.
        val lines =
            log.frames(TrackingState.TRACKING, TrackingFailureReason.NONE, 0L, frames = 18_200)

        assertEquals(listOf("AR tracking: state=TRACKING reason=NONE"), lines)
    }

    @Test
    fun `the duration counts from the transition, so lost tracking reads apart from never tracked`() {
        val log = ARTrackingStateLog()
        log.transition(TrackingState.PAUSED, TrackingFailureReason.NONE, 0L)
        log.transition(TrackingState.TRACKING, TrackingFailureReason.NONE, second)
        log.transition(TrackingState.PAUSED, TrackingFailureReason.NONE, 60 * second)

        assertNull(log.transition(TrackingState.PAUSED, TrackingFailureReason.NONE, 69 * second))
        assertEquals(
            "AR tracking: state=PAUSED reason=NONE for=12s",
            log.transition(TrackingState.PAUSED, TrackingFailureReason.NONE, 72 * second)
        )
    }

    @Test
    fun `a clock that goes back starts the count over instead of reporting a stale duration`() {
        val log = ARTrackingStateLog()
        log.transition(TrackingState.PAUSED, TrackingFailureReason.NONE, 500 * second)

        // Not the clock the count started on: no line for it, and the count restarts from it.
        assertNull(log.transition(TrackingState.PAUSED, TrackingFailureReason.NONE, 2 * second))
        assertNull(log.transition(TrackingState.PAUSED, TrackingFailureReason.NONE, 11 * second))
        assertEquals(
            "AR tracking: state=PAUSED reason=NONE for=10s",
            log.transition(TrackingState.PAUSED, TrackingFailureReason.NONE, 12 * second)
        )
    }

    @Test
    fun `a recreated session starts the log over, in the same state included`() {
        val log = ARTrackingStateLog()
        log.transition(TrackingState.PAUSED, TrackingFailureReason.NONE, 0L, sessionId = 1)
        assertEquals(
            "AR tracking: state=PAUSED reason=NONE for=10s",
            log.transition(TrackingState.PAUSED, TrackingFailureReason.NONE, 10 * second, sessionId = 1)
        )

        // Same state, same reason, another session: its first frame is a line of its own…
        assertEquals(
            "AR tracking: state=PAUSED reason=NONE",
            log.transition(TrackingState.PAUSED, TrackingFailureReason.NONE, 15 * second, sessionId = 2)
        )
        // …and `for=` counts from that frame, not from the session before it.
        assertNull(
            log.transition(TrackingState.PAUSED, TrackingFailureReason.NONE, 24 * second, sessionId = 2)
        )
        assertEquals(
            "AR tracking: state=PAUSED reason=NONE for=10s",
            log.transition(TrackingState.PAUSED, TrackingFailureReason.NONE, 25 * second, sessionId = 2)
        )
    }

    @Test
    fun `a recreated session that tracks at once says so, the one before it tracking too`() {
        val log = ARTrackingStateLog()
        log.transition(TrackingState.TRACKING, TrackingFailureReason.NONE, 0L, sessionId = 1)
        assertNull(log.transition(TrackingState.TRACKING, TrackingFailureReason.NONE, second, sessionId = 1))

        assertEquals(
            "AR tracking: state=TRACKING reason=NONE",
            log.transition(TrackingState.TRACKING, TrackingFailureReason.NONE, 2 * second, sessionId = 2)
        )
    }
}
