package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARCoreAvailabilityOverlayTags
import io.github.sceneview.ar.ARCoreAvailabilityState
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlacementResumeFailureTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun resumeFailureShowsRetryUntilTheSessionResumesSuccessfully() {
        var availability by mutableStateOf<ARCoreAvailability?>(null)
        var retries = 0
        rule.setContent {
            SceneViewDemoTheme {
                Box(Modifier.fillMaxSize()) {
                    availability?.let {
                        PlacementAvailabilityOverlay(
                            ARCoreAvailabilityState(it, retry = { retries++ }),
                        )
                    }
                }
            }
        }
        rule.onNodeWithTag(ARCoreAvailabilityOverlayTags.CARD).assertDoesNotExist()

        rule.runOnIdle { availability = ARCoreAvailability.SessionFailed }
        rule.onNodeWithText("Try again").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, retries) }
        rule.onNodeWithTag(ARCoreAvailabilityOverlayTags.CARD).assertIsDisplayed()

        rule.runOnIdle { availability = null }
        rule.onNodeWithTag(ARCoreAvailabilityOverlayTags.CARD).assertDoesNotExist()
    }
}
