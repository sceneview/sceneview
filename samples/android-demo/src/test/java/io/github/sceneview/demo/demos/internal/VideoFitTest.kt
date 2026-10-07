package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFitTest {

    private val screen = Size(0.64f, 0.36f, 0f)

    private fun assertSize(expected: Size, actual: Size) {
        assertEquals(expected.x, actual.x, 1e-5f)
        assertEquals(expected.y, actual.y, 1e-5f)
        assertEquals(expected.z, actual.z, 1e-5f)
    }

    @Test
    fun `a picture of the screen's own shape fills it`() {
        assertSize(screen, fitInside(screen, videoWidth = 1280, videoHeight = 720))
    }

    @Test
    fun `a wider picture keeps the width and leaves bars above and below`() {
        // 2.39:1 on a 16:9 screen.
        assertSize(Size(0.64f, 0.64f / 2.39f, 0f), fitInside(screen, videoWidth = 2390, videoHeight = 1000))
    }

    @Test
    fun `a narrower picture keeps the height and leaves bars on the sides`() {
        // 4:3, then a phone clip filmed upright.
        assertSize(Size(0.48f, 0.36f, 0f), fitInside(screen, videoWidth = 640, videoHeight = 480))
        assertSize(Size(0.36f * 9f / 16f, 0.36f, 0f), fitInside(screen, videoWidth = 720, videoHeight = 1280))
    }

    @Test
    fun `a fitted picture is never stretched`() {
        for ((width, height) in listOf(1280 to 720, 2390 to 1000, 640 to 480, 720 to 1280, 1 to 1)) {
            val fitted = fitInside(screen, width, height)
            assertEquals(width.toFloat() / height, fitted.x / fitted.y, 1e-4f)
            assertTrue(fitted.x <= screen.x + 1e-5f && fitted.y <= screen.y + 1e-5f)
        }
    }

    @Test
    fun `dimensions a player has not reported yet keep the whole screen`() {
        assertSize(screen, fitInside(screen, videoWidth = 0, videoHeight = 0))
        assertSize(screen, fitInside(screen, videoWidth = 1280, videoHeight = 0))
    }
}
