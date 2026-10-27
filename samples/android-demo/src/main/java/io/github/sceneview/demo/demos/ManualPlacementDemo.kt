package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.sceneview.ar.PlacementScene
import io.github.sceneview.ar.rememberPlacementController
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.LocalDemoChromeBottomInset
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.SceneAction
import io.github.sceneview.demo.common.SceneActionBar
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/**
 * The manual tap-to-place API, [PlacementScene], with its reticle.
 *
 * The reticle is a placing cue: it shows while the user looks for a surface, fades out the
 * moment the toy car is down — the car is then seen over the bare camera feed — and comes back
 * on "Place another" ([io.github.sceneview.ar.PlacementController.requestPlacement]).
 */
@Composable
fun ManualPlacementDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val controller = rememberPlacementController()
    val playback = rememberArPlaybackDataset()
    var surfaceFound by remember { mutableStateOf(false) }
    DemoScaffold(
        title = stringResource(R.string.demo_manual_placement_title),
        onBack = onBack,
        bottomOverlay = {
            DemoStatusBanner(
                text = if (controller.isPlacing && surfaceFound) {
                    stringResource(R.string.ar_manual_place_hint)
                } else {
                    null
                },
                tone = DemoStatusTone.Guidance,
            )
            if (!controller.isPlacing) {
                SceneActionBar(
                    SceneAction(
                        stringResource(R.string.ar_manual_place_another),
                        onClick = controller::requestPlacement,
                    ),
                    SceneAction(
                        stringResource(R.string.ar_dock_reset_label),
                        onClick = controller::clear,
                    ),
                )
            }
        },
    ) {
        PlacementScene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            playbackDataset = playback,
            coaching = true,
            coachingBottomClearance = LocalDemoChromeBottomInset.current,
            controller = controller,
            onSurfaceFound = { surfaceFound = true },
            onPlaced = { anchor ->
                // One instance per anchor: a Filament instance has a single parent.
                rememberModelInstance(modelLoader, MANUAL_PLACEMENT_MODEL)?.let { instance ->
                    AnchorNode(anchor = anchor) {
                        ModelNode(modelInstance = instance, scaleToUnits = MANUAL_PLACEMENT_SIZE_M)
                    }
                }
            },
        )
    }
}

private const val MANUAL_PLACEMENT_MODEL = "models/khronos_toy_car.glb"

/** The bundled showcases' shared 0.3 m preview size (`BUNDLED_PLACEMENT_MODELS`). */
private const val MANUAL_PLACEMENT_SIZE_M = 0.3f
