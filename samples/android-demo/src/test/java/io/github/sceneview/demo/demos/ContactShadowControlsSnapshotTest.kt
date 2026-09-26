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
 * Roborazzi snapshot tests for [ContactShadowControls] and [ContactShadowStageBar].
 *
 * Both composables were extracted `internal` precisely so this could exist: they are pure
 * Compose (no Filament, no SceneView, no ARCore), so a layout regression fails fast in plain
 * JVM without an emulator. Pattern from issue
 * [#880](https://github.com/sceneview/sceneview/issues/880); the demo this covers is #2740.
 *
 * The [ContactShadowStageBar] cases carry the load the settings panel cannot: the bar's whole
 * argument is that the staged preset is *named on screen* in plain words, so `Floor` and
 * `Wall` must produce visibly different captions. Two goldens make a silent regression to a
 * single shared caption impossible to merge. A third pins the held state of the compare pill,
 * whose label is the only feedback that the gesture registered (#3498).
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

/** The default staging — a chair on the floor. */
    @Test
    fun stage_bar_floor_preset() {
        captureControls("src/test/snapshots/contact_shadow_stage_bar_floor.png") {
            ContactShadowStageBar(
                stage = ContactShadowContext.Floor,
                onStageChange = {},
                comparing = false,
                onComparingChange = {},
            )
        }
    }

    /** The wall staging. Its caption must differ from the floor's. */
    @Test
    fun stage_bar_wall_preset() {
        captureControls("src/test/snapshots/contact_shadow_stage_bar_wall.png") {
            ContactShadowStageBar(
                stage = ContactShadowContext.Wall,
                onStageChange = {},
                comparing = false,
                onComparingChange = {},
            )
        }
    }

    /**
     * The compare pill while held. The label swap is the only feedback the gesture registered,
     * so it gets a golden of its own rather than riding on the default-state capture.
     */
    @Test
    fun stage_bar_comparing() {
        captureControls("src/test/snapshots/contact_shadow_stage_bar_comparing.png") {
            ContactShadowStageBar(
                stage = ContactShadowContext.Floor,
                onStageChange = {},
                comparing = true,
                onComparingChange = {},
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
         * Colour-distance tolerance above Roborazzi's default, because goldens recorded on
         * macOS are verified on the CI's Linux runners and the two round some composited
         * colours differently.
         *
         * The gap is *measured*, not assumed. Comparing each macOS golden against the runner's
         * own `_actual.png` (runs 30284733229 / 30288149200): the wall-beat pair differs on
         * 66 % of pixels and the all-off panel on 0.18 %, the largest single-channel delta is
         * **2/255**, and — the number that actually governs the verdict, since
         * `SimpleImageComparator` thresholds the *euclidean* RGBA distance rather than a
         * per-channel one — the largest distance is **0.01176** normalised. That is why a
         * first attempt at 0.01 still failed: it sat just under the real figure. 0.02 clears
         * the measurement with room to spare while staying invisible side by side (both
         * renderings were inspected). A ~2/255 offset spread over a background is rounding,
         * not a regression.
         *
         * Raising `maxDistance` rather than a change-*percentage* threshold is the point: a
         * percentage large enough to absorb 66 % of the frame would wave through anything,
         * whereas this keeps every pixel compared and forgives only sub-perceptual drift. A
         * real regression here — a caption swapped, a chip unselected, a control gone — moves
         * whole glyphs, i.e. distances near 1.0, some fifty times this bound.
         *
         * Calibrated by reproducing the mismatch locally: the runner's `_actual.png` files were
         * dropped in as goldens and `verifyRoborazziDebug` was run on macOS, which reproduces
         * exactly the cross-OS delta CI sees, instead of guessing at 20 minutes per CI round.
         */
        private val CROSS_PLATFORM_TOLERANT = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                imageComparator = SimpleImageComparator(maxDistance = 0.02f),
            ),
        )
    }
}
