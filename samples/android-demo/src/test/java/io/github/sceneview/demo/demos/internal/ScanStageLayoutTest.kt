package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 3D Scan stage while a room is scanned: the live 3D map over the whole stage, the camera in
 * a thumbnail between the status card and the controls, swapped in one tap.
 */
class ScanStageLayoutTest {
    // A 393 dp phone at 2.75 px per dp: 1,080 × 2,200 px of stage.
    private val width = 1080f
    private val height = 2200f
    private val hud = 440f
    private val controls = 1716f

    private fun layout(mapFirst: Boolean = true, corner: ThumbCorner = ThumbCorner.BottomEnd) =
        requireNotNull(ScanStageLayout.of(width, height, hud, controls, mapFirst, corner, margin = 32f))

    @Test
    fun theMapTakesTheWholeStage() {
        val stage = layout()
        assertEquals(StageRect(0f, 0f, width, height), stage.map)
        assertEquals(stage.main, stage.map)
        assertEquals(stage.thumb, stage.camera)
    }

    @Test
    fun theCameraSitsInACornerBetweenTheCardAndTheControls() {
        val camera = layout().camera
        assertEquals(width - 32f, camera.right, 0.5f)
        assertEquals(controls - 32f, camera.bottom, 0.5f)
        assertTrue("${camera.top} below the card", camera.top >= hud)
        // Portrait, as the phone is held: 3 wide for 4 tall.
        assertEquals(4f / 3f, camera.height / camera.width, 0.01f)
        assertEquals(width * ScanStageLayout.THUMB_WIDTH_SHARE, camera.width, 0.5f)
    }

    @Test
    fun eachCornerKeepsItsSide() {
        val topStart = layout(corner = ThumbCorner.TopStart).thumb
        assertEquals(32f, topStart.left, 0.5f)
        assertEquals(hud + 32f, topStart.top, 0.5f)
        val bottomStart = layout(corner = ThumbCorner.BottomStart).thumb
        assertEquals(32f, bottomStart.left, 0.5f)
        assertEquals(controls - 32f, bottomStart.bottom, 0.5f)
    }

    @Test
    fun aShortBandShrinksTheThumbnail() {
        // A band of 800 px: the thumbnail keeps at most 45 % of it.
        val stage = requireNotNull(ScanStageLayout.of(width, 1400f, 300f, 1100f, true, ThumbCorner.BottomEnd, 0f))
        assertEquals(800f * ScanStageLayout.THUMB_MAX_BAND_SHARE, stage.thumb.height, 0.5f)
        assertEquals(4f / 3f, stage.thumb.height / stage.thumb.width, 0.01f)
    }

    @Test
    fun swappedTheCameraTakesTheStageAndTheMapTheCorner() {
        val stage = layout(mapFirst = false)
        assertEquals(StageRect(0f, 0f, width, height), stage.camera)
        assertEquals(layout().thumb, stage.map)
    }

    @Test
    fun theMapIsMostOfWhatIsSeen() {
        // #4810 gave the map 60 % of the band, 35 % of the stage, 13 % of it under the card.
        val share = layout().mapVisibleShare
        assertTrue("$share", share >= 0.5f)
        assertEquals(0f, layout(mapFirst = false).mapVisibleShare, 0.08f)
    }

    @Test
    fun theClearPartRunsFromTheCardToTheControls() {
        val stage = layout()
        assertEquals(hud, stage.clearTop, 0f)
        assertEquals(controls, stage.clearBottom, 0f)
    }

    @Test
    fun unmeasuredChromeFallsBackToTheUsualPlaces() {
        val stage = requireNotNull(ScanStageLayout.of(width, height, Float.NaN, Float.NaN, true, ThumbCorner.BottomEnd, 0f))
        assertEquals(height * ScanStageLayout.DEFAULT_HUD_BOTTOM, stage.clearTop, 0.5f)
        assertEquals(height * ScanStageLayout.DEFAULT_CONTROLS_TOP, stage.clearBottom, 0.5f)
    }

    @Test
    fun aLandscapeStageIsNotShared() {
        assertNull(ScanStageLayout.of(2200f, 1080f, 500f, 700f, true, ThumbCorner.BottomEnd, 0f))
        assertNull(ScanStageLayout.of(0f, 0f, 0f, 0f, true, ThumbCorner.BottomEnd, 0f))
    }

    @Test
    fun aDroppedThumbnailSnapsToTheNearestCorner() {
        fun near(x: Float, y: Float) = ScanStageLayout.nearestCorner(x, y, width, hud, controls)
        assertEquals(ThumbCorner.TopStart, near(100f, 500f))
        assertEquals(ThumbCorner.TopEnd, near(1000f, 500f))
        assertEquals(ThumbCorner.BottomStart, near(100f, 1600f))
        assertEquals(ThumbCorner.BottomEnd, near(1000f, 1600f))
    }

    @Test
    fun theSwapMovesFromOneRectToTheOther() {
        val from = StageRect(0f, 0f, 1000f, 2000f)
        val to = StageRect(600f, 1000f, 300f, 400f)
        assertEquals(from, StageRect.lerp(from, to, 0f))
        assertEquals(to, StageRect.lerp(from, to, 1f))
        assertEquals(StageRect(300f, 500f, 650f, 1200f), StageRect.lerp(from, to, 0.5f))
    }

    @Test
    fun theMapStaysUpWhileTheScanIsReviewed() {
        assertTrue(ScanPhase.Reviewing in ScanStageLayout.MAP_PHASES)
        assertTrue(ScanPhase.Paused in ScanStageLayout.MAP_PHASES)
        assertTrue(ScanPhase.Saving !in ScanStageLayout.MAP_PHASES)
    }

    @Test
    fun aDraggedThumbnailKeepsItsSizeInItsNewCorner() {
        val stage = layout()
        val top = stage.thumbAt(ThumbCorner.TopStart)
        assertEquals(stage.thumb.width, top.width, 0.01f)
        assertEquals(stage.thumb.height, top.height, 0.01f)
        assertEquals(32f, top.left, 0.01f)
        assertEquals(hud + 32f, top.top, 0.01f)
        assertEquals(stage.thumb, stage.thumbAt(ThumbCorner.BottomEnd))
    }

    @Test
    fun aPaneIsScaledToCoverItsThumbnailAndCroppedToIt() {
        val stage = layout()
        val t = PaneTransform.of(width, height, stage.thumb)
        // The thumbnail is narrower than the stage's shape: its width sets the scale.
        assertEquals(stage.thumb.width / width, t.scale, 0.001f)
        assertEquals(stage.thumb.width, t.crop.width * t.scale, 0.5f)
        assertEquals(stage.thumb.height, t.crop.height * t.scale, 0.5f)
        assertEquals(stage.thumb.left + stage.thumb.width / 2f - width / 2f, t.translationX, 0.01f)
        val whole = PaneTransform.of(width, height, stage.main)
        assertEquals(1f, whole.scale, 0.0001f)
        assertEquals(0f, whole.translationX, 0.0001f)
    }

    @Test
    fun theCardAndControlsEdgesAreHeldForTheCapture() {
        var hud = Float.NaN
        var controls = Float.NaN
        for ((cardBottom, controlsStart) in listOf(400f to 1600f, 460f to 1540f, 400f to 1600f)) {
            hud = ScanStageLayout.holdLowest(hud, cardBottom)
            controls = ScanStageLayout.holdHighest(controls, controlsStart)
        }
        assertEquals(460f, hud, 0f)
        assertEquals(1540f, controls, 0f)
    }

    @Test
    fun theFirstMeasurementIsTakenAsIs() {
        assertEquals(420f, ScanStageLayout.holdLowest(Float.NaN, 420f), 0f)
        assertEquals(1500f, ScanStageLayout.holdHighest(Float.NaN, 1500f), 0f)
    }
}
