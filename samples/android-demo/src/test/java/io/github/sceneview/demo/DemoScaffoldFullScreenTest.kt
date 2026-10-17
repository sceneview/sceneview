package io.github.sceneview.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #4558: every scene screen gives the 3D view the whole screen in one tap.
 *
 * The control lives once in [DemoScaffold], so asserting it here covers every sample:
 * the button is in the identity row, a tap hides the row, the dock and both overlay
 * slots, the scene keeps its full size, and the exit button brings everything back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoScaffoldFullScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun setScaffold() {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                DemoScaffold(
                    title = "Full screen",
                    onBack = {},
                    topOverlay = {
                        Box(Modifier.fillMaxWidth().height(24.dp).testTag(PROBE_TOP))
                    },
                    bottomOverlay = {
                        Box(Modifier.fillMaxWidth().height(24.dp).testTag(PROBE_BOTTOM))
                    },
                ) {
                    Box(Modifier.fillMaxSize().testTag(PROBE_SCENE))
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun fullScreen_hidesEveryChrome_andExitBringsItBack() {
        setScaffold()
        val sceneBefore = composeRule.onNodeWithTag(PROBE_SCENE).getUnclippedBoundsInRoot()
        composeRule.onNodeWithTag(DemoScaffoldTestTags.IDENTITY_ROW).assertIsDisplayed()
        composeRule.onNodeWithTag(PROBE_TOP).assertIsDisplayed()
        composeRule.onNodeWithTag(PROBE_BOTTOM).assertIsDisplayed()

        composeRule.onNodeWithTag(DemoScaffoldTestTags.FULL_SCREEN).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DemoScaffoldTestTags.IDENTITY_ROW).assertDoesNotExistOrHidden()
        composeRule.onNodeWithTag(PROBE_TOP).assertIsNotDisplayed()
        composeRule.onNodeWithTag(PROBE_BOTTOM).assertIsNotDisplayed()
        composeRule.onNodeWithTag(DemoScaffoldTestTags.FULL_SCREEN_EXIT).assertIsDisplayed()
        val sceneFull = composeRule.onNodeWithTag(PROBE_SCENE).getUnclippedBoundsInRoot()
        assertEquals("the scene must keep the whole screen in full screen", sceneBefore, sceneFull)

        composeRule.onNodeWithTag(DemoScaffoldTestTags.FULL_SCREEN_EXIT).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DemoScaffoldTestTags.IDENTITY_ROW).assertIsDisplayed()
        composeRule.onNodeWithTag(PROBE_TOP).assertIsDisplayed()
        composeRule.onNodeWithTag(PROBE_BOTTOM).assertIsDisplayed()
    }

    @Test
    fun fullScreen_systemBackLeavesFullScreen_notTheDemo() {
        var backs = 0
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                DemoScaffold(title = "Back", onBack = { backs++ }) {
                    Box(Modifier.fillMaxSize().testTag(PROBE_SCENE))
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DemoScaffoldTestTags.FULL_SCREEN).performClick()
        composeRule.waitForIdle()

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        assertEquals("Back in full screen must not leave the demo", 0, backs)
        composeRule.onNodeWithTag(DemoScaffoldTestTags.IDENTITY_ROW).assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistOrHidden() {
        val nodes = fetchSemanticsNodes()
        if (nodes.isNotEmpty()) assertIsNotDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.fetchSemanticsNodes() =
        runCatching { listOf(fetchSemanticsNode()) }.getOrDefault(emptyList())

    private companion object {
        const val PROBE_TOP = "probe-top"
        const val PROBE_BOTTOM = "probe-bottom"
        const val PROBE_SCENE = "probe-scene"
    }
}
