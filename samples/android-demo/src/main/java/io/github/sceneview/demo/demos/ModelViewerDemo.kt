package io.github.sceneview.demo.demos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
import io.github.sceneview.environment.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.createDefaultCameraManipulator
import io.github.sceneview.model.model
import io.github.sceneview.toAabb
import io.github.sceneview.verticalFovDegreesForFocalLength
import io.github.sceneview.demo.AssetSourceState
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.LocalDemoChromeTopInset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.SETTINGS_FAB_RESERVED_SPACE
import io.github.sceneview.demo.common.rememberFileModelInstance
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.ErrorScrim
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.viewer.BundledViewerModel
import io.github.sceneview.demo.ui.viewer.AnimationBar
import io.github.sceneview.demo.ui.viewer.EnvironmentSheet
import io.github.sceneview.demo.ui.viewer.ModelPickerSheet
import io.github.sceneview.demo.ui.viewer.ViewerEnvironment
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.demos.internal.PARK_EYE_HEIGHT
import io.github.sceneview.demo.demos.internal.PARK_FALLBACK_ASPECT
import io.github.sceneview.demo.demos.internal.PARK_HEIGHT
import io.github.sceneview.demo.demos.internal.PARK_SLOTS
import io.github.sceneview.demo.demos.internal.ParkSlot
import io.github.sceneview.demo.demos.internal.parkCameraDistance
import io.github.sceneview.demo.initialDemoMode
import io.github.sceneview.demo.EntranceCameraManipulator
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.VIEWER_MAX_ZOOM_FACTOR
import io.github.sceneview.demo.VIEWER_MIN_ZOOM_FACTOR
import io.github.sceneview.demo.rememberFitOrbitRadius
import io.github.sceneview.demo.rememberHeroOrbitCameraManipulator
import io.github.sceneview.demo.rememberHeroYaw
import io.github.sceneview.demo.sketchfab.AssetSourceProbe
import io.github.sceneview.demo.sketchfab.SampleAssets
import io.github.sceneview.demo.sketchfab.SketchfabAssetResolver
import io.github.sceneview.demo.sketchfab.SketchfabConfig
import io.github.sceneview.demo.sketchfab.SketchfabService
import io.github.sceneview.demo.sketchfab.SketchfabSlug
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.sample.ui.LabeledSlider
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import java.util.Locale
import kotlinx.coroutines.launch
import java.io.File
import com.google.ar.core.ArCoreApk
import kotlinx.coroutines.delay
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.Animatable

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
    // Switching section drops any camera-distance override (#2913). The Single-Model slider writes
    // to the process-global `DemoSettings.cameraDistance` — that is how it drives the live camera,
    // since `rememberHeroOrbitCameraManipulator` reads the override itself (#1571) — and the
    // Multi-Model section honours the same override. Without this reset, dragging the slider down
    // to 0.5 m and then tapping "Multi-Model" put the camera inside the formation, and that section
    // exposes no slider to undo it. A cold launch never passes through here, so the `--ef
    // camera_distance` / `?cameraDistance=` deep link keeps working for either section.
    val onModeChange: (ModelViewerMode) -> Unit = { next ->
        if (next != mode) DemoSettings.cameraDistance = null
        mode = next
    }
    when (mode) {
        ModelViewerMode.Single -> SingleModelSection(onBack, mode, onModeChange)
        ModelViewerMode.Multi -> MultiModelSection(onBack, mode, onModeChange)
        ModelViewerMode.Gallery -> GallerySection(onBack, mode, onModeChange)
    }
}

/**
 * Frames queued with a fully loaded model instance before the cover-releasing `flushAndWait`.
 * Not 1: the scene parents DSL nodes through an async `snapshotFlow`, so the first frame after
 * the instance lands can be drawn without the `ModelNode`; the flush must wait on a frame that
 * includes it.
 */
private const val MODEL_COVER_FRAMES = 3

/**
 * Length of the camera fly-in when the model lands (#3406). Twice `duration-medium`:
 * a screen transition is 350 ms, but this one is the subject arriving rather than a
 * surface changing, and under 500 ms the dolly reads as a stutter instead of a move.
 * Long enough to be seen, short enough that the first drag is never waiting on it —
 * and a touch cancels it outright.
 */
private const val CAMERA_ENTRANCE_MILLIS = 700

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
// bottom-start, the demo searches the Sketchfab API for a downloadable model
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
    val bundledModels = remember { listOf(
        // #3324 — the two untextured low-poly rows (Fox, Shiba) are out: in a full-screen
        // PBR viewer they are the two models that make the SDK look worse than it is. The
        // three that take their place each exercise a different material extension
        // (sheen, sheen + specular, iridescence + transmission + volume). Both GLBs stay
        // bundled — `SampleAssets` fallbacks and `ARGeospatialAnchorsDemo` still load them.
        BundledViewerModel("models/khronos_damaged_helmet.glb", "Damaged Helmet"),
        BundledViewerModel("models/khronos_glam_velvet_sofa.glb", "Velvet Sofa"),
        BundledViewerModel("models/khronos_sheen_chair.glb", "Sheen Chair"),
        BundledViewerModel("models/khronos_iridescent_dish.glb", "Olive Dish"),
        BundledViewerModel("models/khronos_lantern.glb", "Lantern"),
        BundledViewerModel("models/khronos_toy_car.glb", "Toy Car"),
        BundledViewerModel("models/threejs_soldier.glb", "Soldier"),
    ) }
    var selectedModel by remember { mutableStateOf(bundledModels.first()) }
    var modelSheetOpen by remember { mutableStateOf(false) }
    var environmentSheetOpen by remember { mutableStateOf(false) }
    // Chinese Garden leads, and the flagship viewer opens on it (#3402). The old default —
    // `studio_2k` — is a grey box with white softboxes: correct light, no colour, and on the
    // near-black stage the hero read as a grey object on a black field. Measured on the
    // emulator against every bundled HDR: `studio_warm` is the same picture a shade warmer,
    // `sunset` reflects mostly pale sky and washes the model out, `outdoor_cloudy` is flat
    // by construction, and the two night maps are darker than the stage. The garden is the
    // one that makes a PBR viewer look like a PBR viewer — green canopy and blue sky across
    // the chrome, a hard sun glint, real depth in the visor — and it is bright enough that
    // the model never sinks into the stage in either theme. The list still leads with the
    // default so "Reset lighting" is the first tile.
    val viewerEnvironments = remember { listOf(
        ViewerEnvironment("environments/chinese_garden_2k.hdr", "Chinese Garden"),
        ViewerEnvironment("environments/sunset_2k.hdr", "Sunset"),
        ViewerEnvironment("environments/studio_2k.hdr", "Studio"),
        ViewerEnvironment("environments/studio_warm_2k.hdr", "Studio Warm"),
        ViewerEnvironment("environments/outdoor_cloudy_2k.hdr", "Outdoor Cloudy"),
        ViewerEnvironment("environments/night_sky_2k.hdr", "Night Sky"),
        ViewerEnvironment("environments/rooftop_night_2k.hdr", "Rooftop Night"),
    ) }
    var requestedEnvironment by remember { mutableStateOf(viewerEnvironments.first()) }
    var iblIntensity by remember { mutableStateOf(1f) }
    var showEnvironment by remember { mutableStateOf(false) }
    var recenterGeneration by remember { mutableStateOf(0) }
    var spinScene by remember { mutableStateOf(false) }
    var animationBarOpen by remember { mutableStateOf(false) }
    var animationPlaying by remember { mutableStateOf(true) }
    var selectedAnimation by remember { mutableStateOf(0) }
    var animationProgress by remember { mutableStateOf(0f) }

    // Source of truth for the currently-viewed model:
    //  - null            → render the bundled hero helmet (default state).
    //  - non-null URL    → render the streamed Sketchfab GLB.
    // Restored via remember (savedStateRegistry would survive config change
    // but we want the helmet back on every cold start so screenshots / Play
    // Store store-page assets stay deterministic).
    // "Open with SceneView" (#3482) — a `.3mf` / `.glb` / `.gltf` another app handed over,
    // already staged into the cache as a `file://` path by `OpenedModelIntent`. It rides the
    // same override the Sketchfab stream uses (`rememberModelInstance` resolves a `file://`
    // location exactly like an http one), so an opened file arrives on the flagship viewer with
    // its framing, lighting, animation bar and View-in-AR handoff already working — rather than
    // on a second, poorer screen written to say the same thing.
    val openedModel = remember { DemoSettings.openedModel.also { DemoSettings.openedModel = null } }
    var streamedFileUrl by remember { mutableStateOf<String?>(openedModel?.location) }
    // Last-tapped state — when it's `true` the FAB shows a spinner. The
    // surprise-coroutine flips it back to `false` regardless of success so
    // the button doesn't get stuck in the loading state.
    var surpriseInFlight by remember { mutableStateOf(false) }

    // `DemoSettings.cameraDistance` is process-global — Geometry, Camera & Gestures and the Park
    // section all read it. Until #3426 only the slider could write it, which is a deliberate act;
    // now a pinch does too, so the section has to clean up after itself or a two-finger gesture
    // here would silently re-frame an unrelated demo three taps later. A cold launch never passes
    // through the dispose, so the `--ef camera_distance` / `?cameraDistance=` deep link is intact.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { DemoSettings.cameraDistance = null }
    }

    val context = LocalContext.current
    val arSupported by produceState<Boolean?>(initialValue = null, context) {
        var availability = ArCoreApk.getInstance().checkAvailability(context)
        repeat(20) {
            if (availability != ArCoreApk.Availability.UNKNOWN_CHECKING) return@repeat
            delay(100)
            availability = ArCoreApk.getInstance().checkAvailability(context)
        }
        value = availability == ArCoreApk.Availability.SUPPORTED_INSTALLED ||
            availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ||
            availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD
    }
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
    val bundledModelInstance = rememberModelInstance(modelLoader, selectedModel.assetPath)

    // The instance actually rendered this frame. Falls back to the bundled
    // helmet whenever the streamed instance is null (no Surprise tap yet,
    // streamed load still in flight, or streamed load failed).
    val activeModelInstance = streamedModelInstance ?: bundledModelInstance
    val animationNames = remember(activeModelInstance) {
        val animator = activeModelInstance?.animator ?: return@remember emptyList()
        (0 until animator.animationCount).map { animator.getAnimationName(it).takeIf(String::isNotBlank) ?: "Clip ${it + 1}" }
    }
    LaunchedEffect(activeModelInstance, selectedAnimation, animationPlaying) {
        val animator = activeModelInstance?.animator ?: return@LaunchedEffect
        // gltfio's Animator has no bounds check: querying a clip on a model
        // without animations is a native null dereference, not an exception.
        if (selectedAnimation !in 0 until animator.animationCount) return@LaunchedEffect
        val duration = animator.getAnimationDuration(selectedAnimation).takeIf { it > 0f } ?: return@LaunchedEffect
        var start = 0L
        while (animationPlaying) {
            withFrameNanos { now ->
                if (start == 0L) start = now - (animationProgress * duration * 1_000_000_000L).toLong()
                val seconds = (now - start) / 1_000_000_000f
                animationProgress = (seconds % duration) / duration
                animator.applyAnimation(selectedAnimation, animationProgress * duration)
                animator.updateBoneMatrices()
            }
        }
    }

    // Auto-fit camera framing (#1439, reworked after QA round 3). The model is rendered at its
    // true glTF size and is never moved: `DemoMath.viewerFraming` places the CAMERA from the
    // instance's real AABB so the model spans ~65 % of the band the chrome leaves visible
    // (identity row → dock), centred on its bounding-box centre, seen from the front (+Z) and
    // 12° above. The library's `autoCenterContent` is OFF for this scene — it would translate
    // the content root to the origin while the camera aims at the measured centre, and that
    // double offset is what put the Fox's tail in the lens.
    Box(Modifier.fillMaxSize().background(SceneViewTokens.Stage.background)) {
    // Every bundled model — the Damaged Helmet included — is framed at its loaded glTF pose.
    // The helmet's root node already carries the +90° X quaternion that stands it upright under
    // glTF +Y-up/+Z-front, so its world AABB is right as loaded; an extra -90° X here tipped it
    // crown-forward. The camera always looks from +Z, 12° above (the Khronos sample-viewer home).
    val bounds = remember(activeModelInstance) {
        val instance = activeModelInstance ?: return@remember null
        runCatching { instance.model.boundingBox.toAabb() }.getOrNull()?.takeUnless { it.isEmpty }
    }
    val modelCenter = bounds?.center ?: Position(0f, 0f, 0f)
    // Live auto-fit distance, written by the scene block (which knows the chrome insets) so the
    // "Camera distance" slider below can display it. 1.4 m until the bounds are measurable.
    var autoFitRadius by remember { mutableStateOf(1.4f) }
    // Settle drop — the model rises the last few centimetres into its resting pose.
    // Driven, with the camera entrance below, by one effect gated on the first frame
    // that actually shows the model.
    val fitProgress = remember { Animatable(0f) }
    // Camera entrance (#3406). One tween on `ease-expressive`: the camera starts wide,
    // swung off-axis and lifted, and flies to the resting framing while the model settles
    // under it. See [EntranceCameraManipulator] for the geometry.
    val entranceProgress = remember { Animatable(0f) }
    val modelYaw = rememberHeroYaw(trigger = spinScene && activeModelInstance != null, durationMillis = 20_000, staticYaw = 0f)

    // Camera-distance slider state. Wired directly to [DemoSettings.cameraDistance]
    // — the SAME global override that `rememberHeroOrbitCameraManipulator` reads
    // for the `--ef camera_distance <f>` / `?cameraDistance=<f>` deep-link hook
    // (#1571). So a deep link launches this demo at the requested zoom AND the
    // slider reflects it; dragging the slider drives the live camera distance.
    // `null` ⇒ no override, the auto-fit distance is used. Maestro has no pinch
    // gesture, so this slider is the only way QA flows can exercise zoom.
    val sliderDistance = DemoSettings.cameraDistance

    // Per-demo offline indicator chip (#1152 Stage 3): hide while we're on
    // the bundled hero only (no Surprise tap yet). Once the user kicks a
    // streamed roll the chip surfaces "Streaming…" → "Streamed (cached)".
    // When no key is configured, "Surprise me" is disabled in the controls
    // and we never enter the streaming branch — chip stays hidden.
    //
    // NOT an [AssetSourceProbe] site, deliberately — do not "finish" #2989 by routing
    // this one through it too. "Surprise me" is true random content with no registry
    // entry, so `pickRandomDownloadableModel` bypasses the resolver and calls
    // `SketchfabService.downloadModel` directly (it takes a `SketchfabAssetResolver`
    // only to satisfy its signature — the parameter is `@Suppress("UNUSED_PARAMETER")`).
    // With no registry entry there is no bundled fallback to stage: a failure yields
    // `null`, `streamedFileUrl` stays null, the chip hides and the bundled hero simply
    // stays on screen. So this chip can never render a stand-in under a "Streamed"
    // label — there is no origin to get wrong, which is the probe's entire reason to
    // exist. The other four sites go through `SketchfabAssetResolver`, whose every
    // failure path DOES end at a fallback file, and they do share the probe.
    val assetSource = when {
        // The user's own file is neither streamed nor bundled: its origin is the title bar,
        // which names the file. A "Streamed" chip over a local file would simply be false.
        openedModel != null -> null
        streamedFileUrl == null -> null
        streamedModelInstance == null -> AssetSourceState.Streaming
        else -> AssetSourceState.Streamed
    }

    val firstFrame = rememberFirstFrameState()
    // The preview cover stays up until a Filament frame that actually SHOWS the model. Three
    // signals fire too early: the first frame lands before the GLB is decoded;
    // `rememberModelInstance` returns while gltfio is still uploading textures
    // (`ModelLoader.progress < 1`); and even with the resources in, Choreographer keeps ticking
    // at 60 Hz while Filament's backend thread is still linking the model's material programs —
    // measured on the emulator: ticks resume at +0.5 s, the helmet is first presented at +3.5 s,
    // black in between. So once the instance exists, the resources report complete and a couple
    // of frames have queued the node's draw, `Engine.flushAndWait()` blocks until the backend
    // has actually executed that frame, and only then is the cover released. The cover is a
    // static image, so the wait is invisible (~100 ms on hardware, the full link time on a
    // software GL). Latched — a later model swap must not bring the helmet preview back.
    val modelFramesSeen = remember { mutableStateOf(0) }
    val hasModelRef = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    hasModelRef.set(activeModelInstance != null)
    val onFrame: (Long) -> Unit = remember(firstFrame, modelLoader, engine) {
        { nanos ->
            firstFrame.onFrame(nanos)
            if (hasModelRef.get() && modelFramesSeen.value < MODEL_COVER_FRAMES &&
                runCatching { modelLoader.progress >= 1f }.getOrDefault(true)
            ) {
                if (modelFramesSeen.value == MODEL_COVER_FRAMES - 1) runCatching { engine.flushAndWait() }
                modelFramesSeen.value++
            }
        }
    }
    // The first HDR decode runs on the main thread through Filament and stalls the UI for a
    // couple of seconds on a software GPU; releasing the cover before it lands leaves a black
    // composite on screen for that whole stall. One-way latch: later swaps re-decode but must
    // not bring the preview back.
    var firstEnvironmentLoaded by remember { mutableStateOf(false) }
    val firstModelFrame = remember {
        derivedStateOf { firstFrame.rendered.value && firstEnvironmentLoaded && modelFramesSeen.value >= MODEL_COVER_FRAMES }
    }
    // The HDR decode is a `produceState` keyed on (asset, skybox): `key` forces a fresh slot
    // per choice so a swap always re-decodes. Leaving the slot destroys the previous
    // environment's IndirectLight/Skybox, so it must never stay attached to the scene
    // (SIGSEGV in libfilament-jni) — the neutral default covers the decode instead.
    val fallbackEnvironment = rememberEnvironment(environmentLoader)
    val loadedEnvironment = key(requestedEnvironment.assetPath, showEnvironment) {
        rememberHDREnvironment(environmentLoader, requestedEnvironment.assetPath, createSkybox = showEnvironment)
    }
    val viewerEnvironment = loadedEnvironment ?: fallbackEnvironment
    if (loadedEnvironment != null) firstEnvironmentLoaded = true
    LaunchedEffect(viewerEnvironment, iblIntensity) {
        viewerEnvironment.indirectLight?.intensity = 30_000f * iblIntensity
    }
    // The arrival (#3406) — camera fly-in and model settle, started together and gated on
    // the frame that actually SHOWS the model. Keying these on `bounds` alone (what the
    // settle used to do) spent the whole animation behind the loading cover: the model was
    // measured seconds before the backend finished linking its materials, so by the time
    // anything was on screen the spring had long since come to rest. `firstModelFrame` is
    // the same latch the cover releases on, so the entrance plays *as* the scene appears.
    // In `qaMode` both snap to their resting values — a screenshot taken mid-flight frames
    // the model differently every run.
    val modelPresented = firstModelFrame.value
    LaunchedEffect(bounds, recenterGeneration, modelPresented, DemoSettings.qaMode) {
        if (bounds == null) return@LaunchedEffect
        if (DemoSettings.qaMode) {
            entranceProgress.snapTo(1f)
            fitProgress.snapTo(1f)
            return@LaunchedEffect
        }
        if (!modelPresented) {
            entranceProgress.snapTo(0f)
            fitProgress.snapTo(0f)
            return@LaunchedEffect
        }
        entranceProgress.snapTo(0f)
        fitProgress.snapTo(0f)
        launch { fitProgress.animateTo(1f, SceneViewTokens.Motion.spring()) }
        entranceProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = CAMERA_ENTRANCE_MILLIS,
                easing = SceneViewTokens.Ease.expressive,
            ),
        )
    }
    // Back closes transient chrome before leaving the demo.
    BackHandler(enabled = animationBarOpen || modelSheetOpen || environmentSheetOpen) {
        animationBarOpen = false; modelSheetOpen = false; environmentSheetOpen = false
    }

    DemoScaffold(
        // An opened file is titled with its own name: the user came here from their file
        // manager or a share sheet, and "Model Viewer" would not tell them it worked.
        title = openedModel?.displayName ?: stringResource(R.string.demo_model_viewer_screen_title),
        onBack = onBack,
        assetSource = assetSource,
        firstFrameRendered = firstModelFrame,
        // No preview image on the cover any more (#3402). It used to draw
        // `preview_model_viewer_<scheme>.webp` edge-to-edge: a white-field studio photo,
        // cropped to fill a phone, of a helmet lit and framed nothing like the scene that
        // replaced it half a second later — the "picture of the previous card" the report
        // describes. The cover now says what is happening instead of guessing at a frame
        // that has not been rendered.
        loadingLabel = stringResource(R.string.demo_model_viewer_loading),
        controls = {
            // Camera-distance slider — makes zoom discoverable without a pinch
            // gesture (and Maestro-testable, see #1571). The displayed value is
            // the slider override when set, otherwise the live auto-fit radius.
            // #3426 — the range used to be a fixed `0.5f..10f`, which is meaningless for a model
            // that auto-fits at 0.4 m (the slider could only ever push it away) or at 90 m (the
            // slider could not reach it at all). It is now the same bounds-relative window the
            // pinch is clamped to, so both controls span the subject rather than a guessed metre
            // range.
            LabeledSlider(
                label = "Camera distance",
                value = (sliderDistance ?: autoFitRadius)
                    .coerceIn(autoFitRadius * VIEWER_MIN_ZOOM_FACTOR, autoFitRadius * VIEWER_MAX_ZOOM_FACTOR),
                onValueChange = { DemoSettings.cameraDistance = it },
                valueRange = (autoFitRadius * VIEWER_MIN_ZOOM_FACTOR)..(autoFitRadius * VIEWER_MAX_ZOOM_FACTOR),
                valueText = "%.2f m".format(Locale.US, sliderDistance ?: autoFitRadius),
            )
            Row(Modifier.fillMaxWidth().toggleable(spinScene) { spinScene = it }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Spin scene")
                Switch(spinScene, null)
            }
        },
        // Dock order reads left to right as the questions a viewer answers: *what* am I
        // looking at, *under what light*, *does it move*, and *put it back where it was*
        // (#3402). Every icon changed with it. `ViewInAr` outlined was "Models" while
        // `ViewInAr` filled was the AR accent right beside it — the same cube twice, which
        // is what made the row unreadable; `Category` (three solids) says "pick a model"
        // and leaves the cube to mean AR. `CenterFocusStrong`'s reticle read as a zoom or
        // a camera-focus control, so Recenter is `RestartAlt` — an action, not a viewfinder.
        dock = listOf(
            DockItem(Icons.Outlined.Category, "Models", { modelSheetOpen = true }),
            DockItem(Icons.Outlined.WbSunny, "Lighting", { environmentSheetOpen = true }),
        ) + (if (animationNames.isNotEmpty()) listOf(DockItem(Icons.Outlined.Animation, "Animate", { animationBarOpen = !animationBarOpen }, selected = animationBarOpen)) else emptyList()) +
            listOf(DockItem(Icons.Outlined.RestartAlt, "Recenter", {
                // Recenter drops the zoom override too (#3403) — the camera returning to its
                // framed home pose while keeping a 4x zoom is not "recentred".
                DemoSettings.cameraDistance = null
                recenterGeneration++
            })),
        // Always composed when the model has clips, so the bar can slide in and out with
        // the standard M3 enter/exit instead of appearing and vanishing between frames
        // (#3406). An `AnimatedVisibility` that is not visible measures zero, so the
        // scaffold's measured bottom band is unchanged while it is closed.
        bottomOverlay = if (animationNames.isNotEmpty()) {{
            AnimatedVisibility(
                visible = animationBarOpen,
                enter = fadeIn(SceneViewTokens.Motion.fade()) +
                    expandVertically(SceneViewTokens.Motion.spring(), expandFrom = Alignment.Bottom),
                exit = fadeOut(SceneViewTokens.Motion.fade()) +
                    shrinkVertically(SceneViewTokens.Motion.spring(), shrinkTowards = Alignment.Bottom),
            ) {
                AnimationBar(animationNames, selectedAnimation, animationPlaying, animationProgress,
                    onPlayingChange = { animationPlaying = it },
                    onClipChange = { selectedAnimation = it; animationProgress = 0f },
                    onProgressChange = {
                        animationProgress = it
                        activeModelInstance?.animator?.takeIf { selectedAnimation in 0 until it.animationCount }?.let { animator ->
                            animator.applyAnimation(selectedAnimation, it * animator.getAnimationDuration(selectedAnimation))
                            animator.updateBoneMatrices()
                        }
                    })
            }
        }} else null,
        dockAccent = DockItem(Icons.Filled.ViewInAr, "View in AR", {
            // An opened file goes to AR as itself, at the size it actually is. That measurement
            // is the point for a 3MF: the format carries true manufacturing size, so a 60 mm
            // print must arrive in the room as 60 mm, not as the catalogue's default 30 cm.
            DemoSettings.openedModelSizeMeters = openedModel?.let {
                bounds?.extents?.let { extents ->
                    // `Aabb.extents` is already the FULL size (halfExtent * 2), so the longest
                    // dimension is the object's real length — no second doubling.
                    maxOf(extents.x, extents.y, extents.z).takeIf { it > 0f }
                }
            }
            val model = openedModel?.location ?: selectedModel.assetPath
            DemoSettings.requestedRoute = "demo/ar-placement?model=$model"
        }, enabled = arSupported == true),
        chromeToggleOnTap = true,
    ) {
        // The scene fills the viewport edge to edge; the chrome floats over it. Framing
        // therefore needs the band the chrome leaves visible: the identity row at the top
        // (provided by the scaffold) and the dock band plus the navigation bar at the bottom.
        val topInset = LocalDemoChromeTopInset.current
        val bottomInset = SETTINGS_FAB_RESERVED_SPACE +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val framing = remember(bounds, maxWidth, maxHeight, topInset, bottomInset) {
                val extents = bounds?.extents ?: return@remember null
                DemoMath.viewerFraming(
                    extentX = extents.x, extentY = extents.y, extentZ = extents.z,
                    viewportWidth = maxWidth.value, viewportHeight = maxHeight.value,
                    topInset = topInset.value, bottomInset = bottomInset.value,
                    verticalFovDegrees = verticalFovDegreesForFocalLength(28.0),
                )
            }
            LaunchedEffect(framing) { framing?.let { autoFitRadius = it.distance } }
            // Camera orbits; the model stays fixed at its glTF pose. The resting pose is flown
            // to when the model lands (#3406) and then held live.
            //
            // #3403 / #3404 — the manipulator is deliberately keyed on the CONTENT ONLY. A
            // Filament manipulator carries the whole camera pose, so rebuilding it discards the
            // user's orbit and snaps the camera back to the front view. The previous
            // `remember(framing, modelCenter, recenterGeneration, sliderDistance)` did exactly
            // that on every zoom-slider step (#3403), and again the first time the chrome measured
            // its identity row and moved the framing insets (#3404) — a visible "décroché" from a
            // control that must not touch the camera at all. Framing, pivot and zoom are read live
            // through providers now, so a settling inset or a zoom change moves the distance and
            // nothing else.
            val liveFraming = androidx.compose.runtime.rememberUpdatedState(framing)
            val liveCenter = androidx.compose.runtime.rememberUpdatedState(modelCenter)
            val livePivot = {
                val f = liveFraming.value
                val c = liveCenter.value
                if (f == null) c
                else Position(c.x, c.y + f.targetOffset.second, c.z + f.targetOffset.third)
            }
            val cameraManipulator = remember(activeModelInstance, recenterGeneration) {
                EntranceCameraManipulator(
                    eye = {
                        val f = liveFraming.value
                        val c = liveCenter.value
                        if (f == null) Position(c.x, c.y, c.z + 1.4f)
                        else Position(c.x, c.y + f.eyeOffset.second, c.z + f.eyeOffset.third)
                    },
                    target = livePivot,
                    progress = { entranceProgress.value },
                    fitDistance = { liveFraming.value?.distance ?: 1.4f },
                    distanceOverride = { DemoSettings.cameraDistance },
                    // A pinch publishes its distance to the SAME state the slider writes, so the
                    // two controls agree and the readout follows the gesture.
                    onDistanceChange = { DemoSettings.cameraDistance = it },
                )
            }
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = onFrame,
                engine = engine,
                modelLoader = modelLoader,
                environmentLoader = environmentLoader,
                environment = viewerEnvironment,
                // OFF: the camera is aimed at the measured bbox centre, see the framing notes.
                autoCenterContent = false,
                cameraManipulator = cameraManipulator,
            ) {
                activeModelInstance?.let { instance ->
                    // "Spin scene" turns the model about its bounding-box centre, not the glTF
                    // origin: counter-translate the pivot so the centre stays put under the camera.
                    val (rx, rz) = DemoMath.rotateAroundCentre(modelCenter.x, modelCenter.z, -modelYaw)
                    ModelNode(
                        modelInstance = instance,
                        // No `scaleToUnits` — the model renders at its true glTF size and the
                        // camera adapts to it (#1439). The settle spring drops the model in
                        // from slightly below its resting pose.
                        position = Position(
                            modelCenter.x - rx,
                            -autoFitRadius * 0.06f * (1f - fitProgress.value),
                            modelCenter.z - rz,
                        ),
                        rotation = Rotation(y = modelYaw),
                    )
                }
            }

            LoadingScrim(
                loading = activeModelInstance == null,
                label = stringResource(R.string.demo_model_viewer_loading),
            )
        }
    }
    if (modelSheetOpen) ModelPickerSheet(
        bundledModels, selectedModel.assetPath, hasSketchfabKey, surpriseInFlight,
        onSelect = { selectedModel = it; streamedFileUrl = null; modelSheetOpen = false },
        onPark = { modelSheetOpen = false; onModeChange(ModelViewerMode.Multi) },
        onSurprise = { if (!surpriseInFlight) { surpriseInFlight = true; scope.launch { streamedFileUrl = runCatching { pickRandomDownloadableModel(service, resolver) }.getOrNull(); surpriseInFlight = false; modelSheetOpen = false } } },
        onBrowse = { modelSheetOpen = false; onModeChange(ModelViewerMode.Gallery) },
        onDismiss = { modelSheetOpen = false },
    )
    if (environmentSheetOpen) EnvironmentSheet(
        environments = viewerEnvironments,
        selectedPath = requestedEnvironment.assetPath, intensity = iblIntensity, showEnvironment = showEnvironment,
        onSelect = { requestedEnvironment = it }, onIntensity = { iblIntensity = it }, onShowEnvironment = { showEnvironment = it },
        onReset = { requestedEnvironment = viewerEnvironments.first(); iblIntensity = 1f; showEnvironment = false },
        onDismiss = { environmentSheetOpen = false },
    )
    }
}

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
// Composes a themed "Park" scene from the 4 glTF assets in [SampleAssets]' `park`
// category: one hero at the back of the formation and three smaller ones in a
// front row.
//
// The layout is positional and fixed ([PARK_SLOTS]); WHICH model stands in each slot
// is the registry's call ([ParkSlot.uid]). Nothing here names a species: the
// visibility chips read their label off the resolved [SketchfabSlug.displayName], the
// same curated-English source the Gallery section's chips use, so a registry edit
// renames the chip with the model. Until a slug resolves the chip falls back to a
// positional "Model N". They used to be hardcoded "Tree" / "Bench" / "Dog" / "Bird"
// from a composition the registry stopped holding — four oaks named after a bench and
// a dog, with no bench and no dog on screen (#2933).
//
// ⚠️ WHAT loads depends on the build. With a Sketchfab API key the resolver streams
// the `park` category — four photoreal scanned oaks. Without one it substitutes each
// slug's BUNDLED fallback: a lantern, a lantern, a shiba, a soldier. Same demo id,
// same layout, completely different picture — worth knowing before reading a
// screenshot of this section as evidence of anything (#2913). The chip label names
// the CATALOGUE ENTRY, not the geometry, so on a fallback build it still reads
// "Oak Trees" over a lantern; the scaffold's asset-source pill is what tells the two
// apart, and it is wired here for exactly that reason — off the RESOLVED FILE, not
// off whether a key is configured, because a keyed build whose download fails lands
// on the same stand-ins.
//
// Lighting comes from `studio_warm_2k.hdr` — a soft golden-hour wash that unifies
// the four assets into one cohesive open-air display.
//
// Framing is aspect-aware (#2913): the camera distance is computed per viewport
// from the formation's own dimensions — see [parkCameraDistance]. Before that the
// section aimed a fixed camera at `(0, 0, -1.5)` while the library's
// `autoCenterContent` had already moved the models onto the world origin, which
// left the lens ~0.6 m from the content centroid — inside the subject. Whichever
// model sat on the pivot filled the frame as one featureless slab of its own
// material, with the rest of the viewport falling on the HDRI backdrop.
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
    var modelSheetOpen by remember { mutableStateOf(false) }
    // One flag per SLOT, not per species — index i pairs with PARK_SLOTS[i] and
    // slugs[i]. A SnapshotStateList keeps the four flags in one stable `remember`
    // slot, so toggling a chip recomposes the scene content without re-running the
    // loaders. It carries a single state record for the whole list, so a toggle
    // invalidates every reader of it rather than just the chip that changed — four
    // booleans in a demo, so the extra recomposition is not worth four `remember`s.
    val visible = remember { List(PARK_SLOTS.size) { true }.toMutableStateList() }
    var spinScene by remember { mutableStateOf(true) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val context = LocalContext.current

    // Resolve each slot's slug by uid (stable across registry re-ordering). Falling
    // back to the slug at the same index in the category if an explicit uid is
    // somehow missing keeps the demo running at degraded fidelity rather than
    // crashing. Always exactly PARK_SLOTS.size entries, so every `slugs[i]` below is
    // a fixed composition slot even when an entry resolves to null.
    val parkSlugs = SampleAssets.byCategory["park"].orEmpty()
    val slugs = remember(parkSlugs) {
        PARK_SLOTS.mapIndexed { index, slot ->
            SampleAssets.byUid[slot.uid] ?: parkSlugs.getOrNull(index)
        }
    }

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
    //
    // Deliberately unrolled rather than looped. What these calls need is a STABLE
    // composition slot each, so the `produceState` inside them keeps invalidating
    // this caller when the load lands (#1464). An inline `map` over a fixed-size
    // list would give that too — the unrolling is a conservative choice, not a
    // language requirement, and #1464 cost enough to earn the caution. One call per
    // slot, in slot order; resizing PARK_SLOTS without matching it here is caught by
    // the size assertion in DemoMathTest — in the unit tests, not on screen.
    val files = listOf(
        rememberSlugFile(slugs[0]),
        rememberSlugFile(slugs[1]),
        rememberSlugFile(slugs[2]),
        rememberSlugFile(slugs[3]),
    )
    val instances = listOf(
        rememberFileModelInstance(modelLoader, files[0]),
        rememberFileModelInstance(modelLoader, files[1]),
        rememberFileModelInstance(modelLoader, files[2]),
        rememberFileModelInstance(modelLoader, files[3]),
    )

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

    val allLoaded = instances.all { it != null }
    // Yaw drives the parent-scene rotation when "Spin scene" is on. Slow 30 s sweep
    // so the viewer can take in each face of the display before it cycles round.
    val sceneYaw = rememberHeroYaw(
        trigger = allLoaded && spinScene, durationMillis = 30_000, staticYaw = 0f,
    )

    // Same asset-source vocabulary as the Gallery section, and what keeps the chip
    // labels honest: they name the catalogue entry, and an "Offline model" pill says
    // the geometry under them is the bundled stand-in rather than the oak the label
    // names (#2933).
    //
    // The verdict is MEASURED from the resolved files, never inferred from the config
    // — [AssetSourceProbe] owns that rule and explains why. The evidence that earned it
    // came from THIS section: on the QA emulator (2026-07-28, key configured) all four
    // slots staged out of `cache/sketchfab/fallback/`, the download endpoint answering
    // 429, and this section's first cut read `SketchfabConfig.apiKey == null` and
    // labelled that exact scene "Streamed (cached)" (#2933).
    //
    // `allLoaded` watches the INSTANCES while the probe's fallback branch watches the
    // FILES — two different signals on purpose, which is what makes this a MOVING
    // verdict during load: a slot that falls back last flips the pill Streaming →
    // Offline after the fact. All four slots are streamed `park` slugs, so the whole
    // list is passed; none is a bundled-by-design slot.
    //
    // Whole-scene and pessimistic (see the probe): one fallen-back slot out of four
    // reads "Offline model" for all of them, and the pill never says which one swapped.
    val assetSource = if (slugs.all { it == null }) {
        null
    } else {
        AssetSourceProbe.ofAll(
            resolvedFiles = files,
            hasApiKey = SketchfabConfig.apiKey != null,
            loaded = allLoaded,
        )
    }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_multi_model_title),
        onBack = onBack,
        assetSource = assetSource,
        firstFrameRendered = firstFrame.rendered,
        dock = listOf(DockItem(Icons.Outlined.Category, "Models", { modelSheetOpen = true })),
        controls = {
            Text("Visibility", style = MaterialTheme.typography.labelLarge)
            // Labels come from the resolved slug's curated-English `displayName`
            // (same source as the Gallery chips), never from a hardcoded noun — the
            // registry decides what stands in each slot, so it decides the label
            // too. Horizontally scrolling for the same reason Gallery's row is:
            // catalogue names run long ("Skovfogedegen Oak") and four of them do not
            // fit a portrait phone width without clipping. `OverflowChipRow` fades
            // the overflowing edge so the off-screen chip is discoverable (#2944).
            OverflowChipRow {
                slugs.forEachIndexed { index, slug ->
                    FilterChip(
                        selected = visible[index],
                        onClick = { visible[index] = !visible[index] },
                        // Positional fallback for a slot the registry has no slug for —
                        // a chip with no model behind it still needs a stable, non-lying
                        // handle. DemoMathTest asserts every PARK_SLOTS uid resolves, so
                        // this branch is unreachable from THIS repo's registry; it is
                        // there for a build that edits SampleAssets without the tests.
                        label = { Text(slug?.displayName ?: "Model ${index + 1}") },
                    )
                }
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
        // BoxWithConstraints, not Box: the camera distance below is computed from the LIVE
        // viewport aspect (#2913). See PARK_SLOTS / [parkCameraDistance] for the framing rules.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val viewportAspect = (maxWidth / maxHeight)
                .takeIf { it.isFinite() && it > 0f } ?: PARK_FALLBACK_ASPECT
            // `camera_distance` / `?cameraDistance=` still wins when a QA flow or a store
            // capture sets it — the same override every hero-orbit demo honours. Before #2913
            // this section ignored it entirely (it built a stock `rememberCameraManipulator`,
            // which knows nothing about DemoSettings), so the store script's `--ef
            // camera_distance 6.0` was a silent no-op and probing 2.5 / 3.5 / 4.5 m produced
            // three identical frames. That is why the extra "could not fix the framing".
            val orbitDistance = DemoSettings.cameraDistance ?: parkCameraDistance(viewportAspect)
            val cameraManipulator = remember(orbitDistance) {
                createDefaultCameraManipulator(
                    // Straight-on, at the mid-height of the tallest model, looking at the
                    // formation centre — which IS the world origin, see `autoCenterContent`.
                    eyePosition = Position(0f, PARK_EYE_HEIGHT, orbitDistance),
                    targetPosition = Position(0f, 0f, 0f),
                )
            }
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = firstFrame.onFrame,
                engine = engine,
                modelLoader = modelLoader,
                environmentLoader = environmentLoader,
                environment = activeEnvironment,
                // OFF on purpose (#2913). The library's auto-centre pass translates the content
                // root so the union centroid lands on the orbit pivot, using whatever bounds have
                // materialised on the first non-empty frame — with four models streaming in
                // independently, WHICH bounds those are is a race. This scene places its own
                // models around the origin below, so the composition no longer depends on that
                // race, and the camera can be aimed at a centre that is known before load.
                autoCenterContent = false,
                cameraManipulator = cameraManipulator,
            ) {
                // Grove arrangement, centred on the world origin: the hero model at the back of
                // the formation, the three smaller ones in a front row, every one of them
                // bottom-aligned onto a shared ground plane at y = -PARK_HEIGHT / 2.
                //
                // sceneYaw rotates each model AROUND that centre by treating its (x, z) as polar
                // coords, so the formation turns like a turntable. The hero sits 0.2 m off the
                // pivot, so it sweeps a small circle rather than staying put: the framing covers
                // PARK_SPAN — the width the whole formation sweeps out — so every phase of the spin
                // keeps models filling the frame, but the exact composition still breathes as it
                // turns. Per-model rotation cancels the yaw on its own Y so each piece keeps facing
                // the camera.
                //
                // Indexed off PARK_SLOTS rather than four named locals, so visibility, loaded
                // instance and layout can only ever be read for the SAME slot (#2933).
                val displays = PARK_SLOTS.mapIndexed { index, slot ->
                    Display(visible[index], instances[index], slot)
                }
                // `key(index)` + `isVisible`, never a skipped call site (#2939). `ModelNode`
                // holds `remember(engine, modelInstance)`, and its `DisposableEffect(node)`
                // runs `node.destroy()`, which calls `engine.safeDestroyEntity` on entities
                // the ModelInstance only BORROWS — the ids survive, the renderable components
                // do not. Two ways that used to fire here:
                //   · dropping a hidden slot's call shifted every later ModelNode onto the
                //     preceding group with a DIFFERENT instance, re-keying the remember and
                //     destroying all four;
                //   · unmounting a hidden slot destroyed its own renderables, so toggling the
                //     chip back on rendered nothing.
                // The instances come from a `produceState` whose keys never change again, so
                // they are never reloaded and there is no recovery — a silent black scene with
                // no scrim, because the instance is still non-null. Same defect the Materials
                // section shipped until #2939. Keep every slot mounted; hide with `isVisible`.
                displays.forEachIndexed { index, d ->
                    key(index) {
                        if (d.instance != null) {
                            // Rotation math lives in DemoMath.rotateAroundCentre so it can be
                            // JVM-unit-tested without firing up Filament / Compose.
                            val (rx, rz) = DemoMath.rotateAroundCentre(d.slot.x, d.slot.z, sceneYaw)
                            ModelNode(
                                modelInstance = d.instance,
                                isVisible = d.show,
                                // Models with a skeletal animation auto-play it for "alive"
                                // scene reads; in qaMode we need the bind pose to render every
                                // frame so golden screenshots stay deterministic.
                                autoAnimate = !DemoSettings.qaMode,
                                scaleToUnits = d.slot.scale,
                                // Bottom-aligned (#2913): `Position(0, -1, 0)` puts each model's
                                // bounding-box FLOOR on its node origin, so `position.y` below
                                // stands them all on one ground plane. Without it a node keeps the
                                // asset's authored pivot, which differs per GLB — the formation's
                                // vertical placement was a property of whichever models the
                                // registry happened to point at, and the pulled-back framing this
                                // fix needs would have shown the smaller ones floating. (This
                                // parameter used to be a silent no-op; it was fixed library-side
                                // and is now honoured.)
                                centerOrigin = Position(0f, -1f, 0f),
                                position = Position(x = rx, y = -PARK_HEIGHT / 2f, z = rz),
                                rotation = Rotation(y = -sceneYaw),
                            )
                        }
                    }
                }
            }
            LoadingScrim(loading = !allLoaded, label = "Loading ${PARK_SLOTS.size} models…")
        }
    }
    if (modelSheetOpen) ModelPickerSheet(
        models = listOf(BundledViewerModel("models/khronos_damaged_helmet.glb", "Damaged Helmet")),
        selectedPath = "", surpriseAvailable = false, surpriseLoading = false,
        onSelect = { modelSheetOpen = false; onModeChange(ModelViewerMode.Single) },
        onPark = { modelSheetOpen = false }, onSurprise = {},
        onBrowse = { modelSheetOpen = false; onModeChange(ModelViewerMode.Gallery) },
        onDismiss = { modelSheetOpen = false },
    )
}

/**
 * One model's visibility and loaded instance, bound to its fixed place in the formation.
 *
 * The layout itself ([PARK_SLOTS]) and the framing derived from it live in `internal/ParkFraming.kt`
 * so a JVM unit test can assert that the derivation follows the layout (#2913).
 */
private data class Display(
    val show: Boolean,
    val instance: io.github.sceneview.model.ModelInstance?,
    val slot: ParkSlot,
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

    // Per-demo offline indicator chip (#1152 Stage 3). The origin is MEASURED from the
    // resolved file, never inferred from the config — [AssetSourceProbe] owns that rule.
    // Measured here too: on the QA emulator (2026-07-28, key configured, radio off) this
    // section staged out of `cache/sketchfab/fallback/` — the only thing under
    // `cache/sketchfab/` — while the pill read "Streamed (cached)" over the bundled car
    // (#2936).
    //
    // `loaded` is the parsed `ModelInstance`, NOT the resolved file, and that choice is
    // load-bearing: it keeps the chip in lockstep with the centre LoadingScrim (#1465),
    // so the chip reads "Streaming…" until the model is fully parsed and can never claim
    // a finished download over a still-spinning scrim.
    //
    // The probe's fallback branch deliberately outranks `loaded`, so a file known to be
    // the bundled stand-in reads "Offline model" while the scrim is still spinning on the
    // local load. That pairing is not new — a keyless build has always shown "Offline
    // model" over a spinning scrim — and it is the honest way round: #1465 is about the
    // chip never claiming COMPLETION early, and "Offline model" claims an ORIGIN, not a
    // finished download.
    val assetSource = if (slugs.isEmpty()) {
        null
    } else {
        AssetSourceProbe.of(
            resolvedFile = resolvedFile,
            hasApiKey = SketchfabConfig.apiKey != null,
            loaded = modelInstance != null,
        )
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
            // catalogue identifiers, not localizable UI copy. The overflowing edge
            // fades so the row reads as scrollable (#2944).
            OverflowChipRow {
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
        //
        // #3426 — the radius used to be a flat `1.6f` for *every* chip, while the gallery's slugs
        // are normalised anywhere from 0.20 to 0.85 units. The same shot therefore ranged from a
        // model overflowing the frame to one filling a sixth of it, purely by which chip was
        // tapped. It is now fitted per chip, so switching chips changes the model and not its
        // apparent size.
        val cameraManipulator = rememberHeroOrbitCameraManipulator(
            trigger = modelInstance != null,
            radius = rememberFitOrbitRadius(
                extentX = selectedSlug?.scaleToUnits ?: 0.5f,
                extentY = selectedSlug?.scaleToUnits ?: 0.5f,
                extentZ = selectedSlug?.scaleToUnits ?: 0.5f,
            ),
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
                        // Framing is handled by the scene's default autoCenterContent, which
                        // recentres the content root once the union bounding box is known. A
                        // previous `centerOrigin = Position(0, 0, 0)` here was a silent no-op
                        // (#2622 — the old formula ignored the AABB center) and was removed to
                        // keep this scene byte-for-byte identical now that centerOrigin works.
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

/**
 * Horizontally scrolling chip row with an overflow affordance (#2944).
 *
 * A plain `Row.horizontalScroll` gives no hint that more chips exist past the edge —
 * with registry labels like "Skovfogedegen Oak" the fourth Multi-Model chip sat
 * entirely off-screen with nothing to say so. This row fades whichever edge still has
 * content beyond it into the sheet's own container colour, so the clipped chip reads
 * as "continues" rather than "ends". The fade is drawn over the row, tracks the scroll
 * state live, and animates in / out on `duration-short`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverflowChipRow(content: @Composable RowScope.() -> Unit) {
    val scrollState = rememberScrollState()
    // The controls live inside the scaffold's ModalBottomSheet — fade into exactly
    // the colour the sheet paints behind the chips, light and dark alike.
    val fadeColor = BottomSheetDefaults.ContainerColor
    val fadeWidth = SceneViewTokens.Space.xl
    val endAlpha by animateFloatAsState(
        targetValue = if (scrollState.canScrollForward) 1f else 0f,
        animationSpec = tween(SceneViewTokens.Duration.shortMillis),
        label = "chipRowEndFade",
    )
    val startAlpha by animateFloatAsState(
        targetValue = if (scrollState.canScrollBackward) 1f else 0f,
        animationSpec = tween(SceneViewTokens.Duration.shortMillis),
        label = "chipRowStartFade",
    )
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    drawContent()
                    val w = fadeWidth.toPx()
                    if (endAlpha > 0f) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, fadeColor),
                                startX = size.width - w,
                                endX = size.width,
                            ),
                            alpha = endAlpha,
                        )
                    }
                    if (startAlpha > 0f) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(fadeColor, Color.Transparent),
                                startX = 0f,
                                endX = w,
                            ),
                            alpha = startAlpha,
                        )
                    }
                }
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            content = content,
        )
        // A fade alone is easy to miss when the next chip barely peeks, so an explicit
        // chevron rides the trailing fade while there is more to scroll. Decorative:
        // the chips themselves are the accessible targets.
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .graphicsLayer { alpha = endAlpha },
        )
    }
}
