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
 * Asserted on measured bounds, with the demo's real HUD, warning and legend, in the Pixel 4a's
 * two orientations and in one half of a split screen. The warning is measured as its whole pill
 * ([OUTDOOR_NOTICE_TAG]), not only its first line of text.
 *
 * Landscape runs at font scale 1.3, not 1.0: Robolectric draws no system bars, so the scaffold's
 * bands get the status and navigation bar heights back, and at 1.0 the old layout's legend
 * cleared the warning here while it covered it on the phone. At 1.3 the old layout overlaps.
 *
 * The split-screen half (393 × 400 dp) is short but narrow: the side-by-side layout does not fit
 * there — the HUD's "41 %" wrapped one character per line and the warning went under the dock —
 * so it must keep the portrait layout. The dock check runs below font scale 2 only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SceneSemanticsLegendPlacementTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun `landscape legend never covers the outdoor warning at font scale 1_3`() {
        assertWarningClearOfLegend(fontScale = 1.3f, legendInTopBand = true)
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

    @Test
    @Config(qualifiers = SPLIT_SCREEN)
    fun `split screen half keeps the portrait layout`() {
        assertWarningClearOfLegend(fontScale = 1.0f, legendInTopBand = false)
    }

    @Test
    @Config(qualifiers = SPLIT_SCREEN)
    fun `split screen half keeps the portrait layout at font scale 2`() {
        // 400 dp cannot hold the identity row, the HUD, the warning, the legend and the dock at
        // this scale (about 540 dp of content), in either layout: only the layout itself and the
        // HUD's unwrapped percentages are asserted here.
        assertWarningClearOfLegend(fontScale = 2.0f, legendInTopBand = false, checkFit = false)
    }

    /**
     * @param checkFit Require the warning pill to stay clear of the legend and, below font scale
     * 2, of the dock: under 400 dp of height that scale cannot stack the identity row, the HUD and
     * the warning above the dock, whichever band the legend is in.
     */
    private fun assertWarningClearOfLegend(
        fontScale: Float,
        legendInTopBand: Boolean,
        checkFit: Boolean = true,
    ) {
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
        val warning = composeRule.onNodeWithTag(OUTDOOR_NOTICE_TAG).getUnclippedBoundsInRoot()
        val legend = composeRule.onNodeWithTag(SEMANTIC_LEGEND_TAG).getUnclippedBoundsInRoot()
        val topBand = composeRule.onNodeWithTag(DemoScaffoldTestTags.TOP_OVERLAY)
            .getUnclippedBoundsInRoot()
        val dock = composeRule.onNodeWithTag(DemoScaffoldTestTags.DOCK).getUnclippedBoundsInRoot()

        val percent = composeRule.onNodeWithText(TOP_PERCENT).getUnclippedBoundsInRoot()

        assertTrue(
            "at font scale $fontScale the HUD's \"$TOP_PERCENT\" $percent wraps onto several lines",
            percent.right - percent.left > percent.bottom - percent.top,
        )
        if (checkFit && fontScale < 2f) {
            assertFalse(
                "at font scale $fontScale the outdoor warning $warning runs under the dock $dock",
                warning.intersects(dock),
            )
        }
        if (checkFit) {
            assertFalse(
                "at font scale $fontScale the legend $legend overlaps the outdoor warning $warning",
                warning.intersects(legend),
            )
        }
        // Side by side, the legend is the end half of a Row in the top band; in portrait it is
        // start-aligned in the bottom band. In a 400 dp window the two bands overlap, so the
        // layout is told by the legend's column, not by which band's bounds contain it.
        val besideHud = legend.left >= (topBand.left + topBand.right) / 2
        assertTrue(
            "the legend $legend should be " +
                (if (legendInTopBand) "beside the HUD in the top band" else "in the bottom band") +
                " (top band: $topBand)",
            legendInTopBand == besideHud && (!legendInTopBand || topBand.contains(legend)),
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

        /** One half of a split screen on the Pixel 4a in portrait: short, but phone-narrow. */
        const val SPLIT_SCREEN = "w393dp-h400dp-xxhdpi"

        /** The HUD's widest percentage, on its first row. */
        const val TOP_PERCENT = "41 %"

        /** The three rows the HUD shows once an indoor recording has settled. */
        val SETTLED_LABELS = listOf(
            LabelFraction(SemanticLabel.BUILDING, 0.41f),
            LabelFraction(SemanticLabel.SIDEWALK, 0.39f),
            LabelFraction(SemanticLabel.STRUCTURE, 0.10f),
        )
    }
}
