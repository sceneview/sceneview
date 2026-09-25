package io.github.sceneview.demo.demos

import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.sceneview.ar.ARHapticFeedback
import io.github.sceneview.ar.AutoPlacementModel
import io.github.sceneview.ar.AutoPlacementScene
import io.github.sceneview.ar.PlacementPhase
import io.github.sceneview.ar.rememberAutoPlacementState
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/** The additive automatic-placement convenience API, using the shared 0.3 m Toy Car preview. */
@Composable
fun PlacementSceneDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val instance = rememberModelInstance(modelLoader, "models/khronos_toy_car.glb")
    val state = rememberAutoPlacementState()
    val playback = rememberArPlaybackDataset()
    ARHapticFeedback(state)
    DemoScaffold(
        title = stringResource(R.string.demo_placement_scene_title),
        onBack = onBack,
        bottomOverlay = {
            val message = when {
                instance == null -> R.string.ar_place_loading_model
                state.phase == PlacementPhase.SCANNING -> R.string.ar_place_move_slowly
                state.phase == PlacementPhase.NO_SURFACE -> R.string.ar_place_no_surface_title
                state.phase == PlacementPhase.TRACKING_LOST -> R.string.ar_place_tracking_paused
                state.phase == PlacementPhase.RECOVERING -> R.string.ar_place_finding_placement
                state.phase == PlacementPhase.RECOVERY_FAILED -> R.string.ar_place_recovery_failed_title
                state.phase == PlacementPhase.CAMERA_ERROR -> R.string.ar_place_camera_error_title
                else -> null
            }
            DemoStatusBanner(text = message?.let { stringResource(it) }, tone = DemoStatusTone.Guidance)
        },
        controls = {
            Text(stringResource(R.string.ar_place_preview_size))
            TextButton(onClick = { state.resetPlacement(SystemClock.uptimeMillis()) }) {
                Text(stringResource(R.string.ar_dock_reset_label))
            }
            if (state.phase == PlacementPhase.NO_SURFACE) {
                TextButton(onClick = { state.keepScanning(SystemClock.uptimeMillis()) }) {
                    Text(stringResource(R.string.ar_place_keep_scanning))
                }
            }
        },
    ) {
        AutoPlacementScene(
            assetReady = instance != null,
            modifier = Modifier.fillMaxSize(),
            state = state,
            engine = engine,
            modelLoader = modelLoader,
            playbackDataset = playback,
        ) { placement ->
            instance?.let { AutoPlacementModel(placement, state, it) }
        }
    }
}
