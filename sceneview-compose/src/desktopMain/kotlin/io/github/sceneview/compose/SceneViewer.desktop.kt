package io.github.sceneview.compose

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import dev.romainguy.kotlin.math.Float3
import io.github.erkko68.filament.View
import io.github.erkko68.filament.compose.FilamentSceneView
import io.github.erkko68.filament.compose.FilamentViewState
import io.github.erkko68.filament.compose.OnFrame
import io.github.erkko68.filament.compose.rememberFilamentEngine
import io.github.erkko68.filament.compose.rememberFilamentViewState
import io.github.erkko68.filament.compose.scene.Direction
import io.github.erkko68.filament.compose.scene.DirectionalLight
import io.github.erkko68.filament.compose.scene.GltfInstance
import io.github.erkko68.filament.compose.scene.IndirectLightState
import io.github.erkko68.filament.compose.scene.LightIntensity
import io.github.erkko68.filament.compose.scene.LinearColor
import io.github.erkko68.filament.compose.scene.Position
import io.github.erkko68.filament.compose.scene.ShadowConfig
import io.github.erkko68.filament.compose.scene.Shadows
import io.github.erkko68.filament.compose.scene.SkyboxSource
import io.github.erkko68.filament.compose.scene.SkyboxState
import io.github.erkko68.filament.compose.scene.rememberCameraState as rememberFilamentCameraState
import io.github.erkko68.filament.compose.scene.rememberGltfAsset
import io.github.erkko68.filament.compose.scene.rememberHDREnvironment
import io.github.erkko68.filament.compose.scene.rememberSkyboxState
import io.github.erkko68.filament.utils.Float4
import io.github.erkko68.filament.utils.inverse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.compose.scene.CameraState as FilamentCameraState

/**
 * Desktop implementation: Filament through filament-kmp (offscreen readback → Skia).
 *
 * filament-kmp types stay `implementation`. Nothing in this file is public except the
 * [SceneViewer] actual, whose signature is the common expect.
 */
@Composable
public actual fun SceneViewer(
    model: ModelSource,
    modifier: Modifier,
    camera: CameraState,
    lighting: Lighting,
    environment: EnvironmentSource,
    onTap: ((ModelHit?) -> Unit)?,
    onFrame: ((frameTimeNanos: Long) -> Unit)?,
    onError: ((SceneViewerError) -> Unit)?,
) {
    val engine = rememberFilamentEngine()
    val viewState = rememberFilamentViewState()
    val filamentCamera = rememberFilamentCameraState()
    val currentOnError by rememberUpdatedState(onError)
    val currentOnTap = rememberUpdatedState(onTap)
    val currentOnFrame by rememberUpdatedState(onFrame)
    val scope = rememberCoroutineScope()

    applyOrbit(camera, filamentCamera)

    val gltf = rememberGltfAsset(
        key = model,
        engine = engine,
        onError = { report(model.loadDescription(), it, currentOnError) },
    ) { loadModelBytes(model) }

    val desktopEnvironment = rememberDesktopEnvironment(
        engine = engine,
        source = environment,
        lighting = lighting,
        onError = currentOnError,
    )

    FilamentSceneView(
        modifier = modifier.viewerInput(
            camera = camera,
            filamentCamera = filamentCamera,
            viewState = viewState,
            onTap = currentOnTap.value,
            dispatchTap = { hit ->
                // scope is Compose's main context (rememberCoroutineScope); no explicit
                // dispatcher, so desktop does not require kotlinx-coroutines-swing.
                scope.launch { currentOnTap.value?.invoke(hit) }
            },
        ),
        engine = engine,
        cameraState = filamentCamera,
        viewState = viewState,
        skyboxState = desktopEnvironment.skyboxState,
        indirectLightState = desktopEnvironment.indirectLightState,
        shadows = if (lighting.castShadows) Shadows.Pcf else null,
    ) {
        DirectionalLight(
            direction = Direction(lighting.direction.x, lighting.direction.y, lighting.direction.z),
            intensity = LightIntensity.LuminousPower(lighting.intensity),
            shadow = if (lighting.castShadows) ShadowConfig() else null,
        )
        GltfInstance(asset = gltf)
        currentOnFrame?.let { frame ->
            OnFrame { info -> frame(info.frameTimeNanos) }
        }
    }
}

@Composable
private fun rememberDesktopEnvironment(
    engine: Engine,
    source: EnvironmentSource,
    lighting: Lighting,
    onError: ((SceneViewerError) -> Unit)?,
): DesktopEnvironment {
    val skyboxState = rememberSkyboxState()
    val hdr = if (source is EnvironmentSource.Hdr) {
        rememberHDREnvironment(
            initialIntensity = DEFAULT_IBL_INTENSITY,
            showSkybox = source.showSkybox,
            key = source.path,
            onError = { report("loading HDR environment '${source.path}'", it, onError) },
            engine = engine,
        ) { DesktopAssets.bytes(source.path) }
    } else {
        null
    }

    if (hdr != null) {
        val authoredAmbient = remember(hdr) { hdr.indirectLightState.intensity }
        SideEffect {
            hdr.indirectLightState.intensity = authoredAmbient * lighting.ambientIntensity
        }
        return DesktopEnvironment(hdr.skyboxState, hdr.indirectLightState)
    }

    SideEffect { skyboxState.source = source.toSkyboxSource() }
    return DesktopEnvironment(skyboxState, indirectLightState = null)
}

private class DesktopEnvironment(
    val skyboxState: SkyboxState?,
    val indirectLightState: IndirectLightState?,
)

@Composable
private fun applyOrbit(camera: CameraState, filament: FilamentCameraState) {
    val target = camera.target
    val distance = camera.distance
    val azimuth = camera.azimuth
    val elevation = camera.elevation
    SideEffect {
        val eye = orbitEyePosition(target, distance, azimuth, elevation)
        filament.eye = Position(eye.x, eye.y, eye.z)
        filament.target = Position(target.x, target.y, target.z)
    }
}

private fun Modifier.viewerInput(
    camera: CameraState,
    filamentCamera: FilamentCameraState,
    viewState: FilamentViewState,
    onTap: ((ModelHit?) -> Unit)?,
    dispatchTap: (ModelHit?) -> Unit,
): Modifier {
    var modifier = this
    if (camera.gesturesEnabled) {
        modifier = modifier
            .pointerInput(camera) {
                detectDragGestures { _, drag ->
                    camera.azimuth -= drag.x * ORBIT_DEGREES_PER_PIXEL
                    camera.elevation += drag.y * ORBIT_DEGREES_PER_PIXEL
                    pushOrbit(camera, filamentCamera)
                }
            }
            .pointerInput(camera) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val scroll = event.changes.firstOrNull()?.scrollDelta ?: continue
                        if (scroll.y != 0f) {
                            camera.distance *= 1f + scroll.y * SCROLL_ZOOM
                            pushOrbit(camera, filamentCamera)
                        }
                    }
                }
            }
    }
    if (onTap != null) {
        modifier = modifier.pointerInput(viewState) {
            detectTapGestures { offset ->
                viewState.pick(offset.x.toInt(), offset.y.toInt()) { result ->
                    dispatchTap(modelHit(result, filamentCamera, viewState.view))
                }
            }
        }
    }
    return modifier
}

private fun pushOrbit(camera: CameraState, filament: FilamentCameraState) {
    val eye = orbitEyePosition(camera.target, camera.distance, camera.azimuth, camera.elevation)
    filament.eye = Position(eye.x, eye.y, eye.z)
    filament.target = Position(camera.target.x, camera.target.y, camera.target.z)
}

/**
 * World hit from Filament color-picking. [View.PickingQueryResult.fragCoords] are the
 * coords passed to `View.pick` (bottom-left origin, already flipped by [FilamentViewState.pick]);
 * [View.PickingQueryResult.depth] is window depth in `0..1`.
 */
private fun modelHit(
    result: View.PickingQueryResult,
    camera: FilamentCameraState,
    view: View?,
): ModelHit? {
    if (result.renderable == 0) return null
    val world = unprojectPick(result, camera, view) ?: return ModelHit(
        position = Float3(camera.target.x, camera.target.y, camera.target.z),
        distance = length(camera.eye, camera.target),
    )
    return ModelHit(position = world, distance = length(camera.eye, world))
}

private fun unprojectPick(
    result: View.PickingQueryResult,
    camera: FilamentCameraState,
    view: View?,
): Float3? {
    val viewMatrix = camera.viewMatrix ?: return null
    val projection = camera.projectionMatrix ?: return null
    val viewport = view?.viewport?.takeIf { it.width > 0 && it.height > 0 } ?: return null

    val ndcX = 2f * result.fragCoords[0] / viewport.width - 1f
    val ndcY = 2f * result.fragCoords[1] / viewport.height - 1f
    val ndcZ = 2f * result.depth - 1f
    val worldH = inverse(projection * viewMatrix) * Float4(ndcX, ndcY, ndcZ, 1f)
    if (worldH.w == 0f) return null
    return Float3(worldH.x / worldH.w, worldH.y / worldH.w, worldH.z / worldH.w)
}

private fun length(a: Position, b: Position): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun length(eye: Position, world: Float3): Float {
    val dx = world.x - eye.x
    val dy = world.y - eye.y
    val dz = world.z - eye.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun EnvironmentSource.toSkyboxSource(): SkyboxSource = when (this) {
    is EnvironmentSource.Default ->
        SkyboxSource.Color(LinearColor(0.08f, 0.10f, 0.14f))
    is EnvironmentSource.Color ->
        SkyboxSource.Color(LinearColor(red, green, blue), alpha = alpha)
    is EnvironmentSource.Hdr ->
        SkyboxSource.Color(LinearColor(0.08f, 0.10f, 0.14f))
}

private fun ModelSource.loadDescription(): String = when (this) {
    is ModelSource.Asset -> "loading asset '$path'"
    is ModelSource.Bytes -> "parsing ${bytes.size} in-memory bytes"
    is ModelSource.Url -> "downloading $url"
}

private suspend fun loadModelBytes(model: ModelSource): ByteArray = when (model) {
    is ModelSource.Asset -> withContext(Dispatchers.IO) { DesktopAssets.bytes(model.path) }
    is ModelSource.Bytes -> model.bytes
    is ModelSource.Url -> withContext(Dispatchers.IO) { fetchModelBytes(model.url) }
}

/**
 * Classpath lookup that is not named [SceneViewer] — a `SceneViewer::class` reference is
 * parsed as an invocation of the composable.
 */
private object DesktopAssets {
    fun bytes(path: String): ByteArray {
        val loader = Thread.currentThread().contextClassLoader
            ?: DesktopAssets::class.java.classLoader
        val normalized = path.removePrefix("/")
        val stream = loader.getResourceAsStream(normalized)
            ?: loader.getResourceAsStream("/$normalized")
            ?: throw IllegalArgumentException("Classpath asset not found: $path")
        return stream.use { it.readBytes() }
    }
}

private fun report(what: String, cause: Throwable?, onError: ((SceneViewerError) -> Unit)?) {
    System.err.println("SceneViewer failed $what${cause?.let { ": $it" } ?: ""}")
    onError?.invoke(SceneViewerError(what, cause))
}

private const val SCROLL_ZOOM = 0.05f
private const val DEFAULT_IBL_INTENSITY = 30_000f
