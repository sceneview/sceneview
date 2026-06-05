package io.github.sceneview.demo.demos

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.fitDistanceForBounds
import io.github.sceneview.model.model
import io.github.sceneview.toAabb
import io.github.sceneview.verticalFovDegreesForFocalLength
import io.github.sceneview.demo.AssetSourceState
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.ErrorScrim
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.initialDemoMode
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberHeroOrbitCameraManipulator
import io.github.sceneview.demo.rememberHeroYaw
import io.github.sceneview.demo.sketchfab.SampleAssets
import io.github.sceneview.demo.sketchfab.SketchfabAssetResolver
import io.github.sceneview.demo.sketchfab.SketchfabConfig
import io.github.sceneview.demo.sketchfab.SketchfabService
import io.github.sceneview.demo.sketchfab.SketchfabSlug
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.launch
import java.io.File

/**
 * Unified "Models" demo — consolidates the retired `multi-model` and
 * `scene-gallery` demos into the existing `model-viewer` entry behind a single
 * segmented-button toggle (#2239 Batch 5).
 *
 * Each sub-mode showcases one facet of loading + displaying glTF models:
 *
 * - **Single Model** (default) — the canonical full-screen 3D model viewer:
 *   bundled hero helmet, auto-fit hero orbit, optional "Surprise me" Sketchfab
 *   stream. (The original `model-viewer` demo — the flagship example.)
 * - **Multi-Model** — a themed "Park" scene composed from 4 streamed glTF
 *   assets with per-model visibility chips and a spin toggle. (Formerly
 *   `multi-model`.)
 * - **Gallery** — a chip-picked gallery of themed Sketchfab CC-BY models, one
 *   on screen at a time on an automated orbit. (Formerly `scene-gallery`.)
 *
 * Each sub-mode keeps its own `SceneView` + its own [rememberEngine] / loaders,
 * so switching tabs tears down the inactive section completely — no engine is
 * hoisted above the `when`, which is what prevents resource leaks across tab
 * switches (Batch 1 review confirmed this pattern). Old deep links route
 * through [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES]; the
 * `model-viewer` id itself stays a live registered demo (the flagship
 * umbrella — its id and `ModelViewerDemo.kt` file are referenced across docs
 * and kept verbatim).
 */
@Composable
fun ModelViewerDemo(onBack: () -> Unit) {
    var mode by remember {
        mutableStateOf(initialDemoMode(ModelViewerMode.entries, ModelViewerMode.Single))
    }
    when (mode) {
        ModelViewerMode.Single -> SingleModelSection(onBack, mode) { mode = it }
        ModelViewerMode.Multi -> MultiModelSection(onBack, mode) { mode = it }
        ModelViewerMode.Gallery -> GallerySection(onBack, mode) { mode = it }
    }
}

private enum class ModelViewerMode(val label: String) {
    Single("Single Model"),
    Multi("Multi-Model"),
    Gallery("Gallery"),
}

@Composable
private fun ModeSelector(
    current: ModelViewerMode,
    onModeChange: (ModelViewerMode) -> Unit,
) {
    val modes = ModelViewerMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, m ->
            SegmentedButton(
                selected = m == current,
                onClick = { onModeChange(m) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                label = { Text(m.label) },
            )
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
}

// ─── Single Model section ─────────────────────────────────────────────────────
// The original `model-viewer` demo — the flagship full-screen 3D model viewer.
//
// **Default state.** Loads the bundled `khronos_damaged_helmet.glb` so the demo
// renders identically with or without a Sketchfab API key — the very first
// frame the user sees is the same hero shot the screenshots and store assets
// promise. The CAMERA orbits the helmet so lights, reflections and IBL hit the
// same surface every frame.
//
// **"Surprise me" button.** When the user taps the extended FAB at the
// bottom-right, the demo searches the Sketchfab API for a downloadable model
// tagged like the previous pick (or just downloadable PBR content on first
// tap), then routes the resulting URL through SceneView's `file://` model
// loader. The streamed pick replaces the helmet for the rest of the session
// (or until the next tap). When no API key is configured (App Store builds),
// the button is hidden — there is no plausible "Surprise me" without the
// Sketchfab catalogue, and showing a non-functional button would mislead.
//
// The moment the user touches the viewport the orbit hands off to the stock
// CameraGestureDetector.DefaultCameraManipulator at the exact same pose, so
// there's no snap — drag / pinch / zoom continue from where the automated
// orbit left off.

@Composable
private fun SingleModelSection(
    onBack: () -> Unit,
    mode: ModelViewerMode,
    onModeChange: (ModelViewerMode) -> Unit,
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // Source of truth for the currently-viewed model:
    //  - null            → render the bundled hero helmet (default state).
    //  - non-null URL    → render the streamed Sketchfab GLB.
    // Restored via remember (savedStateRegistry would survive config change
    // but we want the helmet back on every cold start so screenshots / Play
    // Store store-page assets stay deterministic).
    var streamedFileUrl by remember { mutableStateOf<String?>(null) }
    // Last-tapped state — when it's `true` the FAB shows a spinner. The
    // surprise-coroutine flips it back to `false` regardless of success so
    // the button doesn't get stuck in the loading state.
    var surpriseInFlight by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context) { SketchfabService.getInstance(context) }
    val resolver = remember(context) { SketchfabAssetResolver.getInstance(context) }
    val hasSketchfabKey = remember { SketchfabConfig.apiKey != null }

    // The streamed model is loaded via the URL overload. This MUST be called
    // unconditionally — wrapping a @Composable in `streamedFileUrl?.let { }`
    // makes the composer group appear/disappear with `streamedFileUrl`, so the
    // `produceState` inside `rememberModelInstance` lands in an unstable slot.
    // The State it returns then fails to invalidate the scope that reads
    // `streamedModelInstance` when the load completes, leaving `assetSource`
    // pinned at `Streaming` for the whole session even though the model is
    // loaded and interactive (#1464). `rememberStreamedModelInstance` keeps the
    // call site stable and simply returns null while no stream is active.
    val streamedModelInstance =
        rememberStreamedModelInstance(modelLoader, streamedFileUrl)
    // The bundled hero — assets/models/khronos_damaged_helmet.glb. Loaded
    // eagerly so the first frame after launch shows the hero shot.
    val bundledModelInstance =
        rememberModelInstance(modelLoader, "models/khronos_damaged_helmet.glb")

    // The instance actually rendered this frame. Falls back to the bundled
    // helmet whenever the streamed instance is null (no Surprise tap yet,
    // streamed load still in flight, or streamed load failed).
    val activeModelInstance = streamedModelInstance ?: bundledModelInstance

    // Auto-fit camera framing (#1439): instead of a per-demo hand-tuned orbit
    // radius, compute the distance at which the *current* model's bounding
    // sphere exactly fills the viewport. A 5 cm bee and a 5 m crate both end
    // up comfortably framed without touching `scaleToUnits` — the camera
    // adapts to the model, not the other way round.
    //
    // The framing uses the library helper `fitDistanceForBounds`, fed with the
    // model's intrinsic glTF bounds (`model.boundingBox`). The portrait phone
    // aspect (~0.5) and the default 28 mm lens FOV match the stock SceneView
    // camera the hero orbit drives. We also read the bounds' centre so the
    // ModelNode can be translated to put that centre on the world origin the
    // hero orbit pivots around — many glTF models have an off-origin pivot.
    val framing = remember(activeModelInstance) {
        val instance = activeModelInstance ?: return@remember null
        val bounds = runCatching { instance.model.boundingBox.toAabb() }.getOrNull()
        if (bounds == null || bounds.isEmpty) {
            null
        } else {
            ModelFraming(
                radius = fitDistanceForBounds(
                    bounds = bounds,
                    verticalFovDegrees = verticalFovDegreesForFocalLength(28.0),
                    aspect = 0.5,
                ).coerceIn(0.2f, 50f),
                center = bounds.center,
            )
        }
    }

    // Auto-fit orbit radius for the current model — falls back to 1.4 m while
    // the bounds are not yet measurable. The "Camera distance" slider below
    // lets the user override this; `null` slider state means "use auto-fit".
    val autoFitRadius = framing?.radius ?: 1.4f

    // Camera-distance slider state. Wired directly to [DemoSettings.cameraDistance]
    // — the SAME global override that `rememberHeroOrbitCameraManipulator` reads
    // for the `--ef camera_distance <f>` / `?cameraDistance=<f>` deep-link hook
    // (#1571). So a deep link launches this demo at the requested zoom AND the
    // slider reflects it; dragging the slider drives the live camera distance.
    // `null` ⇒ no override, the auto-fit `radius` is used. Maestro has no pinch
    // gesture, so this slider is the only way QA flows can exercise zoom.
    val sliderDistance = DemoSettings.cameraDistance

    // Camera orbits; model stays fixed. The orbit radius is auto-fit to the
    // model's intrinsic size (see `framing` above) so every model — bundled
    // helmet or streamed Sketchfab pick — is framed identically. A non-null
    // `DemoSettings.cameraDistance` (slider or deep link) overrides it.
    val cameraManipulator = rememberHeroOrbitCameraManipulator(
        trigger = activeModelInstance != null,
        radius = autoFitRadius,
        yHeight = 0f,
        durationMillis = 20_000,
    )

    // Per-demo offline indicator chip (#1152 Stage 3): hide while we're on
    // the bundled hero only (no Surprise tap yet). Once the user kicks a
    // streamed roll the chip surfaces "Streaming…" → "Streamed (cached)".
    // When no key is configured, "Surprise me" is disabled in the controls
    // and we never enter the streaming branch — chip stays hidden.
    val assetSource = when {
        streamedFileUrl == null -> null
        streamedModelInstance == null -> AssetSourceState.Streaming
        else -> AssetSourceState.Streamed
    }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_model_viewer),
        onBack = onBack,
        assetSource = assetSource,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ModeSelector(mode, onModeChange)
            // Camera-distance slider — makes zoom discoverable without a pinch
            // gesture (and Maestro-testable, see #1571). The displayed value is
            // the slider override when set, otherwise the live auto-fit radius.
            Text(
                "Camera distance: %.1f m".format(sliderDistance ?: autoFitRadius),
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = sliderDistance ?: autoFitRadius,
                onValueChange = { DemoSettings.cameraDistance = it },
                valueRange = 0.5f..10f
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                modelLoader = modelLoader,
                environmentLoader = environmentLoader,
                environment = rememberModelDemoEnvironment(environmentLoader),
                cameraManipulator = cameraManipulator,
            ) {
                activeModelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        // No `scaleToUnits` — the model renders at its true
                        // glTF size. Auto-fit framing (#1439) adapts the orbit
                        // radius to that intrinsic size instead, so a 5 m crate
                        // and a 5 cm bee are both framed identically without
                        // squashing every model to a fixed unit cube.
                        //
                        // Translate the model so its bounding-box centre lands
                        // on the world origin the hero orbit pivots around —
                        // glTF pivots are often off-centre. `framing.center` is
                        // the bounds centre the same auto-fit pass measured.
                        position = framing?.let { -it.center }
                            ?: io.github.sceneview.math.Position(0f, 0f, 0f),
                    )
                }
            }

            LoadingScrim(
                loading = activeModelInstance == null,
                label = stringResource(R.string.demo_model_viewer_loading),
            )

            // Surprise FAB lives only when the user has a Sketchfab API key —
            // tapping it without a key would silently fall back to the same
            // bundled helmet, which is worse than no button at all.
            if (hasSketchfabKey) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (surpriseInFlight) return@ExtendedFloatingActionButton
                        surpriseInFlight = true
                        scope.launch {
                            val picked = runCatching {
                                pickRandomDownloadableModel(service, resolver)
                            }.getOrNull()
                            // Even on failure we exit the in-flight state so
                            // the user can retry. The helmet stays put.
                            streamedFileUrl = picked
                            surpriseInFlight = false
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                        )
                    },
                    text = {
                        Text(
                            text = if (surpriseInFlight) {
                                stringResource(R.string.demo_model_viewer_surprise_loading)
                            } else {
                                stringResource(R.string.demo_model_viewer_surprise)
                            },
                        )
                    },
                    // Bottom-START (#2350). The DemoScaffold's Settings FAB / peek
                    // chip is pinned to the bottom-END corner with the same 16 dp
                    // padding, so a bottom-END placement here had the round Settings
                    // control sitting on top of the extended FAB and clipping its
                    // "Surprise me" label to "Surprise…". Bottom-start keeps the two
                    // controls in opposite corners. `systemBars` inset padding lifts it
                    // above the nav bar, matching the Settings column.
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(16.dp),
                )
            }
        }
    }
}

/**
 * Auto-fit framing parameters for the currently displayed model (#1439).
 *
 * @property radius Orbit distance, in metres, at which the model's bounding sphere fills the
 *   viewport — computed by `io.github.sceneview.fitDistanceForBounds` from the model's intrinsic
 *   glTF bounds.
 * @property center Bounding-box centre of the model in its own local space. The `ModelNode` is
 *   translated by `-center` so the centre lands on the world origin the hero orbit pivots around.
 */
private data class ModelFraming(
    val radius: Float,
    val center: io.github.sceneview.math.Position,
)

/**
 * Loads the streamed Sketchfab model for [streamedFileUrl], or returns `null`
 * when no stream is active (`streamedFileUrl == null`).
 *
 * Why a dedicated helper instead of `streamedFileUrl?.let { rememberModelInstance(...) }`:
 * a `@Composable` invoked inside `?.let` is a **conditional** call — the
 * composer group for `rememberModelInstance`'s internal `produceState` only
 * exists while `streamedFileUrl` is non-null. When the load finishes and
 * `produceState` emits the loaded instance, the snapshot State sits in that
 * conditionally-present group and does not reliably invalidate the caller that
 * reads the result. The `assetSource` chip therefore stayed stuck on
 * `Streaming` even after the model was fully loaded and interactive (#1464).
 *
 * Calling `rememberModelInstance` unconditionally here keeps its group in a
 * fixed slot, so the State invalidates the caller correctly and the chip
 * transitions `Streaming → Streamed` the moment the model is ready. The empty
 * sentinel path returns `null` without ever touching the loader.
 */
@Composable
private fun rememberStreamedModelInstance(
    modelLoader: io.github.sceneview.loaders.ModelLoader,
    streamedFileUrl: String?,
): io.github.sceneview.model.ModelInstance? {
    // rememberModelInstance is called on every recomposition, in a stable slot.
    // The named `fileLocation =` argument binds to the URL-capable overload — without it
    // the two-arg positional call binds to the asset-path overload, which feeds the
    // `cached.toURI()` `file://…` URL straight to `AssetManager.open`; that throws, the
    // instance stays `null`, and "Surprise me" silently never swaps in the streamed model
    // (#1422 / the #2302 overload trap). When there is no active stream we feed it an empty
    // path: the URL overload sees a scheme-less location, delegates to the asset reader,
    // which fails fast and returns null — the bundled helmet keeps rendering.
    val instance = rememberModelInstance(modelLoader, fileLocation = streamedFileUrl ?: "")
    return if (streamedFileUrl == null) null else instance
}

/**
 * Surprise-me coroutine. Hits the Sketchfab search API for downloadable PBR
 * content, picks a random hit, streams it through [SketchfabService.downloadModel]
 * → on-disk cache → `file://` URL. Failure modes (no results / rate limit /
 * non-PBR download) all return `null` and the FAB caller leaves the helmet on
 * screen, so the demo never sits on a black viewport.
 */
private suspend fun pickRandomDownloadableModel(
    service: SketchfabService,
    @Suppress("UNUSED_PARAMETER") resolver: SketchfabAssetResolver,
): String? {
    // Search a broad PBR-friendly query so the picks read well under the demo
    // lighting. Falls back to "modern" if "pbr" returns 0 hits for some reason.
    val candidates = listOf("pbr", "modern", "scan")
    @Suppress("LoopWithTooManyJumpStatements") // continue-guards replace nested ifs for readability
    for (query in candidates) {
        val results = runCatching {
            service.search(query = query, downloadable = true, limit = 24)
        }.getOrNull() ?: continue
        // Filter to PBR-ish, sub-50k-poly hits so the demo doesn't stall on
        // a 5 M-poly scan. faceCount = 0 happens for non-PBR models — keep
        // them out.
        val viable = results.filter { it.downloadable && it.faceCount in 1..200_000 }
        if (viable.isEmpty()) continue
        val pick = viable.random()
        val cached = runCatching { service.downloadModel(pick.uid) }.getOrNull()
            ?: continue
        return cached.toURI().toString()
    }
    return null
}

// ─── Multi-Model section ──────────────────────────────────────────────────────
// Formerly MultiModelDemo (id `multi-model`).
//
// Composes a themed "Park" scene from 4 streamed glTF assets — an oak tree
// (the backdrop), a park bench (the foreground prop), a sleeping dog (the
// animated occupant), and a perched songbird (the second animated occupant).
//
// Lighting comes from `studio_warm_2k.hdr` — a soft golden-hour wash that
// unifies the four very different materials (bark, weathered wood, fur,
// feathers) into one cohesive open-air display.
//
// Controls:
// - Visibility chips per model (toggle individual nodes off / on)
// - "Spin scene" toggle — slow circular auto-rotation of the whole formation,
//   lets the viewer walk around the display without touching the screen
//
// The previous "tabletop" composition (shiba + lantern + helmet + dragon, all
// bundled) is replaced by the streamed `park` category from [SampleAssets].
// Offline fallback is per-slug (`shiba.glb` / `khronos_lantern.glb` /
// `threejs_soldier.glb` etc.) so the demo still renders four nodes when no
// Sketchfab key is configured — the visual swap is documented in the CHANGELOG
// but the user-visible behaviour stays "4 nodes, 4 chips, 1 spin toggle".
//
// Streaming pipeline (Stage 2, issue #1152) — the resolver returns the
// downloaded GLB or the registered bundled fallback (see [SketchfabAssetResolver]
// Kdoc). The whole scene is keyed by the slug uid, so a registry edit
// re-resolves exactly the affected nodes.

@Composable
private fun MultiModelSection(
    onBack: () -> Unit,
    mode: ModelViewerMode,
    onModeChange: (ModelViewerMode) -> Unit,
) {
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
            ModeSelector(mode, onModeChange)
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
                        // Models are placed by `position`; their formation is centred on the
                        // bounding box, not ground-anchored. A previous `centerOrigin =
                        // Position(0, 0.5, 0)` here was a silent no-op (the composable discarded
                        // it — fixed library-side) and was removed to keep this scene's framing
                        // byte-for-byte identical. (Adopting the now-working centerOrigin to sit
                        // each model on a ground plane is a separate, visually-QA'd enhancement.)
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
 * Wrapped in a helper so the Multi-Model section body stays focused on the
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

// ─── Gallery section ──────────────────────────────────────────────────────────
// Formerly SceneGalleryDemo (id `scene-gallery`).
//
// Streamed model gallery — themed bundles (Animals, Furniture, Retro, …) rotating
// Sketchfab CC-BY content. Each chip selects one [SketchfabSlug] in the curated
// `gallery` category of [SampleAssets]; the resolver hands back either the
// streamed GLB or the bundled fallback when no API key is configured. The
// `SceneView` composable then renders the model with an automated orbit camera.
//
// Honours the umbrella's hard rules:
//  - **No Sketchfab WebView / external link** — the demo only ever points
//    [rememberModelInstance] at the local [java.io.File] returned by
//    [SketchfabAssetResolver.resolve].
//  - **No network required to render something useful** — empty key (App Store
//    cold-cache builds) → the resolver stages the bundled fallback under the
//    same cache root and the demo renders it the same way as the streamed file.
//  - **License attribution preserved** — the per-chip caption shows the author
//    name. The Credits sheet (Stage 3) will surface the full per-model
//    attribution.
//
// The chip labels come from [SketchfabSlug.displayName] (set by registry
// curators in English at design time) — they're not user-facing copy strings
// subject to translation. Authors and license URLs are user-data of the
// Sketchfab catalogue itself; only the demo scaffolding (title / subtitle /
// loading copy) goes through `stringResource()`.

@Composable
private fun GallerySection(
    onBack: () -> Unit,
    mode: ModelViewerMode,
    onModeChange: (ModelViewerMode) -> Unit,
) {
    val context = LocalContext.current
    val resolver = remember(context) { SketchfabAssetResolver.getInstance(context) }

    // The four curated `gallery` slugs declared in SampleAssets. Stage 2 keeps
    // the chip count low so the offline-fallback footprint stays bounded — Stage
    // 2 follow-ups can fan out to ~10 via Sketchfab `search()`.
    val slugs = remember { SampleAssets.byCategory["gallery"].orEmpty() }
    var selectedIndex by remember { mutableStateOf(0) }
    val selectedSlug = slugs.getOrNull(selectedIndex)

    // Bumped by the error scrim's Retry button. It is a `produceState` key so a
    // tap re-runs the resolve coroutine for the same slug instead of leaving the
    // demo stuck on a failed resolution forever (#2088).
    var retryTick by remember { mutableStateOf(0) }

    // Warm every gallery slug in parallel on first frame so chip taps switch
    // instantly after the cold-start download. The resolver is idempotent
    // (cache-hit -> touches lastModified only) so re-running is cheap.
    LaunchedEffect(resolver) {
        runCatching { resolver.prefetchAll("gallery") }
    }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // Resolve the slug to a local file. produceState delegates IO + retries to
    // the resolver; the `key1 = selectedSlug` rebinds the file when the user
    // picks a new chip without leaking any prior coroutine. A failed resolve is
    // surfaced as [GalleryResolveState.Error] instead of being swallowed into a
    // `null` path that hangs the loading scrim forever (#2088). `retryTick`
    // re-runs the resolve when the user taps Retry.
    val resolveState: GalleryResolveState by produceState<GalleryResolveState>(
        initialValue = GalleryResolveState.Loading,
        key1 = resolver,
        key2 = selectedSlug?.uid,
        key3 = retryTick,
    ) {
        value = GalleryResolveState.Loading
        val slug = selectedSlug ?: return@produceState
        value = runCatching { resolver.resolve(slug) }
            .fold(
                onSuccess = { GalleryResolveState.Resolved(it) },
                onFailure = { GalleryResolveState.Error(it.message ?: it.javaClass.simpleName) },
            )
    }
    val resolvedFile = (resolveState as? GalleryResolveState.Resolved)?.file
    val resolveError = (resolveState as? GalleryResolveState.Error)?.message

    // Load the resolved file (streamed GLB or bundled fallback) through
    // [rememberFileModelInstance] → `ModelLoader.loadModelInstance("file://…")`,
    // NOT the two-argument `rememberModelInstance(modelLoader, fileUri)`. The
    // latter binds to the asset-path overload — Kotlin prefers the candidate that
    // needs no default argument — which feeds the `file://` string straight to
    // `AssetManager.open`; that throws, the instance stays `null`, and the
    // "Streaming model…" scrim hangs forever even though the bundled fallback
    // resolved instantly offline (#2306 — same root cause as #1422 / the
    // Multi-Model section above). Called unconditionally so its `produceState`
    // slot stays stable (#1464).
    val modelInstance = rememberFileModelInstance(modelLoader, resolvedFile)

    // Per-demo offline indicator chip (#1152 Stage 3). When no API key is
    // configured we know up-front the resolver will fall back to bundled
    // GLBs; otherwise the chip mirrors the centre LoadingScrim exactly so the
    // two never contradict each other (#1465): it stays "Streaming…" until the
    // model is fully parsed into a `ModelInstance` — not merely while the file
    // path resolves — and only then flips to "Streamed (cached)". Gating on
    // `modelInstance` (the same signal the LoadingScrim uses) keeps the chip
    // and the spinner in lockstep.
    val assetSource = when {
        slugs.isEmpty() -> null
        SketchfabConfig.apiKey == null -> AssetSourceState.Bundled
        modelInstance == null -> AssetSourceState.Streaming
        else -> AssetSourceState.Streamed
    }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_scene_gallery_title),
        onBack = onBack,
        assetSource = assetSource,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ModeSelector(mode, onModeChange)
            // Category chips along the top of the controls sheet. We expose
            // them as a horizontally scrolling row so the four labels never
            // wrap at portrait phone widths. Each chip's label is a hand-
            // curated English `displayName` from SampleAssets — these are
            // catalogue identifiers, not localizable UI copy.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                slugs.forEachIndexed { index, slug ->
                    FilterChip(
                        selected = index == selectedIndex,
                        onClick = { selectedIndex = index },
                        label = { Text(slug.displayName) },
                    )
                }
            }
            // Author credit — required by CC-BY 4.0 attribution. Stage 3 adds
            // a full Credits sheet; the inline byline below keeps the
            // attribution visible without a tap.
            selectedSlug?.let { slug ->
                Text(
                    text = stringResource(R.string.demo_scene_gallery_credit, slug.author),
                )
            }
        },
    ) {
        // Hero orbit so lighting + reflections sweep over the same surface
        // every frame — same camera contract as the Single Model section, just
        // bound to a different model. yHeight = 0 keeps the model centered in
        // portrait without the empty-top-band artefact (QA finding
        // 2026-05-11).
        val cameraManipulator = rememberHeroOrbitCameraManipulator(
            trigger = modelInstance != null,
            radius = 1.6f,
            yHeight = 0f,
            durationMillis = 24_000,
        )
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                modelLoader = modelLoader,
                environmentLoader = environmentLoader,
                environment = rememberModelDemoEnvironment(environmentLoader),
                cameraManipulator = cameraManipulator,
            ) {
                val instance = modelInstance
                val slug = selectedSlug
                if (instance != null && slug != null) {
                    // Switching chips swaps the model in this single slot. The previous
                    // model is disposed correctly on every switch (its `onDispose` runs and
                    // `SceneNodeManager.removeNode` removes all of its renderable entities —
                    // verified on device: `Scene.getRenderableCount()` drops to the new
                    // model's count). The earlier "stacking" symptom was NOT a disposal bug:
                    // the IBL-only environment has no skybox, so the Filament swap chain was
                    // never cleared and the previous (larger) model's uncovered pixels lingered
                    // on screen. Fixed library-side (#2400) by clearing the color buffer every
                    // frame in `SceneView`; no demo-side `key()` wrapper is needed.
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = slug.scaleToUnits,
                        // centerOrigin lets SceneView re-centre the model on the
                        // world origin so the camera (looking at 0,0,0) frames
                        // the body, not the model's authored pivot point.
                        centerOrigin = Position(0f, 0f, 0f),
                    )
                }
            }
            // Mutually exclusive with LoadingScrim: a resolve failure shows the
            // error scrim (with Retry) instead of hanging on "Streaming…" (#2088).
            if (resolveError != null) {
                ErrorScrim(
                    message = resolveError,
                    onRetry = { retryTick++ },
                    label = stringResource(R.string.demo_scene_gallery_error),
                    retryLabel = stringResource(R.string.demo_scene_gallery_retry),
                )
            } else {
                LoadingScrim(
                    loading = modelInstance == null,
                    label = stringResource(R.string.demo_scene_gallery_loading),
                )
            }
        }
    }
}

/** Resolution lifecycle for a streamed gallery slug. See [GallerySection]. */
private sealed interface GalleryResolveState {
    /** Resolve coroutine in flight. */
    data object Loading : GalleryResolveState

    /** Resolve succeeded — [file] is the on-disk GLB (streamed or bundled fallback). */
    data class Resolved(val file: File) : GalleryResolveState

    /** Resolve failed — [message] is a short human-readable reason. */
    data class Error(val message: String) : GalleryResolveState
}
