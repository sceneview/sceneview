package io.github.sceneview.demo.common.placement

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.md_theme_dark_outline
import io.github.sceneview.demo.theme.md_theme_dark_outlineVariant
import io.github.sceneview.demo.theme.md_theme_dark_surface
import io.github.sceneview.demo.theme.md_theme_dark_surfaceContainerHigh
import io.github.sceneview.demo.theme.md_theme_light_outline
import io.github.sceneview.demo.theme.md_theme_light_outlineVariant
import io.github.sceneview.demo.theme.md_theme_light_surface
import io.github.sceneview.demo.theme.md_theme_light_surfaceContainerHigh
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The chooser's catalogue scrolls **under** its top bar — so the top bar has to be an edge
 * the eye can name, in both schemes.
 *
 * ## The defect these tests would have caught
 *
 * Moving `contentPadding` inside the scroll is what lets the last card row clear the CTA
 * bar while the viewport stays the whole screen; it also means content now slides under the
 * *top* bar. A bare `TopAppBar` is `surface` on a `surface` page — **1.00:1**, in light as
 * in dark — so that change swapped a hard stop at the bottom for an invisible boundary at
 * the top: text simply dissolved into the bar. The screen recreated at the top exactly the
 * "edge nothing draws" it fixes at the bottom.
 *
 * ## What is asserted
 *
 *  - the viewport really is the whole window (otherwise there is nothing to slide under,
 *    and the boundary question is moot);
 *  - the bar really does draw a hairline, full width, at the documented thickness;
 *  - the colours that boundary is made of are not the page colour, and clear the
 *    non-text 3:1 bar in dark.
 *
 * ## Light is drawn but not yet conformant, and that is on purpose
 *
 * `outline` is the role that carries WCAG 1.4.11 in this theme, and #3681 solved its *dark*
 * value for the ratio: 6.26:1 on the page, 4.08:1 on the raised bar. Its light value —
 * `#D6DAE0` — is 1.40:1 and 1.26:1. Nothing in the light ramp sits between it and
 * `onSurfaceVariant`'s `#3D4654`; a 3:1 hairline on white needs roughly `#8D8D8D`. Moving
 * the light token lands on every control border in the app, so it belongs in the light-ramp
 * pull request next to the dark one, not here. The light assertions below therefore pin
 * only that the boundary is *drawn* — that the regression back to 1.00:1 cannot return —
 * and they keep passing, unchanged, once the light ramp is fixed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlacementChooserBoundaryTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ── Layout: there is something to slide under ─────────────────────────────────────

    @Test
    fun catalogue_scrollsUnderTheTopBar_ratherThanStoppingAtIt() {
        renderChooser(darkTheme = false)

        val window = composeRule.onRoot().getUnclippedBoundsInRoot()
        val catalogue = composeRule
            .onNodeWithTag(PlacementChooserTestTags.CATALOGUE)
            .getUnclippedBoundsInRoot()
        val edge = composeRule
            .onNodeWithTag(PlacementChooserTestTags.TOP_BAR_EDGE)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "the scroll viewport starts at ${catalogue.top} instead of the top of the " +
                "window: `contentPadding` is back outside the scroll, and the catalogue " +
                "stops dead at an edge nothing draws instead of sliding under the bar.",
            catalogue.top <= 0.dp,
        )
        assertTrue(
            "the scroll viewport ends at ${catalogue.bottom} but the window ends at " +
                "${window.bottom} — same defect at the other end.",
            catalogue.bottom >= window.bottom,
        )
        assertTrue(
            "the top bar's hairline is at ${edge.top}, which is not inside the viewport " +
                "(${catalogue.top}..${catalogue.bottom}) — nothing scrolls under it.",
            edge.top > catalogue.top && edge.top < catalogue.bottom,
        )
    }

    @Test
    fun topBar_drawsAHairline_fullWidth() {
        renderChooser(darkTheme = true)

        val window = composeRule.onRoot().getUnclippedBoundsInRoot()
        composeRule.onNodeWithTag(PlacementChooserTestTags.TOP_BAR_EDGE)
            .assertIsDisplayed()
            .assertHeightIsEqualTo(SceneViewTokens.Layout.hairlineWidth)

        val edge = composeRule
            .onNodeWithTag(PlacementChooserTestTags.TOP_BAR_EDGE)
            .getUnclippedBoundsInRoot()
        assertTrue(
            "the hairline is ${edge.right - edge.left} wide on a ${window.right} screen — " +
                "a boundary that stops short of either side is not a boundary.",
            edge.right - edge.left >= window.right,
        )
    }

    // ── Colour: the boundary is made of something ─────────────────────────────────────

    @Test
    fun raisedBar_isNeverThePageColour() {
        // The 1.00:1 regression, stated as the thing it actually was: the bar's container
        // and the page it floats over being the same colour.
        assertNotEquals(
            "light: the top bar's scrolled container is the page surface — surface on " +
                "surface, 1.00:1, the boundary is not drawn at all.",
            md_theme_light_surface,
            md_theme_light_surfaceContainerHigh,
        )
        assertNotEquals(
            "dark: the top bar's scrolled container is the page surface — 1.00:1.",
            md_theme_dark_surface,
            md_theme_dark_surfaceContainerHigh,
        )
    }

    @Test
    fun darkHairline_clearsTheNonTextBar_onThePageAndOnTheRaisedBar() {
        // 3:1, the 1.4.11 bar for anything that identifies a boundary or a control. Dark
        // is where this screen is judged hardest and where #3681 solved the token.
        assertRatioAtLeast(
            "dark, hairline against the page",
            md_theme_dark_outline, md_theme_dark_surface, 3.0,
        )
        assertRatioAtLeast(
            "dark, hairline against the raised bar",
            md_theme_dark_outline, md_theme_dark_surfaceContainerHigh, 3.0,
        )
    }

    @Test
    fun theHairlineRole_beatsTheOneItReplaced_inBothSchemes() {
        // `outlineVariant` was the CTA bar's hairline: 1.05:1 in light, 1.55:1 in dark
        // against its own container — a line you cannot see on the surface it sits on.
        // Whatever the ramp does later, `outline` must stay the stronger of the two, or
        // this screen has silently gone back to drawing nothing.
        listOf(
            Scheme(
                "light",
                container = md_theme_light_surfaceContainerHigh,
                outline = md_theme_light_outline,
                outlineVariant = md_theme_light_outlineVariant,
            ),
            Scheme(
                "dark",
                container = md_theme_dark_surfaceContainerHigh,
                outline = md_theme_dark_outline,
                outlineVariant = md_theme_dark_outlineVariant,
            ),
        ).forEach { scheme ->
            val chosen = contrastRatio(scheme.outline, scheme.container)
            val replaced = contrastRatio(scheme.outlineVariant, scheme.container)
            assertTrue(
                "${scheme.name}: the hairline reads ${"%.2f".format(chosen)}:1 against the bar, " +
                    "no better than the ${"%.2f".format(replaced)}:1 `outlineVariant` it " +
                    "replaced.",
                chosen > replaced,
            )
        }
    }

    @Test
    fun lightHairline_isDrawn_evenThoughTheLightRampCannotYetReachThreeToOne() {
        // Deliberately a floor, not a range: the point is that the boundary exists at all.
        // 1.40:1 / 1.26:1 today; the light-ramp pull request will raise it and this test
        // is meant to keep passing untouched when it does.
        assertRatioAtLeast(
            "light, hairline against the page",
            md_theme_light_outline, md_theme_light_surface, 1.35,
        )
        assertRatioAtLeast(
            "light, hairline against the raised bar",
            md_theme_light_outline, md_theme_light_surfaceContainerHigh, 1.2,
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────

    /** One scheme's three boundary roles, so the two schemes assert the same way. */
    private data class Scheme(
        val name: String,
        val container: Color,
        val outline: Color,
        val outlineVariant: Color,
    )

    /**
     * An empty catalogue on purpose: the boundary is chrome, and the cards would only add
     * asset loading to a test about where a 1 dp line is and what colour it is.
     */
    private fun renderChooser(darkTheme: Boolean) {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = darkTheme) {
                PlacementChooserScreen(
                    models = emptyList(),
                    picker = rememberPlacementPickerState(initialSelectedId = "none"),
                    flow = rememberPlacementFlowState(),
                    arSupported = true,
                    onBack = {},
                    title = "Tap to place",
                    teaches = "Anchors, hit tests and one placed model.",
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertRatioAtLeast(what: String, a: Color, b: Color, minimum: Double) {
        val ratio = contrastRatio(a, b)
        assertTrue(
            "$what reads ${"%.2f".format(ratio)}:1, below the ${"%.2f".format(minimum)}:1 " +
                "this boundary is held to.",
            ratio >= minimum,
        )
    }

    /** WCAG 2.x contrast ratio. Same formula the theme's own comments quote. */
    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }
}
