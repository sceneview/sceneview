package io.github.sceneview.demo.demos

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.ar.*
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.AR_CAMERA_INIT_SCRIM_TIMEOUT_MS
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoModalBottomSheet
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.placement.PlacementActionCard
import io.github.sceneview.demo.common.placement.PlacementCard
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.ar.ARHapticFeedback
import io.github.sceneview.material.setColor
import io.github.sceneview.node.CubeNode as CubeNodeImpl
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.delay
import java.io.File

/** One TV, automatically placed on the first usable wall. The SDK owns all placement decisions. */
@Composable
fun WallPlacementDemo(onBack: () -> Unit, playbackDataset: File? = rememberArPlaybackDataset()) {
    var sessionKey by remember { mutableIntStateOf(0) }
    key(sessionKey) {
        WallPlacementExperience(onBack, playbackDataset, onRestart = { sessionKey++ })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WallPlacementExperience(onBack: () -> Unit, playbackDataset: File?, onRestart: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val state = rememberAutoPlacementState()
    // The scene draws the animated wall coaching itself; this keeps the pill quiet meanwhile.
    val guidance = rememberArGuidanceState(state, PlacementSurface.WALL)
    var availability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    var trackingFailure by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var invalidMove by remember { mutableStateOf(false) }
    var show3D by remember { mutableStateOf(false) }
    var hintShown by remember { mutableStateOf(false) }
    var showHint by remember { mutableStateOf(false) }
    var hadPlacement by remember { mutableStateOf(false) }

    // Placement, selection, snap, limits, invalid moves and tracking: the SDK's opt-in haptics.
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
    fun move(x: Float, y: Float) { state.moveBy(x, y) }
    val card = when (state.phase) {
        PlacementPhase.NO_SURFACE -> PlacementCard.NO_SURFACE
        PlacementPhase.RECOVERY_FAILED -> PlacementCard.RECOVERY_FAILED
        // CAMERA_ERROR is raised only when AR never started, which is exactly when the SDK
        // already shows its own full-screen "Couldn't start AR" retry. A second card here
        // stacked two competing "Try again" buttons. iOS leaves this to ARExperienceContainer.
        else -> null
    }
    DemoScaffold(
        title = stringResource(R.string.wall_title),
        onBack = onBack,
        controls = {
            Text(stringResource(R.string.wall_preview_size), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.ar_scale_preview_size, (state.scaleFactor * 100).toInt()))
            TextButton(onClick = ::reset, enabled = state.hasPlacement) {
                Text(stringResource(R.string.wall_reset))
            }
            TextButton(onClick = { show3D = true }) { Text(stringResource(R.string.ar_place_view_in_3d)) }
            if (state.hasPlacement && state.isSelected) {
                val enabled = state.phase == PlacementPhase.PLACED
                // Sheet-only accessibility alternatives; never a default D-pad over the camera.
                WallAdjustment(R.string.wall_dpad_left, R.string.wall_dpad_right, enabled,
                    { move(-0.02f, 0f) }, { move(0.02f, 0f) })
                WallAdjustment(R.string.wall_dpad_down, R.string.wall_dpad_up, enabled,
                    { move(0f, -0.02f) }, { move(0f, 0.02f) })
                WallAdjustment(R.string.wall_dpad_rotate_left, R.string.wall_dpad_rotate_right, enabled,
                    { state.rotateBy(-2f) }, { state.rotateBy(2f) })
                WallAdjustment(R.string.wall_scale_down, R.string.wall_scale_up, enabled,
                    { state.scaleTo(state.scaleFactor - 0.1f) }, { state.scaleTo(state.scaleFactor + 0.1f) })
            }
        },
        bottomOverlay = {
            val text = when {
                card != null -> null
                guidance.isCoaching &&
                    !(state.phase == PlacementPhase.TRACKING_LOST &&
                        trackingFailure == TrackingFailureReason.INSUFFICIENT_LIGHT) -> null
                invalidMove -> stringResource(R.string.ar_place_keep_on_surface)
                else -> when (state.phase) {
                    PlacementPhase.SCANNING -> stringResource(R.string.wall_phase_scanning)
                    PlacementPhase.TRACKING_LOST -> stringResource(R.string.ar_place_tracking_paused) +
                        if (trackingFailure == TrackingFailureReason.INSUFFICIENT_LIGHT)
                            " " + stringResource(R.string.ar_place_try_brighter_area) else ""
                    PlacementPhase.RECOVERING -> stringResource(R.string.ar_place_finding_placement)
                    PlacementPhase.PLACED -> if (showHint) stringResource(R.string.ar_place_gesture_hint) else null
                    PlacementPhase.ADJUSTING ->
                        stringResource(
                            R.string.ar_scale_preview_size,
                            (state.scaleFactor * 100).toInt(),
                        )
                    else -> null
                }
            }
            DemoStatusBanner(text, tone = DemoStatusTone.Guidance)
            PlacementActionCard(card, { show3D = true },
                { state.keepScanning(SystemClock.uptimeMillis()) }, ::reset, onRestart)
        },
    ) {
        AutoPlacementScene(
            assetReady = true,
            modifier = Modifier.fillMaxSize(),
            state = state,
            surface = PlacementSurface.WALL,
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            // No synthetic wall shadow. See the documented renderer parity limitation.
            groundShadows = false,
            playbackDataset = playbackDataset,
            onARCoreAvailability = { availability = it },
            onTrackingFailureChanged = { trackingFailure = it },
        ) { placement ->
            AutoPlacementNode(placement, state,
                onInvalidMove = { invalidMove = it },
            ) { opacity -> WallTV(opacity) }
        }
        // Keyed on the first camera frame, not on INITIALIZING: untracked start-up frames
        // already show the camera, and the coaching overlay speaks over them.
        ARCameraInitScrim(state.phase == PlacementPhase.INITIALIZING && !state.hasCameraFrame, availability)
    }
    if (show3D) {
        DemoModalBottomSheet(onDismissRequest = { show3D = false }) {
            // #3716: the container now reaches the true bottom edge — clear the
            // navigation bar explicitly, or "Close preview" lands under it.
            Column(Modifier.navigationBarsPadding()) {
                Text(stringResource(R.string.wall_preview_size), modifier = Modifier.padding(SceneViewTokens.Space.md))
                SceneView(modifier = Modifier.fillMaxWidth().aspectRatio(1f), engine = engine,
                    modelLoader = modelLoader, materialLoader = materialLoader) { WallTV() }
                TextButton(onClick = { show3D = false }) { Text(stringResource(R.string.wall_close_preview)) }
            }
        }
    }
}

/** Vertical button layout keeps every adjustment readable at large text sizes. */
@Composable
private fun WallAdjustment(decreaseLabel: Int, increaseLabel: Int, enabled: Boolean,
                           decrease: () -> Unit, increase: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs)) {
        OutlinedButton(onClick = decrease, enabled = enabled) { Text(stringResource(decreaseLabel)) }
        OutlinedButton(onClick = increase, enabled = enabled) { Text(stringResource(increaseLabel)) }
    }
}

/** Same authored geometry/materials as iOS; the base size is a 0.3 m preview, not a 55-inch claim. */
@Composable
private fun SceneScope.WallTV(opacity: Float = 1f) {
    val body = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFF20242A).copy(alpha = 0f), metallic = 0f, roughness = 0.8f)
    }
    val screen = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFF06080C).copy(alpha = 0f), metallic = 0f, roughness = 0.15f)
    }
    SideEffect {
        body.setColor(Color(0xFF20242A).copy(alpha = opacity))
        screen.setColor(Color(0xFF06080C).copy(alpha = opacity))
    }
    Node(scale = Scale(0.3f / 1.26f)) {
        // The whole TV moves and scales as one: only the parent node takes gestures.
        val fixedChild: CubeNodeImpl.() -> Unit = {
            isEditable = true
            isPositionEditable = false
            isRotationEditable = false
            isScaleEditable = false
        }
        CubeNode(
            size = Size(1.26f, 0.74f, 0.04f),
            position = Position(0f, 0.37f, 0.02f),
            materialInstance = body,
            apply = fixedChild,
        )
        CubeNode(
            size = Size(1.20f, 0.68f, 0.01f),
            position = Position(0f, 0.37f, 0.045f),
            materialInstance = screen,
            apply = fixedChild,
        )
    }
}
