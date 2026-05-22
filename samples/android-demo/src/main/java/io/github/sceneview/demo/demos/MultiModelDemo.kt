package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberHeroYaw
import io.github.sceneview.demo.sketchfab.SampleAssets
import io.github.sceneview.demo.sketchfab.SketchfabAssetResolver
import io.github.sceneview.demo.sketchfab.SketchfabSlug
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelLoader
import java.io.File

/**
 * Composes a themed "Park" scene from 4 streamed glTF assets — an oak tree
 * (the backdrop), a park bench (the foreground prop), a sleeping dog (the
 * animated occupant), and a perched songbird (the second animated occupant).
 *
 * Lighting comes from `studio_warm_2k.hdr` — a soft golden-hour wash that
 * unifies the four very different materials (bark, weathered wood, fur,
 * feathers) into one cohesive open-air display.
 *
 * Controls:
 * - Visibility chips per model (toggle individual nodes off / on)
 * - "Spin scene" toggle — slow circular auto-rotation of the whole formation,
 *   lets the viewer walk around the display without touching the screen
 *
 * The previous "tabletop" composition (shiba + lantern + helmet + dragon, all
 * bundled) is replaced by the streamed `park` category from [SampleAssets].
 * Offline fallback is per-slug (`shiba.glb` / `khronos_lantern.glb` /
 * `threejs_soldier.glb` etc.) so the demo still renders four nodes when no
 * Sketchfab key is configured — the visual swap is documented in the CHANGELOG
 * but the user-visible behaviour stays "4 nodes, 4 chips, 1 spin toggle".
 *
 * Streaming pipeline (Stage 2, issue #1152) — the resolver returns the
 * downloaded GLB or the registered bundled fallback (see [SketchfabAssetResolver]
 * Kdoc). The whole scene is keyed by the slug uid, so a registry edit
 * re-resolves exactly the affected nodes.
 */
@Composable
fun MultiModelDemo(onBack: () -> Unit) {
    var showTree by remember { mutableStateOf(true) }
    var showBench by remember { mutableStateOf(true) }
    var showDog by remember { mutableStateOf(true) }
    var showBird by remember { mutableStateOf(true) }
    var spinScene by remember { mutableStateOf(true) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val context = LocalContext.current

    // Look up the 4 `park` slugs by uid (stable across registry re-ordering).
    // Falling back to the first slug-by-category if an explicit uid is somehow
    // missing keeps the demo running at degraded fidelity rather than crashing.
    val parkSlugs = SampleAssets.byCategory["park"].orEmpty()
    val tree = SampleAssets.byUid["d841c3bcc5324daebee50f45619e05fc"] ?: parkSlugs.getOrNull(0)
    val bench = SampleAssets.byUid["6d1aeea748f147789004bc03e1930d32"] ?: parkSlugs.getOrNull(1)
    val dog = SampleAssets.byUid["4f6ab5594a8a415aba3f958682b9ced5"] ?: parkSlugs.getOrNull(2)
    val bird = SampleAssets.byUid["fd582b0d4a8c4af1a1b5c4f21a481c93"] ?: parkSlugs.getOrNull(3)

    // Warm-up the park category in parallel on first composition. The resolver
    // dedupes concurrent calls for the same slug so the per-node `resolve`
    // below picks up the cached file as soon as the prefetch lands.
    LaunchedEffect(Unit) {
        runCatching {
            SketchfabAssetResolver.getInstance(context).prefetchAll("park")
        }
    }

    // Each `produceState` flips from `null` (download / fallback-copy still
    // running on IO) to a real `File` once the resolver returns. ModelInstance
    // creation happens only after the file is on disk — `rememberFileModelInstance`
    // loads the `file://` URI via `ModelLoader.loadModelInstance`, which is
    // async-safe and keeps the Filament JNI work on the Main thread.
    val treeFile = rememberSlugFile(tree)
    val benchFile = rememberSlugFile(bench)
    val dogFile = rememberSlugFile(dog)
    val birdFile = rememberSlugFile(bird)

    val treeInstance = rememberFileModelInstance(modelLoader, treeFile)
    val benchInstance = rememberFileModelInstance(modelLoader, benchFile)
    val dogInstance = rememberFileModelInstance(modelLoader, dogFile)
    val birdInstance = rememberFileModelInstance(modelLoader, birdFile)

    // Warm dusk HDR — `studio_warm_2k.hdr` gives a golden-hour wash that
    // unifies the four very different materials. Skybox enabled so the warm tint
    // is visible behind the display, not just rim-lighting the models on a black
    // void. Falls back to the default neutral environment while the HDR is still
    // loading.
    val hdrEnvironment = rememberHDREnvironment(
        environmentLoader,
        "environments/studio_warm_2k.hdr",
        createSkybox = true,
    )
    val fallbackEnvironment = rememberEnvironment(environmentLoader)
    val activeEnvironment = hdrEnvironment ?: fallbackEnvironment

    val allLoaded = treeInstance != null && benchInstance != null &&
        dogInstance != null && birdInstance != null
    // Yaw drives the parent-scene rotation when "Spin scene" is on. Slow 30 s sweep
    // so the viewer can take in each face of the display before it cycles round.
    val sceneYaw = rememberHeroYaw(
        trigger = allLoaded && spinScene, durationMillis = 30_000, staticYaw = 0f,
    )

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_multi_model_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            Text("Visibility", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = showTree,
                    onClick = { showTree = !showTree },
                    label = { Text("Tree") },
                )
                FilterChip(
                    selected = showBench,
                    onClick = { showBench = !showBench },
                    label = { Text("Bench") },
                )
                FilterChip(
                    selected = showDog,
                    onClick = { showDog = !showDog },
                    label = { Text("Dog") },
                )
                FilterChip(
                    selected = showBird,
                    onClick = { showBird = !showBird },
                    label = { Text("Bird") },
                )
            }

            // Spin toggle — wrap the row in toggleable so taps anywhere flip the state
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = spinScene,
                        onValueChange = { spinScene = it },
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Spin scene", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = spinScene, onCheckedChange = null)
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                modelLoader = modelLoader,
                environmentLoader = environmentLoader,
                environment = activeEnvironment,
                cameraManipulator = rememberCameraManipulator(
                    orbitHomePosition = Position(0f, 0.4f, 0.5f),
                    targetPosition = Position(0f, 0f, -1.5f),
                ),
            ) {
                // Park scene arrangement: tree as the towering backdrop (back-
                // centre, scale 1.8 m to read as a real-world tree on the
                // tabletop), bench in front-centre as the foreground prop, the
                // sleeping dog at front-left next to the bench's leg, the
                // songbird perched front-right.
                //
                // Front row z=-1.3, back row z=-1.7 so the depth difference reads
                // even on a portrait phone viewport. sceneYaw rotates each model
                // AROUND the formation centre by treating its (x, z) as polar
                // coords offset from (0, -1.5). Per-model rotation cancels the
                // yaw on its own Y so each piece stays facing the camera as the
                // formation sweeps — gives a "turntable display" feel.
                val centerZ = -1.5f
                val displays = listOf(
                    Display(showTree, treeInstance, x = 0.0f, z = -1.7f, scale = 1.80f),
                    Display(showBench, benchInstance, x = 0.0f, z = -1.3f, scale = 0.65f),
                    Display(showDog, dogInstance, x = -0.55f, z = -1.3f, scale = 0.40f),
                    Display(showBird, birdInstance, x = 0.55f, z = -1.3f, scale = 0.15f),
                )
                for (d in displays) {
                    if (!d.show || d.instance == null) continue
                    // Rotation math lives in DemoMath.rotateAroundCentre so it can be
                    // JVM-unit-tested without firing up Filament / Compose.
                    val (rx, rz) = DemoMath.rotateAroundCentre(d.x, d.z - centerZ, sceneYaw)
                    ModelNode(
                        modelInstance = d.instance,
                        // The animated dog + bird auto-play their skeletal animation
                        // for "alive" scene reads; in qaMode we need the bind pose
                        // to render every frame so golden screenshots stay
                        // deterministic.
                        autoAnimate = !DemoSettings.qaMode,
                        scaleToUnits = d.scale,
                        centerOrigin = Position(0f, 0.5f, 0f),
                        position = Position(x = rx, y = 0f, z = rz + centerZ),
                        rotation = Rotation(y = -sceneYaw),
                    )
                }
            }
            LoadingScrim(loading = !allLoaded, label = "Loading 4 models…")
        }
    }
}

private data class Display(
    val show: Boolean,
    val instance: io.github.sceneview.model.ModelInstance?,
    val x: Float,
    val z: Float,
    val scale: Float,
)

/**
 * Resolve a `SketchfabSlug` to a local `File` via [SketchfabAssetResolver].
 *
 * Returns `null` while the resolver is still downloading / staging the
 * bundled fallback. Once the resolver returns, the [File] is the streamed
 * GLB (or the bundled fallback if the network/key was unavailable).
 *
 * Wrapped in a helper so the `MultiModelDemo` body stays focused on the
 * scene composition — the resolve plumbing is the same for every slug.
 */
@Composable
private fun rememberSlugFile(slug: SketchfabSlug?): File? {
    if (slug == null) return null
    val context = LocalContext.current
    return produceState<File?>(initialValue = null, key1 = slug.uid) {
        value = runCatching {
            SketchfabAssetResolver.getInstance(context).resolve(slug)
        }.getOrNull()
    }.value
}

/**
 * Load a [io.github.sceneview.model.ModelInstance] from a nullable local [File],
 * returning `null` until the file is ready.
 *
 * The resolver always hands back a real on-disk [File] (streamed GLB or staged
 * bundled fallback), so the model must be loaded through
 * [io.github.sceneview.loaders.ModelLoader.loadModelInstance], which understands
 * `file://` URIs. The two-argument `rememberModelInstance(modelLoader, String)`
 * call is **not** usable here: Kotlin overload resolution binds it to the
 * asset-path overload (the one without a defaulted `resourceResolver`), which
 * feeds the `file://` string straight to `AssetManager.open` — that throws
 * `FileNotFoundException`, the instance stays `null`, and the demo hangs forever
 * on "Loading 4 models…" (#1422). Loading via `produceState` + `loadModelInstance`
 * keeps the Filament JNI work on the loader's own Main-thread hop.
 */
@Composable
private fun rememberFileModelInstance(
    modelLoader: io.github.sceneview.loaders.ModelLoader,
    file: File?,
): io.github.sceneview.model.ModelInstance? {
    if (file == null) return null
    return produceState<io.github.sceneview.model.ModelInstance?>(
        initialValue = null,
        key1 = modelLoader,
        key2 = file.absolutePath,
    ) {
        value = runCatching {
            modelLoader.loadModelInstance("file://${file.absolutePath}")
        }.getOrNull()
    }.value
}
