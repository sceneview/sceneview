package io.github.sceneview.demo.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.MeasureMode
import io.github.sceneview.demo.demos.internal.MeasureSnap
import io.github.sceneview.demo.demos.internal.MeasureSnapKind
import io.github.sceneview.demo.demos.internal.MeasureUnitSystem
import io.github.sceneview.demo.demos.internal.formatMeasureArea
import io.github.sceneview.demo.demos.internal.formatMeasureLength
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Position
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Roborazzi goldens for the 2D layer of the rebuilt `ar-measure` demo
 * ([#3509](https://github.com/sceneview/sceneview/issues/3509)): the readout card, the
 * snapping reticle with its lock chip, and the Distance · Wall · Room mode pill, in each
 * mode's most telling state, light and dark.
 *
 * The emulator cannot start a live ARCore session (arm64 AVD, no camera id 0 — #2754), so
 * this is the only screen proof of the overlay short of a phone. The 3D layer (segments,
 * labels, rising walls) needs a real device. The backdrop is a ramp, not a camera still:
 * deterministic across hosts and it spans the luminance a camera feed can put behind the
 * overlay.
 *
 * Re-record after a deliberate UI change:
 *   `./gradlew :samples:android-demo:recordRoborazziDebug --tests '*MeasureOverlaySnapshotTest*'`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeasureOverlaySnapshotTest {

    private val locale = Locale.US
    private fun len(m: Float) = formatMeasureLength(m, MeasureUnitSystem.Metric, locale)
    private fun area(m: Float) = formatMeasureArea(m, MeasureUnitSystem.Metric, locale)

    @Test fun distance_light() = capture("measure_distance_light", dark = false) { Distance() }

    @Test fun distance_dark() = capture("measure_distance_dark", dark = true) { Distance() }

    @Test fun wall_light() = capture("measure_wall_light", dark = false) { Wall() }

    @Test fun wall_dark() = capture("measure_wall_dark", dark = true) { Wall() }

    @Test fun room_light() = capture("measure_room_light", dark = false) { Room() }

    @Test fun room_dark() = capture("measure_room_dark", dark = true) { Room() }

    /** Second segment of a chain, reticle locked on a plane corner. */
    @Composable
    private fun Distance() = Overlay(
        mode = MeasureMode.Distance,
        readout = MeasureReadout(
            value = len(0.84f),
            label = stringResource(R.string.demo_ar_measure_label_live),
            details = stringResource(R.string.demo_ar_measure_total, len(2.06f)),
        ),
        aim = MeasureAimView(MeasureSnap(Position(), MeasureSnapKind.Corner), false, MeasureHitSource.Plane),
        raising = false,
    )

    /** Wall rising with the aim, before Set height. */
    @Composable
    private fun Wall() = Overlay(
        mode = MeasureMode.Wall,
        readout = MeasureReadout(
            value = len(2.48f),
            label = stringResource(R.string.demo_ar_measure_label_height_live),
            details = listOf(
                stringResource(R.string.demo_ar_measure_width, len(3.2f)),
                stringResource(R.string.demo_ar_measure_area, area(3.2f * 2.48f)),
            ).joinToString(" · "),
        ),
        aim = null,
        raising = true,
    )

    /** A closed room, ceiling locked: every number of the room on one card. */
    @Composable
    private fun Room() = Overlay(
        mode = MeasureMode.Room,
        readout = MeasureReadout(
            value = len(2.5f),
            label = stringResource(R.string.demo_ar_measure_label_ceiling),
            details = listOf(
                stringResource(R.string.demo_ar_measure_floor, area(14.2f)),
                stringResource(R.string.demo_ar_measure_perimeter, len(15.3f)),
                stringResource(R.string.demo_ar_measure_walls, area(38.25f)),
            ).joinToString(" · "),
        ),
        aim = MeasureAimView(MeasureSnap(Position(), MeasureSnapKind.Square), false, MeasureHitSource.Floor),
        raising = false,
    )

    @Composable
    private fun Overlay(mode: MeasureMode, readout: MeasureReadout, aim: MeasureAimView?, raising: Boolean) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.White, Color(0xFF6B6B6B), Color.Black))),
        ) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = SceneViewTokens.Space.x4l)) {
                MeasureReadoutCard(readout)
            }
            MeasureReticle(aim = aim, raising = raising, modifier = Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = SceneViewTokens.Space.x4l)) {
                MeasureModePill(mode = mode, onSelect = {})
            }
        }
    }

    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
        captureRoboImage("src/test/snapshots/$name.png", roborazziOptions = HOST_TOLERANT) {
            SceneViewDemoTheme(darkTheme = dark) { content() }
        }
    }

    private companion object {
        val HOST_TOLERANT = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                imageComparator = SimpleImageComparator(maxDistance = 0.007f),
            ),
        )
    }
}
