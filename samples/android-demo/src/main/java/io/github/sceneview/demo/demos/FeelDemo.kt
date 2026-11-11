package io.github.sceneview.demo.demos

import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.Camera
import com.google.android.filament.RenderableManager
import com.google.android.filament.View
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
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
import io.github.sceneview.demo.demos.internal.feel.MatterMeshes
import io.github.sceneview.demo.demos.internal.feel.MatterOpening
import io.github.sceneview.demo.demos.internal.feel.MatterStage
import io.github.sceneview.demo.demos.internal.feel.MatterSurfaces
import io.github.sceneview.demo.recording.rememberDemoSceneRecorder
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.viewer.ShowroomFloor
import io.github.sceneview.demo.ui.viewer.ShowroomMath
import io.github.sceneview.demo.ui.viewer.ShowroomReflection
import io.github.sceneview.demo.ui.viewer.ShowroomStage
import io.github.sceneview.demo.ui.viewer.ShowroomToneMapping
import io.github.sceneview.demo.ui.viewer.ShowroomViewOptions
import io.github.sceneview.demo.ui.viewer.ViewerBackdrop
import io.github.sceneview.demo.ui.viewer.rememberShowroomReflection
import io.github.sceneview.demo.ui.viewer.rememberShowroomStage
import io.github.sceneview.demo.ui.viewer.showroomIsDark
import io.github.sceneview.environment.Environment
import io.github.sceneview.environment.rememberHDREnvironment
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
import io.github.sceneview.rememberView
import io.github.sceneview.utils.screenToRay
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
    val engine = rememberEngine()
    val loader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val view = rememberView(engine)
    val camera = rememberCameraNode(engine)
    val showroom = rememberShowroomStage(engine, view, loader)
    val reflection = rememberShowroomReflection(engine, view)
    val dark = showroomIsDark()
    val palette = if (dark) SceneViewTokens.Showroom.Dark else SceneViewTokens.Showroom.Light
    val hdrPath = ShowroomMath.hdrPath(dark)
    val hdr = rememberHDREnvironment(environmentLoader, hdrPath, createSkybox = false)
    val environment = remember(hdr, showroom) { (hdr ?: Environment()).copy(skybox = showroom.skybox) }
    val light = rememberMainLightNode(engine) {
        lightDirection = Direction(-0.45f, -1f, -0.35f)
        intensity = 12_000f
        isShadowCaster = true
    }
    LaunchedEffect(engine, light) {
        light.updateShadowOptions {
            mapSize = 1024
            shadowCascades = 1
            shadowFar = 30f
            normalBias = 0.02f
            shadowBulbRadius = 0.08f
        }
    }
    val recorder = rememberDemoSceneRecorder()
    val firstFrame = rememberFirstFrameState(engine)
    val touchView = LocalView.current
    var systemMotion by remember(touchView) {
        mutableStateOf(Settings.Global.getFloat(touchView.context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f)
    }
    val openingAllowed = MatterOpening.isAllowed(systemMotion, DemoSettings.qaMode)
    val motion = openingAllowed && LocalMotionEnabled.current
    // Keep an unplayed opening pending while QA/system motion disables it.
    val simulation = remember { FeelSimulation(openingEnabled = true) }
    val stage = remember { MatterStage() }
    val haptics = remember(touchView) { FeelHapticPlayer(touchView) }
    val touch = remember(simulation, view, haptics, stage, touchView) {
        FeelTouch(simulation, view, haptics, stage, ViewConfiguration.get(touchView.context))
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, touch) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                systemMotion = Settings.Global.getFloat(touchView.context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
            }
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) touch.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); touch.cancel() }
    }
    val mesh = remember(engine) { MatterMesh(engine, MatterMeshes.slab()) }
    val materials = remember(loader) { MatterSurfaceMaterials(loader) }
    DisposableEffect(mesh, materials) {
        onDispose { mesh.destroy(); materials.destroy() }
    }
    val pads = remember { arrayOfNulls<Node>(FeelMaterial.entries.size) }
    val mark = remember { arrayOfNulls<Node>(1) }
    var selected by remember { mutableStateOf(FeelMaterial.MemoryFoam) }
    var displayed by remember { mutableStateOf(selected) }
    var outgoing by remember { mutableStateOf<FeelMaterial?>(null) }
    val slide = remember { Animatable(1f) }
    val switching = selected != displayed || outgoing != null
    LaunchedEffect(selected, motion) {
        val previous = displayed
        if (!motion || previous == selected) {
            displayed = selected; outgoing = null; slide.snapTo(1f)
        } else {
            slide.snapTo(0f)
            outgoing = previous
            displayed = selected
            slide.animateTo(1f, SceneViewTokens.Motion.spring())
            outgoing = null
        }
    }
    val choose: (FeelMaterial) -> Unit = { next ->
        if (next != selected && !switching) {
            touch.cancel()
            simulation.select(next, SystemClock.uptimeMillis())
            selected = next
        }
    }
    val colors = MaterialTheme.colorScheme
    val ringColour = ViewerBackdrop.linearBeforeNeutral(colors.primary)
    val hairlinePx = with(LocalDensity.current) { SceneViewTokens.Layout.hairlineWidth.toPx() }
    val center = remember { Position(y = -FeelSimulation.PAD_THICKNESS / 2f) }
    val floorY = -FeelSimulation.PAD_THICKNESS
    SideEffect {
        hdr?.indirectLight?.apply {
            intensity = ShowroomMath.baseIntensity(hdrPath)
            setRotation(MatterStage.STUDIO_ROTATION)
        }
        showroom.dress(ShowroomStage.Look(
            palette = palette, isDark = dark, neutral = true,
            irradiance = ShowroomMath.upwardIrradiance(hdrPath),
            baseIntensity = ShowroomMath.baseIntensity(hdrPath), slider = 1f,
            exposure = ShowroomMath.exposure(camera.aperture, camera.shutterSpeed, camera.sensitivity),
            lights = true, staged = true, center = center, floorY = floorY, radius = MatterStage.RADIUS,
        ), camera.worldPosition)
        FeelMaterial.entries.filter { it != FeelMaterial.Gel }.forEach { kind ->
            materials.samples[kind.ordinal].setParameter("rippleColor", ringColour[0], ringColour[1], ringColour[2])
        }
    }
    ShowroomToneMapping(engine, view, neutral = true, requestRender = {})
    var readout by remember { mutableStateOf(FeelReadout()) }
    val timing = remember { longArrayOf(0L, 0L, 0L) }
    // Changing state is read by the FIRST SceneView callbacks through these updated delegates.
    val onTouchNow by rememberUpdatedState<(MotionEvent) -> Boolean>({ event ->
        touch.onEvent(event, displayed, switching, choose)
    })
    val onFrameNow by rememberUpdatedState<(Long) -> Unit>({ nanos ->
        stage.frame()
        camera.setProjection(MatterStage.HORIZONTAL_FOV,
            stage.widthPx.toDouble() / stage.heightPx.coerceAtLeast(1),
            MatterStage.NEAR, MatterStage.FAR, Camera.Fov.HORIZONTAL)
        camera.position = Position(stage.eyeX, stage.eyeY, stage.eyeZ)
        camera.lookAt(Position(y = stage.targetY))
        val now = SystemClock.uptimeMillis()
        if (!openingAllowed && simulation.opening.visible) simulation.opening.cancel()
        val focused = touchView.hasWindowFocus()
        // Initial composition can precede window focus. Do not dismiss an opening not yet shown.
        if (!focused && (simulation.active != null || simulation.opening.visible)) touch.cancel()
        simulation.advance(now, startOpening = firstFrame.sceneReady.value && focused && openingAllowed)
            ?.let(haptics::play)
        touch.followIndentation()
        val direction = if (displayed.ordinal >= (outgoing?.ordinal ?: displayed.ordinal)) 1f else -1f
        val progress = if (motion) slide.value else 1f
        FeelMaterial.entries.forEach { kind ->
            val visible = kind == displayed || kind == outgoing
            pads[kind.ordinal]?.apply {
                isVisible = visible
                if (visible) {
                    val offset = when (kind) {
                        outgoing -> -progress * direction * stage.slideDistance
                        else -> (1f - progress) * direction * stage.slideDistance
                    }
                    position = Position(offset * MatterStage.COS_AZIMUTH, 0f, -offset * MatterStage.SIN_AZIMUTH)
                    materials.update(kind, simulation, now, motion,
                        hairlinePx * stage.halfWidth * 2f / stage.widthPx.coerceAtLeast(1))
                }
            }
        }
        mark[0]?.apply {
            isVisible = simulation.opening.visible && displayed == FeelMaterial.MemoryFoam
            if (isVisible) position = Position(y = simulation.opening.heightAbovePad -
                MatterSurfaces.visualDepth(simulation.pads[0].depthMm, FeelMaterial.MemoryFoam))
        }
        showroom.onFrame(camera.worldPosition)
        // Sample the visible state at 30 Hz. An idle sample reuses its reflection indefinitely.
        // Shader-only deformation, the ripple and opening are invisible to the mirror's pose cache.
        if (now - timing[1] >= 32L) {
            timing[1] = now
            var signature = displayed.ordinal.toLong() * 31 + (hdr?.indirectLight?.hashCode() ?: 0)
            for (kind in FeelMaterial.entries) if (kind == displayed || kind == outgoing) {
                val i = kind.ordinal
                signature = signature * 31 + simulation.pads[i].depthMm.toBits()
                signature = signature * 31 + simulation.fingerX[i].toBits()
                signature = signature * 31 + simulation.fingerZ[i].toBits()
                val contactAt = simulation.contactMillis[i]
                if (kind.rigid && motion && contactAt != Long.MIN_VALUE &&
                    now - contactAt in 0L until MatterMeshes.RIPPLE_MILLIS) signature += now / 32L
            }
            signature = signature * 31 + progress.toBits()
            signature = signature * 31 + simulation.opening.heightAbovePad.toBits()
            signature = signature * 31 + if (simulation.opening.visible) 1L else 0L
            timing[2] = signature
        }
        reflection.set(showroom.floorMaterial, ShowroomReflection.Pose(
            center = center, floorY = floorY - ShowroomMath.FLOOR_DROP_RADII * MatterStage.RADIUS,
            radius = MatterStage.RADIUS, halfWidth = FeelSimulation.PAD_HALF_WIDTH,
            halfDepth = FeelSimulation.PAD_HALF_LENGTH, height = FeelSimulation.PAD_THICKNESS,
            yawDegrees = 0f, lift = 0f, isDark = dark, contentVersion = timing[2],
        ))
        reflection.onFrame()
        if (now - timing[0] >= 50L) {
            timing[0] = now
            val indentation = simulation.pads[displayed.ordinal]
            readout = FeelReadout(displayed, indentation.depthMm,
                MatterSurfaces.showsDisplacement(displayed, indentation, simulation.active == displayed))
        }
        firstFrame.onFrame(nanos)
    })
    firstFrame.holdUntil(hdr?.indirectLight != null, "material studio lighting")

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
                    mainLightNode = light, fillLightNode = null, cameraManipulator = null,
                    autoCenterContent = false, environment = environment,
                    surfaceMirrorer = recorder.surfaceMirrorer,
                    frameRatePolicy = FrameRatePolicy.Continuous(),
                    onTouchEvent = { event, _ -> onTouchNow(event) },
                    onFrame = { onFrameNow(it) },
                ) {
                    ShowroomFloor(stage = showroom, center = center, floorY = floorY,
                        radius = MatterStage.RADIUS, halfWidth = FeelSimulation.PAD_HALF_WIDTH,
                        halfDepth = FeelSimulation.PAD_HALF_LENGTH, yawDegrees = 0f,
                        showFloor = true, shadowAlpha = if (switching) 0f else 1f)
                    FeelMaterial.entries.forEach { kind ->
                        Node(isVisible = kind == FeelMaterial.MemoryFoam, apply = { pads[kind.ordinal] = this }) {
                            MeshNode(
                                primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
                                vertexBuffer = mesh.vertexBuffer, indexBuffer = mesh.indexBuffer,
                                boundingBox = mesh.boundingBox, materialInstance = materials.samples[kind.ordinal],
                                apply = {
                                    isShadowCaster = true; isShadowReceiver = true; isTouchable = false
                                    setLayerMask(ShowroomReflection.MIRROR_LAYER, ShowroomReflection.MIRROR_LAYER)
                                },
                            )
                            if (kind == FeelMaterial.MemoryFoam) {
                                Node(isVisible = false, apply = { mark[0] = this; isTouchable = false }) {
                                    val side = MatterOpening.MARK_WIDTH / 1.4142136f
                                    CubeNode(size = Size(side), position = Position(y = side / 2f),
                                        rotation = Rotation(y = 45f + MatterStage.AZIMUTH),
                                        materialInstance = materials.mark, apply = {
                                            isTouchable = false
                                            setLayerMask(ShowroomReflection.MIRROR_LAYER, ShowroomReflection.MIRROR_LAYER)
                                        })
                                }
                            }
                        }
                    }
                }
                ShowroomViewOptions(view, dark, staged = true, showroomLit = true,
                    radius = MatterStage.RADIUS, requestRender = {})
                // Apply AFTER the showroom/default quality effects; Matter has no bloom halo.
                LaunchedEffect(view, dark) {
                    view.bloomOptions = view.bloomOptions.apply { enabled = false }
                    view.shadowType = View.ShadowType.DPCF
                }
            }
            FeelControls(selected, if (readout.material == selected) readout.depth else 0f,
                readout.material == selected && readout.showDisplacement, switching, motion, choose)
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
        if (released && simulation.active != null) haptics.release() else haptics.cancel()
        pointerId = -1
        emptyGesture = false
        velocity?.recycle(); velocity = null
        simulation.release(SystemClock.uptimeMillis())
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
