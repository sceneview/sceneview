package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.DEFAULT_LABEL_FADE_START_DEGREES
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialCaptionLayoutTest {
    @Test
    fun `the idle gallery publishes uncapped live spacing`() {
        assertTrue(canPublishLiveGalleryCaptionSpacing(focusIndex = null, focusZoom = 0f))
        assertEquals(320f, publishedGalleryCaptionSpacingPx(320f, 160f, null, 0f), 0f)
    }

    @Test
    fun `a picked sphere keeps flight spacing through departure and arrival`() {
        for (focusZoom in listOf(0f, 0.5f, 1f)) {
            assertFalse(canPublishLiveGalleryCaptionSpacing(focusIndex = 0, focusZoom))
        }
    }

    @Test
    fun `remaining zoom prevents live publication even without a picked sphere`() {
        for (focusZoom in listOf(Float.MIN_VALUE, 0.5f, 1f)) {
            assertFalse(canPublishLiveGalleryCaptionSpacing(focusIndex = null, focusZoom))
        }
    }

    @Test
    fun `camera depth ignores lateral eye displacement and follows the forward axis`() {
        assertEquals(3f, galleryCaptionDepth(Position(0f, 0f, 3f), Direction(0f, 0f, -1f)), 0f)
        assertEquals(3f, galleryCaptionDepth(Position(4f, 2f, 3f), Direction(0f, 0f, -1f)), 0f)
        assertEquals(4f, galleryCaptionDepth(Position(4f, 2f, 3f), Direction(-1f, 0f, 0f)), 0f)
        assertEquals(3.6f, galleryCaptionDepth(Position(4f, 2f, 3f), Direction(0f, -0.6f, -0.8f)), 1e-6f)
    }

    @Test
    fun `depth at or behind the camera plane suppresses gallery spacing`() {
        for (eyeZ in listOf(0f, -3f)) {
            val depth = galleryCaptionDepth(Position(4f, 0f, eyeZ), Direction(0f, 0f, -1f))
            assertTrue(depth <= 0f)
            assertEquals(0f, galleryCaptionSpacingPx(320f, depth, 0.6f, 3, 25f), 0f)
        }
    }

    @Test
    fun `focus flights use the smaller live or return spacing through both legs`() {
        for ((focusIndex, focusZoom) in listOf(0 to 0f, 0 to 0.5f, 0 to 1f, null to Float.MIN_VALUE)) {
            assertEquals(160f, publishedGalleryCaptionSpacingPx(160f, 320f, focusIndex, focusZoom), 0f)
            assertEquals(160f, publishedGalleryCaptionSpacingPx(320f, 160f, focusIndex, focusZoom), 0f)
            assertEquals(160f, publishedGalleryCaptionSpacingPx(160f, 160f, focusIndex, focusZoom), 0f)
            assertEquals(0f, publishedGalleryCaptionSpacingPx(0f, 160f, focusIndex, focusZoom), 0f)
        }
    }

    @Test
    fun `the gallery sweep stays within the fully opaque caption yaw`() {
        assertTrue(
            "Gallery sweep (${MaterialStudio.SWEEP_DEGREES} degrees) must not exceed the caption fade start " +
                "($DEFAULT_LABEL_FADE_START_DEGREES degrees), or opaque captions can overlap",
            MaterialStudio.SWEEP_DEGREES <= DEFAULT_LABEL_FADE_START_DEGREES,
        )
    }

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
    fun `flight spacing accounts for pinching both closer and farther before a tap`() {
        for ((headOnSpacingPx, cameraDepth) in listOf(640f to 1.5f, 320f to 3f, 160f to 6f)) {
            val spacing = galleryCaptionSpacingAtRadiusPx(
                headOnSpacingPx = headOnSpacingPx,
                cameraDepth = cameraDepth,
                galleryRadius = 3f,
                ballSpacing = 0.6f,
                columns = 3,
                yawDegrees = 25f,
            )
            // The return radius controls both pinhole scaling and yaw compression.
            assertEquals(267.416f, spacing, 0.001f)
        }
    }

    @Test
    fun `head-on flight spacing scales inversely with the return radius`() {
        assertEquals(160f, galleryCaptionSpacingAtRadiusPx(320f, 3f, 6f, 0.6f, 3, 0f), 1e-4f)
        assertEquals(640f, galleryCaptionSpacingAtRadiusPx(320f, 3f, 1.5f, 0.6f, 3, 0f), 1e-4f)
    }

    @Test
    fun `flight spacing applies the eye-plane guard at the return radius`() {
        assertEquals(0f, galleryCaptionSpacingAtRadiusPx(320f, 3f, 0.2f, 0.6f, 3, 25f), 0f)
    }

    @Test
    fun `gallery captions hide below the low threshold and show above the high threshold`() {
        for (wasVisible in listOf(false, true)) {
            assertFalse(galleryCaptionVisible(63, wasVisible, hideBelowPx = 64, showAbovePx = 72))
            assertTrue(galleryCaptionVisible(73, wasVisible, hideBelowPx = 64, showAbovePx = 72))
        }
    }

    @Test
    fun `the whole dead band including its endpoints preserves visibility`() {
        for (widthPx in 64..72) {
            for (wasVisible in listOf(false, true)) {
                assertEquals(wasVisible, galleryCaptionVisible(widthPx, wasVisible, hideBelowPx = 64, showAbovePx = 72))
            }
        }
    }

    @Test
    fun `zoom jitter needs to cross the opposite threshold before captions switch again`() {
        var visible = true
        val steps = listOf(80 to true, 70 to true, 63 to false, 64 to false, 68 to false, 72 to false, 73 to true)
        for ((widthPx, expected) in steps) {
            visible = galleryCaptionVisible(widthPx, visible, hideBelowPx = 64, showAbovePx = 72)
            assertEquals("Caption visibility at width $widthPx", expected, visible)
        }
    }

    @Test
    fun `visibility thresholds accept density-scaled pixels`() {
        assertFalse(galleryCaptionVisible(127, wasVisible = true, hideBelowPx = 128, showAbovePx = 144))
        assertFalse(galleryCaptionVisible(144, wasVisible = false, hideBelowPx = 128, showAbovePx = 144))
        assertTrue(galleryCaptionVisible(145, wasVisible = false, hideBelowPx = 128, showAbovePx = 144))
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
