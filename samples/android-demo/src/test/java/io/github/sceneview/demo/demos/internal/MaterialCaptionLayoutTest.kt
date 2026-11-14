package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialCaptionLayoutTest {
    @Test
    fun `anchors 300 pixels apart leave a gap between caption boxes`() {
        val firstX = 200f
        val secondX = 500f
        val gapPx = 10
        val widthPx = compareCaptionWidthPx(firstX, secondX, gapPx, minimumWidthPx = 120)

        assertEquals(290, widthPx)
        val firstRight = firstX + widthPx / 2f
        val secondLeft = secondX - widthPx / 2f
        assertTrue(firstRight + gapPx <= secondLeft)
    }

    @Test
    fun `a very close pair keeps the minimum readable width`() {
        assertEquals(120, compareCaptionWidthPx(200f, 205f, gapPx = 10, minimumWidthPx = 120))
        assertEquals(120, compareCaptionWidthPx(200f, 200f, gapPx = 10, minimumWidthPx = 120))
    }

    @Test
    fun `caption width follows spacing in either screen order`() {
        assertEquals(290, compareCaptionWidthPx(500f, 200f, gapPx = 10, minimumWidthPx = 120))
        assertEquals(190, compareCaptionWidthPx(200f, 400f, gapPx = 10, minimumWidthPx = 120))
    }

    @Test
    fun `anchor well above the cover stays opaque`() {
        assertEquals(1f, materialCaptionCoverAlpha(400f, 1000f, 300f, 40f), 0f)
    }

    @Test
    fun `anchor at or below the cover edge is hidden`() {
        assertEquals(0f, materialCaptionCoverAlpha(700f, 1000f, 300f, 40f), 0f)
        assertEquals(0f, materialCaptionCoverAlpha(800f, 1000f, 300f, 40f), 0f)
    }

    @Test
    fun `anchor within one caption height of the cover fades`() {
        val alpha = materialCaptionCoverAlpha(680f, 1000f, 300f, 40f)
        assertTrue(alpha > 0f && alpha < 1f)
        assertEquals(0.5f, alpha, 0f)
        assertEquals(1f, materialCaptionCoverAlpha(660f, 1000f, 300f, 40f), 0f)
    }

    @Test
    fun `zero cover leaves captions unchanged even at the bottom`() {
        assertEquals(1f, materialCaptionCoverAlpha(990f, 1000f, 0f, 40f), 0f)
        assertEquals(1f, materialCaptionCoverAlpha(1100f, 1000f, 0f, 40f), 0f)
    }
}
