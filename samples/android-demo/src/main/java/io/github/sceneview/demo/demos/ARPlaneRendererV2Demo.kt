package io.github.sceneview.demo.demos

import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARCoachingOverlay
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ArGuidanceCue
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.scene.PlaneRendererBase
import io.github.sceneview.ar.rememberArGuidanceState
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.LocalDemoChromeBottomInset
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener

/**
 * AR showcase demo — **Plane Renderer V2** (#3507).
 *
 * Opts `ARSceneView` into [PlaneRendererBase.Version.V2] and lets the renderer speak for
 * itself: soft dots grow over each surface as ARCore finds it, the floor you are facing gets a
 * bright spot, and the dots step aside once something is placed. The one sentence in the
 * status banner says what is on screen right now:
 *
 * 1. **Scanning** — dots appear on each surface the phone finds.
 * 2. **Found** — tap to place the toy car (the bright spot marks the floor you face).
 * 3. **Placed** — the planes fade out (`planeRenderer = false`); tap to pick the car up and
 *    they fade back in.
 *
 * Floors, walls (`HORIZONTAL_AND_VERTICAL` plane finding) and ceilings each get their own dot
 * style from the SDK — no legend needed, the difference is on the surfaces.
 */
@Composable
fun ARPlaneRendererV2Demo(onBack: () -> Unit) {
    var arSessionFailed by remember { mutableStateOf(false) }
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch.
    val arPlaybackDataset = rememberArPlaybackDataset()

    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var planeDetected by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var isTracking by remember { mutableStateOf(false) }
    var placedAnchor by remember { mutableStateOf<Anchor?>(null) }
    // Read by the tap handler only: a plain holder, so a new frame never recomposes.
    val latestFrame = remember { FrameHolder() }

    // #3341: non-null once ARCore has ruled this device out. The flag the scanning
    // banner waits on never flips then, so that banner has to read the verdict or
    // it promises a scan under the SDK's "AR unavailable" card, forever.
    var arCoreAvailability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    // The SDK coaching card: getting ready, the phone sweep until a surface comes up, "Surface
    // found", and the reason with its fix when tracking struggles. While it speaks the
    // banner steps aside — one voice at a time.
    val guidance = rememberArGuidanceState(cameraReady, isTracking, planeDetected, trackingFailureReason)

    DemoScaffold(
        arSessionFailed = arSessionFailed,
        arOverlaysEnabled = arCoreAvailability == null,
        title = stringResource(R.string.demo_ar_plane_renderer_v2_title),
        onBack = onBack,
        bottomOverlay = {
            val trackingLost = trackingFailureReason != null && placedAnchor == null
            DemoStatusBanner(
                // #3341: on a device ARCore has ruled out, the SDK card carries the reason.
                text = when {
                    arCoreAvailability != null -> null
                    guidance.isCoaching -> null
                    trackingLost -> stringResource(R.string.ar_plane_v2_status_move)
                    placedAnchor != null -> stringResource(R.string.ar_plane_v2_status_placed)
                    planeDetected -> stringResource(R.string.ar_plane_v2_status_found)
                    else -> stringResource(R.string.ar_plane_v2_status_scanning)
                },
                tone = if (trackingLost) DemoStatusTone.Guidance else DemoStatusTone.Progress,
            )
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
                // Placing the car fades the surfaces out; picking it up fades them back in.
                planeRenderer = placedAnchor == null,
                planeRendererVersion = PlaneRendererBase.Version.V2,
                sessionConfiguration = { _: Session, config: Config ->
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                },
                onARCoreAvailability = { arCoreAvailability = it },
                onTrackingFailureChanged = { reason -> trackingFailureReason = reason },
                onSessionUpdated = { _, frame ->
                    latestFrame.frame = frame
                    cameraReady = true
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    if (!planeDetected) {
                        planeDetected = frame.getUpdatedTrackables(Plane::class.java)
                            .any { it.trackingState == TrackingState.TRACKING }
                    }
                },
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = { event: MotionEvent, _ ->
                        val placed = placedAnchor
                        if (placed != null) {
                            // Pick the car up: the surfaces fade back in.
                            placed.detach()
                            placedAnchor = null
                        } else {
                            placedAnchor = latestFrame.frame?.placeOnPlane(event)
                        }
                    }
                ),
            ) {
                placedAnchor?.let { anchor ->
                    key(anchor) {
                        val car = rememberModelInstance(modelLoader, TOY_CAR_ASSET)
                        AnchorNode(anchor = anchor) {
                            car?.let { ModelNode(modelInstance = it, scaleToUnits = TOY_CAR_SIZE_METERS) }
                        }
                    }
                }
            }
            // #3341: silent while the SDK's "AR unavailable" card carries the reason.
            ARCoachingOverlay(
                cue = if (arCoreAvailability == null) guidance.cue else ArGuidanceCue.NONE,
                surface = guidance.surface,
                hint = guidance.hint,
                scanLingering = guidance.scanLingering,
                contentPadding = PaddingValues(bottom = LocalDemoChromeBottomInset.current),
            )
        }
    }
}

/** Latest AR frame, for the tap handler. Not Compose state: it changes every frame. */
private class FrameHolder {
    var frame: Frame? = null
}

/** An anchor where [event] hits a tracked floor or table inside its polygon, or `null`. */
private fun Frame.placeOnPlane(event: MotionEvent): Anchor? {
    if (camera.trackingState != TrackingState.TRACKING) return null
    return hitTest(event).firstOrNull { result ->
        val plane = result.trackable
        plane is Plane &&
            plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
            plane.trackingState == TrackingState.TRACKING &&
            plane.isPoseInPolygon(result.hitPose) &&
            result.distance <= MAX_PLACE_DISTANCE_METERS
    }?.createAnchor()
}

private const val TOY_CAR_ASSET = "models/khronos_toy_car.glb"
private const val TOY_CAR_SIZE_METERS = 0.25f
private const val MAX_PLACE_DISTANCE_METERS = 5.0f
