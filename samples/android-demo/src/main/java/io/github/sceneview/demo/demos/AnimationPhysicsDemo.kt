package io.github.sceneview.demo.demos

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.google.android.filament.Skybox
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.LoadingScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.driving
import io.github.sceneview.demo.rememberContinuousCameraManipulator
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.sketchfab.SampleAssets
import io.github.sceneview.demo.sketchfab.SketchfabAssetResolver
import io.github.sceneview.demo.sketchfab.SketchfabSlug
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.model.Model
import io.github.sceneview.model.model
import io.github.sceneview.toAabb
import io.github.sceneview.node.ModelNode as ModelNodeImpl
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.LifecyclePausingLaunchedEffect
import io.github.sceneview.sample.ui.LabeledSlider
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width

/**
 * Animation demo — skeletal / keyframe animation playback with a model carousel,
 * cinematic camera shots, and play / pause / speed / loop controls.
 *
 * The id stays `animation-physics` so existing deep links, the home card preview and
 * the render golden keep resolving. Its Physics tab, the tray of rolling balls, is
 * its own demo since #4083: see [RollingBallsDemo]. The retired `physics` deep link
 * routes there through [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES].
 */
@Composable
fun AnimationPhysicsDemo(onBack: () -> Unit) {
    AnimationSection(onBack)
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
private fun AnimationSection(onBack: () -> Unit) {
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
    // Default 10_000 lux matches SceneView's balanced IBL default (#1075); a lower
    // default left the model reading as a black silhouette (#1468). Range 0–10_000
    // still lets users dial down to a darker, atmospheric look.
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
        //
        // `key` gives each subject its own load. `rememberModelInstance` is built on
        // `produceState`, which keeps its last value when its key changes: without `key`, a
        // subject switch left the previous model on screen, alive, with no loading scrim, until
        // the new one landed (#3883). Here the previous subject leaves the composition, its model
        // is destroyed at once, and the instance reads `null` — so the scrim and the card's
        // "Loading…" cover the switch exactly as they cover the first load.
        key(activeFileLocation) {
            rememberModelInstance(modelLoader, fileLocation = activeFileLocation)
        }
    } else null

    // Studio stage (#3820), the Sketchfab default: studio HDR light, a neutral grey backdrop.
    // A photographed place put the subject floating over whatever ground the panorama had (the
    // garden's pond, the rooftop's car park before it); a plain backdrop has no ground to miss.
    val hdrEnvironment = rememberHDREnvironment(
        environmentLoader,
        "environments/studio_2k.hdr",
        createSkybox = false,
    )
    val fallbackEnvironment = rememberEnvironment(environmentLoader)
    val stageSkybox = remember(engine) { neutralStageSkybox(engine) }
    val lightEnvironment = hdrEnvironment ?: fallbackEnvironment
    val activeEnvironment = remember(lightEnvironment, stageSkybox) {
        lightEnvironment.copy(skybox = stageSkybox)
    }

    // Pin the IBL intensity to the slider value — near SceneView's balanced 10k default a
    // subject is lit, far below it reads as an unlit black silhouette (#1468). Re-runs whenever
    // the active environment OR the slider value change, so dragging it updates in real time.
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

    // Only the node built from the instance we hold right now. On a subject switch the
    // previous model is destroyed as soon as its key changes (see `key` above), but the
    // ref is only cleared once the scene's own composition drops the old node — later, often not
    // until the new subject has loaded. Reading `animationCount` or posing that stale node in
    // between is a native use-after-free (#3801).
    val node = modelNodeRef.value?.takeIf { it.modelInstance === modelInstance }
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
    // Re-pin the animation track to the subject's default once its node lands. Not on the switch
    // itself: until the new subject has loaded there is no node to clamp against, and clamping
    // against the previous subject's clip count folded the new default back to 0 — the
    // soldier opened on Idle instead of Walk. We can't always know a streamed model's clip count
    // up-front, so out-of-range defaults are clamped below.
    LaunchedEffect(node) {
        node ?: return@LaunchedEffect
        selectedAnim = activeModel.defaultAnimationIndex.coerceAtLeast(0)
    }
    LaunchedEffect(node, selectedAnim, DemoSettings.qaMode) {
        node ?: return@LaunchedEffect
        for (index in 0 until node.animationCount) node.stopAnimation(index)
        if (node.animationCount > 0 && selectedAnim !in animationNames.indices) selectedAnim = 0
    }
    // No node yet means the subject is still loading, which is not the same thing as a subject
    // that has no clip: the card used to read "No animation clip available" for the whole load
    // (#3801). Until the node lands, the card names the slot and says it is loading.
    val clipsLoading = node == null
    val clipName = animationNames.getOrNull(selectedAnim)
        ?: stringResource(
            if (clipsLoading) R.string.demo_animation_physics_clip else R.string.demo_animation_physics_no_clip,
        )

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
    // Eye height above the target for the orbit: a slight look down on the subject.
    val elevationRadians = Math.toRadians(ANIMATION_ORBIT_ELEVATION_DEGREES.toDouble())
    val baseYHeight = baseRadius * kotlin.math.tan(elevationRadians).toFloat()
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
    LaunchedEffect(playing, clipTime, selectedAnim, blendIndex, blendWeight, node) {
        if (playing) return@LaunchedEffect
        val animatedNode = node ?: return@LaunchedEffect
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
                // `surface-container`, DESIGN.md's card role — not `surface`: in dark, `surface`
                // (#0D1117) is one step off the `stage-background` loading cover (#0B0F16) and the
                // card vanished into it while the subject loaded (#3883). Light is #FFFFFF either way.
                modifier = Modifier.padding(horizontal = SceneViewTokens.Space.md)
                    .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
                    .padding(SceneViewTokens.Space.sm),
                verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
            ) {
                Text(
                    clipName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (clipsLoading) {
                        stringResource(R.string.demo_animation_physics_clip_loading)
                    } else {
                        stringResource(R.string.demo_animation_physics_clip_status,
                            stringResource(
                                if (isPlaying && !DemoSettings.qaMode) {
                                    R.string.demo_animation_physics_playing
                                } else {
                                    R.string.demo_animation_physics_paused
                                },
                            ),
                            clipTime, duration)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (clipsLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { if (duration > 0f) (clipTime / duration).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (animationNames.size > 1) Text(
                    stringResource(R.string.demo_animation_physics_blend_status, clipName,
                        animationNames[blendIndex], (blendWeight * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        controls = {
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

            // IBL intensity of the studio HDR.
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
                    val animatedNode = node
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
                environmentLoader = environmentLoader,
                environment = activeEnvironment,
                cameraNode = cameraNode,
                cameraManipulator = activeManipulator,
            ) {
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
                    // Clean up the ref when the node leaves composition — but only if it is still
                    // this instance's node. On a subject switch the new node's `apply` runs during
                    // composition, before the old instance's `onDispose`; clearing unconditionally
                    // wiped the new node, which then never animated and left the card on its
                    // no-node state for good (#3801).
                    DisposableEffect(instance) {
                        onDispose {
                            if (modelNodeRef.value?.modelInstance === instance) modelNodeRef.value = null
                        }
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

/** Look-down of the turntable shot: enough to show the ground under the subject. */
private const val ANIMATION_ORBIT_ELEVATION_DEGREES = 14f

/** Share of the frame the subject fills at the fit radius. */
private const val ANIMATION_FILL = 0.82f

/** Front three-quarter view the turntable starts from, and the QA freeze holds. */
private const val ANIMATION_START_YAW_DEGREES = 60f

/**
 * Solid neutral backdrop for a studio stage. Filament skybox colours are linear: 0.4 reads as a
 * mid grey (~#A8A8AA), light enough to separate from the stage clear colour, dark enough for a
 * light floor to stand out. Freed with the engine (`rememberEngine` tears it down).
 */
internal fun neutralStageSkybox(engine: com.google.android.filament.Engine): Skybox =
    Skybox.Builder().color(0.40f, 0.40f, 0.42f, 1.0f).build(engine)

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
