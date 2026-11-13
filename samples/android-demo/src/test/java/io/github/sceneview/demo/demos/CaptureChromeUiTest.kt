package io.github.sceneview.demo.demos

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.CaptureChrome
import io.github.sceneview.demo.demos.internal.ScanCopy
import io.github.sceneview.demo.demos.internal.ScanInstruction
import io.github.sceneview.demo.demos.internal.ScanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The capture screen's top and bottom on the device: one pill (duration · floor area), at most one
 * instruction, one Done (with Next room for a whole home), and no review step, pause or breadcrumb.
 * The review card, no longer part of the capture, still builds a model instead of "saving".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureChromeUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun text(id: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id)

    @Test
    fun `the capture's bottom is one instruction and one Done, nothing else`() {
        composeRule.setContent {
            ScanControls(instruction = ScanInstruction.Sweep, doneEnabled = true, onFinish = {})
        }
        composeRule.onNodeWithText(text(R.string.scan_capture_sweep)).assertIsDisplayed()
        composeRule.onNodeWithTag(SCAN_FINISH_TAG).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag(SCAN_NEXT_ROOM_TAG).assertDoesNotExist()
        // No review step, pause or breadcrumb any more (Thomas, 2026-11-13).
        composeRule.onNodeWithText(CaptureChrome.STOP_AND_REVIEW).assertDoesNotExist()
        for (step in ScanStep.entries) composeRule.onNodeWithText(step.label).assertDoesNotExist()
    }

    @Test
    fun `with nothing to say there is no instruction line, and Done waits for a capture`() {
        composeRule.setContent {
            ScanControls(instruction = null, doneEnabled = false, onFinish = {})
        }
        composeRule.onNodeWithTag(SCAN_FINISH_TAG).assertIsNotEnabled()
        for (id in listOf(R.string.scan_capture_sweep, R.string.scan_capture_slower, R.string.scan_capture_go_back)) {
            composeRule.onNodeWithText(text(id)).assertDoesNotExist()
        }
    }

    @Test
    fun `a whole home offers Next room beside Done`() {
        var next = 0
        composeRule.setContent {
            ScanControls(instruction = null, doneEnabled = true, onFinish = {}, onNextRoom = { next++ })
        }
        composeRule.onNodeWithTag(SCAN_NEXT_ROOM_TAG).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(SCAN_FINISH_TAG).assertIsDisplayed()
        assertEquals(1, next)
    }

    @Test
    fun `review builds the model, it does not save a scan`() {
        composeRule.setContent {
            ScanReviewCard(empty = false, figures = "1 200 points · 0:42", onKeepScanning = {}, onSave = {})
        }
        composeRule.onNodeWithText(CaptureChrome.BUILD_MODEL).assertIsDisplayed()
        composeRule.onNodeWithText(CaptureChrome.REVIEW_DETAIL).assertIsDisplayed()
        composeRule.onNodeWithText(ScanCopy.SAVE_SCAN).assertDoesNotExist()
        composeRule.onNodeWithTag(SCAN_STEPS_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, CaptureChrome.stepDescription(ScanStep.Review)))
    }

    @Test
    fun `a room whose walls are closed over a hatched floor is not shown complete`() {
        composeRule.setContent {
            ScanRoomGauge(share = CaptureChrome.share(1f, wallsDone = true, floorShare = 0.3f), complete = false)
        }
        val said = composeRule.onNodeWithTag(SCAN_ROOM_GAUGE_TAG).fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription]
        assertTrue(said, !said.contains("100"))
    }

    @Test
    fun `the top is one pill, duration and floor area`() {
        composeRule.setContent { ScanHud(seconds = 23f, floorSquareMetres = 14f) }
        composeRule.onNodeWithTag(SCAN_HUD_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("0:23", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("14", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(ScanCopy.PAUSED, substring = true).assertDoesNotExist()
    }
}
