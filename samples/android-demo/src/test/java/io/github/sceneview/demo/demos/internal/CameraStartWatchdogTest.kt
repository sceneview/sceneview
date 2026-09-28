package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraStartWatchdogTest {

    @Test
    fun `a frame silences the watchdog at any time`() {
        for (waited in listOf(0L, CAMERA_START_TIMEOUT_MS, 60_000L)) {
            assertEquals(
                CameraStartAction.IDLE,
                cameraStartAction(waited, frameReceived = true, restartsDone = 0, arBlocked = false),
            )
        }
    }

    @Test
    fun `a blocked AR session is never restarted`() {
        assertEquals(
            CameraStartAction.IDLE,
            cameraStartAction(60_000L, frameReceived = false, restartsDone = 0, arBlocked = true),
        )
    }

    @Test
    fun `waits inside the start-up window`() {
        assertEquals(
            CameraStartAction.WAIT,
            cameraStartAction(0L, frameReceived = false, restartsDone = 0, arBlocked = false),
        )
        assertEquals(
            CameraStartAction.WAIT,
            cameraStartAction(
                CAMERA_START_TIMEOUT_MS - 1,
                frameReceived = false,
                restartsDone = 0,
                arBlocked = false,
            ),
        )
    }

    @Test
    fun `restarts once the window is spent`() {
        assertEquals(
            CameraStartAction.RESTART_SESSION,
            cameraStartAction(
                CAMERA_START_TIMEOUT_MS,
                frameReceived = false,
                restartsDone = 0,
                arBlocked = false,
            ),
        )
    }

    @Test
    fun `gives up after the last restart`() {
        assertEquals(
            CameraStartAction.RESTART_SESSION,
            cameraStartAction(
                CAMERA_START_TIMEOUT_MS,
                frameReceived = false,
                restartsDone = CAMERA_START_MAX_RESTARTS - 1,
                arBlocked = false,
            ),
        )
        assertEquals(
            CameraStartAction.GIVE_UP,
            cameraStartAction(
                CAMERA_START_TIMEOUT_MS,
                frameReceived = false,
                restartsDone = CAMERA_START_MAX_RESTARTS,
                arBlocked = false,
            ),
        )
    }

    @Test
    fun `a stuck screen ends on the failure card, never on a spinner`() {
        // Replays the watchdog loop as the demo runs it: one tick per second, the clock
        // reset on every restart. A camera that never starts must reach GIVE_UP in bounded
        // time, after exactly the allowed number of restarts.
        var restarts = 0
        var waited = 0L
        var elapsed = 0L
        var outcome: CameraStartAction? = null
        while (outcome == null && elapsed < 120_000L) {
            when (cameraStartAction(waited, frameReceived = false, restarts, arBlocked = false)) {
                CameraStartAction.WAIT -> {
                    waited += 1_000L
                    elapsed += 1_000L
                }
                CameraStartAction.RESTART_SESSION -> {
                    restarts++
                    waited = 0L
                }
                CameraStartAction.GIVE_UP -> outcome = CameraStartAction.GIVE_UP
                CameraStartAction.IDLE -> outcome = CameraStartAction.IDLE
            }
        }
        assertEquals(CameraStartAction.GIVE_UP, outcome)
        assertEquals(CAMERA_START_MAX_RESTARTS, restarts)
        assertEquals((CAMERA_START_MAX_RESTARTS + 1) * CAMERA_START_TIMEOUT_MS, elapsed)
    }
}
