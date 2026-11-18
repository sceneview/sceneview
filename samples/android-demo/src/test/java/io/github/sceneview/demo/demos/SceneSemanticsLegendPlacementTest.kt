package io.github.sceneview.demo.demos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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
 * and neither does the portrait one: on the phone, from 130 % text up, the legend in the bottom
 * band covered the warning, and the HUD too at 200 %. There the legend goes under the warning in
 * the top band, which ends above the dock and scrolls what does not fit.
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
        assertLegendPlacement(fontScale = 1.3f, expected = LegendPlace.BesideHud)
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun `landscape legend never covers the outdoor warning at font scale 2`() {
        assertLegendPlacement(fontScale = 2.0f, expected = LegendPlace.BesideHud)
    }

    @Test
    @Config(qualifiers = PORTRAIT)
    fun `portrait keeps the legend in the bottom band below the warning`() {
        assertLegendPlacement(fontScale = 1.0f, expected = LegendPlace.BottomBand)
    }

    @Test
    @Config(qualifiers = PORTRAIT)
    fun `portrait keeps the legend in the bottom band at font scale 2`() {
        assertLegendPlacement(fontScale = 2.0f, expected = LegendPlace.BottomBand)
    }

    @Test
    @Config(qualifiers = SPLIT_SCREEN)
    fun `split screen half stacks the legend under the warning, all of it above the dock`() {
        assertLegendPlacement(fontScale = 1.0f, expected = LegendPlace.UnderWarning)
        // At the default text size everything fits: nothing to scroll to.
        val legend = composeRule.onNodeWithTag(SEMANTIC_LEGEND_TAG).getUnclippedBoundsInRoot()
        val dock = composeRule.onNodeWithTag(DemoScaffoldTestTags.DOCK).getUnclippedBoundsInRoot()
        assertFalse("the legend $legend runs under the dock $dock", legend.intersects(dock))
    }

    @Test
    @Config(qualifiers = SPLIT_SCREEN)
    fun `split screen half keeps the legend off the warning at font scale 1_3`() {
        // The phone's largest text size on Android 13, where the legend covered the warning.
        assertLegendPlacement(fontScale = 1.3f, expected = LegendPlace.UnderWarning)
    }

    @Test
    @Config(qualifiers = SPLIT_SCREEN)
    fun `split screen half keeps the legend off the warning and the HUD at font scale 2`() {
        // 400 dp cannot show the identity row, the HUD, the warning, the legend and the dock at
        // this scale (about 540 dp of content): the band ends above the dock and scrolls.
        assertLegendPlacement(fontScale = 2.0f, expected = LegendPlace.UnderWarning)
    }

    /** Where the color legend is expected for a window. */
    private enum class LegendPlace {
        /** Tall window: start-aligned in the bottom band, above the dock. */
        BottomBand,

        /** Short and wide: the end half of a Row in the top band. */
        BesideHud,

        /** Short and narrow: under the warning, in the top band's one scrolling Column. */
        UnderWarning,
    }

    private fun assertLegendPlacement(fontScale: Float, expected: LegendPlace) {
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
        assertFalse(
            "at font scale $fontScale the legend $legend overlaps the outdoor warning $warning",
            warning.intersects(legend),
        )
        val besideHud = legend.left >= (topBand.left + topBand.right) / 2
        if (expected == LegendPlace.UnderWarning) {
            assertStackedAboveTheDock(fontScale, warning = warning, legend = legend, dock = dock)
            assertFalse("the legend $legend should be start-aligned, not beside the HUD", besideHud)
            return
        }
        composeRule.onNodeWithTag(SEMANTIC_LEGEND_TAG).assertIsDisplayed()
        if (fontScale < 2f) {
            assertFalse(
                "at font scale $fontScale the outdoor warning $warning runs under the dock $dock",
                warning.intersects(dock),
            )
        }
        // Side by side, the legend is the end half of a Row in the top band; in portrait it is
        // start-aligned in the bottom band.
        val legendInTopBand = expected == LegendPlace.BesideHud
        assertTrue(
            "the legend $legend should be " +
                (if (legendInTopBand) "beside the HUD in the top band" else "in the bottom band") +
                " (top band: $topBand)",
            legendInTopBand == besideHud && (!legendInTopBand || topBand.contains(legend)),
        )
    }

    /**
     * The short and narrow window: HUD, warning and legend are one Column that stops above the
     * dock. What does not fit is clipped by that Column, never drawn under the dock, and each of
     * the three can be scrolled fully into view.
     */
    private fun assertStackedAboveTheDock(fontScale: Float, warning: DpRect, legend: DpRect, dock: DpRect) {
        val band = composeRule.onNodeWithTag(SEMANTIC_STACKED_BAND_TAG).getUnclippedBoundsInRoot()
        assertTrue(
            "at font scale $fontScale the band $band runs under the dock $dock",
            band.bottom <= dock.top,
        )
        assertTrue(
            "at font scale $fontScale the legend $legend is not under the warning $warning",
            legend.top >= warning.bottom,
        )
        for (tag in listOf(OUTDOOR_NOTICE_TAG, SEMANTIC_LEGEND_TAG)) {
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val shown = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue(
                "at font scale $fontScale, scrolled to, $tag $shown is not wholly inside the band $band",
                shown.top >= band.top && shown.bottom <= band.bottom,
            )
        }
        composeRule.onNodeWithText(TOP_PERCENT).performScrollTo().assertIsDisplayed()
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
