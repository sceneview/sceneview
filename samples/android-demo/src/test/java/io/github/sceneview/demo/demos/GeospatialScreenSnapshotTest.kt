package io.github.sceneview.demo.demos

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.sceneview.demo.common.GEOSPATIAL_ACCURACY_TAG
import io.github.sceneview.demo.common.GEOSPATIAL_DROP_BUTTON_TAG
import io.github.sceneview.demo.common.GEOSPATIAL_DROP_FEEDBACK_TAG
import io.github.sceneview.demo.common.GEOSPATIAL_STATUS_CARD_TAG
import io.github.sceneview.demo.demos.internal.GeospatialScenario
import io.github.sceneview.demo.demos.internal.frame
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the Geospatial Anchors screen (#3832) in the states the QA video could not
 * show well: the real [GeospatialScreen] — scaffold, Terrain / Rooftop / Clear dock and
 * the status card with its Drop button — over a flat stand-in for the camera feed.
 *
 * What it is **not**: proof that Earth localizes, that a drop resolves, or that the model
 * appears on the anchor. No ARCore session starts off-device (#2754); those checks are in
 * the PR's `needs-device` list. This is proof of *layout, copy and the enabled state of
 * the one primary action* — and that there is exactly one status surface on screen.
 *
 * Every frame comes from [GeospatialScenario.frame], the same source the demo's
 * `--es qa_state` deep link renders, so a golden cannot drift from the emulator capture of
 * the same name. White and black grounds are the two ends of what a camera feed can put
 * behind the card.
 *
 * Re-record after a deliberate UI change:
 *   `./gradlew :samples:android-demo:recordRoborazziDebug --tests '*GeospatialScreenSnapshotTest*'`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GeospatialScreenSnapshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun starting_dark_onBlack() {
        capture(GeospatialScenario.Starting, darkTheme = true, ground = Color.Black)
        composeRule.onNodeWithTag(GEOSPATIAL_DROP_BUTTON_TAG).assertIsNotEnabled()
    }

    @Test
    fun improving_light_onWhite() {
        capture(GeospatialScenario.Improving, darkTheme = false, ground = Color.White)
        composeRule.onNodeWithTag(GEOSPATIAL_ACCURACY_TAG).assertExists()
        composeRule.onNodeWithTag(GEOSPATIAL_DROP_BUTTON_TAG).assertIsEnabled()
    }

    @Test
    fun improving_dark_onBlack() =
        capture(GeospatialScenario.Improving, darkTheme = true, ground = Color.Black)

    @Test
    fun locked_light_onWhite() {
        capture(GeospatialScenario.Locked, darkTheme = false, ground = Color.White)
        composeRule.onNodeWithTag(GEOSPATIAL_DROP_BUTTON_TAG).assertIsEnabled()
    }

    @Test
    fun locked_dark_onBlack() =
        capture(GeospatialScenario.Locked, darkTheme = true, ground = Color.Black)

    @Test
    fun takingLong_light_onBlack() =
        capture(GeospatialScenario.TakingLong, darkTheme = false, ground = Color.Black)

    @Test
    fun anchoredBelow_dark_onWhite() {
        capture(GeospatialScenario.AnchoredBelow, darkTheme = true, ground = Color.White)
        composeRule.onNodeWithTag(GEOSPATIAL_DROP_FEEDBACK_TAG).assertExists()
    }

    @Test
    fun dropFailed_light_onWhite() {
        capture(GeospatialScenario.DropFailed, darkTheme = false, ground = Color.White)
        composeRule.onNodeWithTag(GEOSPATIAL_DROP_FEEDBACK_TAG).assertExists()
    }

    @Test
    fun rooftop_dark_onBlack() =
        capture(GeospatialScenario.Rooftop, darkTheme = true, ground = Color.Black)

    private fun capture(scenario: GeospatialScenario, darkTheme: Boolean, ground: Color) {
        val frame = scenario.frame()
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = darkTheme) {
                GeospatialScreen(
                    onBack = {},
                    frame = frame,
                    dropCount = if (frame.lastDrop != null) 1 else 0,
                    onModeChange = {},
                    onDrop = {},
                    onClear = {},
                    blocker = {},
                    controls = {},
                ) {
                    Box(Modifier.fillMaxSize().background(ground))
                }
            }
        }
        composeRule.waitForIdle()
        // The card arrives through the scaffold's fade; idle is not settled.
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()

        // One status surface, never a card plus a second loader (#3832).
        assertEquals(
            "exactly one Geospatial status card on screen",
            1,
            composeRule.onAllNodesWithTag(GEOSPATIAL_STATUS_CARD_TAG).fetchSemanticsNodes().size,
        )

        val theme = if (darkTheme) "dark" else "light"
        val backdrop = if (ground == Color.Black) "black" else "white"
        composeRule.onRoot().captureRoboImage(
            "src/test/snapshots/geospatial_${scenario.name.lowercase()}-$theme-$backdrop.png",
            roborazziOptions = CROSS_PLATFORM_TOLERANT,
        )
    }

    private companion object {
        /**
         * The recipe `PlacementBottomAnchorSnapshotTest` measured: macOS-recorded goldens
         * verified on Linux runners differ by sub-perceptual rounding on anti-aliased
         * edges; every pixel stays compared.
         */
        val CROSS_PLATFORM_TOLERANT = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                imageComparator = SimpleImageComparator(maxDistance = 0.03f),
            ),
        )
    }
}
