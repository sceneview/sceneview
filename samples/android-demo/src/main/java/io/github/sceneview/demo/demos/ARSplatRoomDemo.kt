package io.github.sceneview.demo.demos

import android.os.SystemClock
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARHapticFeedback
import io.github.sceneview.ar.AutoPlacementNode
import io.github.sceneview.ar.AutoPlacementScene
import io.github.sceneview.ar.PlacementPhase
import io.github.sceneview.ar.rememberArGuidanceState
import io.github.sceneview.ar.rememberAutoPlacementState
import io.github.sceneview.collision.Box
import io.github.sceneview.collision.Vector3
import io.github.sceneview.core.splat.SplatCloud
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.AR_CAMERA_INIT_SCRIM_TIMEOUT_MS
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.placement.PlacementActionCard
import io.github.sceneview.demo.common.placement.PlacementCard
import io.github.sceneview.demo.demos.internal.ScanFootprint
import io.github.sceneview.demo.demos.internal.ScanRoomStatus
import io.github.sceneview.demo.demos.internal.realSizePercent
import io.github.sceneview.demo.demos.internal.scanFootprint
import io.github.sceneview.demo.demos.internal.scanHeightCentimetres
import io.github.sceneview.demo.demos.internal.scanRoomStatus
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberSplatCloud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** The bundled capture: the same raccoon family as `splat-preview`, now on the user's floor. */
private const val SCAN_ASSET = "splats/raccoon_family.spz"

/**
 * "Your scan, in your room" (#4023): a real phone capture, stood on the first floor or table
 * ARCore finds, at the size it really is.
 *
 * The scan is in metres already, so nothing is normalised: the SDK's automatic placement puts
 * an anchor on the plane and [SplatNode][io.github.sceneview.SceneScope.SplatNode] draws the
 * cloud under it, offset so the captured ground lands on the real one ([scanFootprint]). The
 * splats re-sort for the AR camera on their own as the user walks around them. Pinch still
 * works (25–400 %), and *Real size* snaps back.
 */
@Composable
fun ARSplatRoomDemo(onBack: () -> Unit, playbackDataset: File? = rememberArPlaybackDataset()) {
    var sessionKey by remember { mutableIntStateOf(0) }
    key(sessionKey) {
        SplatRoomExperience(onBack, playbackDataset, onRestart = { sessionKey++ })
    }
}

@Composable
private fun SplatRoomExperience(onBack: () -> Unit, playbackDataset: File?, onRestart: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val cloud = rememberSplatCloud(SCAN_ASSET)
    // Three sorts of 233 808 floats: cheap, but not a main-thread job.
    val footprint by produceState<ScanFootprint?>(null, cloud) {
        value = cloud?.let { withContext(Dispatchers.Default) { scanFootprint(it) } }
    }
    val scanReady = cloud != null && footprint != null

    val state = rememberAutoPlacementState()
    // The scene draws the animated coaching itself; this keeps the pill quiet meanwhile.
    val guidance = rememberArGuidanceState(state)
    var availability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    var trackingFailure by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var invalidMove by remember { mutableStateOf(false) }
    var hintShown by remember { mutableStateOf(false) }
    var showHint by remember { mutableStateOf(false) }
    var hadPlacement by remember { mutableStateOf(false) }

    ARHapticFeedback(state)
    LaunchedEffect(state.hasPlacement) {
        if (state.hasPlacement && !hadPlacement && !hintShown) { hintShown = true; showHint = true }
        hadPlacement = state.hasPlacement
    }
    LaunchedEffect(showHint) {
        if (showHint) { delay(5_000); showHint = false }
    }
    LaunchedEffect(state.phase) {
        if (state.phase == PlacementPhase.ADJUSTING) showHint = false
        if (state.phase != PlacementPhase.PLACED && state.phase != PlacementPhase.ADJUSTING) invalidMove = false
    }
    LaunchedEffect(state.phase, availability) {
        if (state.phase == PlacementPhase.INITIALIZING && availability == null) {
            delay(AR_CAMERA_INIT_SCRIM_TIMEOUT_MS)
            state.cameraFailed()
        }
    }
    fun reset() {
        invalidMove = false
        state.resetPlacement(SystemClock.uptimeMillis())
    }
    val canAdjust = state.hasPlacement &&
        (state.phase == PlacementPhase.PLACED || state.phase == PlacementPhase.ADJUSTING)
    val percent = realSizePercent(state.scaleFactor)
    fun realSize() {
        state.selectPlacement()
        state.scaleTo(1f)
    }
    val card = when (state.phase) {
        PlacementPhase.NO_SURFACE -> PlacementCard.NO_SURFACE
        PlacementPhase.RECOVERY_FAILED -> PlacementCard.RECOVERY_FAILED
        // CAMERA_ERROR: the SDK already shows its own full-screen retry (see wall-placement).
        else -> null
    }
    val heightCm = footprint?.let { scanHeightCentimetres(it) }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_splat_room_title),
        onBack = onBack,
        peekHeader = if (canAdjust && heightCm != null) {
            if (percent == 100) stringResource(R.string.demo_ar_splat_room_peek_real, heightCm)
            else stringResource(R.string.demo_ar_splat_room_scale, percent)
        } else null,
        onReset = ::reset,
        dock = listOf(
            DockItem(
                icon = Icons.Outlined.Straighten,
                label = stringResource(R.string.demo_ar_splat_room_real_size),
                caption = stringResource(R.string.demo_ar_splat_room_real_size_caption),
                onClick = ::realSize,
                enabled = canAdjust && percent != 100,
            ),
            DockItem(
                icon = Icons.Outlined.RestartAlt,
                label = stringResource(R.string.ar_dock_reset_label),
                caption = stringResource(R.string.demo_ar_splat_room_reset_caption),
                onClick = ::reset,
                enabled = state.hasPlacement,
            ),
        ),
        controls = {
            Text(stringResource(R.string.demo_ar_splat_room_intro), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp)) // space-sm
            Text(
                text = if (heightCm != null) stringResource(R.string.demo_ar_splat_room_size, heightCm)
                    else stringResource(R.string.demo_ar_splat_room_opening),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp)) // space-xs
            Text(
                stringResource(R.string.demo_ar_splat_room_credit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        bottomOverlay = {
            val status = scanRoomStatus(
                phase = state.phase,
                scanReady = scanReady,
                cardShown = card != null,
                coaching = guidance.isCoaching,
                invalidMove = invalidMove,
                showGestureHint = showHint,
                lowLight = trackingFailure == TrackingFailureReason.INSUFFICIENT_LIGHT,
            )
            val text = when (status) {
                null -> null
                ScanRoomStatus.OpeningScan -> stringResource(R.string.demo_ar_splat_room_opening)
                ScanRoomStatus.MoveSlowly -> stringResource(R.string.ar_place_move_slowly)
                ScanRoomStatus.KeepOnSurface -> stringResource(R.string.ar_place_keep_on_surface)
                ScanRoomStatus.TrackingPaused -> stringResource(R.string.ar_place_tracking_paused)
                ScanRoomStatus.TrackingPausedLowLight -> stringResource(R.string.ar_place_tracking_paused) +
                    " " + stringResource(R.string.ar_place_try_brighter_area)
                ScanRoomStatus.FindingPlacement -> stringResource(R.string.ar_place_finding_placement)
                ScanRoomStatus.GestureHint -> stringResource(R.string.ar_place_gesture_hint)
                ScanRoomStatus.Scale -> stringResource(R.string.demo_ar_splat_room_scale, percent)
            }
            DemoStatusBanner(text, tone = DemoStatusTone.Guidance)
            // "View in 3D" is the neighbouring `splat-preview` demo, so the card offers none.
            PlacementActionCard(card, null,
                { state.keepScanning(SystemClock.uptimeMillis()) }, ::reset, onRestart)
        },
    ) {
        AutoPlacementScene(
            assetReady = scanReady,
            modifier = Modifier.fillMaxSize(),
            state = state,
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            playbackDataset = playbackDataset,
            onARCoreAvailability = { availability = it },
            onTrackingFailureChanged = { trackingFailure = it },
        ) { placement ->
            val scan = cloud
            val measured = footprint
            if (scan != null && measured != null) {
                AutoPlacementNode(placement, state, onInvalidMove = { invalidMove = it }) {
                    StandingScan(scan, measured)
                }
            }
        }
        ARCameraInitScrim(state.phase == PlacementPhase.INITIALIZING && !state.hasCameraFrame, availability)
    }
}

/**
 * The cloud, offset so its captured ground sits on the anchor, with a box collider around the
 * subject: a splat cloud has no mesh to pick, and `AutoPlacementNode` only moves, turns and
 * scales content a touch actually lands on. Editable with every edit disabled, as the SDK asks,
 * so the gesture reaches the placement pivot instead of the node.
 */
@Composable
private fun io.github.sceneview.NodeScope.StandingScan(cloud: SplatCloud, footprint: ScanFootprint) {
    val ground = footprint.groundPoint
    SplatNode(
        splatCloud = cloud,
        position = -ground,
        apply = {
            isEditable = true
            isPositionEditable = false
            isRotationEditable = false
            isScaleEditable = false
            collisionShape = Box(
                Vector3(footprint.width, footprint.height, footprint.depth),
                Vector3(ground.x, ground.y + footprint.height / 2f, ground.z),
            )
        },
    )
}
