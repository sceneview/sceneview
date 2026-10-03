package io.github.sceneview.demo.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.sceneview.demo.demos.internal.GeometryDemoState
import io.github.sceneview.demo.demos.internal.GeometryShape
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi snapshot tests for the Compose half of [GeometryDemo]: the settings sheet content
 * ([GeometryDemoControls]) and the shape chips that float over the scene ([GeometryShapeChips]).
 *
 * Both are screenshot-tested in pure JVM (Robolectric + Roborazzi NATIVE graphics mode) so a
 * layout regression fails at commit time without an emulator. Pattern from issue
 * [#880](https://github.com/sceneview/sceneview/issues/880).
 *
 * Generate the goldens (run once after a deliberate UI change):
 *   `./gradlew :samples:android-demo:recordRoborazziDebug --tests GeometryDemoControlsSnapshotTest`
 *
 * Verify against goldens (every CI run):
 *   `./gradlew :samples:android-demo:verifyRoborazziDebug`
 *
 * Goldens land in `src/test/snapshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GeometryDemoControlsSnapshotTest {

    @Composable
    private fun Controls(
        darkTheme: Boolean = false,
        metallic: Float = GeometryDemoState.DEFAULT_METALLIC,
        roughness: Float = GeometryDemoState.DEFAULT_ROUGHNESS,
    ) {
        SceneViewDemoTheme(darkTheme = darkTheme) {
            Surface {
                Box(modifier = Modifier.padding(SceneViewTokens.Space.md)) {
                    GeometryDemoControls(
                        metallic = metallic, onMetallicChange = {},
                        roughness = roughness, onRoughnessChange = {},
                    )
                }
            }
        }
    }

    /** The chips as they stand on screen: over the dark stage, whatever the theme. */
    @Composable
    private fun Chips(visibleShapes: Set<GeometryShape>) {
        SceneViewDemoTheme(darkTheme = true) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SceneViewTokens.Stage.background)
                    .padding(SceneViewTokens.Space.md),
                contentAlignment = Alignment.Center,
            ) {
                GeometryShapeChips(visibleShapes = visibleShapes, onToggle = {})
            }
        }
    }

    @Test
    fun controls_default_state_lightMode() {
        captureRoboImage("src/test/snapshots/geometry_controls_default_light.png") { Controls() }
    }

    @Test
    fun controls_default_state_darkMode() {
        captureRoboImage("src/test/snapshots/geometry_controls_default_dark.png") {
            Controls(darkTheme = true)
        }
    }

    @Test
    fun controls_polished_mirror() {
        // metallic=1, roughness=0 = the "polished mirror" extreme. Pinned so a future
        // slider-range change (e.g. swapping the upper bound from 1f to 100f) would
        // produce a visibly different slider thumb position and fail this snapshot.
        captureRoboImage("src/test/snapshots/geometry_controls_mirror.png") {
            Controls(metallic = 1f, roughness = 0f)
        }
    }

    @Test
    fun controls_chalky_matte() {
        // metallic=0, roughness=1 = the "chalky matte" extreme.
        captureRoboImage("src/test/snapshots/geometry_controls_matte.png") {
            Controls(metallic = 0f, roughness = 1f)
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w891dp-h411dp-xhdpi")
    fun controls_landscape_sliders_side_by_side() {
        // A landscape sheet peeks at about a third of a short window: stacked, the second
        // slider would start below the fold.
        captureRoboImage("src/test/snapshots/geometry_controls_landscape.png") {
            Controls(darkTheme = true)
        }
    }

    @Test
    fun shapeChips_all_shown() {
        captureRoboImage("src/test/snapshots/geometry_shape_chips_all.png") {
            Chips(GeometryDemoState.ALL_SHAPES)
        }
    }

    @Test
    fun shapeChips_only_cube_shown() {
        // Pins the hidden look — glass, white label — next to the one shown chip.
        captureRoboImage("src/test/snapshots/geometry_shape_chips_only_cube.png") {
            Chips(setOf(GeometryShape.Cube))
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w891dp-h411dp-xhdpi")
    fun shapeChips_landscape_single_row() {
        captureRoboImage("src/test/snapshots/geometry_shape_chips_landscape.png") {
            Chips(GeometryDemoState.ALL_SHAPES - GeometryShape.Torus)
        }
    }

    @Test
    fun fullDemo_in_preview_mode_shows_placeholder() {
        // Snapshot the FULL GeometryDemo composable in inspection mode. The demo body
        // short-circuits to DemoPreviewPlaceholder before any rememberEngine() call,
        // so this works in pure JVM with no Filament JNI. Roborazzi by default does
        // NOT set LocalInspectionMode (Robolectric runs the actual app code), so we
        // force it here via CompositionLocalProvider — same value AS Preview pane uses.
        // Catches regressions in:
        //   - the inspection-mode short-circuit (e.g. someone moves rememberEngine
        //     above the LocalInspectionMode check, breaking @Preview)
        //   - the placeholder layout / labels / colours
        //   - the DemoScaffold TopAppBar styling
        captureRoboImage("src/test/snapshots/geometry_demo_preview_placeholder.png") {
            SceneViewDemoTheme(darkTheme = false) {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    GeometryDemo(onBack = {})
                }
            }
        }
    }
}
