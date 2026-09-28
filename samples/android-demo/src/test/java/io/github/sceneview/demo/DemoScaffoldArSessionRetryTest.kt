package io.github.sceneview.demo

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.sceneview.ar.ARCoreAvailabilityOverlayTags
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The AR failure card is a way back into AR, not a dead end (#4062).
 *
 * Ten AR demos swap their viewport for a failure card when the session cannot start. That
 * card used to say "Return to the catalog and reopen this demo" with nothing to press, while
 * the other AR demos showed the SDK's "Couldn't start AR" card with a Try again action. A
 * demo that passes `onArSessionRetry` now gets that same card, and Try again puts its scene
 * back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoScaffoldArSessionRetryTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tryAgain_remountsTheScene() {
        var retries = 0
        composeRule.setContent {
            var failed by remember { mutableStateOf(true) }
            SceneViewDemoTheme(darkTheme = false) {
                DemoScaffold(
                    title = "AR",
                    onBack = {},
                    arSessionFailed = failed,
                    onArSessionRetry = {
                        retries++
                        failed = false
                    },
                ) {
                    Text(SCENE)
                }
            }
        }

        composeRule.onNodeWithTag(ARCoreAvailabilityOverlayTags.CARD).assertIsDisplayed()
        composeRule.onNodeWithText(SCENE).assertDoesNotExist()

        composeRule.onNodeWithTag(ARCoreAvailabilityOverlayTags.ACTION).performClick()
        composeRule.waitForIdle()

        assertEquals(1, retries)
        composeRule.onNodeWithText(SCENE).assertIsDisplayed()
        composeRule.onNodeWithTag(ARCoreAvailabilityOverlayTags.CARD).assertDoesNotExist()
    }

    @Test
    fun withoutRetry_keepsTheStatusCard() {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = true) {
                DemoScaffold(title = "AR", onBack = {}, arSessionFailed = true) {
                    Text(SCENE)
                }
            }
        }

        composeRule.onNodeWithTag(ARCoreAvailabilityOverlayTags.CARD).assertDoesNotExist()
        composeRule.onNodeWithText(SCENE).assertDoesNotExist()
    }

    private companion object {
        const val SCENE = "scene-content"
    }
}
