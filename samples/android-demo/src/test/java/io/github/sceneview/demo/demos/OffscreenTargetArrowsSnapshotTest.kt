package io.github.sceneview.demo.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import kotlin.math.PI

/**
 * Roborazzi snapshot tests for [OffscreenTargetArrows] — `ar-orbital`'s off-screen
 * direction indicator ([#3304](https://github.com/sceneview/sceneview/issues/3304)).
 *
 * The defect these goldens pin is not a layout bug, it is a **silhouette** bug, and no
 * assertion can catch it: the previous glyph drew a translucent disc of radius 25.3 dp
 * centred on an arrow whose furthest point was 22 dp away, so the halo was strictly
 * larger than the arrow in every direction and the indicator read as a dot with no
 * direction. A picture is the only check that would have shown that, hence this file.
 *
 * The backdrop is a white → black vertical ramp rather than a camera still. It is
 * deterministic across hosts (no JPEG decode in the golden), and it is the harder test:
 * every arrow has to stay readable somewhere on the full luminance range the camera feed
 * can hand the overlay, top row to bottom row, in a single image.
 *
 * The two goldens are expected to be **identical**, and that is the assertion: per
 * `DESIGN.md`'s AR coaching overlay section the overlay's colours do not flip with the
 * app theme, because what they are read against is an arbitrary camera frame and never
 * `surface`. A future change that reaches for `MaterialTheme.colorScheme` here would
 * split them apart and fail this pair.
 *
 * Re-record after a deliberate UI change:
 *   `./gradlew :samples:android-demo:recordRoborazziDebug --tests '*OffscreenTargetArrowsSnapshotTest*'`
 *
 * Verify against goldens (every CI run):
 *   `./gradlew :samples:android-demo:verifyRoborazziDebug`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OffscreenTargetArrowsSnapshotTest {

    /**
     * One target per viewport edge plus two diagonals — the diagonals are the cases
     * that a symmetric glyph loses first, because there the direction cannot be
     * inferred from which edge the indicator sits on.
     */
    private val targets = listOf(
        OffscreenTarget(angleRad = 0f, distanceMeters = 1.4f),
        OffscreenTarget(angleRad = (PI / 2).toFloat(), distanceMeters = 2.7f),
        OffscreenTarget(angleRad = PI.toFloat(), distanceMeters = 0.9f),
        OffscreenTarget(angleRad = (-PI / 2).toFloat(), distanceMeters = 3.5f),
        OffscreenTarget(angleRad = (-PI / 4).toFloat(), distanceMeters = 2.1f),
        OffscreenTarget(angleRad = 2.4f, distanceMeters = 12.8f),
    )

    @Test
    fun arrows_lightTheme() {
        captureRoboImage(
            "src/test/snapshots/orbital_offscreen_arrows_light.png",
            roborazziOptions = HOST_TOLERANT,
        ) {
            ArrowsOverLuminanceRamp(darkTheme = false)
        }
    }

    @Test
    fun arrows_darkTheme() {
        captureRoboImage(
            "src/test/snapshots/orbital_offscreen_arrows_dark.png",
            roborazziOptions = HOST_TOLERANT,
        ) {
            ArrowsOverLuminanceRamp(darkTheme = true)
        }
    }

    @Composable
    private fun ArrowsOverLuminanceRamp(darkTheme: Boolean) {
        SceneViewDemoTheme(darkTheme = darkTheme) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Not a design token and deliberately so: this is a camera-feed
                    // stand-in, i.e. test data, not app chrome.
                    .background(Brush.verticalGradient(listOf(Color.White, Color.Black))),
            ) {
                OffscreenTargetArrows(targets = targets)
            }
        }
    }

    private companion object {
        /**
         * Same tolerance, and the same reasoning, as `DemoListScreenSnapshotTest`:
         * goldens are recorded on a developer machine, so the Linux CI run has to
         * absorb sub-pixel host drift without absorbing a real change.
         */
        val HOST_TOLERANT = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                changeThreshold = 0f,
                imageComparator = SimpleImageComparator(maxDistance = 0.02f),
            ),
        )
    }
}
