package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import io.github.sceneview.ar.ARCoachingOverlay
import io.github.sceneview.ar.ArGuidanceCue
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
 * `PlacementScene(coachingBottomClearance = …)` — the parameter that lets a host tell the
 * built-in coaching about chrome the SDK cannot see
 * ([#3735](https://github.com/sceneview/sceneview/issues/3735)) — now that the coaching is the
 * [ARCoachingOverlay] card (#4038) rather than the plane-discovery pill.
 *
 * ## What is pinned
 *
 * `PlacementScene` cannot be composed on the JVM (it builds an `ARSceneView`: Filament plus
 * an ARCore session), so this composes what it hands the clearance to, with the same
 * arithmetic: the card keeps its own 16 dp gutter off the safe area, so `PlacementScene`
 * passes only the excess, `coachingBottomClearance - 16 dp`, as the card's bottom
 * `contentPadding` ([placementSceneCoaching]). The claims:
 *
 *  - the default clearance is the card's own gutter, so a host that passes nothing lands
 *    exactly where a bare card does;
 *  - the card and its "Surface found" pill never reach into the band the host names — in
 *    portrait and in a short landscape window, where the pill's 120 dp drop under the centre
 *    used to overshoot the band and land on the dock;
 *  - the clearance is paid once: in that short window the pill rests exactly on the band's
 *    bottom edge, not a second clearance above it.
 *
 * Robolectric reports no window inset, so `safeDrawing` contributes 0 dp throughout. The card
 * renders in inspection mode, its resting pose, as in `ARCoachingOverlaySnapshotTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlacementSceneCoachingClearanceTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** `null` composes a bare card: no `contentPadding` argument at all. */
    private var clearance by mutableStateOf<Dp?>(null)
    private var cue by mutableStateOf(ArGuidanceCue.SCAN)

    private fun show() {
        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                SceneViewDemoTheme(darkTheme = true) {
                    Box(Modifier.fillMaxSize()) {
                        val current = clearance
                        if (current == null) {
                            ARCoachingOverlay(cue = cue)
                        } else {
                            ARCoachingOverlay(cue = cue, contentPadding = placementSceneCoaching(current))
                        }
                    }
                }
            }
        }
        settle()
    }

    @Test
    fun defaultClearance_isTheCardsOwnGutter_soExistingHostsDoNotMove() {
        show()
        val bare = cardBounds()

        clearance = DEFAULT_CLEARANCE
        settle()
        val byDefault = cardBounds()

        assertDp(
            "PlacementScene's default clearance must place the card exactly where a bare card " +
                "sits. It differs by ${byDefault.top - bare.top}: the default and the card's own " +
                "gutter drifted apart, and every host that passes nothing moved.",
            expected = 0.dp,
            actual = byDefault.top - bare.top,
        )
    }

    @Test
    fun hostClearance_keepsTheCardAndThePill_outOfTheBandItNames() {
        clearance = DOCK_CLEARANCE
        show()
        assertAboveBand("card", cardBounds(), DOCK_CLEARANCE)

        cue = ArGuidanceCue.SURFACE_FOUND
        settle()
        assertAboveBand("\"Surface found\" pill", pillBounds(), DOCK_CLEARANCE)
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-xhdpi")
    fun shortLandscapeWindow_thePillRestsOnTheBand_insteadOfDroppingOntoTheDock() {
        clearance = DOCK_CLEARANCE
        cue = ArGuidanceCue.SURFACE_FOUND
        show()
        val pill = pillBounds()
        val bandBottom = windowHeight() - DOCK_CLEARANCE

        // 360 dp - 16 dp gutter - 96 dp clearance leaves a 248 dp band: the full 120 dp drop
        // would put the pill's bottom 16 dp past it, on the dock. Clamped, it rests on the edge.
        assertDp(
            "in a short band the pill must rest exactly on the band's bottom edge. It is " +
                "${bandBottom - pill.bottom} above it: negative means it reached into the dock, " +
                "a full clearance means the clearance was paid twice.",
            expected = 0.dp,
            actual = bandBottom - pill.bottom,
        )
    }

    private fun settle() {
        composeRule.waitForIdle()
        // Past every enter transition.
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
    }

    private fun assertAboveBand(what: String, bounds: DpRect, clearance: Dp) {
        val bandTop = windowHeight() - clearance
        assertTrue(
            "the $what must end above the ${clearance.value.toInt()} dp the host names; its " +
                "bottom is ${bounds.bottom - bandTop} into that band.",
            bounds.bottom <= bandTop + TOLERANCE,
        )
    }

    private fun cardBounds(): DpRect = composeRule
        .onNodeWithContentDescription(CARD_WORDS, substring = true)
        .getUnclippedBoundsInRoot()
        .let { DpRect(it.left, it.top, it.right, it.bottom) }

    private fun pillBounds(): DpRect = composeRule
        .onNodeWithContentDescription(FOUND_WORDS)
        .getUnclippedBoundsInRoot()
        .let { DpRect(it.left, it.top, it.right, it.bottom) }

    private fun windowHeight(): Dp = composeRule.onRoot().getUnclippedBoundsInRoot().bottom

    private fun assertDp(message: String, expected: Dp, actual: Dp) {
        // Sub-pixel tolerance: dp rounded through px at xhdpi; a real defect is a gutter or more.
        assertEquals(message, expected.value.toDouble(), actual.value.toDouble(), TOLERANCE.value.toDouble())
    }

    private companion object {
        /** `PlacementScene`'s default `coachingBottomClearance`, spelled out: the SDK symbol is internal. */
        val DEFAULT_CLEARANCE = 16.dp

        /** What a host with this app's dock passes: the dock band plus one gutter. */
        val DOCK_CLEARANCE = 80.dp + SceneViewTokens.Space.md

        val TOLERANCE = 0.75.dp

        /** `sceneview_coaching_scan_surface`, the headline of the scan card. */
        const val CARD_WORDS = "Move your phone slowly"

        /** `sceneview_coaching_found_surface`. */
        const val FOUND_WORDS = "Surface found"

        /** What `PlacementScene` hands the card for its `coachingBottomClearance`. */
        fun placementSceneCoaching(clearance: Dp) =
            PaddingValues(bottom = (clearance - DEFAULT_CLEARANCE).coerceAtLeast(0.dp))
    }
}
