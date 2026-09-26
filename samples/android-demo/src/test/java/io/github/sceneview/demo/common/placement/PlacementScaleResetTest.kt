package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.runner.RunWith

/**
 * The pinch read-out's tap-to-reset affordance
 * ([#3830](https://github.com/sceneview/sceneview/issues/3830)).
 *
 * Before this, the read-out was pure display: it vanished the instant the fingers lifted
 * (`onScaleEnd` nulled `scalePercent` unconditionally), so a model pinched to 303 % had
 * *nothing* on screen to bring it back to 100 % short of pinching back through the ±6 %
 * detent by hand. `TapToPlaceStatusOverlays` now owns a short post-pinch lifetime for the
 * read-out instead — long enough to tap it, per [PLACEMENT_SCALE_RESET_WINDOW_MS] — and the
 * read-out itself is clickable, wired to `AutoPlacementState.scaleTo(1f)` via `onResetScale`.
 *
 * `AutoPlacementState.scaleTo`'s actual effect on a Filament node needs a real ARCore
 * session and is out of reach on the JVM — see the PR's `needs-device` note. What is pinned
 * here is everything above that: the read-out's own lifetime, and that a tap invokes the
 * callback exactly when there is something to reset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlacementScaleResetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val state = TapToPlaceState()
    private var resetCalls = 0

    @Before
    fun setUp() {
        ForcedTrackingFailure.override = null
        state.cameraReady = true
        state.isTracking = true
        state.phase = PlacementPhase.PLACED
        resetCalls = 0

        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    TapToPlaceStatusOverlays(
                        state = state,
                        onResetScale = { resetCalls++ },
                    )
                }
            }
        }
    }

    @After
    fun tearDown() {
        ForcedTrackingFailure.override = null
    }

    @Test
    fun `tapping the read-out while off real-world size invokes the reset callback`() {
        state.scalePercent = 303
        state.isRealWorldSize = false
        state.scaleLabel = ScaleLabelMode.PREVIEW
        settle(500)

        composeRule.onNodeWithTag(PlacementTestTags.SCALE_READOUT)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        assertEquals(1, resetCalls)
    }

    @Test
    fun `the read-out at real-world size offers no click action`() {
        state.scalePercent = 100
        state.isRealWorldSize = true
        settle(500)

        composeRule.onNodeWithTag(PlacementTestTags.SCALE_READOUT)
            .assertIsDisplayed()
            .assertHasNoClickAction()
    }

    @Test
    fun `off real-world size the read-out survives the gesture-hint-sized window, then hides`() {
        state.scalePercent = 303
        state.isRealWorldSize = false
        // Inside the window: still there, still tappable.
        settle(PLACEMENT_SCALE_RESET_WINDOW_MS - 500)
        composeRule.onNodeWithTag(PlacementTestTags.SCALE_READOUT).assertIsDisplayed()

        // Past it: nothing left to tap.
        settle(1_000)
        assertNodeAbsent(PlacementTestTags.SCALE_READOUT)
    }

    @Test
    fun `at real-world size the read-out only lingers as a brief confirmation`() {
        state.scalePercent = 100
        state.isRealWorldSize = true
        // Past the short confirmation window but well inside the longer reset window —
        // proves the two durations are actually different, not one constant reused twice.
        settle(PLACEMENT_SCALE_CONFIRM_MS + 500)
        assertNodeAbsent(PlacementTestTags.SCALE_READOUT)
    }

    @Test
    fun `a live pinch never gets timed out from under the user's fingers`() {
        state.scalePercent = 303
        state.isRealWorldSize = false
        state.activeGesture = PlacementGesture.SCALING
        // Comfortably past both windows — the gesture is still live, so nothing hides it.
        settle(PLACEMENT_SCALE_RESET_WINDOW_MS + 1_000)

        composeRule.onNodeWithTag(PlacementTestTags.SCALE_READOUT).assertIsDisplayed()
    }

    private fun settle(millis: Long) {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(millis)
        composeRule.waitForIdle()
    }

    private fun assertNodeAbsent(tag: String) {
        composeRule.onNodeWithTag(tag).assertDoesNotExistOrIsGone()
    }

    /**
     * `AnimatedVisibility(visible = false)` keeps the node in the tree for the length of
     * the exit fade rather than removing it immediately, so "gone" here means "not
     * displayed", not "absent from the semantics tree" — [assertIsDisplayed]'s negation.
     */
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistOrIsGone() {
        runCatching { assertIsDisplayed() }.onSuccess {
            throw AssertionError("expected the read-out to be hidden by now, but it is displayed")
        }
    }
}
