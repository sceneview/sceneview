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
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.HighQuality
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.runtime.SideEffect
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
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.model.model
import io.github.sceneview.toAabb
import io.github.sceneview.verticalFovDegreesForFocalLength
import io.github.sceneview.demo.AssetSourceState
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.LocalDemoChromeTopInset
import io.github.sceneview.demo.LocalDemoSheetCover
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.SETTINGS_FAB_RESERVED_SPACE
import io.github.sceneview.demo.common.rememberFileModelInstance
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.hdpack.HdPack
import io.github.sceneview.demo.hdpack.HdPackDownloadDialog
import io.github.sceneview.demo.hdpack.HdPackPerfProbe
import io.github.sceneview.demo.hdpack.HdPackStatus
import io.github.sceneview.demo.hdpack.hdPackSize
import io.github.sceneview.demo.hdpack.rememberHdPackStatus
import io.github.sceneview.demo.hdpack.rememberHdPackStore
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.GlassActionPill
import io.github.sceneview.demo.ui.viewer.BundledViewerModel
import io.github.sceneview.demo.ui.viewer.AnimationBar
import io.github.sceneview.demo.ui.viewer.EnvironmentSheet
import io.github.sceneview.demo.ui.viewer.ModelPickerSheet
import io.github.sceneview.demo.ui.viewer.ViewerScene
import io.github.sceneview.demo.ui.viewer.ModelUnitSheet
import io.github.sceneview.demo.OpenedModelIntent
import io.github.sceneview.core.threemf.ModelUnitGuess
import io.github.sceneview.core.threemf.ThreeMfUnit
import io.github.sceneview.demo.ui.viewer.ViewerEnvironment
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.demos.internal.SURPRISE_POOL
import io.github.sceneview.demo.demos.internal.SurprisePrefetch
import io.github.sceneview.demo.demos.internal.SurpriseRolls
import io.github.sceneview.demo.demos.internal.checkSurpriseSize
import io.github.sceneview.demo.demos.internal.drawFullyOpaqueMaskedMaterials
import io.github.sceneview.demo.ui.GlassPill
import io.github.sceneview.demo.demos.internal.PARK_HEIGHT
import io.github.sceneview.demo.demos.internal.PARK_SLOTS
import io.github.sceneview.demo.demos.internal.ParkSlot
import io.github.sceneview.demo.demos.internal.parkCamera
import io.github.sceneview.demo.initialDemoMode
import io.github.sceneview.demo.EntranceCameraManipulator
import io.github.sceneview.demo.driving
import io.github.sceneview.demo.rememberContinuousCameraManipulator
import io.github.sceneview.demo.rememberBackendDrainWait
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.VIEWER_MAX_ZOOM_FACTOR
import io.github.sceneview.demo.VIEWER_MIN_ZOOM_FACTOR
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
import io.github.sceneview.rememberRenderInvalidator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
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
 *
 * The third section, **Gallery** (formerly `scene-gallery`: one streamed Sketchfab
 * model at a time on a black stage), was removed by #4039 — it was not a scene, and
 * streamed single models are what "Surprise me" is for. Its deep link opens the
 * Single Model section.
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
    // The model on the single-model stage lives here, above the sections, so the Models
    // sheet opened from the Park can open a model directly (#3828) — it used to
    // offer only the Damaged Helmet there — and coming back from a scene finds the model the
    // user left rather than the default.
    var selectedModel by remember { mutableStateOf(BUNDLED_VIEWER_MODELS.first()) }
    val openModel: (BundledViewerModel) -> Unit = {
        selectedModel = it
        onModeChange(ModelViewerMode.Single)
    }
    when (mode) {
        ModelViewerMode.Single -> SingleModelSection(onBack, mode, onModeChange, selectedModel) { selectedModel = it }
        ModelViewerMode.Multi -> MultiModelSection(onBack, mode, onModeChange, openModel)
    }
}

/**
 * The models the viewer ships in its APK, in picker order — one list for the Models sheet of all
 * three sections.
 *
 * #3324 — the two untextured low-poly rows (Fox, Shiba) are out: in a full-screen PBR viewer they
 * are the two models that make the SDK look worse than it is. The three that take their place each
 * exercise a different material extension (sheen, sheen + specular, iridescence + transmission +
 * volume). Both GLBs stay bundled — `SampleAssets` fallbacks and `ARGeospatialAnchorsDemo` still
 * load them.
 */
private val BUNDLED_VIEWER_MODELS = listOf(
    BundledViewerModel("models/khronos_damaged_helmet.glb", "Damaged Helmet", R.string.demo_model_desc_damaged_helmet),
    // HD pack (2026-09-29): the full-resolution Khronos Flight Helmet, downloaded after install.
    // Its stand-in is the bundled Damaged Helmet — the nearest thing the APK ships, another
    // helmet at the same scale — shown instantly while the HD file downloads or loads.
    BundledViewerModel(
        "models/khronos_damaged_helmet.glb",
        "Flight Helmet",
        R.string.demo_model_desc_flight_helmet,
        hdAssetId = "flight-helmet",
        thumbnailStem = "khronos_flight_helmet",
    ),
    BundledViewerModel("models/khronos_glam_velvet_sofa.glb", "Velvet Sofa", R.string.demo_model_desc_velvet_sofa),
    BundledViewerModel("models/khronos_sheen_chair.glb", "Sheen Chair", R.string.demo_model_desc_sheen_chair),
    BundledViewerModel("models/khronos_iridescent_dish.glb", "Olive Dish", R.string.demo_model_desc_olive_dish),
    BundledViewerModel("models/khronos_lantern.glb", "Lantern", R.string.demo_model_desc_lantern),
    BundledViewerModel("models/khronos_toy_car.glb", "Toy Car", R.string.demo_model_desc_toy_car),
    // The three.js Soldier is authored facing -Z: from the viewer's +Z camera it showed its back,
    // while its picker card showed a face (#3828).
    BundledViewerModel("models/threejs_soldier.glb", "Soldier", R.string.demo_model_desc_soldier, frontYaw = 180f),
)

/** The section a scene card of the Models sheet opens. */
private fun ViewerScene.mode(): ModelViewerMode = when (this) {
    ViewerScene.Park -> ModelViewerMode.Multi
}

/**
 * Frames queued with a fully loaded model instance before the cover-releasing backend fence.
 * Not 1: the scene parents DSL nodes through an async `snapshotFlow`, so the first frame after
 * the instance lands can be drawn without the `ModelNode`; the fence must follow a frame that
 * includes it.
 *
 * Not 3 either, since #3108: this counter is paid out of frames the scene presents *after* the
 * model is in, and a render-on-demand scene presents a settle tail and then parks — measured at
 * 3 frames in 10 s total on `emulator-5554`, of which fewer still land post-load. A threshold
 * the scene never reaches leaves the "Still loading…" card over a finished model for good. Two
 * is the smallest count that keeps the reason above intact, and the fence after the second is
 * what actually proves the backend drew it.
 */
private const val MODEL_COVER_FRAMES = 2

/**
 * Length of the camera fly-in when the model lands (#3406). Twice `duration-medium`:
 * a screen transition is 350 ms, but this one is the subject arriving rather than a
 * surface changing, and under 500 ms the dolly reads as a stutter instead of a move.
 * Long enough to be seen, short enough that the first drag is never waiting on it —
 * and a touch cancels it outright.
 */
private const val CAMERA_ENTRANCE_MILLIS = 700

/**
 * How long the resting framing takes to follow a change of the chrome insets or the viewport
 * (the identity row measuring itself, the navigation bar settling). One `duration-medium`.
 */
private const val FRAMING_SETTLE_MILLIS = 350

/**
 * Largest step, in seconds, one frame may advance the camera flights by. The frames that first
 * show a model are the ones its upload drops; a flight timed on the wall clock spends itself in
 * those gaps and reaches the screen as a cut. Capped, a dropped frame only makes it run longer.
 */
private const val MAX_FLIGHT_STEP_SECONDS = 1f / 20f

/**
 * Animates this value from where it is to `1f` over [durationMillis] of *frames*, eased by
 * [easing]. Unlike `animateTo(tween(...))`, which reads the wall clock, each frame advances by
 * at most [MAX_FLIGHT_STEP_SECONDS], so a stall pauses the flight instead of skipping it.
 */
private suspend fun Animatable<Float, *>.flyToOne(
    durationMillis: Int,
    easing: androidx.compose.animation.core.Easing,
) {
    val duration = durationMillis / 1000f
    val start = value
    var elapsed = 0f
    var last = withFrameNanos { it }
    while (elapsed < duration) {
        val now = withFrameNanos { it }
        elapsed += ((now - last) / 1_000_000_000f).coerceIn(0f, MAX_FLIGHT_STEP_SECONDS)
        last = now
        val t = easing.transform((elapsed / duration).coerceAtMost(1f))
        snapTo(start + (1f - start) * t)
    }
}

/**
 * The resting framing the camera is actually aimed with — the live [DemoMath.ViewerFraming],
 * but eased when it changes under a model that is already on screen (#3404's inset settle).
 *
 * The eye and pivot providers used to read the live framing, so the frame the identity row
 * measured itself moved the destination of a flight in progress, or the resting camera, by the
 * whole difference at once. A new model (a different `bounds` instance) or a first framing is
 * not eased: nothing on screen is framed by it yet.
 */
private class EasedFraming {
    private var from: DemoMath.ViewerFraming? = null
    private var to: DemoMath.ViewerFraming? = null
    private var content: Any? = null

    /** `true` between [retarget] and the start of the ease: hold the pose on screen. */
    private var holding = false

    /**
     * Points the framing at [framing] for [forContent]. Returns `true` when the change must be
     * eased — the caller then runs the blend from `0` to `1` and calls [release] once at `0`.
     */
    fun retarget(framing: DemoMath.ViewerFraming?, forContent: Any?, blend: Float): Boolean {
        if (framing == to && forContent === content) return false
        val shown = current(blend)
        val sameContent = forContent === content
        content = forContent
        return if (shown == null || framing == null || !sameContent) {
            from = null
            to = framing
            holding = false
            false
        } else {
            from = shown
            to = framing
            holding = true
            true
        }
    }

    /** The ease has started from `0`: stop holding the captured pose. */
    fun release() {
        holding = false
    }

    /** The framing to aim with at [blend] (`0` = where the ease started, `1` = the target). */
    fun current(blend: Float): DemoMath.ViewerFraming? {
        val target = to ?: return null
        val start = from ?: return target
        if (holding) return start
        val t = blend.coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = a + (b - a) * t
        fun mix(a: Triple<Float, Float, Float>, b: Triple<Float, Float, Float>) =
            Triple(mix(a.first, b.first), mix(a.second, b.second), mix(a.third, b.third))
        return DemoMath.ViewerFraming(
            distance = mix(start.distance, target.distance),
            targetOffset = mix(start.targetOffset, target.targetOffset),
            eyeOffset = mix(start.eyeOffset, target.eyeOffset),
        )
    }
}

private enum class ModelViewerMode(val label: String) {
    Single("Single Model"),
    Multi("Multi-Model"),
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
    selectedModel: BundledViewerModel,
    onSelectModel: (BundledViewerModel) -> Unit,
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    var modelSheetOpen by remember { mutableStateOf(false) }
    var environmentSheetOpen by remember { mutableStateOf(false) }
    // Height the open Lighting sheet covers from the bottom edge, as it measures itself (#4053).
    var environmentSheetCover by remember { mutableStateOf(0.dp) }
    // Chinese Garden leads, and the flagship viewer opens on it (#3402). The old default —
    // `studio_2k` — gave correct light but no colour, and on the near-black stage the hero
    // read as a grey object on a black field. Measured on the emulator against every bundled
    // HDR: the studios are neutral, `outdoor_cloudy` is flat by construction, and the two
    // night maps are darker than the stage. The garden is the one that makes a PBR viewer
    // look like a PBR viewer — green canopy and blue sky across
    // the chrome, a hard sun glint, real depth in the visor — and it is bright enough that
    // the model never sinks into the stage in either theme. The list still leads with the
    // default so "Reset lighting" is the first tile.
    //
    // #4052 — every name says what the HDR shows, and every tile is rendered from it. The
    // bundled `sunset_2k.hdr` is an overcast seascape, so "Sunset" is Poly Haven's "The Sky Is
    // On Fire" (CC0). `studio_warm_2k.hdr` is the grey softbox studio and `studio_2k.hdr` a
    // sunlit living room, so they read "Studio" and "Interior" — the names Material Studio
    // already gives the same two files.
    val viewerEnvironments = remember { listOf(
        ViewerEnvironment("environments/chinese_garden_2k.hdr", "Chinese Garden"),
        ViewerEnvironment("environments/sky_on_fire_2k.hdr", "Sunset"),
        ViewerEnvironment("environments/studio_warm_2k.hdr", "Studio"),
        ViewerEnvironment("environments/studio_2k.hdr", "Interior"),
        ViewerEnvironment("environments/outdoor_cloudy_2k.hdr", "Outdoor Cloudy"),
        ViewerEnvironment("environments/night_sky_2k.hdr", "Night Sky"),
        ViewerEnvironment("environments/rooftop_night_2k.hdr", "Rooftop Night"),
    ) }
    var requestedEnvironment by remember { mutableStateOf(viewerEnvironments.first()) }
    var iblIntensity by remember { mutableStateOf(1f) }
    var showEnvironment by remember { mutableStateOf(false) }
    var recenterGeneration by remember { mutableStateOf(0) }
    // Set once the scene block below creates the live manipulator, so the Recenter action —
    // declared here, ahead of that block in source order — can still reach it and capture the
    // pose actually on screen before the flight resets (#3622).
    val cameraManipulatorRef = remember { mutableStateOf<EntranceCameraManipulator?>(null) }
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
    // "Open with SceneView" — a `.3mf` / `.glb` / `.gltf` / `.stl` / `.obj` / `.ply` file,
    // already staged into the cache as a `file://` path by `OpenedModelIntent`. It rides the
    // same override the Sketchfab stream uses (`rememberModelInstance` resolves a `file://`
    // location exactly like an http one), so an opened file arrives on the flagship viewer with
    // its framing, lighting, animation bar and View-in-AR handoff already working — rather than
    // on a second, poorer screen written to say the same thing.
    val openedModel = remember { DemoSettings.openedModel.also { DemoSettings.openedModel = null } }
    var streamedFileUrl by remember { mutableStateOf<String?>(openedModel?.location) }
    // Scale question for a unit-less file (#3543). STL / OBJ / PLY record no unit, so the loader
    // reads them in millimetres; `loadedUnit` is the reading currently on screen, and taking the
    // offer re-converts the same staged bytes at the other one.
    var loadedUnit by remember { mutableStateOf(ThreeMfUnit.Default) }
    var unitSheetOpen by remember { mutableStateOf(false) }
    var unitAnswered by remember { mutableStateOf(openedModel == null) }
    // The step a "Surprise me" roll is in, or `null` when none is running (#3825). The pill
    // narrates it — download, then decode — instead of a static "Finding…". Every exit path
    // (success, failed download, failed decode, a model picked meanwhile) lands back on `null`,
    // so the button can never stick in its loading state.
    var surpriseStage by remember { mutableStateOf<SurpriseStage?>(null) }
    val surpriseInFlight = surpriseStage != null
    // The roll in flight, kept so a model picked from the sheet cancels it (#4034): a roll that
    // finished after the pick used to replace the model the user had just chosen.
    var surpriseJob by remember { mutableStateOf<Job?>(null) }
    // The registry entry the last roll put on screen and the file it streamed, for the credit
    // line: every pick is CC-BY, and the licence wants the author named.
    var surprisePick by remember { mutableStateOf<Pair<SketchfabSlug, String>?>(null) }
    // The next roll's model, downloaded while the user looks at this one (#4034).
    val surprisePrefetch = remember { SurprisePrefetch() }
    // Stops a roll the user has overridden by choosing a model (#4034).
    val cancelSurprise: () -> Unit = {
        surpriseJob?.cancel()
        surpriseJob = null
        surpriseStage = null
        surprisePick = null
    }

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
    val hasSketchfabKey = remember { SketchfabConfig.apiKey != null }

    // The streamed model is loaded via the URL overload. This MUST be called
    // unconditionally — wrapping a @Composable in `streamedFileUrl?.let { }`
    // makes the composer group appear/disappear with `streamedFileUrl`, so the
    // `produceState` inside `rememberModelInstance` lands in an unstable slot.
    // The State it returns then fails to invalidate the scope that reads
    // `streamedModelInstance` when the load completes, leaving `assetSource`
    // pinned at `Streaming` for the whole session even though the model is
    // loaded and interactive (#1464). `rememberStreamedModelInstance` keeps the
    // call site stable and simply returns null while no stream is active. It hands a model
    // over only once its textures are in and its bounds are sane (#4034).
    val renderInvalidator = rememberRenderInvalidator()
    val streamedModel = rememberStreamedModelInstance(
        modelLoader,
        streamedFileUrl,
        wakeRenderLoop = renderInvalidator::requestRender,
    ) { rejected ->
        // The file loaded as nothing, or as a box with no size: the previous model stays up.
        if (surprisePick?.second == rejected) surprisePick = null
        if (surpriseStage == SurpriseStage.Decoding) surpriseStage = null
    }
    val streamedModelInstance = streamedModel?.instance
    // The bundled hero — assets/models/khronos_damaged_helmet.glb. Loaded
    // eagerly so the first frame after launch shows the hero shot.
    val bundledModelInstance = rememberModelInstance(modelLoader, selectedModel.assetPath)

    // HD pack (2026-09-29). An HD entry shows its bundled stand-in at once and swaps to the
    // downloaded file when it is on disk — read from `filesDir`, never re-fetched. It rides the
    // same loader as the streamed models, so the swap waits for its textures and the stand-in
    // stays up meanwhile: no untextured frame, no black stage.
    val hdStore = rememberHdPackStore()
    val hdStatus by rememberHdPackStatus(hdStore)
    val hdReadyIds by remember(hdStore) {
        hdStore?.readyIds ?: kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())
    }.collectAsState()
    val hdAsset = selectedModel.hdAssetId
        ?.takeIf { openedModel == null && streamedFileUrl == null }
        ?.let { hdStore?.manifest?.asset(it) }
    val hdFileLocation = hdAsset?.takeIf { it.id in hdReadyIds }
        ?.let { android.net.Uri.fromFile(hdStore?.fileFor(it)).toString() }
    // A file that times out or has no framable bounds is dropped: the stand-in stays and the
    // pill turns to "HD · download failed" (tap = retry) instead of spinning forever.
    var hdRejectedLocation by remember { mutableStateOf<String?>(null) }
    val hdLoadFailed = hdFileLocation != null && hdFileLocation == hdRejectedLocation
    val hdModel = rememberStreamedModelInstance(
        modelLoader,
        hdFileLocation?.takeIf { !hdLoadFailed },
        wakeRenderLoop = renderInvalidator::requestRender,
        onRejected = { hdRejectedLocation = it },
    )
    val hdShown = hdFileLocation != null && !hdLoadFailed && hdModel?.location == hdFileLocation
    val hdPillShown = hdAsset != null && !hdShown
    var hdDialogOpen by remember { mutableStateOf(false) }

    // The instance actually rendered this frame. Falls back to the bundled
    // helmet whenever the streamed instance is null (no Surprise tap yet,
    // streamed load still in flight, or streamed load failed).
    val activeModelInstance = streamedModelInstance ?: hdModel?.instance ?: bundledModelInstance
    // Debug builds log how long a pick takes to reach a finished frame (HD pack QA).
    val perfTargetReached = when {
        streamedFileUrl != null -> false
        hdFileLocation != null -> hdShown
        else -> bundledModelInstance != null
    }
    androidx.compose.runtime.SideEffect { if (perfTargetReached) HdPackPerfProbe.instanceReady() }
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
    // Which content `entranceProgress` currently belongs to (the `bounds` instance the entrance
    // effect last claimed it for). A manipulator rebuilt for a new model reads `0f` until the
    // effect below claims the progress for it: before, it read the PREVIOUS model's `1f` for the
    // frame(s) until `snapTo(0f)` ran, so the camera showed the resting pose, then jumped wide.
    val entranceOwner = remember { java.util.concurrent.atomic.AtomicReference<Any?>(null) }
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
    // this one through it too. "Surprise me" draws from registry entries since #4034
    // ([SURPRISE_POOL]), but `pickSurpriseModel` still calls `SketchfabService.downloadModel`
    // directly and never stages the entry's bundled fallback: a failure yields `null`,
    // `streamedFileUrl` keeps its value, and the model already on screen simply stays. So
    // this chip can never render a stand-in under a "Streamed" label — there is no origin to
    // get wrong, which is the probe's entire reason to exist. The other three sites go through `SketchfabAssetResolver`, whose every
    // failure path DOES end at a fallback file, and they do share the probe.
    val assetSource = when {
        // The user's own file is neither streamed nor bundled: its origin is the title bar,
        // which names the file. A "Streamed" chip over a local file would simply be false.
        openedModel != null -> null
        streamedFileUrl == null -> null
        streamedModel?.location != streamedFileUrl -> AssetSourceState.Streaming
        else -> AssetSourceState.Streamed
    }

    val firstFrame = rememberFirstFrameState(engine)
    // The preview cover stays up until a Filament frame that actually SHOWS the model. Three
    // signals fire too early: the first frame lands before the GLB is decoded;
    // `rememberModelInstance` returns while gltfio is still uploading textures
    // (`ModelLoader.progress < 1`); and even with the resources in, Choreographer keeps ticking
    // at 60 Hz while Filament's backend thread is still linking the model's material programs —
    // measured on the emulator: ticks resume at +0.5 s, the helmet is first presented at +3.5 s,
    // black in between. So once the instance exists, the resources report complete and a couple
    // of frames have queued the node's draw, a backend fence confirms the backend has actually
    // executed that frame, and only then is the cover released. The cover is a static image,
    // so the wait is invisible (~100 ms on hardware, the full link time on a software GL).
    // The fence is polled, never awaited: an `Engine.flushAndWait()` here held the main thread
    // for that whole link time and a BACK press behind it raised an ANR (#3799).
    // Latched — a later model swap must not bring the helmet preview back.
    val modelFramesSeen = remember { mutableStateOf(0) }
    val hasModelRef = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    hasModelRef.set(activeModelInstance != null)
    val modelDrain = rememberBackendDrainWait(engine)
    val onFrame: (Long) -> Unit = remember(firstFrame, modelLoader, modelDrain, renderInvalidator) {
        { nanos ->
            firstFrame.onFrame(nanos)
            HdPackPerfProbe.onFrame { runCatching { modelLoader.progress >= 1f }.getOrDefault(true) }
            if (hasModelRef.get() && modelFramesSeen.value < MODEL_COVER_FRAMES &&
                runCatching { modelLoader.progress >= 1f }.getOrDefault(true)
            ) {
                if (modelFramesSeen.value < MODEL_COVER_FRAMES - 1) {
                    modelFramesSeen.value++
                    // The count is owed a frame the scene may never present on its own (#3982):
                    // the load finishing is the last change, and on a slow GPU the one frame
                    // presented after it closes the settle window and parks the loop, leaving
                    // "Still loading…" over the finished model until the user orbits. Ask for
                    // the next one; `onFrame` alone cannot keep the loop awake.
                    renderInvalidator.requestRender()
                } else {
                    // The last count is paid by the backend, not by a frame. No-op while pending.
                    modelDrain.start { modelFramesSeen.value = MODEL_COVER_FRAMES }
                }
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
        // `IndirectLight` is a raw Filament object — the SDK hands it out and never sees it
        // again — so dimming it reaches the engine and nothing else. Under `OnDemand` the new
        // ambient would sit there with no frame coming to show it (#3718).
        renderInvalidator.requestRender()
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
    // #3543 — a unit-less mesh a couple of units across is metre-authored, not a 2 mm part. The
    // viewer frames it correctly either way now, so this is a question, asked once, about what the
    // file MEANT — never a silent rescale, and never a hidden setting.
    val unitSuggestion = remember(bounds, loadedUnit, openedModel) {
        val name = openedModel?.displayName ?: return@remember null
        if (OpenedModelIntent.unitLessFormat(name) == null) return@remember null
        val extents = bounds?.extents ?: return@remember null
        ModelUnitGuess.suggestFromLoaded(maxOf(extents.x, extents.y, extents.z), loadedUnit)
    }
    // Never in `qaMode`: a screenshot run must show the model, not a sheet over it.
    val askAboutUnit = unitSuggestion != null && !unitAnswered && !DemoSettings.qaMode
    LaunchedEffect(askAboutUnit, modelPresented) {
        if (askAboutUnit && modelPresented) unitSheetOpen = true
    }
    LaunchedEffect(bounds, recenterGeneration, modelPresented, DemoSettings.qaMode) {
        if (bounds == null) return@LaunchedEffect
        if (DemoSettings.qaMode) {
            entranceProgress.snapTo(1f)
            fitProgress.snapTo(1f)
            entranceOwner.set(bounds)
            return@LaunchedEffect
        }
        if (!modelPresented) {
            entranceProgress.snapTo(0f)
            fitProgress.snapTo(0f)
            entranceOwner.set(bounds)
            return@LaunchedEffect
        }
        entranceProgress.snapTo(0f)
        fitProgress.snapTo(0f)
        entranceOwner.set(bounds)
        // The frame that shows the model is followed by the ones its upload drops. The flight
        // below advances by capped frame steps (see [flyToOne]) so those gaps cannot eat it,
        // and waiting for a steady pace first keeps it from starting inside them.
        io.github.sceneview.demo.awaitSteadyFrames()
        launch { fitProgress.animateTo(1f, SceneViewTokens.Motion.spring()) }
        // The flight is read by the camera manipulator from inside the render loop; wake it.
        renderInvalidator.requestRender()
        entranceProgress.flyToOne(CAMERA_ENTRANCE_MILLIS, SceneViewTokens.Ease.expressive)
    }
    // Back closes transient chrome before leaving the demo.
    val anySheetOpen = animationBarOpen || modelSheetOpen || environmentSheetOpen
    // #3822 — swapping to a bundled model (e.g. "Soldier") from the Models sheet is not a
    // navigation the back stack knows about either: it is the same screen with different
    // content. Without this, back from a swapped-in model skipped straight past the demo's
    // own default (the Damaged Helmet) to the Showcase home. One level at a time: revert to
    // the default model first, exit only from there. Scoped to the bundled-model swap only —
    // an opened external file (`openedModel`, a one-shot `val` for the "Open with SceneView"
    // handoff) is a different, narrower flow this issue does not report on.
    val modelSwapped = openedModel == null && selectedModel != BUNDLED_VIEWER_MODELS.first()
    BackHandler(enabled = anySheetOpen || unitSheetOpen || modelSwapped) {
        when {
            anySheetOpen || unitSheetOpen -> {
                animationBarOpen = false; modelSheetOpen = false; environmentSheetOpen = false
                if (unitSheetOpen) { unitSheetOpen = false; unitAnswered = true }
            }
            modelSwapped -> {
                cancelSurprise()
                onSelectModel(BUNDLED_VIEWER_MODELS.first())
                streamedFileUrl = null
            }
        }
    }

    // The floating pill's roll. Since #3828 it is the only "Surprise me". Since #4034 it draws
    // from a curated pool of small single objects, in shuffle-bag order, and it no longer closes
    // the Models sheet when it lands: the sheet open at that point is one the user opened during
    // the roll, and picking a model in it cancels the roll.
    val rollSurprise: () -> Unit = {
        if (!surpriseInFlight) {
            surpriseStage = SurpriseStage.Fetching(name = "", cached = false)
            surpriseJob = scope.launch {
                val pick = pickSurpriseModel(service, surprisePrefetch) { surpriseStage = it }
                if (pick == null) {
                    surpriseStage = null
                    return@launch
                }
                // The same file twice keeps the instance already on screen: nothing to decode.
                surpriseStage = if (pick.second == streamedFileUrl) null else SurpriseStage.Decoding
                surprisePick = pick
                streamedFileUrl = pick.second
                // Warm the next roll while this model is looked at.
                surprisePrefetch.warm(scope, service)
            }
        }
        Unit
    }
    // The step after the download happens in the loader, not in the roll coroutine: the GLB is
    // parsed and its textures uploaded, and only then does [rememberStreamedModelInstance] hand
    // the model over. The narration ends when the model on screen is the one this roll fetched.
    LaunchedEffect(streamedModel, surpriseStage) {
        if (surpriseStage != SurpriseStage.Decoding) return@LaunchedEffect
        if (streamedModel != null && streamedModel.location == streamedFileUrl) {
            surpriseStage = null
        } else {
            // A backstop only: a failed load reports itself through the rejection above.
            delay(SURPRISE_STEP_TIMEOUT_MS)
            surpriseStage = null
        }
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
            // #3821 — `value` used to clamp into the valid window while `valueText` read the
            // raw, unclamped distance: whenever a re-frame shifted the window, the thumb
            // snapped to a bound but the label kept showing the stale unclamped number, so
            // the two visibly disagreed. Both now read the same clamped value.
            val clampedSliderDistance = (sliderDistance ?: autoFitRadius)
                .coerceIn(autoFitRadius * VIEWER_MIN_ZOOM_FACTOR, autoFitRadius * VIEWER_MAX_ZOOM_FACTOR)
            LabeledSlider(
                label = "Camera distance",
                value = clampedSliderDistance,
                onValueChange = {
                    DemoSettings.cameraDistance = it
                    // Like the IBL intensity fix above (#3718), this write reaches the camera
                    // manipulator through a plain state read, not a gesture the `OnDemand`
                    // render loop's own bookkeeping can see — without this the model's on-screen
                    // size only caught up once some unrelated touch invalidated a frame.
                    renderInvalidator.requestRender()
                },
                valueRange = (autoFitRadius * VIEWER_MIN_ZOOM_FACTOR)..(autoFitRadius * VIEWER_MAX_ZOOM_FACTOR),
                valueText = "%.2f m".format(Locale.US, clampedSliderDistance),
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
                // Capture the pose actually on screen — post-orbit, pre-reset — before anything
                // moves, so the flight below starts from there instead of snapping to the
                // cold-open's synthetic swung-off-axis pose (#3622).
                cameraManipulatorRef.value?.beginRecenterFlight()
                // Recenter drops the zoom override too (#3403) — the camera returning to its
                // framed home pose while keeping a 4x zoom is not "recentred".
                DemoSettings.cameraDistance = null
                recenterGeneration++
                // #3821 — the flight is read through `EntranceCameraManipulator.getTransform()`
                // off `entranceProgress` and `fallback`, neither of which the `OnDemand` render
                // loop's bookkeeping tracks on its own (same class of bug as #3718). Without this
                // the tap had no visible effect until some unrelated gesture forced a frame.
                renderInvalidator.requestRender()
            })),
        // The animation bar is always composed when the model has clips, so it can slide
        // in and out with the standard M3 enter/exit instead of appearing and vanishing
        // between frames (#3406). An `AnimatedVisibility` that is not visible measures
        // zero, so the scaffold's measured bottom band is unchanged while it is closed.
        bottomOverlay = if (hasSketchfabKey || animationNames.isNotEmpty() || hdPillShown) {{
            // #3585 — "Surprise me" was reachable only from the third row of a sheet the
            // user had to open first, and it is the one action of this viewer that makes
            // people keep tapping. A glass pill floating over the scene re-rolls without
            // opening anything. It is the only entry point since #3828: a second copy at the
            // top of the Models sheet read as two different features.
            // The dock is full (Models, Lighting, Animate, Recenter) and AR owns the
            // accent, so a fifth labelled dock item is not available — DESIGN.md caps the
            // dock at four plus the accent. The pill is theme-independent like every
            // other piece of chrome over a live viewport. Hidden without a Sketchfab key
            // (App Store / F-Droid builds): there is no catalogue to surprise anyone with.
            // CC-BY wants the author named wherever the model is shown (#4034): a Surprise pick
            // carries its credit while it is on screen, above the pill that rolled it.
            val credit = surprisePick?.first?.takeIf {
                !surpriseInFlight && streamedModel?.location == surprisePick?.second &&
                    streamedFileUrl == surprisePick?.second
            }
            if (hasSketchfabKey && credit != null) {
                GlassPill(Modifier.padding(horizontal = SceneViewTokens.Space.md)) {
                    Text(
                        text = stringResource(
                            R.string.demo_model_viewer_surprise_credit,
                            credit.displayName,
                            credit.author,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = io.github.sceneview.demo.theme.LocalStageChrome.current.onGlass,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            // HD pack: while an HD entry shows its stand-in, the pill says why and how far along
            // the real model is. Tapping it when nothing is running asks to download now, with the
            // size stated first. It leaves the moment the HD model is on screen.
            if (hdPillShown && hdAsset != null) {
                val status = hdStatus
                val hdFailed = hdLoadFailed || (hdFileLocation == null && status == HdPackStatus.Failed)
                val hdLabel = when {
                    hdFailed -> stringResource(R.string.hd_pill_failed)
                    hdFileLocation != null -> stringResource(R.string.hd_pill_loading)
                    status is HdPackStatus.Downloading ->
                        stringResource(R.string.hd_pill_downloading, (status.fraction * 100).toInt())
                    status == HdPackStatus.WaitingForWifi -> stringResource(R.string.hd_pill_waiting_wifi)
                    status == HdPackStatus.WaitingForNetwork -> stringResource(R.string.hd_pill_waiting_network)
                    else -> stringResource(
                        R.string.hd_pill_download,
                        hdPackSize(context, hdStore?.manifest?.totalBytes ?: hdAsset.bytes),
                    )
                }
                GlassActionPill(
                    icon = Icons.Outlined.HighQuality,
                    label = hdLabel,
                    onClick = {
                        // A file on disk that failed to load retries the load; anything else
                        // asks to download now, size first.
                        if (hdLoadFailed) hdRejectedLocation = null else hdDialogOpen = true
                    },
                    loading = !hdFailed && (hdFileLocation != null || status is HdPackStatus.Downloading),
                    progress = (hdStatus as? HdPackStatus.Downloading)?.fraction?.takeIf { hdFileLocation == null },
                    contentDescription = if (hdFileLocation == null) {
                        stringResource(R.string.hd_pill_hint)
                    } else {
                        hdLabel
                    },
                )
            }
            if (hasSketchfabKey) {
                GlassActionPill(
                    icon = Icons.Filled.Shuffle,
                    label = surpriseStage?.let { surpriseStageText(it) }
                        ?: stringResource(R.string.demo_model_viewer_surprise),
                    onClick = rollSurprise,
                    loading = surpriseInFlight,
                    progress = (surpriseStage as? SurpriseStage.Fetching)?.fraction,
                    contentDescription = stringResource(R.string.demo_model_viewer_surprise_hint),
                )
            }
            AnimatedVisibility(
                visible = animationBarOpen && animationNames.isNotEmpty(),
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
            // The staged file is called `opened-model` on disk, so AR cannot recover the user's
            // file name from the location it is handed. Carry it across explicitly.
            DemoSettings.openedModelDisplayName = openedModel?.displayName
            // #3493 — whatever the viewer is showing must be what AR opens on, never a picker.
            // `selectedModel.assetPath` covers every bundled row, including ones the AR
            // placement catalogue itself doesn't curate (the Damaged Helmet, #2023) — its name
            // has to ride along too, for AR to label a row the catalogue has no entry for. Set
            // unconditionally: a match against the curated catalogue uses ITS OWN name instead,
            // and this value is consumed once then cleared.
            DemoSettings.requestedModelDisplayName = openedModel?.displayName ?: selectedModel.displayName
            // An HD entry goes to AR as the HD file once it is the model on screen.
            // AR always gets the bundled stand-in, HD entries included: HD in AR waits for a
            // real-device proof (same decision on iOS).
            val model = openedModel?.location ?: selectedModel.assetPath
            DemoSettings.requestedRoute = "demo/ar-placement?model=$model"
        }, enabled = arSupported == true),
        chromeToggleOnTap = true,
        // The Lighting sheet is glass (#3827): the dock would show through it.
        dockHidden = environmentSheetOpen,
    ) {
        // The scene fills the viewport edge to edge; the chrome floats over it. Framing
        // therefore needs the band the chrome leaves visible: the identity row at the top
        // (provided by the scaffold) and the dock band plus the navigation bar at the bottom.
        //
        // #4053 — or the glass sheet over the scene, when one is open and taller than the dock:
        // the Lighting sheet, or the scaffold's settings sheet at its peek. Both are glass so the
        // model can be watched while it changes, and the model used to lose its lower third
        // under them. The framing below fits the band above the sheet, and `EasedFraming` flies
        // the camera there and back as the sheet opens and closes — the model is never moved.
        val topInset = LocalDemoChromeTopInset.current
        val sheetCover = maxOf(
            if (environmentSheetOpen) environmentSheetCover else 0.dp,
            LocalDemoSheetCover.current,
        )
        val bottomInset = maxOf(
            SETTINGS_FAB_RESERVED_SPACE + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            sheetCover,
        )
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
            // Published for the "Camera distance" slider. A `SideEffect`, not a
            // `LaunchedEffect`: the effect ran a frame after the framing it reported, so the
            // settle drop and the near plane below were sized for the previous model for a frame.
            SideEffect { framing?.let { if (autoFitRadius != it.distance) autoFitRadius = it.distance } }
            // Same frame as the framing itself, for the scene below.
            val fitRadius = framing?.distance ?: autoFitRadius
            // The framing the camera is aimed with: the live one, eased when it changes under a
            // model already on screen (see [EasedFraming]).
            val easedFraming = remember { EasedFraming() }
            val framingBlend = remember { Animatable(1f) }
            var framingEaseGeneration by remember { mutableStateOf(0) }
            SideEffect {
                if (easedFraming.retarget(framing, bounds, framingBlend.value)) framingEaseGeneration++
            }
            LaunchedEffect(framingEaseGeneration) {
                if (framingEaseGeneration == 0) return@LaunchedEffect
                framingBlend.snapTo(0f)
                easedFraming.release()
                renderInvalidator.requestRender()
                framingBlend.flyToOne(
                    FRAMING_SETTLE_MILLIS,
                    androidx.compose.animation.core.FastOutSlowInEasing,
                )
            }
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
            // Read by providers the manipulator captured at creation: everything it touches is
            // remembered or a state read, never this composition's `framing`.
            val aimFraming = { easedFraming.current(framingBlend.value) ?: liveFraming.value }
            val liveCenter = androidx.compose.runtime.rememberUpdatedState(modelCenter)
            val livePivot = {
                val f = aimFraming()
                val c = liveCenter.value
                if (f == null) c
                else Position(c.x, c.y + f.targetOffset.second, c.z + f.targetOffset.third)
            }
            // Keyed on the content alone (#3403 / #3404, see above) — NOT `recenterGeneration`.
            // Rebuilding on every recenter tap used to be how the flight got a fresh start, but a
            // fresh instance has no [EntranceCameraManipulator.flightStartEye] captured, so it fell
            // through to the cold-open's synthetic swung-off-axis geometry instead of the pose the
            // user had actually orbited to (#3622). `beginRecenterFlight` now does that job on the
            // SAME instance, from the dock's onClick above, before `recenterGeneration` even changes.
            val cameraManipulator = remember(activeModelInstance) {
                // The content this manipulator frames; `entranceProgress` only counts for it once
                // the entrance effect has claimed it (see `entranceOwner`).
                val ownContent = bounds
                EntranceCameraManipulator(
                    eye = {
                        val f = aimFraming()
                        val c = liveCenter.value
                        if (f == null) Position(c.x, c.y, c.z + 1.4f)
                        else Position(c.x, c.y + f.eyeOffset.second, c.z + f.eyeOffset.third)
                    },
                    target = livePivot,
                    progress = {
                        if (entranceOwner.get() === ownContent) entranceProgress.value else 0f
                    },
                    fitDistance = { liveFraming.value?.distance ?: 1.4f },
                    distanceOverride = { DemoSettings.cameraDistance },
                    // A pinch publishes its distance to the SAME state the slider writes, so the
                    // two controls agree and the readout follows the gesture.
                    onDistanceChange = { DemoSettings.cameraDistance = it },
                )
            }
            SideEffect { cameraManipulatorRef.value = cameraManipulator }
            // #3543 — the near plane moves with the subject. The library default is 1 cm, which
            // is in front of a metre-scale model and *behind* a millimetre-scale one: a 2 mm mesh
            // frames at ~5 mm, so a fixed 1 cm near plane clips it away entirely and no amount of
            // recentring brings it back. `viewerNearPlane` keeps the default for everything that
            // already worked and only tightens it for a subject smaller than it.
            val cameraNode = rememberCameraNode(engine)
            // In the frame the radius changes, not one later (see `fitRadius`).
            SideEffect {
                val near = DemoMath.viewerNearPlane(fitRadius)
                if (cameraNode.near != near) cameraNode.near = near
            }
            SceneView(
                modifier = Modifier.fillMaxSize(),
                renderInvalidator = renderInvalidator,
                onFrame = onFrame,
                engine = engine,
                modelLoader = modelLoader,
                environmentLoader = environmentLoader,
                environment = viewerEnvironment,
                // OFF: the camera is aimed at the measured bbox centre, see the framing notes.
                autoCenterContent = false,
                cameraNode = cameraNode,
                cameraManipulator = cameraManipulator,
            ) {
                activeModelInstance?.let { instance ->
                    // "Spin scene" turns the model about its bounding-box centre, not the glTF
                    // origin: counter-translate the pivot so the centre stays put under the camera.
                    // #3821 — `rotateAroundCentre` and `Rotation(y = …)` share the same sign
                    // convention (both clockwise in (x, z) viewed from +Y down, see
                    // `DemoMath.rotateAroundCentre`'s KDoc). Holding a local point C fixed under a
                    // node rotation of `modelYaw` needs `position = C - Rotate(modelYaw)·C`, i.e.
                    // the SAME signed angle passed to both calls. Passing `-modelYaw` here mismatched
                    // that pairing: the pivot no longer cancelled out, so the whole model swam off
                    // its centre as it spun — at a glance this read as the environment orbiting
                    // rather than a clean model-only spin.
                    // A bundled asset authored facing -Z turns to face the camera first (#3828);
                    // a streamed or opened file is shown as authored.
                    val yaw = modelYaw + if (streamedModelInstance == null) selectedModel.frontYaw else 0f
                    val (rx, rz) = DemoMath.rotateAroundCentre(modelCenter.x, modelCenter.z, yaw)
                    ModelNode(
                        modelInstance = instance,
                        // No `scaleToUnits` — the model renders at its true glTF size and the
                        // camera adapts to it (#1439). The settle spring drops the model in
                        // from slightly below its resting pose.
                        position = Position(
                            modelCenter.x - rx,
                            -fitRadius * 0.06f * (1f - fitProgress.value),
                            modelCenter.z - rz,
                        ),
                        rotation = Rotation(y = yaw),
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
        models = BUNDLED_VIEWER_MODELS,
        // A streamed or opened model is not one of the cards: outline none rather than the
        // bundled model it replaced.
        selectedKey = selectedModel.key.takeIf { streamedFileUrl == null },
        currentScene = null,
        onSelect = {
            HdPackPerfProbe.start(it.key)
            cancelSurprise(); onSelectModel(it); streamedFileUrl = null; modelSheetOpen = false
        },
        onScene = { cancelSurprise(); modelSheetOpen = false; onModeChange(it.mode()) },
        onDismiss = { modelSheetOpen = false },
    )
    if (hdDialogOpen && hdStore != null) {
        HdPackDownloadDialog(
            totalBytes = hdStore.manifest.totalBytes,
            onConfirm = { hdDialogOpen = false; HdPack.downloadNow(context) },
            onDismiss = { hdDialogOpen = false },
        )
    }
    if (unitSheetOpen && unitSuggestion != null) {
        val extents = bounds?.extents
        ModelUnitSheet(
            loadedExtentMeters = extents?.let { maxOf(it.x, it.y, it.z) } ?: 0f,
            suggested = unitSuggestion,
            loadedUnit = loadedUnit,
            onOpenAt = { unit ->
                unitSheetOpen = false
                unitAnswered = true
                val name = openedModel?.displayName ?: return@ModelUnitSheet
                scope.launch {
                    val reopened = withContext(Dispatchers.IO) {
                        OpenedModelIntent.reopenAt(context, name, unit)
                    }
                    if (reopened != null) {
                        loadedUnit = unit
                        streamedFileUrl = reopened.location
                        // The file is a different size now: drop the zoom override and re-fly the
                        // camera, or the model lands framed for the size it no longer is.
                        DemoSettings.cameraDistance = null
                        recenterGeneration++
                    }
                }
            },
            onDismiss = { unitSheetOpen = false; unitAnswered = true },
        )
    }
    if (environmentSheetOpen) EnvironmentSheet(
        environments = viewerEnvironments,
        selectedPath = requestedEnvironment.assetPath, intensity = iblIntensity, showEnvironment = showEnvironment,
        onSelect = { requestedEnvironment = it }, onIntensity = { iblIntensity = it }, onShowEnvironment = { showEnvironment = it },
        onReset = { requestedEnvironment = viewerEnvironments.first(); iblIntensity = 1f; showEnvironment = false },
        onDismiss = { environmentSheetOpen = false },
        onCoveredHeightChange = { environmentSheetCover = it },
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
 * Calling it unconditionally keeps its `produceState` in a fixed slot, so the State
 * invalidates the caller correctly and the chip transitions `Streaming → Streamed` the
 * moment the model is ready.
 *
 * Since #4034 it loads the model itself rather than through `rememberModelInstance`, to hand
 * it over only when it can be shown: textures uploaded, bounds finite and non-empty. Until
 * then the previous model stays on screen, and a file that fails either test is reported to
 * [onRejected] and never shown. The result carries the location it was loaded from, so the
 * caller can tell the new model from the previous one.
 */
@Composable
private fun rememberStreamedModelInstance(
    modelLoader: io.github.sceneview.loaders.ModelLoader,
    streamedFileUrl: String?,
    wakeRenderLoop: () -> Unit,
    onRejected: (location: String) -> Unit = {},
): StreamedModel? {
    val rejected = androidx.compose.runtime.rememberUpdatedState(onRejected)
    // One `produceState` in a stable slot, whatever the URL (#1464). It keeps its last value
    // across a key change, so the model on screen stays there while the next one loads.
    val presented = produceState<StreamedModel?>(initialValue = null, modelLoader, streamedFileUrl) {
        val location = streamedFileUrl ?: run {
            value = null
            return@produceState
        }
        var loaded: io.github.sceneview.model.ModelInstance? = null
        try {
            loaded = runCatching { modelLoader.loadModelInstance(location) }.getOrNull()
            currentCoroutineContext().ensureActive()
            // #4103 — a masked material cut off at 1.0 is discarded whole by Filament: the
            // Fantasy Butterfly framed as an empty stage. See [drawFullyOpaqueMaskedMaterials].
            loaded?.drawFullyOpaqueMaskedMaterials()
            // #4034 — the model is handed over once gltfio has uploaded its textures, not
            // before: a streamed model shown mid-upload is untextured blocks on the default
            // material, which is the "garbled model" of the report. The previous model stays
            // on screen meanwhile. The frame loop keeps pumping the upload whether or not the
            // model is in the scene (`ModelLoader.updateLoad`), but only while it runs: a model
            // that is not in the scene yet does not wake an on-demand loop, which then parked with
            // the upload half done until the timeout. Asking for a frame wakes it, and
            // `isLoading` keeps it awake from there.
            if (loaded != null) {
                withTimeoutOrNull(STREAMED_TEXTURES_TIMEOUT_MS) {
                    while (modelLoader.isLoading) {
                        wakeRenderLoop()
                        delay(SURPRISE_POLL_MS)
                    }
                }
            }
            if (loaded != null && loaded.hasFramableBounds()) {
                value = StreamedModel(location, loaded)
                loaded = null
            } else {
                rejected.value(location)
            }
        } finally {
            // Cancelled mid-load, or rejected: this instance never reached the screen.
            loaded?.let { modelLoader.destroyModel(it.model) }
        }
    }.value
    // `produceState` has no per-key disposal: destroy the previous model once it is replaced,
    // after the node that showed it has detached (#2459, #2424).
    androidx.compose.runtime.DisposableEffect(presented) {
        onDispose { presented?.let { modelLoader.destroyModel(it.instance.model) } }
    }
    return if (streamedFileUrl == null) null else presented
}

/** A streamed model on screen and the location it was loaded from. */
private class StreamedModel(val location: String, val instance: io.github.sceneview.model.ModelInstance)

/** Whether the model measures a finite, non-empty box the viewer can frame. */
private fun io.github.sceneview.model.ModelInstance.hasFramableBounds(): Boolean = runCatching {
    val box = model.boundingBox
    val half = box.halfExtent
    val center = box.center
    (half + center).all { it.isFinite() } && half.any { it > 0f }
}.getOrDefault(false)

/** Longest the viewer keeps the previous model up while a streamed one uploads its textures. */
private const val STREAMED_TEXTURES_TIMEOUT_MS = 20_000L

/**
 * The step a "Surprise me" roll is in (#3825). Each value is a stage the code is really
 * in — a byte stream, then a parse and texture upload — never a timed script. There is no
 * search step since #4034: the roll draws from [SURPRISE_POOL].
 */
private sealed interface SurpriseStage {
    /**
     * `SketchfabService.downloadModel` is in flight for [name]: a download, or a cache read
     * when [cached]. [totalBytes] is `-1` until the CDN reports a `Content-Length`.
     */
    data class Fetching(
        val name: String,
        val cached: Boolean,
        val bytesRead: Long = 0L,
        val totalBytes: Long = -1L,
    ) : SurpriseStage {
        /** Download progress in `0..1`, or `null` while the size is unknown. */
        val fraction: Float? get() = if (!cached && totalBytes > 0L) bytesRead.toFloat() / totalBytes else null
    }

    /** The GLB is on disk; gltfio is parsing it and uploading its textures. */
    data object Decoding : SurpriseStage
}

/** The narration line for [stage]. */
@Composable
private fun surpriseStageText(stage: SurpriseStage): String = when (stage) {
    is SurpriseStage.Fetching -> {
        val name = stage.name.shortModelName()
        when {
            stage.cached -> stringResource(R.string.demo_model_viewer_surprise_cached, name)
            stage.totalBytes > 0L -> stringResource(
                R.string.demo_model_viewer_surprise_downloading_sized,
                name,
                android.text.format.Formatter.formatShortFileSize(LocalContext.current, stage.totalBytes),
            )
            else -> stringResource(R.string.demo_model_viewer_surprise_downloading, name)
        }
    }
    SurpriseStage.Decoding -> stringResource(R.string.demo_model_viewer_surprise_decoding)
}

/**
 * A Sketchfab title short enough to leave room for the rest of the sentence on one line.
 *
 * Cut at a word boundary and before an unclosed parenthesis, with no ellipsis of its own:
 * the narration line already ends in one, and "Winter Girl (free dow…" followed by the
 * animated dots read as a double ellipsis on the emulator.
 */
private fun String.shortModelName(): String {
    val name = trim()
    if (name.length <= SURPRISE_NAME_MAX_CHARS) return name
    val cut = name.take(SURPRISE_NAME_MAX_CHARS).let { it.substringBeforeLast(' ', it) }
    val openParen = cut.lastIndexOf('(')
    val balanced = if (openParen > 0 && cut.indexOf(')', openParen) < 0) cut.take(openParen) else cut
    return balanced.trimEnd().ifEmpty { name.take(SURPRISE_NAME_MAX_CHARS) }
}

private const val SURPRISE_NAME_MAX_CHARS = 22

/** How long a post-download step may run before the roll gives up narrating it. */
private const val SURPRISE_STEP_TIMEOUT_MS = 30_000L

private const val SURPRISE_POLL_MS = 100L

/**
 * Surprise-me coroutine (#4034). Takes the next model from the shuffle bag over
 * [SURPRISE_POOL], streams it through [SketchfabService.downloadModel] into the on-disk cache,
 * and returns the entry with its `file://` URL, or `null` when nothing could be fetched: the
 * model on screen then simply stays.
 *
 * A download is abandoned past [SURPRISE_MAX_BYTES] or [SURPRISE_DOWNLOAD_TIMEOUT_MS]; the one
 * retry asks the bag for a model already on disk, so a bad network costs one attempt, not the
 * old fall-through across three searches. A download the [prefetch] already has in flight is
 * awaited rather than started twice.
 *
 * [onStage] is told each step as it starts, and the download's byte count as it streams (from
 * `Dispatchers.IO` — snapshot state is safe to write from there).
 */
private suspend fun pickSurpriseModel(
    service: SketchfabService,
    prefetch: SurprisePrefetch,
    onStage: (SurpriseStage) -> Unit,
): Pair<SketchfabSlug, String>? {
    val bag = SurpriseRolls.bag
    repeat(SURPRISE_ATTEMPTS) { attempt ->
        val slug = (if (attempt == 0) bag.next() else bag.next { service.isCached(it.uid) }) ?: return null
        onStage(SurpriseStage.Fetching(slug.displayName, cached = service.isCached(slug.uid)))
        val file = withTimeoutOrNull(SURPRISE_DOWNLOAD_TIMEOUT_MS) {
            runCatching {
                prefetch.inFlight(slug.uid)?.await() ?: service.downloadModel(slug.uid) { read, total ->
                    checkSurpriseSize(read, total)
                    onStage(
                        SurpriseStage.Fetching(slug.displayName, cached = false, bytesRead = read, totalBytes = total),
                    )
                }
            }.getOrNull()
        }
        // `runCatching` swallows a cancellation too: a roll the user overrode stops here.
        currentCoroutineContext().ensureActive()
        if (file != null) return slug to file.toURI().toString()
    }
    return null
}

/** One download, then one retry from the cache. */
private const val SURPRISE_ATTEMPTS = 2

/** The pool's largest file is 5.2 MB: past this, the network is the problem, not the file. */
private const val SURPRISE_DOWNLOAD_TIMEOUT_MS = 15_000L

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
// same curated-English name the Credits sheet lists, so a registry edit
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
// Framing fits the whole formation (#3923): the camera is placed per viewport
// from the formation's layout bounds, before any model loads — see [parkCamera].
// Until #3923 it covered the frame with the tallest model instead, which put the
// lens inside the streamed trees and cropped the fallbacks to two lanterns
// (#2913 had made that distance aspect-aware). Before #2913 the
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
    onOpenModel: (BundledViewerModel) -> Unit,
) {
    var modelSheetOpen by remember { mutableStateOf(false) }
    // #3822 — `mode` (Single/Multi) lives in the parent `ModelViewerDemo` composable,
    // not on the Android back stack, so without this the raw `onBack` handed down from
    // `MainActivity` skipped straight past "Park scene" to the Showcase home on one press.
    // Close the sheet first if it is open, otherwise step back to the single-model view —
    // one level at a time, like every other back gesture in the app.
    BackHandler {
        if (modelSheetOpen) modelSheetOpen = false else onModeChange(ModelViewerMode.Single)
    }
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

    // Same asset-source vocabulary as the other streamed demos, and what keeps the chip
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

    val firstFrame = rememberFirstFrameState(engine)

    DemoScaffold(
        title = stringResource(R.string.demo_multi_model_title),
        onBack = onBack,
        assetSource = assetSource,
        firstFrameRendered = firstFrame.rendered,
        dock = listOf(DockItem(Icons.Outlined.Category, "Models", { modelSheetOpen = true })),
        controls = {
            Text("Visibility", style = MaterialTheme.typography.labelLarge)
            // Labels come from the resolved slug's curated-English `displayName`
            // (the registry's own name), never from a hardcoded noun — the
            // registry decides what stands in each slot, so it decides the label
            // too. Horizontally scrolling because
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
        // Same visible band as the single-model section: the identity row at the top, the dock
        // band plus the navigation bar at the bottom.
        val topInset = LocalDemoChromeTopInset.current
        val bottomInset = SETTINGS_FAB_RESERVED_SPACE +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // BoxWithConstraints, not Box: the framing below depends on the viewport (#2913).
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // The whole formation, fitted from its layout bounds before any model loads (#3923),
            // so nothing moves the camera when the models land. See [parkCamera].
            //
            // The insets are measured and can settle a frame or two after the first composition.
            // They are followed until the first frame is on screen, then held: once the scene is
            // visible only a new viewport size (rotation, fold, split screen) re-frames it — a new
            // manipulator, eased into by the section's one camera writer rather than cut to.
            //
            // `camera_distance` / `?cameraDistance=` still wins when a QA flow or a store capture
            // sets it — the same override every hero-orbit demo honours. Before #2913 this section
            // ignored it, so the store script's `--ef camera_distance 6.0` was a silent no-op.
            val insetsKey = if (firstFrame.rendered.value) null else topInset to bottomInset
            val distanceOverride = DemoSettings.cameraDistance
            val parkView = remember(maxWidth, maxHeight, insetsKey, distanceOverride) {
                parkCamera(
                    viewportWidth = maxWidth.value,
                    viewportHeight = maxHeight.value,
                    topInset = topInset.value,
                    bottomInset = bottomInset.value,
                    distanceOverride = distanceOverride,
                )
            }
            val parkManipulator = remember(parkView) {
                createDefaultCameraManipulator(
                    eyePosition = parkView.eye,
                    targetPosition = parkView.target,
                )
            }
            val cameraManipulator = rememberContinuousCameraManipulator().driving(parkManipulator)
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
                // pivot, so it sweeps a small circle rather than staying put, and the framing
                // (PARK_BOUNDS, the formation at rest) lets its corners brush the edges mid-spin.
                // Per-model rotation cancels the yaw on its own Y so each piece keeps facing
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
            // Narrates the two real phases (#3825): the resolver fetching each slot's file
            // (network, cache or bundled stand-in), then gltfio parsing it. The counts are
            // read off the same lists the scene renders from.
            val filesReady = files.count { it != null }
            val modelsReady = instances.count { it != null }
            LoadingScrim(
                loading = !allLoaded,
                label = if (filesReady < PARK_SLOTS.size) {
                    stringResource(R.string.demo_multi_model_loading_fetching, filesReady, PARK_SLOTS.size)
                } else {
                    stringResource(R.string.demo_multi_model_loading_decoding, modelsReady, PARK_SLOTS.size)
                },
            )
        }
    }
    // The same sheet as the single-model section's, with every bundled model (#3828 — it used to
    // offer the Damaged Helmet alone). A card opens that model on the single-model stage.
    if (modelSheetOpen) ModelPickerSheet(
        models = BUNDLED_VIEWER_MODELS,
        selectedKey = null,
        currentScene = ViewerScene.Park,
        onSelect = { modelSheetOpen = false; onOpenModel(it) },
        onScene = { modelSheetOpen = false; onModeChange(it.mode()) },
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
