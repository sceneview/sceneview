package io.github.sceneview.demo.demos

import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.ViewTreeObserver
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.Skybox
import com.google.android.filament.Camera
import com.google.android.filament.LightManager
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
import io.github.sceneview.demo.demos.internal.feel.MatterPreparation
import io.github.sceneview.demo.demos.internal.feel.MatterSwitch
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
import io.github.sceneview.math.colorOf
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.node.Node
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.rememberNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberView
import io.github.sceneview.utils.screenToRay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    // Read and pack CPU data off-main. Only Filament construction/upload remains on-main.
    val openingPayloads by produceState<List<ByteBuffer>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            listOf(MatterSurfaceMaterials.asset(FeelMaterial.MemoryFoam), "materials/opaque_unlit_colored.filamat", "materials/matter_floor.filamat").map { path ->
                val bytes = context.assets.open(path).use { it.readBytes() }
                ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { flip() }
            }
        }
    }
    val meshUploads by produceState<Pair<MatterMesh.Upload, MatterMesh.Upload>?>(null) {
        value = withContext(Dispatchers.Default) {
            MatterMesh.Upload(MatterMeshes.slab()) to MatterMesh.Upload(MatterMeshes.slab(rigid = true))
        }
    }
    val initialPayloads = openingPayloads ?: return
    val initialMeshes = meshUploads ?: return
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
    val stage = remember { MatterStage() }
    val camera = rememberNode {
        object : CameraNode(engine) {
            // SceneView calls this on attachment AND native surface recreation, even when
            // Compose reports the same size. Keep the horizontal FOV instead of its 28 mm lens.
            override fun updateProjection(focalLength: Double, near: Float, far: Float, aspect: Double) {
                val viewport = viewport ?: return
                if (viewport.width <= 0 || viewport.height <= 0) return
                stage.widthPx = viewport.width
                stage.heightPx = viewport.height
                setProjection(MatterStage.HORIZONTAL_FOV,
                    viewport.width.toDouble() / viewport.height,
                    MatterStage.NEAR, MatterStage.FAR, Camera.Fov.HORIZONTAL)
            }
        }
    }
    // A single studio for all samples and both app themes. The IBL only fills reflections.
    val skybox = remember(engine) {
        val grey = MatterStage.STUDIO_GREY
        Skybox.Builder().color(grey, grey, grey, 1f).build(engine)
    }
    DisposableEffect(skybox) { onDispose { engine.destroySkybox(skybox) } }
    val hdr by key(environmentLoader) {
        produceState<Environment?>(null) {
            // Null means still loading. An empty environment means the bundled HDR failed,
            // so the stage can finish preparation with its direct studio lights.
            val loaded = try {
                environmentLoader.loadHDREnvironment(ShowroomMath.DARK_HDR, createSkybox = false)
                    ?: Environment().also { Log.w("Matter", "Studio HDR unavailable; using direct lights") }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w("Matter", "Studio HDR failed; using direct lights", error)
                Environment()
            }
            // The loader releases an orphan if cancellation happens during its main-thread
            // build. From here, disposal runs on Main and owns the completed environment.
            value = loaded
            awaitDispose { environmentLoader.destroyEnvironment(loaded) }
        }
    }
    val environment = remember(hdr, skybox) { (hdr ?: Environment()).copy(skybox = skybox) }
    val light = rememberNode {
        LightNode(engine, LightManager.Type.SPOT) {
            direction(-MatterStage.KEY_X, MatterStage.FLOOR_Y - MatterStage.KEY_Y,
                MatterStage.KEY_TARGET_Z - MatterStage.KEY_Z)
            intensityCandela(MatterStage.KEY_CANDELA)
            spotLightCone(Math.toRadians(MatterStage.KEY_INNER_DEGREES.toDouble()).toFloat(),
                Math.toRadians(MatterStage.KEY_OUTER_DEGREES.toDouble()).toFloat())
            falloff(MatterStage.KEY_RANGE)
            castShadows(true)
        }.apply { position = Position(MatterStage.KEY_X, MatterStage.KEY_Y, MatterStage.KEY_Z) }
    }
    val rim = rememberMainLightNode(engine) {
        // Grazing backlight catches the top bevel and the cloth's pale Charlie sheen.
        lightDirection = Direction(-.35f, -.25f, .8f)
        color = colorOf(1f, 1f, 1f)
        intensity = MatterStage.RIM_LUX
        isShadowCaster = false
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
    val mesh = remember(engine, initialMeshes) { MatterMesh(engine, initialMeshes.first) }
    val rigidMesh = remember(engine, initialMeshes) { MatterMesh(engine, initialMeshes.second) }
    val materials = remember(engine, loader, initialPayloads) {
        MatterSurfaceMaterials(engine, loader, initialPayloads[0], initialPayloads[1], initialPayloads[2])
    }
    DisposableEffect(mesh, rigidMesh, materials) {
        onDispose { mesh.destroy(); rigidMesh.destroy(); materials.destroy() }
    }
    val pads = remember { arrayOfNulls<Node>(FeelMaterial.entries.size) }
    val mark = remember { arrayOfNulls<Node>(1) }
    // Selection, the solid on stage and the slide between them, advanced per presented frame.
    val switch = remember { MatterSwitch() }
    var openingPrepared by remember(materials) { mutableStateOf(false) }
    // Only the opening belongs behind the cover. Never draw another sample to warm it:
    // Material.compile queues backend work without putting its slab in the visible set.
    LaunchedEffect(materials, touchView) {
        val preparation = MatterPreparation()
        val cache = withContext(Dispatchers.IO) { MatterDetailCache(touchView.context.cacheDir) }
        suspend fun uploadDetail(kind: FeelMaterial) {
            if (materials.hasDetail(kind)) return
            val cached = withContext(Dispatchers.IO) { cache.read(kind) }
            val pixels = cached ?: withContext(Dispatchers.Default) {
                MatterDetailAtlas.bake(kind) { ensureActive() }
            }
            val buffer = withContext(Dispatchers.Default) {
                ByteBuffer.allocateDirect(pixels.size).put(pixels).apply { flip() }
            }
            withFrameNanos { }
            materials.uploadDetail(kind, buffer)
            invalidator.requestRender()
            if (cached == null) withContext(Dispatchers.IO) { cache.write(kind, pixels) }
        }
        // Foam, floor and mark already exist. Their first real draw compiles only the
        // variants needed by the opening; FirstFrame waits for that draw's backend fence.
        uploadDetail(FeelMaterial.MemoryFoam)
        preparation.prepared(FeelMaterial.MemoryFoam)
        openingPrepared = true
        wakeRender()
        snapshotFlow { firstFrame.sceneReady.value }.first { it }
        fun prepareSample(kind: FeelMaterial) = preparation.request(this, kind) {
            val payload = withContext(Dispatchers.IO) {
                val bytes = touchView.context.assets.open(MatterSurfaceMaterials.asset(kind)).use { it.readBytes() }
                ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { flip() }
            }
            // Preserve the switch path: the shader-ready instance uses its existing fallback
            // detail until the CPU atlas arrives. A cold marble bake must not block selection.
            materials.prepare(kind, payload)
            uploadDetail(kind)
            if (BuildConfig.DEBUG) Log.d("Matter", "Background material ready: ${kind.name}")
        }
        launch {
            snapshotFlow { switch.selected }.collect { kind ->
                if (kind != FeelMaterial.MemoryFoam) prepareSample(kind)
            }
        }
        while (true) {
            // Space background submissions out. Explicit selections may overtake a slow bake.
            repeat(MatterPreparation.FRAMES_BETWEEN_MATERIALS) { withFrameNanos { } }
            val kind = preparation.next(switch.selected, revealed = firstFrame.sceneReady.value) ?: break
            prepareSample(kind).await()
        }
    }
    // Never refused while a slide runs: the chips stay enabled and the latest request queues.
    val choose: (FeelMaterial) -> Unit = { next ->
        if (firstFrame.sceneReady.value && switch.request(next)) {
            touch.cancel()
            simulation.select(next, SystemClock.uptimeMillis())
            wakeRender()
        }
    }
    val colors = MaterialTheme.colorScheme
    val ringColour = ViewerBackdrop.linearBeforeNeutral(colors.primary)
    val hairlinePx = with(LocalDensity.current) { SceneViewTokens.Layout.hairlineWidth.toPx() }
    SideEffect {
        MatterIdentity.colours(dark = colors.surface.luminance() < .5f).forEachIndexed { face, rgb ->
            val linear = MatterIdentity.linearBeforeToneMap(rgb)
            materials.mark[face].setParameter("color", linear[0], linear[1], linear[2], 1f)
        }
        hdr?.indirectLight?.apply {
            intensity = 8_000f
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
        firstFrame.sceneReady.value && touch.onEvent(event, switch, choose)
    })
    val onFrameNow by rememberUpdatedState<(Long) -> Unit>({ nanos ->
        val now = SystemClock.uptimeMillis()
        if (BuildConfig.DEBUG && firstFrame.sceneReady.value) frameTimes.frame(nanos)?.let { window ->
            Log.d("MatterPerf", String.format(Locale.US,
                "material=%s mode=%s avg=%.2fms fps=%.1f intervals=%d",
                switch.displayed.name, cadence.name, window.averageMillis, window.fps, window.intervals))
        }
        val focused = touchView.hasWindowFocus()
        // The incoming solid's node must exist (its sample is composed), or its hidden first
        // draw would not happen. The slide itself advances only on presented frames.
        if (switch.advance(now, ready = pads[switch.selected.ordinal] != null, motion)) idle.touch(now, down = false)
        val shown = switch.displayed
        val leaving = switch.outgoing
        val switching = switch.switching
        val progress = switch.progress
        val cameraMoved = idle.advance(now, motion && focused && simulation.opening.finished && !switching)
        if (cameraMoved) stage.orbit(idle.yawDegrees)
        val reframed = stage.frame()
        if (cameraMoved || reframed) {
            camera.position = Position(stage.eyeX, stage.eyeY, stage.eyeZ)
            camera.lookAt(Position(stage.targetX, stage.targetY, stage.targetZ))
        }
        simulation.opening.travel = stage.openingTravel
        if (!motion && !simulation.opening.finished) simulation.opening.cancel()
        // Initial composition can precede window focus. Do not dismiss an opening not yet shown.
        if (!focused && (simulation.active != null || simulation.opening.visible)) touch.cancel()
        haptics.onFrame()
        touch.resumeAfterSwitch(switch)
        touch.followIndentation()
        simulation.advance(now, startOpening = firstFrame.sceneReady.value && focused && motion)
            ?.let(haptics::play)
        // Only the opening mark needs a mapped shadow. Re-rendering the dense, deformed
        // slab into a shadow atlas doubled its vertex work during every press and orbit.
        val warmingOpening = !firstFrame.sceneReady.value && motion
        val needsMappedShadow = simulation.opening.visible || warmingOpening
        if (light.isShadowCaster != needsMappedShadow) light.isShadowCaster = needsMappedShadow
        val direction = switch.direction
        FeelMaterial.entries.forEach { kind ->
            // The incoming solid is drawn from the first frame of a slide, at its hidden pose
            // under the floor: the stall of its first draw happens before it has to move.
            val visible = kind == shown || (kind == leaving && progress < MatterTransition.HIDDEN)
            pads[kind.ordinal]?.apply {
                isVisible = visible
                if (visible) {
                    val offset = when (kind) {
                        leaving -> -MatterTransition.outgoingTravel(progress) * direction * stage.slideDistance
                        else -> MatterTransition.incomingTravel(progress) * direction * .28f
                    }
                    val height = MatterTransition.height(kind, progress, kind == leaving)
                    position = Position(offset * MatterStage.COS_AZIMUTH, height, -offset * MatterStage.SIN_AZIMUTH)
                    materials.update(kind, simulation, now, motion,
                        hairlinePx * stage.halfWidth * 2f / stage.widthPx.coerceAtLeast(1),
                        landingAge = if (kind == shown) switch.landingAge(now) else -1f)
                }
            }
        }
        // The floor's soft contact shadow follows whichever solid stands on it.
        val shadowLeaving = leaving != null && progress < MatterTransition.HIDDEN
        val shadowOffset = if (shadowLeaving) {
            -MatterTransition.outgoingTravel(progress) * direction * stage.slideDistance
        } else MatterTransition.incomingTravel(progress) * direction * .28f
        val shadowKind = if (shadowLeaving) leaving!! else shown
        val contactStrength = MatterTransition.shadow(progress) *
            if (shadowKind == FeelMaterial.Gel) MatterStage.GEL_CONTACT_SHADOW else 1f
        val contactX = shadowOffset * MatterStage.COS_AZIMUTH
        val contactZ = -shadowOffset * MatterStage.SIN_AZIMUTH
        materials.floor.setParameter("contact", contactX, contactZ, contactStrength)
        materials.samples[FeelMaterial.Gel]?.setParameter("contact", contactX, contactZ, contactStrength)
        val landedAt = switch.landedAt
        if (motion && shown.rigid && landedAt != null && simulation.contactMillis[shown.ordinal] < landedAt) {
            // One landing tap per arrival, when the rigid solid reaches its rest height.
            simulation.contactX[shown.ordinal] = 0f
            simulation.contactZ[shown.ordinal] = 0f
            simulation.contactMillis[shown.ordinal] = now
        }
        mark[0]?.apply {
            isVisible = warmingOpening || (simulation.opening.visible && shown == FeelMaterial.MemoryFoam)
            if (isVisible) position = if (warmingOpening) Position(y = 1f) else
                Position(y = simulation.opening.heightAbovePad -
                    MatterSurfaces.visualDepth(simulation.pads[0].depthMm, FeelMaterial.MemoryFoam) - simulation.opening.squash)
        }
        if (now - timing[0] >= 50L) {
            timing[0] = now
            val indentation = simulation.pads[shown.ordinal]
            val peakMm = simulation.captionDepthMm(shown)
            readout = FeelReadout(shown, peakMm,
                MatterSurfaces.showsDisplacement(shown, indentation, simulation.active == shown, peakMm))
        }
        firstFrame.onFrame(nanos)
        val orbitAt = idle.wakeAt
        val settling = motion && switch.startedAt?.let { now - it in 0L..1100L } == true
        val next = when {
            !focused -> MatterCadence.Parked
            !firstFrame.sceneReady.value || switching || settling ||
                simulation.needsFrames(shown, now, motion) -> MatterCadence.Active
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
    // Reveal once the opening detail and studio lighting have reached a real frame.
    firstFrame.holdUntil(landed = openingPrepared && hdr != null, what = "material studio stage")

    // Observe before the scene/chips/app bar, without consuming their gestures. Even a
    // press on the already-selected chip skips the opening and freezes the idle camera.
    Box(Modifier.fillMaxSize().pointerInput(touch, idle) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            wakeRender()
            if (firstFrame.sceneReady.value && !simulation.opening.finished) touch.cancel()
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
                // This is the actual SceneView surface, ABOVE FeelControls. Native viewport
                // dimensions (not the full window or dynamic-resolution buffer) frame the slab.
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    SceneView(
                        modifier = Modifier.fillMaxSize(), engine = engine, materialLoader = loader,
                        environmentLoader = environmentLoader, view = view, cameraNode = camera,
                        renderer = renderer, renderInvalidator = invalidator,
                        mainLightNode = light, fillLightNode = rim, cameraManipulator = null,
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
                        PlaneNode(size = Size(200f, 0f, 200f), position = Position(y = MatterStage.FLOOR_Y),
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
                FeelControls(switch.selected, if (readout.material == switch.selected) readout.depth else 0f,
                    readout.material == switch.selected && readout.showDisplacement, motion, choose)
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
                         motion: Boolean, choose: (FeelMaterial) -> Unit) {
    val chips = rememberLazyListState()
    val marginPx = with(LocalDensity.current) { SceneViewTokens.Space.md.roundToPx() }
    LaunchedEffect(selected, motion) {
        // Bring the chip into view, no further: a visible chip never slides under the finger,
        // and the first chips stay reachable instead of scrolling off the left edge.
        val info = chips.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == selected.ordinal }
        if (item == null) {
            if (motion) chips.animateScrollToItem(selected.ordinal) else chips.scrollToItem(selected.ordinal)
            return@LaunchedEffect
        }
        val delta = MatterSwitch.chipScroll(item.offset, item.offset + item.size,
            info.viewportStartOffset, info.viewportEndOffset, marginPx)
        if (delta != 0f) if (motion) chips.animateScrollBy(delta) else chips.scrollBy(delta)
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
                        label = { Text(materialName(kind)) })
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
    // A finger that landed during a switch: it becomes a press once the new solid has landed.
    private var waitingForSwitch = false
    private var pressure = 0f
    private var size = 0f

    fun onEvent(event: MotionEvent, switch: MatterSwitch, choose: (FeelMaterial) -> Unit): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                idle.touch(event.eventTime, down = true)
                pointerId = event.getPointerId(0)
                screenX = event.x; screenY = event.y; downX = event.x; downY = event.y
                pressure = event.pressure; size = event.size
                val point = if (switch.switching) null else pointOnPad(switch.displayed, deformed = false)
                waitingForSwitch = switch.switching
                emptyGesture = point == null
                if (point != null) {
                    simulation.begin(switch.displayed, point.first, point.second, event.pressure, event.size, event.eventTime)
                    simulation.advance(event.eventTime, startOpening = false)?.let(haptics::play)
                } else {
                    velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) { cancel(); return true }
                velocity?.addMovement(event)
                pressure = event.getPressure(index); size = event.getSize(index)
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
                cancel(released = true)
                // Relative to the latest request, so two quick flings step two materials.
                if (fling) choose(MatterSwitch.flingTarget(switch.selected, vx))
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointerId) cancel(released = true)
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> cancel()
        }
        return true
    }

    /** Per frame: a finger held since the switch presses the new solid as soon as it has landed. */
    fun resumeAfterSwitch(switch: MatterSwitch) {
        if (!waitingForSwitch || pointerId < 0 || switch.switching) return
        waitingForSwitch = false
        // A finger already travelling is a fling on its way, not a press.
        if (abs(screenX - downX) > configuration.scaledTouchSlop || abs(screenY - downY) > configuration.scaledTouchSlop) return
        val point = pointOnPad(switch.displayed, deformed = false) ?: return
        val now = SystemClock.uptimeMillis()
        emptyGesture = false
        velocity?.recycle(); velocity = null
        simulation.begin(switch.displayed, point.first, point.second, pressure, size, now)
        simulation.advance(now, startOpening = false)?.let(haptics::play)
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
        waitingForSwitch = false
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
