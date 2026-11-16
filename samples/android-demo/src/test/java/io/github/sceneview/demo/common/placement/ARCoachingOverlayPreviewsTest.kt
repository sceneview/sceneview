package io.github.sceneview.demo.common.placement

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Recovery samples must render the real SDK messages through its public guidance API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ARCoachingOverlayPreviewsTest {

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
    fun badStateSample_showsRecoveryAdvice() = assertSample(
        "bad-state",
        "AR error. Restart the AR view. AR stopped working",
    )

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun cameraUnavailableSample_showsRecoveryAdvice() = assertSample(
        "camera-unavailable",
        "Camera busy. Close the other camera app. Another app is using the camera",
    )

    private fun assertSample(label: String, spoken: String) {
        val sample = ARCoachingSamples.single { it.label == label }
        // Keep the looping illustration from advancing while asserting the card semantics.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { ARCoachingFrame(sample) }
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.onNodeWithContentDescription(spoken)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }
}
