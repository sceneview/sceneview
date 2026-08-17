package io.github.sceneview.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
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
 * The `bottomOverlay` slot must clear the Settings FAB cluster **at every font
 * scale** — measured, not eyeballed.
 *
 * ## The regression this pins (#3229)
 *
 * [SETTINGS_FAB_RESERVED_SPACE] was a constant, `104.dp`, derived from a peek
 * chip measured at ≈ 79 dp — with the English word "Settings", at font scale
 * 1.0. The chip is *text*. At font scale 1.3 it grows past the band reserved
 * for it, and an overlay that faithfully followed the documented idiom
 * (`padding(end = settingsFabReservedSpace)`) ends up **underneath** the FAB
 * anyway. Device-QA on the Pixel_7a emulator hit it on two demos at once:
 * `ar-terrain-anchor`'s first-launch banner and `ar-measure`'s "Clear" button.
 * Both were clean at 1.0.
 *
 * That is the whole difficulty. A constant clearance fails only in the
 * configurations nobody screenshots — a larger font scale, a longer
 * translation, a demo passing a wordier `peekHeader` — so no amount of looking
 * at the app in English at 1.0x can find it, and the overlap when it does
 * happen is ~4 dp, which a Roborazzi golden diff shows as a smudge a human is
 * meant to notice. Hence an arithmetic assertion on real measured bounds: it
 * either clears or it does not, and CI can tell.
 *
 * The fix is to measure the cluster instead of predicting it, so these tests
 * deliberately assert the *invariant* ("the overlay's end edge never reaches
 * the chip's start edge"), not the constant — a future chip redesign should
 * keep them green without a re-record.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoScaffoldBottomBandTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bottomOverlay_clearsTheSettingsChip_atDefaultFontScale() {
        assertOverlayClearsChip(fontScale = 1.0f)
    }

    @Test
    fun bottomOverlay_clearsTheSettingsChip_atLargeFontScale() {
        // 1.3 is the scale device-QA found the overlap at. 1.0 passing is not
        // evidence for this one: the whole defect is that they disagree.
        assertOverlayClearsChip(fontScale = 1.3f)
    }

    @Test
    fun bottomOverlay_clearsTheSettingsChip_atMaximumFontScale() {
        // Android's accessibility slider goes to 2.0. A band sized for 1.3
        // would be the same bug one notch further out.
        assertOverlayClearsChip(fontScale = 2.0f)
    }

    @Test
    fun bottomOverlay_clearsAWordyPeekHeader() {
        // `peekHeader` puts a demo-authored string on the chip in place of
        // "Settings" (#1152 Stage 3), so a demo can widen the cluster without
        // touching the scaffold at all. `ARStreetscapeDemo`-length text at a
        // large scale is the worst realistic case.
        assertOverlayClearsChip(
            fontScale = 1.3f,
            peekHeader = "12 anchors placed · streaming",
        )
    }

    @Test
    fun bottomOverlay_keepsUsableWidth_besideAWordyChip() {
        // The opposite failure, and the reason the peek chip is now capped: a
        // reserve that clears the chip by *starving* the overlay is not a fix.
        // `DemoStatusBanner` spends the reserve twice — it insets both sides to
        // stay centred — so an uncapped chip taking 230 dp of a 411 dp screen
        // leaves a centred pill negative width. Anything at or below half the
        // screen means the chip has stopped peeking and started occupying.
        val overlay = measureOverlay(
            fontScale = 1.3f,
            // `ar-measure`'s real first-launch header, verbatim — 38 characters.
            peekHeader = "Tap a surface to drop the first point",
        )
        val usable = overlay.right - overlay.left
        assertTrue(
            "a wordy peek header left the bottom overlay $usable wide on a $SCREEN_WIDTH " +
                "screen. Either the chip stopped being bounded, or the reserve it forces " +
                "has eaten the overlay it was supposed to sit beside — a banner that " +
                "cannot hold a sentence is not a fixed banner.",
            usable > SCREEN_WIDTH / 2,
        )
    }

    private fun assertOverlayClearsChip(fontScale: Float, peekHeader: String? = null) {
        val overlay = measureOverlay(fontScale, peekHeader)
        val chip = composeRule
            .onNodeWithTag(DemoScaffoldTestTags.SETTINGS_PEEK)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "at fontScale $fontScale the bottom overlay ends at ${overlay.right} but the " +
                "Settings peek chip starts at ${chip.left} — they overlap by " +
                "${overlay.right - chip.left}. The reserve is measured from the real " +
                "cluster; if this fails, something stopped feeding that measurement " +
                "back into DemoBottomOverlayScope.settingsFabReservedSpace.",
            overlay.right <= chip.left,
        )
    }

    private fun measureOverlay(fontScale: Float, peekHeader: String? = null): DpRect {
        composeRule.setContent {
            ScaledDensity(fontScale) {
                SceneViewDemoTheme(darkTheme = false) {
                    DemoScaffold(
                        title = "Band",
                        onBack = {},
                        peekHeader = peekHeader,
                        // Non-null `controls` is what makes the FAB — and
                        // therefore the reserve — exist at all.
                        controls = { Text("a setting") },
                        bottomOverlay = {
                            // The documented full-width idiom, verbatim. Its
                            // end edge lands exactly at
                            // `width - settingsFabReservedSpace`, which makes
                            // the assertion below an exact read of the reserve.
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = settingsFabReservedSpace)
                                    .height(48.dp)
                                    .testTag(OVERLAY_PROBE),
                            )
                        },
                    ) {}
                }
            }
        }
        composeRule.waitForIdle()
        return composeRule.onNodeWithTag(OVERLAY_PROBE).getUnclippedBoundsInRoot()
    }

    @Composable
    private fun ScaledDensity(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale),
            content = content,
        )
    }

    private companion object {
        const val OVERLAY_PROBE = "bottom-overlay-probe"

        /** Matches the `w411dp` Robolectric qualifier on this class. */
        val SCREEN_WIDTH = 411.dp
    }
}
