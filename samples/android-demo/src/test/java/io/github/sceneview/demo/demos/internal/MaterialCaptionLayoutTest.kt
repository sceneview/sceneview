package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialCaptionLayoutTest {
    @Test
    fun `landscape far-side spacing at 25 degrees is smaller than head-on spacing`() {
        val headOnSpacingPx = 320f
        val spacing = galleryCaptionSpacingPx(headOnSpacingPx, 3f, 0.6f, 3, 25f)

        assertTrue(spacing < headOnSpacingPx)
        // Perspective also compresses the far side beyond cosine foreshortening alone.
        assertEquals(267.416f, spacing, 0.001f)
        val width = galleryCaptionWidthPx(spacing, gapPx = 10, maximumWidthPx = 289)
        assertTrue(width + 10 <= spacing)
    }

    @Test
    fun `the narrowest gap handles either far edge and vanishes behind the eye plane`() {
        val positiveYaw = galleryCaptionSpacingPx(320f, 3f, 0.6f, 3, 25f)
        val negativeYaw = galleryCaptionSpacingPx(320f, 3f, 0.6f, 3, -25f)
        assertEquals(positiveYaw, negativeYaw, 1e-4f)
        assertEquals(320f, galleryCaptionSpacingPx(320f, 3f, 0.6f, 3, 0f), 1e-4f)
        assertEquals(0f, galleryCaptionSpacingPx(320f, 0.2f, 0.6f, 3, 25f), 0f)
    }

    @Test
    fun `perspective compression accounts for camera distance and the full wall span`() {
        val nearSpacing = galleryCaptionSpacingPx(320f, 2f, 0.6f, 3, 25f)
        val farSpacing = galleryCaptionSpacingPx(320f, 5f, 0.6f, 3, 25f)
        val widerWallSpacing = galleryCaptionSpacingPx(320f, 2f, 0.6f, 5, 25f)
        assertTrue(nearSpacing < farSpacing)
        assertTrue(widerWallSpacing < nearSpacing)
    }

    @Test
    fun `gallery caption without known spacing uses the maximum width`() {
        assertEquals(240, galleryCaptionWidthPx(null, gapPx = 10, maximumWidthPx = 240))
    }

    @Test
    fun `widely spaced gallery captions are capped at the maximum width`() {
        assertEquals(240, galleryCaptionWidthPx(400f, gapPx = 10, maximumWidthPx = 240))
    }

    @Test
    fun `narrowly spaced gallery captions leave a gap`() {
        assertEquals(110, galleryCaptionWidthPx(120f, gapPx = 10, maximumWidthPx = 240))
    }

    @Test
    fun `gallery spacing below the gap leaves zero width`() {
        assertEquals(0, galleryCaptionWidthPx(5f, gapPx = 10, maximumWidthPx = 240))
    }

    @Test
    fun `gallery captions respect a small maximum width`() {
        assertEquals(30, galleryCaptionWidthPx(120f, gapPx = 10, maximumWidthPx = 30))
    }

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
    fun `stacked compare captions fade using each placed bottom`() {
        val first = MaterialCaptionPlacement(x = 200, y = 620, height = 40)
        val second = MaterialCaptionPlacement(x = 205, y = 620, height = 60)
        val placements = compareCaptionPlacements(first, second, widthPx = 120, gapPx = 10)

        assertEquals(first, placements[0])
        assertEquals(670, placements[1].y)
        assertEquals(second.x, placements[1].x)
        assertEquals(660f, placements[0].captionBottomY, 0f)
        assertEquals(730f, placements[1].captionBottomY, 0f)
        assertEquals(1f, materialCaptionCoverAlpha(placements[0].captionBottomY, 1000f, 300f, 40f), 0f)
        assertEquals(0f, materialCaptionCoverAlpha(placements[1].captionBottomY, 1000f, 300f, 60f), 0f)
        assertTrue(materialCaptionCoverAlpha(second.captionBottomY, 1000f, 300f, 60f) > 0f)
    }

    @Test
    fun `separated compare captions keep their anchor rows in either screen order`() {
        val first = MaterialCaptionPlacement(x = 200, y = 620, height = 40)
        val second = MaterialCaptionPlacement(x = 500, y = 600, height = 60)
        assertEquals(listOf(first, second), compareCaptionPlacements(first, second, 120, 10))
        assertEquals(listOf(second, first), compareCaptionPlacements(second, first, 120, 10))
    }

    @Test
    fun `caption bottom well above the cover stays opaque`() {
        assertEquals(1f, materialCaptionCoverAlpha(400f, 1000f, 300f, 40f), 0f)
    }

    @Test
    fun `caption bottom at or below the cover edge is hidden`() {
        assertEquals(0f, materialCaptionCoverAlpha(700f, 1000f, 300f, 40f), 0f)
        assertEquals(0f, materialCaptionCoverAlpha(800f, 1000f, 300f, 40f), 0f)
    }

    @Test
    fun `caption bottom within one caption height of the cover fades`() {
        val alpha = materialCaptionCoverAlpha(680f, 1000f, 300f, 40f)
        assertTrue(alpha > 0f && alpha < 1f)
        assertEquals(0.5f, alpha, 0f)
        assertEquals(1f, materialCaptionCoverAlpha(660f, 1000f, 300f, 40f), 0f)
    }

    @Test
    fun `closed sheet leaves captions unchanged`() {
        assertEquals(1f, materialCaptionCoverAlpha(990f, 1000f, 0f, 40f), 0f)
        assertEquals(1f, materialCaptionCoverAlpha(1100f, 1000f, 0f, 40f), 0f)
    }

    @Test
    fun `a sheet still under the chrome fades only what it reaches`() {
        assertEquals(0.5f, materialCaptionCoverAlpha(880f, 1000f, 100f, 40f), 0f)
        assertEquals(1f, materialCaptionCoverAlpha(850f, 1000f, 100f, 40f), 0f)
    }

    @Test
    fun `a taller caption fades more at the same anchor`() {
        val anchorY = 620f
        val gapPx = 10f
        val shortHeightPx = 40f
        val tallHeightPx = 60f
        val shortAlpha = materialCaptionCoverAlpha(
            captionBottomY = anchorY + gapPx + shortHeightPx,
            sceneHeightPx = 1000f,
            sheetCoverPx = 300f,
            fadeBandPx = shortHeightPx,
        )
        val tallAlpha = materialCaptionCoverAlpha(
            captionBottomY = anchorY + gapPx + tallHeightPx,
            sceneHeightPx = 1000f,
            sheetCoverPx = 300f,
            fadeBandPx = tallHeightPx,
        )

        assertEquals(0.75f, shortAlpha, 1e-6f)
        assertEquals(1f / 6f, tallAlpha, 1e-6f)
        assertTrue(tallAlpha < shortAlpha)
    }
}
