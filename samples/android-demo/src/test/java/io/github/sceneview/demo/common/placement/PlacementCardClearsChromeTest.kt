package io.github.sceneview.demo.common.placement

import android.view.View
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARCoreAvailabilityOverlayTags
import io.github.sceneview.ar.ARCoreAvailabilityState
import io.github.sceneview.demo.AssetSourceState
import io.github.sceneview.demo.DemoBottomOverlayScope
import io.github.sceneview.demo.DEMO_MODE_PILL_TAG
import io.github.sceneview.demo.DemoMode
import io.github.sceneview.demo.DemoModeSwitch
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoScaffoldTestTags
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.LocalDemoChromeBottomInset
import io.github.sceneview.demo.LocalDemoModeSwitch
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The placement screen's cards never share pixels with the scaffold's bottom chrome — the
 * dock and the Place / Free pose / One call pill above it — measured on the real screen: the
 * real [DemoScaffold], inside a mode switch, with the real [TapToPlaceStatusOverlays] in its
 * scene slot.
 *
 * ## The regression this pins (#4509, #4503)
 *
 * The scaffold told the scene how tall its **dock** was, and the stack anchored one gutter
 * above that. When AR Placement became one mode of three, the scaffold started drawing a mode
 * pill above the dock — after the scene, so on top of it — and nothing told the stack. On a
 * phone the "No surface found" card came up with its "Keep scanning" / "View in 3D" row under
 * the pill: the card read as drawn behind the selector, and the action that leads out of a
 * room with no usable surface could not be reached.
 *
 * [PlacementBottomAnchorTest] could not see it: it hands the stack a bottom inset by hand, and
 * the defect was the value the scaffold hands it. So every case here goes through the scaffold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlacementCardClearsChromeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val state = TapToPlaceState()

    @Before
    fun setUp() {
        ForcedTrackingFailure.override = null
    }

    @After
    fun tearDown() {
        ForcedTrackingFailure.override = null
    }

    @Test
    fun noSurfaceCard_stacksAboveTheModePill() {
        assertCardClearsChrome(PlacementPhase.NO_SURFACE, fontScale = 1.0f)
    }

    @Test
    fun noSurfaceCard_stacksAboveTheModePill_atLargeFontScale() {
        assertCardClearsChrome(PlacementPhase.NO_SURFACE, fontScale = 1.3f)
    }

    @Test
    fun noSurfaceCard_stacksAboveTheModePill_atMaximumFontScale() {
        // Android's accessibility slider goes to 2.0.
        assertCardClearsChrome(PlacementPhase.NO_SURFACE, fontScale = 2.0f)
    }

    @Test
    fun cameraErrorCard_stacksAboveTheModePill() {
        // The card an emulator ends on — and the one device QA can photograph without a room.
        assertCardClearsChrome(PlacementPhase.CAMERA_ERROR, fontScale = 1.0f)
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp-xhdpi")
    fun smallWindow_theCardKeepsItsButtons_betweenTheTitleAndThePill() {
        assertCardClearsChrome(PlacementPhase.NO_SURFACE, fontScale = 1.0f)
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp-xhdpi")
    fun smallWindow_atMaximumFontScale_theTextScrolls_andTheButtonsStay() {
        // The whole card does not fit here. What must hold: it is cut at neither end by the
        // chrome, and both actions are still whole inside it.
        assertCardClearsChrome(PlacementPhase.NO_SURFACE, fontScale = 2.0f)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun phoneHeldSideways_theCardSitsBesideTheChrome_notUnderIt() {
        // The band above the pill is thinner than the card's row of buttons; the corner next
        // to the centred chrome is not.
        assertCardClearsChrome(PlacementPhase.NO_SURFACE, fontScale = 1.0f)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun phoneHeldSideways_atLargeFontScale() {
        assertCardClearsChrome(PlacementPhase.NO_SURFACE, fontScale = 1.3f)
    }

    @Test
    fun whateverPadsItselfByTheChromeInset_endsAboveTheModePill() {
        // The SDK's coaching card, in this demo and six others, is handed the same number as
        // `contentPadding`. A probe padded by it stands for all of them: it must end above the
        // pill, not merely above the dock.
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                CompositionLocalProvider(
                    LocalDemoModeSwitch provides DemoModeSwitch(MODES, selected = 0, onSelect = {}),
                ) {
                    DemoScaffold(
                        title = "AR Placement",
                        onBack = {},
                        dock = listOf(DockItem(Icons.Outlined.WbSunny, "Models", onClick = {})),
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(WindowInsets.safeDrawing)
                                .padding(bottom = LocalDemoChromeBottomInset.current)
                                .testTag(PROBE),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        val probe = bounds(PROBE)
        val pill = bounds(DEMO_MODE_PILL_TAG)
        assertTrue(
            "content padded by the chrome inset ends at ${probe.bottom}, but the mode pill " +
                "starts at ${pill.top}: the scaffold is reporting its dock, not its chrome.",
            probe.bottom <= pill.top + TOLERANCE,
        )
    }

    // ── Screens with no mode pill: the inset only grows by what is really drawn ───────────

    @Test
    fun noPillAndNoOverlay_theInsetIsTheDockBand_asBefore() {
        // What `main` published everywhere: the dock's toolbar and its gutter.
        val probe = probeBottomInset(bottomOverlay = null)
        val dock = bounds(DemoScaffoldTestTags.DOCK)
        assertInsetIsTheDockBand(probe, dock)
    }

    @Test
    fun anOverlayThatDrawsNothing_leavesTheInsetAtTheDockBand() {
        // The recordings gallery, and every AR demo whose status line steps aside while the
        // coaching card speaks: the band is composed, and empty. Its 104 dp dock reserve must
        // not reach the scene, or those screens move by 24 dp for nothing.
        val probe = probeBottomInset(bottomOverlay = {})
        val dock = bounds(DemoScaffoldTestTags.DOCK)
        assertInsetIsTheDockBand(probe, dock)
    }

    @Test
    fun twoBannersWithNothingToSay_leaveTheInsetAtTheDockBand() {
        // Two children with no height still get a gap between them: the band measures 8 dp
        // of nothing. That is not chrome, and must not send the inset to the 104 dp reserve.
        val probe = probeBottomInset(
            bottomOverlay = {
                Box(Modifier.fillMaxWidth().height(0.dp))
                Box(Modifier.fillMaxWidth().height(0.dp))
            },
        )
        val dock = bounds(DemoScaffoldTestTags.DOCK)
        assertInsetIsTheDockBand(probe, dock)
    }

    @Test
    fun anOverlayWithNoPill_raisesTheInsetToItsOwnTopEdge() {
        // AR Measure's action bar, a status banner: drawn after the scene, so over it.
        val probe = probeBottomInset(
            bottomOverlay = { Box(Modifier.size(120.dp, 40.dp).testTag(OVERLAY)) },
        )
        val overlay = bounds(OVERLAY)
        assertTrue(
            "content padded by the chrome inset ends at ${probe.bottom}, under the demo's " +
                "overlay which starts at ${overlay.top}",
            probe.bottom <= overlay.top + TOLERANCE,
        )
        assertTrue(
            "the inset is further above the overlay than its top edge: probe ${probe.bottom}, " +
                "overlay ${overlay.top}",
            probe.bottom >= overlay.top - TOLERANCE,
        )
    }

    /** Composes a scaffold with no mode switch and returns a probe padded by the inset. */
    private fun probeBottomInset(
        bottomOverlay: (@Composable DemoBottomOverlayScope.() -> Unit)?,
    ): DpRect {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                DemoScaffold(
                    title = "AR Measure",
                    onBack = {},
                    dock = listOf(DockItem(Icons.Outlined.WbSunny, "Models", onClick = {})),
                    bottomOverlay = bottomOverlay,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(bottom = LocalDemoChromeBottomInset.current)
                            .testTag(PROBE),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        return bounds(PROBE)
    }

    private fun assertInsetIsTheDockBand(probe: DpRect, dock: DpRect) {
        // The dock band ends where the toolbar starts: that is the number `main` publishes.
        val gap = dock.top - probe.bottom
        assertTrue(
            "the inset ends $gap above the dock (probe ${probe.bottom}, dock ${dock.top}): " +
                "it must stop at the dock's top edge, not at the band's 104 dp reserve.",
            gap >= -TOLERANCE && gap <= TOLERANCE,
        )
    }

    @Test
    fun couldNotStartArCard_staysBetweenTheTitleAndThePill() {
        assertAvailabilityCardClearsChrome()
    }

    @Test
    fun couldNotStartArCard_inTheDarkTheme() {
        assertAvailabilityCardClearsChrome(darkTheme = true)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun phoneHeldSideways_couldNotStartArCard_sitsBesideTheChrome() {
        // What the emulator shows: the SDK centred this card in the scene, and sideways its
        // "Try again" came up under the mode pill.
        assertAvailabilityCardClearsChrome()
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun phoneHeldSideways_couldNotStartArCard_atMaximumFontScale() {
        // "Try again" must stay in the window, and reachable, at the largest text size.
        assertAvailabilityCardClearsChrome(fontScale = 2.0f)
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun narrowPhoneHeldSideways_couldNotStartArCard_atMaximumFontScale() {
        // The corner beside the chrome is too narrow for the button's label here.
        assertAvailabilityCardClearsChrome(fontScale = 2.0f)
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun narrowPhoneHeldSideways_couldNotStartArCard_atMaximumFontScale_withSystemBars() {
        // The same window on a real phone: a status bar above and the gesture handle below
        // take 48 dp off the 360 the card had to itself in the case above (#4533).
        assertAvailabilityCardClearsChrome(fontScale = 2.0f, systemBars = GESTURE_BARS)
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun narrowPhoneHeldSideways_couldNotStartArCard_atMaximumFontScale_withThreeButtonBar() {
        // Three-button navigation sits on the short edge of a phone held sideways.
        assertAvailabilityCardClearsChrome(fontScale = 2.0f, systemBars = THREE_BUTTON_BARS)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun phoneHeldSideways_couldNotStartArCard_atMaximumFontScale_withSystemBars() {
        assertAvailabilityCardClearsChrome(fontScale = 2.0f, systemBars = GESTURE_BARS)
    }

    @Test
    fun noSurfaceCard_inTheDarkTheme() {
        assertCardClearsChrome(PlacementPhase.NO_SURFACE, fontScale = 1.0f, darkTheme = true)
    }

    private fun assertAvailabilityCardClearsChrome(
        fontScale: Float = 1.0f,
        darkTheme: Boolean = false,
        systemBars: SystemBars? = null,
    ) {
        var host: View? = null
        composeRule.setContent {
          host = LocalView.current
          ScaledDensity(fontScale) {
            SceneViewDemoTheme(darkTheme = darkTheme) {
                CompositionLocalProvider(
                    LocalDemoModeSwitch provides DemoModeSwitch(MODES, selected = 0, onSelect = {}),
                ) {
                    DemoScaffold(
                        title = "AR Placement",
                        onBack = {},
                        assetSource = AssetSourceState.Bundled,
                        dock = listOf(
                            DockItem(Icons.Outlined.WbSunny, "Models", onClick = {}),
                            DockItem(Icons.Outlined.WbSunny, "Reset", onClick = {}),
                        ),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            PlacementAvailabilityOverlay(
                                ARCoreAvailabilityState(ARCoreAvailability.SessionFailed, retry = {}),
                            )
                        }
                    }
                }
            }
          }
        }
        composeRule.waitForIdle()
        if (systemBars != null) injectSystemBars(checkNotNull(host), systemBars)
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        val window = composeRule.onRoot().getUnclippedBoundsInRoot()
        val card = bounds(ARCoreAvailabilityOverlayTags.CARD)
        val action = bounds(ARCoreAvailabilityOverlayTags.ACTION)
        val pill = bounds(DEMO_MODE_PILL_TAG)
        val dock = bounds(DemoScaffoldTestTags.DOCK)
        val identity = bounds(DemoScaffoldTestTags.IDENTITY_ROW)
        val where = "card $card, action $action, mode pill $pill, dock $dock, " +
            "identity row $identity, window $window"
        // `card` is the card's ground — the dark rectangle that is drawn, not the text inside
        // its padding: the SDK tags the ground (#4533).
        assertTrue("the card shares pixels with the mode pill — $where", !card.overlaps(pill))
        assertTrue("the card shares pixels with the dock — $where", !card.overlaps(dock))
        assertTrue("the card runs under the identity row — $where", !card.overlaps(identity))
        assertTrue(
            "the card leaves the window — $where",
            card.top >= window.top && card.bottom <= window.bottom &&
                card.left >= window.left && card.right <= window.right,
        )
        assertTrue(
            "\"Try again\" is not whole inside its card — $where",
            action.top >= card.top && action.bottom <= card.bottom,
        )
        assertActionIsWhole(action, fontScale, where)
        assertTrue(
            "\"Try again\" leaves the window — $where",
            action.top >= window.top && action.bottom <= window.bottom &&
                action.left >= window.left && action.right <= window.right,
        )
        if (systemBars != null) assertClearOfSystemBars(systemBars, card, identity, dock, window, where)
    }

    private fun assertClearOfSystemBars(
        systemBars: SystemBars,
        card: DpRect,
        identity: DpRect,
        dock: DpRect,
        window: DpRect,
        where: String,
    ) {
        // The injection took: the chrome moved off the bars.
        assertTrue(
            "the system bars were not applied ($systemBars) — $where",
            identity.top >= window.top + systemBars.top.dp - TOLERANCE &&
                dock.bottom <= window.bottom - systemBars.bottom.dp + TOLERANCE,
        )
        assertTrue(
            "the card runs under a system bar ($systemBars) — $where",
            card.top >= window.top + systemBars.top.dp - TOLERANCE &&
                card.bottom <= window.bottom - systemBars.bottom.dp + TOLERANCE &&
                card.right <= window.right - systemBars.right.dp + TOLERANCE,
        )
    }

    /** What the system draws over the window's edges, in dp. */
    private data class SystemBars(val top: Int, val bottom: Int, val right: Int = 0)

    /**
     * Hands the composition the insets a phone's system bars produce. Robolectric's window has
     * none, so every other case here runs in a window the app has to itself; whether the
     * injection took is asserted by the caller, on what was measured.
     */
    private fun injectSystemBars(host: View, bars: SystemBars) {
        val density = host.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px(bars.top), 0, 0))
            .setInsets(
                WindowInsetsCompat.Type.navigationBars(),
                Insets.of(0, 0, px(bars.right), px(bars.bottom)),
            )
            .build()
        composeRule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(host, insets) }
        composeRule.waitForIdle()
    }

    /** Neither squeezed flat, nor so narrow that its label breaks inside a word. */
    private fun assertActionIsWhole(action: DpRect, fontScale: Float, where: String) {
        val height = action.bottom - action.top
        // Its padding and one whole line of its label, at this text size.
        val whole = ACTION_PADDING + ACTION_LINE_AT_LEAST * fontScale
        assertTrue("\"Try again\" is squeezed to $height, under $whole — $where", height >= whole)
        assertTrue(
            "\"Try again\" is $height tall: its label has so little width that it breaks " +
                "inside a word — $where",
            height <= ACTION_PADDING + ACTION_LINE * fontScale * 2 + TOLERANCE,
        )
    }

    private fun assertCardClearsChrome(
        phase: PlacementPhase,
        fontScale: Float,
        darkTheme: Boolean = false,
    ) {
        show(phase, fontScale, darkTheme)
        val window = composeRule.onRoot().getUnclippedBoundsInRoot()
        val card = bounds(PlacementTestTags.PLACEMENT_CARD)
        val primary = bounds(PlacementTestTags.PLACEMENT_CARD_PRIMARY)
        val pill = bounds(DEMO_MODE_PILL_TAG)
        val dock = bounds(DemoScaffoldTestTags.DOCK)
        val identity = bounds(DemoScaffoldTestTags.IDENTITY_ROW)
        val where = "card $card, primary $primary, mode pill $pill, dock $dock, " +
            "identity row $identity, window $window"

        assertTrue("the card shares pixels with the mode pill — $where", !card.overlaps(pill))
        assertTrue("the card shares pixels with the dock — $where", !card.overlaps(dock))
        assertTrue("the card runs under the identity row — $where", !card.overlaps(identity))
        assertTrue(
            "the card leaves the window — $where",
            card.top >= window.top && card.bottom <= window.bottom &&
                card.left >= window.left && card.right <= window.right,
        )
        assertTrue(
            "the primary action is not whole inside its card — $where",
            primary.top >= card.top && primary.bottom <= card.bottom &&
                primary.left >= card.left && primary.right <= card.right,
        )
        assertTrue(
            "the primary action is under a 48 dp touch target — $where",
            primary.bottom - primary.top >= 47.dp,
        )
        if (phase == PlacementPhase.NO_SURFACE) {
            val secondary = bounds(PlacementTestTags.PLACEMENT_CARD_SECONDARY)
            assertTrue(
                "\"Keep scanning\" is not whole inside its card — $secondary; $where",
                secondary.top >= card.top && secondary.bottom <= card.bottom &&
                    secondary.left >= card.left && secondary.right <= card.right,
            )
            assertTrue(
                "the two actions overlap — $secondary; $where",
                !secondary.overlaps(primary),
            )
        }
    }

    private fun show(phase: PlacementPhase, fontScale: Float, darkTheme: Boolean = false) {
        state.cameraReady = true
        state.isTracking = true
        state.phase = phase
        composeRule.setContent {
            ScaledDensity(fontScale) {
                SceneViewDemoTheme(darkTheme = darkTheme) {
                    CompositionLocalProvider(
                        LocalDemoModeSwitch provides DemoModeSwitch(MODES, selected = 0, onSelect = {}),
                    ) {
                        // The chrome `ARPlacementDemo` declares: two dock items and the source pill.
                        DemoScaffold(
                            title = "AR Placement",
                            onBack = {},
                            assetSource = AssetSourceState.Bundled,
                            dock = listOf(
                                DockItem(Icons.Outlined.WbSunny, "Models", onClick = {}),
                                DockItem(Icons.Outlined.WbSunny, "Reset", onClick = {}),
                            ),
                        ) {
                            TapToPlaceStatusOverlays(state = state, onViewIn3D = {}, onRestartSession = {})
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // Past every enter transition, and the frame the scaffold's measurements land on.
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
    }

    private fun bounds(tag: String): DpRect = composeRule
        .onNodeWithTag(tag)
        .getUnclippedBoundsInRoot()

    /** Sub-pixel tolerant: bounds are px rounded back to dp, and a real overlap is a row of buttons. */
    private fun DpRect.overlaps(other: DpRect): Boolean =
        left < other.right - TOLERANCE && other.left < right - TOLERANCE &&
            top < other.bottom - TOLERANCE && other.top < bottom - TOLERANCE

    @Composable
    private fun ScaledDensity(fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale),
            content = content,
        )
    }

    private companion object {
        val TOLERANCE = 0.75.dp

        /** The SDK button's vertical padding, and one line of its label at font scale 1. */
        val ACTION_PADDING = 24.dp
        val ACTION_LINE = 17.dp

        /** The least one line of that label takes: a 14 sp line box is a little over 15 dp. */
        val ACTION_LINE_AT_LEAST = 15.dp

        /** A status bar and the gesture handle, as a phone held sideways reports them. */
        val GESTURE_BARS = SystemBars(top = 24, bottom = 24)

        /** A status bar, and three-button navigation on the short edge. */
        val THREE_BUTTON_BARS = SystemBars(top = 24, bottom = 0, right = 48)
        const val PROBE = "chrome-inset-probe"
        const val OVERLAY = "demo-bottom-overlay-content"

        /** The three modes `ArPlacementFragment` hosts, with their real labels. */
        val MODES = listOf(
            DemoMode("place", R.string.demo_mode_place),
            DemoMode("free-pose", R.string.demo_mode_free_pose),
            DemoMode("one-call", R.string.demo_mode_one_call),
        )
    }
}
