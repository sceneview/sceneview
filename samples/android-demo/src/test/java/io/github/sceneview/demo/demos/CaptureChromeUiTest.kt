package io.github.sceneview.demo.demos

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.sceneview.demo.demos.internal.CaptureChrome
import io.github.sceneview.demo.demos.internal.CaptureCue
import io.github.sceneview.demo.demos.internal.ScanActivity
import io.github.sceneview.demo.demos.internal.ScanBlocker
import io.github.sceneview.demo.demos.internal.ScanCopy
import io.github.sceneview.demo.demos.internal.ScanPhase
import io.github.sceneview.demo.demos.internal.ScanStep
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The capture screen's top and bottom on the device: the primary action reads as the step it leads
 * to, the step row says where the capture is, one line says either what blocks or what comes next,
 * never both, and the review card builds a model instead of "saving".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureChromeUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the capture's bottom names its next step and its primary action`() {
        composeRule.setContent {
            ScanControls(
                phase = ScanPhase.Scanning,
                cue = CaptureCue.Next(ScanCopy.FLOOR_LEFT),
                onTogglePause = {},
                onFinish = {},
            )
        }
        composeRule.onNodeWithText(CaptureChrome.STOP_AND_REVIEW).assertIsDisplayed()
        composeRule.onNodeWithText(ScanCopy.FLOOR_LEFT, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("${CaptureChrome.NEXT}:", substring = true).assertIsDisplayed()
        for (step in ScanStep.entries) composeRule.onNodeWithText(step.label).assertIsDisplayed()
        composeRule.onNodeWithText(ScanCopy.FINISH_SCAN).assertDoesNotExist()
    }

    @Test
    fun `a blocker shows its cause and its way out, and no next line`() {
        composeRule.setContent {
            ScanControls(
                phase = ScanPhase.Scanning,
                cue = CaptureCue.Blocked(ScanBlocker.TooFast),
                onTogglePause = {},
                onFinish = {},
            )
        }
        composeRule.onNodeWithText(ScanBlocker.TooFast.cause).assertIsDisplayed()
        composeRule.onNodeWithText(ScanBlocker.TooFast.wayOut).assertIsDisplayed()
        composeRule.onNodeWithText("${CaptureChrome.NEXT}:", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag(SCAN_BLOCKER_TAG)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
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
    fun `while the model builds, the top card says so and never paused`() {
        composeRule.setContent {
            ScanHud(phase = ScanPhase.Saving, seconds = 42f, points = 1200, label = "Room", activity = ScanActivity.Paused)
        }
        composeRule.onNodeWithText("Building the model", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(ScanCopy.PAUSED, substring = true).assertDoesNotExist()
    }
}
