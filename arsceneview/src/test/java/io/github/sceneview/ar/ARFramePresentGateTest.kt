package io.github.sceneview.ar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A view whose camera stopped keeps the camera image it last presented (#5137): the default
 * lifecycle pauses the session as a `NavHost` exit transition starts, and the frame presented right
 * after that sampled a camera texture ARCore no longer fed.
 */
class ARFramePresentGateTest {

    private fun present(
        sessionIsClosed: Boolean? = false,
        cameraIsLive: Boolean = true,
        hasCameraImage: Boolean = true,
        hasNewArFrame: Boolean = false,
        sceneChanged: Boolean = false,
        framesOwed: Int = 0,
    ) = shouldPresentARFrame(
        sessionIsClosed = sessionIsClosed,
        cameraIsLive = cameraIsLive,
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
    fun `a stopped camera keeps its last image while content changes`() {
        // The tick right after a NavHost exit starts: the settle window is still open.
        assertFalse(present(cameraIsLive = false, sceneChanged = true))
    }

    @Test
    fun `a stopped camera presents nothing on an idle tick`() {
        assertFalse(present(cameraIsLive = false))
    }

    @Test
    fun `a stopped camera still pays a new swap chain the frame it owes`() {
        assertTrue(present(cameraIsLive = false, framesOwed = 1))
    }

    @Test
    fun `a view that has not shown the camera yet still presents virtual content`() {
        assertTrue(present(cameraIsLive = false, hasCameraImage = false, sceneChanged = true))
    }

    @Test
    fun `no session or a closed one presents nothing, owed frame or not`() {
        assertFalse(present(sessionIsClosed = null, hasNewArFrame = true, framesOwed = 1))
        assertFalse(present(sessionIsClosed = true, sceneChanged = true, framesOwed = 1))
    }

    @Test
    fun `the camera is live only while the host is resumed and holds it`() {
        assertTrue(isARCameraLive(hostIsResumed = true, cameraInUseByAnotherSession = false))
        assertFalse(isARCameraLive(hostIsResumed = false, cameraInUseByAnotherSession = false))
        assertFalse(isARCameraLive(hostIsResumed = true, cameraInUseByAnotherSession = true))
    }

    @Test
    fun `a failed resume with the host still resumed keeps drawing its nodes`() {
        // CameraNotAvailableException on return from Home: the session is not resumed, the host
        // is, and #5132 keeps the scene alive in that state.
        val cameraIsLive = isARCameraLive(hostIsResumed = true, cameraInUseByAnotherSession = false)
        assertTrue(present(cameraIsLive = cameraIsLive, sceneChanged = true))
    }

    @Test
    fun `a camera taken by another view keeps its last image while content changes`() {
        val cameraIsLive = isARCameraLive(hostIsResumed = true, cameraInUseByAnotherSession = true)
        assertFalse(present(cameraIsLive = cameraIsLive, sceneChanged = true))
    }
}
