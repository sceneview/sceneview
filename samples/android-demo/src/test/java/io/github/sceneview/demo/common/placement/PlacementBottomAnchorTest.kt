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
 * The anchored `Column` measures itself with `onSizeChanged` **last** in its modifier
 * chain, so it reports its content and nothing else — not the window inset, not the
 * host's `chromeBottom + 16 dp`. Each child of the anchor carries its own 8 dp gutter as
 * a *top* padding inside its visibility wrapper, so a hidden child contributes exactly
 * nothing and the bottom-most visible child sits one 16 dp gutter off the dock.
 *
 * Everything below is *differential*: no assumption about the pill's own height, the
 * banner's own height, or any constant from another module. What is assumed is the
 * promise:
 *
 *  - the host's bottom chrome is counted **once**, so doubling `chromeBottom` moves the
 *    coaching line by exactly that much and not twice that much;
 *  - an empty coaching stack measures **zero**;
 *  - a second child costs its own painted height plus one gutter and nothing else.
 *
 * Robolectric reports no navigation inset, so `windowInsetsPadding` contributes 0 dp in
 * every case below; [PlacementBottomAnchorSnapshotTest] dispatches one by hand.
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
        ForcedTrackingFailure.override = null
        state.cameraReady = true
        state.isTracking = true
        // SCANNING: the coaching line ("Move slowly to find a surface.") is the one child
        // on screen, so it is the painted edge above the dock.
        state.phase = PlacementPhase.SCANNING

        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                CompositionLocalProvider(LocalDemoChromeBottomInset provides chromeBottom) {
                    Box(Modifier.fillMaxSize()) {
                        TapToPlaceStatusOverlays(state = state)
                    }
                }
            }
        }
        settle()
    }

    @After
    fun tearDown() {
        ForcedTrackingFailure.override = null
    }

    @Test
    fun emptyCoachingStack_measuresZero() {
        // INITIALIZING: the init scrim speaks, the anchor says nothing at all.
        state.phase = PlacementPhase.INITIALIZING
        chromeBottom = DOCK_BAND
        settle()

        assertDp(
            "the coaching stack is empty in INITIALIZING, so the Column's content must " +
                "measure 0 dp. Anything else means `onSizeChanged` is reporting padding.",
            expected = 0.dp,
            actual = coachStackContentHeight(),
        )
    }

    @Test
    fun chromeBottom_isCountedExactlyOnce() {
        chromeBottom = 0.dp
        settle()
        val withoutDock = lineTop()

        chromeBottom = DOCK_BAND
        settle()
        val withDock = lineTop()

        assertDp(
            "parking an ${DOCK_BAND.value.toInt()} dp dock under the screen must lift the " +
                "coaching line by exactly that much. It moved by ${withoutDock - withDock}.",
            expected = DOCK_BAND,
            actual = withoutDock - withDock,
        )
    }

    @Test
    fun aSecondChild_costsExactlyItsOwnPaintedHeightPlusOneGutter() {
        chromeBottom = DOCK_BAND
        // Just placed: the one-shot gesture hint is the line. A quiet PLACED has no line
        // at all, so this is the phase in which a read-out can share the anchor with one.
        state.phase = PlacementPhase.PLACED
        state.lastPlacedAtMillis = 1L
        settle(HINT_SAFE_MS)
        val lineOnly = coachStackContentHeight()
        val lineTopBefore = lineTop()

        // A live pinch inside the hint window: the read-out stacks above the line.
        state.scalePercent = 120
        settle(HINT_SAFE_MS)
        val stacked = coachStackContentHeight()
        val readout = composeRule
            .onNodeWithTag(PlacementTestTags.SCALE_READOUT)
            .getUnclippedBoundsInRoot()

        // The read-out's tag sits *after* its own top padding, so its bounds are the pill
        // as painted; the gutter is the difference and nothing else.
        assertDp(
            "the stack grew by ${stacked - lineOnly} for a read-out painted " +
                "${readout.bottom - readout.top} tall — a gutter of " +
                "${stacked - lineOnly - (readout.bottom - readout.top)} rather than $GUTTER.",
            expected = GUTTER,
            actual = stacked - lineOnly - (readout.bottom - readout.top),
        )
        // …and the line, nearest the dock, did not move: the read-out stacks above it.
        assertDp(
            "the coaching line moved by ${lineTopBefore - lineTop()} when the read-out " +
                "appeared above it — the anchor's fixed point is the bottom edge.",
            expected = 0.dp,
            actual = lineTopBefore - lineTop(),
        )
    }

    private fun settle(millis: Long = 2_000) {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(millis)
        composeRule.waitForIdle()
    }

    /**
     * Top edge of the coaching line's node. Its tag sits *before* its top gutter, so this
     * includes the gutter — a constant, cancelled by every difference below.
     */
    private fun lineTop(): Dp = composeRule
        .onNodeWithTag(PlacementTestTags.COACHING_LINE)
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

        /** Past every fade, and two of them still inside the 3.5 s gesture-hint window. */
        const val HINT_SAFE_MS = 1_000L
    }
}
