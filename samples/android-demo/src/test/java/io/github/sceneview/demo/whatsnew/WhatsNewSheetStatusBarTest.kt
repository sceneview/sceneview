package io.github.sceneview.demo.whatsnew

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.sceneview.demo.common.ProvideStatusBarIcons
import io.github.sceneview.demo.common.RequestLightStatusBarIcons
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #4006: the sheet's surface must stay below the status bar over the home stage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WhatsNewSheetStatusBarTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sinceSheetClearsStatusBarInLightTheme() = assertSheetClearsStatusBar(darkTheme = false) {
        SinceSheet()
    }

    @Test
    fun sinceSheetClearsStatusBarInDarkTheme() = assertSheetClearsStatusBar(darkTheme = true) {
        SinceSheet()
    }

    @Test
    fun releaseSheetClearsStatusBarWhenExpandedInLightTheme() =
        assertSheetClearsStatusBar(darkTheme = false) { ReleaseSheet() }

    @Test
    fun releaseSheetClearsStatusBarWhenExpandedInDarkTheme() =
        assertSheetClearsStatusBar(darkTheme = true) { ReleaseSheet() }

    private fun assertSheetClearsStatusBar(darkTheme: Boolean, sheet: @Composable () -> Unit) {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = darkTheme) {
                ProvideStatusBarIcons {
                    // The home stage keeps requesting white icons while the sheet is open.
                    RequestLightStatusBarIcons()
                    Box(Modifier.fillMaxSize())
                    sheet()
                }
            }
        }
        composeRule.waitForIdle()

        val surface = composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle),
        )
        val sheetView = (surface.fetchSemanticsNode().root as ViewRootForTest).view
        val density = sheetView.resources.displayMetrics.density
        // Robolectric has no real status bar. Dispatch a phone's inset to the dialog's
        // own Compose view, not the activity's: ModalBottomSheet has a separate window.
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (24 * density).toInt(), 0, 0))
            .build()
        composeRule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(sheetView, insets) }
        composeRule.waitForIdle()
        surface.performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        val bounds = surface.getUnclippedBoundsInRoot()
        assertTrue("the long sheet must be expanded near the top: $bounds", bounds.top < 96.dp)
        assertTrue("the sheet surface covers the 24 dp status bar: $bounds", bounds.top >= 24.dp)
    }

    @Composable
    private fun SinceSheet() {
        WhatsNewSinceSheet(
            sections = List(20) { index ->
                WhatsNewSection(
                    version = "4.$index.0",
                    date = null,
                    title = null,
                    entries = listOf(WhatsNewEntry("entry-$index", WhatsNewCategory.Fixed, "An improvement")),
                )
            },
            seenVersion = "4.0.0",
            onDemoClick = {},
            onMarkSeen = {},
            onDismiss = {},
        )
    }

    @Composable
    private fun ReleaseSheet() {
        WhatsNewSheet(
            releases = List(20) { index ->
                WhatsNewRelease(
                    version = "4.$index.0",
                    date = null,
                    title = null,
                    highlights = listOf(WhatsNewHighlight(WhatsNewCategory.Fixed, "An improvement")),
                )
            },
            inReviewDemos = emptyList(),
            onDemoClick = {},
            onDismiss = {},
        )
    }
}
