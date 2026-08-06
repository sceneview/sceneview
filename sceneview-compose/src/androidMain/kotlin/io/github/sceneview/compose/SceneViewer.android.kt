package io.github.sceneview.compose

import android.view.MotionEvent
import android.view.ViewConfiguration
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.android.filament.Engine
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import io.github.sceneview.SceneView
import io.github.sceneview.collision.HitResult
import io.github.sceneview.environment.Environment
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.model
import io.github.sceneview.node.CameraNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Android implementation: delegates to the Filament renderer in `:sceneview`.
 *
 * Nothing here reaches into that module — it composes the public `SceneView { }` exactly
 * as an application would, so the façade cannot drift from the API it claims to mirror.
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
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val modelInstance = rememberModelInstance(modelLoader, model, onError)

    val filamentEnvironment = rememberEnvironment(engine, environmentLoader, environment, onError)

    // The image-based light is shared state owned by the Environment, and Filament has
    // no "authored intensity" to read back — `indirectLight.intensity` is simply the
    // last value written. So the baseline is captured ONCE per Environment and every
    // later multiplier is applied to that, never to the live value.
    //
    // Scaling the live value instead compounds: a slider moved 1.0 -> 0.5 -> 1.0 leaves
    // the scene at half ambient with the control back at neutral, and a single pass
    // through 0f latches ambient at zero forever, since 0 * x is 0 for every later x.
    val authoredAmbient = remember(filamentEnvironment) {
        filamentEnvironment.indirectLight?.intensity
    }
    LaunchedEffect(filamentEnvironment, lighting.ambientIntensity) {
        val baseline = authoredAmbient ?: return@LaunchedEffect
        filamentEnvironment.indirectLight?.intensity = baseline * lighting.ambientIntensity
    }

    SceneView(
        modifier = modifier,
        engine = engine,
        modelLoader = modelLoader,
        environmentLoader = environmentLoader,
        environment = filamentEnvironment,
        isOpaque = environment.isOpaque,
        // Both built-in lights are suppressed: the key light is declared in the content
        // block below so `Lighting` changes flow through recomposition, and the fill
        // light would otherwise light the scene a second time with an intensity this
        // façade neither exposes nor can disable.
        mainLightNode = null,
        fillLightNode = null,
        cameraNode = rememberOrbitCameraNode(engine, camera),
        // `null`, NOT the default manipulator. SceneView's frame loop unconditionally
        // assigns `cameraNode.transform = manipulator.getTransform()` on every rendered
        // frame, which would silently overwrite everything CameraState writes and make
        // the whole `camera` parameter decorative. Gestures are handled below instead,
        // writing back into CameraState so reads observe what the user did.
        cameraManipulator = null,
        onTouchEvent = rememberGestureHandler(camera, onTap),
        onFrame = onFrame,
    ) {
        // Keyed on castShadows: it is applied through the LightManager builder, which
        // runs only at node creation — the reactive path upstream pushes intensity,
        // direction and colour but not shadow casting. Re-keying recreates the node so
        // a runtime toggle actually takes effect.
        key(lighting.castShadows) {
            LightNode(
                type = LightManager.Type.DIRECTIONAL,
                intensity = lighting.intensity,
                direction = lighting.direction,
                apply = { castShadows(lighting.castShadows) },
            )
        }

        modelInstance?.let { instance ->
            ModelNode(modelInstance = instance)
        }
    }
}

/** Whether the viewport should be drawn opaque for this environment. */
private val EnvironmentSource.isOpaque: Boolean
    get() = when (this) {
        is EnvironmentSource.Default -> true
        is EnvironmentSource.Color -> alpha >= 1f
        // With no skybox there is nothing to draw behind the model, so the surface must
        // be transparent for the Compose content underneath to show through — which is
        // exactly what `showSkybox = false` promises. An opaque surface would render
        // black instead.
        is EnvironmentSource.Hdr -> showSkybox
    }

/**
 * Loads [model] into a Filament [ModelInstance], whatever its source.
 *
 * Filament's JNI calls must run on the main thread; **reading the bytes must not**. Every
 * source that has bytes to fetch reads them off it, because `produceState` runs its
 * producer in the composition's context — which on Android is the main thread — so any
 * blocking read left in this block blocks the frame that started it.
 *
 * The instance is destroyed when the source changes or the composable leaves —
 * `produceState` alone would cancel the producer but never free the GPU-side model,
 * which is the leak described in #2459.
 */
@Composable
private fun rememberModelInstance(
    modelLoader: ModelLoader,
    model: ModelSource,
    onError: ((SceneViewerError) -> Unit)?,
): ModelInstance? {
    // Kept fresh without restarting the load: `onError` is a lambda, so using it as a
    // `produceState` key would re-download the model on every recomposition that passed
    // a new one. Capturing the first one instead would send failures to a stale handler.
    val currentOnError by rememberUpdatedState(onError)

    val instance = produceState<ModelInstance?>(
        initialValue = null,
        key1 = modelLoader,
        key2 = model,
    ) {
        value = when (model) {
            // `loadModelInstance`, NOT `createModelInstance`: the latter is @MainThread
            // and reads the asset on the CALLING thread (ModelLoader.createModel ->
            // context.assets.readBuffer), so the whole file landed on the main thread
            // here. The suspending overload reads through Dispatchers.IO and hops back
            // to Main for the JNI call alone. Sibling resolution is preserved — its
            // default resolver walks the same asset folder, so a .gltf with external
            // .bin/textures still loads.
            is ModelSource.Asset -> runCatching {
                modelLoader.loadModelInstance(model.path)
            }.orReport("loading asset '${model.path}'", currentOnError)

            // No IO: the caller already holds the bytes. Straight to the JNI call, on
            // the main thread, which is where this block already is.
            is ModelSource.Bytes -> runCatching {
                modelLoader.createModelInstance(ByteBuffer.wrap(model.bytes))
            }.orReport("parsing ${model.bytes.size} in-memory bytes", currentOnError)

            is ModelSource.Url -> {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { fetchModelBytes(model.url) }
                        .orReport("downloading ${model.url}", currentOnError)
                }
                bytes?.let {
                    // Back on the composition (main) thread — the Filament JNI contract.
                    runCatching {
                        modelLoader.createModelInstance(ByteBuffer.wrap(it))
                    }.orReport("parsing the model downloaded from ${model.url}", currentOnError)
                }
            }
        }
    }.value

    // Frees the previous instance on a source swap and on leave-composition. Registered
    // before the consuming ModelNode is declared, so on a swap the node detaches and
    // destroys itself first and the buffers are freed after — the ordering #2424 needs.
    DisposableEffect(instance) {
        onDispose { instance?.let { modelLoader.destroyModel(it.model) } }
    }

    return instance
}

/**
 * Downloads a remote model with the guards a raw `URL.openStream()` does not have.
 *
 * Restricted to http/https (`java.net.URL` would happily open `file://` and turn a
 * forwarded deep link into a local-file read), with connect/read timeouts so a silent
 * server cannot hang the IO coroutine forever, and a size cap so a hostile or simply
 * huge response cannot exhaust the heap.
 */
private fun fetchModelBytes(url: String): ByteArray {
    val uri = URI(url)
    require(uri.scheme?.lowercase() in setOf("http", "https")) {
        "ModelSource.Url only accepts http and https, got '${uri.scheme}'"
    }

    val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        instanceFollowRedirects = true
    }

    return try {
        // Refuse an oversized body before reading a single byte, when the server
        // announces one. The streaming check below is the real guard (Content-Length
        // is a hint a hostile server can omit or lie about), but honouring it costs
        // nothing and turns the common case into an instant, allocation-free refusal.
        val announced = connection.contentLengthLong
        require(announced <= MAX_MODEL_BYTES) {
            "Model at $url declares $announced bytes, over the ${MAX_MODEL_BYTES shr 20} MB limit"
        }

        connection.inputStream.use { stream ->
            val buffer = ByteArray(DOWNLOAD_CHUNK_BYTES)
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.size().let { soFar ->
                    require(soFar + read <= MAX_MODEL_BYTES) {
                        "Model at $url exceeds the ${MAX_MODEL_BYTES shr 20} MB limit"
                    }
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    } finally {
        connection.disconnect()
    }
}

/**
 * Unwraps a [Result], reporting a failure instead of discarding it.
 *
 * A failed load still leaves the viewport showing the environment — pixel-identical to a
 * load still in progress. This is the one place that becomes observable: always to
 * logcat, and to the app's `onError` when it supplied one.
 *
 * **Both shapes of failure land here**, and the second one is not optional. A missing
 * asset throws (`AssetManager.open`), which `runCatching` catches. A *malformed* one does
 * not: `createModelInstance` raised `IllegalArgumentException` when Filament refused to
 * parse the buffer, but the suspending `loadModelInstance` this now calls returns a plain
 * `null` for the same input. Handling only the exception would have turned the
 * off-main-thread fix into a silent failure for every unparseable model.
 *
 * [CancellationException] is rethrown: a cancelled coroutine is not a failure, and
 * swallowing it would break structured concurrency.
 */
private fun <T : Any> Result<T?>.orReport(
    what: String,
    onError: ((SceneViewerError) -> Unit)?,
): T? = fold(
    onSuccess = { value ->
        if (value == null) report(what, cause = null, onError = onError)
        value
    },
    onFailure = { cause ->
        if (cause is CancellationException) throw cause
        report(what, cause, onError)
        null
    },
)

/** Writes a failure to logcat, and hands it to the app when it asked for one. */
private fun report(what: String, cause: Throwable?, onError: ((SceneViewerError) -> Unit)?) {
    Log.e(TAG, "SceneViewer failed $what", cause)
    onError?.invoke(SceneViewerError(what, cause))
}

private const val TAG = "SceneViewer"
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 30_000
private const val DOWNLOAD_CHUNK_BYTES = 64 * 1024

/**
 * Ceiling on a downloaded model, in bytes.
 *
 * Sized against the heap, not against what a model "could" be. The bytes accumulate in
 * a `ByteArrayOutputStream`, whose growth-by-doubling plus the final `toByteArray()`
 * copy peaks near twice this value — so a cap set at a nominally generous 256 MB would
 * OOM the app long before it ever tripped, protecting nothing. 64 MB peaks around
 * 128 MB, which a typical Android heap survives, so the limit actually fires.
 */
private const val MAX_MODEL_BYTES = 64L * 1024 * 1024

/**
 * Maps the portable [EnvironmentSource] onto a Filament [Environment].
 *
 * The neutral fallback is built **inside the branches that use it**, not once up front.
 * Building it is not free: `createEnvironment` reads `environments/neutral/neutral_ibl.ktx`
 * out of assets synchronously and uploads it as a cubemap, and a hoisted `val` paid that
 * on every [EnvironmentSource.Color] scene only to drop the result — a colour background
 * has no image-based light, so the fallback was never reachable from that branch.
 */
@Composable
private fun rememberEnvironment(
    engine: Engine,
    environmentLoader: EnvironmentLoader,
    source: EnvironmentSource,
    onError: ((SceneViewerError) -> Unit)?,
): Environment {
    val isOpaque = source.isOpaque
    val currentOnError by rememberUpdatedState(onError)

    return when (source) {
        is EnvironmentSource.Default -> rememberEnvironment(environmentLoader, isOpaque)

        // Remembered on explicit value keys rather than through the `environment = { }`
        // factory overload: that overload takes the factory lambda as a `remember` key
        // too, and this lambda captures the Filament `Engine`, whose Compose stability is
        // inferred from a class this module does not own. Keying on (engine, loader,
        // source) does not depend on that inference — the skybox is rebuilt when, and
        // only when, one of the three actually changes.
        is EnvironmentSource.Color -> {
            val colorEnvironment = remember(engine, environmentLoader, source) {
                environmentLoader.createEnvironment(
                    skybox = Skybox.Builder()
                        .color(source.red, source.green, source.blue, source.alpha)
                        .build(engine),
                )
            }
            DisposableEffect(colorEnvironment) {
                onDispose { environmentLoader.destroyEnvironment(colorEnvironment) }
            }
            colorEnvironment
        }

        // HDR loading is suspending and may fail (missing file, unreadable format). Until
        // it resolves — and if it never does — fall back to the default environment
        // rather than rendering an unlit scene that looks like a broken model.
        is EnvironmentSource.Hdr -> {
            val loaded = produceState<Environment?>(null, environmentLoader, source) {
                value = runCatching {
                    environmentLoader.loadHDREnvironment(
                        url = source.path,
                        createSkybox = source.showSkybox,
                    )
                }.orReport("loading HDR environment '${source.path}'", currentOnError)
            }.value

            // Only environments this branch created are ours to free. The fallback below
            // is owned by `rememberEnvironment`, which destroys it itself — destroying it
            // here too would be a double free.
            DisposableEffect(loaded) {
                onDispose { loaded?.let { environmentLoader.destroyEnvironment(it) } }
            }

            // Conditional on purpose: once the HDR resolves, the fallback leaves
            // composition and its own DisposableEffect frees the neutral cubemap.
            loaded ?: rememberEnvironment(environmentLoader, isOpaque)
        }
    }
}

/**
 * Turns raw touch events into camera orbit, zoom and tap callbacks.
 *
 * This is the only camera driver — `SceneView` is given `cameraManipulator = null` — so
 * gestures mutate [state] and the camera follows from it. That keeps reads of
 * [CameraState] truthful about what the user is doing, which the API promises.
 *
 * A tap is a down/up pair that never travelled beyond the touch slop; without that check
 * every orbit would end in a spurious tap, since a drag also ends with `ACTION_UP`.
 *
 * Always returns `false`: this observes events, it does not consume them.
 */
@Composable
private fun rememberGestureHandler(
    state: CameraState,
    onTap: ((ModelHit?) -> Unit)?,
): ((MotionEvent, HitResult?) -> Boolean) {
    // Kept fresh without re-creating the handler: re-creating it mid-gesture would drop
    // the accumulated down position and turn an orbit into a phantom tap.
    val currentOnTap by rememberUpdatedState(onTap)
    val currentState by rememberUpdatedState(state)

    val touchSlop = with(LocalContext.current) {
        ViewConfiguration.get(this).scaledTouchSlop.toFloat()
    }

    return remember(touchSlop) {
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var lastY = 0f
        var lastSpan = 0f
        var moved = false

        handler@{ event, hitResult ->
            val cameraState = currentState

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                    lastY = event.y
                    moved = false
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    lastSpan = event.spanOrZero()
                    moved = true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                        moved = true
                    }
                    if (!cameraState.gesturesEnabled) return@handler false

                    if (event.pointerCount >= 2) {
                        val span = event.spanOrZero()
                        if (lastSpan > 0f && span > 0f) {
                            // Pinch out brings the camera closer, so divide.
                            cameraState.distance /= (span / lastSpan)
                        }
                        lastSpan = span
                    } else {
                        cameraState.azimuth -= (event.x - lastX) * ORBIT_DEGREES_PER_PIXEL
                        cameraState.elevation += (event.y - lastY) * ORBIT_DEGREES_PER_PIXEL
                    }
                    lastX = event.x
                    lastY = event.y
                }

                MotionEvent.ACTION_POINTER_UP -> lastSpan = 0f

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        currentOnTap?.invoke(
                            hitResult?.nodeOrNull?.let {
                                ModelHit(
                                    position = hitResult.getWorldPosition(),
                                    distance = hitResult.getDistance(),
                                )
                            },
                        )
                    }
                }
            }
            false
        }
    }
}

/** Distance between the first two pointers, or `0` when there are fewer than two. */
private fun MotionEvent.spanOrZero(): Float =
    if (pointerCount < 2) 0f else hypot(getX(0) - getX(1), getY(0) - getY(1))

private const val ORBIT_DEGREES_PER_PIXEL = 0.3f

/**
 * Derives the Filament camera position from the portable orbit [state].
 *
 * Spherical (azimuth, elevation, distance) around [CameraState.target], with the
 * elevation clamp in [CameraState] keeping the basis non-degenerate.
 */
@Composable
private fun rememberOrbitCameraNode(
    engine: Engine,
    state: CameraState,
): CameraNode {
    val cameraNode = rememberCameraNode(engine)

    LaunchedEffect(cameraNode, state.target, state.distance, state.azimuth, state.elevation) {
        val azimuthRad = state.azimuth.toRadians()
        val elevationRad = state.elevation.toRadians()
        val horizontal = state.distance * cos(elevationRad)

        cameraNode.position = Position(
            x = state.target.x + horizontal * sin(azimuthRad),
            y = state.target.y + state.distance * sin(elevationRad),
            z = state.target.z + horizontal * cos(azimuthRad),
        )
        cameraNode.lookAt(Position(state.target.x, state.target.y, state.target.z))
    }

    return cameraNode
}

private fun Float.toRadians(): Float = this * (PI.toFloat() / 180f)
