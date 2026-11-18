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

    @Test
    fun `no bound image is never fresh`() {
        assertFalse(freshCameraImage(boundTimestamp = 0L, staleTimestamp = 0L))
    }

    @Test
    fun `the first bound image of a new view is fresh`() {
        assertTrue(freshCameraImage(boundTimestamp = 100L, staleTimestamp = 0L))
    }

    @Test
    fun `after a stop the image the texture still holds does not lift the cover`() {
        assertFalse(freshCameraImage(boundTimestamp = 500L, staleTimestamp = 500L))
        assertTrue(freshCameraImage(boundTimestamp = 533L, staleTimestamp = 500L))
    }

    @Test
    fun `a return from the background holds the cover until a new image and two more frames`() {
        val stale = 500L
        // Reset on stop: count back to 0. Frames presented with the stale image count nothing.
        var count = 0
        repeat(3) { count = presentedCameraFrames(count, freshCameraImage(stale, stale), submitted = true) }
        assertEquals(0, count)
        assertTrue(cameraCoverVisible(count))
        count = presentedCameraFrames(count, freshCameraImage(533L, stale), submitted = true)
        count = presentedCameraFrames(count, freshCameraImage(533L, stale), submitted = true)
        assertTrue(cameraCoverVisible(count))
        count = presentedCameraFrames(count, freshCameraImage(566L, stale), submitted = true)
        assertFalse(cameraCoverVisible(count))
    }

    private val second = 1_000_000_000L

    /** Expiry check after a reset over stale image 500, first present at t = 1 s, nothing owed. */
    private fun expired(
        atNanos: Long,
        boundTimestamp: Long = 500L,
        framesOwed: Int = 0,
        firstPresentNanos: Long = second,
        playbackFinished: Boolean = false,
    ) = staleCameraCoverExpired(
        boundTimestamp = boundTimestamp,
        staleTimestamp = 500L,
        framesOwed = framesOwed,
        firstPresentNanos = firstPresentNanos,
        frameTimeNanos = atNanos,
        playbackFinished = { playbackFinished },
    )

    @Test
    fun `a reset cover over a stale image lifts after the cap`() {
        assertEquals(2 * second, STALE_CAMERA_COVER_TIMEOUT_NANOS)
        assertFalse(expired(atNanos = second + STALE_CAMERA_COVER_TIMEOUT_NANOS - 1))
        assertTrue(expired(atNanos = second + STALE_CAMERA_COVER_TIMEOUT_NANOS))
    }

    @Test
    fun `a finished playback lifts a reset cover once the new surface has its frame`() {
        assertTrue(expired(atNanos = second, playbackFinished = true))
        assertFalse(expired(atNanos = second, playbackFinished = true, framesOwed = 1))
        assertFalse(expired(atNanos = second, playbackFinished = true, firstPresentNanos = 0L))
    }

    @Test
    fun `the cap waits for the frames owed to the new surface`() {
        assertFalse(expired(atNanos = 10 * second, framesOwed = 1))
        assertFalse(expired(atNanos = 10 * second, firstPresentNanos = 0L))
    }

    @Test
    fun `the cap never lifts the cover of a view that never had a camera image`() {
        assertFalse(
            staleCameraCoverExpired(
                boundTimestamp = 0L,
                staleTimestamp = 0L,
                framesOwed = 0,
                firstPresentNanos = second,
                frameTimeNanos = 10 * second,
                playbackFinished = { true },
            ),
        )
    }

    @Test
    fun `a newer image takes the normal reveal path, not the cap`() {
        assertFalse(expired(atNanos = 10 * second, boundTimestamp = 533L))
    }

    @Test
    fun `playback status is not read before the cheaper conditions hold`() {
        var reads = 0
        staleCameraCoverExpired(
            boundTimestamp = 500L, staleTimestamp = 500L, framesOwed = 1,
            firstPresentNanos = second, frameTimeNanos = second, playbackFinished = { reads++; true },
        )
        staleCameraCoverExpired(
            boundTimestamp = 500L, staleTimestamp = 500L, framesOwed = 0,
            firstPresentNanos = second, frameTimeNanos = 3 * second, playbackFinished = { reads++; true },
        )
        assertEquals(0, reads)
    }

    /**
     * The reset belongs to the surface, not to the lifecycle: a NavHost stops its entry as the
     * exit transition starts, while the frozen camera image is still on screen (#5094 review).
     */
    @Test
    fun `the cover is reset only when the surface is destroyed`() {
        val source = java.io.File("src/main/java/io/github/sceneview/ar/ARSceneView.kt").readText()
        val destroyed = source.substringAfter("sceneRenderer.onSurfaceDestroyed = {")
            .substringBefore("\n        }")
        assertTrue(destroyed.contains("coverStaleImageTimestamp.set("))
        assertTrue(destroyed.contains("presentedCameraFrameCount = 0"))
        assertEquals(1, source.split("presentedCameraFrameCount = 0").size - 1)
        assertEquals(1, source.split("coverStaleImageTimestamp.set(").size - 1)
    }
}
