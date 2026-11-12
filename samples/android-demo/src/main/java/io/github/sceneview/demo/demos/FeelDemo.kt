package io.github.sceneview.demo.demos

import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.ViewTreeObserver
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import com.google.android.filament.Camera
import com.google.android.filament.RenderableManager
import com.google.android.filament.View
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.LocalDemoSceneCover
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.updateShadowOptions
import io.github.sceneview.demo.demos.internal.FeelHapticPlayer
import io.github.sceneview.demo.demos.internal.MatterMesh
import io.github.sceneview.demo.demos.internal.MatterSurfaceMaterials
import io.github.sceneview.demo.demos.internal.feel.FeelMaterial
import io.github.sceneview.demo.demos.internal.feel.FeelSimulation
import io.github.sceneview.demo.demos.internal.feel.MatterCadence
import io.github.sceneview.demo.demos.internal.feel.MatterDetailCache
import io.github.sceneview.demo.demos.internal.feel.MatterDetailAtlas
import io.github.sceneview.demo.demos.internal.feel.MatterFrameTimes
import io.github.sceneview.demo.demos.internal.feel.MatterIdle
import io.github.sceneview.demo.demos.internal.feel.MatterMeshes
import io.github.sceneview.demo.demos.internal.feel.MatterIdentity
import io.github.sceneview.demo.demos.internal.feel.MatterOpening
import io.github.sceneview.demo.demos.internal.feel.MatterStage
import io.github.sceneview.demo.demos.internal.feel.MatterTransition
import io.github.sceneview.demo.demos.internal.feel.MatterSurfaces
import io.github.sceneview.demo.recording.rememberDemoSceneRecorder
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.viewer.ShowroomMath
import io.github.sceneview.demo.ui.viewer.ShowroomToneMapping
import io.github.sceneview.demo.ui.viewer.ViewerBackdrop
import io.github.sceneview.environment.Environment
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.math.colorOf
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberView
import io.github.sceneview.utils.screenToRay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Matter: six physical surfaces, one hero, a shared static mesh and uniform-only deformation. */
@OptIn(ExperimentalSceneViewApi::class)
@Composable
fun FeelDemo(onBack: () -> Unit) {
    val title = stringResource(R.string.demo_feel_title)
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = title, onBack = onBack)
        return
    }
    val context = LocalContext.current
    // These packages are required for the stage/opening. Read bytes off-main, then
    // construct Filament objects on-main; no other family belongs on this startup path.
    val openingPayloads by produceState<List<ByteBuffer>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            listOf(MatterSurfaceMaterials.asset(FeelMaterial.MemoryFoam), "materials/opaque_unlit_colored.filamat", "materials/matter_floor.filamat").map { path ->
                val bytes = context.assets.open(path).use { it.readBytes() }
                ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { flip() }
            }
        }
    }
    val initialPayloads = openingPayloads ?: return
    val engine = rememberEngine()
    val loader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val view = rememberView(engine)
    val renderer = rememberRenderer(engine)
    val invalidator = rememberRenderInvalidator()
    var cadence by remember { mutableStateOf(MatterCadence.Active) }
    var orbitWakeAt by remember { mutableStateOf<Long?>(null) }
    val frameTimes = remember { MatterFrameTimes() }
    val wakeRender by rememberUpdatedState<() -> Unit>({
        if (cadence == MatterCadence.Parked) frameTimes.reset()
        cadence = MatterCadence.Active
        orbitWakeAt = null
        invalidator.requestRender()
    })
    LaunchedEffect(renderer, cadence) {
        frameTimes.reset()
        // Active contact and orbit both target the display's 60 Hz cadence.
        renderer.frameRateOptions = renderer.frameRateOptions.apply {
            interval = 1
        }
    }
    val camera = rememberCameraNode(engine)
    // A single studio for all samples and both app themes. The IBL only fills reflections.
    val skybox = remember(engine) { Skybox.Builder().color(.12f, .12f, .12f, 1f).build(engine) }
    DisposableEffect(skybox) { onDispose { engine.destroySkybox(skybox) } }
    val hdr = rememberHDREnvironment(environmentLoader, ShowroomMath.DARK_HDR, createSkybox = false)
    val environment = remember(hdr, skybox) { (hdr ?: Environment()).copy(skybox = skybox) }
    val light = rememberMainLightNode(engine) {
        lightDirection = Direction(.16f, -1f, -.10f)
        color = colorOf(1f, .88f, .72f)
        intensity = 55_000f
        isShadowCaster = true
    }
    LaunchedEffect(engine, light) {
        light.updateShadowOptions {
            mapSize = 512
            shadowCascades = 1
            shadowFar = 20f
            normalBias = 0.008f
            shadowBulbRadius = 0.22f
        }
    }
    val recorder = rememberDemoSceneRecorder()
    val firstFrame = rememberFirstFrameState(engine)
    val touchView = LocalView.current
    var systemMotion by remember(touchView) {
        mutableStateOf(Settings.Global.getFloat(touchView.context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f)
    }
    val motion = MatterOpening.isAllowed(systemMotion, DemoSettings.qaMode) && LocalMotionEnabled.current
    LaunchedEffect(orbitWakeAt, motion) {
        orbitWakeAt?.takeIf { motion }?.let { due ->
            delay((due - SystemClock.uptimeMillis()).coerceAtLeast(1L))
            if (touchView.hasWindowFocus()) {
                cadence = MatterCadence.Orbit
                invalidator.requestRender()
            }
        }
    }
    DisposableEffect(touchView) {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { wakeRender() }
        touchView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose {
            if (touchView.viewTreeObserver.isAlive) touchView.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
        }
    }
    // Wait for the actual lit scene before starting; reduced motion/QA dismiss it permanently.
    val simulation = remember { FeelSimulation(openingEnabled = true) }
    val stage = remember { MatterStage() }
    val idle = remember { MatterIdle() }
    val haptics = remember(touchView) { FeelHapticPlayer(touchView) }
    val touch = remember(simulation, view, haptics, stage, touchView) {
        FeelTouch(simulation, view, haptics, stage, idle, ViewConfiguration.get(touchView.context))
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, touch) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                wakeRender()
                systemMotion = Settings.Global.getFloat(touchView.context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
            }
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                touch.cancel()
                frameTimes.reset()
                orbitWakeAt = null
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); touch.cancel() }
    }
    val mesh = remember(engine) { MatterMesh(engine, MatterMeshes.slab()) }
    val rigidMesh = remember(engine) { MatterMesh(engine, MatterMeshes.slab(rigid = true)) }
    val materials = remember(engine, loader, initialPayloads) {
        MatterSurfaceMaterials(engine, loader, initialPayloads[0], initialPayloads[1], initialPayloads[2])
    }
    DisposableEffect(mesh, rigidMesh, materials) {
        onDispose { mesh.destroy(); rigidMesh.destroy(); materials.destroy() }
    }
    val pads = remember { arrayOfNulls<Node>(FeelMaterial.entries.size) }
    val mark = remember { arrayOfNulls<Node>(1) }
    var selected by remember { mutableStateOf(FeelMaterial.MemoryFoam) }
    var displayed by remember { mutableStateOf(selected) }
    var outgoing by remember { mutableStateOf<FeelMaterial?>(null) }
    val slide = remember { Animatable(1f) }
    var transitionStarted by remember { mutableStateOf<Long?>(null) }
    val switching = selected != displayed || outgoing != null
    val selectedNow by rememberUpdatedState(selected)
    // One worker owns every atlas: rapid selections never duplicate a bake or cancel a cache write.
    LaunchedEffect(materials, touchView) {
        snapshotFlow { firstFrame.rendered.value }.first { it }
        val cache = withContext(Dispatchers.IO) { MatterDetailCache(touchView.context.cacheDir) }
        val remaining = FeelMaterial.entries.filter { it != FeelMaterial.Gel }.toMutableList()
        while (remaining.isNotEmpty()) {
            val kind = selectedNow.takeIf { it in remaining } ?: remaining.first()
            val cached = withContext(Dispatchers.IO) { cache.read(kind) }
            val pixels = cached ?: withContext(Dispatchers.Default) {
                MatterDetailAtlas.bake(kind) { ensureActive() }
            }
            // Leave a frame between uploads even when every disk read is a hit.
            withFrameNanos { }
            materials.uploadDetail(kind, pixels)
            wakeRender()
            if (cached == null) withContext(Dispatchers.IO) { cache.write(kind, pixels) }
            remaining.remove(kind)
        }
    }
    val warmPayloads = remember(materials) { mutableStateMapOf<FeelMaterial, ByteBuffer>() }
    LaunchedEffect(materials) {
        snapshotFlow { firstFrame.sceneReady.value }.first { it }
        for (kind in FeelMaterial.entries.filter { it != FeelMaterial.MemoryFoam }) {
            val payload = withContext(Dispatchers.IO) {
                val bytes = touchView.context.assets.open(MatterSurfaceMaterials.asset(kind)).use { it.readBytes() }
                ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { flip() }
            }
            warmPayloads[kind] = payload
            wakeRender()
        }
    }
    val targetReady = selected in materials.samples
    LaunchedEffect(selected, targetReady, motion, materials) {
        if (!targetReady) return@LaunchedEffect
        val target = selected
        wakeRender()
        val previous = displayed
        if (!motion || previous == target) {
            displayed = target; outgoing = null; slide.snapTo(1f)
            transitionStarted = null
        } else {
            transitionStarted = SystemClock.uptimeMillis()
            idle.touch(transitionStarted!!, down = false)
            slide.snapTo(0f)
            outgoing = previous
            displayed = target
            slide.animateTo(1f, tween(SceneViewTokens.Motion.containerMillis, easing = LinearEasing))
            outgoing = null
        }
    }
    val choose: (FeelMaterial) -> Unit = { next ->
        if (next != selected && outgoing == null) {
            touch.cancel()
            simulation.select(next, SystemClock.uptimeMillis())
            selected = next
            wakeRender()
        }
    }
    val colors = MaterialTheme.colorScheme
    val ringColour = ViewerBackdrop.linearBeforeNeutral(colors.primary)
    val hairlinePx = with(LocalDensity.current) { SceneViewTokens.Layout.hairlineWidth.toPx() }
    val floorY = -FeelSimulation.PAD_THICKNESS
    SideEffect {
        MatterIdentity.colours(dark = colors.surface.luminance() < .5f).forEachIndexed { face, rgb ->
            val linear = MatterIdentity.linearBeforeToneMap(rgb)
            materials.mark[face].setParameter("color", linear[0], linear[1], linear[2], 1f)
        }
        hdr?.indirectLight?.apply {
            intensity = 4_000f
            setRotation(MatterStage.STUDIO_ROTATION)
        }
        materials.samples.forEach { (kind, instance) ->
            if (kind != FeelMaterial.Gel) instance.setParameter("rippleColor", ringColour[0], ringColour[1], ringColour[2])
        }
    }
    ShowroomToneMapping(engine, view, neutral = true, requestRender = invalidator::requestRender)
    var readout by remember { mutableStateOf(FeelReadout()) }
    val timing = remember { longArrayOf(0L) }
    // Changing state is read by the FIRST SceneView callbacks through these updated delegates.
    val onTouchNow by rememberUpdatedState<(MotionEvent) -> Boolean>({ event ->
        wakeRender()
        touch.onEvent(event, displayed, switching, choose)
    })
    val onFrameNow by rememberUpdatedState<(Long) -> Unit>({ nanos ->
        val now = SystemClock.uptimeMillis()
        val detailFading = materials.advanceDetail(now)
        if (BuildConfig.DEBUG) frameTimes.frame(nanos)?.let { window ->
            Log.d("MatterPerf", String.format(Locale.US,
                "material=%s mode=%s avg=%.2fms fps=%.1f intervals=%d",
                displayed.name, cadence.name, window.averageMillis, window.fps, window.intervals))
        }
        val focused = touchView.hasWindowFocus()
        val cameraMoved = idle.advance(now, motion && focused && simulation.opening.finished && !switching)
        if (cameraMoved) stage.orbit(idle.yawDegrees)
        val reframed = stage.frame()
        if (reframed) camera.setProjection(MatterStage.HORIZONTAL_FOV,
            stage.widthPx.toDouble() / stage.heightPx.coerceAtLeast(1),
            MatterStage.NEAR, MatterStage.FAR, Camera.Fov.HORIZONTAL)
        if (cameraMoved || reframed) {
            camera.position = Position(stage.eyeX, stage.eyeY, stage.eyeZ)
            camera.lookAt(Position(y = stage.targetY))
        }
        simulation.opening.travel = stage.openingTravel
        if (!motion && !simulation.opening.finished) simulation.opening.cancel()
        // Initial composition can precede window focus. Do not dismiss an opening not yet shown.
        if (!focused && (simulation.active != null || simulation.opening.visible)) touch.cancel()
        haptics.onFrame()
        touch.followIndentation()
        simulation.advance(now, startOpening = firstFrame.sceneReady.value && focused && motion)
            ?.let(haptics::play)
        // Only the opening mark needs a mapped shadow. Re-rendering the dense, deformed
        // slab into a shadow atlas doubled its vertex work during every press and orbit.
        val needsMappedShadow = simulation.opening.visible
        if (light.isShadowCaster != needsMappedShadow) light.isShadowCaster = needsMappedShadow
        val direction = if (displayed.ordinal >= (outgoing?.ordinal ?: displayed.ordinal)) 1f else -1f
        val progress = if (motion) slide.value else 1f
        FeelMaterial.entries.forEach { kind ->
            val visible = (kind == displayed && (outgoing == null || progress >= .40f)) ||
                (kind == outgoing && progress < .40f)
            pads[kind.ordinal]?.apply {
                isVisible = visible
                if (visible) {
                    val offset = when (kind) {
                        outgoing -> -MatterTransition.outgoingTravel(progress) * direction * stage.slideDistance
                        else -> MatterTransition.incomingTravel(progress) * direction * .28f
                    }
                    val height = MatterTransition.height(kind, progress, kind == outgoing)
                    position = Position(offset * MatterStage.COS_AZIMUTH, height, -offset * MatterStage.SIN_AZIMUTH)
                    materials.update(kind, simulation, now, motion,
                        hairlinePx * stage.halfWidth * 2f / stage.widthPx.coerceAtLeast(1),
                        landingAge = if (kind == displayed) transitionStarted?.let {
                            (now - it) / 1000f - SceneViewTokens.Motion.containerMillis * .82f / 1000f
                        } ?: -1f else -1f)
                }
            }
        }
        if (motion && displayed.rigid && transitionStarted != null &&
            now - transitionStarted!! >= SceneViewTokens.Motion.containerMillis * .82f &&
            simulation.contactMillis[displayed.ordinal] < transitionStarted!!
        ) {
            simulation.contactX[displayed.ordinal] = 0f
            simulation.contactZ[displayed.ordinal] = 0f
            simulation.contactMillis[displayed.ordinal] = now
        }
        mark[0]?.apply {
            isVisible = simulation.opening.visible && displayed == FeelMaterial.MemoryFoam
            if (isVisible) position = Position(y = simulation.opening.heightAbovePad -
                MatterSurfaces.visualDepth(simulation.pads[0].depthMm, FeelMaterial.MemoryFoam) - simulation.opening.squash)
        }
        if (now - timing[0] >= 50L) {
            timing[0] = now
            val indentation = simulation.pads[displayed.ordinal]
            val peakMm = simulation.captionDepthMm(displayed)
            readout = FeelReadout(displayed, peakMm,
                MatterSurfaces.showsDisplacement(displayed, indentation, simulation.active == displayed, peakMm))
        }
        // All Filament work stays in this main-thread callback. Never warm during contact,
        // the opening, recovery or a slide. Selected families get priority, without hiding the stage.
        if (focused && firstFrame.sceneReady.value && simulation.opening.finished &&
            simulation.active == null && !idle.isTouching && outgoing == null &&
            !simulation.needsFrames(displayed, now, motion)) {
            val candidate = selected.takeIf { !materials.hasPrepared(it) && it in warmPayloads }
                ?: FeelMaterial.entries.firstOrNull { !materials.hasPrepared(it) && it in warmPayloads }
            candidate?.let { kind ->
                if (materials.warmOne(kind, warmPayloads.getValue(kind)) { wakeRender() }) {
                    warmPayloads.remove(kind)
                }
            }
        }
        firstFrame.onFrame(nanos)
        val orbitAt = idle.wakeAt
        val settling = motion && transitionStarted?.let { now - it in 0L..1100L } == true
        val next = when {
            !focused -> MatterCadence.Parked
            !firstFrame.sceneReady.value || switching || settling || detailFading ||
                (materials.needsWarmup && warmPayloads.isNotEmpty()) ||
                simulation.needsFrames(displayed, now, motion) -> MatterCadence.Active
            motion && orbitAt != null && now >= orbitAt -> MatterCadence.Orbit
            else -> MatterCadence.Parked
        }
        orbitWakeAt = if (next == MatterCadence.Parked && motion && focused) orbitAt else null
        if (next != cadence) {
            cadence = next
            // Uniform updates are below the node invalidation system; present the final state.
            invalidator.requestRender()
        }
    })
    // Geometry, the neutral foam and the direct studio light exist at composition time.
    // HDR enrichment and atlases arrive in-stage; neither holds the first presented picture.
    firstFrame.holdUntil(landed = FeelMaterial.MemoryFoam in materials.samples, what = "material studio stage")

    // Observe before the scene/chips/app bar, without consuming their gestures. Even a
    // press on the already-selected chip skips the opening and freezes the idle camera.
    Box(Modifier.fillMaxSize().pointerInput(touch, idle) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            wakeRender()
            if (!simulation.opening.finished) touch.cancel()
            idle.touch(down.uptimeMillis, down = true)
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
            } finally {
                idle.touch(SystemClock.uptimeMillis(), down = false)
                wakeRender()
            }
        }
    }) {
        DemoScaffold(
            title = title, onBack = onBack,
            firstFrameRendered = firstFrame.sceneReady, sceneReady = firstFrame.sceneReady,
            contentIssue = firstFrame.contentIssue,
            loadingLabel = stringResource(R.string.demo_feel_loading),
            themedStage = true, actionsMenu = true, recorder = recorder, dockHidden = true,
        ) {
            Column(Modifier.fillMaxSize().background(colors.surface).padding(LocalDemoSceneCover.current)) {
                Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged {
                    stage.widthPx = it.width; stage.heightPx = it.height
                }) {
                    SceneView(
                        modifier = Modifier.fillMaxSize(), engine = engine, materialLoader = loader,
                        environmentLoader = environmentLoader, view = view, cameraNode = camera,
                        renderer = renderer, renderInvalidator = invalidator,
                        mainLightNode = light, fillLightNode = null, cameraManipulator = null,
                        autoCenterContent = false, environment = environment,
                        surfaceMirrorer = recorder.surfaceMirrorer,
                        frameRatePolicy = when (cadence) {
                            MatterCadence.Active -> FrameRatePolicy.Continuous(maxFps = 60)
                            MatterCadence.Orbit -> FrameRatePolicy.Continuous(maxFps = 60)
                            MatterCadence.Parked -> FrameRatePolicy.OnDemand(maxFps = 60)
                        },
                        onTouchEvent = { event, _ -> onTouchNow(event) },
                        onFrame = { onFrameNow(it) },
                    ) {
                        val progress = if (motion) slide.value else 1f
                        val direction = if (displayed.ordinal >= (outgoing?.ordinal ?: displayed.ordinal)) 1f else -1f
                        val shadowOffset = if (outgoing != null && progress < .40f) {
                            -MatterTransition.outgoingTravel(progress) * direction * stage.slideDistance
                        } else MatterTransition.incomingTravel(progress) * direction * .28f
                        val shadowKind = if (outgoing != null && progress < .40f) outgoing!! else displayed
                        // Local fill/rim lights: Filament supports one directional key per scene.
                        LightNode(type = LightManager.Type.POINT, position = Position(5f, 3f, 4f),
                            color = colorOf(.64f, .78f, 1f), intensity = 750_000f,
                            apply = { falloff(12f); castShadows(false) })
                        LightNode(type = LightManager.Type.SPOT, position = Position(1f, 4f, -4f),
                            direction = Direction(-1f, -3f, 4f), color = colorOf(.82f, .90f, 1f),
                            intensity = 500_000f,
                            apply = { falloff(12f); spotLightCone(.45f, .95f); castShadows(false) })
                        SideEffect {
                            materials.floor.setParameter("contact", shadowOffset * MatterStage.COS_AZIMUTH,
                                -shadowOffset * MatterStage.SIN_AZIMUTH,
                                MatterTransition.shadow(progress) * if (shadowKind == FeelMaterial.Gel) .25f else 1f)
                        }
                        PlaneNode(size = Size(200f, 0f, 200f), position = Position(y = floorY - .003f),
                            materialInstance = materials.floor, apply = {
                                isShadowCaster = false
                                isShadowReceiver = false
                                isTouchable = false
                                setCulling(false)
                                axisAlignedBoundingBox = com.google.android.filament.Box(0f, 1f, 0f, 100f, 1f, 100f)
                            })
                        FeelMaterial.entries.forEach { kind ->
                            val sampleMaterial = materials.samples[kind] ?: return@forEach
                            key(kind) {
                                val sampleMesh = if (kind.rigid) rigidMesh else mesh
                                Node(isVisible = kind == FeelMaterial.MemoryFoam, apply = { pads[kind.ordinal] = this }) {
                                    MeshNode(
                                        primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
                                        vertexBuffer = sampleMesh.vertexBuffer, indexBuffer = sampleMesh.indexBuffer,
                                        boundingBox = sampleMesh.boundingBox, materialInstance = sampleMaterial,
                                        apply = {
                                            // A transmissive gel must not receive its own opaque shadow in the well.
                                            isShadowCaster = false
                                            isShadowReceiver = kind != FeelMaterial.Gel
                                            isTouchable = false
                                        },
                                    )
                                    if (kind == FeelMaterial.MemoryFoam) {
                                        Node(isVisible = false, apply = { mark[0] = this; isTouchable = false }) {
                                            val side = MatterOpening.MARK_WIDTH / 1.4142136f
                                            val inset = side * MatterIdentity.FACE_INSET
                                            val face = side - 2f * inset
                                            val thickness = side * .006f
                                            Node(rotation = Rotation(y = MatterIdentity.yaw(MatterStage.AZIMUTH))) {
                                                // The three identity faces have open joints, with no hidden plate
                                                // showing through the front gap as a bright fourth face.
                                                CubeNode(size = Size(face, thickness, face),
                                                    position = Position(y = side - thickness / 2f),
                                                    materialInstance = materials.mark[0], apply = {
                                                        isShadowCaster = true; isShadowReceiver = false; isTouchable = false
                                                    })
                                                CubeNode(size = Size(face, face, thickness),
                                                    position = Position(y = side / 2f, z = (side - thickness) / 2f),
                                                    materialInstance = materials.mark[1], apply = {
                                                        isShadowCaster = true; isShadowReceiver = false; isTouchable = false
                                                    })
                                                CubeNode(size = Size(thickness, face, face),
                                                    position = Position(x = (side - thickness) / 2f, y = side / 2f),
                                                    materialInstance = materials.mark[2], apply = {
                                                        isShadowCaster = true; isShadowReceiver = false; isTouchable = false
                                                    })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Apply after SceneView's defaults. No mirror, SSAO, bloom or vignette passes.
                    LaunchedEffect(view) {
                        view.fogOptions = view.fogOptions.apply { enabled = false }
                        view.vignetteOptions = view.vignetteOptions.apply { enabled = false }
                        view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply { enabled = false }
                        view.bloomOptions = view.bloomOptions.apply { enabled = false }
                        view.shadowType = View.ShadowType.PCF
                        // Leave GPU headroom for a sustained press on the Pixel 4a, including the whorl.
                        view.dynamicResolutionOptions = view.dynamicResolutionOptions.apply {
                            enabled = true
                            homogeneousScaling = true
                            minScale = .67f
                            maxScale = .85f
                            quality = View.QualityLevel.MEDIUM
                        }
                        invalidator.requestRender()
                    }
                }
                FeelControls(selected, if (readout.material == selected) readout.depth else 0f,
                    readout.material == selected && readout.showDisplacement, outgoing != null, motion, choose)
            }
        }
    }
}

private data class FeelReadout(
    val material: FeelMaterial = FeelMaterial.MemoryFoam,
    val depth: Float = 0f,
    val showDisplacement: Boolean = false,
)

@Composable
private fun materialName(kind: FeelMaterial): String = stringResource(when (kind) {
    FeelMaterial.MemoryFoam -> R.string.demo_feel_foam
    FeelMaterial.Gel -> R.string.demo_feel_gel
    FeelMaterial.Velvet -> R.string.demo_feel_velvet
    FeelMaterial.Leather -> R.string.demo_feel_leather
    FeelMaterial.Marble -> R.string.demo_feel_marble
    FeelMaterial.BrushedSteel -> R.string.demo_feel_steel
})

@Composable
private fun FeelControls(selected: FeelMaterial, depth: Float, showDisplacement: Boolean,
                         switching: Boolean, motion: Boolean,
                         choose: (FeelMaterial) -> Unit) {
    val chips = rememberLazyListState()
    LaunchedEffect(selected, motion) {
        if (motion) chips.animateScrollToItem(selected.ordinal) else chips.scrollToItem(selected.ordinal)
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(vertical = SceneViewTokens.Space.sm)) {
            Column(Modifier.padding(horizontal = SceneViewTokens.Space.md),
                verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs)) {
                Text(materialName(selected), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                val response = if (!showDisplacement) when (selected) {
                    FeelMaterial.MemoryFoam -> R.string.demo_feel_foam_rest
                    FeelMaterial.Gel -> R.string.demo_feel_gel_rest
                    FeelMaterial.Velvet -> R.string.demo_feel_velvet_rest
                    FeelMaterial.Leather -> R.string.demo_feel_leather_rest
                    FeelMaterial.Marble, FeelMaterial.BrushedSteel -> R.string.demo_feel_rigid_rest
                } else when (selected) {
                    FeelMaterial.MemoryFoam -> R.string.demo_feel_foam_press
                    FeelMaterial.Gel -> if (depth < -0.5f) R.string.demo_feel_gel_rebound else R.string.demo_feel_gel_response
                    FeelMaterial.Velvet, FeelMaterial.Leather -> R.string.demo_feel_smooth_response
                    FeelMaterial.Marble, FeelMaterial.BrushedSteel -> R.string.demo_feel_rigid_response
                }
                Text(stringResource(response, abs(depth).roundToInt()), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyRow(state = chips, contentPadding = PaddingValues(horizontal = SceneViewTokens.Space.md),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
                itemsIndexed(FeelMaterial.entries, key = { _, kind -> kind.name }) { _, kind ->
                    FilterChip(selected = kind == selected, onClick = { choose(kind) },
                        enabled = !switching, label = { Text(materialName(kind)) })
                }
            }
        }
    }
}

/** A DOWN on the solid is permanently a press. Only DOWN in empty space may become a fling. */
private class FeelTouch(
    private val simulation: FeelSimulation,
    private val view: View,
    private val haptics: FeelHapticPlayer,
    private val stage: MatterStage,
    private val idle: MatterIdle,
    private val configuration: ViewConfiguration,
) {
    private var pointerId = -1
    private var screenX = 0f
    private var screenY = 0f
    private var downX = 0f
    private var downY = 0f
    private var emptyGesture = false
    private var velocity: VelocityTracker? = null

    fun onEvent(event: MotionEvent, selected: FeelMaterial, switching: Boolean, choose: (FeelMaterial) -> Unit): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                idle.touch(event.eventTime, down = true)
                if (switching) return true
                pointerId = event.getPointerId(0)
                screenX = event.x; screenY = event.y; downX = event.x; downY = event.y
                val point = pointOnPad(selected, deformed = false)
                emptyGesture = point == null
                if (point != null) {
                    simulation.begin(selected, point.first, point.second, event.pressure, event.size, event.eventTime)
                    simulation.advance(event.eventTime, startOpening = false)?.let(haptics::play)
                } else {
                    velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) { cancel(); return true }
                velocity?.addMovement(event)
                val active = simulation.active
                if (active != null) {
                    for (history in 0 until event.historySize) {
                        screenX = event.getHistoricalX(index, history); screenY = event.getHistoricalY(index, history)
                        pointOnPad(active, deformed = false)?.let {
                            simulation.move(it.first, it.second, event.getHistoricalPressure(index, history),
                                event.getHistoricalSize(index, history))
                        }
                    }
                }
                screenX = event.getX(index); screenY = event.getY(index)
                if (active != null) {
                    val point = pointOnPad(active, deformed = false)
                    if (point == null) { cancel(); return true }
                    simulation.move(point.first, point.second, event.getPressure(index), event.getSize(index))
                }
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(event)
                velocity?.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
                val vx = velocity?.getXVelocity(pointerId) ?: 0f
                val dx = event.x - downX; val dy = event.y - downY
                val fling = emptyGesture && abs(dx) > configuration.scaledTouchSlop && abs(dx) > abs(dy) * 1.5f &&
                    abs(vx) >= configuration.scaledMinimumFlingVelocity && dx * vx > 0f
                val next = (selected.ordinal + if (vx < 0f) 1 else -1).coerceIn(0, FeelMaterial.entries.lastIndex)
                cancel(released = true)
                if (fling && !switching) choose(FeelMaterial.entries[next])
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointerId) cancel(released = true)
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> cancel()
        }
        return true
    }

    fun followIndentation() {
        val kind = simulation.active ?: return
        val point = pointOnPad(kind, deformed = true) ?: return
        simulation.fingerX[kind.ordinal] = point.first.coerceIn(-FeelSimulation.PAD_HALF_WIDTH, FeelSimulation.PAD_HALF_WIDTH)
        simulation.fingerZ[kind.ordinal] = point.second.coerceIn(-FeelSimulation.PAD_HALF_LENGTH, FeelSimulation.PAD_HALF_LENGTH)
    }

    fun cancel(released: Boolean = false) {
        simulation.opening.cancel()
        haptics.cancel()
        idle.touch(SystemClock.uptimeMillis(), down = false)
        pointerId = -1
        emptyGesture = false
        velocity?.recycle(); velocity = null
        simulation.release(SystemClock.uptimeMillis(), hapticRelease = released)
    }

    private fun pointOnPad(kind: FeelMaterial, deformed: Boolean): Pair<Float, Float>? {
        if (view.viewport.width <= 0 || view.viewport.height <= 0) return null
        val ray = view.screenToRay(screenX, screenY) ?: return null
        val silhouette = stage.pointOnSlab(ray.origin.x, ray.origin.y, ray.origin.z,
            ray.direction.x, ray.direction.y, ray.direction.z) ?: return null
        if (!deformed || abs(silhouette.first) >= FeelSimulation.PAD_HALF_WIDTH - MatterMeshes.BEVEL ||
            abs(silhouette.second) >= FeelSimulation.PAD_HALF_LENGTH - MatterMeshes.BEVEL) return silhouette
        val point = stage.pointOnPad(ray.origin.x, ray.origin.y, ray.origin.z,
            ray.direction.x, ray.direction.y, ray.direction.z,
            MatterSurfaces.visualDepth(simulation.pads[kind.ordinal].depthMm, kind)) ?: return silhouette
        return point.first.coerceIn(-FeelSimulation.PAD_HALF_WIDTH, FeelSimulation.PAD_HALF_WIDTH) to
            point.second.coerceIn(-FeelSimulation.PAD_HALF_LENGTH, FeelSimulation.PAD_HALF_LENGTH)
    }
}
