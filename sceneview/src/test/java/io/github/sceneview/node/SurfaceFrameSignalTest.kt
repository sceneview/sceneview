package io.github.sceneview.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule a [VideoNode] follows to say whether it still owes the scene a frame (#3108).
 *
 * `player.isPlaying` alone was the obvious answer and it was wrong in one direction, silently: a
 * **seek** or a **frame-step** on a paused player produces exactly one new frame on the node's
 * `SurfaceTexture` with `isPlaying` false from beginning to end. On a render-on-demand scene the
 * loop was correctly parked, nothing was in an error state, and the picture on screen was the frame
 * from *before* the seek — the "frozen image" family this change exists to close, and the one shape
 * a cadence profiler cannot show you, because there is no cadence anomaly to find.
 *
 * A `MediaPlayer` cannot be driven from the JVM, so what is pinned here is the decision itself,
 * which is the whole of the fix: the frames come from the surface, not from the player.
 */
class SurfaceFrameSignalTest {

    @Test
    fun aPlayingVideoHoldsTheCadenceWithoutWaitingForACallback() {
        val signal = SurfaceFrameSignal(requestRender = {})

        repeat(10) {
            assertTrue(
                "a playing video produces frames continuously; reading it from the player keeps " +
                    "the scene at full cadence even on a device whose callback delivery lags " +
                    "behind the decoder",
                signal.isActive(forcedActive = true)
            )
        }
    }

    @Test
    fun aSeekOnAPausedPlayerWakesTheScene() {
        var renderRequests = 0
        val signal = SurfaceFrameSignal(requestRender = { renderRequests++ })

        // The scene is parked and the player is paused: what a `seekTo()` produces is one frame on
        // the SurfaceTexture and nothing else. No transform changed, no animation is playing, no
        // load is in flight — this callback is the only thing that exists to notice it.
        signal.onFrameAvailable()

        assertEquals(
            "the frame must push an invalidation immediately: a parked loop is suspended on the " +
                "snapshot and nothing will come round to ask it anything",
            1,
            renderRequests
        )
        assertTrue(
            "and it must also report active for the tick it wakes, because the callback can land " +
                "at any point in a tick — including after the gate was already asked — and one " +
                "frame must not be lost to that race",
            signal.isActive(forcedActive = false)
        )
    }

    @Test
    fun oneSeekBuysOneTickAndNotAPermanentWakefulness() {
        val signal = SurfaceFrameSignal(requestRender = {})
        signal.onFrameAvailable()

        assertTrue(signal.isActive(forcedActive = false))
        assertFalse(
            "the latch is consumed on read: a seek must hold the scene awake for the tick that " +
                "presents it, then let it park again — a latch that stayed set would turn every " +
                "paused video into a scene that renders forever",
            signal.isActive(forcedActive = false)
        )
    }

    @Test
    fun aPausedPlayerThatProducedNothingLetsTheSceneSettle() {
        val signal = SurfaceFrameSignal(requestRender = {})

        assertFalse(
            "a paused video with no new frame is exactly the case render-on-demand exists for",
            signal.isActive(forcedActive = false)
        )
    }

    @Test
    fun everyFrameStepInARowIsNoticed() {
        var renderRequests = 0
        val signal = SurfaceFrameSignal(requestRender = { renderRequests++ })

        // Frame-stepping: the user taps "next frame" five times, and each tap must reach the
        // screen. A signal that only fired on the transition from "no frame" would show the first
        // step and freeze on it.
        repeat(5) {
            signal.onFrameAvailable()
            assertTrue(signal.isActive(forcedActive = false))
        }

        assertEquals(5, renderRequests)
    }
}
