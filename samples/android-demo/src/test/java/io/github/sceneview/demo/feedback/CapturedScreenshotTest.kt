package io.github.sceneview.demo.feedback

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pure-JVM tests for the transparent-hole probe (#2654) — the guard that stops
 * the bug reporter from silently attaching a blank-viewport screenshot when
 * the compositor returns [android.view.PixelCopy.SUCCESS] without compositing
 * the Filament `SurfaceView` (observed on the emulator's gfxstream: the
 * viewport region reads back `alpha == 0`).
 */
@RunWith(RobolectricTestRunner::class)
class CapturedScreenshotTest {

    private fun bitmapOf(width: Int, height: Int, fill: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(fill)
        }

    @Test
    fun `fully opaque capture has no transparent hole`() {
        assertFalse(hasTransparentHole(bitmapOf(200, 100, Color.WHITE)))
    }

    @Test
    fun `legitimately dark scene stays opaque black and never trips the probe`() {
        // A dark theme / dark 3D scene is opaque black (alpha 255) — the probe
        // keys on alpha, not luminance, precisely so this can't false-positive.
        assertFalse(hasTransparentHole(bitmapOf(200, 100, Color.BLACK)))
    }

    @Test
    fun `viewport-sized transparent hole trips the probe`() {
        // Opaque UI + a transparent centre band (~40 % of the area) — the
        // uncomposited-SurfaceView signature measured in the #2654 report.
        val bmp = bitmapOf(200, 100, Color.WHITE)
        for (y in 30 until 70) {
            for (x in 0 until bmp.width) bmp.setPixel(x, y, Color.TRANSPARENT)
        }
        assertTrue(hasTransparentHole(bmp))
    }

    @Test
    fun `tiny decoration transparency stays under the threshold`() {
        // 2 transparent rows out of 100 (~2 % sampled) — decoration edge
        // cases must not flip the warning on.
        val bmp = bitmapOf(200, 100, Color.WHITE)
        for (y in 0 until 2) {
            for (x in 0 until bmp.width) bmp.setPixel(x, y, Color.TRANSPARENT)
        }
        assertFalse(hasTransparentHole(bmp))
    }
}
