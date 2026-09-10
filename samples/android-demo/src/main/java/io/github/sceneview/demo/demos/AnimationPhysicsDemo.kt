package io.github.sceneview.demo.demos

import io.github.sceneview.node.PhysicsBody
import io.github.sceneview.node.FloorProvider
import kotlin.math.sqrt
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.theme.SceneViewTokens
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.android.filament.LightManager
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.initialDemoMode
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.sketchfab.SampleAssets
import io.github.sceneview.demo.sketchfab.SketchfabAssetResolver
import io.github.sceneview.demo.sketchfab.SketchfabSlug
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.math.Transform
import io.github.sceneview.model.Model
import io.github.sceneview.node.ModelNode as ModelNodeImpl
import io.github.sceneview.node.SphereNode as SphereNodeImpl
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.LifecyclePausingLaunchedEffect
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.ui.LabeledSlider
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unified "Animation & Physics" demo — consolidates the retired `animation` and
 * `physics` demos behind a single segmented-button toggle (#2239 Batch 3).
 *
 * - **Animation** (default) — skeletal / keyframe animation playback with a model
 *   carousel, cinematic camera shots, and play / pause / speed / loop controls.
 *   (Formerly `animation`.)
 * - **Physics** — rigid-body simulation: drop streamed crash-test bodies (or
 *   bundled spheres) that fall under gravity and bounce off the floor. (Formerly
 *   `physics`.)
 *
 * Each sub-mode owns its own `SceneView` + its own [rememberEngine] / loaders,
 * so switching tabs tears down the inactive section completely — no engine is
 * hoisted above the `when`, which is what prevents resource leaks across tab
 * switches (Batch 1 review confirmed this pattern). Old deep links route
 * through [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
@Composable
fun AnimationPhysicsDemo(onBack: () -> Unit) {
    var mode by remember {
        mutableStateOf(initialDemoMode(AnimationPhysicsMode.entries, AnimationPhysicsMode.Animation))
    }
    when (mode) {
        AnimationPhysicsMode.Animation -> AnimationSection(onBack, mode) { mode = it }
        AnimationPhysicsMode.Physics -> PhysicsSection(onBack, mode) { mode = it }
    }
}

private enum class AnimationPhysicsMode(@StringRes val labelRes: Int) {
    Animation(R.string.demo_animation_physics_mode_animation),
    Physics(R.string.demo_animation_physics_mode_physics),
}

@Composable
private fun ModeSelector(
    current: AnimationPhysicsMode,
    onModeChange: (AnimationPhysicsMode) -> Unit,
) {
    val modes = AnimationPhysicsMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, m ->
            SegmentedButton(
                selected = m == current,
                onClick = { onModeChange(m) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                label = { Text(stringResource(m.labelRes)) },
            )
        }
    }
    Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))
}

// ─── Animation section ──────────────────────────────────────────────────────
// Formerly AnimationDemo.
//
// Demonstrates model animation playback controls: play/pause, speed, and loop mode.
//
// The `autoAnimate` parameter on `ModelNode` is only read once at node creation, and
// the composable's reactive `animationName` path doesn't re-key on speed/loop
// changes — so we drive the animation state imperatively through a `LaunchedEffect`
// that watches (isPlaying, speed, loop) and calls `playAnimation` / `stopAnimation`
// on a captured node reference. That gives the three chips a real effect.
//
// Cinematic camera (Phase 3 — real cinematic shots)
// --------------------------------------------------
// The four scripted modes are not generic primitives ("orbit", "dolly", "crane") any
// more — each one is a real cinematic shot type with its own keyframed choreography:
//
//   - HERO    — slow heroic low-angle orbit with a 2 s pause at the front-3/4 hold
//   - REVEAL  — close-up chest framing pulls back to a slight high-angle wide shot
//   - VERTIGO — Hitchcock dolly-zoom (radius and FOV move in opposite directions)
//   - TRACKING — straight-line lateral pass with a per-frame lookAt back at the subject
//   - FREE    — user gesture only (DefaultCameraManipulator), no scripted motion
//
// The single [ScriptedCameraManipulator] is parameterized on lambdas (yaw, radius,
// yHeight, *and* a full-eye override) so TRACKING can leave the orbit circle and
// move along a straight line while still re-aiming at the subject every frame.
//
// FOV control (for the dolly-zoom shot) goes through the SDK's [CameraNode] —
// we own the camera node via [rememberCameraNode] and call `setProjection(fov, ...)`
// each composition pass, driven by an [Animatable]. Since `setProjection` clamps
// silently (0 < fov < 180) and only no-ops while `view == null`, this is safe to call
// before the first frame.
private enum class CameraMode { HERO, REVEAL, VERTIGO, TRACKING, FREE }

/**
 * One slot in the carousel of animated models.
 *
 * Exactly one of [bundledAssetPath] / [streamedSlug] is non-null. Bundled
 * entries are read straight from `assets/`; streamed entries resolve through
 * [SketchfabAssetResolver] and fall back to the registry's bundled asset when
 * the API key is missing (App Store builds, cold cache, network down).
 *
 * `scaleToUnits` is the ground-up height of the model in metres — picked so all
 * five carousel models read at roughly the same screen size in the existing
 * cinematic framing (camera at y=0.5, radius 3.5 m). The cinematic shots and
 * IBL slider stay model-agnostic so swapping models doesn't break the framing.
 */
private data class AnimationModel(
    @StringRes val nameRes: Int,
    val bundledAssetPath: String? = null,
    val streamedSlug: SketchfabSlug? = null,
    val scaleToUnits: Float,
    /**
     * Default animation index to play when the carousel lands on this model.
     * Negative means "auto-play index 0 if any animation exists" — used for
     * streamed models we can't introspect statically (their animation count
     * is only known after the GLB loads).
     */
    val defaultAnimationIndex: Int = -1,
) {
    init {
        require((bundledAssetPath == null) != (streamedSlug == null)) {
            "AnimationModel must define exactly one of bundledAssetPath or streamedSlug."
        }
    }
}

/**
 * Carousel of 5 animated models for [AnimationSection].
 *
 * Slot 0 is the historical `threejs_soldier.glb` (bundled, 4 animations:
 * 0=Idle, 1=Run, 2=TPose, 3=Walk). The next 4 slots stream the 4 entries from
 * the `animation` category of [SampleAssets] — each carries at least one
 * baked animation so the play/pause/speed/loop controls have something to
 * drive.
 *
 * Streamed slots fall back to their registered bundled GLB when offline so
 * the demo always renders five carousel options (the bundled fallback for
 * three of the four streamed slugs is `threejs_soldier.glb` / `shiba.glb` /
 * `khronos_fox.glb` — those still ship in the APK).
 */
private val ANIMATION_MODELS: List<AnimationModel> = run {
    val walkingRobot = SampleAssets.byUid["574e006a4e50408d9565e82fafe8ef19"]
    val dancingKnight = SampleAssets.byUid["ad9bc16464744935b1ac9b7768a17474"]
    val idleCat = SampleAssets.byUid["7190ff66cb3d4e729a2ab95aeb9e797f"]
    val sleepingFox = SampleAssets.byUid["cc4ab41731cc4c94a6adf2983821d1a8"]
    listOf(
        AnimationModel(
            nameRes = R.string.demo_animation_physics_subject_soldier,
            bundledAssetPath = "models/threejs_soldier.glb",
            scaleToUnits = 1.0f,
            // 3 = "Walk" — the strongest first-impression animation on the
            // soldier rig (the cinematic REVEAL pull-back pairs with a walking
            // subject). Kept as the historical default for visual stability.
            defaultAnimationIndex = 3,
        ),
        AnimationModel(
            nameRes = R.string.demo_animation_physics_subject_robot,
            streamedSlug = walkingRobot,
            scaleToUnits = 1.30f,
        ),
        AnimationModel(
            nameRes = R.string.demo_animation_physics_subject_knight,
            streamedSlug = dancingKnight,
            scaleToUnits = 1.45f,
        ),
        AnimationModel(
            nameRes = R.string.demo_animation_physics_subject_cat,
            streamedSlug = idleCat,
            scaleToUnits = 0.40f,
        ),
        AnimationModel(
            nameRes = R.string.demo_animation_physics_subject_fox,
            streamedSlug = sleepingFox,
            scaleToUnits = 0.55f,
        ),
    )
}

@OptIn(ExperimentalSceneViewApi::class)
@Composable
private fun AnimationSection(
    onBack: () -> Unit,
    mode: AnimationPhysicsMode,
    onModeChange: (AnimationPhysicsMode) -> Unit,
) {
    // Index into [ANIMATION_MODELS] — defaults to the historical soldier so the
    // first frame looks identical to v4.3.1 when the carousel is unused.
    var selectedModelIndex by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(1f) }
    var loop by remember { mutableStateOf(true) }
    // Animation index inside the *current* model. Reset to the per-model default
    // when the carousel switches.
    var selectedAnim by remember { mutableIntStateOf(ANIMATION_MODELS[0].defaultAnimationIndex.coerceAtLeast(0)) }
    // Default cinematic shot is REVEAL — close-up to wide pull-back is the most
    // dramatic intro and pairs naturally with a walking subject.
    var cameraMode by remember { mutableStateOf(CameraMode.REVEAL) }
    // IBL intensity — exposed as a slider so users can dial atmospheric vs neutral.
    // Default 10_000 lux matches SceneView's balanced IBL default (#1075). The
    // rooftop_night skybox renders at full HDR luminance, so a lower IBL left the
    // model reading as a black silhouette against the bright sky (#1468). Range
    // 0–10_000 still lets users dial down to a darker, atmospheric look.
    var iblIntensity by remember { mutableFloatStateOf(10_000f) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val context = LocalContext.current

    // Resolve every streamed slug exactly once per composition. Bundled-only
    // models pass through (`null`) and are loaded via the asset-path overload
    // of `rememberModelInstance`. The streamed slot flips from `null` (download
    // / fallback-copy still running on IO) to a real [File] once the resolver
    // returns. ANIMATION_MODELS is a `val` constant so the call order is stable.
    val streamedFiles: List<File?> = ANIMATION_MODELS.mapIndexed { index, model ->
        val slug = model.streamedSlug
        if (slug == null) {
            null
        } else {
            produceState<File?>(initialValue = null, key1 = slug.uid, key2 = index) {
                value = runCatching {
                    SketchfabAssetResolver.getInstance(context).resolve(slug)
                }.getOrNull()
            }.value
        }
    }

    val activeModel = ANIMATION_MODELS[selectedModelIndex]
    val activeFileLocation: String? = when {
        activeModel.bundledAssetPath != null -> activeModel.bundledAssetPath
        else -> streamedFiles.getOrNull(selectedModelIndex)?.let { "file://${it.absolutePath}" }
    }
    val modelInstance = if (activeFileLocation != null) {
        // `activeFileLocation` is EITHER a bundled `assets/`-relative path OR a streamed
        // `file://…` URI. The named `fileLocation =` argument binds to the URL-capable
        // `rememberModelInstance` overload, which scheme-detects: asset paths go through the
        // fast asset reader, `file://` URIs through `ModelLoader.loadModelInstance`. The
        // two-arg positional call would bind to the asset-path overload instead and feed the
        // `file://` string to `AssetManager.open`, which throws — the streamed model would
        // silently stay `null` forever (#1422 / the #2302 overload trap).
        rememberModelInstance(modelLoader, fileLocation = activeFileLocation)
    } else null

    // Re-pin the animation track to the new model's default whenever the
    // carousel switches. We can't always know the streamed model's animation
    // count up-front, so we fall back to 0 and let the play/pause LaunchedEffect
    // below clamp out-of-range indices.
    LaunchedEffect(selectedModelIndex) {
        selectedAnim = activeModel.defaultAnimationIndex.coerceAtLeast(0)
    }

    // HDR environment matching the sci-fi tactical Vanguard soldier — urban rooftop at
    // dusk gives a dramatic atmospheric backdrop. Skybox enabled so the sky and city
    // silhouette are visible behind the soldier (cinematic), not just a black void.
    val hdrEnvironment = rememberHDREnvironment(
        environmentLoader,
        "environments/rooftop_night_2k.hdr",
        createSkybox = true,
    )
    val fallbackEnvironment = rememberEnvironment(environmentLoader)
    val activeEnvironment = hdrEnvironment ?: fallbackEnvironment

    // Pin the IBL intensity to the slider value. The rooftop_night skybox renders at
    // full HDR luminance, so the IBL must stay near SceneView's balanced 10k default
    // to keep the soldier lit in step with the bright sky behind it (#1468) — a lower
    // value left the model looking like an unlit black silhouette. Re-runs whenever the
    // active environment OR the slider value change, so dragging it updates in real time.
    LaunchedEffect(activeEnvironment, iblIntensity) {
        activeEnvironment.indirectLight?.intensity = iblIntensity
    }

    // Captured ref to the ModelNode once it's created — used by the LaunchedEffect
    // below to drive play/pause/speed/loop imperatively.
    val modelNodeRef = remember { androidx.compose.runtime.mutableStateOf<ModelNodeImpl?>(null) }

    val node = modelNodeRef.value
    val animationNames = remember(node) {
        if (node == null) emptyList() else (0 until node.animationCount).map { index ->
            node.animator.getAnimationName(index).orEmpty().ifBlank {
                context.getString(R.string.demo_animation_physics_clip_fallback, index + 1)
            }
        }
    }
    val duration = remember(node, selectedAnim) {
        if (node != null && selectedAnim in animationNames.indices) {
            node.animator.getAnimationDuration(selectedAnim)
        } else 0f
    }
    var clipTime by remember(node, selectedAnim) { mutableFloatStateOf(0f) }
    var blendIndex by remember(node, selectedAnim) {
        mutableIntStateOf(animationNames.indices.firstOrNull { it != selectedAnim } ?: selectedAnim)
    }
    var blendWeight by remember(node, selectedAnim) { mutableFloatStateOf(0f) }
    val previousFrame = remember(node, selectedAnim, isPlaying, DemoSettings.qaMode) { longArrayOf(0L) }
    LaunchedEffect(node, selectedAnim, DemoSettings.qaMode) {
        node ?: return@LaunchedEffect
        for (index in 0 until node.animationCount) node.stopAnimation(index)
        if (node.animationCount > 0 && selectedAnim !in animationNames.indices) selectedAnim = 0
    }
    val clipName = animationNames.getOrNull(selectedAnim)
        ?: stringResource(R.string.demo_animation_physics_no_clip)

    // Cinematic camera framing — a Pixel 7a portrait viewport (~1080x1500 after the
    // controls panel) needs the soldier framed head-to-toe with margins. The model is
    // 1 m tall (scaleToUnits=1.0) centered at origin, so y goes from -0.5 to +0.5.
    //
    // baseRadius = 3.5 m   → soldier height ≈ 50% of viewport at default 28 mm focal
    //                        length (≈45° vertical FOV) — comfortable margins both sides
    // baseYHeight = 0.0    → camera at the soldier's vertical center for symmetric
    //                        framing (head and feet equidistant from frame edges)
    // target = (0, 0.0, 0) → look-at point at the soldier's center of mass
    // baseRadius / baseYHeight / defaultFovDegrees are sourced from DemoMath so the
    // pure-JVM cinematic-camera tests (AnimationDemoStateMachineTest, issue #880)
    // assert against the same constants the demo actually renders with.
    val baseRadius = DemoMath.BASE_RADIUS
    val baseYHeight = DemoMath.BASE_Y_HEIGHT
    // The soldier is lifted so feet rest on y=0; its bbox center (chest) is at y=0.5 in
    // world space. Camera target lives at chest height so all modes frame the upper body
    // naturally and the rooftop ground line aligns visually with the soldier's feet.
    val target = remember { Position(0f, 0.5f, 0f) }

    // Default lens FOV (vertical, degrees). Filament's default focal length of 28 mm
    // works out to ~46° vertical FOV on a phone aspect — we drive this directly so the
    // VERTIGO shot can compress/expand it while radius moves in opposition.
    val defaultFovDegrees = DemoMath.DEFAULT_FOV_DEGREES

    // Animatables that drive the scripted camera. yaw/radius/yHeight are spherical
    // coordinates, eyeOverride lets TRACKING bypass them with an absolute position,
    // and fovAnim is wired straight into cameraNode.setProjection.
    val yawAnim = remember { Animatable(0f) }
    val radiusAnim = remember { Animatable(baseRadius) }
    val yHeightAnim = remember { Animatable(baseYHeight) }
    val fovAnim = remember { Animatable(defaultFovDegrees) }
    // Tracking shot's straight-line eye position — when non-null the manipulator uses
    // it instead of (yaw,radius,yHeight). null means scripted spherical mode is active.
    val trackingEye = remember { androidx.compose.runtime.mutableStateOf<Position?>(null) }

    // Cinematic easings — FastOutSlowInEasing is Material's standard, EaseInOutCubic
    // is a slightly more dramatic S-curve we use for the hero pause-and-resume.
    val easeInOutCubic: Easing = remember { CubicBezierEasing(0.65f, 0.0f, 0.35f, 1.0f) }
    val easeOutQuart: Easing = remember { CubicBezierEasing(0.25f, 1.0f, 0.5f, 1.0f) }

    // ---------------------------------------------------------------------------
    // Mode driver: each case below is a self-contained cinematic loop. We
    // canonicalize all Animatable values at entry, then run a `while (true)`
    // sequence of `animateTo` calls. Cancellation comes for free because Compose
    // tears down the effect when `cameraMode` changes.
    //
    // Lifecycle pausing (#974): this uses `LifecyclePausingLaunchedEffect`, the
    // STATE-PRESERVING lifecycle helper — not `LifecycleAwareLaunchedEffect`.
    // The plain `repeatOnLifecycle` helper re-runs the block from the top on
    // every `onStart`, and every `when (cameraMode)` arm begins with
    // `xAnim.snapTo(initialValue)`, so a user who Alt-Tabs mid-orbit would see
    // the camera teleport back to yaw=0 on return (#936 review → migration
    // reverted in `de692709`). `LifecyclePausingLaunchedEffect` instead hands
    // the body a `gate` whose `awaitResumed()` SUSPENDS the running coroutine
    // in place while backgrounded: the coroutine is never cancelled, every
    // `Animatable.value` is preserved, and the loop resumes exactly where it
    // paused. We drop one `gate.awaitResumed()` before each tween/delay so the
    // loop parks on a clean boundary — stopping the per-frame Compose snapshot
    // writes that were the residual background drain (~5-10 mW, #936).
    // ---------------------------------------------------------------------------
    LifecyclePausingLaunchedEffect(cameraMode, DemoSettings.qaMode) { gate ->
        // QA freeze — match the hero-orbit helper so screenshot tests stay stable.
        if (DemoSettings.qaMode) {
            yawAnim.snapTo(45f)
            radiusAnim.snapTo(baseRadius)
            yHeightAnim.snapTo(baseYHeight)
            fovAnim.snapTo(defaultFovDegrees)
            trackingEye.value = null
            return@LifecyclePausingLaunchedEffect
        }

        // Reset overrides on every mode switch so previous mode state doesn't bleed in.
        trackingEye.value = null
        fovAnim.snapTo(defaultFovDegrees)

        when (cameraMode) {
            CameraMode.HERO -> {
                // Heroic eyes-level orbit. Previously sat at yHeight 0.15 m which gave a
                // monument low-angle shot — visually striking but cropped the soldier's
                // head on portrait phones because we were looking sharply UP. Bumped to
                // 0.55 m so the camera sits ~10 cm above the soldier's chest target
                // (target.y=0.5), placing the lens roughly at his eyes — natural framing
                // that keeps head-and-feet in frame across all viewport aspect ratios.
                // We rotate slowly (25 s nominal) but break the loop into 4 segments
                // with a 2 s hold at the front-3/4 angle (≈45°) for a cinematic beat.
                radiusAnim.snapTo(baseRadius + 0.2f)
                yHeightAnim.snapTo(0.55f)
                while (true) {
                    yawAnim.snapTo(0f)
                    // Quarter 1: 0° → 45° (front-3/4) over 5 s, ease-in-out.
                    // gate.awaitResumed() parks on a clean boundary while the
                    // app is backgrounded — yaw is preserved, no teleport.
                    gate.awaitResumed()
                    yawAnim.animateTo(45f, tween(5_000, easing = easeInOutCubic))
                    // Hold the front-3/4 angle for 2 s (the cinematic beat).
                    // animateTo to the same value returns immediately, so use delay.
                    kotlinx.coroutines.delay(2_000)
                    // Quarter 2: 45° → 180° over 8 s, ease-out
                    gate.awaitResumed()
                    yawAnim.animateTo(180f, tween(8_000, easing = easeOutQuart))
                    // Half: 180° → 360° over 10 s, ease-in-out
                    gate.awaitResumed()
                    yawAnim.animateTo(360f, tween(10_000, easing = easeInOutCubic))
                }
            }

            CameraMode.REVEAL -> {
                // Close-up at the chest, then pull back smoothly to a wide high-angle.
                // No yaw motion — the dolly-out IS the shot. We hold yaw at a slight
                // off-axis angle (15°) so we never look at the model dead-on.
                //
                // Camera Y is `target.y + yHeight` = 0.5 + yHeight. Soldier head is at
                // y≈1.0, feet at y=0. To keep him visually grounded with the rooftop
                // floor visible underneath, camera must sit above his head (yHeight ≥
                // 0.6 → camY ≥ 1.1) for the whole shot. Previous values 0.5 → 0.8 put
                // the camera at chest-to-shoulder height, which made the soldier look
                // like he was floating because his feet drifted out of frame on the
                // close-up. Bumped to 0.9 → 1.2 (camY 1.4 → 1.7) so we look slightly
                // DOWN at the soldier and the rooftop ground line is always visible.
                yawAnim.snapTo(15f)
                while (true) {
                    // Snap to the close-up start
                    radiusAnim.snapTo(1.5f)
                    yHeightAnim.snapTo(0.9f)
                    // Park on the close-up boundary while backgrounded.
                    gate.awaitResumed()
                    // 6 s pull-back to wide, ease-in-out — matches a real dolly-out
                    val pullBack = tween<Float>(6_000, easing = FastOutSlowInEasing)
                    val sync = launch { radiusAnim.animateTo(5.0f, pullBack) }
                    yHeightAnim.animateTo(1.2f, pullBack)
                    sync.join()
                    // 2 s hold on the wide shot before looping (delay, not animateTo
                    // — the latter returns immediately when target == current).
                    kotlinx.coroutines.delay(2_000)
                }
            }

            CameraMode.VERTIGO -> {
                // Hitchcock dolly-zoom: camera moves AWAY (radius increases) while
                // FOV NARROWS — keeping the subject the same on-screen size while
                // the background appears to compress. Then reverse for the vertigo-out.
                yawAnim.snapTo(20f)
                yHeightAnim.snapTo(baseYHeight)
                while (true) {
                    radiusAnim.snapTo(2.0f)
                    fovAnim.snapTo(60f)
                    // Park on the vertigo-start boundary while backgrounded.
                    gate.awaitResumed()
                    // Vertigo IN: 10 s. Radius grows 2 → 5, FOV shrinks 60 → 25.
                    // The subject stays roughly the same screen size; the background
                    // appears to crush in. Easing: gentle ease-in-out for the build.
                    val vIn = tween<Float>(10_000, easing = easeInOutCubic)
                    val syncR = launch { radiusAnim.animateTo(5.0f, vIn) }
                    fovAnim.animateTo(25f, vIn)
                    syncR.join()
                    // Hold at the extreme for 1 s — lets the eye register the warp.
                    kotlinx.coroutines.delay(1_000)
                    // Vertigo OUT: 8 s. Reverse — radius 5 → 2, FOV 25 → 60.
                    gate.awaitResumed()
                    val vOut = tween<Float>(8_000, easing = easeInOutCubic)
                    val syncR2 = launch { radiusAnim.animateTo(2.0f, vOut) }
                    fovAnim.animateTo(60f, vOut)
                    syncR2.join()
                    // Hold close-up for 1 s before looping.
                    kotlinx.coroutines.delay(1_000)
                }
            }

            CameraMode.TRACKING -> {
                // Lateral tracking shot — camera flies past the subject in a straight
                // line, lookAt re-aims every frame. We sweep along the X axis from
                // -4 m → +4 m at a fixed Z standoff of 2.5 m, so the soldier is always
                // centered in frame as the camera passes.
                //
                // Camera Y is absolute here (eyeOverride bypasses spherical math). The
                // soldier's feet sit at y=0 and head at y≈1.0; previous yLevel=0.4 was
                // BELOW his chest (target.y=0.5), so we ended up looking UP at him with
                // the floor cropped — soldier appeared to float. Bumped to 1.4 (above
                // his head) so the shot looks slightly DOWN, keeping the rooftop ground
                // line visible underneath the walking soldier.
                val zStandoff = 2.5f
                val yLevel = 1.4f
                val startX = -4.0f
                val endX = 4.0f
                // Reuse a single Animatable across loop iterations — animateTo will
                // mutate `value` continuously and we publish each step into trackingEye
                // via a child coroutine that observes via snapshotFlow.
                val xAnim = Animatable(startX)
                val publisher = launch {
                    androidx.compose.runtime.snapshotFlow { xAnim.value }.collect { x ->
                        trackingEye.value = Position(x, yLevel, zStandoff)
                    }
                }
                try {
                    while (true) {
                        xAnim.snapTo(startX)
                        // Park on the sweep-start boundary while backgrounded.
                        gate.awaitResumed()
                        // 8 s lateral sweep, ease-in-out so the pass accelerates
                        // smoothly and decelerates at the end (real dolly track feel).
                        xAnim.animateTo(
                            targetValue = endX,
                            animationSpec = tween(8_000, easing = easeInOutCubic),
                        )
                        // 1 s pause off-frame before resetting (instant teleport back).
                        kotlinx.coroutines.delay(1_000)
                    }
                } finally {
                    publisher.cancel()
                }
            }

            CameraMode.FREE -> {
                // Free hands control to the user-gesture manipulator below; nothing
                // to drive here. The Animatables retain their last values so the
                // initial fallback eye matches where the cinematic motion stopped.
            }
        }
    }

    // Camera node — we own it ourselves so VERTIGO can drive `setProjection(fov)`.
    // The default FOV is 45° vertical (a 50 mm-equivalent "natural" cinema lens).
    val cameraNode = rememberCameraNode(engine)

    // Drive the FOV from the Animatable. We use a snapshotFlow so the call only
    // fires when fovAnim.value actually changes, instead of restarting a coroutine
    // on every frame. setProjection silently no-ops while the SceneView's internal
    // `view` field is null, so it's safe to call even before the first frame.
    LaunchedEffect(cameraNode) {
        androidx.compose.runtime.snapshotFlow { fovAnim.value }.collect { fov ->
            cameraNode.setProjection(fovInDegrees = fov.toDouble())
        }
    }

    // Single manipulator drives all scripted modes via provider lambdas. TRACKING
    // sets `eyeOverride` to take the lambda over the spherical math; the other modes
    // leave it null so the (yaw, radius, yHeight) path runs.
    //
    // Gesture hand-off — the cinematic shots are a *starting point*, not a lock. The
    // moment the user touches the screen the scripted manipulator records the begin
    // event (kind + coords) into [pendingBegin] and flips `cameraMode` to FREE via
    // [onUserGesture]. Compose recomposes immediately, builds a fresh
    // `DefaultCameraManipulator`, and we **replay the pending begin call on it** so
    // subsequent grabUpdate / scrollUpdate events have a valid origin and produce
    // real deltas. Without this replay the gesture detector keeps routing updates to
    // the new manipulator, but its internal Filament `Manipulator` never saw the
    // begin event and returns zero-delta transforms — that's the bug v1 (commit
    // 38c2842d) shipped: the swap happened, but the new manipulator was missing the
    // `grabBegin` call so all gestures looked frozen.
    val pendingBegin = remember {
        androidx.compose.runtime.mutableStateOf<PendingGestureBegin?>(null)
    }
    val scriptedManipulator = remember {
        ScriptedCameraManipulator(
            target = target,
            yawProvider = { yawAnim.value },
            radiusProvider = { radiusAnim.value },
            yHeightProvider = { yHeightAnim.value },
            eyeOverrideProvider = { trackingEye.value },
            onGrabBegin = { x, y, strafe ->
                if (cameraMode != CameraMode.FREE) {
                    pendingBegin.value = PendingGestureBegin.Grab(x, y, strafe)
                    cameraMode = CameraMode.FREE
                }
            },
            onScrollBegin = { x, y, separation ->
                if (cameraMode != CameraMode.FREE) {
                    pendingBegin.value = PendingGestureBegin.Scroll(x, y, separation)
                    cameraMode = CameraMode.FREE
                }
            },
        )
    }
    // For Free mode: capture the scripted manipulator's last eye and seed a stock
    // gesture manipulator with that as its orbit home, so the hand-off is seamless.
    //
    // Important — viewport propagation. The SDK calls `setViewport(w,h)` on the active
    // manipulator only via `sceneRenderer.onSurfaceResized`, which fires once when the
    // surface is sized. When the user switches to Free *after* the surface is already
    // sized, the freshly-built `DefaultCameraManipulator` never receives those dimensions,
    // so its underlying Filament `Manipulator` operates against a 0x0 viewport and every
    // gesture produces a zero-magnitude transform delta — i.e. taps/drags/pinches do
    // nothing. We capture the viewport as the scripted manipulator sees it and forward
    // it to the free manipulator the moment it appears.
    //
    // Once the viewport is forwarded, we replay any [pendingBegin] gesture event so the
    // user's in-flight drag/pinch gets a valid begin call on the new manipulator and
    // produces real deltas instead of zeros.
    val freeManipulator = remember(cameraMode) {
        if (cameraMode == CameraMode.FREE) {
            CameraGestureDetector.DefaultCameraManipulator(
                eyePosition = scriptedManipulator.currentEye(),
                targetPosition = target,
            ).also { mgr ->
                val (w, h) = scriptedManipulator.lastViewport()
                if (w > 0 && h > 0) mgr.setViewport(w, h)
                // Replay the in-flight gesture's begin call so the underlying Filament
                // Manipulator has a valid origin point. Without this, the next
                // grabUpdate/scrollUpdate the gesture detector forwards lands on a
                // manipulator that thinks no gesture is in progress and returns a
                // zero-delta transform.
                pendingBegin.value?.let { begin ->
                    when (begin) {
                        is PendingGestureBegin.Grab ->
                            mgr.grabBegin(begin.x, begin.y, begin.strafe)
                        is PendingGestureBegin.Scroll ->
                            mgr.scrollBegin(begin.x, begin.y, begin.separation)
                    }
                    pendingBegin.value = null
                }
            }
        } else null
    }
    val activeManipulator = if (cameraMode == CameraMode.FREE) freeManipulator else scriptedManipulator

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_animation_physics_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        topOverlay = {
            Column(
                modifier = Modifier.padding(horizontal = SceneViewTokens.Space.md)
                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                    .padding(SceneViewTokens.Space.sm),
                verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
            ) {
                Text(
                    clipName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.demo_animation_physics_clip_status,
                        stringResource(
                            if (isPlaying && !DemoSettings.qaMode) {
                                R.string.demo_animation_physics_playing
                            } else {
                                R.string.demo_animation_physics_paused
                            },
                        ),
                        clipTime, duration),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { if (duration > 0f) (clipTime / duration).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (animationNames.size > 1) Text(
                    stringResource(R.string.demo_animation_physics_blend_status, clipName,
                        animationNames[blendIndex], (blendWeight * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        controls = {
            ModeSelector(mode, onModeChange)
            // Animation picker — one chip per animation defined in the GLB. Names come
            // from the Filament Animator (gltf animation names). Plays only the selected
            // one to avoid the "stacked animations" visual mess of playing all at once.
            if (animationNames.isNotEmpty()) {
                Text(stringResource(R.string.demo_animation_physics_clip), style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(SceneViewTokens.Space.xs))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    animationNames.forEachIndexed { index, name ->
                        FilterChip(
                            selected = selectedAnim == index,
                            onClick = { selectedAnim = index },
                            label = { Text(name) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
            }

            if (duration > 0f) {
                LabeledSlider(
                    label = stringResource(R.string.demo_animation_physics_scrub),
                    value = clipTime.coerceIn(0f, duration),
                    onValueChange = { isPlaying = false; clipTime = it },
                    valueRange = 0f..duration,
                    valueText = stringResource(R.string.demo_animation_physics_time, clipTime, duration),
                )
            }
            if (animationNames.size > 1) {
                Text(
                    stringResource(R.string.demo_animation_physics_blend_to),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                ) {
                    animationNames.forEachIndexed { index, name ->
                        if (index != selectedAnim) FilterChip(
                            selected = blendIndex == index,
                            onClick = { blendIndex = index },
                            label = { Text(name) },
                        )
                    }
                }
                LabeledSlider(
                    label = stringResource(R.string.demo_animation_physics_blend, clipName, animationNames[blendIndex]),
                    value = blendWeight,
                    onValueChange = { blendWeight = it },
                    valueRange = 0f..1f,
                    valueText = stringResource(R.string.demo_animation_physics_weight, (blendWeight * 100).toInt()),
                )
            }
            Text(
                stringResource(R.string.demo_animation_physics_animation_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.demo_animation_physics_playback),
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(onClick = {
                    if (!isPlaying && clipTime >= duration) clipTime = 0f
                    isPlaying = !isPlaying
                }) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) {
                            stringResource(R.string.demo_animation_physics_pause)
                        } else {
                            stringResource(R.string.demo_animation_physics_play)
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))

            LabeledSlider(
                label = stringResource(R.string.demo_animation_physics_speed),
                value = speed,
                onValueChange = { speed = it },
                valueRange = DemoMath.ANIMATION_SPEED_RANGE,
                valueText = stringResource(R.string.demo_animation_physics_speed_value, speed),
                steps = 10,
            )

            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))

            Row(horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
                FilterChip(
                    selected = loop,
                    onClick = { loop = true },
                    label = { Text(stringResource(R.string.demo_animation_physics_loop)) }
                )
                FilterChip(
                    selected = !loop,
                    onClick = { loop = false },
                    label = { Text(stringResource(R.string.demo_animation_physics_once)) }
                )
            }

            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))

            // Model carousel — switches the active animated subject. Slot 0 is
            // the bundled threejs soldier; slots 1–4 stream from the `animation`
            // category of SampleAssets. Streamed slots fall back to the bundled
            // soldier / shiba / fox GLBs when no Sketchfab key is configured.
            Text(stringResource(R.string.demo_animation_physics_subject), style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.xs))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ANIMATION_MODELS.forEachIndexed { index, model ->
                    FilterChip(
                        selected = selectedModelIndex == index,
                        onClick = { selectedModelIndex = index },
                        label = { Text(model.streamedSlug?.displayName ?: stringResource(model.nameRes)) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))

            // Cinematic camera picker. Hero (heroic low-angle orbit), Reveal
            // (close-up to wide pullback), Vertigo (Hitchcock dolly-zoom), Tracking
            // (lateral pass), Free (user gesture only).
            Text(stringResource(R.string.demo_animation_physics_camera), style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.xs))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CameraMode.entries.forEach { camMode ->
                    FilterChip(
                        selected = cameraMode == camMode,
                        onClick = { cameraMode = camMode },
                        label = {
                            Text(
                                when (camMode) {
                                    CameraMode.HERO -> stringResource(R.string.demo_animation_physics_camera_hero)
                                    CameraMode.REVEAL -> stringResource(R.string.demo_animation_physics_camera_reveal)
                                    CameraMode.VERTIGO -> stringResource(R.string.demo_animation_physics_camera_vertigo)
                                    CameraMode.TRACKING ->
                                        stringResource(R.string.demo_animation_physics_camera_tracking)
                                    CameraMode.FREE -> stringResource(R.string.demo_animation_physics_camera_free)
                                }
                            )
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))

            // IBL intensity — the rooftop_night HDR is over-bright from cmgen's defaults.
            // 0 lux gives a pitch-black scene (only the directional sun left), 5 000 lux
            // is the atmospheric default, 10 000 lux pushes into over-exposed neutral.
            LabeledSlider(
                label = stringResource(R.string.demo_animation_physics_ibl),
                value = iblIntensity,
                onValueChange = { iblIntensity = it },
                valueRange = DemoMath.IBL_INTENSITY_RANGE,
                valueText = stringResource(R.string.demo_animation_physics_lux, iblIntensity.toInt()),
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                onFrame = { nanos ->
                    firstFrame.onFrame(nanos)
                    val animatedNode = modelNodeRef.value
                    if (animatedNode != null && selectedAnim in animationNames.indices && duration > 0f) {
                        val previous = previousFrame[0]
                        previousFrame[0] = nanos
                        if (isPlaying && !DemoSettings.qaMode && previous != 0L) {
                            val next = clipTime + ((nanos - previous) / 1_000_000_000f).coerceAtMost(0.1f) * speed
                            clipTime = if (loop) next % duration else next.coerceAtMost(duration)
                            if (!loop && clipTime >= duration) isPlaying = false
                        }
                        val animator = animatedNode.animator
                        if (blendWeight > 0f && blendIndex in animationNames.indices && blendIndex != selectedAnim) {
                            val blendDuration = animator.getAnimationDuration(blendIndex)
                            animator.applyAnimation(blendIndex, clipTime / duration * blendDuration)
                            animator.applyCrossFade(selectedAnim, clipTime, blendWeight)
                        } else animator.applyAnimation(selectedAnim, clipTime)
                        animator.updateBoneMatrices()
                        animatedNode.onWorldTransformChanged()
                    }
                },
                engine = engine,
                modelLoader = modelLoader,
                environmentLoader = environmentLoader,
                environment = activeEnvironment,
                cameraNode = cameraNode,
                cameraManipulator = activeManipulator,
            ) {
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = activeModel.scaleToUnits,
                        // Lift the model so its feet rest on y=0 (ground plane). These assets
                        // are authored roughly bbox-centered on their pivot, so half the scaled
                        // height (scaleToUnits / 2) puts the feet at y=0. A previous
                        // `centerOrigin = Position(0, 0, 0)` here was a silent no-op (#2622 —
                        // the old formula ignored the AABB center) and was removed to keep this
                        // scene's framing byte-for-byte identical; adopting the now-working
                        // `centerOrigin = Position(0, -1, 0)` (which grounds ANY asset exactly,
                        // replacing this manual lift) is a separate, visually-QA'd enhancement.
                        position = Position(0f, activeModel.scaleToUnits * 0.5f, 0f),
                        // autoAnimate = false so the ModelNode init doesn't fire-and-forget
                        // all animations — the scene frame callback applies exactly the
                        // selected clip time and optional blend on the main thread.
                        autoAnimate = false,
                        apply = { modelNodeRef.value = this },
                    )
                    // Clean up the ref when the node leaves composition.
                    DisposableEffect(instance) {
                        onDispose { modelNodeRef.value = null }
                    }
                }
            }
            LoadingScrim(
                loading = modelInstance == null,
                label = stringResource(
                    R.string.demo_animation_physics_loading,
                    activeModel.streamedSlug?.displayName ?: stringResource(activeModel.nameRes),
                ),
            )
        }
    }
}

/**
 * Records an in-flight gesture's begin event so the host can replay it on the
 * fresh `DefaultCameraManipulator` after the FREE-mode swap. See [freeManipulator]
 * in [AnimationSection] for the rationale (TL;DR — without replay the new manipulator
 * has no origin point and every grabUpdate produces zero deltas).
 */
private sealed class PendingGestureBegin {
    data class Grab(val x: Int, val y: Int, val strafe: Boolean) : PendingGestureBegin()
    data class Scroll(val x: Int, val y: Int, val separation: Float) : PendingGestureBegin()
}

/**
 * Camera manipulator driven by provider lambdas. By default it computes its eye
 * position from spherical coordinates `(yaw, radius, yHeight)` around [target] and
 * looks at the target. The TRACKING shot needs to leave the orbit circle and follow
 * a straight line, so [eyeOverrideProvider] returns a non-null absolute eye when
 * active — when present it bypasses the spherical math entirely.
 *
 * Gestures don't manipulate the camera directly here — instead they fire
 * [onGrabBegin] / [onScrollBegin] so the host can record the begin event, switch to
 * FREE mode, and replay the begin call on a real `DefaultCameraManipulator` seeded
 * at [currentEye]. From the user's point of view the cinematic shot was a starting
 * position, not a lock.
 */
private class ScriptedCameraManipulator(
    private val target: Position,
    private val yawProvider: () -> Float,
    private val radiusProvider: () -> Float,
    private val yHeightProvider: () -> Float,
    private val eyeOverrideProvider: () -> Position? = { null },
    private val onGrabBegin: (x: Int, y: Int, strafe: Boolean) -> Unit = { _, _, _ -> },
    private val onScrollBegin: (x: Int, y: Int, separation: Float) -> Unit = { _, _, _ -> },
) : CameraGestureDetector.CameraManipulator {

    // Latest viewport size pushed by the SDK's surface-resize callback. We don't use
    // it here (this manipulator is gesture-less by design), but we expose it via
    // [lastViewport] so that the demo can hand it to the freshly-built Free-mode
    // `DefaultCameraManipulator` — see `freeManipulator` above for the rationale.
    private var lastWidth: Int = 0
    private var lastHeight: Int = 0
    fun lastViewport(): Pair<Int, Int> = lastWidth to lastHeight

    fun currentEye(): Position {
        // TRACKING (or any future linear-path mode) supplies an absolute eye and
        // bypasses the spherical math — we still re-aim at the target via lookAt so
        // the soldier stays centered in frame as the camera flies past.
        eyeOverrideProvider()?.let { return it }
        val rad = Math.toRadians(yawProvider().toDouble()).toFloat()
        return Position(
            x = sin(rad) * radiusProvider() + target.x,
            y = target.y + yHeightProvider(),
            z = cos(rad) * radiusProvider() + target.z,
        )
    }

    override fun setViewport(width: Int, height: Int) {
        // Cache the viewport so a subsequent Free-mode swap can seed its manipulator
        // with the right dimensions. Filament's underlying `Manipulator` returns a
        // zero-delta transform until it knows the viewport, so without this capture
        // the user's first gestures after entering Free mode produce no movement.
        lastWidth = width
        lastHeight = height
    }

    override fun getTransform(): Transform {
        val mat = dev.romainguy.kotlin.math.lookAt(
            eye = currentEye(),
            target = target,
            up = dev.romainguy.kotlin.math.Float3(0f, 1f, 0f),
        )
        return Transform(mat)
    }

    // Any touch on the scene = "I want control". Forward the begin event (with its
    // coordinates) to the host so it can flip into FREE mode AND replay the begin
    // call on the freshly-built DefaultCameraManipulator. The replay is what makes
    // the in-flight gesture actually move the camera — without it the new
    // manipulator has no origin point recorded.
    override fun grabBegin(x: Int, y: Int, strafe: Boolean) { onGrabBegin(x, y, strafe) }
    @Suppress("EmptyFunctionBlock") // required interface stubs — no-ops are intentional
    override fun grabUpdate(x: Int, y: Int) {}
    @Suppress("EmptyFunctionBlock")
    override fun grabEnd() {}
    override fun scrollBegin(x: Int, y: Int, separation: Float) { onScrollBegin(x, y, separation) }
    @Suppress("EmptyFunctionBlock")
    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) {}
    @Suppress("EmptyFunctionBlock")
    override fun scrollEnd() {}
    @Suppress("EmptyFunctionBlock")
    override fun update(deltaTime: Float) {}
}

// ─── Physics section ────────────────────────────────────────────────────────
// PhysicsBody supplies gravity and floor bounce. The sample adds equal-mass sphere
// contacts and fixed steps because PhysicsNode has no body-to-body collision API.
// Streamed meshes ride the same spherical colliders; this is not mesh-shaped physics.
@Composable
private fun PhysicsSection(
    onBack: () -> Unit,
    mode: AnimationPhysicsMode,
    onModeChange: (AnimationPhysicsMode) -> Unit,
) {
    var bodyCount by remember { mutableIntStateOf(PHYSICS_INITIAL_BODIES) }
    var generation by remember { mutableIntStateOf(0) }
    var replaying by remember { mutableStateOf(true) }
    var liveBodyCount by remember { mutableIntStateOf(0) }
    var collisions by remember { mutableIntStateOf(0) }
    val simulation = remember(generation) { DemoCollisionReplay() }

    // Streamed `physics` slugs from SampleAssets. selectedSlug == null means
    // "Bundled spheres" — the v4.3.1 visual default. Selecting a slug arms
    // it as the carousel of streamed crash-test bodies (chairs / vases /
    // barrels / amphorae) cycling through each drop.
    val physicsSlugs = remember { SampleAssets.byCategory["physics"].orEmpty() }
    var selectedSlug by remember { mutableStateOf<SketchfabSlug?>(null) }

    val context = LocalContext.current

    // Warm the `physics` cache so the very first drop renders without a pop-in.
    // The resolver dedupes concurrent calls, so the per-body resolve below picks
    // up the cached file as soon as the prefetch lands.
    LaunchedEffect(Unit) {
        runCatching {
            SketchfabAssetResolver.getInstance(context).prefetchAll("physics")
        }
    }

    // Resolve the currently-selected slug to a local file (null while
    // downloading / staging the bundled fallback). When null, drops fall
    // back to the original spheres-only mode so the user sees something
    // moving while the streamed mesh lands.
    val selectedFile: File? = selectedSlug?.let { slug ->
        produceState<File?>(initialValue = null, key1 = slug.uid) {
            value = runCatching {
                SketchfabAssetResolver.getInstance(context).resolve(slug)
            }.getOrNull()
        }.value
    }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    // Camera above and back from the scene, angled down. The look-at target sits between
    // the floor (y = -0.5) and the spheres' rest height (y ≈ -0.42) rather than the scene
    // origin, so the 1.6 m ground plane is vertically centred in the viewport instead of
    // being shoved into the bottom third with its near edge clipped (#1463). Pulled back to
    // z = 4 so the full plane depth fits with headroom for the drop column above it.
    val cameraNode = rememberCameraNode(engine) {
        position = Position(0f, 2f, 4f)
        lookAt(Position(0f, -0.35f, 0f))
    }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_animation_physics_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        peekHeader = stringResource(R.string.demo_animation_physics_counts, liveBodyCount, collisions),
        bottomOverlay = {
            DemoStatusBanner(
                text = stringResource(
                    if (replaying) {
                        R.string.demo_animation_physics_replaying
                    } else {
                        R.string.demo_animation_physics_reset_ready
                    },
                ),
                tone = DemoStatusTone.Guidance,
            )
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                Button(onClick = { generation++; replaying = true }) {
                    Text(stringResource(R.string.demo_animation_physics_replay))
                }
                Button(onClick = {
                    bodyCount = PHYSICS_INITIAL_BODIES
                    generation++
                    replaying = false
                }) { Text(stringResource(R.string.demo_animation_physics_reset)) }
            }
        },
        controls = {
            ModeSelector(mode, onModeChange)
            Text(
                stringResource(R.string.demo_animation_physics_counts, liveBodyCount, collisions),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                Button(onClick = { generation++; replaying = true }) {
                    Text(stringResource(R.string.demo_animation_physics_replay))
                }
                Button(onClick = {
                    bodyCount = PHYSICS_INITIAL_BODIES
                    generation++
                    replaying = false
                }) { Text(stringResource(R.string.demo_animation_physics_reset)) }
                Button(enabled = bodyCount < PHYSICS_MAX_BODIES, onClick = {
                    bodyCount++
                    generation++
                    replaying = true
                }) { Text(stringResource(R.string.demo_animation_physics_drop)) }
                Button(enabled = bodyCount < PHYSICS_MAX_BODIES, onClick = {
                    bodyCount = (bodyCount + 10).coerceAtMost(PHYSICS_MAX_BODIES)
                    generation++
                    replaying = true
                }) { Text(stringResource(R.string.demo_animation_physics_drop_ten)) }
            }
            Text(
                stringResource(R.string.demo_animation_physics_physics_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))
            Text(
                text = stringResource(R.string.demo_physics_picker_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.xs))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                // "Bundled spheres" chip preserves the v4.3.1 visual default
                // — useful for QA / offline / store-listing screenshots.
                FilterChip(
                    selected = selectedSlug == null,
                    onClick = {
                        selectedSlug = null
                        // Reset so the chip swap is unambiguous — spheres
                        // first, then more spheres on tap.
                        bodyCount = PHYSICS_INITIAL_BODIES
                        replaying = true
                        generation++
                    },
                    label = {
                        Text(stringResource(R.string.demo_physics_picker_spheres))
                    },
                )
                physicsSlugs.forEach { slug ->
                    FilterChip(
                        selected = selectedSlug?.uid == slug.uid,
                        onClick = {
                            selectedSlug = slug
                            bodyCount = PHYSICS_INITIAL_BODIES
                            replaying = true
                            generation++
                        },
                        label = { Text(slug.displayName) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.xs))
            Text(
                text = stringResource(R.string.demo_physics_picker_subtitle),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    ) {
        // key(generation) forces full recomposition on reset
        key(generation) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                cameraNode = cameraNode,
                autoCenterContent = false,
                onFrame = { nanos ->
                    firstFrame.onFrame(nanos)
                    // Start only once all nodes are registered: composition timing cannot
                    // change which body gets a head start on the deterministic replay.
                    simulation.onFrame(nanos, replaying && simulation.bodies.size == bodyCount)
                    liveBodyCount = simulation.bodies.size
                    collisions = simulation.collisions
                },
                cameraManipulator = rememberCameraManipulator(
                    orbitHomePosition = cameraNode.worldPosition
                )
            ) {
                // Left-side counter-fill — same as v4.3.1, kept verbatim.
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    direction = io.github.sceneview.math.Direction(-0.3f, -1f, -0.5f),
                    apply = {
                        intensity(5_000f)
                    }
                )
                val groundMaterial = rememberMaterialInstance(
                    materialLoader, SceneViewColors.SurfaceDim
                )
                // Ramp4 is a fixed 4-colour list — call the helper once per slot so
                // each MaterialInstance gets the same disposal hygiene as the rest.
                val sphereMaterials = listOf(
                    rememberMaterialInstance(materialLoader, SceneViewColors.Ramp4[0]),
                    rememberMaterialInstance(materialLoader, SceneViewColors.Ramp4[1]),
                    rememberMaterialInstance(materialLoader, SceneViewColors.Ramp4[2]),
                    rememberMaterialInstance(materialLoader, SceneViewColors.Ramp4[3]),
                )

                // Ground plane — must use Size(x, y=0, z) for a HORIZONTAL floor
                PlaneNode(
                    materialInstance = groundMaterial,
                    size = Size(x = 1.6f, y = 0f, z = 1.6f),
                    position = Position(y = -0.5f),
                )

                // Visible rails mark the bounds used by the collision response.
                for (side in listOf(-1f, 1f)) {
                    CubeNode(
                        size = Size(0.03f, 0.16f, 1.6f),
                        position = Position(side * 0.8f, -0.42f, 0f),
                        materialInstance = groundMaterial,
                    )
                    CubeNode(
                        size = Size(1.6f, 0.16f, 0.03f),
                        position = Position(0f, -0.42f, side * 0.8f),
                        materialInstance = groundMaterial,
                    )
                }

                // Streamed mesh path. The model file is `null` until the
                // resolver returns (or the user picked "Bundled spheres").
                // The GLB is parsed once into a single `Model` (geometry +
                // materials live here, shared by every instance); each falling
                // body then gets its OWN `ModelInstance` spawned from it below.
                // A `ModelInstance` wraps exactly one Filament entity / one
                // TransformManager slot, so it can only ride one body at a
                // time — sharing it across bodies meant every ModelNode wrote
                // the same entity transform (last-write-wins) and only one
                // mesh was ever visible (#1706).
                val streamedModel: Model? = selectedFile?.let { file ->
                    rememberStreamedModel(modelLoader, file)
                }

                val collisionRadius = PHYSICS_RADIUS

                for (i in 0 until bodyCount) {
                    val startPosition = remember(i) { physicsStartPosition(i) }

                    var nodeRef by remember(i) { mutableStateOf<SphereNodeImpl?>(null) }

                    // The simulated SphereNode is rendered "invisibly" (it
                    // carries the colour ramp material when the user is in
                    // bundled-sphere mode; in streamed mode we ALSO render
                    // it — same coloured silhouette — so the dropped streamed
                    // mesh sits visually on top of a soft colour pad which
                    // hides the bounding-sphere abstraction).
                    SphereNode(
                        radius = collisionRadius,
                        materialInstance = sphereMaterials[i % 4],
                        position = startPosition,
                        apply = { nodeRef = this }
                    ) {
                        // Streamed mesh child — only rendered when a streamed
                        // slug is selected AND its download has landed. The
                        // child inherits the sphere's transform so it rides
                        // the simulation. Each body spawns its OWN
                        // `ModelInstance` from the shared `Model` so it has an
                        // independent Filament entity — `createInstance` only
                        // duplicates the lightweight entity tree, geometry and
                        // materials stay shared (#1706). Keyed on `i` so the
                        // instance is created once per body, on the main
                        // composition thread (Filament JNI is @MainThread).
                        val model = streamedModel
                        val slug = selectedSlug
                        if (model != null && slug != null) {
                            val instance = remember(i, model) {
                                modelLoader.createInstance(model)
                            }
                            if (instance != null) {
                                ModelNode(
                                    modelInstance = instance,
                                    scaleToUnits = slug.scaleToUnits,
                                )
                            }
                        }
                    }

                    nodeRef?.let { node ->
                        DisposableEffect(node, simulation) {
                            simulation.bodies[i] = PhysicsBody(
                                node = node,
                                restitution = PHYSICS_RESTITUTION,
                                floorY = PHYSICS_FLOOR,
                                radius = collisionRadius,
                                initialVelocity = if (i == 0) Position(1.9f, 0.4f, 0f) else Position(0f),
                                // Keep resting bodies responsive to later sphere impacts.
                                floorProvider = FloorProvider { _, _, _, _ -> PHYSICS_FLOOR },
                            )
                            onDispose { simulation.bodies.remove(i) }
                        }
                    }
                }
            }
        }
    }
}

private const val PHYSICS_INITIAL_BODIES = 7
private const val PHYSICS_MAX_BODIES = 30
private const val PHYSICS_RADIUS = 0.08f
private const val PHYSICS_FLOOR = -0.5f
private const val PHYSICS_RESTITUTION = 0.8f
private const val PHYSICS_STEP_NANOS = 8_333_333L

private fun physicsStartPosition(index: Int): Position = when (index) {
    0 -> Position(-0.65f, -0.32f, 0f)
    in 1..3 -> Position(0.05f + (index - 1) * PHYSICS_RADIUS * 2f, PHYSICS_FLOOR + PHYSICS_RADIUS, 0f)
    in 4..5 -> Position(0.13f + (index - 4) * PHYSICS_RADIUS * 2f,
        PHYSICS_FLOOR + PHYSICS_RADIUS * (1f + sqrt(3f)), 0f)
    6 -> Position(0.21f, PHYSICS_FLOOR + PHYSICS_RADIUS * (1f + 2f * sqrt(3f)), 0f)
    else -> Position((index % 5 - 2) * 0.18f, 0.6f + (index / 5) * 0.18f, (index % 3 - 1) * 0.18f)
}

/** A deterministic sphere-contact demonstration, deliberately local to this sample. */
private class DemoCollisionReplay {
    val bodies = sortedMapOf<Int, PhysicsBody>()
    var collisions = 0
        private set
    private var previousFrame = 0L
    private var accumulatedNanos = 0L

    fun onFrame(nanos: Long, playing: Boolean) {
        val elapsed = if (previousFrame == 0L) 0L else (nanos - previousFrame).coerceIn(0L, 100_000_000L)
        previousFrame = nanos
        if (!playing) return
        accumulatedNanos += elapsed
        while (accumulatedNanos >= PHYSICS_STEP_NANOS) {
            step()
            accumulatedNanos -= PHYSICS_STEP_NANOS
        }
    }

    private fun step() {
        for (body in bodies.values) {
            val before = body.velocity
            body.step(PHYSICS_STEP_NANOS, 0L)
            val p = body.node.position
            val v = body.velocity
            if (before.y < -0.2f && v.y > 0f) collisions++
            // Same rails as the rendered tray. Only count approaching impacts,
            // not persistent resting contacts or positional corrections.
            val bound = 0.8f - 0.015f - body.radius
            var vx = v.x
            var vz = v.z
            val belowRail = p.y - body.radius < PHYSICS_FLOOR + 0.16f
            if (belowRail && kotlin.math.abs(p.x) > bound && p.x * vx > 0f) {
                vx = -vx * body.restitution
                collisions++
            }
            if (belowRail && kotlin.math.abs(p.z) > bound && p.z * vz > 0f) {
                vz = -vz * body.restitution
                collisions++
            }
            if (p.y <= PHYSICS_FLOOR + body.radius && kotlin.math.abs(v.y) < 0.2f) {
                vx *= 0.985f
                vz *= 0.985f
            }
            if (belowRail) {
                body.node.position =
                    Position(p.x.coerceIn(-bound, bound), p.y, p.z.coerceIn(-bound, bound))
            }
            body.velocity = Position(vx, v.y, vz)
        }
        // Stable index order, a fixed timestep and fixed initial velocities make
        // the same initial population produce the same sequence of impacts.
        for ((aIndex, a) in bodies) {
            for ((bIndex, b) in bodies) {
                if (bIndex > aIndex && resolvePair(a, b)) collisions++
            }
        }
    }

    /**
     * Separates [a] and [b] if their spheres overlap and exchanges the impulse
     * along the contact normal. Returns `true` when the pair met hard enough to
     * count as an impact, so the caller owns the counter and this stays pure
     * enough to read.
     */
    private fun resolvePair(a: PhysicsBody, b: PhysicsBody): Boolean {
        val pa = a.node.position
        val pb = b.node.position
        val dx = pb.x - pa.x
        val dy = pb.y - pa.y
        val dz = pb.z - pa.z
        val distanceSquared = dx * dx + dy * dy + dz * dz
        val diameter = a.radius + b.radius
        if (distanceSquared >= diameter * diameter) return false
        val distance = sqrt(distanceSquared)
        val nx = if (distance > 0.00001f) dx / distance else 1f
        val ny = if (distance > 0.00001f) dy / distance else 0f
        val nz = if (distance > 0.00001f) dz / distance else 0f
        val correction = (diameter - distance) * 0.5f
        a.node.position = Position(
            pa.x - nx * correction,
            (pa.y - ny * correction).coerceAtLeast(PHYSICS_FLOOR + a.radius),
            pa.z - nz * correction,
        )
        b.node.position = Position(
            pb.x + nx * correction,
            (pb.y + ny * correction).coerceAtLeast(PHYSICS_FLOOR + b.radius),
            pb.z + nz * correction,
        )
        val va = a.velocity
        val vb = b.velocity
        val approach = (vb.x - va.x) * nx + (vb.y - va.y) * ny + (vb.z - va.z) * nz
        if (approach >= 0f) return false
        val impulse = -(1f + PHYSICS_RESTITUTION) * approach / 2f
        a.velocity = Position(va.x - impulse * nx, va.y - impulse * ny, va.z - impulse * nz)
        b.velocity = Position(vb.x + impulse * nx, vb.y + impulse * ny, vb.z + impulse * nz)
        return approach < -0.2f
    }
}

/**
 * Parses the streamed GLB at [file] once into a single [Model] that every
 * dropped body then spawns its own [ModelInstance] from.
 *
 * `releaseSourceData = false` is mandatory here: [ModelLoader.createInstance]
 * cannot run after the source glTF data has been released, so we keep it
 * resident for as long as new bodies may still be dropped.
 *
 * Threading mirrors `rememberModelInstance`: the file bytes are read on
 * [Dispatchers.IO], then `createModel` (a `@MainThread` Filament JNI call)
 * runs back on the composition's main dispatcher inside [produceState].
 * Returns `null` while loading.
 */
@Composable
private fun rememberStreamedModel(
    modelLoader: ModelLoader,
    file: File,
): Model? = produceState<Model?>(initialValue = null, key1 = modelLoader, key2 = file.absolutePath) {
    // Read the GLB bytes (and any external glTF resources) off the main
    // thread, then call `createModel` — a @MainThread Filament JNI call —
    // back on the composition's main dispatcher (produceState's context).
    val buffer = withContext(Dispatchers.IO) {
        runCatching { java.nio.ByteBuffer.wrap(file.readBytes()) }.getOrNull()
    } ?: return@produceState
    value = runCatching {
        modelLoader.createModel(
            buffer = buffer,
            releaseSourceData = false,
            resourceResolver = { resourceFile ->
                runCatching {
                    java.nio.ByteBuffer.wrap(File(file.parent, resourceFile).readBytes())
                }.getOrNull()
            },
        )
    }.getOrNull()
}.value
