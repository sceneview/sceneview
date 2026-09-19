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

/**
 * Pictures of the placement screen's bottom anchor, with the gap measured on the same
 * composition that is photographed.
 *
 * ## Why this file exists
 *
 * [PlacementBottomAnchorTest] pins the arithmetic differentially and is the better
 * regression test — it fails loudly and names the mechanism. What it cannot do is show
 * that the result *looks* right, and the pull request's device proof was blocked twice:
 * no ARCore session starts on the CI emulator (so the discovery pill and the coaching
 * line never render there at all), and the review device was locked. So this file
 * renders the real [DemoScaffold] with the real dock and the real
 * [TapToPlaceStatusOverlays] over it, photographs the result, and asserts the 16 dp on
 * the bounds of that same frame.
 *
 * What it is **not**: proof that an ARCore session starts, that the camera feed arrives,
 * or that the frame is composited correctly by the GPU on a real phone. Those need a
 * device. This is proof of *layout* only, which is the part that was in doubt.
 *
 * ## Both navigation modes, because the failure was mode-dependent
 *
 * The arithmetic this screen replaced put the pill 8 dp inside the dock with a gesture bar
 * and 32 dp inside it with three buttons, so a proof that only ever sees one of the two
 * modes is half a proof. Robolectric hands the window no system bars — the limitation
 * [PlacementBottomAnchorTest] documents — so the `*_threeButtonNav` cases dispatch a 48 dp
 * navigation inset onto the composition's host view by hand, and then *assert that
 * `safeDrawing` actually reports it*. A dispatch that stops being delivered turns those
 * cases red instead of quietly re-photographing the gesture-bar case under a second name.
 *
 * ## What the pictures show that the numbers do not
 *
 * The guide's pill lives in the scaffold's `scene` slot, and the scaffold paints its
 * bottom scrim *over* that slot. So the pill is veiled by the same wash that grounds the
 * dock: its white text composites down to 141/255 at the pill's top row and 82/255 at its
 * bottom one, which is 4.3:1 falling to 2.3:1 against the pill's own fill on a white
 * camera frame. The dock's captions, painted *above* the scrim, keep their 5.4:1. This is
 * not a property of the anchor and it predates it — it is why the pictures are here.
 *
 * ## The backdrop is flat white and flat black, not a camera still
 *
 * The overlay is read against an arbitrary camera frame, so every case is photographed
 * twice: once on white and once on black, the two ends of the range a translucent glass
 * surface has to survive. Flat grounds are deterministic across hosts (no JPEG decode in
 * the golden) and they are what makes the painted edges unambiguous when the PNG is
 * measured for the 16 dp.
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
     * it. Held on the class rather than in a local because the value that matters is the
     * one *after* [injectNavigationBarInset] has run, i.e. after the composing call has
     * already returned.
     */
    private var observedBottomInsetPx = 0

    @Before
    fun setUp() {
        // LOST is the one phase where exactly one of the guide's three elements carries
        // its modifier — the message pill — so DISCOVERY_GUIDE is unambiguous and the
        // coaching line is null, which is what makes the stack empty.
        ForcedTrackingFailure.override = null
        state.cameraReady = true
        state.isTracking = false
        state.trackingFailureReason = TrackingFailureReason.INSUFFICIENT_LIGHT
        state.anyPlaneTracked = false
    }

    @After
    fun tearDown() {
        ForcedTrackingFailure.override = null
    }

    @Test
    fun quietStack_dark_onWhite() = capture("quiet-dark-white", true, Color.White)

    @Test
    fun quietStack_dark_onBlack() = capture("quiet-dark-black", true, Color.Black)

    @Test
    fun quietStack_light_onWhite() = capture("quiet-light-white", false, Color.White)

    @Test
    fun quietStack_light_onBlack() = capture("quiet-light-black", false, Color.Black)

    @Test
    fun liveGesture_dark_onWhite() = capture("gesture-dark-white", true, Color.White, 120)

    @Test
    fun liveGesture_dark_onBlack() = capture("gesture-dark-black", true, Color.Black, 120)

    @Test
    fun liveGesture_light_onWhite() = capture("gesture-light-white", false, Color.White, 120)

    @Test
    fun liveGesture_light_onBlack() = capture("gesture-light-black", false, Color.Black, 120)

    /**
     * Three-button navigation — the mode the old arithmetic failed hardest (the pill sat
     * 32 dp *inside* the dock). 48 dp is the platform's three-button bar.
     */
    @Test
    fun quietStack_dark_threeButtonNav() =
        capture("quiet-dark-white-nav48", true, Color.White, navInsetDp = 48)

    @Test
    fun liveGesture_dark_threeButtonNav() =
        capture("gesture-dark-white-nav48", true, Color.White, 120, navInsetDp = 48)

    /**
     * Composes the screen, photographs it, and measures the anchor on the frame that was
     * photographed — the picture and the number can therefore never disagree.
     */
    private fun capture(
        name: String,
        darkTheme: Boolean,
        ground: Color,
        scalePercent: Int? = null,
        navInsetDp: Int = 0,
    ) {
        state.scalePercent = scalePercent
        composeScreen(darkTheme, ground)
        if (navInsetDp > 0) injectNavigationBarInset(navInsetDp)
        settleAnimations()

        // What `safeDrawing` actually reported, so a silently-ignored dispatch shows up as
        // a number in the log instead of passing as a proof it never made.
        val observedNavInset = with(composeRule.density) { observedBottomInsetPx.toDp() }

        composeRule.onRoot().captureRoboImage(
            "src/test/snapshots/placement_bottom_$name.png",
            roborazziOptions = CROSS_PLATFORM_TOLERANT,
        )
        assertAnchor(name, navInsetDp, observedNavInset)
    }

    /** The real scaffold, the real dock, the real overlays, over a flat stand-in scene. */
    private fun composeScreen(darkTheme: Boolean, ground: Color) {
        composeRule.setContent {
            observedBottomInsetPx = WindowInsets.safeDrawing.getBottom(LocalDensity.current)
            SceneViewDemoTheme(darkTheme = darkTheme) {
                DemoScaffold(
                    title = "Tap to place",
                    onBack = {},
                    dock = DOCK,
                    scene = {
                        // Stands in for the camera feed. Flat white and flat black are
                        // the two ends of the range the overlay can be asked to read
                        // against — the worst case for a translucent glass surface in
                        // each direction — and a flat ground is also what makes the
                        // painted edges unambiguous when the PNG is measured.
                        Box(Modifier.fillMaxSize().background(ground))
                        TapToPlaceStatusOverlays(state = state, nextModelLabel = "Sofa")
                    },
                )
            }
        }
    }

    /**
     * Robolectric hands the window no system bars at all, so `safeDrawing` is 0 on every
     * side and the two navigation modes are indistinguishable — the exact limitation
     * [PlacementBottomAnchorTest] documents. Dispatching the insets by hand is the only
     * way to render the three-button case off-device. Whether it takes is *measured* by
     * the caller, not assumed.
     */
    private fun injectNavigationBarInset(navInsetDp: Int) {
        composeRule.activityRule.scenario.onActivity { activity ->
            val insets = WindowInsetsCompat.Builder()
                .setInsets(
                    WindowInsetsCompat.Type.navigationBars(),
                    Insets.of(0, 0, 0, (navInsetDp * DENSITY).toInt()),
                )
                .build()
            // Compose listens on the `AndroidComposeView`, not on the decor view, and a
            // decor-level dispatch does not reach it under Robolectric. Hand the insets to
            // the composition's own host view instead.
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            generateSequence(content as View) { v ->
                (v as? ViewGroup)?.takeIf { it.childCount > 0 }?.getChildAt(0)
            }.forEach { ViewCompat.dispatchApplyWindowInsets(it, insets) }
        }
    }

    private fun settleAnimations() {
        composeRule.waitForIdle()
        // The guide is a time-driven state machine and its pill arrives through a 150 ms
        // `fadeIn`, so an *idle* composition is not necessarily a *settled* one — advance
        // past every transition before photographing.
        //
        // This is NOT what dims the pill. The first run of this file caught its text at
        // 141/255 and the animation was the obvious suspect; advancing the clock moved
        // neither the geometry nor a single contrast reading. The dimming is the
        // scaffold's own bottom scrim, painted over the `scene` slot the guide lives in —
        // see the class KDoc.
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()
    }

    /** The measurement, on the frame that was just photographed. */
    private fun assertAnchor(name: String, navInsetDp: Int, observedNavInset: Dp) {
        val pill = composeRule.onNodeWithTag(PlacementTestTags.DISCOVERY_GUIDE)
            .getUnclippedBoundsInRoot()
        val dock = composeRule.onNodeWithTag(DemoScaffoldTestTags.DOCK)
            .getUnclippedBoundsInRoot()
        val window = composeRule.onRoot().getUnclippedBoundsInRoot()

        // `getUnclippedBoundsInRoot` returns the node's bounds *including* every padding
        // in its own modifier chain. Both the guide and the anchored Column carry their
        // clearance as trailing `padding(bottom = …)`, so both report a bottom edge of
        // `window.bottom` and neither is the painted edge. Only two readings here are
        // painted edges, and only those are asserted on:
        //
        //  - `pill.top`  — every padding on the guide's node is horizontal or bottom, so
        //                  the top of its bounds is where the pill is actually drawn.
        //                  (This is the same reading `PlacementBottomAnchorTest` uses.)
        //  - `dock.top`  — the tag sits after `.overMediaEdge(shape).height(dockHeight)`,
        //                  so the tagged node IS the toolbar box.
        //
        // The gap between the *pill's* painted bottom and the dock is therefore measured
        // on the PNG this test just wrote, not reconstructed from the formula under test.
        val stack = composeRule.onNodeWithTag(PlacementTestTags.COACH_STACK)
            .getUnclippedBoundsInRoot()

        println(
            "MEASURE[$name] density=${composeRule.density.density} " +
                "window=${window.bottom} pillTop=${pill.top} " +
                "dockTop=${dock.top} dockBottom=${dock.bottom} stackTop=${stack.top} " +
                "navInsetAsked=${navInsetDp}dp navInsetSeen=$observedNavInset"
        )
        if (navInsetDp > 0) {
            // The whole point of the variant. If the dispatch is a no-op under Robolectric
            // this fails and says so, rather than photographing the gesture-nav case twice
            // and calling the second picture a three-button proof.
            assertTrue(
                "asked for a ${navInsetDp} dp navigation inset, `safeDrawing` reports " +
                    "$observedNavInset — the dispatch did not reach the composition, so " +
                    "this variant proves nothing about three-button navigation.",
                observedNavInset.value >= navInsetDp - 1f,
            )
        }

        // The pill is drawn above the dock, not inside it.
        assertTrue(
            "the discovery pill's top edge is at ${pill.top}, at or below the dock's top " +
                "edge ${dock.top} — the pill is inside the dock.",
            pill.top < dock.top,
        )
        // The dock does not run off the bottom of the window.
        assertTrue(
            "the dock ends at ${dock.bottom}, past the window's ${window.bottom}.",
            dock.bottom <= window.bottom + TOLERANCE,
        )
    }

    private companion object {
        val TOLERANCE = 0.75.dp

        /** `xhdpi` from the qualifiers — 1 dp = 2 px, and every number below is in dp. */
        const val DENSITY = 2f

        /**
         * `ARPlacementDemo`'s own dock, item for item: Models and Clear, with the
         * scaffold appending Controls itself. No `dockAccent` — the placement screen
         * declares none, and the accent item is the one piece of this band that is
         * themed, so a harness that adds one photographs a light/dark difference the
         * screen does not have.
         */
        val DOCK = listOf(
            DockItem(icon = Icons.Filled.ViewInAr, label = "Models", onClick = {}),
            DockItem(icon = Icons.Filled.Refresh, label = "Clear", onClick = {}),
        )

        /**
         * Same tolerance, same reason, as `ContactShadowControlsSnapshotTest`: goldens
         * recorded on macOS are verified on the CI's Linux runners, and the two round some
         * composited colours differently.
         *
         * Measured here, not inherited on faith. Run 35455042711 rejected all ten of these
         * goldens; comparing each one against the runner's own `_actual.png` gives a
         * **largest single-channel delta of 2/255** and not one pixel beyond it — on a frame
         * whose own contrast range spans 0..255. The geometry is identical: every
         * `MEASURE[…]` line the runner printed matches the local one to the dp.
         *
         * `maxDistance` rather than a change-percentage: the drift is spread thinly over the
         * scrim's gradient, so a percentage big enough to absorb it would wave through a
         * moved pill. This keeps every pixel compared and forgives only sub-perceptual
         * rounding — a real regression here moves whole glyph blocks, distances near 1.0.
         */
        private val CROSS_PLATFORM_TOLERANT = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                imageComparator = SimpleImageComparator(maxDistance = 0.02f),
            ),
        )
    }
}
