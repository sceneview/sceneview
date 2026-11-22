package io.github.sceneview.ar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A paused session keeps the camera image it last presented (#5137): the default lifecycle pauses
 * the session as a `NavHost` exit transition starts, and the frame presented right after that used
 * to sample a camera texture ARCore had released — black for the whole transition.
 */
class ARFramePresentGateTest {

    private fun present(
        sessionIsClosed: Boolean? = false,
        sessionIsRunning: Boolean = true,
        hasCameraImage: Boolean = true,
        hasNewArFrame: Boolean = false,
        sceneChanged: Boolean = false,
        framesOwed: Int = 0,
    ) = shouldPresentARFrame(
        sessionIsClosed = sessionIsClosed,
        sessionIsRunning = sessionIsRunning,
        hasCameraImage = hasCameraImage,
        hasNewArFrame = hasNewArFrame,
        sceneChanged = sceneChanged,
        framesOwed = framesOwed,
    )

    @Test
    fun `a running session presents a new camera image`() {
        assertTrue(present(hasNewArFrame = true))
    }

    @Test
    fun `a running session presents a virtual content change between two camera images`() {
        assertTrue(present(sceneChanged = true))
    }

    @Test
    fun `a running session skips a tick with nothing new`() {
        assertFalse(present())
    }

    @Test
    fun `a session paused after showing the camera keeps its last image while content changes`() {
        // The tick right after a NavHost exit starts: the settle window is still open.
        assertFalse(present(sessionIsRunning = false, sceneChanged = true))
    }

    @Test
    fun `a session paused after showing the camera presents nothing on an idle tick`() {
        assertFalse(present(sessionIsRunning = false))
    }

    @Test
    fun `a paused session still pays a new swap chain the frame it owes`() {
        assertTrue(present(sessionIsRunning = false, framesOwed = 1))
    }

    @Test
    fun `a session that has not shown the camera yet still presents virtual content`() {
        assertTrue(present(sessionIsRunning = false, hasCameraImage = false, sceneChanged = true))
    }

    @Test
    fun `no session or a closed one presents nothing, owed frame or not`() {
        assertFalse(present(sessionIsClosed = null, hasNewArFrame = true, framesOwed = 1))
        assertFalse(present(sessionIsClosed = true, sceneChanged = true, framesOwed = 1))
    }

    @Test
    fun `a resumed session presents again once its camera is back`() {
        assertTrue(present(sessionIsRunning = true, hasNewArFrame = true))
    }
}
