package io.github.sceneview.ar.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFrameTest {
    private val firstTexture = Any()
    private val secondTexture = Any()
    private val textures = mapOf(7 to firstTexture, 11 to secondTexture)

    @Test
    fun `timestamp zero does not reveal a registered but uninitialised camera texture`() {
        assertNull(cameraTextureForFrame(0L, 7, textures))
    }

    @Test
    fun `an image uses the current texture from ARCore's rotating pool`() {
        assertSame(firstTexture, cameraTextureForFrame(100L, 7, textures))
        assertSame(secondTexture, cameraTextureForFrame(200L, 11, textures))
    }

    @Test
    fun `a frame without a registered texture does not reveal the camera quad`() {
        assertNull(cameraTextureForFrame(100L, 99, textures))
    }

    /** Drives the counter through [frames] (hasCameraImage, submitted) pairs from a fresh view. */
    private fun countAfter(vararg frames: Pair<Boolean, Boolean>): Int =
        frames.fold(0) { count, (image, submitted) -> presentedCameraFrames(count, image, submitted) }

    @Test
    fun `submitting an empty startup frame keeps the cover`() {
        val count = countAfter(false to true)
        assertEquals(0, count)
        assertTrue(cameraCoverVisible(count))
    }

    @Test
    fun `binding an image without submitting it keeps the cover`() {
        val count = countAfter(true to false)
        assertEquals(0, count)
        assertTrue(cameraCoverVisible(count))
    }

    @Test
    fun `the first submitted camera image does not drop the cover yet`() {
        assertTrue(cameraCoverVisible(countAfter(true to true)))
    }

    @Test
    fun `the cover holds for two more presented frames after the first camera image`() {
        assertEquals(2, CAMERA_COVER_SETTLE_FRAMES)
        assertTrue(cameraCoverVisible(countAfter(true to true, true to true)))
        assertFalse(cameraCoverVisible(countAfter(true to true, true to true, true to true)))
    }

    @Test
    fun `refused frames between camera images do not count towards the reveal`() {
        val count = countAfter(true to true, true to false, true to false, true to true)
        assertEquals(2, count)
        assertTrue(cameraCoverVisible(count))
        assertFalse(cameraCoverVisible(presentedCameraFrames(count, true, true)))
    }

    @Test
    fun `frames before the first image do not count towards the reveal`() {
        val count = countAfter(false to true, false to true, false to true, true to true)
        assertEquals(1, count)
        assertTrue(cameraCoverVisible(count))
    }

    @Test
    fun `the count saturates so a revealed view stops writing state`() {
        val revealed = countAfter(*Array(10) { true to true })
        assertEquals(CAMERA_COVER_REVEAL_FRAMES, revealed)
        assertEquals(revealed, presentedCameraFrames(revealed, true, true))
    }

    @Test
    fun `a skipped frame after the reveal does not flash the cover again`() {
        val revealed = countAfter(true to true, true to true, true to true)
        assertFalse(cameraCoverVisible(presentedCameraFrames(revealed, true, false)))
    }

    @Test
    fun `a new view cannot inherit the previous view's readiness`() {
        val oldView = countAfter(true to true, true to true, true to true)
        val newView = countAfter(false to true)
        assertFalse(cameraCoverVisible(oldView))
        assertTrue(cameraCoverVisible(newView))
    }
}
