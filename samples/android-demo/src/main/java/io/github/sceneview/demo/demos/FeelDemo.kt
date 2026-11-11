package io.github.sceneview.demo.demos

import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.Camera
import com.google.android.filament.RenderableManager
import com.google.android.filament.Skybox
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.LocalDemoSceneCover
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.FeelHapticPlayer
import io.github.sceneview.demo.demos.internal.MatterMesh
import io.github.sceneview.demo.demos.internal.feel.FeelMaterial
import io.github.sceneview.demo.demos.internal.feel.FeelSimulation
import io.github.sceneview.demo.demos.internal.feel.MatterMeshes
import io.github.sceneview.demo.demos.internal.feel.MatterStage
import io.github.sceneview.demo.recording.rememberDemoSceneRecorder
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.motionFade
import io.github.sceneview.demo.ui.viewer.ViewerBackdrop
import io.github.sceneview.environment.Environment
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.math.Transform
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberView
import io.github.sceneview.utils.screenToRay
import kotlin.math.roundToInt

/** Matter: one camera, three solid samples, one shared load simulation. No downloaded assets. */
@Composable
fun FeelDemo(onBack: () -> Unit) {
    val title = stringResource(R.string.demo_feel_title)
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = title, onBack = onBack)
        return
    }
    val engine = rememberEngine()
    val loader = rememberMaterialLoader(engine)
    val view = rememberView(engine)
    val camera = rememberCameraNode(engine)
    val recorder = rememberDemoSceneRecorder()
    val firstFrame = rememberFirstFrameState(engine)
    val touchView = LocalView.current
    // Also check directly: the debug host does not necessarily provide LocalMotionEnabled.
    val systemMotion = remember(touchView) {
        Settings.Global.getFloat(touchView.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    val motion = systemMotion && LocalMotionEnabled.current && !DemoSettings.qaMode
    val simulation = remember { FeelSimulation(openingEnabled = motion) }
    val stage = remember { MatterStage() }
    val haptics = remember(touchView) { FeelHapticPlayer(touchView) }
    val touch = remember(simulation, view, haptics, stage) { FeelTouch(simulation, view, haptics, stage) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, touch) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) touch.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); touch.cancel() }
    }

    val colors = MaterialTheme.colorScheme
    val skybox = remember(engine, colors.surface) {
        val c = ViewerBackdrop.linearBeforeNeutral(colors.surface)
        Skybox.Builder().color(c[0], c[1], c[2], 1f).build(engine)
    }
    DisposableEffect(engine, skybox) { onDispose { engine.destroySkybox(skybox) } }
    val environment = remember(skybox) { Environment(skybox = skybox) }
    val meshes = remember(engine) {
        Array(3) { listOf(MatterMeshes.top(), MatterMeshes.sides(), MatterMeshes.lattice(),
            MatterMeshes.contact(true), MatterMeshes.contact(false)).map { MatterMesh(engine, it) } }
    }
    DisposableEffect(meshes) { onDispose { meshes.forEach { row -> row.forEach(MatterMesh::destroy) } } }
    val materials = remember(loader, colors) {
        val roles = listOf(
            listOf(colors.primary, colors.primaryContainer, colors.onPrimary, colors.onPrimary, colors.onPrimary),
            listOf(colors.tertiary, colors.tertiaryContainer, colors.onTertiary, colors.onTertiary, colors.onTertiary),
            listOf(colors.surfaceContainerHighest, colors.outline, colors.onSurfaceVariant, colors.onSurface, colors.onSurface),
        )
        roles.map { row -> row.mapIndexed { index, color ->
            loader.createUnlitColorInstance(color.copy(alpha = when (index) { 2 -> 0.42f; 3 -> 0.12f; else -> 1f }))
        } }
    }
    val markMaterial = remember(loader, colors) { loader.createColorInstance(colors.primary, roughness = 0.8f) }
    val bracketMaterial = remember(loader, colors) { loader.createUnlitColorInstance(colors.primary) }
    DisposableEffect(loader, materials, markMaterial, bracketMaterial) {
        onDispose {
            materials.flatten().forEach(loader::destroyMaterialInstance)
            loader.destroyMaterialInstance(markMaterial)
            loader.destroyMaterialInstance(bracketMaterial)
        }
    }
    val pads = remember { arrayOfNulls<Node>(3) }
    val contacts = remember { Array(3) { arrayOfNulls<Node>(2) } }
    val mark = remember { arrayOfNulls<Node>(1) }
    val lastDeformation = remember { Array(3) { floatArrayOf(Float.NaN, Float.NaN, Float.NaN) } }
    val motionNow by rememberUpdatedState(motion)
    val touchNow by rememberUpdatedState(touch)
    val meshesNow by rememberUpdatedState(meshes)
    var readout by remember { mutableStateOf(FeelReadout()) }
    val timing = remember { longArrayOf(0L) }
    firstFrame.holdUntil(true, "material samples")

    DemoScaffold(
        title = title,
        onBack = onBack,
        firstFrameRendered = firstFrame.sceneReady,
        sceneReady = firstFrame.sceneReady,
        contentIssue = firstFrame.contentIssue,
        loadingLabel = stringResource(R.string.demo_feel_loading),
        themedStage = true,
        actionsMenu = true,
        recorder = recorder,
        bottomOverlayReservesScene = true,
        bottomOverlay = {
            Surface(color = colors.surface, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (readout.showHint) stringResource(R.string.demo_feel_instruction) else "",
                    Modifier.padding(horizontal = SceneViewTokens.Space.md, vertical = SceneViewTokens.Space.sm),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
    ) {
        // Physically inset this viewport once. The labels use precisely the same free area.
        Box(Modifier.fillMaxSize().padding(LocalDemoSceneCover.current).onSizeChanged {
            stage.widthPx = it.width
            stage.heightPx = it.height
        }) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                materialLoader = loader,
                view = view,
                cameraNode = camera,
                cameraManipulator = null,
                autoCenterContent = false,
                environment = environment,
                surfaceMirrorer = recorder.surfaceMirrorer,
                frameRatePolicy = FrameRatePolicy.Continuous(),
                // SceneView retains the FIRST callback: never close over a readiness Boolean.
                onTouchEvent = { event, _ -> touchNow.onEvent(event) },
                onFrame = { nanos ->
                    if (stage.frame()) {
                        camera.setProjection(Camera.Projection.ORTHO, -stage.halfWidth.toDouble(), stage.halfWidth.toDouble(),
                            -stage.halfHeight.toDouble(), stage.halfHeight.toDouble(), 0.1, 100.0)
                        camera.position = Position(0f, 20f * MatterStage.SIN, 20f * MatterStage.COS)
                        camera.lookAt(Position())
                        for (i in 0..2) pads[i]?.transform = Transform(
                            Position(0f, stage.y[i], stage.z[i]), scale = Scale(stage.scale[i]),
                        )
                    }
                    val now = SystemClock.uptimeMillis()
                    if (!motionNow) simulation.opening.cancel()
                    if (!touchView.hasWindowFocus()) touchNow.cancel()
                    simulation.advance(now, startOpening = firstFrame.sceneReady)?.let(haptics::play)
                    touchNow.followIndentation()
                    FeelMaterial.entries.forEach { kind ->
                        val i = kind.ordinal
                        val depth = simulation.pads[i].depthMm
                        val x = simulation.fingerX[i]
                        val z = simulation.fingerZ[i]
                        val previous = lastDeformation[i]
                        if (depth != previous[0] || x != previous[1] || z != previous[2]) {
                            for (part in 0..2) {
                                val mesh = meshesNow[i][part]
                                mesh.geometry.deform(depth, x, z, kind.radius)
                                mesh.upload()
                            }
                            previous[0] = depth; previous[1] = x; previous[2] = z
                        }
                        val touching = simulation.active == kind
                        val age = if (simulation.contactMillis[i] == Long.MIN_VALUE) Long.MAX_VALUE else now - simulation.contactMillis[i]
                        val ripple = kind == FeelMaterial.Steel && motionNow && age in 0L..450L
                        contacts[i][0]?.isVisible = touching
                        contacts[i][1]?.isVisible = if (kind == FeelMaterial.Steel) ripple else touching
                        if (touching || ripple) {
                            val contactRadius = 0.20f + simulation.inferred.value * 0.15f
                            MatterMeshes.updateContact(meshesNow[i][3].geometry, contactRadius, x, z, depth, kind)
                            MatterMeshes.updateContact(meshesNow[i][4].geometry,
                                if (ripple) 0.20f + age / 450f * 2.2f else contactRadius, x, z, depth, kind)
                            meshesNow[i][3].upload()
                            meshesNow[i][4].upload()
                        }
                    }
                    mark[0]?.apply {
                        isVisible = simulation.opening.visible
                        if (isVisible) transform = Transform(Position(y =
                            -simulation.pads[0].depthMm * FeelMaterial.WORLD_PER_MM + simulation.opening.heightAbovePad))
                    }
                    if (now - timing[0] >= 50L) {
                        timing[0] = now
                        readout = FeelReadout(simulation.pads[0].depthMm, simulation.active == FeelMaterial.Foam,
                            simulation.opening.finished)
                    }
                    firstFrame.onFrame(nanos)
                },
            ) {
                FeelMaterial.entries.forEach { kind ->
                    val i = kind.ordinal
                    Node(apply = { pads[i] = this }) {
                        meshes[i].forEachIndexed { part, mesh ->
                            MeshNode(
                                primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
                                vertexBuffer = mesh.buffers.vertexBuffer,
                                indexBuffer = mesh.buffers.indexBuffer,
                                boundingBox = mesh.buffers.box,
                                materialInstance = materials[i][part],
                                apply = {
                                    isShadowCaster = false
                                    isShadowReceiver = false
                                    isTouchable = false
                                    if (part >= 3) { contacts[i][part - 3] = this; isVisible = false }
                                },
                            )
                        }
                        if (kind == FeelMaterial.Foam) {
                            Node(apply = { mark[0] = this; isTouchable = false }) {
                                // logo.svg: cube width 268, frame width 320, brackets 38 long / 8 thick.
                                val unit = 0.9f / 320f
                                val cubeSide = 268f * unit / 1.4142136f
                                CubeNode(size = Size(cubeSide), position = Position(y = cubeSide / 2f),
                                    rotation = Rotation(y = 45f), materialInstance = markMaterial,
                                    apply = { isTouchable = false })
                                Node(position = Position(y = 174f * unit * MatterStage.COS),
                                    rotation = Rotation(x = -MatterStage.ELEVATION)) {
                                    for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) {
                                        CubeNode(size = Size(38f * unit, 8f * unit, 8f * unit),
                                            position = Position(sx * 141f * unit, sy * 174f * unit, 0f),
                                            materialInstance = bracketMaterial, apply = { isTouchable = false })
                                        CubeNode(size = Size(8f * unit, 38f * unit, 8f * unit),
                                            position = Position(sx * 160f * unit, sy * 155f * unit, 0f),
                                            materialInstance = bracketMaterial, apply = { isTouchable = false })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxSize()) {
                FeelMaterial.entries.forEach { kind ->
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        FeelCaption(kind, readout, Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .onSizeChanged { stage.captionPx[kind.ordinal] = it.height })
                    }
                }
            }
        }
    }
}

private data class FeelReadout(val foamDepth: Float = 0f, val pressingFoam: Boolean = false, val showHint: Boolean = false)

@Composable
private fun FeelCaption(kind: FeelMaterial, readout: FeelReadout, modifier: Modifier) {
    val depth by animateFloatAsState(readout.foamDepth, motionFade(), label = "foam indentation")
    val name = stringResource(when (kind) {
        FeelMaterial.Foam -> R.string.demo_feel_foam
        FeelMaterial.Oak -> R.string.demo_feel_oak
        FeelMaterial.Steel -> R.string.demo_feel_steel
    })
    val description = when (kind) {
        FeelMaterial.Foam -> when {
            depth < 0.5f -> stringResource(R.string.demo_feel_foam_rest)
            readout.pressingFoam -> stringResource(R.string.demo_feel_foam_press, depth.roundToInt())
            else -> stringResource(R.string.demo_feel_foam_recovery, depth.roundToInt())
        }
        FeelMaterial.Oak -> stringResource(R.string.demo_feel_oak_response)
        FeelMaterial.Steel -> stringResource(R.string.demo_feel_steel_response)
    }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(Modifier.padding(horizontal = SceneViewTokens.Space.md, vertical = SceneViewTokens.Space.xs)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            // A full-width row fits at 360 dp. At large font scales it grows instead of clipping.
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Capture one pointer, reproject against the deformed surface, keep real travel separate. */
private class FeelTouch(
    private val simulation: FeelSimulation,
    private val view: com.google.android.filament.View,
    private val haptics: FeelHapticPlayer,
    private val stage: MatterStage,
) {
    private var pointerId = -1
    private var screenX = 0f
    private var screenY = 0f

    fun onEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                screenX = event.x; screenY = event.y
                for (kind in FeelMaterial.entries) {
                    val point = pointOnPad(kind, deformed = false) ?: continue
                    if (!FeelSimulation.contains(point.first, point.second)) continue
                    pointerId = event.getPointerId(0)
                    simulation.begin(kind, point.first, point.second, event.pressure, event.size, event.eventTime)
                    simulation.advance(event.eventTime, startOpening = false)?.let(haptics::play)
                    break
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                val active = simulation.active
                if (index < 0 || active == null) { cancel(); return true }
                for (history in 0 until event.historySize) {
                    screenX = event.getHistoricalX(index, history)
                    screenY = event.getHistoricalY(index, history)
                    pointOnPad(active, deformed = false)?.let {
                        simulation.move(it.first, it.second, event.getHistoricalPressure(index, history), event.getHistoricalSize(index, history))
                    }
                }
                screenX = event.getX(index); screenY = event.getY(index)
                val point = pointOnPad(active, deformed = false)
                if (point == null || !FeelSimulation.contains(point.first, point.second)) { cancel(); return true }
                simulation.move(point.first, point.second, event.getPressure(index), event.getSize(index))
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointerId) cancel(released = true)
            MotionEvent.ACTION_UP -> cancel(released = true)
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
        if (released && pointerId != -1) haptics.release() else haptics.cancel()
        pointerId = -1
        simulation.release(SystemClock.uptimeMillis())
    }

    private fun pointOnPad(kind: FeelMaterial, deformed: Boolean): Pair<Float, Float>? {
        if (view.viewport.width <= 0 || view.viewport.height <= 0) return null
        val ray = view.screenToRay(screenX, screenY) ?: return null
        if (ray.direction.y >= -0.0001f) return null
        val i = kind.ordinal
        val scale = stage.scale[i]
        val height = stage.y[i] - if (deformed) simulation.pads[i].depthMm * FeelMaterial.WORLD_PER_MM * scale else 0f
        val t = (height - ray.origin.y) / ray.direction.y
        if (t < 0f) return null
        return (ray.origin.x + ray.direction.x * t) / scale to
            (ray.origin.z + ray.direction.z * t - stage.z[i]) / scale
    }
}
