package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberHeroOrbitCameraManipulator
import io.github.sceneview.demo.sketchfab.SketchfabAssetResolver
import io.github.sceneview.demo.sketchfab.SketchfabConfig
import io.github.sceneview.demo.sketchfab.SketchfabService
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.launch

/**
 * Full-screen 3D model viewer.
 *
 * **Default state.** Loads the bundled `khronos_damaged_helmet.glb` so the demo
 * renders identically with or without a Sketchfab API key — the very first
 * frame the user sees is the same hero shot the screenshots and store assets
 * promise. The CAMERA orbits the helmet so lights, reflections and IBL hit the
 * same surface every frame.
 *
 * **"Surprise me" button.** When the user taps the extended FAB at the
 * bottom-right, the demo searches the Sketchfab API for a downloadable model
 * tagged like the previous pick (or just downloadable PBR content on first
 * tap), then routes the resulting URL through SceneView's `file://` model
 * loader. The streamed pick replaces the helmet for the rest of the session
 * (or until the next tap). When no API key is configured (App Store builds),
 * the button is hidden — there is no plausible "Surprise me" without the
 * Sketchfab catalogue, and showing a non-functional button would mislead.
 *
 * The moment the user touches the viewport the orbit hands off to the stock
 * [io.github.sceneview.gesture.CameraGestureDetector.DefaultCameraManipulator]
 * at the exact same pose, so there's no snap — drag / pinch / zoom continue
 * from where the automated orbit left off.
 */
@Composable
fun ModelViewerDemo(onBack: () -> Unit) {
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
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
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
    // When there is no active stream we feed it an empty path: the URL overload
    // sees a scheme-less location, the asset reader fails fast, and it returns
    // null — no model is loaded and the bundled helmet keeps rendering.
    val instance = rememberModelInstance(modelLoader, streamedFileUrl ?: "")
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
