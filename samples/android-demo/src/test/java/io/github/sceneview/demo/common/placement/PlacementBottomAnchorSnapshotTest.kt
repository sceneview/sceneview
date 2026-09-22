package io.github.sceneview.demo.common.placement

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoScaffoldTestTags
import io.github.sceneview.demo.DockItem
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
import kotlin.math.absoluteValue

/**
 * Pictures of the placement screen in each state of the §2.2 machine, with the bottom
 * anchor measured on the same composition that is photographed.
 *
 * ## Why this file exists
 *
 * [PlacementBottomAnchorTest] pins the arithmetic differentially and is the better
 * regression test. What it cannot do is show that the result *looks* right, and no ARCore
 * session starts on the CI emulator — the coaching line, the cards and the read-out never
 * render there at all. So this file renders the real [DemoScaffold] with the real dock
 * and the real [TapToPlaceStatusOverlays] over it, photographs the result, and asserts
 * the 16 dp on the bounds of that same frame.
 *
 * What it is **not**: proof that an ARCore session starts, that a surface is found, that
 * the object lands on it. Those need a device. This is proof of *layout and copy* only.
 *
 * ## The states
 *
 *  - **scanning** — "Move slowly to find a surface." — the coaching line alone;
 *  - **placed** — the one-shot "Drag to move. Pinch or twist to adjust." hint;
 *  - **no surface** — the 10 s card, *View in 3D* / *Keep scanning*;
 *  - **tracking lost** in low light — "Tracking paused. Move slowly. Try a brighter area.";
 *  - **gesture** — a live pinch read-out, "Preview size · 120 %", hint window closed.
 *
 * Every state is photographed light and dark; the flat white and flat black grounds are
 * the two ends of the range a translucent glass surface has to survive, and they are what
 * makes the painted edges unambiguous when the PNG is measured. The `*-nav48` cases
 * dispatch a 48 dp three-button navigation inset by hand and assert that `safeDrawing`
 * actually reports it.
 *
 * Re-record after a deliberate UI change:
 *   `./gradlew :samples:android-demo:recordRoborazziDebug --tests '*PlacementBottomAnchorSnapshotTest*'`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlacementBottomAnchorSnapshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val state = TapToPlaceState()

    /**
     * What `WindowInsets.safeDrawing` reported to the composition itself, read from inside
     * it — the value *after* [injectNavigationBarInset] has run.
     */
    private var observedBottomInsetPx = 0

    @Before
    fun setUp() {
        ForcedTrackingFailure.override = null
        state.cameraReady = true
        state.isTracking = true
        state.trackingFailureReason = null
    }

    @After
    fun tearDown() {
        ForcedTrackingFailure.override = null
    }

    // ── Scanning ──────────────────────────────────────────────────────────────────────

    @Test
    fun scanning_light_onWhite() = capture("scanning-light-white", false, Color.White, Scene.SCANNING)

    @Test
    fun scanning_light_onBlack() = capture("scanning-light-black", false, Color.Black, Scene.SCANNING)

    @Test
    fun scanning_dark_onWhite() = capture("scanning-dark-white", true, Color.White, Scene.SCANNING)

    @Test
    fun scanning_dark_onBlack() = capture("scanning-dark-black", true, Color.Black, Scene.SCANNING)

    // ── Placed, with the one-shot gesture hint ────────────────────────────────────────

    @Test
    fun placed_light_onWhite() = capture("placed-light-white", false, Color.White, Scene.PLACED_HINT)

    @Test
    fun placed_dark_onBlack() = capture("placed-dark-black", true, Color.Black, Scene.PLACED_HINT)

    // ── No surface after 10 s ─────────────────────────────────────────────────────────

    @Test
    fun noSurface_light_onWhite() = capture("no-surface-light-white", false, Color.White, Scene.NO_SURFACE)

    @Test
    fun noSurface_dark_onBlack() = capture("no-surface-dark-black", true, Color.Black, Scene.NO_SURFACE)

    // ── Tracking lost, low light ──────────────────────────────────────────────────────

    @Test
    fun trackingLost_light_onWhite() =
        capture("tracking-lost-light-white", false, Color.White, Scene.TRACKING_LOST)

    @Test
    fun trackingLost_dark_onBlack() =
        capture("tracking-lost-dark-black", true, Color.Black, Scene.TRACKING_LOST)

    // ── Live pinch read-out ───────────────────────────────────────────────────────────

    @Test
    fun gesture_light_onWhite() = capture("gesture-light-white", false, Color.White, Scene.GESTURE)

    @Test
    fun gesture_dark_onBlack() = capture("gesture-dark-black", true, Color.Black, Scene.GESTURE)

    // ── Three-button navigation ───────────────────────────────────────────────────────

    @Test
    fun scanning_dark_threeButtonNav() =
        capture("scanning-dark-white-nav48", true, Color.White, Scene.SCANNING, navInsetDp = 48)

    @Test
    fun gesture_dark_threeButtonNav() =
        capture("gesture-dark-white-nav48", true, Color.White, Scene.GESTURE, navInsetDp = 48)

    private enum class Scene { SCANNING, PLACED_HINT, NO_SURFACE, TRACKING_LOST, GESTURE }

    /**
     * Composes the screen, photographs it, and measures the anchor on the frame that was
     * photographed — the picture and the number can therefore never disagree.
     */
    private fun capture(
        name: String,
        darkTheme: Boolean,
        ground: Color,
        scene: Scene,
        navInsetDp: Int = 0,
    ) {
        when (scene) {
            Scene.SCANNING -> state.phase = PlacementPhase.SCANNING
            Scene.PLACED_HINT -> {
                state.phase = PlacementPhase.PLACED
                state.lastPlacedAtMillis = 1L
            }
            Scene.NO_SURFACE -> state.phase = PlacementPhase.NO_SURFACE
            Scene.TRACKING_LOST -> {
                state.phase = PlacementPhase.TRACKING_LOST
                state.isTracking = false
                state.trackingFailureReason = TrackingFailureReason.INSUFFICIENT_LIGHT
            }
            Scene.GESTURE -> {
                state.phase = PlacementPhase.PLACED
                state.lastPlacedAtMillis = 0L
                state.scalePercent = 120
                state.scaleLabel = ScaleLabelMode.PREVIEW
            }
        }
        composeScreen(darkTheme, ground)
        if (navInsetDp > 0) injectNavigationBarInset(navInsetDp)
        settleAnimations()

        val observedNavInset = with(composeRule.density) { observedBottomInsetPx.toDp() }

        composeRule.onRoot().captureRoboImage(
            "src/test/snapshots/placement_bottom_$name.png",
            roborazziOptions = CROSS_PLATFORM_TOLERANT,
        )
        assertAnchor(name, scene, navInsetDp, observedNavInset)
    }

    /** The real scaffold, the real dock, the real overlays, over a flat stand-in scene. */
    private fun composeScreen(darkTheme: Boolean, ground: Color) {
        composeRule.setContent {
            observedBottomInsetPx = WindowInsets.safeDrawing.getBottom(LocalDensity.current)
            SceneViewDemoTheme(darkTheme = darkTheme) {
                DemoScaffold(
                    title = "Place in AR",
                    onBack = {},
                    dock = DOCK,
                    scene = {
                        Box(Modifier.fillMaxSize().background(ground))
                        TapToPlaceStatusOverlays(
                            state = state,
                            onViewIn3D = {},
                            onRestartSession = {},
                        )
                    },
                )
            }
        }
    }

    /**
     * Robolectric hands the window no system bars at all, so `safeDrawing` is 0 on every
     * side. Dispatching the insets by hand onto the composition's own host view is the
     * only way to render the three-button case off-device; whether it takes is *measured*
     * by the caller, not assumed.
     */
    private fun injectNavigationBarInset(navInsetDp: Int) {
        composeRule.activityRule.scenario.onActivity { activity ->
            val insets = WindowInsetsCompat.Builder()
                .setInsets(
                    WindowInsetsCompat.Type.navigationBars(),
                    Insets.of(0, 0, 0, (navInsetDp * DENSITY).toInt()),
                )
                .build()
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            generateSequence(content as View) { v ->
                (v as? ViewGroup)?.takeIf { it.childCount > 0 }?.getChildAt(0)
            }.forEach { ViewCompat.dispatchApplyWindowInsets(it, insets) }
        }
    }

    private fun settleAnimations() {
        composeRule.waitForIdle()
        // The banner and the card arrive through fades; an *idle* composition is not
        // necessarily a *settled* one. 2 s is past every transition and still inside the
        // 3.5 s gesture-hint window, so `PLACED_HINT` photographs the hint.
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()
    }

    /** The measurement, on the frame that was just photographed. */
    private fun assertAnchor(
        name: String,
        scene: Scene,
        navInsetDp: Int,
        observedNavInset: Dp,
    ) {
        val dock = composeRule.onNodeWithTag(DemoScaffoldTestTags.DOCK)
            .getUnclippedBoundsInRoot()
        val window = composeRule.onRoot().getUnclippedBoundsInRoot()
        val stack = composeRule.onNodeWithTag(PlacementTestTags.COACH_STACK)
            .getUnclippedBoundsInRoot()

        // The bottom edge of the bottom-most thing actually *on screen*. The anchor's
        // children are declared read-out, card, line — so the line wins whenever it is
        // up, the card when the line is silent, the read-out when both are. Only bottom
        // edges are read: every gutter in this anchor is a *top* padding, so the bottom
        // edge is the painted edge whether the tag sits before or after the padding.
        val paintedBottom = when (scene) {
            Scene.SCANNING, Scene.PLACED_HINT, Scene.TRACKING_LOST ->
                composeRule.onNodeWithTag(PlacementTestTags.COACHING_LINE)
                    .getUnclippedBoundsInRoot().bottom
            Scene.NO_SURFACE ->
                composeRule.onNodeWithTag(PlacementTestTags.PLACEMENT_CARD)
                    .getUnclippedBoundsInRoot().bottom
            Scene.GESTURE ->
                composeRule.onNodeWithTag(PlacementTestTags.SCALE_READOUT)
                    .getUnclippedBoundsInRoot().bottom
        }
        val clearance = dock.top - paintedBottom

        println(
            "MEASURE[$name] density=${composeRule.density.density} " +
                "window=${window.bottom} dockTop=${dock.top} dockBottom=${dock.bottom} " +
                "stackTop=${stack.top} stackBottom=${stack.bottom} " +
                "readout=${boundsOrNull(PlacementTestTags.SCALE_READOUT)} " +
                "card=${boundsOrNull(PlacementTestTags.PLACEMENT_CARD)} " +
                "line=${boundsOrNull(PlacementTestTags.COACHING_LINE)} " +
                "paintedBottom=$paintedBottom clearance=$clearance " +
                "navInsetAsked=${navInsetDp}dp navInsetSeen=$observedNavInset"
        )
        if (navInsetDp > 0) {
            assertTrue(
                "asked for a $navInsetDp dp navigation inset, `safeDrawing` reports " +
                    "$observedNavInset — the dispatch did not reach the composition, so " +
                    "this variant proves nothing about three-button navigation.",
                observedNavInset.value >= navInsetDp - 1f,
            )
        }

        // The invariant, in every state: whatever is bottom-most on screen clears the
        // dock by one gutter, no more and no less.
        assertTrue(
            "[$name] the bottom-most painted overlay ends at $paintedBottom and the " +
                "dock starts at ${dock.top} — a clearance of $clearance, not " +
                "$EXPECTED_CLEARANCE. A gap paid to a hidden child is the usual cause.",
            (clearance - EXPECTED_CLEARANCE).value.absoluteValue <= TOLERANCE.value,
        )
        // The dock does not run off the bottom of the window.
        assertTrue(
            "the dock ends at ${dock.bottom}, past the window's ${window.bottom}.",
            dock.bottom <= window.bottom + TOLERANCE,
        )
    }

    /** Bounds of a tagged node, or `null` when it is not on screen — a log helper. */
    private fun boundsOrNull(tag: String): String = runCatching {
        val b = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        "[${b.top}..${b.bottom}]"
    }.getOrDefault("absent")

    private companion object {
        val TOLERANCE = 0.75.dp

        /** One gutter — `SceneViewTokens.Space.md`, the same grid as the dock. */
        val EXPECTED_CLEARANCE = 16.dp

        /** `xhdpi` from the qualifiers — 1 dp = 2 px, and every number below is in dp. */
        const val DENSITY = 2f

        /**
         * `ARPlacementDemo`'s own dock, item for item: Models and Reset, with the
         * scaffold appending Controls itself. No `dockAccent` — the placement screen
         * declares none.
         */
        val DOCK = listOf(
            DockItem(icon = Icons.Filled.ViewInAr, label = "Models", onClick = {}),
            DockItem(icon = Icons.Filled.Refresh, label = "Reset", onClick = {}),
        )

        /**
         * Same recipe, same reason, as `ContactShadowControlsSnapshotTest`: goldens recorded
         * on macOS are verified on the CI's Linux runners, and the two round some composited
         * colours differently. `maxDistance` rather than a change-percentage, so every pixel
         * stays compared and only sub-perceptual rounding is forgiven.
         *
         * The bound is *measured*, not inherited. Run 35723212447 rejected exactly one of
         * these fourteen goldens, `no-surface-light-white`, and comparing the runner's
         * `_actual.png` against the macOS golden gives a largest single-channel delta of
         * **3/255** — on 7 anti-aliased pixels along the card's and the dock's edges, all
         * three channels drift by 3 at once, a euclidean distance of **0.02038** normalised.
         * The previous 0.02 (the figure `ContactShadowControlsSnapshotTest` measured for its
         * own frames) sat 0.0004 under it. 0.03 clears the measurement with the same margin
         * that file left itself and is still some thirty times under a moved glyph, whose
         * distances sit near 1.0. Calibrated by dropping the runner's `_actual.png` in as
         * the golden and running `verifyRoborazziDebug` on macOS, which reproduces the
         * cross-OS delta locally.
         */
        private val CROSS_PLATFORM_TOLERANT = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                imageComparator = SimpleImageComparator(maxDistance = 0.03f),
            ),
        )
    }
}
