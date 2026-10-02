package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.PlaneDiscoveryGuide
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `PlacementScene(coachingBottomClearance = …)` — the parameter that lets a host tell the
 * built-in coaching pill about chrome the SDK cannot see
 * ([#3735](https://github.com/sceneview/sceneview/issues/3735)).
 *
 * ## The defect
 *
 * `PlacementScene(coaching = true)` called [PlaneDiscoveryGuide] without `bottomClearance`,
 * so the pill sat one 16 dp gutter off the safe area — underneath whatever the host had
 * parked down there. In this app that is a dock band, so the pill was inside the dock on
 * every device. The app's other AR host already measures that band and hands it to the
 * guide (#3712); `PlacementScene` simply had no way to be told.
 *
 * ## Why this composes the guide and not the screen
 *
 * `PlacementSceneDemo` is one `PlacementScene` call, and `PlacementScene` builds an
 * `ARSceneView` — Filament plus an ARCore session, neither of which exists on the JVM. That
 * screen is therefore **not** covered here; it is the device pass that checks it. What is
 * pinned instead is the contract the new parameter rides on — the value reaches the pill,
 * is honoured **once**, and its default is the gutter the guide already used, so a host
 * that does not pass it does not move. `PlacementScene` forwards the value verbatim, in one
 * argument, with no arithmetic of its own.
 *
 * (The test lives in this module rather than in `arsceneview` because the Compose test
 * artifact is a dependency of this module only; `arsceneview`'s own `PlacementScene*Test`
 * files all exercise extracted pure functions, for the same reason.)
 *
 * The second assertion is **differential**: it assumes neither the pill's own height nor
 * the window inset, which Robolectric reports as zero in both readings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlacementSceneCoachingClearanceTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Driven from the test body. `null` omits the argument entirely, which is the only way
     * to read the parameter's *default* rather than a value this test chose.
     */
    private var clearance by mutableStateOf<Dp?>(null)

    @Before
    fun setUp() {
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize()) {
                    // Camera up, tracking lost with an actionable reason: LOST is the one
                    // phase where exactly ONE element carries the guide's modifier — the
                    // message pill. Same setup as PlacementBottomAnchorTest, for the same
                    // reason.
                    val tagged = Modifier.testTag(GUIDE_TAG)
                    val current = clearance
                    if (current == null) {
                        PlaneDiscoveryGuide(
                            cameraReady = true,
                            isTracking = false,
                            anyPlaneTracked = false,
                            trackingFailureReason = TrackingFailureReason.INSUFFICIENT_LIGHT,
                            modifier = tagged,
                        )
                    } else {
                        PlaneDiscoveryGuide(
                            cameraReady = true,
                            isTracking = false,
                            anyPlaneTracked = false,
                            trackingFailureReason = TrackingFailureReason.INSUFFICIENT_LIGHT,
                            modifier = tagged,
                            bottomClearance = current,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun defaultClearance_isTheGutterTheGuideAlreadyUsed_soExistingHostsDoNotMove() {
        // `coachingBottomClearance` is additive, and its default is this same symbol. A
        // caller that does not pass it must land exactly where it landed before the
        // parameter existed — that is the whole claim that makes the change non-breaking.
        clearance = null
        composeRule.waitForIdle()
        val byDefault = pillTop()

        clearance = LEGACY_GUTTER
        composeRule.waitForIdle()
        val explicit = pillTop()

        assertDp(
            "omitting the clearance must place the pill exactly where passing the guide's " +
                "own 16 dp gutter does. It differs by ${byDefault - explicit}, so the " +
                "default drifted and every existing host of PlacementScene moved with it.",
            expected = 0.dp,
            actual = byDefault - explicit,
        )
    }

    @Test
    fun hostClearance_liftsThePill_byExactlyTheBandItNames() {
        clearance = LEGACY_GUTTER
        composeRule.waitForIdle()
        val withoutDock = pillTop()

        // What PlacementSceneDemo now passes: the scaffold's measured chrome inset plus one
        // gutter — the same source and the same arithmetic as the app's other AR host
        // (#3712, TapToPlaceArSession).
        clearance = DOCK_BAND + SceneViewTokens.Space.md
        composeRule.waitForIdle()
        val withDock = pillTop()

        assertDp(
            "naming a ${DOCK_BAND.value.toInt()} dp dock must lift the pill by exactly the " +
                "difference between the two clearances. It moved by " +
                "${withoutDock - withDock}: twice the expected value means the term is " +
                "being paid on both sides of the hand-off, zero means the parameter never " +
                "reaches the pill.",
            expected = DOCK_BAND + SceneViewTokens.Space.md - LEGACY_GUTTER,
            actual = withoutDock - withDock,
        )
    }

    /**
     * Top edge of the guide's message pill.
     *
     * The tag sits outermost in the guide's modifier chain, so this node's bounds include
     * its window inset and its `bottomClearance`; the node is aligned to the bottom of the
     * window, so a larger clearance moves this edge **up**. A difference between two
     * readings is therefore free of the pill's own height.
     */
    private fun pillTop(): Dp = composeRule
        .onNodeWithTag(GUIDE_TAG)
        .getUnclippedBoundsInRoot()
        .top

    private fun assertDp(message: String, expected: Dp, actual: Dp) {
        // Sub-pixel tolerance: these are dp rounded through px at xhdpi, and a real defect
        // here is never smaller than a dock band.
        assertEquals(message, expected.value.toDouble(), actual.value.toDouble(), 0.75)
    }

    private companion object {
        const val GUIDE_TAG = "placement-scene-discovery-guide"

        /**
         * `PlaneDiscoveryGuide`'s own default gutter, and now `PlacementScene`'s. Spelled
         * out because the SDK constant is `internal` — which is the point: this test is
         * what notices if that value changes underneath the new parameter's KDoc.
         */
        val LEGACY_GUTTER = 16.dp

        /** A stand-in for the demo scaffold's measured bottom chrome. */
        val DOCK_BAND = 80.dp
    }
}
