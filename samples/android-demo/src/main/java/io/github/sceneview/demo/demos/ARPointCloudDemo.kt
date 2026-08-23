package io.github.sceneview.demo.demos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.demo.common.QaCameraBackdrop
import io.github.sceneview.demo.common.qaCameraBackdropEnabled
import io.github.sceneview.demo.common.qaCameraBackdropSurfaceType
import io.github.sceneview.demo.common.rememberQaCameraBackdropActive
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.demos.internal.PointCloudFeedback
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * AR demo — renders ARCore's live tracking feature points as an in-scene point cloud using
 * [io.github.sceneview.ar.node.PointCloudNode].
 *
 * Unlike [ARRawDepthPointCloudDemo] — which samples the raw *depth image* and draws a
 * screen-space Compose `Canvas` overlay — this demo consumes
 * [com.google.ar.core.Frame.acquirePointCloud] (the sparse, **world-space** feature points
 * ARCore uses for motion tracking) and renders them as a real Filament `POINTS` primitive in
 * the 3D scene. The result is the SceneView equivalent of AR Foundation's `ARPointCloudManager`.
 *
 * A Compose [Slider] drives the confidence cut-off: at `0` every detected feature point is
 * rendered (a denser but noisier cloud); raising it keeps only ARCore's most confident points.
 *
 * The live point count is surfaced in the scaffold header so the user gets honest feedback on
 * tracking quality — more points generally means a richer, better-tracked scene.
 *
 * **Tracking feedback (#3270).** [io.github.sceneview.ar.node.PointCloudNode.update] silently
 * no-ops whenever ARCore isn't `TRACKING` — a lost-tracking session renders literally nothing,
 * with no on-screen explanation, and read to users as "nothing rendered" rather than "point the
 * camera at a textured surface". This demo now surfaces the same tracking-failure banner as its
 * [ARRawDepthPointCloudDemo] / `ARSceneSemanticsDemo` siblings, plus a "still scanning" hint if
 * tracking is fine but the cloud has stayed empty for a couple of seconds (the #1617 principle:
 * never leave the user staring at a screen that looks broken without saying why).
 *
 * Closes [#1773](https://github.com/sceneview/sceneview/issues/1773),
 * [#3270](https://github.com/sceneview/sceneview/issues/3270).
 */
@Composable
fun ARPointCloudDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch.
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Confidence cut-off in [0, 1]. Default mirrors PointCloudNode.DEFAULT_CONFIDENCE_THRESHOLD
    // (0.2) — permissive enough that motion-stereo's noisy first frames still surface points.
    var confidenceThreshold by remember { mutableFloatStateOf(0.2f) }
    var pointCount by remember { mutableIntStateOf(0) }

    var isTracking by remember { mutableStateOf(false) }
    // QA camera backdrop (#3308): the emulator has no camera HAL, so a blurred room photo
    // stands in for the camera feed until a real frame arrives.
    var cameraReady by remember { mutableStateOf(false) }
    val cameraStream = rememberARCameraStream(materialLoader)
    val qaBackdrop = rememberQaCameraBackdropActive(cameraReady)
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }

    // "Stuck at zero points" chip state (#3270) — same shape as ARRawDepthPointCloudDemo's.
    // `zeroPointsSince` is set the first time the cloud reports zero points and cleared as
    // soon as it recovers; `now` is bumped by a 1 Hz tick so the gate re-evaluates even while
    // the point count itself never changes (a level condition, not an edge).
    var zeroPointsSince by remember { mutableStateOf<Long?>(System.currentTimeMillis()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1.seconds)
            now = System.currentTimeMillis()
        }
    }
    val zeroPointsStuck = PointCloudFeedback.zeroPointsStuck(
        isTracking = isTracking,
        pointCount = pointCount,
        zeroPointsSinceMs = zeroPointsSince,
        nowMs = now,
    )

    // A flat cyan unlit dot — lighting-independent so the points read clearly against any
    // camera feed. Owned by this composable: destroyed on dispose to avoid leaking the
    // MaterialInstance into Filament's tables (#1123).
    val pointMaterial = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(SceneViewColors.Accent)
    }
    DisposableEffect(pointMaterial) {
        onDispose { materialLoader.destroyMaterialInstance(pointMaterial) }
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_point_cloud_title),
        onBack = onBack,
        peekHeader = if (pointCount > 0) {
            stringResource(R.string.demo_ar_point_cloud_count, pointCount)
        } else {
            stringResource(R.string.demo_ar_point_cloud_move_hint)
        },
        controls = {
            Text(
                text = "ARCore's sparse feature points, rendered as a world-space point cloud " +
                    "(PointCloudNode). Move your device to detect more points.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Confidence filter",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "%.2f".format(Locale.US, confidenceThreshold),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Slider(
                    value = confidenceThreshold,
                    onValueChange = { confidenceThreshold = it },
                    valueRange = 0f..1f,
                )
            }
            // Developer-only debug toggle — lets QA force-emit each TrackingFailureReason so
            // the banner below can be validated without staging a real failure (#1881).
            ForceTrackingFailureMenu()
        },
        // Tracking-failure banner + "still scanning" hint — same vocabulary as the other AR
        // demos. Without this, a lost-tracking session (or a cold-start still resolving its
        // first points) rendered nothing at all with zero on-screen explanation, which is
        // exactly what #3270 reported as "nothing rendered".
        bottomOverlay = {
            val effectiveReason = ForcedTrackingFailure.override ?: trackingFailureReason
            AnimatedVisibility(
                visible = (!isTracking && trackingFailureReason != null) ||
                    ForcedTrackingFailure.override != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                val (statusText, statusTone) = when (effectiveReason) {
                    TrackingFailureReason.INSUFFICIENT_LIGHT ->
                        "Not enough light" to DemoStatusTone.Guidance
                    TrackingFailureReason.EXCESSIVE_MOTION ->
                        "Moving too fast" to DemoStatusTone.Guidance
                    TrackingFailureReason.INSUFFICIENT_FEATURES ->
                        "Not enough detail — point at a textured surface" to
                            DemoStatusTone.Guidance
                    TrackingFailureReason.CAMERA_UNAVAILABLE ->
                        "Camera unavailable" to DemoStatusTone.Blocked
                    TrackingFailureReason.BAD_STATE ->
                        "AR session error" to DemoStatusTone.Blocked
                    else -> stringResource(R.string.ar_status_scanning) to
                        DemoStatusTone.Progress
                }
                DemoStatusBanner(text = statusText, tone = statusTone)
            }
            AnimatedVisibility(
                visible = zeroPointsStuck && trackingFailureReason == null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                DemoStatusBanner(
                    text = stringResource(R.string.demo_ar_point_cloud_move_hint_short),
                    tone = DemoStatusTone.Guidance,
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (qaBackdrop) QaCameraBackdrop(seed = "point-cloud")
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                materialLoader = materialLoader,
                isOpaque = !qaCameraBackdropEnabled(),
                surfaceType = qaCameraBackdropSurfaceType(),
                cameraStream = if (qaBackdrop) null else cameraStream,
                playbackDataset = arPlaybackDataset,
                onSessionUpdated = { _: Session, frame: Frame ->
                    cameraReady = true
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                },
                onTrackingFailureChanged = { reason ->
                    trackingFailureReason = reason
                },
            ) {
                val pointCloud = rememberPointCloud(
                    confidenceThreshold = confidenceThreshold,
                    materialInstance = pointMaterial,
                    onPointCloudUpdated = {
                        pointCount = it
                        zeroPointsSince = if (it > 0) null else zeroPointsSince ?: System.currentTimeMillis()
                    },
                )
                PointCloudNode(node = pointCloud)
            }
        }
    }
}
