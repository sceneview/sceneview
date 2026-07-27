package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.node.ContactShadowContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi snapshot tests for [ContactShadowControls] and [WallShadowBeat].
 *
 * Both composables were extracted `internal` precisely so this could exist: they are pure
 * Compose (no Filament, no SceneView, no ARCore), so a layout regression fails fast in plain
 * JVM without an emulator. Pattern from issue
 * [#880](https://github.com/sceneview/sceneview/issues/880); the demo this covers is #2740.
 *
 * The [WallShadowBeat] cases carry the load the settings panel cannot: the beat's whole
 * argument is that the preset in force is *named on screen*, so `Floor`-on-a-wall and
 * `Wall`-on-a-wall must produce visibly different captions. Two goldens make a silent
 * regression to a single shared caption impossible to merge.
 *
 * Generate the goldens (run once after a deliberate UI change):
 *   `./gradlew :samples:android-demo:recordRoborazziDebug --tests ContactShadowControlsSnapshotTest`
 *
 * Verify against goldens (every CI run):
 *   `./gradlew :samples:android-demo:verifyRoborazziDebug`
 *
 * Goldens land in `src/test/snapshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactShadowControlsSnapshotTest {

    @Test
    fun controls_default_state_lightMode() {
        captureControls("src/test/snapshots/contact_shadow_controls_default_light.png") {
            ContactShadowControls(
                shadowsEnabled = true, onShadowsEnabledChange = {},
                motionEnabled = true, onMotionEnabledChange = {},
                intensityFactor = 1f, onIntensityFactorChange = {},
            )
        }
    }

    @Test
    fun controls_default_state_darkMode() {
        captureControls(
            "src/test/snapshots/contact_shadow_controls_default_dark.png",
            darkTheme = true,
        ) {
            ContactShadowControls(
                shadowsEnabled = true, onShadowsEnabledChange = {},
                motionEnabled = true, onMotionEnabledChange = {},
                intensityFactor = 1f, onIntensityFactorChange = {},
            )
        }
    }

    /**
     * Everything off and the intensity slider at zero — the state in which no shadow is drawn
     * at all. Guards the panel that a viewer reaches for when the on-screen labels say
     * "Shadows off".
     */
    @Test
    fun controls_all_off() {
        captureControls("src/test/snapshots/contact_shadow_controls_all_off.png") {
            ContactShadowControls(
                shadowsEnabled = false, onShadowsEnabledChange = {},
                motionEnabled = false, onMotionEnabledChange = {},
                intensityFactor = 0f, onIntensityFactorChange = {},
            )
        }
    }

    /** The preset the wall actually wants: a faint, wide halo. */
    @Test
    fun wall_beat_wall_preset() {
        captureControls("src/test/snapshots/contact_shadow_wall_beat_wall.png") {
            WallShadowBeat(
                wallContext = ContactShadowContext.Wall,
                onWallContextChange = {},
            )
        }
    }

    /** The instructive mistake — `Floor` on a wall. Its caption must differ from `Wall`'s. */
    @Test
    fun wall_beat_floor_preset() {
        captureControls("src/test/snapshots/contact_shadow_wall_beat_floor.png") {
            WallShadowBeat(
                wallContext = ContactShadowContext.Floor,
                onWallContextChange = {},
            )
        }
    }

    private fun captureControls(
        path: String,
        darkTheme: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        captureRoboImage(path, roborazziOptions = CROSS_PLATFORM_TOLERANT) {
            SceneViewDemoTheme(darkTheme = darkTheme) {
                Surface {
                    Column(modifier = Modifier.padding(16.dp)) { content() }
                }
            }
        }
    }

    private companion object {
        /**
         * Per-channel tolerance one notch above Roborazzi's default, because goldens recorded
         * on macOS are verified on the CI's Linux runners and the two round some composited
         * colours differently.
         *
         * The gap is *measured*, not assumed. Comparing each macOS golden against the runner's
         * `_actual.png` from run 30284733229: the wall-beat pair differs on 66 % of pixels and
         * the all-off panel on 0.18 %, but in every case **the largest per-channel difference
         * is 2/255** — i.e. 0.0078 normalised, a hair over the 0.007 default, and invisible
         * side by side (both renderings were inspected). A flat 2/255 offset across a
         * background is rounding, not a regression.
         *
         * Raising `maxDistance` rather than a change-*percentage* threshold is the point: a
         * percentage large enough to absorb 66 % of the frame would wave through anything,
         * whereas this keeps every pixel checked and only forgives sub-perceptual channel
         * drift. A real regression here — a caption swapped, a chip unselected, a control gone
         * — moves whole glyphs, which is orders of magnitude past 2/255.
         */
        private val CROSS_PLATFORM_TOLERANT = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                imageComparator = SimpleImageComparator(maxDistance = 0.01f),
            ),
        )
    }
}
