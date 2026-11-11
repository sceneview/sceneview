package io.github.sceneview.demo.demos

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Skybox
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.LocalDemoSceneCover
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.rememberFilmicView
import io.github.sceneview.demo.demos.internal.CosmosSpacetime
import io.github.sceneview.demo.demos.internal.FeelHapticPlayer
import io.github.sceneview.demo.demos.internal.GpuSheet
import io.github.sceneview.demo.demos.internal.bindHorizon
import io.github.sceneview.demo.demos.internal.feel.FeelMaterial
import io.github.sceneview.demo.demos.internal.feel.FeelSimulation
import io.github.sceneview.demo.demos.internal.feel.PressureSource
import io.github.sceneview.demo.demos.internal.spacetimeRectangle
import io.github.sceneview.demo.demos.internal.stagedSheet
import io.github.sceneview.demo.demos.internal.uploadSheet
import io.github.sceneview.demo.recording.rememberDemoSceneRecorder
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.viewer.ViewerBackdrop
import io.github.sceneview.environment.Environment
import io.github.sceneview.math.Position
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.utils.screenToRay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.sqrt

/** A tactile material comparison. All geometry and materials are local; no model download. */
@Composable
fun FeelDemo(onBack: () -> Unit) {
    val title = stringResource(R.string.demo_feel_title)
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = title, onBack = onBack)
        return
    }
    val engine = rememberEngine()
    val loader = rememberMaterialLoader(engine)
    val view = rememberFilmicView(engine)
    val camera = rememberCameraNode(engine)
    val recorder = rememberDemoSceneRecorder()
    val firstFrame = rememberFirstFrameState(engine)
    val simulation = remember { FeelSimulation() }
    val touchView = LocalView.current
    val haptics = remember(touchView) { FeelHapticPlayer(touchView) }
    val touch = remember(simulation, view, haptics) { FeelTouch(simulation, view, haptics) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, touch) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) touch.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); touch.cancel() }
    }

    val colors = MaterialTheme.colorScheme
    val background = colors.surface
    val skybox = remember(engine, background) {
        val c = ViewerBackdrop.linearBeforeFilmic(background)
        Skybox.Builder().color(c[0], c[1], c[2], 1f).build(engine)
    }
    DisposableEffect(engine, skybox) { onDispose { engine.destroySkybox(skybox) } }
    val environment = remember(skybox) { Environment(skybox = skybox) }
    val material by produceState<Material?>(null, loader) {
        value = loader.loadMaterial("materials/cosmos_spacetime.filamat")
        if (value == null) firstFrame.reportContentFailed()
    }
    val sheets = remember(loader, material) {
        material?.let { base -> List(4) { loader.createInstance(base) } }
    }
    val padMesh by produceState<GpuSheet?>(null, engine) {
        val staged = withContext(Dispatchers.Default) {
            spacetimeRectangle(FeelSimulation.PAD_HALF_WIDTH, FeelSimulation.PAD_HALF_LENGTH)
                .stagedSheet(false, FeelSimulation.PAD_HALF_WIDTH, FeelSimulation.PAD_HALF_LENGTH)
        }
        value = staged.uploadSheet(engine)
    }
    val groundMesh by produceState<GpuSheet?>(null, engine) {
        val staged = withContext(Dispatchers.Default) {
            spacetimeRectangle(4.6f, 2.3f, columns = 144).stagedSheet(false, 4.6f, 2.3f)
        }
        value = staged.uploadSheet(engine)
    }
    DisposableEffect(engine, padMesh) {
        val pad = padMesh
        onDispose { pad?.destroy(engine) }
    }
    DisposableEffect(engine, groundMesh) {
        val ground = groundMesh
        onDispose { ground?.destroy(engine) }
    }
    val field = remember { FeelField() }
    // Dark, themed material surfaces keep the existing additive grid legible in either theme.
    val dark = colors.surface.luminance() < 0.5f
    val base = if (dark) colors.surfaceContainer else colors.inverseSurface
    DisposableEffect(sheets, padMesh, groundMesh, colors) {
        sheets?.forEachIndexed { i, sheet ->
            val mesh = if (i == 3) groundMesh else padMesh
            mesh?.let { sheet.bindHorizon(it.horizon) }
            sheet.setParameter("lightDir", CosmosSpacetime.LIGHT[0], CosmosSpacetime.LIGHT[1], CosmosSpacetime.LIGHT[2])
            sheet.setFeelColor("baseColor", base)
            sheet.setFeelColor("gridColor", when (i) {
                0 -> if (dark) colors.primary else colors.inversePrimary
                1 -> if (dark) colors.tertiary else colors.tertiaryContainer
                2 -> if (dark) colors.onSurfaceVariant else colors.inverseOnSurface
                else -> colors.outlineVariant
            })
            sheet.setParameter("well", 0f) // No star or baked star shadow in Feel.
            sheet.setParameter("offset", if (i == 3) -0.035f else 0f)
            sheet.setParameter("intensity", if (i == 3) 0.3f else 1f)
            sheet.setParameter("ringCenter", 0f, 0f, 0f, 0f)
            sheet.setParameter("ringNormal", 0f, 1f, 0f, 0f)
            sheet.setParameter("spheres", MaterialInstance.FloatElement.FLOAT4, field.noSpheres, 0, 8)
        }
        sheets?.let { field.update(simulation, it) }
        onDispose { }
    }
    val ready = sheets != null && padMesh != null && groundMesh != null
    firstFrame.holdUntil(ready, "material pads")
    var readout by remember { mutableStateOf(FeelReadout()) }
    val timing = remember { longArrayOf(0L) }
    val framing = remember { doubleArrayOf(0.0) }

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
        bottomOverlay = { FeelReadouts(readout) },
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            materialLoader = loader,
            view = view,
            cameraNode = camera,
            cameraManipulator = null,
            autoCenterContent = false,
            contentPadding = LocalDemoSceneCover.current,
            environment = environment,
            mainLightNode = null,
            fillLightNode = null,
            surfaceMirrorer = recorder.surfaceMirrorer,
            frameRatePolicy = FrameRatePolicy.Continuous(),
            onTouchEvent = { event, _ -> if (ready) touch.onEvent(event) else true },
            onFrame = { nanos ->
                val aspect = camera.getViewPortAspect()
                if (aspect != framing[0]) {
                    framing[0] = aspect
                    // 28 mm lens, 24 mm sensor height. Keep all three pads in the free stage.
                    val distance = maxOf(9.0, 5.2 / (12.0 / 28.0 * aspect)).toFloat()
                    camera.position = Position(0f, distance * 0.819152f, distance * 0.573576f)
                    camera.lookAt(Position(0f, -0.25f, 0f))
                }
                val now = SystemClock.uptimeMillis() // Same time base as MotionEvent.eventTime.
                if (!touchView.hasWindowFocus()) touch.cancel()
                simulation.advance(now)?.let(haptics::play)
                touch.followIndentation()
                sheets?.let { field.update(simulation, it) }
                // Readout at 10 Hz; simulation and uniform uploads still follow every frame.
                if (now - timing[0] >= 100L) {
                    timing[0] = now
                    readout = FeelReadout(
                        simulation.selected,
                        simulation.pads.map { it.depthMm },
                        simulation.active != null,
                        simulation.inferred.source,
                    )
                }
                firstFrame.onFrame(nanos)
            },
        ) {
            val instances = sheets
            if (ready && instances != null) {
                groundMesh?.let { ground ->
                    MeshNode(
                        primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
                        vertexBuffer = ground.mesh.vertexBuffer,
                        indexBuffer = ground.mesh.indexBuffer,
                        boundingBox = ground.mesh.box,
                        materialInstance = instances[3],
                        apply = { isShadowCaster = false; isShadowReceiver = false; isTouchable = false },
                    )
                }
                padMesh?.let { pad ->
                    FeelMaterial.entries.forEach { materialKind ->
                        androidx.compose.runtime.key(materialKind) {
                            MeshNode(
                                primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
                                vertexBuffer = pad.mesh.vertexBuffer,
                                indexBuffer = pad.mesh.indexBuffer,
                                boundingBox = pad.mesh.box,
                                materialInstance = instances[materialKind.ordinal],
                                apply = {
                                    position = Position(x = FeelSimulation.centerX(materialKind))
                                    isShadowCaster = false
                                    isShadowReceiver = false
                                    isTouchable = false // Analytic sheet picking, not its undeformed bounds.
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class FeelReadout(
    val selected: FeelMaterial = FeelMaterial.Foam,
    val depths: List<Float> = listOf(0f, 0f, 0f),
    val pressing: Boolean = false,
    val source: PressureSource = PressureSource.Duration,
)

@Composable
private fun FeelReadouts(readout: FeelReadout) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(SceneViewTokens.Space.md),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
        ) {
            Text(stringResource(R.string.demo_feel_instruction), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
                FeelMaterial.entries.forEach { material ->
                    Column(Modifier.weight(1f)) {
                        Text(
                            material.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (readout.pressing && readout.selected == material) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            String.format(Locale.US, "%.1f mm / %.2f N/mm", readout.depths[material.ordinal], material.stiffness),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                stringResource(if (readout.pressing && readout.selected != FeelMaterial.Steel &&
                    readout.source == PressureSource.Duration
                ) {
                    R.string.demo_feel_hold
                } else R.string.demo_feel_comparison),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun MaterialInstance.setFeelColor(parameter: String, color: Color) {
    val linear = color.convert(ColorSpaces.LinearSrgb)
    setParameter(parameter, linear.red, linear.green, linear.blue)
}

/** Same Plummer wells as Cosmos, normalized so the well centre equals the simulated mm. */
private class FeelField {
    val noSpheres = FloatArray(32)
    private val bodies = Array(4) { FloatArray(32).apply { for (i in 0..7) this[i * 4 + 3] = 1f } }

    fun update(simulation: FeelSimulation, sheets: List<MaterialInstance>) {
        FeelMaterial.entries.forEach { material ->
            val i = material.ordinal
            val width = material.radius
            val depth = simulation.pads[i].depthMm * FeelMaterial.WORLD_PER_MM /
                (1f - width / sqrt(144f + width * width))
            val local = bodies[i]
            local[0] = simulation.fingerX[i]
            local[1] = simulation.fingerZ[i]
            local[2] = depth
            local[3] = width
            val ground = bodies[3]
            ground[i * 4] = local[0] + FeelSimulation.centerX(material)
            ground[i * 4 + 1] = local[1]
            ground[i * 4 + 2] = depth
            ground[i * 4 + 3] = width
        }
        sheets.forEachIndexed { i, sheet ->
            sheet.setParameter("bodies", MaterialInstance.FloatElement.FLOAT4, bodies[i], 0, 8)
        }
    }
}

/** Capture the first pointer until UP/CANCEL; a second finger never changes material or force. */
private class FeelTouch(
    private val simulation: FeelSimulation,
    private val view: com.google.android.filament.View,
    private val haptics: FeelHapticPlayer,
) {
    private var pointerId = -1
    private var screenX = 0f
    private var screenY = 0f
    private var pressure = 0f
    private var size = 0f

    fun onEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                screenX = event.x; screenY = event.y
                val point = pointAtHeight(0f) ?: return true
                val material = FeelSimulation.padAt(point.first, point.second) ?: return true
                pointerId = event.getPointerId(0)
                pressure = event.pressure; size = event.size
                simulation.begin(material, point.first - FeelSimulation.centerX(material), point.second, pressure, size, event.eventTime)
                simulation.advance(event.eventTime)?.let(haptics::play) // Steel responds on DOWN.
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) { cancel(); return true }
                screenX = event.getX(index); screenY = event.getY(index)
                // Historical MOVE samples count too; stationary holds are advanced by frame time.
                for (history in 0 until event.historySize) {
                    simulation.move(
                        simulation.fingerX[simulation.selected.ordinal], simulation.fingerZ[simulation.selected.ordinal],
                        event.getHistoricalPressure(index, history), event.getHistoricalSize(index, history),
                    )
                }
                pressure = event.getPressure(index); size = event.getSize(index)
                val point = pointAtHeight(0f)
                val active = simulation.active
                if (point == null || active == null ||
                    FeelSimulation.padAt(point.first, point.second) != active
                ) { cancel(); return true }
                simulation.move(point.first - FeelSimulation.centerX(active), point.second, pressure, size)
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointerId) cancel()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> cancel()
        }
        return true
    }

    fun followIndentation() {
        val material = simulation.active ?: return
        val point = pointAtHeight(-simulation.pads[material.ordinal].depthMm * FeelMaterial.WORLD_PER_MM) ?: return
        // Reproject the stationary finger onto the depressed surface without inventing sensor samples.
        simulation.fingerX[material.ordinal] = (point.first - FeelSimulation.centerX(material))
            .coerceIn(-FeelSimulation.PAD_HALF_WIDTH, FeelSimulation.PAD_HALF_WIDTH)
        simulation.fingerZ[material.ordinal] = point.second.coerceIn(-FeelSimulation.PAD_HALF_LENGTH, FeelSimulation.PAD_HALF_LENGTH)
    }

    fun cancel() {
        if (pointerId != -1) haptics.cancel()
        pointerId = -1
        simulation.release(SystemClock.uptimeMillis())
    }

    private fun pointAtHeight(y: Float): Pair<Float, Float>? {
        if (view.viewport.width <= 0 || view.viewport.height <= 0) return null
        val ray = view.screenToRay(screenX, screenY) ?: return null
        val origin = ray.getOrigin()
        val direction = ray.getDirection()
        if (direction.y >= -0.0001f) return null
        val t = (y - origin.y) / direction.y
        if (t < 0f) return null
        return (origin.x + direction.x * t) to (origin.z + direction.z * t)
    }
}
