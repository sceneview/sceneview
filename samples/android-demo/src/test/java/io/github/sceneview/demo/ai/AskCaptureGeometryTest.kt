package io.github.sceneview.demo.ai

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the frame the Point & Ask demo actually sends to Gemini Nano (#3343).
 *
 * The regression these lock down: the demo used to hand ML Kit the raw window capture, and
 * ML Kit only clamps the SHORT edge to 768 px — so a 1080×2424 phone frame reached the
 * on-device model as a 768×1723 strip.
 */
class AskCaptureGeometryTest {

    private companion object {
        const val PIXEL_9_WIDTH = 1080
        const val PIXEL_9_HEIGHT = 2424
    }

    @Test
    fun `a phone-shaped capture is cropped and downscaled inside the budget`() {
        val region = askCaptureRegion(PIXEL_9_WIDTH, PIXEL_9_HEIGHT)

        assertTrue(
            "longest edge ${max(region.scaledWidth, region.scaledHeight)} exceeds the budget",
            max(region.scaledWidth, region.scaledHeight) <= ASK_IMAGE_MAX_EDGE,
        )
        // The pre-fix behaviour — short edge 768, long edge left alone — must not survive.
        assertTrue(region.scaledHeight < 1723)
    }

    @Test
    fun `the crop keeps a sane aspect ratio instead of a full-height strip`() {
        val region = askCaptureRegion(PIXEL_9_WIDTH, PIXEL_9_HEIGHT)
        val aspect = max(region.width, region.height).toFloat() /
            min(region.width, region.height)
        assertTrue("aspect $aspect is still a strip", aspect <= 1.5f)
    }

    @Test
    fun `the crop stays inside the source in every corner`() {
        listOf(
            0f to 0f,
            PIXEL_9_WIDTH.toFloat() to PIXEL_9_HEIGHT.toFloat(),
            -500f to -500f,
            5_000f to 9_000f,
        ).forEach { (fx, fy) ->
            val region = askCaptureRegion(PIXEL_9_WIDTH, PIXEL_9_HEIGHT, fx, fy)
            assertTrue("x ${region.x}", region.x >= 0)
            assertTrue("y ${region.y}", region.y >= 0)
            assertTrue(region.x + region.width <= PIXEL_9_WIDTH)
            assertTrue(region.y + region.height <= PIXEL_9_HEIGHT)
            assertTrue(region.width > 0 && region.height > 0)
        }
    }

    @Test
    fun `the crop follows the tap`() {
        val top = askCaptureRegion(PIXEL_9_WIDTH, PIXEL_9_HEIGHT, focusX = 540f, focusY = 400f)
        val bottom = askCaptureRegion(PIXEL_9_WIDTH, PIXEL_9_HEIGHT, focusX = 540f, focusY = 2_000f)
        assertTrue("a tap near the top must not crop the same region as one near the bottom",
            top.y < bottom.y)
    }

    @Test
    fun `no focus centres the crop`() {
        val region = askCaptureRegion(PIXEL_9_WIDTH, PIXEL_9_HEIGHT)
        val centre = region.y + region.height / 2
        assertTrue(abs(centre - PIXEL_9_HEIGHT / 2) <= 1)
    }

    @Test
    fun `a small capture is passed through untouched`() {
        val region = askCaptureRegion(640, 480)
        assertEquals(0, region.x)
        assertEquals(0, region.y)
        assertEquals(640, region.width)
        assertEquals(480, region.height)
        assertEquals(640, region.scaledWidth)
        assertEquals(480, region.scaledHeight)
    }

    @Test
    fun `a landscape capture is handled on the other axis`() {
        val region = askCaptureRegion(2424, 1080)
        assertTrue(region.width < 2424)
        assertEquals(1080, region.height)
        assertTrue(max(region.scaledWidth, region.scaledHeight) <= ASK_IMAGE_MAX_EDGE)
    }

    @Test
    fun `degenerate sizes never produce an empty bitmap request`() {
        listOf(0 to 0, 1 to 1, -5 to 10).forEach { (w, h) ->
            val region = askCaptureRegion(w, h)
            assertTrue("$w x $h", region.width >= 1 && region.height >= 1)
            assertTrue("$w x $h", region.scaledWidth >= 1 && region.scaledHeight >= 1)
        }
    }

    @Test
    fun `a non-finite focus falls back to the centre instead of throwing`() {
        val region = askCaptureRegion(
            PIXEL_9_WIDTH, PIXEL_9_HEIGHT, focusX = Float.NaN, focusY = Float.NaN,
        )
        assertTrue(region.x >= 0 && region.y >= 0)
        assertEquals(askCaptureRegion(PIXEL_9_WIDTH, PIXEL_9_HEIGHT), region)
    }
}
