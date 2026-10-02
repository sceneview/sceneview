package io.github.sceneview.demo.common.placement

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the SDK's AR coaching card (#4038) in every state a session meets — getting
 * ready, the phone sweep, the found beat, the three reasons tracking struggles for — light and
 * dark, on a phone-sized frame.
 *
 * Rendered in inspection mode, so the illustration takes its resting pose (the phone mid
 * sweep, the chevrons drawn) instead of a frame of an animation the test clock would freeze
 * anywhere. The motion itself is proved by the emulator recording of
 * `ARCoachingPreviewActivity`; what the card says in a real session needs a device.
 *
 * Re-record after a deliberate UI change:
 *   `./gradlew :samples:android-demo:recordRoborazziDebug --tests '*ARCoachingOverlaySnapshotTest*'`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ARCoachingOverlaySnapshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun initializing_light() = capture("initializing", dark = false)

    @Test fun initializingTooDark_dark() = capture("initializing-too-dark", dark = true)

    @Test fun scan_light() = capture("scan", dark = false)

    @Test fun scan_dark() = capture("scan", dark = true)

    @Test fun scanWall_dark() = capture("scan-wall", dark = true)

    @Test fun scanLingering_light() = capture("scan-lingering", dark = false)

    @Test fun surfaceFound_light() = capture("surface-found", dark = false)

    @Test fun surfaceFound_dark() = capture("surface-found", dark = true)

    @Test fun limitedTooFast_light() = capture("limited-too-fast", dark = false)

    @Test fun limitedTooFast_dark() = capture("limited-too-fast", dark = true)

    @Test fun limitedLowDetail_dark() = capture("limited-low-detail", dark = true)

    @Test fun relocalizing_light() = capture("relocalizing", dark = false)

    @Test fun relocalizing_dark() = capture("relocalizing", dark = true)

    private fun capture(label: String, dark: Boolean) {
        val sample = ARCoachingSamples.first { it.label == label }
        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                SceneViewDemoTheme(darkTheme = dark) {
                    ARCoachingFrame(sample)
                }
            }
        }
        composeRule.waitForIdle()
        // The card fades in; 1 s is past every enter transition.
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(
            "src/test/snapshots/ar_coaching_$label-${if (dark) "dark" else "light"}.png",
            roborazziOptions = TOLERANT,
        )
    }

    private companion object {
        /** Antialiasing differs a little between macOS and Linux renderers. */
        val TOLERANT = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                imageComparator = SimpleImageComparator(maxDistance = 0.03f),
            ),
        )
    }
}
