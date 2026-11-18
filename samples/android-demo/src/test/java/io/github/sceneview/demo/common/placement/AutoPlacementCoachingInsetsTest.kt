package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import io.github.sceneview.ar.ARCoachingOverlay
import io.github.sceneview.ar.ArGuidanceCue
import io.github.sceneview.demo.DemoModeSwitch
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.LocalDemoChromeBottomInset
import io.github.sceneview.demo.LocalDemoModeSwitch
import io.github.sceneview.demo.demos.autoPlacementGuidancePadding
import io.github.sceneview.demo.fragments.ArPlacementFragment
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Recovery-card positioning must not shorten the existing landscape coaching band. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutoPlacementCoachingInsetsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun largeText_preservesTheCoachingBand() = verify(fontScale = 1.5f)

    @Test
    fun maximumText_preservesTheCoachingBand() = verify(fontScale = 2f)

    private fun verify(fontScale: Float) {
        var useHostPadding by mutableStateOf(false)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalInspectionMode provides true,
                LocalDemoModeSwitch provides DemoModeSwitch(ArPlacementFragment.modes, selected = 2, onSelect = {}),
            ) {
                SceneViewDemoTheme(darkTheme = false) {
                    DemoScaffold(title = "One call", onBack = {}, controls = { Text("Reset placement") }) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            ARCoachingOverlay(
                                cue = ArGuidanceCue.SCAN,
                                contentPadding = if (useHostPadding) {
                                    autoPlacementGuidancePadding(maxWidth, recoveryCardShown = false)
                                } else {
                                    // Before recovery-card positioning, coaching only cleared the dock.
                                    PaddingValues(bottom = LocalDemoChromeBottomInset.current)
                                },
                            )
                        }
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        val card = composeRule.onNodeWithContentDescription(
            "Move your phone slowly. Point it at the floor or a table",
        )
        val baseline = card.getUnclippedBoundsInRoot()
        composeRule.runOnIdle { useHostPadding = true }
        composeRule.mainClock.advanceTimeBy(1_000)
        assertEquals(
            "Recovery insets must preserve the coaching card's size and position at $fontScale font scale",
            baseline,
            card.getUnclippedBoundsInRoot(),
        )
    }
}
