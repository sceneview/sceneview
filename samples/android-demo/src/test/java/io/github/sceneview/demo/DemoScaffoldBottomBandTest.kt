package io.github.sceneview.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
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
 * The `bottomOverlay` slot must sit **above the dock** at every font scale —
 * measured, not eyeballed.
 *
 * ## The regression this pins (#3229, carried over to the dock)
 *
 * [SETTINGS_FAB_RESERVED_SPACE] used to be a constant derived from a peek chip
 * measured at font scale 1.0 — and at 1.3 the chip outgrew it, putting two
 * demos' bottom overlays under the FAB. The chip is gone and the dock is a
 * bottom-centre floating toolbar, but the shape of the defect is the same: a
 * clearance that is predicted rather than measured fails only in the
 * configurations nobody screenshots. So the scaffold measures the dock band and
 * stacks the overlay above it, and these tests assert the *invariant* ("the
 * overlay's bottom edge never reaches the dock's top edge") on real bounds, not
 * the constant — a future dock redesign should keep them green.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoScaffoldBottomBandTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bottomOverlay_clearsTheDock_atDefaultFontScale() {
        assertOverlayClearsDock(fontScale = 1.0f)
    }

    @Test
    fun bottomOverlay_clearsTheDock_atLargeFontScale() {
        // 1.3 is the scale device-QA found the historical overlap at. 1.0 passing
        // is not evidence for this one: the whole defect is that they disagree.
        assertOverlayClearsDock(fontScale = 1.3f)
    }

    @Test
    fun bottomOverlay_clearsTheDock_atMaximumFontScale() {
        // Android's accessibility slider goes to 2.0.
        assertOverlayClearsDock(fontScale = 2.0f)
    }

    @Test
    fun statusPill_stacksAboveTheDock_andKeepsUsableWidth() {
        // `peekHeader` is now a glass status pill at the top of the bottom band.
        // It must stack with the overlay above the dock, and a wordy header must
        // not starve the overlay beside it of width — the overlay still spans the
        // screen, because nothing sits in a bottom corner any more.
        val overlay = measureOverlay(
            fontScale = 1.3f,
            // `ar-measure`'s real first-launch header, verbatim — 38 characters.
            peekHeader = "Tap a surface to drop the first point",
        )
        val pill = composeRule.onNodeWithTag(DemoScaffoldTestTags.STATUS_PILL)
            .getUnclippedBoundsInRoot()
        val dock = composeRule.onNodeWithTag(DemoScaffoldTestTags.DOCK)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "the status pill ends at ${pill.bottom} but the overlay probe starts at " +
                "${overlay.top} — the band is a Column, its children must stack.",
            pill.bottom <= overlay.top,
        )
        assertTrue(
            "the overlay probe ends at ${overlay.bottom} but the dock starts at ${dock.top}.",
            overlay.bottom <= dock.top,
        )
        val usable = overlay.right - overlay.left
        assertTrue(
            "the bottom overlay is only $usable wide on a $SCREEN_WIDTH screen; with the " +
                "dock centred below it, the overlay should span the full width.",
            usable > SCREEN_WIDTH / 2,
        )
    }

    @Test
    fun bareDemo_stillGetsTheDock_andTheOverlayClearsIt() {
        // Before #3328 a demo with no `controls`, no `dock` and no accent composed
        // no dock at all, and the overlay ran to the bottom of the scene. The
        // settings sheet is now the single settings surface — Reset, Send feedback
        // and QA mode live in it — so the dock always carries the Controls item
        // that opens it, on every demo. The invariant that replaces "no dock" is
        // therefore: the bare demo still has a dock, and the overlay still clears
        // it rather than running underneath.
        composeRule.setContent {
            ScaledDensity(1.0f) {
                SceneViewDemoTheme(darkTheme = false) {
                    DemoScaffold(
                        title = "Band",
                        onBack = {},
                        bottomOverlay = {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag(OVERLAY_PROBE),
                            )
                        },
                    ) {
                        Box(Modifier.fillMaxSize().testTag(SCENE_PROBE))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DemoScaffoldTestTags.SETTINGS_FAB)
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Demo settings")
        val dock = composeRule.onNodeWithTag(DemoScaffoldTestTags.DOCK)
            .getUnclippedBoundsInRoot()
        val overlay = composeRule.onNodeWithTag(OVERLAY_PROBE).getUnclippedBoundsInRoot()
        val scene = composeRule.onNodeWithTag(SCENE_PROBE).getUnclippedBoundsInRoot()
        assertTrue(
            "a demo with no controls of its own still gets the dock, but its overlay ends " +
                "at ${overlay.bottom} while the dock starts at ${dock.top}.",
            overlay.bottom <= dock.top,
        )
        assertTrue(
            "the overlay ends at ${overlay.bottom}, ${dock.top - overlay.bottom} above the " +
                "dock at ${dock.top} on a scene ending at ${scene.bottom} — the reserve " +
                "should be the dock band and a gutter, not a second empty band.",
            dock.top - overlay.bottom < 48.dp,
        )
    }

    @Test
    fun dock_carriesTheControlsItem_withTheHistoricalTagAndLabel() {
        // `DemoInteractionTest` (androidTest) opens the controls sheet through
        // `By.res("demo-settings-fab")` or `By.desc("Demo settings")`. Both must
        // survive the move from a FAB to a dock item.
        measureOverlay(fontScale = 1.0f)
        composeRule.onNodeWithTag(DemoScaffoldTestTags.SETTINGS_FAB)
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Demo settings")
    }

    private fun assertOverlayClearsDock(fontScale: Float) {
        val overlay = measureOverlay(fontScale)
        val dock = composeRule
            .onNodeWithTag(DemoScaffoldTestTags.DOCK)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "at fontScale $fontScale the bottom overlay ends at ${overlay.bottom} but the " +
                "dock starts at ${dock.top} — they overlap by ${overlay.bottom - dock.top}. " +
                "The reserve is measured from the real dock band; if this fails, something " +
                "stopped feeding that measurement into the bottomOverlay slot.",
            overlay.bottom <= dock.top,
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
                        // Non-null `controls` is what makes the dock — and
                        // therefore the reserve — exist at all.
                        controls = { Text("a setting") },
                        bottomOverlay = {
                            // The documented full-width idiom, verbatim. The
                            // end inset is now a no-op; the clearance is vertical.
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
        const val SCENE_PROBE = "scene-probe"

        /** Matches the `w411dp` Robolectric qualifier on this class. */
        val SCREEN_WIDTH = 411.dp
    }
}
