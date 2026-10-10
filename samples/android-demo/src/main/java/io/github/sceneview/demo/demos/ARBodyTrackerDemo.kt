package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.cameraImage
import io.github.sceneview.ar.body.BodyPose
import io.github.sceneview.ar.body.Joint
import io.github.sceneview.ar.body.SKELETON_BONES
import io.github.sceneview.ar.camera.CameraImageSize
import io.github.sceneview.ar.camera.CameraImageToViewMapping
import io.github.sceneview.ar.camera.cameraImageToViewMapping
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.CameraImageRotation
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.demos.internal.CameraMappingProbe
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import java.io.ByteArrayOutputStream

/**
 * AR body-tracking demo — a live 2D skeleton overlay driven by MediaPipe Pose Landmarker
 * (#1763). Tier-1 parity target: ARKit `ARBodyTrackingConfiguration` + `BodyTrackedEntity`.
 *
 * ### Honest parity note
 *
 * ARCore has **no native body-tracking API**. Where ARKit hands you a world-anchored,
 * 6-DoF rigged skeleton, this demo instead feeds the AR CPU camera image to Google's
 * on-device [PoseLandmarker] and projects the resulting **image-space** landmarks onto
 * SceneView's 17 ARKit-parity [Joint]s via [BodyPose.fromMediaPipeLandmarks].
 *
 * The output is therefore a 2D screen-space skeleton, not a world-anchored 3D rig — see the
 * extended caveat on [Joint]. That is genuinely useful for fitness, gesture and AR-filter
 * use cases, and the [Joint] naming matches Apple so cross-platform app code reads the same,
 * but it is **not** a drop-in replacement for `BodyTrackedEntity`. The demo draws the
 * skeleton as a Compose overlay on top of the AR camera feed rather than welding 3D models
 * to limbs, because MediaPipe landmarks cannot reliably world-anchor a 3D model.
 *
 * ### Pipeline
 *  1. Each AR frame, pull the CPU camera image via [io.github.sceneview.ar.arcore.cameraImage].
 *  2. Throttle to ~6 fps (the landmarker costs ~30–60 ms/frame) and convert the YUV image to
 *     an ARGB [Bitmap].
 *  3. Run [PoseLandmarker.detect] (`RunningMode.IMAGE`) with an [ImageProcessingOptions]
 *     rotation hint (#3266 — see below), adapt the result into [BodyPose.RawLandmark]s and
 *     build a [BodyPose].
 *  4. Draw the [SKELETON_BONES] topology over the viewport with a Compose [Canvas].
 *
 * **Rotation (#3266).** [io.github.sceneview.ar.arcore.cameraImage] hands back the CPU image in
 * ARCore's raw sensor orientation — landscape, regardless of the device's current display
 * orientation (see that accessor's kdoc: "ML Kit: `InputImage.fromMediaImage(image,
 * rotationDegrees)`", the same caveat applies here). [toBitmap] does a byte-for-byte YUV→ARGB
 * conversion with no rotation applied, so on a portrait phone the bitmap handed to the
 * landmarker showed a person lying sideways. MediaPipe's pose model is not rotation-invariant —
 * fed a 90°-off frame it almost never found a body, which is why the demo "did nothing" no
 * matter how a person stood in front of the camera. The fix passes the same
 * [io.github.sceneview.demo.common.CameraImageRotation] used by `ar-ml-object-label`'s ML
 * Kit pipeline as an [ImageProcessingOptions] rotation hint to [PoseLandmarker.detect] — that
 * lets MediaPipe correct for inference without physically rotating the bitmap.
 *
 * **Which image the landmarks are normalized to.** The *unrotated* input bitmap, not the
 * upright image the model saw — so the overlay maps them with
 * [CameraImageToViewMapping.mapImageNormalized], which applies the sensor rotation and the
 * preview crop, and not with `mapNormalized`, which would undo a rotation that was never
 * applied to the coordinates. This is what MediaPipe's sources say at the pinned version
 * (`mediapipe-tasks-vision` 0.10.26, tag `v0.10.26` of `google-ai-edge/mediapipe`):
 *  - `mediapipe/tasks/cc/vision/pose_landmarker/pose_landmarker_graph.cc`, on the graph's
 *    landmark outputs: "All returned coordinates are in the unrotated and uncropped input
 *    image coordinates system."
 *  - `mediapipe/tasks/java/com/google/mediapipe/tasks/vision/core/BaseVisionTaskApi.java`,
 *    `convertToNormalizedRect`: the rotation of [ImageProcessingOptions] is not applied to the
 *    image; it becomes the rotation of a region-of-interest rectangle laid over the input image
 *    (`setRotation(-(float) Math.PI * rotationDegrees / 180.0f)`).
 *  - `mediapipe/tasks/cc/vision/pose_landmarker/pose_landmarks_detector_graph.cc`: the
 *    landmarks found in that rotated crop are projected back onto the input image through the
 *    same rectangle (`LandmarkProjectionCalculator`).
 *
 * Read in the sources, not yet observed on a phone: the device check is to raise the right arm
 * and see the skeleton's arm rise on the same side, in portrait and in both landscapes.
 *
 * ### Model asset
 *
 * The MediaPipe Pose Landmarker model ships at
 * `samples/android-demo/src/main/assets/mediapipe/pose_landmarker_lite.task`. If that asset
 * is missing the demo degrades gracefully to a clear "model unavailable" banner instead of
 * crashing — the rest of the AR scene still renders.
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

    // MediaPipe Pose Landmarker — loaded once from the bundled .task asset. Wrapped in
    // runCatching so a missing/corrupt model asset surfaces as a banner, never a crash.
    val landmarker = remember {
        runCatching {
            PoseLandmarker.createFromOptions(
                context,
                PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setModelAssetPath(MODEL_ASSET_PATH)
                            .build(),
                    )
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumPoses(1)
                    .build(),
            )
        }.onFailure {
            android.util.Log.e("ARBodyTracker", "PoseLandmarker init failed", it)
        }.getOrNull()
    }
    DisposableEffect(landmarker) {
        onDispose { landmarker?.close() }
    }

    // True after the first ARCore camera frame — used to dismiss ARCameraInitScrim (#2485).
    var cameraReady by remember { mutableStateOf(false) }
    // #3341: non-null once ARCore has ruled this device out. `cameraReady` never flips
    // then, so the init scrim below has to read the verdict or it covers the SDK's own
    // explanation card forever.
    var arCoreAvailability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var bodyPose by remember { mutableStateOf(BodyPose(emptyMap())) }
    var bodyMapping by remember { mutableStateOf<CameraImageToViewMapping?>(null) }
    // Only compared, in one log line per orientation, with where the mapping puts the image
    // centre (see [CameraMappingProbe]); the mapping itself comes from ARCore's display geometry.
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val mappingProbe = remember { CameraMappingProbe() }

    // Sensor mount read once per camera id, not once per landmarker pass.
    val imageRotation = remember(context) { CameraImageRotation(context) }

    // Landmarker throttle — minimum gap between detector runs so we don't starve the
    // renderer. ~6 fps is plenty for a live skeleton overlay.
    val lastDetectMs = remember { longArrayOf(0L) }

    DemoScaffold(
        arSessionFailed = arSessionFailed,
        arOverlaysEnabled = arCoreAvailability == null,
        title = stringResource(R.string.demo_ar_body_tracker_title),
        onBack = onBack,
        controls = {
            Text(
                stringResource(R.string.demo_ar_body_tracker_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val trackingMessage = trackingFailureMessage(trackingFailureReason)
            val statusText = when {
                landmarker == null -> stringResource(R.string.demo_ar_body_tracker_status_no_model)
                trackingMessage != null -> trackingMessage
                bodyPose.isTracked -> context.resources.getString(
                    R.string.demo_ar_body_tracker_status_tracked,
                    bodyPose.landmarks.size,
                )
                else -> stringResource(R.string.demo_ar_body_tracker_status_aim)
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        },
        // Persistent hint pill — always visible in the viewport so the user knows what
        // to do regardless of whether the controls panel is open. Hidden once the first
        // body is tracked so it never fights the skeleton overlay (#2485).
        //
        // Lives in the scaffold's `bottomOverlay` slot, not in `scene`: this demo always
        // passes `controls`, so the Settings FAB is always there and a plain
        // `Alignment.BottomCenter` pill ran straight under it as soon as the hint got
        // long enough to reach the end edge (#2779).
        //
        // Side effect of the move: the slot draws above the whole scene Box, so the hint
        // is now visible over ARCameraInitScrim during the cold start instead of behind
        // it. That matches ARSceneGeometryDemo (formerly ARStreetscapeDemo), where the
        // status pill has always drawn on top of the same scrim (#2484) — status copy
        // should not be hidden by it.
        //
        // Renders through the shared `DemoStatusBanner` (`ar-scrim` dark pill, #3265) —
        // this demo hand-rolled its own `primary` / `error` @ 82 % alpha pill instead,
        // which is exactly the anti-pattern `DemoStatusBanner`'s own kdoc calls out: a
        // mid-tone brand colour sits close to a lot of real-world luminance (a lit wall,
        // in this case), which is precisely what Pixel 4a device QA hit — the pill read
        // as a pale, low-contrast lavender over a white wall instead of the near-opaque
        // scrim every other AR demo uses (device QA, #3295 follow-up).
        //
        // State priority:
        //   model missing  → "Pose model unavailable" (Blocked)
        //   tracking failed → ARCore's standard tracking-failure reason (Blocked for a
        //                      hard stop, Guidance for a "move to fix it" one)
        //   body tracked    → hidden (skeleton overlay is the feedback)
        //   else            → "Point the camera at a person — full body visible" (Guidance)
        bottomOverlay = {
            val trackingFailureHint = trackingFailureMessage(trackingFailureReason)
            val (statusText, statusTone) = when {
                landmarker == null ->
                    stringResource(R.string.demo_ar_body_tracker_status_no_model) to
                        DemoStatusTone.Blocked
                trackingFailureHint != null ->
                    trackingFailureHint to when (trackingFailureReason) {
                        TrackingFailureReason.CAMERA_UNAVAILABLE,
                        TrackingFailureReason.BAD_STATE -> DemoStatusTone.Blocked
                        else -> DemoStatusTone.Guidance
                    }
                bodyPose.isTracked -> null to DemoStatusTone.Guidance
                else ->
                    stringResource(R.string.demo_ar_body_tracker_hint_aim) to
                        DemoStatusTone.Guidance
            }
            DemoStatusBanner(statusText, tone = statusTone)
        },
    ) {
        Box(modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it }) {
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
                onSessionUpdated = { session, frame: Frame ->
                    // First callback = camera is delivering frames; dismiss the init scrim.
                    if (!cameraReady) cameraReady = true
                    if (landmarker == null) return@ARSceneView
                    if (frame.camera.trackingState != TrackingState.TRACKING) return@ARSceneView

                    val now = System.currentTimeMillis()
                    if (now - lastDetectMs[0] < DETECT_INTERVAL_MS) return@ARSceneView
                    lastDetectMs[0] = now

                    // Pull the CPU camera image. `null` is normal during session warm-up.
                    val cameraImage = frame.cameraImage() ?: return@ARSceneView
                    bodyPose = cameraImage.use { image ->
                        runCatching {
                            val rotationDegrees = imageRotation.degrees(session)
                            val mapping = frame.cameraImageToViewMapping(
                                imageSize = CameraImageSize(image.width, image.height),
                                inputRotationDegrees = rotationDegrees,
                            )
                            mappingProbe.report(mapping, viewSize.width, viewSize.height)
                            val bitmap = image.toBitmap()
                            val mpImage = BitmapImageBuilder(bitmap).build()
                            // #3266: the bitmap is still in ARCore's raw sensor orientation —
                            // tell MediaPipe how to rotate it internally so the pose model
                            // sees an upright person instead of one lying sideways. See the
                            // "Rotation (#3266)" note on the file kdoc.
                            val imageProcessingOptions = ImageProcessingOptions.builder()
                                .setRotationDegrees(rotationDegrees)
                                .build()
                            val result = landmarker.detect(mpImage, imageProcessingOptions)
                            // First (and only — numPoses=1) pose, adapted to the
                            // renderer-agnostic RawLandmark shape so BodyPose stays
                            // free of any MediaPipe dependency.
                            val raw = result.landmarks().firstOrNull()?.map { lm ->
                                BodyPose.RawLandmark(
                                    x = lm.x(),
                                    y = lm.y(),
                                    z = lm.z(),
                                    visibility = lm.visibility().orElse(0f),
                                )
                            }
                            BodyPose.fromMediaPipeLandmarks(raw).also { bodyMapping = mapping }
                        }.getOrDefault(BodyPose(emptyMap()))
                    }
                },
                onTrackingFailureChanged = { reason -> trackingFailureReason = reason },
            )

            // 2D skeleton overlay — drawn from the image-space BodyPose landmarks. The
            // MediaPipe x/y stay normalized to the unrotated input bitmap. The mapping captured
            // from the same ARCore frame applies sensor rotation and the camera preview crop.
            SkeletonOverlay(
                pose = bodyPose,
                mapping = bodyMapping,
                modifier = Modifier.fillMaxSize(),
            )

            // Cover the still-black ARSceneView surface until ARCore delivers its first
            // camera frame — on a cold start this can take several seconds and the
            // silent black screen reads as a crash (#2485, #1473).
            ARCameraInitScrim(
                initializing = !cameraReady,
                arCoreAvailability = arCoreAvailability,
            )
        }
    }
}

/** Draws the live skeleton from an image-space [BodyPose] onto a full-screen Compose [Canvas]. */
@Composable
private fun SkeletonOverlay(
    pose: BodyPose,
    mapping: CameraImageToViewMapping?,
    modifier: Modifier,
) {
    // SceneView accent blue — matches the demo design language.
    val boneColor = Color(0xFF4C8DFF)
    val jointColor = Color(0xFFFFC400)
    Canvas(modifier = modifier) {
        if (!pose.isTracked || mapping == null) return@Canvas

        // Bones first so joint dots sit on top of the lines.
        SKELETON_BONES.forEach { (a, b) ->
            val la = pose[a]
            val lb = pose[b]
            if (la != null && lb != null) {
                val start = mapping.mapImageNormalized(la.x, la.y)
                val end = mapping.mapImageNormalized(lb.x, lb.y)
                drawLine(
                    color = boneColor,
                    start = Offset(start.x, start.y),
                    end = Offset(end.x, end.y),
                    strokeWidth = 8f,
                    cap = Stroke.DefaultCap,
                )
            }
        }
        Joint.entries.forEach { joint ->
            pose[joint]?.let { lm ->
                val point = mapping.mapImageNormalized(lm.x, lm.y)
                drawCircle(
                    color = jointColor,
                    radius = 12f,
                    center = Offset(point.x, point.y),
                )
            }
        }
    }
}

/**
 * Converts an ARCore `YUV_420_888` camera [Image] to an ARGB [Bitmap] for MediaPipe.
 * Routed through [YuvImage] / JPEG — a small per-frame cost, kept bounded by the
 * `DETECT_INTERVAL_MS` throttle so it never runs at the full 60 Hz frame rate.
 */
private fun Image.toBitmap(): Bitmap {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    // YUV_420_888 → NV21: interleave V then U after the Y plane.
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
    val jpegBytes = out.toByteArray()
    return android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
}

/** Path of the bundled MediaPipe Pose Landmarker model under `src/main/assets`. */
private const val MODEL_ASSET_PATH = "mediapipe/pose_landmarker_lite.task"

/** Minimum gap between landmarker runs — ~6 fps so the detector never starves the renderer. */
private const val DETECT_INTERVAL_MS = 160L
