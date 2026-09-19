package io.github.sceneview.demo.demos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the animation-physics screen is allowed to call "still running" (#3718).
 *
 * The screen declared `FrameRatePolicy.Continuous()` from a `replaying` flag that started `true` and
 * was lowered only by the Reset button. Measured on the emulator: 878 Filament frames in 15 s on a
 * settled stack of spheres whose captures were identical to the byte, at ~57 fps, indefinitely.
 * Pressing Reset dropped it to 0, which is what identified the flag as the term.
 *
 * A declaration of continuous rendering has to follow motion that exists. These tests pin the two
 * ways that can go wrong: never settling (the defect) and settling too eagerly, which would park the
 * screen mid-bounce — a ball at the apex of its arc is motionless for one frame and is not finished.
 */
class MotionSettleTrackerTest {

    private val settleNanos = 500_000_000L
    private val frame = 16_666_667L

    @Test
    fun `a tracker that has seen nothing yet reports motion`() {
        assertTrue(
            "the first frames must run, or nothing would ever start",
            MotionSettleTracker(settleNanos).isMoving
        )
    }

    @Test
    fun `motion within the window keeps the screen running`() {
        val tracker = MotionSettleTracker(settleNanos)
        var now = 0L
        repeat(60) {
            now += frame
            tracker.update(now, moved = true)
            assertTrue(tracker.isMoving)
        }
    }

    @Test
    fun `one motionless frame is not a settled simulation`() {
        val tracker = MotionSettleTracker(settleNanos)
        tracker.update(frame, moved = true)
        tracker.update(2 * frame, moved = false)

        assertTrue(
            "a ball at the top of its bounce moves 0 for one frame — parking there freezes it",
            tracker.isMoving
        )
    }

    @Test
    fun `stillness for the whole settle window stops the declaration`() {
        val tracker = MotionSettleTracker(settleNanos)
        tracker.update(frame, moved = true)

        var now = frame
        while (now - frame < settleNanos) {
            now += frame
            tracker.update(now, moved = false)
        }

        assertFalse(
            "this is the 878 frames / 15 s defect: without it the screen never stops asking",
            tracker.isMoving
        )
    }

    @Test
    fun `a single body moving again re-arms the declaration`() {
        val tracker = MotionSettleTracker(settleNanos)
        tracker.update(frame, moved = true)
        tracker.update(frame + settleNanos, moved = false)
        assertFalse(tracker.isMoving)

        // The tray is tilted: `PhysicsBody.gravity` clears `isAsleep` and the stack starts rolling.
        tracker.update(frame + settleNanos + frame, moved = true)
        assertTrue("a parked screen has to be able to wake up", tracker.isMoving)
    }

    @Test
    fun `restart re-arms without waiting for a body to move`() {
        val tracker = MotionSettleTracker(settleNanos)
        tracker.update(frame, moved = true)
        tracker.update(frame + settleNanos, moved = false)
        assertFalse(tracker.isMoving)

        tracker.restart()

        assertTrue(
            "Replay / Add 1 / a new slope is motion that has not happened yet, not stillness",
            tracker.isMoving
        )
        // And the window is measured from the next frame, not from the last one seen before it.
        tracker.update(frame + settleNanos + frame, moved = false)
        assertTrue(tracker.isMoving)
    }
}
