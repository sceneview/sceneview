package io.github.sceneview.demo.common.placement

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.google.android.filament.Engine
import com.google.ar.core.*
import io.github.sceneview.ar.*
import io.github.sceneview.ar.camera.ARCameraStream
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.rememberOnGestureListener
import java.io.File

/** App adapter for feature sessions that need configuration hooks unavailable on AutoPlacementScene.
 * The SDK still owns candidate validation, state transitions and manipulation; no tap places content.
 */
@Composable
internal fun FeaturePlacementScene(
    assetReady: Boolean,
    state: AutoPlacementState,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    modifier: Modifier = Modifier,
    cameraStream: ARCameraStream? = rememberARCameraStream(materialLoader),
    playbackDataset: File? = null,
    sessionConfiguration: (Session, Config) -> Unit = { _, _ -> },
    onSessionCreated: (Session) -> Unit = {},
    onSessionUpdated: (Session, Frame) -> Unit = { _, _ -> },
    onSessionFailed: (Exception) -> Unit = {},
    onARCoreAvailability: (ARCoreAvailability?) -> Unit = {},
    arCoreAvailabilityOverlay: (@Composable BoxScope.(ARCoreAvailabilityState) -> Unit)? = { ARCoreAvailabilityOverlay(it) },
    onTrackingFailureChanged: (TrackingFailureReason?) -> Unit = {},
    onPlaced: (AutoPlacementResult) -> Unit = {},
    content: @Composable ARSceneScope.(AutoPlacementResult?) -> Unit,
) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var placement by remember(state) { mutableStateOf<AutoPlacementResult?>(null) }
    var planes by remember { mutableStateOf<List<Plane>>(emptyList()) }
    val ready by rememberUpdatedState(assetReady)
    LaunchedEffect(state, assetReady) {
        if (assetReady) state.requestPlacement() else state.withdrawRequest()
    }
    val currentPlacement by rememberUpdatedState(placement)
    DisposableEffect(state) {
        onDispose {
            currentPlacement?.anchor?.detach()
            state.dismiss()
        }
    }
    ARSceneView(
        modifier = modifier.onSizeChanged { viewport = it },
        engine = engine, modelLoader = modelLoader, materialLoader = materialLoader,
        cameraStream = cameraStream, playbackDataset = playbackDataset,
        planeRenderer = false,
        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL,
        instantPlacementMode = Config.InstantPlacementMode.DISABLED,
        sessionConfiguration = sessionConfiguration,
        onSessionCreated = { session ->
            // The SDK may retry startup in place; release the controller's startup error
            // before forwarding the new session, without changing an existing placement.
            if (state.phase == PlacementPhase.CAMERA_ERROR) state.resetPlacement(SystemClock.uptimeMillis())
            onSessionCreated(session)
        },
        onSessionFailed = { state.cameraFailed(); onSessionFailed(it) },
        onARCoreAvailability = onARCoreAvailability,
        arCoreAvailabilityOverlay = arCoreAvailabilityOverlay,
        onTrackingFailureChanged = onTrackingFailureChanged,
        onGestureListener = rememberOnGestureListener(onSingleTapConfirmed = { _, node ->
            if (node == null) state.deselectPlacement() else state.selectPlacement()
        }),
        onSessionUpdated = { session, frame ->
            onSessionUpdated(session, frame)
            if (!state.hasPlacement && placement != null) {
                placement?.anchor?.detach()
                placement = null
            }
            val tracked = session.getAllTrackables(Plane::class.java)
                .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
            if (tracked != planes) planes = tracked
            val candidate = if (ready && state.wantsSurface) {
                findAutoPlacementSurface(frame, tracked, viewport.width, viewport.height)
            } else null
            val effect = state.onFrame(
                FrameInput(SystemClock.uptimeMillis(), frame.camera.trackingState == TrackingState.TRACKING,
                    candidate != null, placement?.anchor?.trackingState?.let { it == TrackingState.TRACKING }),
                commit = { candidate?.createAnchor()?.let { placement = it; true } ?: false },
            )
            if (effect == FrameEffect.PLACE) placement?.let(onPlaced)
        },
    ) {
        // Reset may replace an anchor in a single frame; recreate the whole node subtree
        // so remembered model nodes attach to the new pivot, as in AutoPlacementScene.
        key(placement) { content(placement) }
        if (state.phase == PlacementPhase.PLACED || state.phase == PlacementPhase.ADJUSTING) {
            planes.forEach { plane -> key(plane) { ShadowReceiverPlane(plane = plane) } }
        }
    }
}
