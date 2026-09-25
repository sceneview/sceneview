package io.github.sceneview.demo.demos

import io.github.sceneview.node.PhysicsBody
import io.github.sceneview.node.FloorProvider
import kotlin.math.sqrt
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.theme.SceneViewTokens
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.google.android.filament.LightManager
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.driving
import io.github.sceneview.demo.initialDemoMode
import io.github.sceneview.demo.rememberContinuousCameraManipulator
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.sketchfab.SampleAssets
import io.github.sceneview.demo.sketchfab.SketchfabAssetResolver
import io.github.sceneview.demo.sketchfab.SketchfabSlug
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.loaders.ModelLoader
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.transpose
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.math.toQuaternion
import io.github.sceneview.math.Transform
import dev.romainguy.kotlin.math.rotation as rotationMatrix
import io.github.sceneview.model.Model
import io.github.sceneview.model.model
import io.github.sceneview.toAabb
import io.github.sceneview.node.ModelNode as ModelNodeImpl
import io.github.sceneview.node.SphereNode as SphereNodeImpl
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.LifecyclePausingLaunchedEffect
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.ui.LabeledSlider
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.ScreenRotationAlt
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.runtime.mutableStateListOf
import io.github.sceneview.demo.ui.GlassActionPill
import io.github.sceneview.demo.ui.overMediaEdge

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
 * Carousel of 6 animated models for [AnimationSection].
 *
 * Slot 0 is the bundled Khronos Fox (Survey, Walk, Run); slot 1 the historical
 * `threejs_soldier.glb` (bundled, 4 animations: 0=Idle, 1=Run, 2=TPose, 3=Walk).
 * The next 4 slots stream the 4 entries from
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
        // The landing subject (#3820). The Khronos Fox is the reference skinned-animation asset:
        // three named clips (Survey, Walk, Run) on one rig, bundled, so it plays offline and on a
        // cold cache. Survey is an idle look-around — alive from the first frame without walking
        // in place, which is what made the old soldier read as odd on a turntable.
        AnimationModel(
            nameRes = R.string.demo_animation_physics_subject_fox_bundled,
            bundledAssetPath = "models/khronos_fox.glb",
            scaleToUnits = 1.0f,
            defaultAnimationIndex = 0,
        ),
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
    // Default shot is the slow turntable (#3820): one steady turn, no holds or speed changes, so
    // the eye stays on the animation instead of on the camera.
    var cameraMode by remember { mutableStateOf(CameraMode.HERO) }
    // IBL intensity — exposed as a slider so users can dial atmospheric vs neutral.
    // Default 10_000 lux matches SceneView's balanced IBL default (#1075). The
    // rooftop_night skybox renders at full HDR luminance, so a lower IBL left the
    // model reading as a black silhouette against the bright sky (#1468). Range
    // 0–10_000 still lets users dial down to a darker, atmospheric look.
    var iblIntensity by remember { mutableFloatStateOf(10_000f) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
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

    // Daylight garden backdrop (#3820): the subject reads against a real place with a ground
    // line, lit by the same sky it stands under. The dusk rooftop it replaces put a walking
    // figure on a car park. Skybox on so the scene is never a black void.
    val hdrEnvironment = rememberHDREnvironment(
        environmentLoader,
        "environments/chinese_garden_2k.hdr",
        createSkybox = true,
    )
    val fallbackEnvironment = rememberEnvironment(environmentLoader)
    val activeEnvironment = hdrEnvironment ?: fallbackEnvironment

    // Pin the IBL intensity to the slider value. The rooftop_night skybox renders at
    // full HDR luminance, so the IBL must stay near SceneView's balanced 10k default
    // to keep the soldier lit in step with the bright sky behind it (#1468) — a lower
    // value left the model looking like an unlit black silhouette. Re-runs whenever the
    // active environment OR the slider value change, so dragging it updates in real time.
    val renderInvalidator = rememberRenderInvalidator()
    LaunchedEffect(activeEnvironment, iblIntensity) {
        activeEnvironment.indirectLight?.intensity = iblIntensity
        // `IndirectLight` is a raw Filament object — the SDK hands it out and never sees it
        // again — so dimming it reaches the engine and nothing else. Under `OnDemand` the new
        // ambient would sit there with no frame coming to show it (#3718).
        renderInvalidator.requestRender()
    }

    // Captured ref to the ModelNode once it's created — used by the LaunchedEffect
    // below to drive play/pause/speed/loop imperatively.
    val modelNodeRef = remember { androidx.compose.runtime.mutableStateOf<ModelNodeImpl?>(null) }

    val node = modelNodeRef.value
    // `LocalResources`, not `LocalContext.current.getString(…)`: a `Context` read is not
    // invalidated by a configuration change, so the "Clip N" fallbacks would keep the
    // previous locale's wording after an in-place locale switch
    // (`LocalContextGetResourceValueCall`, #3660). Keying the `remember` on `resources`
    // is what actually re-derives the list when that happens.
    val resources = LocalResources.current
    val animationNames = remember(node, resources) {
        if (node == null) emptyList() else (0 until node.animationCount).map { index ->
            node.animator.getAnimationName(index).orEmpty().ifBlank {
                resources.getString(R.string.demo_animation_physics_clip_fallback, index + 1)
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

    // Framing (#3820) — measured from the subject, never assumed. The node is grounded
    // (`centerOrigin = (0, -1, 0)`: feet on y = 0, centred on the vertical axis) and scaled so its
    // largest side is `scaleToUnits`, so its world size is the glTF box scaled by that same factor.
    // Every shot below is expressed in multiples of the fit radius and the subject's height, so a
    // 0.5 m fox and a 1.45 m knight both land centred at the same screen size.
    val subjectSize = remember(modelInstance, activeModel) {
        val units = activeModel.scaleToUnits
        val fallback = Position(units * 0.6f, units, units * 0.6f)
        val extents = modelInstance?.let { instance ->
            runCatching { instance.model.boundingBox.toAabb() }.getOrNull()
                ?.takeUnless { it.isEmpty }?.extents
        } ?: return@remember fallback
        val largest = maxOf(extents.x, extents.y, extents.z)
        if (!(largest > 0f)) fallback
        else Position(extents.x * units / largest, extents.y * units / largest, extents.z * units / largest)
    }
    val baseRadius = io.github.sceneview.demo.rememberFitOrbitRadius(
        subjectSize.x,
        subjectSize.y,
        subjectSize.z,
        elevationDegrees = ANIMATION_ORBIT_ELEVATION_DEGREES,
        fill = ANIMATION_FILL,
    )
    // Eye height above the target for the orbit: a slight look down, so the ground line sits
    // under the subject instead of cutting through it.
    val baseYHeight = baseRadius * kotlin.math.tan(Math.toRadians(ANIMATION_ORBIT_ELEVATION_DEGREES.toDouble())).toFloat()
    val subjectHeight = subjectSize.y
    // Aim at the middle of the grounded subject: head and feet equidistant from the frame edges.
    val target = remember(subjectHeight) { Position(0f, subjectHeight * 0.5f, 0f) }

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

    // The one camera writer of the screen. Every shot below opens with a `snapTo` of its
    // start pose, the scripted and the
    // free manipulator are different instances and a new subject rebuilds both — each of those
    // drew the new pose on the very next frame, a cut of up to half a turn. `SceneView` is handed
    // this manipulator instead, for good, and it eases from the pose on screen into whatever the
    // current source shows.
    val continuity = rememberContinuousCameraManipulator(pivot = target)
    val subjectShown = rememberUpdatedState(modelInstance != null)

    // Cinematic easings — FastOutSlowInEasing is Material's standard, EaseInOutCubic
    // is a slightly more dramatic S-curve we use for the hero pause-and-resume.
    val easeInOutCubic: Easing = remember { CubicBezierEasing(0.65f, 0.0f, 0.35f, 1.0f) }

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
    LifecyclePausingLaunchedEffect(cameraMode, DemoSettings.qaMode, activeModel, baseRadius, subjectHeight) { gate ->
        // QA freeze — match the hero-orbit helper so screenshot tests stay stable.
        if (DemoSettings.qaMode) {
            yawAnim.snapTo(ANIMATION_START_YAW_DEGREES)
            radiusAnim.snapTo(baseRadius)
            yHeightAnim.snapTo(baseYHeight)
            fovAnim.snapTo(defaultFovDegrees)
            trackingEye.value = null
            return@LifecyclePausingLaunchedEffect
        }

        // A shot's clock starts when its subject is on screen and frames are being drawn again. It
        // used to run on under the loading scrim and through the freeze of the model's first
        // frame, so the reveal showed the camera wherever the script had got to — a quarter of a
        // turn from the frame the scrim had been dimming.
        var staged = false
        suspend fun awaitStage() {
            gate.awaitResumed()
            if (staged) return
            snapshotFlow { subjectShown.value }.first { it }
            io.github.sceneview.demo.awaitSteadyFrames()
            staged = true
        }

        // Reset overrides on every mode switch so previous mode state doesn't bleed in — as a
        // camera move: the pose eases in from the one on screen, and the lens with it.
        continuity.easeNextCut()
        trackingEye.value = null
        if (cameraMode != CameraMode.VERTIGO) {
            launch { fovAnim.animateTo(defaultFovDegrees, tween(CUT_EASE_MILLIS, easing = FastOutSlowInEasing)) }
        }

        when (cameraMode) {
            CameraMode.HERO -> {
                // Turntable (#3820): the fit radius at a slight look-down, one full turn every
                // ANIMATION_TURN_MILLIS at constant speed. The old shot broke the turn into four
                // legs with holds and changes of pace; on screen that read as the camera lurching,
                // not as the subject moving. Linear from 30° to 390° and a snap back to 30° is the
                // same pose, so the loop has no seam.
                radiusAnim.snapTo(baseRadius)
                yHeightAnim.snapTo(baseYHeight)
                yawAnim.snapTo(ANIMATION_START_YAW_DEGREES)
                while (true) {
                    awaitStage()
                    yawAnim.animateTo(
                        ANIMATION_START_YAW_DEGREES + 360f,
                        tween(ANIMATION_TURN_MILLIS, easing = androidx.compose.animation.core.LinearEasing),
                    )
                    yawAnim.snapTo(ANIMATION_START_YAW_DEGREES)
                }
            }

            CameraMode.REVEAL -> {
                // Close-up that pulls back to a wide, slightly high angle, then pushes back in.
                // Distances are multiples of the fit radius and the look-down is a fixed ratio of
                // the distance, so the whole subject and its ground line stay in frame whatever
                // its size. No yaw motion — the dolly IS the shot; 15° keeps it off-axis.
                val close = baseRadius * 0.6f
                val wide = baseRadius * 1.35f
                yawAnim.snapTo(15f)
                radiusAnim.snapTo(close)
                yHeightAnim.snapTo(close * REVEAL_LIFT_RATIO)
                while (true) {
                    awaitStage()
                    val pullBack = tween<Float>(6_000, easing = FastOutSlowInEasing)
                    val sync = launch { radiusAnim.animateTo(wide, pullBack) }
                    yHeightAnim.animateTo(wide * REVEAL_LIFT_RATIO, pullBack)
                    sync.join()
                    // 2 s hold on the wide shot before looping (delay, not animateTo
                    // — the latter returns immediately when target == current).
                    kotlinx.coroutines.delay(2_000)
                    // Back to the close-up as a shot of its own: a 3 s push-in, not a cut.
                    awaitStage()
                    val pushIn = tween<Float>(REVEAL_PUSH_IN_MILLIS, easing = easeInOutCubic)
                    val syncIn = launch { radiusAnim.animateTo(close, pushIn) }
                    yHeightAnim.animateTo(close * REVEAL_LIFT_RATIO, pushIn)
                    syncIn.join()
                }
            }

            CameraMode.VERTIGO -> {
                // Hitchcock dolly-zoom: camera moves AWAY (radius increases) while
                // FOV NARROWS — keeping the subject the same on-screen size while
                // the background appears to compress. Then reverse for the vertigo-out.
                // Radii are fit-radius multiples chosen so r·tan(fov/2) — the subject's screen
                // size — is the same at both ends: 0.7·tan 30° ≈ 1.75·tan 12.5°.
                val near = baseRadius * 0.7f
                val far = baseRadius * 1.75f
                yawAnim.snapTo(20f)
                yHeightAnim.snapTo(0f)
                radiusAnim.snapTo(near)
                // The lens opens to the shot's 60° while the camera eases onto its mark. Every
                // later pass of the loop ends where it starts, so the snaps below are no-ops.
                fovAnim.animateTo(60f, tween(CUT_EASE_MILLIS, easing = FastOutSlowInEasing))
                while (true) {
                    radiusAnim.snapTo(near)
                    fovAnim.snapTo(60f)
                    // Park on the vertigo-start boundary while backgrounded.
                    awaitStage()
                    // Vertigo IN: 10 s. Radius grows 2 → 5, FOV shrinks 60 → 25.
                    // The subject stays roughly the same screen size; the background
                    // appears to crush in. Easing: gentle ease-in-out for the build.
                    val vIn = tween<Float>(10_000, easing = easeInOutCubic)
                    val syncR = launch { radiusAnim.animateTo(far, vIn) }
                    fovAnim.animateTo(25f, vIn)
                    syncR.join()
                    // Hold at the extreme for 1 s — lets the eye register the warp.
                    kotlinx.coroutines.delay(1_000)
                    // Vertigo OUT: 8 s. Reverse — radius 5 → 2, FOV 25 → 60.
                    awaitStage()
                    val vOut = tween<Float>(8_000, easing = easeInOutCubic)
                    val syncR2 = launch { radiusAnim.animateTo(near, vOut) }
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
                // Standoff, track length and height scale with the subject (#3820): the old
                // metres were tuned for a 1 m soldier and left a small subject a speck.
                val zStandoff = baseRadius * 0.9f
                val yLevel = subjectHeight + baseRadius * 0.15f
                val startX = -baseRadius * 1.3f
                val endX = baseRadius * 1.3f
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
                    // Onto the start of the track: swung round the subject, slowly enough to
                    // read, rather than teleported across it.
                    continuity.easeNextCut(TRACK_ENTRY_MILLIS)
                    xAnim.snapTo(startX)
                    var towards = endX
                    while (true) {
                        // Park on the end of the track while backgrounded.
                        awaitStage()
                        // 8 s lateral sweep, ease-in-out so the pass accelerates
                        // smoothly and decelerates at the end (real dolly track feel).
                        xAnim.animateTo(
                            targetValue = towards,
                            animationSpec = tween(8_000, easing = easeInOutCubic),
                        )
                        // 1 s hold, then the dolly runs back the way it came. The loop used to
                        // jump to the start of the track — a 116° cut — and swinging round the
                        // subject in 1.2 s instead was still a whip pan.
                        kotlinx.coroutines.delay(1_000)
                        towards = if (towards == endX) startX else endX
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
    val scriptedManipulator = remember(activeModel, target) {
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
    // The loading scrim is translucent — the rooftop shows through it — so a new subject's camera
    // is eased in like any other change, and is in place by the time the scrim lifts. Hence the
    // default `contentShown = true`, where Model Viewer and the Explore viewer pass
    // `instance != null`: what this screen has to wait for is its script, and `awaitStage()`
    // already parks that on the subject and on steady frames.
    val activeManipulator = continuity.driving(
        source = (if (cameraMode == CameraMode.FREE) freeManipulator else null) ?: scriptedManipulator,
    )

    val firstFrame = rememberFirstFrameState(engine)

    // ── Who keeps this screen awake (#3718) ──────────────────────────────────────────────
    //
    // This screen animates from its own clock, and `onFrame` fires *after* a frame reached
    // the surface — so the callback that advances the clip cannot also be the thing that
    // asks for the next frame. Under render-on-demand that closes on itself: the scene
    // settles, parks, `onFrame` stops, the clip stops, and the soldier stands still from the
    // moment the screen opens. Nothing in the SDK can see it either — `applyAnimation` /
    // `updateBoneMatrices` write bone matrices straight into Filament, and the
    // `onWorldTransformChanged()` below only invalidates the node's world-space cache;
    // `onTransformChanged()` is the one that pushes an invalidation, and an animator
    // write-back never goes through it.
    //
    // Two different answers, because these are two different questions:
    //
    //  * **while it plays** the screen genuinely wants every vsync — that is what
    //    [FrameRatePolicy.Continuous] is for, and it also lets the cadence vote tell the
    //    panel. Pausing hands the screen back to on-demand.
    //  * **while it is paused** a scrub, a clip chip or the blend slider moves the pose with
    //    no clock running. The pose is applied *here*, outside the loop, and
    //    [renderInvalidator] asks for the one frame that shows it. Applying it from
    //    `onFrame` instead would draw the previous pose and park — one frame behind, for good.
    val playing = isPlaying && !DemoSettings.qaMode
    val applyPose: (ModelNodeImpl) -> Unit = { animatedNode ->
        val animator = animatedNode.animator
        if (blendWeight > 0f && blendIndex in animationNames.indices && blendIndex != selectedAnim) {
            val blendDuration = animator.getAnimationDuration(blendIndex)
            animator.applyAnimation(blendIndex, clipTime / duration * blendDuration)
            animator.applyCrossFade(selectedAnim, clipTime, blendWeight)
        } else animator.applyAnimation(selectedAnim, clipTime)
        animator.updateBoneMatrices()
        animatedNode.onWorldTransformChanged()
    }
    LaunchedEffect(playing, clipTime, selectedAnim, blendIndex, blendWeight, modelNodeRef.value) {
        if (playing) return@LaunchedEffect
        val animatedNode = modelNodeRef.value ?: return@LaunchedEffect
        if (selectedAnim !in animationNames.indices || duration <= 0f) return@LaunchedEffect
        applyPose(animatedNode)
        renderInvalidator.requestRender()
    }

    DemoScaffold(
        bottomOverlayReservesScene = true,
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
            //
            // `AnimatedVisibility(fadeIn/fadeOut)`, not a raw `if` (#3810): the whole
            // `controls` column already sits under `DemoScaffold`'s own
            // `animateContentSize()`, and `animationNames` flips from empty to populated
            // in the same recomposition the model finishes loading in — often while the
            // sheet's own open animation is still running. A raw `if` inserts this fully
            // laid-out block in one frame, so `animateContentSize`'s lookahead pass (sized
            // for the *old*, shorter column) and the actual pass (sized for the *new* one)
            // disagree for a frame: a child measured against the stale width can paint at
            // a position its own track hasn't caught up to yet — seen as a slider thumb
            // floating with no track/label under it. `fadeIn`/`fadeOut` (no
            // expand/shrink — that would just reintroduce the same size race a second
            // way) hands the appear/disappear to `AnimatedVisibility`'s own crossfade
            // instead, which composes the block at its final size from the first frame.
            AnimatedVisibility(visible = animationNames.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                Column {
                    Text(
                        stringResource(R.string.demo_animation_physics_clip),
                        style = MaterialTheme.typography.labelLarge,
                    )
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
            }

            // Same race, same fix as above: `duration` goes from `0f` to the clip's real
            // length the instant the model node loads, which is exactly when this slider
            // would otherwise snap into the column mid-resize.
            AnimatedVisibility(visible = duration > 0f, enter = fadeIn(), exit = fadeOut()) {
                LabeledSlider(
                    label = stringResource(R.string.demo_animation_physics_scrub),
                    value = clipTime.coerceIn(0f, duration),
                    onValueChange = { isPlaying = false; clipTime = it },
                    valueRange = 0f..duration.coerceAtLeast(0.0001f),
                    valueText = stringResource(R.string.demo_animation_physics_time, clipTime, duration),
                )
            }
            // Same race again: `animationNames.size > 1` flips from `false` to `true`
            // alongside `animationNames.isNotEmpty()` above, the same instant the model
            // loads.
            AnimatedVisibility(visible = animationNames.size > 1, enter = fadeIn(), exit = fadeOut()) {
                Column {
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
                        label = stringResource(
                            R.string.demo_animation_physics_blend,
                            clipName, animationNames.getOrElse(blendIndex) { "" },
                        ),
                        value = blendWeight,
                        onValueChange = { blendWeight = it },
                        valueRange = 0f..1f,
                        valueText = stringResource(R.string.demo_animation_physics_weight, (blendWeight * 100).toInt()),
                    )
                }
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
                // The subject is placed and framed explicitly (grounded at the origin, camera on
                // its mid-height). Auto-centring would move the content's centroid to the origin
                // behind the camera's back — the soldier sat in the bottom third (#3820).
                autoCenterContent = false,
                onFrame = { nanos ->
                    firstFrame.onFrame(nanos)
                    val animatedNode = modelNodeRef.value
                    if (animatedNode != null && selectedAnim in animationNames.indices && duration > 0f) {
                        val previous = previousFrame[0]
                        previousFrame[0] = nanos
                        // Advance the clip only while playing. The paused pose is applied by the
                        // `LaunchedEffect` above, not here: `onFrame` runs after the frame it is
                        // named for was already presented.
                        if (playing && previous != 0L) {
                            val next = clipTime + ((nanos - previous) / 1_000_000_000f).coerceAtMost(0.1f) * speed
                            clipTime = if (loop) next % duration else next.coerceAtMost(duration)
                            if (!loop && clipTime >= duration) isPlaying = false
                            applyPose(animatedNode)
                        }
                    }
                },
                // Continuous *only* while the clip is running — see the block above
                // `DemoScaffold`. Pausing returns the screen to on-demand, and a paused scene
                // here presents nothing at all.
                frameRatePolicy = if (playing) {
                    FrameRatePolicy.Continuous()
                } else {
                    FrameRatePolicy.OnDemand()
                },
                renderInvalidator = renderInvalidator,
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                environment = activeEnvironment,
                cameraNode = cameraNode,
                cameraManipulator = activeManipulator,
            ) {
                // Plinth (#3820): the subject stands on something instead of floating over the
                // garden. Sized from the measured footprint so every model gets the same margin.
                val plinthMaterial = rememberMaterialInstance(
                    materialLoader, SceneViewColors.SurfaceDim, metallic = 0f, roughness = 0.7f,
                )
                CylinderNode(
                    radius = maxOf(subjectSize.x, subjectSize.z) * ANIMATION_PLINTH_RADIUS_FACTOR,
                    height = ANIMATION_PLINTH_HEIGHT,
                    materialInstance = plinthMaterial,
                    position = Position(y = -ANIMATION_PLINTH_HEIGHT / 2f),
                )
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = activeModel.scaleToUnits,
                        // Grounded exactly (#3820): feet on y = 0, centred on the vertical axis
                        // whatever the asset's authored pivot. The manual half-height lift this
                        // replaces assumed a bbox-centred pivot, which only the soldier had.
                        centerOrigin = Position(0f, -1f, 0f),
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
// A tray of balls to drop, tip and knock over (#3820). PhysicsBody supplies gravity and the floor
// bounce; this sample adds mass-weighted sphere contacts, per-material bounce and rolling friction,
// and the tray's rails, because PhysicsNode has no body-to-body collision API. The bodies are
// spheres and so are their colliders: what you see is exactly what collides.
//
// Three rules this screen holds to:
//  - One SceneView for the life of the screen. Reset and every drop change the *bodies*, never the
//    scene, so there is no teardown frame to show black and no camera jump.
//  - What changes the scene lives on the scene. The material to drop, Drop, Tilt and Reset sit in
//    the bottom band, where their effect is visible as it happens; the settings sheet — which
//    covers the tray — keeps what is read or fine-tuned (counts, exact angles, Drop 10, Level).
//  - The camera frames the tray and the drop column in the viewport it actually gets, and the
//    orbit cannot go under the tray.

/** What a ball is made of — three contrasting behaviours, told apart by colour and finish. */
private enum class BallKind(
    @StringRes val labelRes: Int,
    val radius: Float,
    /** Bounce kept on the floor and the rails; a contact takes the lower of the pair's two. */
    val restitution: Float,
    /** Rolling speed kept per 120 Hz step while touching the floor. */
    val rollFriction: Float,
    /** Relative mass for ball-to-ball impulses: steel scatters rubber, not the reverse. */
    val mass: Float,
    val color: Color,
    val metallic: Float,
    val roughness: Float,
) {
    Rubber(R.string.demo_physics_ball_rubber, 0.075f, 0.82f, 0.99f, 1f, SceneViewColors.Primary, 0f, 0.6f),
    Steel(R.string.demo_physics_ball_steel, 0.06f, 0.35f, 0.997f, 4f, SceneViewColors.TintLight, 1f, 0.22f),
    Foam(R.string.demo_physics_ball_foam, 0.085f, 0.2f, 0.96f, 0.3f, SceneViewColors.TintSoft, 0f, 0.95f),
}

/** One ball on the tray. [id] is unique for the screen's life, so a Compose key is never reused. */
private data class TrayBall(
    val id: Int,
    val kind: BallKind,
    val start: Position,
    val velocity: Position = Position(0f),
)

@Composable
private fun PhysicsSection(
    onBack: () -> Unit,
    mode: AnimationPhysicsMode,
    onModeChange: (AnimationPhysicsMode) -> Unit,
) {
    val simulation = remember { DemoCollisionReplay() }
    val balls = remember { mutableStateListOf<TrayBall>().apply { addAll(openingBalls(firstId = 0)) } }
    var nextId by remember { mutableIntStateOf(balls.size) }
    var dropCount by remember { mutableIntStateOf(0) }
    var selectedKind by remember { mutableStateOf(BallKind.Rubber) }
    var liveBodyCount by remember { mutableIntStateOf(0) }
    var collisions by remember { mutableIntStateOf(0) }

    // Whether the simulation still has visible work, measured from the bodies (#3718). Every
    // change of population or slope re-arms it, so the screen renders continuously exactly while
    // something moves and parks on demand once the tray is still.
    var simulationMoving by remember { mutableStateOf(true) }
    val wake: () -> Unit = {
        simulation.restartSettle()
        simulationMoving = true
    }

    // Reset: the opening shot again, with fresh ids so every body is rebuilt from its start pose.
    // The scene itself is untouched — nothing is torn down, so nothing can show black.
    val reset: () -> Unit = {
        balls.clear()
        simulation.resetCounters()
        val opening = openingBalls(firstId = nextId)
        nextId += opening.size
        balls.addAll(opening)
        dropCount = 0
        wake()
    }
    // Drop: [count] balls of [kind] over the tray, on a golden-angle spiral so consecutive drops
    // never stack on one another. Past the cap the oldest ball makes room.
    val drop: (BallKind, Int) -> Unit = { kind, count ->
        repeat(count) { k ->
            if (balls.size >= PHYSICS_MAX_BODIES) balls.removeAt(0)
            val spot = dropPosition(dropCount)
            balls.add(TrayBall(nextId, kind, Position(spot.x, spot.y + (k / 5) * PHYSICS_DROP_LAYER, spot.z)))
            nextId++
            dropCount++
        }
        wake()
    }

    // ── Tray tilt (#3621) ────────────────────────────────────────────────────
    // `tiltEnabled` swaps what a one-finger drag over the viewport does: OFF it orbits the camera,
    // ON it tips the tray. The toggle raises a transparent pointer-input layer above the SceneView
    // that consumes the drag, so the orbit is untouched when it is off. The angles survive the
    // toggle — tilting, turning tilt off to re-frame, then back on is a normal thing to do.
    var tiltEnabled by remember { mutableStateOf(false) }
    val pitchAnim = remember { Animatable(0f) }
    val rollAnim = remember { Animatable(0f) }
    val tiltScope = rememberCoroutineScope()

    // Gravity expressed in the *tray's* frame: the whole rig hangs off a pivot node rotated by
    // (pitch, 0, roll), so the simulation keeps its flat floor and axis-aligned rails and the slope
    // shows up purely as a horizontal component of gravity.
    val trayGravity = remember(pitchAnim.value, rollAnim.value) {
        trayLocalGravity(pitchAnim.value, rollAnim.value)
    }
    LaunchedEffect(simulation, trayGravity) {
        simulation.gravity = trayGravity
        wake()
    }
    val applyTilt: (Float, Float) -> Unit = { pitch, roll ->
        tiltScope.launch {
            pitchAnim.snapTo(pitch.coerceIn(-PHYSICS_MAX_TILT_DEGREES, PHYSICS_MAX_TILT_DEGREES))
            rollAnim.snapTo(roll.coerceIn(-PHYSICS_MAX_TILT_DEGREES, PHYSICS_MAX_TILT_DEGREES))
        }
    }
    val levelTray: () -> Unit = {
        tiltScope.launch {
            launch { pitchAnim.animateTo(0f, tween(400, easing = FastOutSlowInEasing)) }
            rollAnim.animateTo(0f, tween(400, easing = FastOutSlowInEasing))
        }
    }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    // Studio backdrop (#3820): the tray sits in a lit room, never a black void inside the
    // reserved band.
    val studioEnvironment = rememberHDREnvironment(
        environmentLoader,
        "environments/studio_2k.hdr",
        createSkybox = true,
    )
    val physicsEnvironment = studioEnvironment ?: rememberEnvironment(environmentLoader)
    val cameraNode = rememberCameraNode(engine)
    val firstFrame = rememberFirstFrameState(engine)
    val counts =stringResource(R.string.demo_animation_physics_counts, liveBodyCount, collisions)

    DemoScaffold(
        title = stringResource(R.string.demo_animation_physics_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        peekHeader = counts,
        // The scene is framed inside the band between the title row and these controls, so the
        // tray is never drawn under them.
        bottomOverlayReservesScene = true,
        bottomOverlay = {
            if (tiltEnabled) {
                DemoStatusBanner(
                    text = stringResource(R.string.demo_animation_physics_tilt_hint),
                    tone = DemoStatusTone.Guidance,
                )
            }
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
            ) {
                BallKind.entries.forEach { kind ->
                    TrayGlassChip(
                        label = stringResource(kind.labelRes),
                        selected = kind == selectedKind,
                        swatch = kind.color,
                        toggle = false,
                        onClick = {
                            // Picking a material drops one straight away: the choice is seen,
                            // not just recorded.
                            selectedKind = kind
                            drop(kind, 1)
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { drop(selectedKind, 1) },
                    modifier = Modifier.heightIn(min = SceneViewTokens.Layout.touchTarget),
                ) {
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = null,
                        modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize),
                    )
                    Spacer(Modifier.width(SceneViewTokens.Space.xs))
                    Text(stringResource(R.string.demo_animation_physics_drop))
                }
                TrayGlassChip(
                    label = stringResource(R.string.demo_animation_physics_tilt_drag),
                    selected = tiltEnabled,
                    icon = Icons.Rounded.ScreenRotationAlt,
                    toggle = true,
                    onClick = { tiltEnabled = !tiltEnabled },
                )
                GlassActionPill(
                    icon = Icons.Outlined.RestartAlt,
                    label = stringResource(R.string.demo_animation_physics_reset),
                    onClick = reset,
                )
            }
        },
        controls = {
            ModeSelector(mode, onModeChange)
            Text(counts, style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                OutlinedButton(onClick = { drop(selectedKind, 10) }) {
                    Text(stringResource(R.string.demo_animation_physics_drop_ten))
                }
                OutlinedButton(
                    enabled = pitchAnim.value != 0f || rollAnim.value != 0f,
                    onClick = levelTray,
                ) { Text(stringResource(R.string.demo_animation_physics_tilt_level)) }
            }
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.xs))
            Text(
                stringResource(R.string.demo_animation_physics_physics_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))
            Text(
                text = stringResource(R.string.demo_animation_physics_tilt_label),
                style = MaterialTheme.typography.labelLarge,
            )
            // The sliders mirror the drag rather than replacing it: they are what a screen reader
            // can operate, and they give the exact angle the drag can only approximate.
            LabeledSlider(
                label = stringResource(R.string.demo_animation_physics_tilt_pitch),
                value = pitchAnim.value,
                onValueChange = { applyTilt(it, rollAnim.value) },
                valueRange = -PHYSICS_MAX_TILT_DEGREES..PHYSICS_MAX_TILT_DEGREES,
                decimals = 0,
                unit = "°",
            )
            LabeledSlider(
                label = stringResource(R.string.demo_animation_physics_tilt_roll),
                value = rollAnim.value,
                onValueChange = { applyTilt(pitchAnim.value, it) },
                valueRange = -PHYSICS_MAX_TILT_DEGREES..PHYSICS_MAX_TILT_DEGREES,
                decimals = 0,
                unit = "°",
            )
            Text(
                text = stringResource(R.string.demo_animation_physics_tilt_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Framed in the viewport this scene actually gets — the band between the title row
            // and the controls — not the whole screen: the tray and the column the balls drop
            // from fill it, seen from a fixed look-down.
            val aspect = if (maxWidth.value > 0f && maxHeight.value > 0f) {
                maxWidth.value / maxHeight.value
            } else {
                0.5f
            }
            val cameraManipulator = remember(aspect) { trayCameraManipulator(aspect) }
            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                environment = physicsEnvironment,
                cameraNode = cameraNode,
                // The rig is authored around the origin; re-centring it on its bounds would move
                // the tray every time a ball flies up.
                autoCenterContent = false,
                onFrame = { nanos ->
                    firstFrame.onFrame(nanos)
                    // Step only once every ball on the list has registered its body, so a reset's
                    // opening shot always starts from the same complete population.
                    simulation.onFrame(nanos, playing = simulation.bodies.size == balls.size)
                    liveBodyCount = simulation.bodies.size
                    collisions = simulation.collisions
                    simulationMoving = simulation.isMoving
                },
                // The simulation is stepped from `onFrame`, which fires only *after* a frame
                // reached the surface — so it cannot be what keeps the loop awake (#3718). While
                // something moves the screen declares every vsync; a still tray hands it back to
                // on-demand, where a tilt, a drop or an orbit still repaints.
                frameRatePolicy = if (simulationMoving) {
                    FrameRatePolicy.Continuous()
                } else {
                    FrameRatePolicy.OnDemand()
                },
                cameraManipulator = cameraManipulator,
            ) {
                LightNode(
                    type = LightManager.Type.DIRECTIONAL,
                    direction = io.github.sceneview.math.Direction(-0.3f, -1f, -0.5f),
                    apply = { intensity(5_000f) },
                )
                val trayMaterial = rememberMaterialInstance(materialLoader, SceneViewColors.SurfaceDim)
                val railMaterial = rememberMaterialInstance(
                    materialLoader, SceneViewColors.AccentDeep, metallic = 0f, roughness = 0.5f,
                )
                val rubberMaterial = rememberMaterialInstance(
                    materialLoader, BallKind.Rubber.color,
                    metallic = BallKind.Rubber.metallic, roughness = BallKind.Rubber.roughness,
                )
                val steelMaterial = rememberMaterialInstance(
                    materialLoader, BallKind.Steel.color,
                    metallic = BallKind.Steel.metallic, roughness = BallKind.Steel.roughness,
                )
                val foamMaterial = rememberMaterialInstance(
                    materialLoader, BallKind.Foam.color,
                    metallic = BallKind.Foam.metallic, roughness = BallKind.Foam.roughness,
                )

                // Tilt pivot (#3621) — the floor, the rails and every ball hang off this node, so
                // the tray rotates as one rigid rig while the simulation stays in its own flat
                // frame. The light stays at the scene root: the room does not tip with the tray.
                Node(rotation = Rotation(x = pitchAnim.value, z = rollAnim.value)) {
                    // Ground plane — Size(x, y = 0, z) is a HORIZONTAL floor.
                    PlaneNode(
                        materialInstance = trayMaterial,
                        size = Size(x = PHYSICS_TRAY_SIZE, y = 0f, z = PHYSICS_TRAY_SIZE),
                        position = Position(y = PHYSICS_FLOOR),
                    )
                    // Visible rails mark the bounds the collision response uses.
                    val half = PHYSICS_TRAY_SIZE / 2f
                    for (side in listOf(-1f, 1f)) {
                        CubeNode(
                            size = Size(PHYSICS_RAIL_THICKNESS, PHYSICS_RAIL_HEIGHT, PHYSICS_TRAY_SIZE),
                            position = Position(side * half, PHYSICS_FLOOR + PHYSICS_RAIL_HEIGHT / 2f, 0f),
                            materialInstance = railMaterial,
                        )
                        CubeNode(
                            size = Size(PHYSICS_TRAY_SIZE, PHYSICS_RAIL_HEIGHT, PHYSICS_RAIL_THICKNESS),
                            position = Position(0f, PHYSICS_FLOOR + PHYSICS_RAIL_HEIGHT / 2f, side * half),
                            materialInstance = railMaterial,
                        )
                    }

                    for (ball in balls) {
                        key(ball.id) {
                            var nodeRef by remember { mutableStateOf<SphereNodeImpl?>(null) }
                            SphereNode(
                                radius = ball.kind.radius,
                                materialInstance = when (ball.kind) {
                                    BallKind.Rubber -> rubberMaterial
                                    BallKind.Steel -> steelMaterial
                                    BallKind.Foam -> foamMaterial
                                },
                                position = ball.start,
                                apply = { nodeRef = this },
                            )
                            nodeRef?.let { node ->
                                DisposableEffect(node, simulation) {
                                    simulation.add(
                                        id = ball.id,
                                        kind = ball.kind,
                                        body = PhysicsBody(
                                            node = node,
                                            restitution = ball.kind.restitution,
                                            floorY = PHYSICS_FLOOR,
                                            radius = ball.kind.radius,
                                            initialVelocity = ball.velocity,
                                            // A floor provider keeps resting balls awake, so a
                                            // later impact or a new slope still moves them.
                                            floorProvider = FloorProvider { _, _, _, _ -> PHYSICS_FLOOR },
                                            gravity = simulation.gravity,
                                        ),
                                    )
                                    onDispose { simulation.remove(ball.id) }
                                }
                            }
                        }
                    }
                }
            }

            // Tilt drag layer — present only while Tilt is on, so with it off every pointer event
            // reaches the SceneView and the camera orbits. Dragging DOWN tips the near edge down,
            // so the balls roll towards the viewer; dragging RIGHT drops the right edge.
            if (tiltEnabled) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                applyTilt(
                                    pitchAnim.value + dragAmount.y * PHYSICS_TILT_DEGREES_PER_PIXEL,
                                    rollAnim.value - dragAmount.x * PHYSICS_TILT_DEGREES_PER_PIXEL,
                                )
                            }
                        }
                )
            }
        }
    }
}

/**
 * A selectable capsule over the scene: glass when off, solid white when on — the white-on-media
 * language of the dock, readable on the dark stage in both themes. [swatch] shows the colour of
 * the ball a material chip stands for; [icon] labels a toggle. [toggle] picks the semantics: a
 * switch for Tilt, one radio button of a group for the materials.
 */
@Composable
private fun TrayGlassChip(
    label: String,
    selected: Boolean,
    toggle: Boolean,
    onClick: () -> Unit,
    swatch: Color? = null,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(SceneViewTokens.Radius.full)
    val content = if (selected) SceneViewTokens.Stage.background else SceneViewTokens.Glass.onGlass
    Row(
        modifier = Modifier
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .overMediaEdge(shape)
            .clip(shape)
            .background(if (selected) SceneViewTokens.Glass.onGlass else SceneViewTokens.Glass.surface)
            .then(
                if (toggle) {
                    Modifier.toggleable(value = selected, role = Role.Switch, onValueChange = { onClick() })
                } else {
                    Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                },
            )
            .padding(horizontal = SceneViewTokens.Glass.pillPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
    ) {
        when {
            selected && swatch != null -> Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize),
            )
            swatch != null -> Box(
                Modifier
                    .size(SceneViewTokens.Space.md)
                    .clip(CircleShape)
                    .background(swatch),
            )
            icon != null -> Icon(
                icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize),
            )
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/**
 * The tray's camera for a viewport of [aspect]: the tray and the drop column above it fitted at
 * [PHYSICS_CAMERA_PITCH_DEGREES] of look-down, with a stock orbit the user can drag.
 */
private fun trayCameraManipulator(aspect: Float): CameraGestureDetector.CameraManipulator {
    val distance = io.github.sceneview.demo.fitOrbitRadius(
        extentX = PHYSICS_FRAME_EXTENT.x,
        extentY = PHYSICS_FRAME_EXTENT.y,
        extentZ = PHYSICS_FRAME_EXTENT.z,
        aspect = aspect,
        elevationDegrees = PHYSICS_CAMERA_PITCH_DEGREES,
        fill = PHYSICS_FRAME_FILL,
        azimuthInvariant = false,
    )
    val pitch = Math.toRadians(PHYSICS_CAMERA_PITCH_DEGREES.toDouble())
    return TrayCameraManipulator(
        eye = Position(
            PHYSICS_FRAME_TARGET.x,
            PHYSICS_FRAME_TARGET.y + distance * sin(pitch).toFloat(),
            PHYSICS_FRAME_TARGET.z + distance * cos(pitch).toFloat(),
        ),
        target = PHYSICS_FRAME_TARGET,
    )
}

/**
 * The stock orbit with the eye kept above the tray: its polar angle is clamped between
 * [PHYSICS_MIN_POLAR_DEGREES] and [PHYSICS_MAX_POLAR_DEGREES] from straight up and re-aimed at
 * the tray, so no drag can carry the camera under the floor and lose the balls from view.
 */
private class TrayCameraManipulator(
    eye: Position,
    private val target: Position,
) : CameraGestureDetector.CameraManipulator {
    private val orbit = CameraGestureDetector.DefaultCameraManipulator(
        eyePosition = eye,
        targetPosition = target,
    )

    override fun setViewport(width: Int, height: Int) = orbit.setViewport(width, height)

    override fun getTransform(): Transform {
        val transform = orbit.getTransform()
        val eye = transform.position
        val clamped = io.github.sceneview.demo.clampOrbitEyePitch(
            eye, target, PHYSICS_MIN_POLAR_DEGREES, PHYSICS_MAX_POLAR_DEGREES,
        )
        if (clamped == eye) return transform
        return Transform(
            dev.romainguy.kotlin.math.lookAt(
                eye = clamped,
                target = target,
                up = dev.romainguy.kotlin.math.Float3(0f, 1f, 0f),
            )
        )
    }

    override fun grabBegin(x: Int, y: Int, strafe: Boolean) = orbit.grabBegin(x, y, strafe)
    override fun grabUpdate(x: Int, y: Int) = orbit.grabUpdate(x, y)
    override fun grabEnd() = orbit.grabEnd()
    override fun scrollBegin(x: Int, y: Int, separation: Float) = orbit.scrollBegin(x, y, separation)
    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) =
        orbit.scrollUpdate(x, y, prevSeparation, currSeparation)
    override fun scrollEnd() = orbit.scrollEnd()
    override fun update(deltaTime: Float) = orbit.update(deltaTime)
}

/**
 * Gravity expressed in the tilted tray's own frame.
 *
 * The tray pivot is rotated by `Rotation(pitch, 0, roll)`; a vector that is constant in world
 * space is therefore the inverse of that rotation applied to it inside the tray. We build the
 * rotation through the exact same `toQuaternion()` call [io.github.sceneview.node.Node.rotation]
 * uses and invert it by transposing the matrix (a rotation matrix is orthonormal), so the result
 * cannot drift from whatever Euler order the SDK settles on.
 *
 * At zero tilt this returns `(0, PhysicsBody.GRAVITY, 0)` exactly, which is what keeps the
 * untouched demo bit-identical to its previous behaviour.
 */
internal fun trayLocalGravity(pitchDegrees: Float, rollDegrees: Float): Position {
    if (pitchDegrees == 0f && rollDegrees == 0f) {
        return Position(0f, PhysicsBody.GRAVITY, 0f)
    }
    val trayRotation = rotationMatrix(Rotation(pitchDegrees, 0f, rollDegrees).toQuaternion())
    val local = transpose(trayRotation) * Float4(0f, PhysicsBody.GRAVITY, 0f, 0f)
    return Position(local.x, local.y, local.z)
}

/** Look-down of the turntable shot: enough to show the ground under the subject. */
private const val ANIMATION_ORBIT_ELEVATION_DEGREES = 14f

/** Share of the frame the subject fills at the fit radius. */
private const val ANIMATION_FILL = 0.82f

/** Front three-quarter view the turntable starts from, and the QA freeze holds. */
private const val ANIMATION_START_YAW_DEGREES = 60f
private const val ANIMATION_PLINTH_RADIUS_FACTOR = 0.75f
private const val ANIMATION_PLINTH_HEIGHT = 0.04f

/** One full turntable revolution — slow enough that the animation, not the camera, leads. */
private const val ANIMATION_TURN_MILLIS = 40_000

/** Eye height over distance for the Reveal shot: a ~19° look-down at both ends of the dolly. */
private const val REVEAL_LIFT_RATIO = 0.35f

/** How long the lens takes to follow the camera into a new shot — the pose's own ease. */
private const val CUT_EASE_MILLIS = 700

/** The swing onto the start of the tracking shot's track: up to most of a half turn. */
private const val TRACK_ENTRY_MILLIS = 1_200L

/** The Reveal shot's way back from the wide frame to its close-up, before the next pull-back. */
private const val REVEAL_PUSH_IN_MILLIS = 3_000

/** Balls on the tray at once; past it a drop recycles the oldest, so Drop never goes dead. */
private const val PHYSICS_MAX_BODIES = 30
private const val PHYSICS_FLOOR = -0.5f
private const val PHYSICS_STEP_NANOS = 8_333_333L

/** Side of the square tray, rail to rail. */
private const val PHYSICS_TRAY_SIZE = 1.6f
private const val PHYSICS_RAIL_THICKNESS = 0.03f
private const val PHYSICS_RAIL_HEIGHT = 0.16f

/** Height a dropped ball starts from — inside the frame, so the fall itself is seen. */
private const val PHYSICS_DROP_HEIGHT = 0.2f

/** Extra height per five balls of one multi-ball drop, so a Drop 10 does not spawn overlapping. */
private const val PHYSICS_DROP_LAYER = 0.2f

/** What the camera frames: the tray plus the drop column above it. */
private val PHYSICS_FRAME_EXTENT = Position(PHYSICS_TRAY_SIZE + 0.1f, 0.75f, PHYSICS_TRAY_SIZE + 0.1f)

/** Centre of [PHYSICS_FRAME_EXTENT]: from the floor to just above the drop height. */
private val PHYSICS_FRAME_TARGET = Position(0f, PHYSICS_FLOOR + 0.35f, 0f)

/** Look-down of the opening frame: the whole tray floor reads, and so does a ball's bounce. */
private const val PHYSICS_CAMERA_PITCH_DEGREES = 32f

/** Share of the viewport the framed volume fills. */
private const val PHYSICS_FRAME_FILL = 0.92f

/** The orbit's polar range: never straight down, never level with or under the tray. */
private const val PHYSICS_MIN_POLAR_DEGREES = 15f
private const val PHYSICS_MAX_POLAR_DEGREES = 78f

/**
 * Minimum closing speed, in m/s, for a contact to be worth counting as an impact.
 *
 * A body pressed against a rail is re-accelerated into it every step, so without this floor a
 * tilted tray counts one "impact" per body per step — 7 resting balls turned the counter into
 * 75 000 in under a minute (#3621). It matches the threshold the floor bounce and the
 * sphere-to-sphere response already use, so all three surfaces agree on what a hit is.
 */
private const val PHYSICS_IMPACT_SPEED = 0.2f

/**
 * How long every body has to stay put before the screen stops declaring continuous rendering.
 * Matches the SDK's own settle window (`SETTLE_DURATION_NANOS`), and for the same reason: a ball at
 * the apex of its bounce is motionless for one frame without being finished.
 */
private const val PHYSICS_SETTLE_NANOS = 500_000_000L

/** Squared displacement below which a body counts as not having moved: 0.1 mm. */
private const val MOTION_EPSILON_SQ = 1e-8f

private fun distanceSquared(a: Position, b: Position): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return dx * dx + dy * dy + dz * dz
}

/** Tilt is clamped well short of the angle at which the rails stop being able to hold a ball. */
private const val PHYSICS_MAX_TILT_DEGREES = 20f

/**
 * Drag sensitivity. At ~2.6x density a comfortable half-screen swipe (≈500 px) sweeps the full
 * ±20° range, so the extremes are reachable without the control feeling twitchy near flat.
 */
private const val PHYSICS_TILT_DEGREES_PER_PIXEL = 0.06f

/**
 * Turns "did anything actually move?" into the answer a [FrameRatePolicy.Continuous] declaration is
 * allowed to rest on (#3718).
 *
 * The physics screen used to declare `Continuous()` from a `replaying` flag that started `true` and
 * was cleared only by the Reset button. The stack of spheres therefore held 878 frames per 15 s on a
 * picture identical to the byte — the same lie as a loading flag nobody lowers. A declaration of
 * continuous rendering has to follow real motion, so this watches the thing that would be visible:
 * the bodies' positions, with a settle window rather than a single still frame, because a ball at
 * the top of its bounce is motionless for one frame and is not finished.
 */
internal class MotionSettleTracker(private val settleNanos: Long = PHYSICS_SETTLE_NANOS) {

    private var lastMotionNanos: Long? = null

    /** True while something moved within the last [settleNanos]. Starts `true`: nothing seen yet. */
    var isMoving: Boolean = true
        private set

    fun update(frameTimeNanos: Long, moved: Boolean) {
        val since = lastMotionNanos
        if (moved || since == null) {
            lastMotionNanos = frameTimeNanos
            isMoving = true
            return
        }
        isMoving = frameTimeNanos - since < settleNanos
    }

    /** Re-arms the window: a replay, a new body or a tilt is motion that has not happened yet. */
    fun restart() {
        lastMotionNanos = null
        isMoving = true
    }
}

/**
 * The opening shot, and what Reset restores: a steel cue ball rolling into a pyramid of six rubber
 * balls. Steel is four times the rubber's mass, so the pile scatters instead of stopping it dead.
 */
private fun openingBalls(firstId: Int): List<TrayBall> {
    val r = BallKind.Rubber.radius
    val rowHeight = r * sqrt(3f)
    val baseX = 0.05f
    val pyramid = listOf(
        Position(baseX, PHYSICS_FLOOR + r, 0f),
        Position(baseX + 2f * r, PHYSICS_FLOOR + r, 0f),
        Position(baseX + 4f * r, PHYSICS_FLOOR + r, 0f),
        Position(baseX + r, PHYSICS_FLOOR + r + rowHeight, 0f),
        Position(baseX + 3f * r, PHYSICS_FLOOR + r + rowHeight, 0f),
        Position(baseX + 2f * r, PHYSICS_FLOOR + r + 2f * rowHeight, 0f),
    )
    val cue = TrayBall(
        id = firstId,
        kind = BallKind.Steel,
        start = Position(-0.65f, PHYSICS_FLOOR + 0.18f, 0f),
        velocity = Position(1.9f, 0.4f, 0f),
    )
    return listOf(cue) + pyramid.mapIndexed { i, p -> TrayBall(firstId + 1 + i, BallKind.Rubber, p) }
}

/**
 * Where the [n]th drop falls from: a golden-angle spiral over the tray, so consecutive drops land
 * apart from each other and every one of them is seen hitting the floor.
 */
private fun dropPosition(n: Int): Position {
    val angle = n * 2.3999632f
    val spread = 0.1f + 0.4f * ((n * 0.618034f) % 1f)
    return Position(spread * cos(angle), PHYSICS_DROP_HEIGHT, spread * sin(angle))
}

/** A deterministic sphere-contact demonstration, deliberately local to this sample. */
private class DemoCollisionReplay {
    val bodies = sortedMapOf<Int, PhysicsBody>()
    private val kinds = mutableMapOf<Int, BallKind>()

    private val settle = MotionSettleTracker()
    private val previousPositions = mutableMapOf<Int, Position>()

    /**
     * Whether the simulation still has visible work. Read by the composable to decide between
     * [FrameRatePolicy.Continuous] and [FrameRatePolicy.OnDemand] — see [MotionSettleTracker].
     */
    val isMoving: Boolean get() = settle.isMoving

    /** Called when the population or the slope changes: the settle window starts over. */
    fun restartSettle() = settle.restart()

    /**
     * Gravity in the tray's frame, pushed onto every body at the top of each step. Held here
     * rather than on the bodies so a ball dropped mid-tilt starts under the same slope as the
     * ones already rolling.
     */
    var gravity: Position = Position(0f, PhysicsBody.GRAVITY, 0f)
    var collisions = 0
        private set
    private var previousFrame = 0L
    private var accumulatedNanos = 0L

    fun add(id: Int, kind: BallKind, body: PhysicsBody) {
        bodies[id] = body
        kinds[id] = kind
    }

    fun remove(id: Int) {
        bodies.remove(id)
        kinds.remove(id)
    }

    /** Reset starts the impact count over along with the opening shot. */
    fun resetCounters() {
        collisions = 0
        accumulatedNanos = 0L
    }

    fun onFrame(nanos: Long, playing: Boolean) {
        val elapsed = if (previousFrame == 0L) 0L else (nanos - previousFrame).coerceIn(0L, 100_000_000L)
        previousFrame = nanos
        if (!playing) {
            settle.update(nanos, moved = false)
            return
        }
        accumulatedNanos += elapsed
        while (accumulatedNanos >= PHYSICS_STEP_NANOS) {
            step()
            accumulatedNanos -= PHYSICS_STEP_NANOS
        }
        settle.update(nanos, moved = takeMotionSinceLastFrame())
    }

    /**
     * Whether any body ended this frame somewhere the eye could tell from where it started it.
     * The threshold is a tenth of a millimetre — three orders of magnitude under a sphere radius,
     * so it cannot hide motion, and above the float noise a resting contact keeps producing.
     */
    private fun takeMotionSinceLastFrame(): Boolean {
        var moved = false
        for ((index, body) in bodies) {
            val position = body.node.position
            val previous = previousPositions.put(index, position)
            if (moved) continue
            moved = previous == null || distanceSquared(previous, position) > MOTION_EPSILON_SQ
        }
        if (previousPositions.size != bodies.size) {
            previousPositions.keys.retainAll(bodies.keys)
            moved = true
        }
        return moved
    }

    private fun kindOf(id: Int): BallKind = kinds[id] ?: BallKind.Rubber

    private fun step() {
        for ((id, body) in bodies) {
            body.gravity = gravity
            val before = body.velocity
            body.step(PHYSICS_STEP_NANOS, 0L)
            val p = body.node.position
            val v = body.velocity
            if (before.y < -PHYSICS_IMPACT_SPEED && v.y > 0f) collisions++
            // The rails hold at any height: a ball that bounces over the rail height is kept on
            // the tray instead of leaving the frame for good. Only approaching impacts count, not
            // resting contacts or positional corrections.
            val bound = PHYSICS_TRAY_SIZE / 2f - PHYSICS_RAIL_THICKNESS / 2f - body.radius
            var vx = v.x
            var vz = v.z
            if (kotlin.math.abs(p.x) > bound && p.x * vx > 0f) {
                if (kotlin.math.abs(vx) >= PHYSICS_IMPACT_SPEED) collisions++
                vx = -vx * body.restitution
            }
            if (kotlin.math.abs(p.z) > bound && p.z * vz > 0f) {
                if (kotlin.math.abs(vz) >= PHYSICS_IMPACT_SPEED) collisions++
                vz = -vz * body.restitution
            }
            if (p.y <= PHYSICS_FLOOR + body.radius && kotlin.math.abs(v.y) < 0.2f) {
                val friction = kindOf(id).rollFriction
                vx *= friction
                vz *= friction
            }
            if (kotlin.math.abs(p.x) > bound || kotlin.math.abs(p.z) > bound) {
                body.node.position = Position(p.x.coerceIn(-bound, bound), p.y, p.z.coerceIn(-bound, bound))
            }
            body.velocity = Position(vx, v.y, vz)
        }
        // Stable id order, a fixed timestep and fixed initial velocities make the same opening
        // population produce the same sequence of impacts on every reset.
        for ((aId, a) in bodies) {
            for ((bId, b) in bodies) {
                if (bId > aId && resolvePair(a, kindOf(aId), b, kindOf(bId))) collisions++
            }
        }
    }

    /**
     * Separates [a] and [b] if their spheres overlap and exchanges the mass-weighted impulse along
     * the contact normal: the lighter ball gives way and takes the larger share of the velocity
     * change. Returns `true` when the pair met hard enough to count as an impact.
     */
    private fun resolvePair(a: PhysicsBody, aKind: BallKind, b: PhysicsBody, bKind: BallKind): Boolean {
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
        val inverseA = 1f / aKind.mass
        val inverseB = 1f / bKind.mass
        val inverseSum = inverseA + inverseB
        val overlap = diameter - distance
        val shiftA = overlap * inverseA / inverseSum
        val shiftB = overlap * inverseB / inverseSum
        a.node.position = Position(
            pa.x - nx * shiftA,
            (pa.y - ny * shiftA).coerceAtLeast(PHYSICS_FLOOR + a.radius),
            pa.z - nz * shiftA,
        )
        b.node.position = Position(
            pb.x + nx * shiftB,
            (pb.y + ny * shiftB).coerceAtLeast(PHYSICS_FLOOR + b.radius),
            pb.z + nz * shiftB,
        )
        val va = a.velocity
        val vb = b.velocity
        val approach = (vb.x - va.x) * nx + (vb.y - va.y) * ny + (vb.z - va.z) * nz
        if (approach >= 0f) return false
        val restitution = minOf(aKind.restitution, bKind.restitution)
        val impulse = -(1f + restitution) * approach / inverseSum
        a.velocity = Position(
            va.x - impulse * inverseA * nx, va.y - impulse * inverseA * ny, va.z - impulse * inverseA * nz,
        )
        b.velocity = Position(
            vb.x + impulse * inverseB * nx, vb.y + impulse * inverseB * ny, vb.z + impulse * inverseB * nz,
        )
        return approach < -PHYSICS_IMPACT_SPEED
    }
}
