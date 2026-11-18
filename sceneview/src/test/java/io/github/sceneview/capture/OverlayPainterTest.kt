package io.github.sceneview.capture

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * What a video frame's overlay may not do to the recording: a throwing overlay costs the overlay,
 * not the frame (the bitmap stays transparent, the scene is still composited), and canvas state
 * left behind by one frame does not reach the next.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayPainterTest {

    private val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    private val red = Paint().apply { color = Color.RED }

    @Before
    fun clearLogs() = ShadowLog.reset()

    @Test
    fun `an overlay draws on the bitmap`() {
        val painter = OverlayPainter({ canvas, _, _ -> canvas.drawRect(0f, 0f, 1f, 1f, red) }, bitmap)

        assertTrue(painter.paint())
        assertEquals(Color.RED, bitmap.getPixel(0, 0))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(7, 7))
    }

    @Test
    fun `an overlay that throws leaves a transparent frame and is logged once`() {
        val painter = OverlayPainter({ canvas, _, _ ->
            canvas.drawRect(0f, 0f, 8f, 8f, red)
            throw IllegalArgumentException("Software rendering doesn't support hardware bitmaps")
        }, bitmap)

        repeat(3) { assertFalse(painter.paint()) }

        assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(7, 7))
        assertEquals(1, ShadowLog.getLogsForTag("SceneRecorder").size)
    }

    @Test
    fun `canvas state does not leak from one frame to the next`() {
        var frame = 0
        val painter = OverlayPainter({ canvas, _, _ ->
            // Each frame draws at its own origin, then leaves a translate and a clip behind.
            canvas.drawRect(0f, 0f, 1f, 1f, red)
            canvas.translate(4f, 4f)
            canvas.clipRect(0f, 0f, 1f, 1f)
            frame++
        }, bitmap)

        painter.paint()
        painter.paint()

        assertEquals(2, frame)
        assertEquals(Color.RED, bitmap.getPixel(0, 0))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(4, 4))
    }
}
