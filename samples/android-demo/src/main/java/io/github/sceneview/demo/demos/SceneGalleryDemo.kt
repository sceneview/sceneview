package io.github.sceneview.demo.demos

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.demo.AssetSourceState
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.sketchfab.SketchfabConfig
import io.github.sceneview.demo.ErrorScrim
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberHeroOrbitCameraManipulator
import io.github.sceneview.demo.sketchfab.SampleAssets
import io.github.sceneview.demo.sketchfab.SketchfabAssetResolver
import io.github.sceneview.demo.sketchfab.SketchfabSlug
import io.github.sceneview.math.Position
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

/**
 * Streamed model gallery — themed bundles (Animals, Furniture, Retro, …) rotating
 * Sketchfab CC-BY content. Each chip selects one [SketchfabSlug] in the curated
 * `gallery` category of [SampleAssets]; the resolver hands back either the
 * streamed GLB or the bundled fallback when no API key is configured. The
 * `SceneView` composable then renders the model with an automated orbit camera.
 *
 * Honours the umbrella's hard rules:
 *  - **No Sketchfab WebView / external link** — the demo only ever points
 *    [rememberModelInstance] at the local [java.io.File] returned by
 *    [SketchfabAssetResolver.resolve].
 *  - **No network required to render something useful** — empty key (App Store
 *    cold-cache builds) → the resolver stages the bundled fallback under the
 *    same cache root and the demo renders it the same way as the streamed file.
 *  - **License attribution preserved** — the per-chip caption shows the author
 *    name. The Credits sheet (Stage 3) will surface the full per-model
 *    attribution.
 *
 * The chip labels come from [SketchfabSlug.displayName] (set by registry
 * curators in English at design time) — they're not user-facing copy strings
 * subject to translation. Authors and license URLs are user-data of the
 * Sketchfab catalogue itself; only the demo scaffolding (title / subtitle /
 * loading copy) goes through `stringResource()`.
 *
 * @see SampleAssets for the curated `gallery` category.
 * @see SketchfabAssetResolver for the resolve / fallback contract.
 */
@Composable
fun SceneGalleryDemo(onBack: () -> Unit) {
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
    // surfaced as [ResolveState.Error] instead of being swallowed into a `null`
    // path that hangs the loading scrim forever (#2088). `retryTick` re-runs the
    // resolve when the user taps Retry.
    val resolveState: ResolveState by produceState<ResolveState>(
        initialValue = ResolveState.Loading,
        key1 = resolver,
        key2 = selectedSlug?.uid,
        key3 = retryTick,
    ) {
        value = ResolveState.Loading
        val slug = selectedSlug ?: return@produceState
        value = runCatching { resolver.resolve(slug) }
            .fold(
                onSuccess = { ResolveState.Resolved(it.toURI().toString()) },
                onFailure = { ResolveState.Error(it.message ?: it.javaClass.simpleName) },
            )
    }
    val resolvedPath = (resolveState as? ResolveState.Resolved)?.path
    val resolveError = (resolveState as? ResolveState.Error)?.message

    val modelInstance = resolvedPath?.let { path ->
        // `rememberModelInstance(modelLoader, fileLocation)` accepts a `file://`
        // URL; the underlying SceneView API auto-detects the scheme and uses
        // `loadModelInstance` on the IO dispatcher (Filament JNI back to main
        // is handled internally — we never touch JNI off-main here).
        rememberModelInstance(modelLoader, path)
    }

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
        // every frame — same camera contract as ModelViewerDemo, just bound
        // to a different model. yHeight = 0 keeps the model centered in
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

/** Resolution lifecycle for a streamed gallery slug. See [SceneGalleryDemo]. */
private sealed interface ResolveState {
    /** Resolve coroutine in flight. */
    data object Loading : ResolveState

    /** Resolve succeeded — [path] is a `file://` URL ready for the model loader. */
    data class Resolved(val path: String) : ResolveState

    /** Resolve failed — [message] is a short human-readable reason. */
    data class Error(val message: String) : ResolveState
}
