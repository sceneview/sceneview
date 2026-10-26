package io.github.sceneview.snapshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.utils.KTX1Loader
import io.github.sceneview.DEFAULT_FILL_LIGHT_COLOR
import io.github.sceneview.DEFAULT_FILL_LIGHT_COLOR_INTENSITY
import io.github.sceneview.DEFAULT_IBL_INTENSITY
import io.github.sceneview.DEFAULT_MAIN_LIGHT_COLOR
import io.github.sceneview.DEFAULT_MAIN_LIGHT_COLOR_INTENSITY
import io.github.sceneview.DefaultCameraNode
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.createView
import io.github.sceneview.destroyRendererWhenBackendIdle
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.model.Model
import io.github.sceneview.rememberEngine
import io.github.sceneview.safeDestroyCamera
import io.github.sceneview.safeDestroyIndirectLight
import io.github.sceneview.safeDestroyLight
import io.github.sceneview.safeDestroyScene
import io.github.sceneview.safeDestroyTexture
import io.github.sceneview.safeDestroyView
import io.github.sceneview.utils.readBuffer
import io.github.sceneview.verticalFovDegreesForFocalLength
import io.github.sceneview.whenBackendIdle
import io.github.sceneview.whenIdle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.sqrt

/**
 * How [ModelSnapshot] frames a model.
 *
 * The default is a three-quarter view from slightly above, the angle a product shot uses: the
 * camera turns [yawDegrees] from the model's front (glTF +Z) toward its right, and looks down
 * [pitchDegrees]. The model is then framed whole, as tight as [padding] allows, and centred on its
 * silhouette.
 *
 * @param yawDegrees rotation of the camera around the model's vertical axis, from its front (+Z)
 * toward its right (+X). `0` is a straight front view, `90` its right side, `180` its back.
 * @param pitchDegrees how far above the model's horizontal plane the camera sits, in degrees,
 * strictly between -90 and 90. Negative values look up at the model.
 * @param focalLength lens focal length in millimetres (Filament's 24 mm sensor height). The
 * default 40 mm is the demo showroom's lens: a natural perspective, without a wide-angle bulge.
 * @param padding fraction of the image's width and height left empty on each side, in `[0, 0.5)`.
 */
@ExperimentalSceneViewApi
data class ModelSnapshotOptions(
    val yawDegrees: Float = DEFAULT_YAW_DEGREES,
    val pitchDegrees: Float = DEFAULT_PITCH_DEGREES,
    val focalLength: Float = DEFAULT_FOCAL_LENGTH,
    val padding: Float = DEFAULT_PADDING,
) {
    init {
        require(pitchDegrees > -90f && pitchDegrees < 90f) {
            "pitchDegrees must be strictly between -90 and 90, was $pitchDegrees"
        }
        require(focalLength > 0f) { "focalLength must be > 0, was $focalLength" }
        require(padding >= 0f && padding < 0.5f) { "padding must be in [0, 0.5), was $padding" }
    }

    companion object {
        /** Three-quarter view: the front and the right side both show. */
        const val DEFAULT_YAW_DEGREES = 30f

        /** Slightly from above: the top shows without flattening the model. */
        const val DEFAULT_PITCH_DEGREES = 20f

        /** The demo showroom's lens. */
        const val DEFAULT_FOCAL_LENGTH = 40f

        /** 8 % of the image left empty on each side. */
        const val DEFAULT_PADDING = 0.08f
    }
}

/** What a cached thumbnail is looked up by: the same model at another size or angle is another image. */
@OptIn(ExperimentalSceneViewApi::class)
internal data class SnapshotKey(
    val fileLocation: String,
    val width: Int,
    val height: Int,
    val options: ModelSnapshotOptions,
)

/**
 * Renders glTF / GLB models into [Bitmap]s off screen, with no view on screen — the thumbnails of
 * a model library, a picker grid or a share sheet.
 *
 * Each image frames the whole model automatically (see [ModelSnapshotOptions]), from a three-quarter
 * angle by default, lit by a neutral studio rig (image-based light plus a key and a fill light),
 * **on a transparent background**: draw it over any card colour, in light and dark themes alike.
 *
 * ```kotlin
 * val engine = rememberEngine()
 * val snapshot = rememberModelSnapshot(engine)
 * val thumbnail = rememberModelThumbnail(snapshot, "models/chair.glb", size = 96.dp)
 * thumbnail.bitmapOrNull()?.let { Image(it, contentDescription = "Chair") }
 * ```
 *
 * Outside Compose, call [snapshot] from any coroutine:
 *
 * ```kotlin
 * val bitmap: Bitmap? = snapshot.snapshot("models/chair.glb", width = 256, height = 256)
 * ```
 *
 * ### What fails
 *
 * The framing trusts the bounding box the glTF loader reports. A request fails — `null` from
 * [snapshot], [ModelThumbnailState.Failed] in Compose, a warning under the `ModelSnapshot` tag, and
 * nothing cached — when the model cannot be loaded, when its box is empty or not finite, when its
 * positions are quantized as normalized integers (`KHR_mesh_quantization`, `gltfpack -cc`: gltfio
 * reports their box about 32767 times too large), when the image comes out fully transparent, or
 * when the render times out. A whole set (ground, sky dome) is framed whole, so its subject comes
 * out tiny: thumbnail single objects.
 *
 * ### One at a time, on the scene's engine
 *
 * A snapshotter lives on an existing [Engine] (the one your `SceneView` uses, typically) and reuses
 * one renderer, view, scene, camera and light rig for every image. Requests queue: **one model is
 * rendered at a time**, in the order asked, so a grid asking for twenty thumbnails at once never
 * stacks twenty model loads into the same frames. A request whose coroutine is cancelled (its grid
 * cell scrolled away) leaves the queue; if it was rendering, the render stops and its model is
 * released before the next request starts.
 *
 * ### What it costs the main thread
 *
 * The file is read and parsed off the main thread, and the image is copied out on a background
 * thread. The rest runs on the main thread, as Filament requires, between the frames of your
 * scene — and costs what loading the same model into a scene costs. Measured on an arm64 emulator
 * with 2–5 MB textured GLBs: creating the model ≈ 130–150 ms, finalising its textures up to
 * ≈ 0.5 s in a single frame (gltfio uploads a decoded texture in one call), two small renders
 * 5–16 ms. A grid of textured models therefore drops frames while it fills, one model at a time.
 * Creating a snapshotter decodes its image-based light (≈ 70 ms) and, unless you pass the scene's
 * [ModelLoader], builds a loader of its own (≈ 0.6 s the first time, for its material provider).
 *
 * ### Cache
 *
 * Finished images are kept in a memory cache of [maxCacheBytes] (keyed by file, size and options),
 * so a cell scrolled back into view gets its image at once. Pass `0` to disable it. An image
 * captured while the model's textures were still decoding (after a 10 s timeout) is handed out but
 * never cached. Bitmaps handed out are immutable and may be shared with the cache: do not
 * [Bitmap.recycle] them.
 *
 * Create it on the main thread, and call [destroy] (or use [rememberModelSnapshot], which does)
 * before the engine is destroyed.
 *
 * @param engine the engine to render with. It must outlive this snapshotter.
 * @param context any context; only its application context is kept.
 * @param maxCacheBytes size of the in-memory thumbnail cache, in bytes. `0` disables it.
 * @param modelLoader the loader to load models with, on [engine] — typically your scene's
 * `rememberModelLoader`, which saves building a second material provider. Its texture loading is
 * then shared with your scene's models: a thumbnail asked while the scene loads waits for those
 * textures too (or is not cached, past the timeout). [destroy] does not destroy it. `null` (the
 * default) builds a loader of the snapshotter's own, destroyed with it.
 */
@ExperimentalSceneViewApi
class ModelSnapshot @MainThread constructor(
    val engine: Engine,
    context: Context,
    val maxCacheBytes: Int = DEFAULT_MAX_CACHE_BYTES,
    modelLoader: ModelLoader? = null,
) {
    private val appContext = context.applicationContext

    private val ownsModelLoader = modelLoader == null
    private val modelLoader = modelLoader ?: ModelLoader(engine, appContext)

    init {
        require(this.modelLoader.engine === engine) { "modelLoader must load on this engine" }
    }

    private val renderer: Renderer = engine.createRenderer().apply {
        clearOptions = clearOptions.apply {
            clear = true
            clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        }
    }
    private val scene = engine.createScene()
    private val cameraEntity = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity).apply {
        setExposure(
            DefaultCameraNode.DEFAULT_APERTURE,
            DefaultCameraNode.DEFAULT_SHUTTER_SPEED,
            DefaultCameraNode.DEFAULT_ISO,
        )
    }
    private val view: View = createView(engine).apply {
        scene = this@ModelSnapshot.scene
        camera = this@ModelSnapshot.camera
        // Alpha survives to the image only with a translucent view on a transparent swap chain.
        blendMode = View.BlendMode.TRANSLUCENT
        // Dithering would put colour noise in the transparent pixels around the model.
        dithering = View.Dithering.NONE
        // No ground to shadow or occlude onto, and no bright surround to bloom against.
        setShadowingEnabled(false)
        ambientOcclusionOptions = ambientOcclusionOptions.apply { enabled = false }
        bloomOptions = bloomOptions.apply { enabled = false }
        // Clean silhouettes over any card colour: MSAA rather than FXAA, which smears the edges.
        antiAliasing = View.AntiAliasing.NONE
        multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply {
            enabled = true
            sampleCount = 4
        }
    }
    private val keyLight = EntityManager.get().create().also {
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(DEFAULT_MAIN_LIGHT_COLOR.r, DEFAULT_MAIN_LIGHT_COLOR.g, DEFAULT_MAIN_LIGHT_COLOR.b)
            .intensity(DEFAULT_MAIN_LIGHT_COLOR_INTENSITY)
            .castShadows(false)
            .build(engine, it)
        scene.addEntity(it)
    }
    private val fillLight = EntityManager.get().create().also {
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(DEFAULT_FILL_LIGHT_COLOR.r, DEFAULT_FILL_LIGHT_COLOR.g, DEFAULT_FILL_LIGHT_COLOR.b)
            .intensity(DEFAULT_FILL_LIGHT_COLOR_INTENSITY)
            .castShadows(false)
            .build(engine, it)
        scene.addEntity(it)
    }
    private val ibl = KTX1Loader.createIndirectLight(
        engine,
        appContext.assets.readBuffer(NEUTRAL_IBL),
    ).also { bundle ->
        scene.indirectLight = bundle.indirectLight?.apply { intensity = DEFAULT_IBL_INTENSITY }
    }

    private val queue = Mutex()

    /** Runs the renders, so [destroy] can stop one mid-load. Main thread. */
    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val cache: LruCache<SnapshotKey, Bitmap>? = if (maxCacheBytes > 0) {
        object : LruCache<SnapshotKey, Bitmap>(maxCacheBytes) {
            override fun sizeOf(key: SnapshotKey, value: Bitmap) = value.byteCount
        }
    } else null

    private var imageThread: HandlerThread? = null
    private var target: CaptureTarget? = null
    private var isDestroyed = false
    private var isReleased = false

    /**
     * Whether Filament may still be called: checked after every suspension of a render, since
     * [destroy] — or the engine's own destruction — can land during any of them.
     */
    private val isAlive: Boolean get() = !isDestroyed && !isReleased && engine.isValid

    /**
     * The cached thumbnail of [fileLocation] at this size and with these [options], or `null` when
     * it has not been rendered yet (or the cache is disabled). Never renders. Any thread.
     */
    fun cached(
        fileLocation: String,
        width: Int,
        height: Int = width,
        options: ModelSnapshotOptions = ModelSnapshotOptions(),
    ): Bitmap? = cached(SnapshotKey(fileLocation, width, height, options))

    internal fun cached(key: SnapshotKey): Bitmap? = cache?.get(key)

    /**
     * Renders the model at [fileLocation] into a [width] × [height] bitmap with a transparent
     * background, framed and lit as described on [ModelSnapshot].
     *
     * Suspends until this request's turn in the queue, then while the model loads and renders;
     * cancel the calling coroutine to give up its place. Callable from any thread.
     *
     * @param fileLocation the model, as [ModelLoader.loadModel] reads it: an asset path
     * (`models/chair.glb`), a file path or an `http(s)` URL.
     * @return the image, from the cache when it holds one, or `null` when the request failed (see
     * "What fails" on [ModelSnapshot]; the reason is logged) or this snapshotter was destroyed.
     */
    suspend fun snapshot(
        fileLocation: String,
        width: Int,
        height: Int = width,
        options: ModelSnapshotOptions = ModelSnapshotOptions(),
    ): Bitmap? {
        require(width > 0 && height > 0) { "Thumbnail size must be positive, was $width × $height" }
        return when (val result = request(SnapshotKey(fileLocation, width, height, options))) {
            is SnapshotResult.Rendered -> result.bitmap
            is SnapshotResult.Failed -> null
        }
    }

    /**
     * One request: from the cache, or queued and rendered. Logs a failure, and caches only a
     * complete image of a snapshotter still alive.
     */
    internal suspend fun request(key: SnapshotKey): SnapshotResult {
        cache?.get(key)?.let { return SnapshotResult.Rendered(it, complete = true) }
        return withContext(Dispatchers.Main.immediate) {
            queue.withLock {
                // A request for the same image may have filled the cache while this one waited.
                cache?.get(key)?.let { return@withLock SnapshotResult.Rendered(it, complete = true) }
                if (!isAlive) return@withLock SnapshotResult.Failed(DESTROYED)
                val render = renderScope.async { render(key) }
                val result = try {
                    render.await()
                } catch (_: CancellationException) {
                    // The caller gave up: rethrown. Otherwise destroy() stopped the render.
                    currentCoroutineContext().ensureActive()
                    SnapshotResult.Failed(DESTROYED)
                } catch (e: Exception) {
                    SnapshotResult.Failed("render failed: $e")
                } finally {
                    // The next request starts once this model is released, never alongside it.
                    withContext(NonCancellable) { render.cancelAndJoin() }
                }
                when (result) {
                    is SnapshotResult.Rendered ->
                        if (result.complete && isAlive) cache?.put(key, result.bitmap)
                    is SnapshotResult.Failed ->
                        Log.w(TAG, "No thumbnail for ${key.fileLocation}: ${result.reason}")
                }
                result
            }
        }
    }

    /** Empties the thumbnail cache. Any thread. */
    fun clearCache() {
        cache?.evictAll()
    }

    /**
     * Releases the renderer, the light rig, its own [ModelLoader] (not one passed in) and the
     * image thread, and empties the cache.
     *
     * A render in progress is stopped at its next step — its model load is cancelled — and its
     * request gets `null` ([ModelThumbnailState.Failed] in Compose); queued requests get the same,
     * without rendering. Filament objects are released once that render has let go of them and the
     * GPU is done with them, never blocking. Call it before the [engine] is destroyed; if the
     * engine is already gone, only the image thread and its reader are released (the engine took
     * the rest with it). Main thread.
     */
    @MainThread
    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        cache?.evictAll()
        renderScope.cancel()
        engine.whenIdle(isBusy = { queue.isLocked }, onEngineGone = ::releaseWithoutEngine) {
            release()
        }
    }

    @MainThread
    private suspend fun render(key: SnapshotKey): SnapshotResult {
        val model = modelLoader.loadModel(key.fileLocation)
            ?: return SnapshotResult.Failed("could not load the model")
        try {
            if (!isAlive) return SnapshotResult.Failed(DESTROYED)
            val extensions = withContext(Dispatchers.IO) {
                readGltfExtensionsUsed(appContext, key.fileLocation)
            }
            if (KHR_MESH_QUANTIZATION in extensions) {
                return SnapshotResult.Failed(
                    "its positions are quantized ($KHR_MESH_QUANTIZATION), whose bounding box " +
                        "gltfio reports un-dequantized: re-export it with float positions"
                )
            }
            // Textures are decoded in the background and uploaded as they come: wait for all of
            // them, so the thumbnail is never captured half-textured.
            val texturesReady = withTimeoutOrNull(TEXTURE_TIMEOUT_MS) {
                while (isAlive && modelLoader.isLoading) {
                    modelLoader.updateLoad()
                    delay(FRAME_MS)
                }
            } != null
            if (!isAlive) return SnapshotResult.Failed(DESTROYED)
            if (!texturesReady) Log.w(TAG, "${key.fileLocation}: textures still loading after $TEXTURE_TIMEOUT_MS ms")
            // Skinned models: pose the skin once, at rest.
            runCatching { model.instance?.animator?.updateBoneMatrices() }
            if (!frame(model, key)) {
                return SnapshotResult.Failed("its bounding box is empty or not finite")
            }
            scene.addEntities(model.entities)
            try {
                // The very first capture of a cold engine can outlast its timeout while the backend
                // compiles: the target is rebuilt after a miss, and a second try finds it warm.
                val captured = capture(key.width, key.height)
                    ?: if (isAlive) capture(key.width, key.height) else null
                return when {
                    !isAlive -> SnapshotResult.Failed(DESTROYED)
                    captured == null -> SnapshotResult.Failed("the render timed out")
                    captured.bitmap == null -> SnapshotResult.Failed(
                        captured.failure ?: "it drew nothing: the image is fully transparent"
                    )
                    else -> SnapshotResult.Rendered(captured.bitmap, complete = texturesReady)
                }
            } finally {
                if (!isReleased && engine.isValid) scene.removeEntities(model.entities)
            }
        } finally {
            // Once released, the engine or the loader's own destroy() took the model with it.
            if (!isReleased && engine.isValid) modelLoader.destroyModel(model)
        }
    }

    /** Aims the camera and the light rig at [model]; `false` when its box cannot be framed. */
    @MainThread
    private fun frame(model: Model, key: SnapshotKey): Boolean {
        val box = model.boundingBox
        if (!isFramable(box.center, box.halfExtent)) return false
        val options = key.options
        val aspect = key.width.toFloat() / key.height
        val focalLength = options.focalLength.toDouble()
        val framing = frameSnapshot(
            center = Position(box.center[0], box.center[1], box.center[2]),
            halfExtent = Position(box.halfExtent[0], box.halfExtent[1], box.halfExtent[2]),
            yawDegrees = options.yawDegrees,
            pitchDegrees = options.pitchDegrees,
            verticalFovDegrees = verticalFovDegreesForFocalLength(focalLength).toFloat(),
            aspect = aspect,
            padding = options.padding,
        )
        camera.setLensProjection(focalLength, aspect.toDouble(), framing.near.toDouble(), framing.far.toDouble())
        camera.lookAt(
            framing.eye.x.toDouble(), framing.eye.y.toDouble(), framing.eye.z.toDouble(),
            framing.target.x.toDouble(), framing.target.y.toDouble(), framing.target.z.toDouble(),
            framing.up.x.toDouble(), framing.up.y.toDouble(), framing.up.z.toDouble(),
        )
        // The light rig turns with the camera, so any angle is lit like the default one: the key
        // from above the camera's left shoulder, the fill low from its right.
        val back = (framing.eye - framing.target).normalized()
        val right = Position(
            framing.up.y * back.z - framing.up.z * back.y,
            framing.up.z * back.x - framing.up.x * back.z,
            framing.up.x * back.y - framing.up.y * back.x,
        )
        setDirection(keyLight, back * 0.6f + framing.up * 1.0f - right * 0.6f)
        setDirection(fillLight, back * 0.8f + framing.up * 0.2f + right * 0.9f)
        view.viewport = Viewport(0, 0, key.width, key.height)
        return true
    }

    /** Points [light] so it shines from [toLight] (a direction toward the light) onto the model. */
    private fun setDirection(light: Int, toLight: Position) {
        val d = toLight.normalized()
        val lightManager = engine.lightManager
        val instance = lightManager.getInstance(light)
        if (instance != 0) lightManager.setDirection(instance, -d.x, -d.y, -d.z)
    }

    /**
     * Renders [WARMUP_FRAMES] + 1 frames into the [ImageReader] of this size and returns the last
     * one, or `null` when it never arrived or this snapshotter went away meanwhile. The warm-up
     * frame lets the backend link the model's shaders; frames are counted on both sides, so the
     * image returned is always this request's last frame.
     */
    @MainThread
    private suspend fun capture(width: Int, height: Int): CapturedFrame? {
        val target = captureTarget(width, height)
        // Frames of an abandoned request may still be on their way: let them land first.
        val drained = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            while (target.received.get() < target.submitted) delay(FRAME_MS)
        }
        if (!isAlive) return null
        if (drained == null) {
            Log.w(TAG, "Earlier frames never arrived; starting a new target")
            releaseTarget()
            return null
        }
        val result = CompletableDeferred<CapturedFrame?>()
        try {
            var rendered = 0
            val deadline = SystemClock.uptimeMillis() + FRAME_BUDGET_MS
            while (rendered <= WARMUP_FRAMES) {
                if (rendered == WARMUP_FRAMES) {
                    target.captureFrame = target.submitted + 1
                    target.pending = result
                }
                if (renderer.beginFrame(target.swapChain, System.nanoTime())) {
                    renderer.render(view)
                    renderer.endFrame()
                    target.submitted++
                    rendered++
                } else if (SystemClock.uptimeMillis() > deadline) {
                    Log.w(TAG, "The renderer kept skipping frames for $FRAME_BUDGET_MS ms")
                    return null
                }
                if (rendered <= WARMUP_FRAMES) {
                    delay(FRAME_MS)
                    if (!isAlive) return null
                }
            }
            engine.flush()
            val frame = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { result.await() }
            if (!isAlive) return null
            // A frame that never arrived would shift every later count: start the next one afresh.
            if (frame == null) {
                Log.w(TAG, "The captured frame never arrived")
                releaseTarget()
            }
            return frame
        } finally {
            // Never left behind for a later frame to complete.
            if (target.pending === result) target.pending = null
        }
    }

    @MainThread
    private fun captureTarget(width: Int, height: Int): CaptureTarget {
        target?.let { if (it.width == width && it.height == height) return it }
        releaseTarget()
        val thread = imageThread ?: HandlerThread("ModelSnapshot").also {
            it.start()
            imageThread = it
        }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        val swapChain = engine.createSwapChain(reader.surface, SwapChainFlags.CONFIG_TRANSPARENT)
        return CaptureTarget(width, height, reader, swapChain, handler).also { capture ->
            reader.setOnImageAvailableListener({ capture.onImagesAvailable() }, handler)
            target = capture
        }
    }

    @MainThread
    private fun releaseTarget() {
        val old = target ?: return
        target = null
        old.pending?.complete(null)
        runCatching { engine.destroySwapChain(old.swapChain) }
        // The backend may still be presenting into the reader's surface: close it once it is done.
        engine.whenBackendIdle(onEngineGone = { old.close() }) { old.close() }
    }

    @MainThread
    private fun release() {
        if (isReleased) return
        isReleased = true
        cache?.evictAll()
        renderScope.cancel()
        releaseTarget()
        if (ownsModelLoader) modelLoader.destroy()
        scene.indirectLight = null
        ibl.indirectLight?.let { engine.safeDestroyIndirectLight(it) }
        ibl.cubemap?.let { engine.safeDestroyTexture(it) }
        for (light in intArrayOf(keyLight, fillLight)) {
            scene.removeEntity(light)
            engine.safeDestroyLight(light)
            EntityManager.get().destroy(light)
        }
        val colorGrading = view.colorGrading
        engine.safeDestroyView(view)
        colorGrading?.let { runCatching { engine.destroyColorGrading(it) } }
        engine.safeDestroyScene(scene)
        engine.safeDestroyCamera(camera)
        EntityManager.get().destroy(cameraEntity)
        engine.destroyRendererWhenBackendIdle(renderer)
        // Last: after every reader closed above has been closed.
        val thread = imageThread
        imageThread = null
        if (thread != null) {
            engine.whenBackendIdle(onEngineGone = { thread.quitSafely() }) { thread.quitSafely() }
        }
    }

    /** The engine went first, taking every Filament object with it: only Android's are left. */
    @MainThread
    private fun releaseWithoutEngine() {
        if (isReleased) return
        isReleased = true
        cache?.evictAll()
        renderScope.cancel()
        target?.let { old ->
            target = null
            old.pending?.complete(null)
            old.close()
        }
        imageThread?.quitSafely()
        imageThread = null
    }

    /** An image read back: [bitmap] is `null` when it was fully transparent or could not be copied. */
    private class CapturedFrame(val bitmap: Bitmap?, val failure: String? = null)

    /** One output size: an [ImageReader], the swap chain that draws into it, and the frame count. */
    private class CaptureTarget(
        val width: Int,
        val height: Int,
        val reader: ImageReader,
        val swapChain: SwapChain,
        /** The image thread's: [reader] is only ever read and closed there. */
        val handler: Handler,
    ) {
        /** Frames rendered into [swapChain]. Main thread. */
        var submitted = 0L

        /** Images received from [reader]. Image thread. */
        val received = AtomicLong(0)

        /** The frame number whose image completes [pending]. */
        @Volatile
        var captureFrame = -1L

        @Volatile
        var pending: CompletableDeferred<CapturedFrame?>? = null

        /** Closes [reader] on the image thread, after any image it is reading; directly once it quit. */
        fun close() {
            if (!handler.post { reader.close() }) reader.close()
        }

        /** Image thread: drains every image, keeps the one awaited, closes the rest. */
        fun onImagesAvailable() {
            while (true) {
                val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
                try {
                    val frame = received.incrementAndGet()
                    val waiting = pending
                    if (waiting != null && frame == captureFrame) {
                        waiting.complete(
                            runCatching {
                                val plane = image.planes[0]
                                val pixels = packRgbaRows(
                                    plane.buffer, image.width, image.height,
                                    plane.rowStride, plane.pixelStride,
                                )
                                if (!hasVisiblePixel(pixels)) return@runCatching CapturedFrame(null)
                                // Filament writes premultiplied RGBA: the byte layout of an
                                // ARGB_8888 bitmap.
                                val drawn = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                                drawn.copyPixelsFromBuffer(pixels)
                                // Immutable: it is shared with the cache and every caller.
                                CapturedFrame(drawn.copy(Bitmap.Config.ARGB_8888, false).also { drawn.recycle() })
                            }.getOrElse { CapturedFrame(null, "the image could not be copied: $it") }
                        )
                    }
                } finally {
                    image.close()
                }
            }
        }
    }

    companion object {
        /** 32 MB: about 120 thumbnails of 256 × 256. */
        const val DEFAULT_MAX_CACHE_BYTES = 32 * 1024 * 1024

        private const val NEUTRAL_IBL = "environments/neutral/neutral_ibl.ktx"
        private const val WARMUP_FRAMES = 1
        private const val TAG = "ModelSnapshot"
        private const val DESTROYED = "the snapshot was destroyed"

        /**
         * How long the renderer may keep refusing frames. It skips while the GPU is behind, which
         * on a first use of a model's materials lasts as long as their shaders take to compile —
         * seconds on an emulator.
         */
        private const val FRAME_BUDGET_MS = 10_000L
        private const val MAX_IMAGES = 3
        private const val FRAME_MS = 16L
        private const val TEXTURE_TIMEOUT_MS = 10_000L
        private const val CAPTURE_TIMEOUT_MS = 5_000L
    }
}

private fun Position.normalized(): Position {
    val length = sqrt(x * x + y * y + z * z)
    return if (length > 0f) Position(x / length, y / length, z / length) else this
}

/**
 * A [ModelSnapshot] on [engine], destroyed when this leaves the composition.
 *
 * Share one per screen: every thumbnail of a grid then queues on the same renderer and cache.
 *
 * @param engine the engine to render with, typically the screen's [rememberEngine].
 * @param maxCacheBytes size of the thumbnail cache, in bytes. `0` disables it.
 * @param modelLoader the scene's loader to share, if the screen has one (see [ModelSnapshot]);
 * `null` builds one for the snapshot.
 */
@ExperimentalSceneViewApi
@Composable
fun rememberModelSnapshot(
    engine: Engine,
    maxCacheBytes: Int = ModelSnapshot.DEFAULT_MAX_CACHE_BYTES,
    modelLoader: ModelLoader? = null,
): ModelSnapshot {
    val context = LocalContext.current
    val snapshot = remember(engine, maxCacheBytes, modelLoader) {
        ModelSnapshot(engine, context, maxCacheBytes, modelLoader)
    }
    DisposableEffect(snapshot) {
        onDispose { snapshot.destroy() }
    }
    return snapshot
}

/**
 * The thumbnail of the model at [fileLocation], [size] wide and high, rendered by [snapshot].
 *
 * [ModelThumbnailState.Loading] until its turn has come and gone (draw a placeholder meanwhile),
 * then [ModelThumbnailState.Ready] with the image on a transparent background, or
 * [ModelThumbnailState.Failed] with the reason (see "What fails" on [ModelSnapshot]). A change of
 * [fileLocation], [size] or [options] starts again from `Loading` — or from the cached image —
 * never from the previous model's image. Rendered once per file, size and options while the
 * snapshot's cache holds it; a cell that leaves the composition before its turn is never rendered.
 *
 * ```kotlin
 * val engine = rememberEngine()
 * val snapshot = rememberModelSnapshot(engine)
 * LazyVerticalGrid(columns = GridCells.Adaptive(96.dp)) {
 *     items(models) { path ->
 *         when (val thumbnail = rememberModelThumbnail(snapshot, path, size = 96.dp)) {
 *             is ModelThumbnailState.Ready -> Image(thumbnail.bitmap, contentDescription = path)
 *             is ModelThumbnailState.Failed -> Icon(Icons.Default.BrokenImage, contentDescription = null)
 *             ModelThumbnailState.Loading -> CircularProgressIndicator()
 *         }
 *     }
 * }
 * ```
 */
@ExperimentalSceneViewApi
@Composable
fun rememberModelThumbnail(
    snapshot: ModelSnapshot,
    fileLocation: String,
    size: Dp,
    options: ModelSnapshotOptions = ModelSnapshotOptions(),
): ModelThumbnailState {
    val pixels = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val key = SnapshotKey(fileLocation, pixels, pixels, options)
    return rememberThumbnailState(
        owner = snapshot,
        key = key,
        cached = { snapshot.cached(key)?.let { ModelThumbnailState.Ready(it.asImageBitmap()) } },
        load = { snapshot.request(key).toThumbnailState() },
    )
}
