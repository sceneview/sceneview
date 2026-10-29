package io.github.sceneview.demo

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #4539: the dock's Settings item must open a sheet you can see, on every demo.
 *
 * A demo that passes no `controls` (AR Surfaces, the AR View tab, AR Face…) still gets the
 * sheet for Send feedback and QA mode. That sheet is shorter than the peek detent, so its
 * peek is its own height — and it stayed parked under the window's bottom edge: Settings
 * hid the dock and showed nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoScaffoldSettingsSheetTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settings_withoutControls_opensAVisibleSheet() {
        setScaffold(withControls = false)
        openSettingsAndAssertVisible()
        // Nothing of its own: the whole sheet is up, down to the app-level actions.
        composeRule.onNodeWithTag(DemoScaffoldTestTags.FEEDBACK_ACTION).assertIsDisplayed()
        composeRule.onNodeWithTag(DemoScaffoldTestTags.QA_MODE_ACTION).assertIsDisplayed()
    }

    @Test
    fun settings_withControls_opensAVisibleSheet() {
        setScaffold(withControls = true)
        // Taller than the peek: it rests at the peek, controls first.
        openSettingsAndAssertVisible()
        composeRule.onNodeWithTag(PROBE_CONTROLS).assertIsDisplayed()
    }

    private fun setScaffold(withControls: Boolean) {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                DemoScaffold(
                    title = if (withControls) "Sheet with controls" else "Sheet without controls",
                    onBack = {},
                    controls = if (withControls) {
                        { Box(Modifier.fillMaxWidth().height(420.dp).testTag(PROBE_CONTROLS)) }
                    } else {
                        null
                    },
                ) {
                    Box(Modifier.fillMaxSize().testTag(PROBE_SCENE))
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun openSettingsAndAssertVisible() {
        val window = composeRule.onNodeWithTag(PROBE_SCENE).getUnclippedBoundsInRoot()
        composeRule.onNodeWithTag(DemoScaffoldTestTags.SETTINGS_FAB).performClick()
        composeRule.waitForIdle()

        val sheet = composeRule.onNodeWithTag(DemoScaffoldTestTags.SETTINGS_SHEET)
            .getUnclippedBoundsInRoot()
        assertTrue(
            "the sheet must rise above the window's bottom edge (top ${sheet.top}, window ${window.bottom})",
            sheet.top < window.bottom - 100.dp,
        )
        composeRule.onNodeWithTag(DemoScaffoldTestTags.SETTINGS_CLOSE).assertIsDisplayed()
    }

    private companion object {
        const val PROBE_SCENE = "probe-scene"
        const val PROBE_CONTROLS = "probe-controls"
    }
}
