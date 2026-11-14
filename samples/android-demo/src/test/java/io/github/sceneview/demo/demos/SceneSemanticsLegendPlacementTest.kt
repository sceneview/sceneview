package io.github.sceneview.demo.demos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import com.google.ar.core.SemanticLabel
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoScaffoldTestTags
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #4036: on a phone held sideways the Scene Semantics color legend sat across the lower half of
 * the permanent "Trained on outdoor scenes" warning. The two live in the scaffold's two
 * independent overlay bands — the top one below the identity row, the bottom one above the
 * dock — and a 393 dp window (Pixel 4a, landscape) is too short for both.
 *
 * Asserted on measured bounds, with the demo's real HUD, warning and legend, at font scale 1.0
 * and 2.0, in the Pixel 4a's two orientations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SceneSemanticsLegendPlacementTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun `landscape legend never covers the outdoor warning`() {
        assertWarningClearOfLegend(fontScale = 1.0f, legendInTopBand = true)
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun `landscape legend never covers the outdoor warning at font scale 2`() {
        assertWarningClearOfLegend(fontScale = 2.0f, legendInTopBand = true)
    }

    @Test
    @Config(qualifiers = PORTRAIT)
    fun `portrait keeps the legend in the bottom band below the warning`() {
        assertWarningClearOfLegend(fontScale = 1.0f, legendInTopBand = false)
    }

    @Test
    @Config(qualifiers = PORTRAIT)
    fun `portrait keeps the legend in the bottom band at font scale 2`() {
        assertWarningClearOfLegend(fontScale = 2.0f, legendInTopBand = false)
    }

    private fun assertWarningClearOfLegend(fontScale: Float, legendInTopBand: Boolean) {
        composeRule.setContent {
            ScaledDensity(fontScale) {
                SceneViewDemoTheme(darkTheme = false) {
                    DemoScaffold(
                        title = "Recognise outdoor surfaces",
                        onBack = {},
                        topOverlay = {
                            SceneSemanticsTopBand(legendShown = true) {
                                SceneSemanticsLabelHud(labels = SETTLED_LABELS)
                                SceneSemanticsOutdoorNotice(
                                    arSessionBlocked = false,
                                    semanticsSupported = true,
                                    semanticsEverReceived = true,
                                    sceneUnclassified = false,
                                )
                            }
                        },
                        bottomOverlay = {
                            SceneSemanticsBottomLegend(legendShown = true)
                        },
                    ) {}
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SEMANTIC_LEGEND_TAG).assertIsDisplayed()
        val warning = composeRule.onNodeWithText(OUTDOOR_WARNING).getUnclippedBoundsInRoot()
        val legend = composeRule.onNodeWithTag(SEMANTIC_LEGEND_TAG).getUnclippedBoundsInRoot()
        val topBand = composeRule.onNodeWithTag(DemoScaffoldTestTags.TOP_OVERLAY)
            .getUnclippedBoundsInRoot()

        assertFalse(
            "at font scale $fontScale the legend $legend overlaps the outdoor warning $warning",
            warning.intersects(legend),
        )
        assertTrue(
            "the legend $legend should be in the " +
                (if (legendInTopBand) "top" else "bottom") + " band (top band: $topBand)",
            legendInTopBand == topBand.contains(legend),
        )
    }

    private fun DpRect.intersects(other: DpRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    private fun DpRect.contains(other: DpRect): Boolean =
        other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom

    @Composable
    private fun ScaledDensity(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale),
            content = content,
        )
    }

    private companion object {
        /** The Pixel 4a held sideways: 2340 × 1080 px at 440 dpi. */
        const val LANDSCAPE = "w851dp-h393dp-land-xxhdpi"
        const val PORTRAIT = "w393dp-h851dp-port-xxhdpi"

        const val OUTDOOR_WARNING = "Trained on outdoor scenes — labels are unreliable indoors"

        /** The three rows the HUD shows once an indoor recording has settled. */
        val SETTLED_LABELS = listOf(
            LabelFraction(SemanticLabel.BUILDING, 0.41f),
            LabelFraction(SemanticLabel.SIDEWALK, 0.39f),
            LabelFraction(SemanticLabel.STRUCTURE, 0.10f),
        )
    }
}
