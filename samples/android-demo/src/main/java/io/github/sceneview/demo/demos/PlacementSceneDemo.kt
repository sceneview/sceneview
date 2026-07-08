package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sceneview.ar.PlacementController
import io.github.sceneview.ar.PlacementScene
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/**
 * One-line tap-to-place AR demo — the Sceneform `ArFragment` parity showcase
 * ([#1765](https://github.com/sceneview/sceneview/issues/1765)).
 *
 * Where [ARPlacementDemo] hand-rolls the full placement pipeline (centre reticle, hit-test in the
 * tap gesture, anchor creation, instant-placement fallback) to demonstrate the low-level AR
 * primitives, this demo shows the high-level [PlacementScene] composable doing all of it in one
 * call. The entire AR scene below — reticle, plane hint, tap-to-place, instant-placement
 * fallback — is the single `PlacementScene { anchor -> ... }` block. The caller only declares
 * *what* rides each placed anchor.
 *
 * This is the snippet `llms.txt` shows first for AR placement: it is the shortest correct
 * tap-to-place code an AI can generate.
 */
@Composable
fun PlacementSceneDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)

    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo with
    // `--es ar_playback_file <path>` (#1576). `null` for every normal launch, so live AR is
    // completely unchanged for real users.
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Keep the controller in the parent so the "Clear All" button (outside the AR scope) can
    // reach it. PlacementScene also `remember`s its own controller when none is passed via
    // `content`; here we surface it so the demo can drive a counter + clear button.
    val controllerHolder = remember { mutableStateOf<PlacementController?>(null) }

    DemoScaffold(
        title = stringResource(R.string.demo_placement_scene_title),
        onBack = onBack,
        controls = {
            Text(
                text = "The whole AR scene is a single `PlacementScene { anchor -> ... }` call. " +
                    "With coaching + ground shadows on, it bundles the animated onboarding guide, " +
                    "a ring reticle that brightens once a surface is ready, tap-to-place, the " +
                    "instant-placement fallback and a contact shadow under each model. " +
                    "Aim the ring at a surface and tap to drop a model.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = { controllerHolder.value?.clear() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text("Clear All")
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            PlacementScene(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                // Modern consumer-AR placement UX: onboarding guide while searching, a ring
                // reticle that flips to "ready" on a surface, and a contact shadow under each
                // placed model. The plane grid fades after the first placement (default).
                coaching = true,
                groundShadows = true,
                onPlaced = { anchor ->
                    // Declare what rides each placed anchor. PlacementScene already created the
                    // Anchor from the tapped hit — the caller just attaches content to it.
                    AnchorNode(anchor = anchor) {
                        val instance =
                            rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")
                        instance?.let {
                            ModelNode(
                                modelInstance = it,
                                scaleToUnits = 0.3f,
                            )
                        }
                    }
                },
                content = { controller ->
                    // Surface the controller so the parent's Clear All button can reach it.
                    controllerHolder.value = controller
                },
            )

            // Overlays — driven by the controller's Compose-observable placement count.
            // The bottom onboarding hint is now handled by PlacementScene's built-in coaching
            // guide (`coaching = true`), so the demo only surfaces the placed-count.
            val placedCount = controllerHolder.value?.count ?: 0
            PlacedCountPill(placedCount, Modifier.align(Alignment.TopCenter))
        }
    }
}

/** Top-centre "X models placed" pill. */
@Composable
private fun PlacedCountPill(count: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(top = 8.dp),
        color = Color.Black.copy(alpha = 0.7f),
        contentColor = Color.White,
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = if (count == 1) "1 model placed" else "$count models placed",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
