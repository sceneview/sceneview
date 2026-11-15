package io.github.sceneview.ar.camera

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

    @Test
    fun `submitting an empty startup frame keeps the placeholder`() {
        assertFalse(cameraFramePresented(false, hasCameraImage = false, submitted = true))
    }

    @Test
    fun `binding an image without submitting it keeps the placeholder`() {
        assertFalse(cameraFramePresented(false, hasCameraImage = true, submitted = false))
    }

    @Test
    fun `the first submitted camera image removes the placeholder`() {
        assertTrue(cameraFramePresented(false, hasCameraImage = true, submitted = true))
    }

    @Test
    fun `a skipped frame after startup does not flash the placeholder again`() {
        assertTrue(cameraFramePresented(true, hasCameraImage = true, submitted = false))
    }

    @Test
    fun `a new view cannot inherit the previous view's readiness`() {
        val oldView = cameraFramePresented(false, hasCameraImage = true, submitted = true)
        val newView = cameraFramePresented(false, hasCameraImage = false, submitted = true)
        assertTrue(oldView)
        assertFalse(newView)
    }
}
