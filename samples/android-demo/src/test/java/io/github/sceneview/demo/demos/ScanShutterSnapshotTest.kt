package io.github.sceneview.demo.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of Room Scan's Stop wait ([ScanShutter]): the line under the camera view, and the bar
 * it gains while a depth scan fuses its room again on ARCore's corrected poses.
 *
 * The bar is pictured part-way, where it is still: past the fusion it runs unmeasured, and a
 * frozen frame of that would pin a moment of an animation. The backdrop is a white to black ramp
 * standing in for the camera, so the pill is read against the whole range it can meet. The light
 * and dark pictures are expected to be the same: what sits on the camera does not flip with the
 * app theme (`DESIGN.md`, AR overlays).
 *
 * Re-record after a deliberate UI change:
 *   `./gradlew :samples:android-demo:recordRoborazziDebug --tests '*ScanShutterSnapshotTest*'`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h220dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScanShutterSnapshotTest {

    @Test fun fusing_light() = capture("fusing-light", dark = false, progress = { PART_WAY })

    @Test fun fusing_dark() = capture("fusing-dark", dark = true, progress = { PART_WAY })

    /** A scan with no depth has nothing to fuse: the wait is the line alone, as before. */
    @Test fun packingWithoutDepth_dark() = capture("packing-dark", dark = true, progress = null)

    private fun capture(name: String, dark: Boolean, progress: (() -> Float)?) {
        captureRoboImage("src/test/snapshots/room_scan_stop_$name.png", roborazziOptions = HOST_TOLERANT) {
            OverCameraRamp(dark) {
                ScanShutter(
                    recording = true,
                    finishing = true,
                    startEnabled = true,
                    onStart = {},
                    onStop = {},
                    finishProgress = progress,
                )
            }
        }
    }

    @Composable
    private fun OverCameraRamp(dark: Boolean, content: @Composable () -> Unit) {
        SceneViewDemoTheme(darkTheme = dark) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // A camera-feed stand-in, test data and not app chrome: no design token.
                    .background(Brush.verticalGradient(listOf(Color.White, Color.Black))),
                contentAlignment = Alignment.Center,
            ) {
                content()
            }
        }
    }

    private companion object {
        const val PART_WAY = 0.4f

        /** Goldens come from a developer machine: the Linux CI run absorbs sub-pixel drift. */
        val HOST_TOLERANT = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                imageComparator = SimpleImageComparator(maxDistance = 0.03f),
            ),
        )
    }
}
