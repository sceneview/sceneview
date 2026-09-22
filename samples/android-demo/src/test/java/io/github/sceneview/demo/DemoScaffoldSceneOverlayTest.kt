package io.github.sceneview.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The `sceneOverlay` slot is painted **above the bottom scrim**, on **the `scene` slot's
 * frame** — read off pixels and bounds, not off the composition order.
 *
 * ## The defect this pins
 *
 * The placement coaching layer — the coaching line, the no-surface and camera-error cards,
 * the scale read-out — used to be composed inside the `scene` slot, and the scaffold paints
 * its bottom scrim over that slot. The layer was therefore painted *through* the scrim:
 * on #3712's goldens the plane-discovery pill of the day had its white text composited
 * down to 141/255 on a white camera frame, 4.3:1 at its top row falling to 2.3:1 at its
 * bottom one against the pill's own fill, while the dock's captions, composed after the
 * scrim, kept 5.4:1. The slot exists so a demo's own overlays get the dock's rank. The
 * z-order is the whole fix, so the first test reads it on the
 * rendered frame: a white probe in the slot must stay white where the same white in the
 * scene, one gutter below it, is darkened by the scrim.
 *
 * ## Why the frame and the insets are asserted too
 *
 * Moving an overlay from `scene` to `sceneOverlay` must change *nothing but* the z-order.
 * Every clearance in the coaching layer is measured from [LocalDemoChromeBottomInset] and
 * the slot's own bottom edge, so the slot has to report the same inset and the same bounds
 * as `scene` — otherwise the 16 dp anchor `PlacementBottomAnchorSnapshotTest` pins would
 * silently move with the slot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoScaffoldSceneOverlayTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** What each slot read from [LocalDemoChromeBottomInset], from inside it. */
    private var sceneBottomInset: Dp = UNREAD
    private var overlayBottomInset: Dp = UNREAD

    @Test
    fun sceneOverlay_isPaintedAboveTheBottomScrim_thatDarkensTheSceneUnderIt() {
        compose()
        val probe = composeRule.onNodeWithTag(OVERLAY_PROBE).getUnclippedBoundsInRoot()
        val dock = composeRule.onNodeWithTag(DemoScaffoldTestTags.DOCK).getUnclippedBoundsInRoot()
        val window = composeRule.onRoot().getUnclippedBoundsInRoot()
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        fun level(x: Dp, y: Dp): Int = with(composeRule.density) {
            val c = pixels[x.roundToPx(), y.roundToPx()]
            (maxOf(c.red, c.green, c.blue) * 255f).toInt()
        }

        // The probe stands at least one gutter above the dock, i.e. inside the scrim's
        // plateau: the plateau is the bottom 55 % of a band that starts at 220 dp and
        // the dock band alone is ~80 dp. (`LocalDemoChromeBottomInset` is the dock band
        // floored at `Layout.dockHeight + Space.md`, so the gap is a gutter or more.)
        assertTrue(
            "the probe ends at ${probe.bottom}, the dock starts at ${dock.top}: the probe " +
                "is not above the dock band, so it is not where the coaching line goes.",
            dock.top - probe.bottom >= SceneViewTokens.Space.md - 0.75.dp,
        )

        val onOverlay = level(probe.centerX, probe.centerY)
        val sceneBesideIt = level(probe.right + SceneViewTokens.Space.md, probe.centerY)
        val sceneBelowIt = level(probe.centerX, probe.bottom + SceneViewTokens.Space.sm)
        val sceneMidScreen = level(window.centerX, window.centerY)

        // The scene itself is white: what darkens it at the bottom is the scrim, not
        // the theme.
        assertTrue("the scene reads $sceneMidScreen/255 mid-screen; it should be white", sceneMidScreen >= 250)
        // Under the scrim's plateau, white composites with `Glass.scrimDock` (68 % black)
        // to about 82/255. Any value this far from white is the scrim at work.
        assertTrue(
            "the scene one gutter beside the probe reads $sceneBesideIt/255 — the bottom " +
                "scrim is not painting the scene there, so this test proves nothing.",
            sceneBesideIt <= 160,
        )
        assertTrue(
            "the scene in the gutter below the probe reads $sceneBelowIt/255 — the bottom " +
                "scrim is not painting the scene there, so this test proves nothing.",
            sceneBelowIt <= 160,
        )
        // And the probe, on the same rows, is untouched: the slot is above the scrim.
        assertTrue(
            "the sceneOverlay probe reads $onOverlay/255 where the scene beside it reads " +
                "$sceneBesideIt/255 — the slot is being painted under the bottom scrim.",
            onOverlay >= 250,
        )
    }

    @Test
    fun sceneOverlay_hasTheSceneFrame_andReadsTheSameChromeInset() {
        compose()
        val scene = composeRule.onNodeWithTag(SCENE_PROBE).getUnclippedBoundsInRoot()
        val slot = composeRule.onNodeWithTag(DemoScaffoldTestTags.SCENE_OVERLAY)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "the sceneOverlay slot $slot does not share the scene's frame $scene",
            slot.left near scene.left && slot.top near scene.top &&
                slot.right near scene.right && slot.bottom near scene.bottom,
        )
        assertTrue("the scene slot never read LocalDemoChromeBottomInset", sceneBottomInset != UNREAD)
        assertTrue("the sceneOverlay slot never read LocalDemoChromeBottomInset", overlayBottomInset != UNREAD)
        assertTrue(
            "LocalDemoChromeBottomInset is $sceneBottomInset — the scaffold provided no dock band",
            sceneBottomInset > 0.dp,
        )
        assertEquals(
            "the two slots read different chrome insets — an overlay moved between them " +
                "would land somewhere else",
            sceneBottomInset,
            overlayBottomInset,
        )
    }

    // The two gates are two tests rather than two `compose()` calls in one: the rule's
    // activity accepts `setContent` once per test, and a second call throws before it can
    // assert anything.
    @Test
    fun sceneOverlay_isGatedBy_arSessionFailed() {
        compose(arSessionFailed = true)
        composeRule.onNodeWithTag(DemoScaffoldTestTags.SCENE_OVERLAY).assertDoesNotExist()
        composeRule.onNodeWithTag(OVERLAY_PROBE).assertDoesNotExist()
    }

    @Test
    fun sceneOverlay_isGatedBy_arOverlaysEnabled() {
        compose(arOverlaysEnabled = false)
        composeRule.onNodeWithTag(DemoScaffoldTestTags.SCENE_OVERLAY).assertDoesNotExist()
        composeRule.onNodeWithTag(OVERLAY_PROBE).assertDoesNotExist()
    }

    private fun compose(arSessionFailed: Boolean = false, arOverlaysEnabled: Boolean = true) {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = true) {
                DemoScaffold(
                    title = "Overlay",
                    onBack = {},
                    // The real dock: it is what sets the dock band, hence the inset both
                    // slots read and the height of the scrim the probe must clear.
                    dock = DOCK,
                    arSessionFailed = arSessionFailed,
                    arOverlaysEnabled = arOverlaysEnabled,
                    sceneOverlay = {
                        overlayBottomInset = LocalDemoChromeBottomInset.current
                        // Where the coaching line goes: bottom-centre, one gutter above
                        // the dock band, clear of the system bars. White, like the line's
                        // text — the thing the scrim was dimming.
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = LocalDemoChromeBottomInset.current + SceneViewTokens.Space.md)
                                .width(200.dp)
                                .height(48.dp)
                                .background(Color.White)
                                .testTag(OVERLAY_PROBE),
                        )
                    },
                ) {
                    sceneBottomInset = LocalDemoChromeBottomInset.current
                    // A white camera frame — the ground the veil was measured on.
                    Box(Modifier.fillMaxSize().background(Color.White).testTag(SCENE_PROBE))
                }
            }
        }
        composeRule.waitForIdle()
        // Both scrims arrive through `AnimatedVisibility`; settle before reading pixels.
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
    }

    private companion object {
        const val OVERLAY_PROBE = "scene-overlay-probe"
        const val SCENE_PROBE = "scene-probe"
        val UNREAD = (-1).dp
        val DOCK = listOf(
            DockItem(icon = Icons.Filled.ViewInAr, label = "Models", onClick = {}),
            DockItem(icon = Icons.Filled.Refresh, label = "Clear", onClick = {}),
        )

        /** Layout rounding at xhdpi is a quarter-dp; anything past that is a real gap. */
        infix fun Dp.near(other: Dp): Boolean = kotlin.math.abs((this - other).value) <= 0.5f

        val DpRect.centerX: Dp get() = left + width / 2
        val DpRect.centerY: Dp get() = top + height / 2
    }
}
