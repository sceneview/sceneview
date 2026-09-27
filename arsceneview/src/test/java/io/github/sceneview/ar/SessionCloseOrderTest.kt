package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ARCore.destroy()` pauses a still-resumed session before closing it (#4026): an
 * `ARSceneView` that leaves composition while its Activity stays resumed never received a
 * lifecycle `ON_PAUSE`, and ARCore's documented teardown order is `pause()` then `close()`.
 */
class SessionCloseOrderTest {

    private val calls = mutableListOf<String>()

    @Test
    fun `a resumed session is paused, then closed`() {
        closeSessionInOrder(isResumed = true, pause = { calls += "pause" }, close = { calls += "close" })
        assertEquals(listOf("pause", "close"), calls)
    }

    @Test
    fun `an already paused session is only closed`() {
        closeSessionInOrder(isResumed = false, pause = { calls += "pause" }, close = { calls += "close" })
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun `a failing pause still closes the session`() {
        closeSessionInOrder(
            isResumed = true,
            pause = { calls += "pause"; throw IllegalStateException("camera already closed") },
            close = { calls += "close" },
            onPauseFailed = { calls += "pause-failed" },
        )
        assertEquals(listOf("pause", "pause-failed", "close"), calls)
    }
}
