package io.github.sceneview.demo.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.sceneview.ar.body.BodyLandmark
import io.github.sceneview.ar.body.BodyPose
import io.github.sceneview.ar.body.Joint
import io.github.sceneview.demo.DemoBottomOverlayScope
import io.github.sceneview.demo.demos.squat.SquatCoachState
import io.github.sceneview.demo.demos.squat.SquatFeedback
import io.github.sceneview.demo.demos.squat.SquatPhase
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi goldens for the Squat Coach HUD (`ar-body-tracker`): the skeleton overlay and
 * the coach card, mid-squat at depth, over a white → black ramp standing in for the camera
 * feed (the same stand-in as `OffscreenTargetArrowsSnapshotTest`).
 *
 * The emulator has no AR camera, so this is the only place the live HUD can be looked at
 * off a phone. Light and dark are expected to match apart from the card's scrim opacity:
 * the overlay reads against a camera frame, never `surface` (DESIGN.md, AR overlay).
 *
 * Re-record: `./gradlew :samples:android-demo:recordRoborazziDebug --tests '*SquatCoachSnapshotTest*'`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SquatCoachSnapshotTest {

    /** A side-on squat at the bottom: thighs about level, shins forward, torso leaning. */
    private val squatPose = BodyPose(
        listOf(
            Joint.HEAD to (0.40f to 0.30f),
            Joint.NECK to (0.42f to 0.36f),
            Joint.LEFT_SHOULDER to (0.40f to 0.37f),
            Joint.RIGHT_SHOULDER to (0.44f to 0.37f),
            Joint.LEFT_ELBOW to (0.52f to 0.42f),
            Joint.RIGHT_ELBOW to (0.55f to 0.42f),
            Joint.LEFT_HAND to (0.64f to 0.40f),
            Joint.RIGHT_HAND to (0.66f to 0.40f),
            Joint.SPINE to (0.46f to 0.44f),
            Joint.ROOT to (0.50f to 0.52f),
            Joint.LEFT_HIP to (0.49f to 0.52f),
            Joint.RIGHT_HIP to (0.51f to 0.52f),
            Joint.LEFT_KNEE to (0.65f to 0.54f),
            Joint.RIGHT_KNEE to (0.67f to 0.54f),
            Joint.LEFT_FOOT to (0.58f to 0.68f),
            Joint.RIGHT_FOOT to (0.60f to 0.68f),
        ).associate { (joint, xy) -> joint to BodyLandmark(joint, xy.first, xy.second, 0f, 1f) },
    )

    private val atDepth = SquatCoachState(
        reps = 4,
        phase = SquatPhase.DEEP_ENOUGH,
        feedback = SquatFeedback.STAND_UP,
        kneeAngle = 92f,
        depth = 1f,
    )

    @Test
    fun squatCoach_lightTheme() {
        captureRoboImage("src/test/snapshots/squat_coach_hud_light.png", roborazziOptions = HOST_TOLERANT) {
            Hud(darkTheme = false)
        }
    }

    @Test
    fun squatCoach_darkTheme() {
        captureRoboImage("src/test/snapshots/squat_coach_hud_dark.png", roborazziOptions = HOST_TOLERANT) {
            Hud(darkTheme = true)
        }
    }

    @Composable
    private fun Hud(darkTheme: Boolean) {
        SceneViewDemoTheme(darkTheme = darkTheme) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Camera-feed stand-in: test data, not app chrome.
                    .background(Brush.verticalGradient(listOf(Color.White, Color.Black))),
            ) {
                SkeletonOverlay(squatPose, atDepth.phase, Modifier.fillMaxSize())
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = SceneViewTokens.Space.lg),
                ) {
                    DemoBottomOverlayScope(this, 0.dp).SquatCoachCard(atDepth)
                }
            }
        }
    }

    private companion object {
        val HOST_TOLERANT = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(
                changeThreshold = 0f,
                imageComparator = SimpleImageComparator(maxDistance = 0.02f),
            ),
        )
    }
}
