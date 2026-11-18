package io.github.sceneview.ar

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.google.ar.core.TrackingFailureReason
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Error coaching must name the problem and one action, including in its TalkBack node. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ARCoachingOverlayHintTest {

    @get:Rule(order = 0)
    val registerActivity = object : ExternalResource() {
        override fun before() {
            val context = RuntimeEnvironment.getApplication()
            shadowOf(context.packageManager).addActivityIfNotPresent(
                ComponentName(context.packageName, ComponentActivity::class.java.name),
            )
        }
    }

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Test
    fun recoveryReasonChanges_updateTheCardWhileThePublicMovementHintStaysNone() {
        val guidance = ArGuidanceState().apply {
            update(PlacementPhase.INITIALIZING, 0L, 0L)
            update(PlacementPhase.INITIALIZING, 0L, ArGuidanceState.INITIALIZING_DELAY_MS)
        }
        // INITIALIZING has no looping illustration: let the rule settle text/chip animations.
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) { ARCoachingOverlay(guidance) }
        }
        composeRule.onNodeWithContentDescription("Getting ready. Hold your phone up and look around")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))

        composeRule.runOnIdle {
            guidance.update(PlacementPhase.INITIALIZING, 0L, TrackingFailureReason.BAD_STATE, 1_000L)
            guidance.update(PlacementPhase.INITIALIZING, 0L, TrackingFailureReason.BAD_STATE, 1_700L)
            assertEquals(ArTrackingHint.NONE, guidance.hint)
        }
        composeRule.onNodeWithContentDescription("AR error. Restart the AR view. AR stopped working")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

        composeRule.runOnIdle {
            guidance.update(PlacementPhase.INITIALIZING, 0L, TrackingFailureReason.CAMERA_UNAVAILABLE, 1_800L)
            guidance.update(PlacementPhase.INITIALIZING, 0L, TrackingFailureReason.CAMERA_UNAVAILABLE, 3_200L)
            assertEquals(ArTrackingHint.NONE, guidance.hint)
        }
        composeRule.onNodeWithContentDescription(
            "Camera busy. Close the other camera app. Another app is using the camera",
        ).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

        composeRule.runOnIdle {
            guidance.update(PlacementPhase.INITIALIZING, 0L, TrackingFailureReason.NONE, 4_000L)
            guidance.update(PlacementPhase.INITIALIZING, 0L, TrackingFailureReason.NONE, 5_400L)
        }
        composeRule.onNodeWithContentDescription("Getting ready. Hold your phone up and look around")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun internalError_respectsTheHostCueMaskAndReturnsWhenItClears() = assertCueMask(
        TrackingFailureReason.BAD_STATE,
        "AR error. Restart the AR view. AR stopped working",
    )

    @Test
    fun cameraBusy_respectsTheHostCueMaskAndReturnsWhenItClears() = assertCueMask(
        TrackingFailureReason.CAMERA_UNAVAILABLE,
        "Camera busy. Close the other camera app. Another app is using the camera",
    )

    private fun assertCueMask(reason: TrackingFailureReason, spoken: String) {
        val guidance = ArGuidanceState().apply {
            update(PlacementPhase.INITIALIZING, 0L, reason, 0L)
            update(PlacementPhase.INITIALIZING, 0L, reason, ArGuidanceState.HINT_STABLE_MS)
        }
        var visible by mutableStateOf(false)
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                ARCoachingOverlay(
                    guidance = guidance,
                    cue = if (visible) guidance.cue else ArGuidanceCue.NONE,
                )
            }
        }
        composeRule.onNodeWithContentDescription(spoken).assertDoesNotExist()
        composeRule.runOnIdle { visible = true }
        composeRule.onNodeWithContentDescription(spoken).assertIsDisplayed()
        composeRule.runOnIdle { visible = false }
        composeRule.onNodeWithContentDescription(spoken).assertDoesNotExist()
        composeRule.runOnIdle { visible = true }
        composeRule.onNodeWithContentDescription(spoken).assertIsDisplayed()
        assertEquals(ArGuidanceCue.INITIALIZING, guidance.cue)
        assertEquals(ArTrackingHint.NONE, guidance.hint)
    }

    @Test
    fun internalErrorDuringStartup_light() = assertErrorCard(
        PlacementPhase.INITIALIZING,
        TrackingFailureReason.BAD_STATE,
        "AR error. Restart the AR view. AR stopped working",
    )

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun internalErrorAfterTracking_dark() = assertErrorCard(
        PlacementPhase.TRACKING_LOST,
        TrackingFailureReason.BAD_STATE,
        "AR error. Restart the AR view. AR stopped working",
    )

    @Test
    fun cameraBusyAfterTracking_light() = assertErrorCard(
        PlacementPhase.TRACKING_LOST,
        TrackingFailureReason.CAMERA_UNAVAILABLE,
        "Camera busy. Close the other camera app. Another app is using the camera",
    )

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun cameraBusyDuringStartup_dark() = assertErrorCard(
        PlacementPhase.INITIALIZING,
        TrackingFailureReason.CAMERA_UNAVAILABLE,
        "Camera busy. Close the other camera app. Another app is using the camera",
    )

    private fun assertErrorCard(phase: PlacementPhase, reason: TrackingFailureReason, spoken: String) {
        val guidance = ArGuidanceState().apply {
            update(phase, 0L, reason, 0L)
            update(phase, 0L, reason, ArGuidanceState.HINT_STABLE_MS)
        }
        // The illustration loops while tracking is limited. Advance just the entry animation.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                // The built-in placement wrappers pass an explicit (possibly masked) cue.
                ARCoachingOverlay(guidance = guidance, cue = guidance.cue)
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.onNodeWithContentDescription(spoken)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }
}
