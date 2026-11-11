package io.github.sceneview.demo.demos

import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.Camera
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Skybox
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.EngineDestroyQueue
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.LocalDemoSceneCover
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.rememberFilmicView
import io.github.sceneview.demo.demos.internal.FeelHapticPlayer
import io.github.sceneview.demo.demos.internal.bindHorizon
import io.github.sceneview.demo.demos.internal.createMatterHorizon
import io.github.sceneview.demo.demos.internal.MatterMesh
import io.github.sceneview.demo.demos.internal.feel.FeelMaterial
import io.github.sceneview.demo.demos.internal.feel.FeelSimulation
import io.github.sceneview.demo.demos.internal.feel.MatterGeometry
import io.github.sceneview.demo.demos.internal.feel.MatterOpening
import io.github.sceneview.demo.demos.internal.feel.MatterMeshes
import io.github.sceneview.demo.demos.internal.feel.MatterStage
import io.github.sceneview.demo.recording.rememberDemoSceneRecorder
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.motionFade
import io.github.sceneview.demo.ui.viewer.ViewerBackdrop
import io.github.sceneview.environment.Environment
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.math.Transform
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.utils.screenToRay
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.sin

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
    // The existing Cosmos lattice encodes inverseFilmic; use its matching tone mapper.
    val view = rememberFilmicView(engine)
    val camera = rememberCameraNode(engine)
    val light = rememberMainLightNode(engine) { lightDirection = Direction(-0.3f, -1f, -0.8f) }
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
        val c = ViewerBackdrop.linearBeforeFilmic(colors.surface)
        Skybox.Builder().color(c[0], c[1], c[2], 1f).build(engine)
    }
    DisposableEffect(engine, skybox) { onDispose { engine.destroySkybox(skybox) } }
    val environment = remember(skybox) { Environment(skybox = skybox) }
    val meshes = remember(engine) {
        Array(3) { listOf(MatterMeshes.top(), MatterMeshes.sides(), MatterMeshes.sides(ends = true),
            MatterMeshes.lattice(), MatterMeshes.contact(true), MatterMeshes.contact(false)).mapIndexed { part, geometry ->
            MatterMesh(engine, geometry, if (part == MatterMeshes.TOP) MatterMeshes.SHADER_XZ_SCALE else 1f)
        } }
    }
    DisposableEffect(meshes) { onDispose { meshes.forEach { row -> row.forEach(MatterMesh::destroy) } } }
    val latticeMaterial = remember(loader) { loader.createMaterial("materials/cosmos_spacetime.filamat") }
    val horizon = remember(engine) { createMatterHorizon(engine) }
    DisposableEffect(engine, horizon) {
        onDispose { EngineDestroyQueue.of(engine).enqueueTexture(horizon) }
    }
    val materials = remember(loader, colors, latticeMaterial, horizon) {
        val roles = listOf(
            listOf(colors.primaryFixedDim, colors.primaryContainer, colors.onPrimaryFixedVariant,
                colors.onPrimaryContainer, colors.onPrimaryFixed, colors.onPrimaryFixed),
            listOf(colors.tertiaryFixedDim, colors.tertiaryContainer, colors.onTertiaryFixedVariant,
                colors.onTertiaryContainer, colors.onTertiaryFixed, colors.onTertiaryFixed),
            listOf(colors.surfaceContainerHighest, colors.surfaceContainerLow, colors.surfaceDim,
                colors.outline, colors.primary, colors.primary),
        )
        roles.map { row -> row.mapIndexed { part, color ->
            if (part == MatterMeshes.TOP) loader.createInstance(latticeMaterial).apply {
                // CPU-deformed top, finite inactive wells. No shader change or new .filamat.
                val bodies = FloatArray(32) { if (it % 4 == 3) 1f else 0f }
                setParameter("bodies", MaterialInstance.FloatElement.FLOAT4, bodies, 0, 8)
                setParameter("spheres", MaterialInstance.FloatElement.FLOAT4, FloatArray(32), 0, 8)
                setParameter("ringCenter", 0f, 0f, 0f, 0f)
                setParameter("ringNormal", 0f, 1f, 0f, 0f)
                setParameter("offset", 0f)
                setParameter("well", 0f)
                bindHorizon(horizon)
                // Overhead light saturates the shader's 1.25 lighting ceiling everywhere in
                // our unfaded patch. Compensate it to show the actual M3 role on the flat top.
                setParameter("lightDir", 0f, 1f, 0f)
                val base = color.convert(ColorSpaces.LinearSrgb)
                val ink = row[MatterMeshes.CONTACT].convert(ColorSpaces.LinearSrgb)
                setParameter("baseColor", base.red / 1.25f, base.green / 1.25f, base.blue / 1.25f)
                // The shader adds its grid; a signed difference also permits dark themed ink.
                setParameter("gridColor", (ink.red - base.red) / 1.632f,
                    (ink.green - base.green) / 1.632f, (ink.blue - base.blue) / 1.632f)
                setParameter("intensity", 1f)
            } else {
                val alpha = when (part) {
                    MatterMeshes.CONTACT -> 0.06f
                    MatterMeshes.RING -> 0.99f
                    else -> 1f
                }
                loader.createUnlitColorInstance(color.copy(alpha = alpha)).apply {
                    val c = ViewerBackdrop.linearBeforeFilmic(color)
                    setParameter("color", c[0], c[1], c[2], alpha)
                }
            }
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
    val materialsNow by rememberUpdatedState(materials)
    val ringColorNow by rememberUpdatedState(ViewerBackdrop.linearBeforeFilmic(colors.primary))
    val ringWidthNow by rememberUpdatedState(with(LocalDensity.current) { SceneViewTokens.Layout.hairlineWidth.toPx() })
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
        dockHidden = true,
    ) {
        // Physically inset this viewport once. The labels use precisely the same free area.
        val hintAlpha by animateFloatAsState(if (readout.showHint) 1f else 0f, motionFade(), label = "touch hint")
        Box(Modifier.fillMaxSize().background(colors.surface).padding(LocalDemoSceneCover.current)) {
            Box(Modifier.fillMaxSize().padding(bottom = SceneViewTokens.Space.lg).onSizeChanged {
                stage.widthPx = it.width
                stage.heightPx = it.height
            }) {
                SceneView(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    materialLoader = loader,
                    view = view,
                    cameraNode = camera,
                    mainLightNode = light,
                    cameraManipulator = null,
                    autoCenterContent = false,
                    environment = environment,
                    surfaceMirrorer = recorder.surfaceMirrorer,
                    frameRatePolicy = FrameRatePolicy.Continuous(),
                    // SceneView retains the FIRST callback: never close over a readiness Boolean.
                    onTouchEvent = { event, _ -> touchNow.onEvent(event) },
                    onFrame = { nanos ->
                        // Surface resize can reset CameraNode's lens AFTER Compose measures.
                        // Reapply the explicit perspective before both rendering and picking.
                        camera.setProjection(MatterStage.HORIZONTAL_FOV,
                            stage.widthPx.toDouble() / stage.heightPx.coerceAtLeast(1),
                            MatterStage.NEAR, MatterStage.FAR, Camera.Fov.HORIZONTAL)
                        if (stage.frame()) {
                            camera.position = Position(MatterStage.DISTANCE * MatterStage.BACK_X,
                                MatterStage.DISTANCE * MatterStage.SIN, MatterStage.DISTANCE * MatterStage.BACK_Z)
                            camera.lookAt(Position())
                            for (i in 0..2) pads[i]?.transform = Transform(
                                Position(stage.x[i], stage.y[i], stage.z[i]),
                                quaternion = Quaternion(MatterStage.COS_AZIMUTH * sin(stage.tilt[i] / 2f), 0f,
                                    -MatterStage.SIN_AZIMUTH * sin(stage.tilt[i] / 2f), cos(stage.tilt[i] / 2f)),
                                scale = Scale(stage.scale[i]),
                            )
                        }
                        val now = SystemClock.uptimeMillis()
                        if (!motionNow) simulation.opening.cancel()
                        if (!touchView.hasWindowFocus()) touchNow.cancel()
                        simulation.advance(now, startOpening = firstFrame.sceneReady.value)?.let(haptics::play)
                        touchNow.followIndentation()
                        FeelMaterial.entries.forEach { kind ->
                            val i = kind.ordinal
                            val depth = simulation.pads[i].depthMm
                            val x = if (kind == FeelMaterial.Steel) simulation.contactX[i] else simulation.fingerX[i]
                            val z = if (kind == FeelMaterial.Steel) simulation.contactZ[i] else simulation.fingerZ[i]
                            val previous = lastDeformation[i]
                            if (depth != previous[0] || x != previous[1] || z != previous[2]) {
                                for (part in MatterMeshes.TOP..MatterMeshes.LATTICE) {
                                    val mesh = meshesNow[i][part]
                                    mesh.geometry.deform(depth, x, z, kind)
                                    mesh.upload()
                                }
                                previous[0] = depth; previous[1] = x; previous[2] = z
                            }
                            val touching = simulation.active == kind
                            val age = if (simulation.contactMillis[i] == Long.MIN_VALUE) Long.MAX_VALUE else now - simulation.contactMillis[i]
                            val ripple = kind == FeelMaterial.Steel && motionNow && age in 0L until MatterMeshes.RIPPLE_MILLIS
                            contacts[i][0]?.isVisible = touching && kind != FeelMaterial.Steel
                            contacts[i][1]?.isVisible = if (kind == FeelMaterial.Steel) ripple else touching
                            if (touching || ripple) {
                                val contactRadius = 0.12f + simulation.inferred.value * 0.10f
                                MatterMeshes.updateContact(meshesNow[i][MatterMeshes.CONTACT].geometry, contactRadius, x, z, depth, kind)
                                MatterMeshes.updateContact(meshesNow[i][MatterMeshes.RING].geometry,
                                    if (ripple) MatterMeshes.rippleRadius(age) else contactRadius, x, z, depth, kind,
                                    strokeWidth = ringWidthNow * stage.halfWidth * 2f /
                                        stage.widthPx.coerceAtLeast(1) / stage.scale[i])
                                if (ripple) {
                                    val c = ringColorNow
                                    materialsNow[i][MatterMeshes.RING].setParameter("color", c[0], c[1], c[2], MatterMeshes.rippleAlpha(age))
                                }
                                meshesNow[i][MatterMeshes.CONTACT].upload()
                                meshesNow[i][MatterMeshes.RING].upload()
                            }
                        }
                        mark[0]?.apply {
                            isVisible = simulation.opening.visible
                            if (isVisible) transform = Transform(Position(y =
                                -MatterGeometry.visualDepth(simulation.pads[0].depthMm, FeelMaterial.Foam) + simulation.opening.heightAbovePad))
                        }
                        if (now - timing[0] >= 50L) {
                            timing[0] = now
                            readout = FeelReadout(simulation.pads[0].depthMm, simulation.active == FeelMaterial.Foam,
                                simulation.opening.finished && !simulation.hasPressed)
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
                                        if (part == MatterMeshes.TOP) scale = Scale(
                                            1f / MatterMeshes.SHADER_XZ_SCALE, 1f, 1f / MatterMeshes.SHADER_XZ_SCALE)
                                        if (part >= MatterMeshes.CONTACT) {
                                            contacts[i][part - MatterMeshes.CONTACT] = this; isVisible = false
                                        }
                                    },
                                )
                            }
                            if (kind == FeelMaterial.Foam) {
                                Node(apply = { mark[0] = this; isTouchable = false }) {
                                    // logo.svg: cube width 268, frame width 320, brackets 38 long / 8 thick.
                                    val unit = MatterOpening.MARK_WIDTH / 268f
                                    val cubeSide = 268f * unit / 1.4142136f
                                    CubeNode(size = Size(cubeSide), position = Position(y = cubeSide / 2f),
                                        rotation = Rotation(y = 45f), materialInstance = markMaterial,
                                        apply = { isTouchable = false })
                                    if (MatterOpening.SHOW_BRACKETS) Node(position = Position(y = 174f * unit * MatterStage.COS),
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
                // Run after SceneView's default quality effect: inverse-Filmic light colours
                // are HDR values, but product silhouettes must not acquire a bloom halo.
                LaunchedEffect(view) {
                    view.bloomOptions = view.bloomOptions.apply { enabled = false }
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
            Text(
                stringResource(R.string.demo_feel_instruction),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = SceneViewTokens.Space.xs).alpha(hintAlpha),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface,
            )
        }
    }
}

private data class FeelReadout(val foamDepth: Float = 0f, val pressingFoam: Boolean = false, val showHint: Boolean = false)

@OptIn(ExperimentalLayoutApi::class)
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
        FlowRow(
            modifier = Modifier.padding(horizontal = SceneViewTokens.Space.md, vertical = SceneViewTokens.Space.xs),
            horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            // One compact line at normal font size; wrap and remeasure at accessibility sizes.
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        return stage.pointOnPad(kind.ordinal, ray.origin.x, ray.origin.y, ray.origin.z,
            ray.direction.x, ray.direction.y, ray.direction.z,
            if (deformed) MatterGeometry.visualDepth(simulation.pads[kind.ordinal].depthMm, kind) else 0f)
    }
}
