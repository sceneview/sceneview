package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The one-shot gesture hint after a placement — "Drag to move. Pinch to resize. Twist to
 * turn." — as the 3D AR Model Viewer app runs its own: it holds [PLACEMENT_GESTURE_HINT_MS]
 * (6 s, three sentences need the time) unless the user starts a gesture first, and then it
 * leaves for good. A hint that comes back once the gesture ends talks over the thing it
 * described, which is the defect pinned here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlacementGestureHintTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val state = TapToPlaceState()

    @Before
    fun setUp() {
        ForcedTrackingFailure.override = null
        state.cameraReady = true
        state.isTracking = true
        state.phase = PlacementPhase.SCANNING
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    TapToPlaceStatusOverlays(state = state)
                }
            }
        }
        advance(0)
    }

    @Test
    fun withoutAGesture_theHint_holdsSixSeconds() {
        place()

        advance(PLACEMENT_GESTURE_HINT_MS - 500)
        assertTrue("the hint must still be up 5.5 s after the placement", hintShown())

        advance(500 + PAST_FADE_MS)
        assertFalse("the hint must be gone once its 6 s window closes", hintShown())
    }

    @Test
    fun theFirstGesture_dismissesTheHint_andItDoesNotComeBack() {
        place()
        advance(1_000)
        assertTrue("the hint must be up a second after the placement", hintShown())

        state.activeGesture = PlacementGesture.MOVING
        state.phase = PlacementPhase.ADJUSTING
        advance(PAST_FADE_MS)
        assertFalse("the first drag must dismiss the hint", hintShown())

        // The drag ends well inside the 6 s window: the hint must not return.
        state.activeGesture = null
        state.phase = PlacementPhase.PLACED
        advance(PAST_FADE_MS)
        assertFalse("the hint came back after the gesture it described", hintShown())
    }

    private fun place() {
        state.phase = PlacementPhase.PLACED
        state.lastPlacedAtMillis = 1L
        advance(0)
    }

    private fun advance(millis: Long) {
        composeRule.mainClock.advanceTimeBy(millis + FRAME_MS)
        composeRule.waitForIdle()
    }

    private fun hintShown(): Boolean =
        composeRule.onAllNodesWithText(HINT, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        /** `ar_place_gesture_hint`, the copy aligned with the 3D AR Model Viewer app. */
        const val HINT = "Drag to move. Pinch to resize. Twist to turn."

        /** Past the pill's exit fade. */
        const val PAST_FADE_MS = 800L

        const val FRAME_MS = 16L
    }
}
