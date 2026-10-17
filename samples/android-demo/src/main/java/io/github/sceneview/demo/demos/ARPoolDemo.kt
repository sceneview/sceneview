package io.github.sceneview.demo.demos

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.ar.core.Config
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.NodeScope
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.ar.ARCoachingOverlay
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARHapticFeedback
import io.github.sceneview.ar.ArGuidanceCue
import io.github.sceneview.ar.AutoPlacementNode
import io.github.sceneview.ar.AutoPlacementState
import io.github.sceneview.ar.PlacementPhase
import io.github.sceneview.ar.camera.ARCameraStream
import io.github.sceneview.ar.createARCameraStream
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.ar.rememberArGuidanceState
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.AR_CAMERA_INIT_SCRIM_TIMEOUT_MS
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.QaCameraBackdrop
import io.github.sceneview.demo.common.placement.FeaturePlacementScene
import io.github.sceneview.demo.common.placement.PLACEMENT_GESTURE_HINT_MS
import io.github.sceneview.demo.common.placement.PlacementActionCard
import io.github.sceneview.demo.common.placement.PlacementCard
import io.github.sceneview.demo.common.qaStateOverridesAllowed
import io.github.sceneview.demo.demos.internal.PoolGeometry
import io.github.sceneview.demo.demos.internal.PoolMesh
import io.github.sceneview.demo.demos.internal.PoolPreset
import io.github.sceneview.demo.demos.internal.PoolSpec
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.motionFade
import io.github.sceneview.demo.ui.ConnectedChoiceRow
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.haptic.rememberHapticFeedback
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.safeDestroyGeometry
import kotlinx.coroutines.delay
import io.github.sceneview.node.MeshNode as MeshNodeImpl

/**
 * `qa_state` value that draws the pool at a sixth of its size, so a whole 8 × 4 m pool fits
 * the indoor recording the emulator replays. Honoured only in QA mode.
 */
internal const val POOL_MINIATURE_QA_STATE = "pool_miniature"

/**
 * `qa_state` value that shows the placed pool without an AR session: an ordinary 3D scene seen
 * from where a person would stand, drawn over the QA backdrop photo. The emulator cannot run
 * ARCore (#2754), so this is the only way to look at the pool there. It checks the pool's own
 * look (liner, water, coping, night, and the hull keeping the background on screen around the
 * opening), never the AR: no session, no plane, no anchor. Honoured only in QA mode.
 */
internal const val POOL_PREVIEW_QA_STATE = "pool_preview"

/**
 * # Pool in the garden: a real-size in-ground pool dug into the real lawn
 *
 * The pool is procedural ([PoolGeometry]): a liner (floor, walls, Roman steps), a sheet of
 * water, a stone coping and an invisible **hull** around them. Nothing is drawn for the
 * ground: the lawn on screen is the camera image.
 *
 * ## How it looks dug in
 * Content modelled below the ground would float *in front of* the lawn, because the camera
 * image is only a background. Three pieces turn it into a hole:
 * 1. `ARCameraStream.isOccluderMeshEnabled = true` draws the camera image first.
 * 2. The hull, an open-topped box 5 cm outside the liner, uses
 *    `MaterialLoader.createOcclusionInstance()` at [ARCameraStream.OCCLUDER_PRIORITY]: it
 *    writes depth and no colour. Any line of sight that enters the ground outside the
 *    opening meets the hull before the liner, so the real lawn stays on screen there.
 *    Through the opening, the liner is nearest and the pool shows.
 * 3. The coping covers the seam, and the hull casts shadows: the ground shades the inside
 *    of the pool the way real ground would.
 *
 * Depth occlusion stays **off** on purpose: ARCore's depth is the surface of the lawn, which
 * is in front of everything dug under it, so it would erase the pool.
 *
 * Real size is locked with `AutoPlacementState.allowsScaling = false`: drag and twist move
 * and turn the pool, pinch and double-tap never resize it. Placement, coaching and recovery
 * are the shared automatic-placement flow of the other AR demos.
 *
 * ## Night
 * Night darkens the garden and switches on two lamps under the water. ARCore sets the scene's
 * light from the camera on every frame, so the demo cannot simply turn it down: the pool's
 * materials take a `daylight` factor instead ([NIGHT_LIGHT]) and their lamps are emission,
 * which no light estimate touches. The real garden is the camera image, darkened by the same
 * factor by a transparent black veil drawn over it and under the pool ([NightVeil]).
 */
@Composable
fun ARPoolDemo(onBack: () -> Unit) {
    // Choices outlive a session restart; the AR session itself does not.
    val choices = remember { PoolChoices() }
    var restart by remember { mutableIntStateOf(0) }
    key(restart) { PoolSession(choices, onBack, onRestart = { restart++ }) }
}

/** The liner finishes, as linear RGB. */
internal enum class PoolLiner(@StringRes val label: Int, val red: Float, val green: Float, val blue: Float) {
    WHITE(R.string.ar_pool_liner_white, 0.85f, 0.89f, 0.91f),
    SAND(R.string.ar_pool_liner_sand, 0.74f, 0.60f, 0.39f),
    LIGHT_GRAY(R.string.ar_pool_liner_gray, 0.45f, 0.48f, 0.50f),
    BLUE(R.string.ar_pool_liner_blue, 0.17f, 0.45f, 0.71f),
    ;

    val next: PoolLiner get() = entries[(ordinal + 1) % entries.size]
}

@Stable
private class PoolChoices {
    var preset by mutableStateOf(PoolPreset.FAMILY)
    var liner by mutableStateOf(PoolLiner.WHITE)
    var night by mutableStateOf(false)

    fun reset() {
        preset = PoolPreset.FAMILY
        liner = PoolLiner.WHITE
        night = false
    }
}

@Composable
private fun PoolSession(choices: PoolChoices, onBack: () -> Unit, onRestart: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // The camera image goes down first, so the hull can keep the real lawn on screen.
    val stream = rememberARCameraStream(materialLoader) {
        createARCameraStream(materialLoader).apply { isOccluderMeshEnabled = true }
    }
    val state = remember { AutoPlacementState().apply { allowsScaling = false } }
    val materials = rememberPoolMaterials(materialLoader)
    // Hoisted as a State: only the few composables that read it recompose while night falls.
    val night = animateFloatAsState(if (choices.night) 1f else 0f, motionFade(), label = "pool-night")
    val haptic = rememberHapticFeedback()
    val playback = rememberArPlaybackDataset()
    var availability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    var cameraReady by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    var startupTimedOut by remember { mutableStateOf(false) }
    var invalidMove by remember { mutableStateOf(false) }
    var hintShown by remember { mutableStateOf(false) }
    var showHint by remember { mutableStateOf(false) }
    var hadPlacement by remember { mutableStateOf(false) }
    var trackingFailure by remember { mutableStateOf<TrackingFailureReason?>(null) }
    val guidance = rememberArGuidanceState(state, trackingFailureReason = trackingFailure)
    val spec = choices.preset.spec
    val qaState = DemoSettings.qaDemoState.takeIf { qaStateOverridesAllowed() }
    val preview = qaState == POOL_PREVIEW_QA_STATE
    val poolScale = if (qaState == POOL_MINIATURE_QA_STATE) MINIATURE_SCALE else 1f
    PoolMaterialSync(
        materials, spec, choices.liner, night,
        veilAtNight = if (preview) NIGHT_VEIL_PREVIEW else NIGHT_VEIL_AR,
    )

    ARHapticFeedback(state, haptic)
    LaunchedEffect(state.hasPlacement) {
        if (state.hasPlacement && !hadPlacement && !hintShown) { hintShown = true; showHint = true }
        hadPlacement = state.hasPlacement
    }
    LaunchedEffect(showHint) { if (showHint) { delay(PLACEMENT_GESTURE_HINT_MS); showHint = false } }
    LaunchedEffect(state.phase) {
        if (state.phase == PlacementPhase.ADJUSTING) showHint = false
        if (state.phase != PlacementPhase.PLACED && state.phase != PlacementPhase.ADJUSTING) invalidMove = false
    }
    LaunchedEffect(preview, cameraReady, cameraFailed, availability) {
        // The QA preview runs no AR session: there is no camera to wait for.
        if (preview) return@LaunchedEffect
        if (!cameraReady && !cameraFailed && availability == null) {
            delay(AR_CAMERA_INIT_SCRIM_TIMEOUT_MS)
            startupTimedOut = true
            cameraFailed = true
            state.cameraFailed()
        }
    }
    fun reset() { invalidMove = false; state.resetPlacement(SystemClock.uptimeMillis()) }
    val card = when (state.phase) {
        PlacementPhase.NO_SURFACE -> PlacementCard.NO_SURFACE
        PlacementPhase.RECOVERY_FAILED -> PlacementCard.RECOVERY_FAILED
        else -> null
    }
    val placed = preview || state.phase == PlacementPhase.PLACED || state.phase == PlacementPhase.ADJUSTING
    val hasPool = preview || state.hasPlacement
    val dimensions = stringResource(
        R.string.ar_pool_dimensions, spec.length.toInt(), spec.width.toInt(), spec.shallowDepth, spec.deepDepth,
    )
    val header = if (poolScale == 1f) dimensions else stringResource(R.string.ar_pool_dimensions_miniature, dimensions)
    DemoScaffold(
        title = stringResource(R.string.demo_ar_pool_title),
        onBack = onBack,
        chromeToggleOnTap = false,
        peekHeader = if (hasPool) header else null,
        onReset = ::reset,
        onResetSettings = choices::reset,
        controls = { PoolControls(choices, state) },
        dock = listOf(
            DockItem(
                icon = Icons.Outlined.Palette,
                label = stringResource(R.string.ar_pool_liner_label, stringResource(choices.liner.label)),
                caption = stringResource(R.string.ar_pool_liner),
                onClick = { choices.liner = choices.liner.next },
            ),
            DockItem(
                icon = Icons.Outlined.NightsStay,
                label = stringResource(R.string.ar_pool_night_label),
                caption = stringResource(R.string.ar_pool_night),
                selected = choices.night,
                onClick = { choices.night = !choices.night },
            ),
            DockItem(
                icon = Icons.Outlined.RestartAlt,
                label = stringResource(R.string.ar_dock_reset_label),
                caption = stringResource(R.string.ar_dock_reset_caption),
                onClick = ::reset,
                enabled = hasPool,
            ),
        ),
        bottomOverlay = {
            if (!cameraFailed) {
                val message = when {
                    preview || card != null -> null
                    // The animated coaching speaks; the pill only adds what it cannot say.
                    guidance.isCoaching -> null
                    invalidMove -> stringResource(R.string.ar_place_keep_on_surface)
                    state.phase == PlacementPhase.SCANNING -> stringResource(R.string.ar_place_move_slowly)
                    state.phase == PlacementPhase.TRACKING_LOST -> stringResource(R.string.ar_place_tracking_paused) +
                        if (trackingFailure == TrackingFailureReason.INSUFFICIENT_LIGHT) {
                            " " + stringResource(R.string.ar_place_try_brighter_area)
                        } else {
                            ""
                        }
                    state.phase == PlacementPhase.RECOVERING -> stringResource(R.string.ar_place_finding_placement)
                    showHint -> stringResource(R.string.ar_pool_hint)
                    else -> null
                }
                DemoStatusBanner(message, tone = DemoStatusTone.Guidance)
                PlacementActionCard(card, null, { state.keepScanning(SystemClock.uptimeMillis()) }, ::reset, onRestart)
                if (placed && card == null) PoolSizePill(choices.preset) { choices.preset = it }
            }
        },
    ) {
        if (preview) {
            PoolPreviewScene(engine, modelLoader, materialLoader, spec, materials, night)
            return@DemoScaffold
        }
        FeaturePlacementScene(
            assetReady = true,
            state = state, engine = engine, modelLoader = modelLoader, materialLoader = materialLoader,
            modifier = Modifier.fillMaxSize(), cameraStream = stream, playbackDataset = playback,
            sessionConfiguration = { _, config ->
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            },
            onSessionCreated = {
                cameraFailed = false
                startupTimedOut = false
                if (state.phase == PlacementPhase.CAMERA_ERROR) reset()
            },
            onSessionUpdated = { _, _ -> cameraReady = true },
            onSessionFailed = { cameraFailed = true },
            onARCoreAvailability = { availability = it },
            onTrackingFailureChanged = { trackingFailure = it },
        ) { placement ->
            placement?.let { result ->
                AutoPlacementNode(result, state, onInvalidMove = { invalidMove = it }) { opacity ->
                    PoolNodes(spec, materials, night, opacity, poolScale)
                }
            }
        }
        // Silent while a card explains that AR could not start (#3986).
        ARCoachingOverlay(
            cue = if (availability == null && !startupTimedOut) guidance.cue else ArGuidanceCue.NONE,
            surface = guidance.surface,
            hint = guidance.hint,
            scanLingering = guidance.scanLingering,
        )
        ARCameraInitScrim(!cameraReady && !cameraFailed, availability)
        if (startupTimedOut && availability == null) {
            PlacementActionCard(PlacementCard.CAMERA_ERROR, null, {}, ::reset, onRestart)
        }
    }
}

/**
 * [POOL_PREVIEW_QA_STATE]: the pool in a plain scene, from a person's eyes at the near end,
 * over the QA backdrop photo. The scene is translucent and the photo is drawn beneath it, so
 * the hull's depth-only pass keeps the photo on screen around the opening exactly as it keeps
 * the camera image in AR. The lens matches a phone camera held upright.
 *
 * Night goes through the very path it takes in AR: the materials' `daylight` and the
 * [NightVeil] over the photo. The scene's own lights are left alone, as ARCore's are.
 */
@Composable
private fun PoolPreviewScene(
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    spec: PoolSpec,
    materials: PoolMaterials,
    night: State<Float>,
) {
    val camera = rememberCameraNode(engine) {
        position = PREVIEW_EYE
        lookAt(PREVIEW_TARGET)
        focalLength = PREVIEW_FOCAL_LENGTH_MM
    }
    QaCameraBackdrop(seed = PREVIEW_BACKDROP_SEED)
    SceneView(
        modifier = Modifier.fillMaxSize(),
        surfaceType = SurfaceType.TextureSurface,
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        isOpaque = false,
        // The water's ripples and caustics move with time.
        frameRatePolicy = FrameRatePolicy.Continuous(),
        autoCenterContent = false,
        cameraNode = camera,
        cameraManipulator = null,
        onGestureListener = null,
    ) {
        Node { PoolNodes(spec, materials, night, opacity = 1f, scale = 1f) }
    }
}

/** The sheet: size, liner, night, and the accessible move / turn buttons. No resize. */
@Composable
private fun PoolControls(choices: PoolChoices, state: AutoPlacementState) {
    Text(
        text = stringResource(R.string.ar_pool_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(SceneViewTokens.Space.md))
    Text(stringResource(R.string.ar_pool_size), style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(SceneViewTokens.Space.sm))
    ConnectedChoiceRow(
        options = PoolPreset.entries,
        selected = choices.preset,
        onSelect = { choices.preset = it },
        label = { presetLabel(it) },
        optionTestTag = { "pool_size_${it.name.lowercase()}" },
    )
    Spacer(Modifier.height(SceneViewTokens.Space.md))
    Text(stringResource(R.string.ar_pool_liner), style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(SceneViewTokens.Space.sm))
    ConnectedChoiceRow(
        options = PoolLiner.entries,
        selected = choices.liner,
        onSelect = { choices.liner = it },
        label = { stringResource(it.label) },
        optionTestTag = { "pool_liner_${it.name.lowercase()}" },
    )
    Spacer(Modifier.height(SceneViewTokens.Space.md))
    SwitchRow(
        label = stringResource(R.string.ar_pool_night_switch),
        checked = choices.night,
        onCheckedChange = { choices.night = it },
    )
    if (state.hasPlacement && state.isSelected) {
        val editable = state.phase == PlacementPhase.PLACED
        Spacer(Modifier.height(SceneViewTokens.Space.md))
        PoolAdjustment(
            R.string.wall_dpad_left, R.string.wall_dpad_right, editable,
            { state.moveBy(-NUDGE_METRES, 0f) }, { state.moveBy(NUDGE_METRES, 0f) },
        )
        PoolAdjustment(
            R.string.ar_adjust_move_closer, R.string.ar_adjust_move_farther, editable,
            { state.moveBy(0f, NUDGE_METRES) }, { state.moveBy(0f, -NUDGE_METRES) },
        )
        PoolAdjustment(
            R.string.ar_adjust_rotate_left, R.string.ar_adjust_rotate_right, editable,
            { state.rotateBy(-NUDGE_DEGREES) }, { state.rotateBy(NUDGE_DEGREES) },
        )
    }
}

@Composable
private fun PoolAdjustment(
    @StringRes decreaseLabel: Int,
    @StringRes increaseLabel: Int,
    enabled: Boolean,
    decrease: () -> Unit,
    increase: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs)) {
        OutlinedButton(onClick = decrease, enabled = enabled) { Text(stringResource(decreaseLabel)) }
        OutlinedButton(onClick = increase, enabled = enabled) { Text(stringResource(increaseLabel)) }
    }
}

@Composable
private fun presetLabel(preset: PoolPreset): String =
    stringResource(R.string.ar_pool_size_option, preset.length.toInt(), preset.width.toInt())

/**
 * The three sizes over the camera: the segmented over-media pill of `DESIGN.md`
 * (`mode-pill-*`), opaque so it reads on any lawn in both themes.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PoolSizePill(preset: PoolPreset, onSelect: (PoolPreset) -> Unit) {
    val pill = SceneViewTokens.ModePill
    Surface(
        shape = CircleShape,
        color = pill.container,
        contentColor = pill.onContainer,
        border = BorderStroke(pill.outlineWidth, pill.outline),
    ) {
        ConnectedChoiceRow(
            options = PoolPreset.entries,
            selected = preset,
            onSelect = onSelect,
            label = { presetLabel(it) },
            modifier = Modifier.padding(horizontal = SceneViewTokens.Space.xs),
            optionTestTag = { "pool_pill_${it.name.lowercase()}" },
            colors = ToggleButtonDefaults.colors(
                containerColor = pill.container,
                contentColor = pill.onContainer,
                checkedContainerColor = pill.selectedContainer,
                checkedContentColor = pill.onSelected,
            ),
            fillWidth = false,
        )
    }
}

/**
 * The pool under its anchor. Origin: the middle of the coping's outer edge on the viewer's
 * side, the pool running away along -Z ([PoolGeometry]).
 *
 * Only the coping and the water take touches: the liner and the hull reach 1.5 m under the
 * lawn, and their bounds would otherwise catch a drag that starts on the grass in front.
 *
 * Liner, coping and water all fade in, so all three draw in Filament's blended pass, after
 * every opaque surface — the camera image and the hull among them. In that pass the veil goes
 * first ([VEIL_PRIORITY]), the liner and the coping next (default priority 4), the water last
 * ([WATER_PRIORITY]), whatever their distance to the camera.
 */
@Composable
private fun NodeScope.PoolNodes(
    spec: PoolSpec,
    materials: PoolMaterials,
    night: State<Float>,
    opacity: Float,
    scale: Float,
) {
    SideEffect { materials.fade(opacity) }
    NightVeil(materials, night)
    Node(scale = Scale(scale)) {
        // The pool moves and turns as one: only the placement pivot takes the gestures.
        val fixedChild: MeshNodeImpl.() -> Unit = {
            isEditable = true
            isPositionEditable = false
            isRotationEditable = false
            isScaleEditable = false
        }
        PoolMeshNode(remember(spec) { PoolGeometry.hull(spec) }, materials.hull) {
            setPriority(ARCameraStream.OCCLUDER_PRIORITY)
            isShadowCaster = true
            isShadowReceiver = false
            isTouchable = false
            isHittable = false
        }
        PoolMeshNode(remember(spec) { PoolGeometry.liner(spec) }, materials.liner) {
            isShadowCaster = false
            isShadowReceiver = true
            isTouchable = false
            isHittable = false
        }
        PoolMeshNode(remember(spec) { PoolGeometry.coping(spec) }, materials.coping) {
            fixedChild()
            isShadowCaster = true
            isShadowReceiver = true
        }
        PoolMeshNode(remember(spec) { PoolGeometry.water(spec) }, materials.water) {
            fixedChild()
            setPriority(WATER_PRIORITY)
            isShadowCaster = false
            isShadowReceiver = false
        }
    }
}

/**
 * Night over the real garden: one triangle that covers the screen, in device coordinates
 * ([PoolGeometry.screen]), drawn in transparent black before the pool. In AR it darkens the
 * camera image, in the QA preview the photo; the pool, drawn after it, keeps its own light.
 * Composed only once night starts to fall, so daytime pays no full-screen pass.
 */
@Composable
private fun SceneScope.NightVeil(materials: PoolMaterials, night: State<Float>) {
    val falling by remember(night) { derivedStateOf { night.value > 0f } }
    if (!falling) return
    PoolMeshNode(remember { PoolGeometry.screen() }, materials.veil) {
        setPriority(VEIL_PRIORITY)
        // Device coordinates ignore the node and the camera: there is nothing to cull.
        setCulling(false)
        isShadowCaster = false
        isShadowReceiver = false
        isTouchable = false
        isHittable = false
    }
}

/**
 * Pushes the choices into the materials. The night fade is read here, during composition, so
 * each of its frames recomposes this alone and lands in the shaders; read only inside the
 * `SideEffect`, it would be written once at its first value and never again.
 */
@Composable
private fun PoolMaterialSync(
    materials: PoolMaterials,
    spec: PoolSpec,
    liner: PoolLiner,
    night: State<Float>,
    veilAtNight: Float,
) {
    val level = night.value
    SideEffect { materials.update(spec, liner, level, veilAtNight) }
}

/** Uploads one [PoolMesh] and frees its buffers when the size changes or the demo leaves. */
@Composable
private fun SceneScope.PoolMeshNode(mesh: PoolMesh, material: MaterialInstance, apply: MeshNodeImpl.() -> Unit) {
    val geometry = remember(engine, mesh) {
        Geometry.Builder(PrimitiveType.TRIANGLES).vertices(mesh.vertices).indices(mesh.indices).build(engine)
    }
    DisposableEffect(geometry) { onDispose { engine.safeDestroyGeometry(geometry) } }
    MeshNode(
        primitiveType = PrimitiveType.TRIANGLES,
        vertexBuffer = geometry.vertexBuffer,
        indexBuffer = geometry.indexBuffer,
        boundingBox = geometry.boundingBox,
        materialInstance = material,
        apply = apply,
    )
}

/**
 * The pool's five materials; the loader destroys them with the session. Main thread only, like
 * every Filament call.
 */
private class PoolMaterials(
    val liner: MaterialInstance,
    val water: MaterialInstance,
    val coping: MaterialInstance,
    val hull: MaterialInstance,
    val veil: MaterialInstance,
) {
    private var night = 0f
    private var opacity = 0f
    private var veilAtNight = 0f

    /**
     * Every parameter, every time: a uniform never written is not guaranteed to read zero.
     *
     * @param night       0 by day, 1 at night; in between while it falls.
     * @param veilAtNight How much of the garden the veil hides once night has fallen.
     */
    fun update(spec: PoolSpec, finish: PoolLiner, night: Float, veilAtNight: Float) {
        this.night = night
        this.veilAtNight = veilAtNight
        // The lamps come on as the light goes.
        val lights = night
        val daylight = 1f - (1f - NIGHT_LIGHT) * night
        liner.setParameter("linerColor", finish.red, finish.green, finish.blue)
        liner.setParameter("waterY", -PoolGeometry.WATER_DROP)
        liner.setParameter("halfWidth", spec.halfWidth)
        liner.setParameter("zNear", spec.zNear)
        liner.setParameter("zFar", spec.zFar)
        liner.setParameter("deepDepth", spec.deepDepth)
        liner.setParameter("shallowDepth", spec.shallowDepth)
        liner.setParameter("lights", lights)
        liner.setParameter("daylight", daylight)
        coping.setParameter("halfWidth", spec.halfWidth)
        coping.setParameter("zNear", spec.zNear)
        coping.setParameter("zFar", spec.zFar)
        coping.setParameter("stone", STONE_RED, STONE_GREEN, STONE_BLUE)
        coping.setParameter("lights", lights)
        coping.setParameter("daylight", daylight)
        water.setParameter("lights", lights)
        water.setParameter("daylight", daylight)
        water.setParameter("tint", WATER_RED, WATER_GREEN, WATER_BLUE)
        water.setParameter("halfWidth", spec.halfWidth)
        water.setParameter("zNear", spec.zNear)
        water.setParameter("zFar", spec.zFar)
        applyVeil()
    }

    /** The placement fade-in: 0 when the pool appears, 1 once it has landed. */
    fun fade(opacity: Float) {
        this.opacity = opacity
        liner.setParameter("opacity", opacity)
        coping.setParameter("opacity", opacity)
        water.setParameter("opacity", opacity)
        applyVeil()
    }

    /** The garden darkens with the night, and with the pool as it appears. */
    private fun applyVeil() = veil.setParameter("strength", veilAtNight * night * opacity)
}

/** Main thread only: Filament material creation is a JNI call. */
@Composable
private fun rememberPoolMaterials(materialLoader: MaterialLoader): PoolMaterials = remember(materialLoader) {
    fun custom(name: String) = materialLoader.createInstance(materialLoader.createMaterial("materials/$name.filamat"))
    PoolMaterials(
        liner = custom("pool_liner"),
        water = custom("pool_water"),
        coping = custom("pool_coping"),
        hull = materialLoader.createOcclusionInstance(),
        veil = custom("pool_night_veil"),
    ).also { it.fade(0f) }
}

/** QA miniature: an 8 × 4 m pool becomes 1.3 × 0.7 m, small enough for an indoor recording. */
private const val MINIATURE_SCALE = 1f / 6f

/**
 * QA preview viewpoint: eyes 1.5 m up, 1.6 m back from the coping and a little to the right,
 * looking down the pool's length so the Roman steps in the far left corner face the lens and
 * both lamps, three quarters of the way down the side walls, stay in frame.
 */
private val PREVIEW_EYE = Position(x = 0.2f, y = 1.5f, z = 1.6f)
private val PREVIEW_TARGET = Position(x = 0f, y = -1.5f, z = -4.4f)

/** About 67° of vertical view: a phone's main camera held upright. */
private const val PREVIEW_FOCAL_LENGTH_MM = 18.0

/** Lands on the QA photo of a floor seen from above, the nearest thing to ground under a phone. */
private const val PREVIEW_BACKDROP_SEED = "ar-pool-floor"

/** Accessible nudges: a pool moves in 10 cm steps and turns in 5° steps. */
private const val NUDGE_METRES = 0.10f
private const val NUDGE_DEGREES = 5f

/** Light limestone coping, linear RGB. */
private const val STONE_RED = 0.70f
private const val STONE_GREEN = 0.66f
private const val STONE_BLUE = 0.58f

/** The water's own faint blue-green, linear RGB. */
private const val WATER_RED = 0.55f
private const val WATER_GREEN = 0.80f
private const val WATER_BLUE = 0.85f

/**
 * Share of the scene's light the garden and the pool keep at night. The pool's surfaces take
 * it as the materials' `daylight`, the camera image through the veil ([NIGHT_VEIL_AR]), both in
 * the scene's linear light, so stone and lawn darken alike; the lamps, being emission, keep all
 * of theirs.
 */
private const val NIGHT_LIGHT = 0.06f

/**
 * How much of the camera image the veil hides at night, in AR: all but [NIGHT_LIGHT]. The veil
 * blends in the scene's linear light, before tone mapping, where the camera image has been
 * un-tone-mapped (`camera_stream_flat.mat`). Through that curve 94 % turns grass at sRGB 0.45
 * into 0.03 and a pale wall at 0.9 into 0.25, while a clipped sky or window keeps most of its
 * light, as it does at dusk.
 */
private const val NIGHT_VEIL_AR = 1f - NIGHT_LIGHT

/**
 * How much of the backdrop photo the veil hides at night, in the QA preview. There the
 * translucent scene is laid over the photo after tone mapping, in display colours, where hiding
 * 85 % of a mid-tone leaves what the Filmic curve makes of [NIGHT_LIGHT] of its light.
 */
private const val NIGHT_VEIL_PREVIEW = 0.85f

/** Draw priority of the night veil: first in the blended pass, before the liner and the coping. */
private const val VEIL_PRIORITY = 2

/** Draw priority of the water: last in the blended pass, over the liner it lets through. */
private const val WATER_PRIORITY = 5
