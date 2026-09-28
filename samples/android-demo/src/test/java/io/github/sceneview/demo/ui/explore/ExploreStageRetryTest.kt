package io.github.sceneview.demo.ui.explore

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #3993: the featured stage of the online gallery ends a failed load with a card
 * and a retry, and the retry really restarts the load.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExploreStageRetryTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `Try again on the failure card puts the stage back into loading`() {
        var status by mutableStateOf(FeedsStatus.Failed)
        var retries = 0
        rule.setContent {
            SceneViewDemoTheme {
                ExploreStage(
                    hero = null,
                    feedsStatus = status,
                    sourceName = "Sketchfab",
                    sketchfabUnavailable = false,
                    // What ExploreTabScreen's retry does: bump refreshTick, whose
                    // effect sets the status back to Loading before refetching.
                    onRetry = {
                        retries++
                        status = FeedsStatus.Loading
                    },
                    onModelClick = {},
                )
            }
        }

        rule.onNodeWithTag(ExploreTestTags.FEED_UNAVAILABLE).assertIsDisplayed()
        rule.onNodeWithText("Couldn't reach Sketchfab").assertIsDisplayed()

        rule.onNodeWithText("Try again").performClick()

        assertEquals(1, retries)
        assertEquals(FeedsStatus.Loading, status)
        rule.onNodeWithTag(ExploreTestTags.FEED_UNAVAILABLE).assertDoesNotExist()
        rule.onNodeWithTag(ExploreTestTags.STAGE_LOADING).assertIsDisplayed()
    }

    @Test
    fun `a rejected Sketchfab key shows no connection card, since its banner explains it`() {
        rule.setContent {
            SceneViewDemoTheme {
                ExploreStage(
                    hero = null,
                    feedsStatus = FeedsStatus.Failed,
                    sourceName = "Sketchfab",
                    sketchfabUnavailable = true,
                    onRetry = {},
                    onModelClick = {},
                )
            }
        }

        rule.onNodeWithTag(ExploreTestTags.FEED_UNAVAILABLE).assertDoesNotExist()
        rule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `every feed empty says so instead of blaming the connection`() {
        rule.setContent {
            SceneViewDemoTheme {
                ExploreStage(
                    hero = null,
                    feedsStatus = FeedsStatus.Empty,
                    sourceName = "Poly Haven",
                    sketchfabUnavailable = false,
                    onRetry = {},
                    onModelClick = {},
                )
            }
        }

        rule.onNodeWithText("Nothing on Poly Haven right now").assertIsDisplayed()
    }
}
