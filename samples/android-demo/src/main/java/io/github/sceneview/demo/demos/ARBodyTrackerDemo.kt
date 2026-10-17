package io.github.sceneview.demo.demos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.body.BodyPose
import io.github.sceneview.ar.body.Joint
import io.github.sceneview.ar.body.SKELETON_BONES
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.DemoBottomOverlayScope
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ArOverlayMeter
import io.github.sceneview.demo.common.CardShell
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.demos.squat.SquatCoachState
import io.github.sceneview.demo.demos.squat.SquatFeedback
import io.github.sceneview.demo.demos.squat.SquatFrame
import io.github.sceneview.demo.demos.squat.SquatPhase
import io.github.sceneview.demo.demos.squat.SquatPosePipeline
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import kotlin.math.roundToInt

/**
 * **Squat Coach** (route `ar-body-tracker`, #1763): prop the phone up, step back, squat —
 * the screen draws your skeleton, counts each rep that goes deep enough, and says "Lower"
 * when it does not.
 *
 * ### Why it used to lag
 * The previous Body Tracker ran the whole pose pipeline inside `onSessionUpdated`, on the
 * thread that draws the camera feed: YUV → NV21 → JPEG → Bitmap, then a blocking
 * `PoseLandmarker.detect` in `RunningMode.IMAGE`. Six times a second the feed froze for the
 * conversion plus the inference. [SquatPosePipeline] now does only a plane copy on that
 * thread and drops the frame when an inference is already running; conversion happens on a
 * worker, inference runs in `RunningMode.LIVE_STREAM` and calls back asynchronously.
 *
 * ### Honest parity note
 * ARCore has **no native body-tracking API**. ARKit's `ARBodyTrackingConfiguration` hands
 * over a world-anchored 3D skeleton; here MediaPipe Pose Landmarker reads the camera image
 * and SceneView maps its landmarks onto the 17 ARKit-parity [Joint]s via
 * [BodyPose.fromMediaPipeLandmarks]. The skeleton is therefore image-space 2D, drawn as a
 * Compose overlay — which is all a rep counter needs: the knee angle is read in the image.
 *
 * ### Model asset
 * `src/main/assets/mediapipe/pose_landmarker_lite.task`. Missing or corrupt, the screen says
 * so in a banner instead of crashing; the camera still runs.
 */
@Composable
fun ARBodyTrackerDemo(onBack: () -> Unit) {
    var arSessionFailed by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)

    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo.
    val arPlaybackDataset = rememberArPlaybackDataset()

    var latest by remember { mutableStateOf(SquatFrame(BodyPose(emptyMap()), SquatCoachState())) }
    val pipeline = remember { SquatPosePipeline(context) { latest = it } }
    DisposableEffect(pipeline) {
        onDispose { pipeline.close() }
    }

    // True after the first ARCore camera frame — used to dismiss ARCameraInitScrim (#2485).
    var cameraReady by remember { mutableStateOf(false) }
    // #3341: non-null once ARCore has ruled this device out; the init scrim reads it so it
    // never covers the SDK's own explanation card.
    var arCoreAvailability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }

    DemoScaffold(
        arSessionFailed = arSessionFailed,
        arOverlaysEnabled = arCoreAvailability == null,
        title = stringResource(R.string.demo_ar_body_tracker_title),
        onBack = onBack,
        onReset = {
            pipeline.resetCount()
            latest = latest.copy(coach = SquatCoachState())
        },
        controls = {
            Text(
                stringResource(R.string.demo_ar_body_tracker_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        // One surface at a time (DESIGN.md, AR Overlay Card): a problem or the "step back"
        // hint while there is nobody to coach, the coach card as soon as there is.
        bottomOverlay = {
            val trackingFailureHint = trackingFailureMessage(trackingFailureReason)
            val coach = latest.coach
            when {
                !pipeline.isAvailable -> DemoStatusBanner(
                    stringResource(R.string.demo_ar_body_tracker_status_no_model),
                    tone = DemoStatusTone.Blocked,
                )
                trackingFailureHint != null -> DemoStatusBanner(
                    trackingFailureHint,
                    tone = when (trackingFailureReason) {
                        TrackingFailureReason.CAMERA_UNAVAILABLE,
                        TrackingFailureReason.BAD_STATE -> DemoStatusTone.Blocked
                        else -> DemoStatusTone.Guidance
                    },
                )
                !cameraReady -> Unit
                coach.phase == SquatPhase.NO_BODY && coach.reps == 0 -> DemoStatusBanner(
                    stringResource(R.string.demo_ar_body_tracker_hint_aim),
                    tone = DemoStatusTone.Guidance,
                )
                else -> SquatCoachCard(coach)
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                onSessionFailure = { arSessionFailed = true },
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                planeRenderer = false,
                sessionConfiguration = { _: Session, config: Config ->
                    config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                },
                onARCoreAvailability = { arCoreAvailability = it },
                onSessionUpdated = { _, frame: Frame ->
                    if (!cameraReady) cameraReady = true
                    if (frame.camera.trackingState == TrackingState.TRACKING) {
                        // A plane copy and a hand-off; returns at once (see SquatPosePipeline).
                        pipeline.onFrame(frame)
                    }
                },
                onTrackingFailureChanged = { reason -> trackingFailureReason = reason },
            )

            SkeletonOverlay(
                pose = latest.pose,
                phase = latest.coach.phase,
                modifier = Modifier.fillMaxSize(),
            )

            ARCameraInitScrim(
                initializing = !cameraReady,
                arCoreAvailability = arCoreAvailability,
            )
        }
    }
}

/**
 * The coach: the rep count, large enough to read from where you squat (`type-display`, as
 * the Rerun record mode does for figures read from a metre away), the one line of coaching,
 * and the depth meter that fills as you go down.
 */
@Composable
internal fun DemoBottomOverlayScope.SquatCoachCard(coach: SquatCoachState) {
    val feedback = when (coach.feedback) {
        SquatFeedback.GO_LOWER -> stringResource(R.string.demo_squat_feedback_lower)
        SquatFeedback.STAND_UP -> stringResource(R.string.demo_squat_feedback_stand_up)
        SquatFeedback.REP_COUNTED -> stringResource(R.string.demo_squat_feedback_rep)
        SquatFeedback.TOO_SHALLOW -> stringResource(R.string.demo_squat_feedback_shallow)
        SquatFeedback.NONE -> stringResource(R.string.demo_squat_feedback_ready)
    }
    val deep = coach.phase == SquatPhase.DEEP_ENOUGH || coach.feedback == SquatFeedback.REP_COUNTED
    val accent = if (deep) SceneViewTokens.ArOverlay.accentSuccess else SceneViewTokens.ArOverlay.accentGuidance
    val filled = (coach.depth * DEPTH_SEGMENTS).roundToInt()
    val kneeLine = coach.kneeAngle?.let {
        stringResource(R.string.demo_squat_knee_angle, it.roundToInt())
    } ?: stringResource(R.string.demo_squat_knee_hidden)

    CardShell(modifier = Modifier, testTag = SQUAT_COACH_CARD_TAG) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(min = SceneViewTokens.Space.x3l),
            ) {
                Text(
                    text = coach.reps.toString(),
                    style = SceneViewTokens.Type.display,
                    color = SceneViewTokens.ArOverlay.onScrim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(
                    text = stringResource(R.string.demo_squat_reps_label),
                    style = SceneViewTokens.Type.caption,
                    color = SceneViewTokens.ArOverlay.onScrimMuted,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
                Text(
                    text = feedback,
                    style = SceneViewTokens.Type.card,
                    color = SceneViewTokens.ArOverlay.onScrim,
                    maxLines = 2,
                )
                Text(
                    text = kneeLine,
                    style = SceneViewTokens.Type.caption,
                    color = SceneViewTokens.ArOverlay.onScrimMuted,
                )
                ArOverlayMeter(
                    filled = filled,
                    segments = DEPTH_SEGMENTS,
                    accent = accent,
                    description = stringResource(
                        R.string.demo_squat_depth_description,
                        (coach.depth * PERCENT).roundToInt(),
                    ),
                )
            }
        }
    }
}

/**
 * The live skeleton, drawn to look like part of the picture rather than a debug overlay:
 * round-capped limbs over a soft glow, no face points, small light joints. The legs carry
 * the coaching colour — `warning` amber on the way down, `success` green once deep enough —
 * so the depth reads on the body itself, not only on the card.
 *
 * Every colour is an AR overlay token (dark-scheme accents in both themes, DESIGN.md): the
 * ground is a camera frame, not a themed surface.
 */
@Composable
internal fun SkeletonOverlay(pose: BodyPose, phase: SquatPhase, modifier: Modifier) {
    val bodyColor = SceneViewTokens.ArOverlay.accentProgress
    val legColor = when (phase) {
        SquatPhase.DEEP_ENOUGH -> SceneViewTokens.ArOverlay.accentSuccess
        SquatPhase.GOING_DOWN -> SceneViewTokens.ArOverlay.accentGuidance
        SquatPhase.STANDING, SquatPhase.NO_BODY -> bodyColor
    }
    val jointColor = SceneViewTokens.ArOverlay.onScrim
    Canvas(modifier = modifier) {
        if (!pose.isTracked) return@Canvas
        val w = size.width
        val h = size.height
        val bone = SceneViewTokens.Space.sm.toPx()
        val glow = SceneViewTokens.Space.lg.toPx()
        val joint = SceneViewTokens.Space.xs.toPx()

        fun point(j: Joint): Offset? = pose[j]?.let { Offset(it.x * w, it.y * h) }
        fun colorOf(a: Joint, b: Joint): Color = if (a in LEG_JOINTS && b in LEG_JOINTS) legColor else bodyColor

        val segments = SKELETON_BONES.mapNotNull { (a, b) ->
            val pa = point(a) ?: return@mapNotNull null
            val pb = point(b) ?: return@mapNotNull null
            Triple(pa, pb, colorOf(a, b))
        }
        // Glow pass, then the limbs, then the joints — three layers, one stroke each.
        segments.forEach { (pa, pb, color) ->
            drawLine(color.copy(alpha = GLOW_ALPHA), pa, pb, strokeWidth = glow, cap = StrokeCap.Round)
        }
        segments.forEach { (pa, pb, color) ->
            drawLine(color, pa, pb, strokeWidth = bone, cap = StrokeCap.Round)
        }
        DRAWN_JOINTS.forEach { j ->
            point(j)?.let { drawCircle(jointColor, radius = joint, center = it) }
        }
    }
}

/** Test tag of the coach card, for UI tests. */
internal const val SQUAT_COACH_CARD_TAG = "squat-coach-card"

/** Segments of the depth meter — one per 20 % of the way from standing to depth. */
private const val DEPTH_SEGMENTS = 5

private const val PERCENT = 100

/** Opacity of the glow under each limb. */
private const val GLOW_ALPHA = 0.28f

private val LEG_JOINTS = setOf(
    Joint.LEFT_HIP, Joint.LEFT_KNEE, Joint.LEFT_FOOT,
    Joint.RIGHT_HIP, Joint.RIGHT_KNEE, Joint.RIGHT_FOOT,
)

/** Joints that get a dot: the articulations. The head and the synthesised spine stay lines. */
private val DRAWN_JOINTS = Joint.entries.filter { it != Joint.HEAD && it != Joint.SPINE && it != Joint.ROOT }
