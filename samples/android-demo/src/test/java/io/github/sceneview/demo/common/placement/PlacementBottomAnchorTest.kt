package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.demo.LocalDemoChromeBottomInset
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The placement screen has **one** bottom anchor, and this pins the arithmetic that makes
 * it one.
 *
 * ## The defect these tests would have caught
 *
 * The anchored `Column` measures itself with `onSizeChanged` and hands that height back to
 * [io.github.sceneview.ar.PlaneDiscoveryGuide] as part of `bottomClearance`, so the
 * discovery pill rides above whatever the coaching line is saying. `onSizeChanged` reports
 * the size of the node **at its own point in the modifier chain** — everything declared to
 * its right, nothing to its left. Declared *first* it therefore reported the content plus
 * the window inset plus `chromeBottom + 16 dp`; `bottomClearance` then added those same two
 * terms a second time, and the pill climbed `navInset + 96 dp` too high. Worse, an empty
 * `Column` already measured `chromeBottom + 16 dp`, so the `coachStackPx > 0` guard could
 * never be false and the pill paid a gutter for a line that was not on screen.
 *
 * Both halves are asserted here on real bounds, and both are *differential*: nothing below
 * assumes the pill's own height, the banner's own height, or any constant that lives in
 * another module. What they assume is the promise:
 *
 *  - the host's bottom chrome is counted **once**, so doubling `chromeBottom` moves the
 *    pill by exactly that much and not twice that much;
 *  - an empty coaching stack measures **zero**, so the guard can fire;
 *  - a non-empty one costs its own measured height and nothing else — the 8 dp gutter is
 *    *inside* that measurement, because each child of the anchor carries its own gutter as
 *    a top padding within its visibility wrapper. It used to sit outside, as a
 *    `spacedBy` on the Column plus a matching term in `bottomClearance`, and that is
 *    precisely what put a hidden child's gutter on screen: `spacedBy` pays a gap between
 *    *children*, not between *visible* ones, so a silent coaching line still bought 8 dp
 *    and the read-out sat 24 dp off the dock instead of 16.
 *
 * ## Why the window inset is not a variable here
 *
 * Robolectric reports no navigation inset, so `windowInsetsPadding` contributes 0 dp in
 * every case below and the two navigation modes are indistinguishable from a JVM test.
 * That term is verified by arithmetic in the pull request instead: it enters the sum
 * exactly once, through `windowInsetsPadding` on the Column and on the guide's own pill,
 * and the regression was that `onSizeChanged` re-injected it — which is precisely what
 * [chromeBottom_isCountedExactlyOnce] detects, because the double-counting is the same
 * mechanism for both terms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlacementBottomAnchorTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Driven from the test body; the scaffold measures this on a real device. */
    private var chromeBottom by mutableStateOf(0.dp)

    private val state = TapToPlaceState()

    @Before
    fun setUp() {
        // Camera up, tracking lost, with a reason: `PlaneDiscoveryGuideState` calls that an
        // actionable failure and goes to LOST, the one phase where exactly ONE element
        // carries the guide's modifier — the message pill. No forced override, because
        // that one is a QA shim and the anchor must hold without it.
        ForcedTrackingFailure.override = null
        state.cameraReady = true
        state.isTracking = false
        state.trackingFailureReason = TrackingFailureReason.INSUFFICIENT_LIGHT
        state.anyPlaneTracked = false

        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                CompositionLocalProvider(LocalDemoChromeBottomInset provides chromeBottom) {
                    Box(Modifier.fillMaxSize()) {
                        TapToPlaceStatusOverlays(state = state, nextModelLabel = "Sofa")
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @After
    fun tearDown() {
        ForcedTrackingFailure.override = null
    }

    @Test
    fun emptyCoachingStack_measuresZero_soTheGuardCanFire() {
        // TRACKING_LOST: `placementCoaching` returns null because the guide owns every
        // pre-surface phase. Nothing in the Column draws, so its content is 0 dp — and
        // only then is `coachStackPx > 0` a meaningful question.
        chromeBottom = DOCK_BAND
        composeRule.waitForIdle()

        assertDp(
            "the coaching stack is empty in SCANNING, so the Column's content must " +
                "measure 0 dp. Anything else means `onSizeChanged` is reporting padding " +
                "— the `coachStackPx > 0` guard can then never be false, and the " +
                "discovery pill pays a gutter for a line that is not on screen.",
            expected = 0.dp,
            actual = coachStackContentHeight(),
        )
    }

    @Test
    fun chromeBottom_isCountedExactlyOnce() {
        // The whole blocker in one number. `chromeBottom` reaches the pill through
        // `bottomClearance`; it must NOT also reach it through the measured stack.
        chromeBottom = 0.dp
        composeRule.waitForIdle()
        val withoutDock = pillTop()

        chromeBottom = DOCK_BAND
        composeRule.waitForIdle()
        val withDock = pillTop()

        assertDp(
            "parking an ${DOCK_BAND.value.toInt()} dp dock under the screen must lift the " +
                "discovery pill by exactly that much. It moved by ${withoutDock - withDock}. " +
                "Twice the dock band means `onSizeChanged` sits at the head of the Column's " +
                "modifier chain again and is reporting the very padding `bottomClearance` " +
                "then adds a second time.",
            expected = DOCK_BAND,
            actual = withoutDock - withDock,
        )
    }

    @Test
    fun aNonEmptyCoachingStack_costsExactlyItsOwnMeasuredHeight() {
        chromeBottom = DOCK_BAND
        composeRule.waitForIdle()
        val quiet = pillTop()

        // A live pinch. The read-out is the child of the anchor that can share the screen
        // with the guide: the coaching *line* never can, by construction — it is null for
        // every phase the guide speaks in, which is the whole point of the split. The
        // read-out has no such rule, so "tracking dropped while two fingers were on the
        // model" puts a pill and a stack on screen at once, and that is the configuration
        // in which the stacking arithmetic is observable at all.
        state.scalePercent = 120
        composeRule.waitForIdle()
        val stacked = pillTop()
        val stack = coachStackContentHeight()

        assertDp(
            "the coaching stack must lift the pill by exactly its own measured height. " +
                "The stack measures $stack and the pill rose by ${quiet - stacked}. Long " +
                "by a gutter means `bottomClearance` is adding one the stack already " +
                "contains; short means the guard read a stack that was never zero.",
            expected = stack,
            actual = quiet - stacked,
        )

        // …and the gutter really is in there, rather than quietly gone. The read-out's tag
        // sits *after* its own top padding, so its bounds are the pill as painted, and the
        // difference between the two readings is the gutter and nothing else. Without this
        // the assertion above would still pass with every gutter deleted.
        val readout = composeRule
            .onNodeWithTag(PlacementTestTags.SCALE_READOUT)
            .getUnclippedBoundsInRoot()
        assertDp(
            "the stack measures $stack around a read-out painted " +
                "${readout.bottom - readout.top} tall, a gutter of " +
                "${stack - (readout.bottom - readout.top)} rather than $GUTTER. The " +
                "anchor's children each carry their own top gutter inside their " +
                "visibility wrapper — a hidden child must contribute neither.",
            expected = GUTTER,
            actual = stack - (readout.bottom - readout.top),
        )
    }

    /**
     * Top edge of the guide's message pill.
     *
     * Every padding on the pill's node — window inset, side gutter, `bottomClearance` — is
     * applied to that same layout node, so its bounds run from the visible top of the pill
     * to the bottom of the window. The top edge is therefore the pill's real position, and
     * differences between two of these readings are free of the pill's own height.
     */
    private fun pillTop(): Dp = composeRule
        .onNodeWithTag(PlacementTestTags.DISCOVERY_GUIDE)
        .getUnclippedBoundsInRoot()
        .top

    /**
     * The anchored Column's content height — what `onSizeChanged` is supposed to report,
     * read back from the layout instead of from the field.
     */
    private fun coachStackContentHeight(): Dp {
        val window = composeRule.onRoot().getUnclippedBoundsInRoot()
        val stack = composeRule
            .onNodeWithTag(PlacementTestTags.COACH_STACK)
            .getUnclippedBoundsInRoot()
        return window.bottom - stack.top - chromeBottom - SceneViewTokens.Space.md
    }

    private fun assertDp(message: String, expected: Dp, actual: Dp) {
        // Sub-pixel: these are px rounded back to dp at xhdpi, so a half-dp of rounding is
        // expected and a defect is never smaller than 8 dp.
        assertEquals(message, expected.value.toDouble(), actual.value.toDouble(), 0.75)
    }

    private companion object {
        /** `DemoDock`'s measured band on this device: 64 dp of dock + one 16 dp gutter. */
        val DOCK_BAND = 80.dp
        val GUTTER = SceneViewTokens.Space.sm
    }
}
