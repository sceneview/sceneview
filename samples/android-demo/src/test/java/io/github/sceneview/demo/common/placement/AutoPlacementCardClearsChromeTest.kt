package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.sceneview.ar.ARCoreAvailabilityOverlayTags
import io.github.sceneview.demo.DEMO_MODE_PILL_TAG
import io.github.sceneview.demo.DemoModeSwitch
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.LocalDemoModeSwitch
import io.github.sceneview.demo.demos.autoPlacementGuidancePadding
import io.github.sceneview.demo.demos.AutoPlacementStartFailedQaOverlay
import io.github.sceneview.demo.fragments.ArPlacementFragment
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** One call uses the SDK's card, with the actual scaffold's measured chrome insets (#4525). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutoPlacementCardClearsChromeTest {
    private var pixelsPerDp = 1f

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun portraitLight() = assertCardClearsSelector(dark = false)

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun portraitDark() = assertCardClearsSelector(dark = true)

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-notnight-xhdpi")
    fun landscapeLight() = assertCardClearsSelector(dark = false)

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-night-xhdpi")
    fun landscapeDark() = assertCardClearsSelector(dark = true)

    private fun assertCardClearsSelector(dark: Boolean) {
        composeRule.setContent {
            pixelsPerDp = LocalDensity.current.density
            SceneViewDemoTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalDemoModeSwitch provides DemoModeSwitch(ArPlacementFragment.modes, selected = 2, onSelect = {}),
                ) {
                    DemoScaffold(title = "One call", onBack = {}, controls = { Text("Reset placement") }) {
                        // Exercise the emulator's QA seam, which draws the SDK card without ARCore.
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            AutoPlacementStartFailedQaOverlay(
                                contentPadding = autoPlacementGuidancePadding(maxWidth, recoveryCardShown = true),
                            )
                        }
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        val card = composeRule.onNodeWithTag(ARCoreAvailabilityOverlayTags.CARD).getUnclippedBoundsInRoot()
        val action = composeRule.onNodeWithTag(ARCoreAvailabilityOverlayTags.ACTION).getUnclippedBoundsInRoot()
        val title = composeRule.onNodeWithText("Couldn't start AR").getUnclippedBoundsInRoot()
        val visibleTitle = composeRule.onNodeWithText("Couldn't start AR").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "SDK failure title is clipped: $visibleTitle instead of $title",
            visibleTitle.height >= (title.bottom - title.top).value * pixelsPerDp - 1f,
        )
        val selector = composeRule.onNodeWithTag(DEMO_MODE_PILL_TAG).getUnclippedBoundsInRoot()
        assertTrue(
            "SDK card $card overlaps One call selector $selector",
            card.bottom <= selector.top + 0.75.dp || card.left >= selector.right - 0.75.dp ||
                card.right <= selector.left + 0.75.dp,
        )
        assertTrue("SDK action $action is clipped by its card $card", action.top >= card.top && action.bottom <= card.bottom)
        assertTrue("SDK action $action loses its touch target", action.bottom - action.top >= 40.dp)
        composeRule.onNodeWithText("Try again").performClick()
        composeRule.onNodeWithTag(ARCoreAvailabilityOverlayTags.CARD).assertExists()
    }
}
