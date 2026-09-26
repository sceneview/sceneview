package io.github.sceneview.demo.common.placement

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.google.android.filament.Engine
import com.google.ar.core.*
import io.github.sceneview.ar.*
import io.github.sceneview.ar.arcore.configure
import io.github.sceneview.demo.*
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoStatusCard
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.demos.internal.DepthOcclusionCopy
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.haptic.rememberHapticFeedback
import io.github.sceneview.ar.ARHapticFeedback
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.model
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** The comparison owns rendering controls; the SDK owns the single, grounded subject. */
@Composable
internal fun ARFeatureComparison(feature: PlacementFeature, onBack: () -> Unit) {
    var restart by remember { mutableIntStateOf(0) }
    key(restart) { FeatureComparisonSession(feature, onBack, onRestart = { restart++ }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeatureComparisonSession(feature: PlacementFeature, onBack: () -> Unit, onRestart: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val stream = rememberARCameraStream(materialLoader)
    val state = rememberAutoPlacementState()
    val guidance = rememberArGuidanceState(state)
    val control = remember { FeatureComparisonControl(feature != PlacementFeature.STABILIZATION) }
    val haptic = rememberHapticFeedback()
    val playback = rememberArPlaybackDataset()
    var session by remember { mutableStateOf<Session?>(null) }
    var availability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    var cameraReady by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    var startupTimedOut by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf<ModelInstance?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var show3D by remember { mutableStateOf(false) }
    var effectFailed by remember { mutableStateOf(false) }
    var invalidMove by remember { mutableStateOf(false) }
    var hintShown by remember { mutableStateOf(false) }
    var showHint by remember { mutableStateOf(false) }
    var hadPlacement by remember { mutableStateOf(false) }
    var trackingFailure by remember { mutableStateOf<TrackingFailureReason?>(null) }

    LaunchedEffect(retry) {
        val ticket = state.selectModel()
        loadFailed = false
        val loaded = try { modelLoader.loadModelInstance(DemoMath.HELMET_ASSET) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (!state.acceptsAsset(ticket)) {
            loaded?.let { modelLoader.destroyModel(it.model) }
        } else {
            model = loaded
            loadFailed = loaded == null
        }
    }
    DisposableEffect(model) { val owned = model; onDispose { owned?.let { modelLoader.destroyModel(it.model) } } }
    // Placement, selection, snap, limits, invalid moves and tracking: the SDK's opt-in haptics.
    ARHapticFeedback(state, haptic)
    LaunchedEffect(state.hasPlacement) {
        if (state.hasPlacement && !hadPlacement && !hintShown) { hintShown = true; showHint = true }
        hadPlacement = state.hasPlacement
    }
    LaunchedEffect(showHint) { if (showHint) { delay(5_000); showHint = false } }
    LaunchedEffect(state.phase) {
        if (state.phase == PlacementPhase.ADJUSTING) showHint = false
        if (state.phase != PlacementPhase.PLACED && state.phase != PlacementPhase.ADJUSTING) invalidMove = false
    }
    LaunchedEffect(cameraReady, cameraFailed, availability, control.supported) {
        val awaitingFirstFrame = !cameraReady && !cameraFailed
        if (awaitingFirstFrame && control.supported != false && availability == null) {
            delay(AR_CAMERA_INIT_SCRIM_TIMEOUT_MS)
            startupTimedOut = true
            cameraFailed = true
            state.cameraFailed()
        }
    }
    // Depth/semantics acquisition remains configured. These setters safely swap the
    // existing Filament material, including while a depth upload is in flight.
    SideEffect {
        val rendering = control.supported == true && control.enabled
        stream?.isDepthOcclusionEnabled = rendering && feature == PlacementFeature.DEPTH
        stream?.isPersonOcclusionEnabled = rendering && feature == PlacementFeature.PEOPLE
    }
    fun reset() { invalidMove = false; state.resetPlacement(SystemClock.uptimeMillis()) }
    fun toggle() {
        effectFailed = !control.toggle { enabled ->
            if (feature != PlacementFeature.STABILIZATION) true else {
                val active = session
                active != null && runCatching {
                    active.configure { config ->
                        config.imageStabilizationMode = if (enabled) {
                            Config.ImageStabilizationMode.EIS
                        } else {
                            Config.ImageStabilizationMode.OFF
                        }
                    }
                    (active.config.imageStabilizationMode == Config.ImageStabilizationMode.EIS) == enabled
                }.getOrDefault(false)
            }
        }
        if (effectFailed) haptic.error()
    }
    val supported = control.supported == true
    val card = when (state.phase) {
        PlacementPhase.NO_SURFACE -> PlacementCard.NO_SURFACE
        PlacementPhase.RECOVERY_FAILED -> PlacementCard.RECOVERY_FAILED
        else -> null
    }
    DemoScaffold(
        title = stringResource(feature.title), onBack = onBack,
        chromeToggleOnTap = false,
        onReset = ::reset,
        controls = {
            Text(stringResource(feature.explanation))
            Text(stringResource(R.string.ar_scale_preview_size, (state.scaleFactor * 100).toInt()))
            TextButton(onClick = ::reset, enabled = state.hasPlacement) { Text(stringResource(R.string.wall_reset)) }
            if (state.hasPlacement && state.isSelected) {
                val editable = state.phase == PlacementPhase.PLACED
                fun move(x: Float, y: Float) { state.moveBy(x, y) }
                FeatureAdjustment(
                    R.string.wall_dpad_left, R.string.wall_dpad_right, editable,
                    { move(-0.02f, 0f) }, { move(0.02f, 0f) },
                )
                FeatureAdjustment(
                    R.string.ar_adjust_move_closer, R.string.ar_adjust_move_farther, editable,
                    { move(0f, 0.02f) }, { move(0f, -0.02f) },
                )
                FeatureAdjustment(
                    R.string.wall_dpad_rotate_left, R.string.wall_dpad_rotate_right, editable,
                    { state.rotateBy(-2f) }, { state.rotateBy(2f) },
                )
                FeatureAdjustment(
                    R.string.wall_scale_down, R.string.wall_scale_up, editable,
                    { state.scaleTo(state.scaleFactor - 0.1f) },
                    { state.scaleTo(state.scaleFactor + 0.1f) },
                )
            }
        },
        topOverlay = {
            if (supported && feature == PlacementFeature.DEPTH) {
                DemoStatusCard(DepthOcclusionCopy.stateTitle(control.enabled) + ". " +
                    DepthOcclusionCopy.stateConsequence(control.enabled))
            }
        },
        bottomOverlay = {
            if (supported && !cameraFailed) {
                val message = when {
                    loadFailed -> stringResource(R.string.ar_place_model_failed)
                    model == null -> stringResource(R.string.ar_place_loading_model)
                    effectFailed -> stringResource(R.string.ar_comparison_failed)
                    card != null -> null
                    // The animated coaching speaks; the pill only adds what it cannot say.
                    guidance.isCoaching &&
                        !(state.phase == PlacementPhase.TRACKING_LOST &&
                            trackingFailure == TrackingFailureReason.INSUFFICIENT_LIGHT) -> null
                    invalidMove -> stringResource(R.string.ar_place_keep_on_surface)
                    state.phase == PlacementPhase.SCANNING -> stringResource(R.string.ar_place_move_slowly)
                    state.phase == PlacementPhase.TRACKING_LOST -> stringResource(R.string.ar_place_tracking_paused) +
                        if (trackingFailure == TrackingFailureReason.INSUFFICIENT_LIGHT) {
                            " " + stringResource(R.string.ar_place_try_brighter_area)
                        } else {
                            ""
                        }
                    state.phase == PlacementPhase.RECOVERING -> stringResource(R.string.ar_place_finding_placement)
                    state.phase == PlacementPhase.ADJUSTING ->
                        stringResource(R.string.ar_scale_preview_size, (state.scaleFactor * 100).toInt())
                    showHint -> stringResource(R.string.ar_place_gesture_hint)
                    else -> null
                }
                DemoStatusBanner(message, tone = DemoStatusTone.Guidance)
                if (loadFailed) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.ar_place_try_again)) }
                PlacementActionCard(
                    card,
                    { show3D = true },
                    { state.keepScanning(SystemClock.uptimeMillis()) },
                    ::reset,
                    onRestart,
                )
                val label = if (feature == PlacementFeature.STABILIZATION) {
                    if (control.enabled) R.string.ar_eis_turn_off else R.string.ar_eis_turn_on
                } else if (control.enabled) R.string.ar_occlusion_turn_off else R.string.ar_occlusion_turn_on
                Button(
                    onClick = ::toggle,
                    modifier = Modifier.testTag("ar-comparison-toggle")
                        .heightIn(min = SceneViewTokens.Layout.touchTarget),
                    shape = RoundedCornerShape(SceneViewTokens.Radius.md),
                    colors = ButtonDefaults.buttonColors(containerColor = SceneViewColors.Primary,
                        contentColor = SceneViewTokens.ArOverlay.onScrim),
                ) { Text(stringResource(label)) }
            }
        },
    ) {
        if (control.supported == false) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(SceneViewTokens.Space.lg), verticalArrangement = Arrangement.Center) {
                    Text(
                        stringResource(R.string.ar_comparison_unavailable),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(stringResource(feature.requirement), Modifier.padding(vertical = SceneViewTokens.Space.sm))
                    Button(onClick = { show3D = true }) { Text(stringResource(R.string.ar_place_view_in_3d)) }
                    TextButton(onClick = onBack) { Text(stringResource(R.string.samples_back)) }
                }
            }
        } else {
            FeaturePlacementScene(
                assetReady = model != null && supported,
                state = state, engine = engine, modelLoader = modelLoader, materialLoader = materialLoader,
                modifier = Modifier.fillMaxSize(), cameraStream = stream, playbackDataset = playback,
                sessionConfiguration = { active, config ->
                    config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                    if (feature.isSupported(active)) {
                        when (feature) {
                            PlacementFeature.DEPTH -> config.depthMode = Config.DepthMode.AUTOMATIC
                            PlacementFeature.PEOPLE -> {
                                config.depthMode = Config.DepthMode.AUTOMATIC
                                config.semanticMode = Config.SemanticMode.ENABLED
                            }
                            PlacementFeature.STABILIZATION ->
                                config.imageStabilizationMode = Config.ImageStabilizationMode.OFF
                        }
                    }
                },
                onSessionCreated = { active ->
                    session = active
                    cameraFailed = false
                    startupTimedOut = false
                    if (state.phase == PlacementPhase.CAMERA_ERROR) reset()
                    control.confirmSupport(feature.isSupported(active) && when (feature) {
                        PlacementFeature.DEPTH -> active.config.depthMode == Config.DepthMode.AUTOMATIC
                        PlacementFeature.PEOPLE ->
                            active.config.semanticMode == Config.SemanticMode.ENABLED &&
                                active.config.depthMode == Config.DepthMode.AUTOMATIC
                        PlacementFeature.STABILIZATION -> true
                    })
                },
                onSessionUpdated = { _, _ -> cameraReady = true },
                onSessionFailed = { cameraFailed = true },
                onARCoreAvailability = { availability = it },
                onTrackingFailureChanged = { trackingFailure = it },
            ) { placement ->
                placement?.let { result -> model?.let { instance ->
                    AutoPlacementModel(result, state, instance,
                        assetRotation = DemoMath.placementRotationFor(DemoMath.HELMET_ASSET),
                        onInvalidMove = { invalidMove = it })
                } }
            }
            ARCoachingOverlay(guidance)
            ARCameraInitScrim(!cameraReady && !cameraFailed, availability)
            if (startupTimedOut && availability == null) {
                PlacementActionCard(PlacementCard.CAMERA_ERROR, null, {}, ::reset, onRestart)
            }
        }
    }
    if (show3D) {
        HelmetPreviewSheet(engine, modelLoader, materialLoader, onDismiss = { show3D = false })
    }
}

/** Side of the cube the preview helmet is fitted into, in metres: the "Preview size" it states. */
private const val HELMET_PREVIEW_SIZE_METRES = 0.3f

/** "View in 3D" for the feature comparisons: the helmet in the shared studio preview (#3884). */
@Composable
internal fun HelmetPreviewSheet(
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    onDismiss: () -> Unit,
) {
    // Separate instance: one Filament entity must never belong to two scenes.
    val preview = rememberModelInstance(modelLoader, DemoMath.HELMET_ASSET)
    PlacementPreviewSheet(
        title = stringResource(R.string.ar_place_preview_size),
        subjectExtent = remember(preview) { preview?.extentScaledTo(HELMET_PREVIEW_SIZE_METRES) },
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        onDismiss = onDismiss,
    ) {
        preview?.let {
            ModelNode(
                it,
                scaleToUnits = HELMET_PREVIEW_SIZE_METRES,
                rotation = DemoMath.placementRotationFor(DemoMath.HELMET_ASSET),
            )
        }
    }
}

@Composable
private fun FeatureAdjustment(
    decreaseLabel: Int,
    increaseLabel: Int,
    enabled: Boolean,
    decrease: () -> Unit,
    increase: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs)) {
        OutlinedButton(onClick = decrease, enabled = enabled) { Text(stringResource(decreaseLabel)) }
        OutlinedButton(onClick = increase, enabled = enabled) { Text(stringResource(increaseLabel)) }
    }
}
