package io.github.sceneview.snapshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.LruCache
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
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
 * thumbnail?.let { Image(it, contentDescription = "Chair") }
 * ```
 *
 * Outside Compose, call [snapshot] from any coroutine:
 *
 * ```kotlin
 * val bitmap: Bitmap? = snapshot.snapshot("models/chair.glb", width = 256, height = 256)
 * ```
 *
 * ### One at a time, on the scene's engine
 *
 * A snapshotter lives on an existing [Engine] (the one your `SceneView` uses, typically) and reuses
 * one renderer, view, scene, camera and light rig for every image. Requests queue: **one model is
 * rendered at a time**, in the order asked, so a grid asking for twenty thumbnails at once never
 * stacks twenty renders into one frame of the main thread. A request whose coroutine is cancelled
 * (its grid cell scrolled away) leaves the queue without being rendered.
 *
 * ### Threads
 *
 * The file is read and parsed off the main thread, and the image is copied out on a background
 * thread. Only the Filament calls — creating the model, rendering two small frames, releasing the
 * model — run on the main thread, as Filament requires, between the frames of your scene. Nothing
 * blocks a thread while the GPU works: the image arrives through an [ImageReader].
 *
 * ### Cache
 *
 * Finished images are kept in a memory cache of [maxCacheBytes] (keyed by file, size and options),
 * so a cell scrolled back into view gets its image at once. Pass `0` to disable it. Bitmaps handed
 * out may be shared with the cache: do not [Bitmap.recycle] them.
 *
 * Create it on the main thread, and call [destroy] (or use [rememberModelSnapshot], which does)
 * before the engine is destroyed.
 *
 * @param engine the engine to render with. It must outlive this snapshotter.
 * @param context any context; only its application context is kept.
 * @param maxCacheBytes size of the in-memory thumbnail cache, in bytes. `0` disables it.
 */
class ModelSnapshot @MainThread constructor(
    val engine: Engine,
    context: Context,
    val maxCacheBytes: Int = DEFAULT_MAX_CACHE_BYTES,
) {
    private val appContext = context.applicationContext

    // Its own loader: gltfio finalises the textures of one model at a time per loader, so sharing
    // the scene's would make a thumbnail wait behind (or delay) the model the scene is loading.
    private val modelLoader = ModelLoader(engine, appContext)

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
     * The cached thumbnail of [fileLocation] at this size and with these [options], or `null` when
     * it has not been rendered yet (or the cache is disabled). Never renders. Any thread.
     */
    fun cached(
        fileLocation: String,
        width: Int,
        height: Int = width,
        options: ModelSnapshotOptions = ModelSnapshotOptions(),
    ): Bitmap? = cache?.get(SnapshotKey(fileLocation, width, height, options))

    /**
     * Renders the model at [fileLocation] into a [width] × [height] bitmap with a transparent
     * background, framed and lit as described on [ModelSnapshot].
     *
     * Suspends until this request's turn in the queue, then while the model loads and renders;
     * cancel the calling coroutine to give up its place. Callable from any thread.
     *
     * @param fileLocation the model, as [ModelLoader.loadModel] reads it: an asset path
     * (`models/chair.glb`), a file path or an `http(s)` URL.
     * @return the image, from the cache when it holds one, or `null` when the model could not be
     * loaded, the render timed out, or this snapshotter was destroyed.
     */
    suspend fun snapshot(
        fileLocation: String,
        width: Int,
        height: Int = width,
        options: ModelSnapshotOptions = ModelSnapshotOptions(),
    ): Bitmap? {
        require(width > 0 && height > 0) { "Thumbnail size must be positive, was $width × $height" }
        val key = SnapshotKey(fileLocation, width, height, options)
        cache?.get(key)?.let { return it }
        return withContext(Dispatchers.Main.immediate) {
            queue.withLock {
                // A request for the same image may have filled the cache while this one waited.
                cache?.get(key)?.let { return@withLock it }
                if (isDestroyed) return@withLock null
                render(fileLocation, width, height, options)?.also { cache?.put(key, it) }
            }
        }
    }

    /** Empties the thumbnail cache. Any thread. */
    fun clearCache() {
        cache?.evictAll()
    }

    /**
     * Releases the renderer, the light rig and every model still loading. A render in progress is
     * abandoned and its request returns `null`; queued requests return `null` too.
     *
     * Never blocks: whatever the GPU may still be using is released once it is done with it.
     * Call it before the [engine] is destroyed. Main thread.
     */
    @MainThread
    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        cache?.evictAll()
        // A render still in progress owns the renderer: release once it has returned.
        engine.whenIdle(isBusy = { queue.isLocked }) { release() }
    }

    @MainThread
    private suspend fun render(
        fileLocation: String,
        width: Int,
        height: Int,
        options: ModelSnapshotOptions,
    ): Bitmap? {
        val model = modelLoader.loadModel(fileLocation) ?: return null
        try {
            // Textures are decoded in the background and uploaded as they come: wait for all of
            // them, so the thumbnail is never captured half-textured.
            withTimeoutOrNull(TEXTURE_TIMEOUT_MS) {
                while (modelLoader.isLoading && !isDestroyed) {
                    modelLoader.updateLoad()
                    delay(FRAME_MS)
                }
            }
            if (isDestroyed) return null
            // Skinned models: pose the skin once, at rest.
            runCatching { model.instance?.animator?.updateBoneMatrices() }
            frame(model, width, height, options)
            scene.addEntities(model.entities)
            try {
                return capture(width, height)
            } finally {
                if (!isReleased) scene.removeEntities(model.entities)
            }
        } finally {
            modelLoader.destroyModel(model)
        }
    }

    @MainThread
    private fun frame(model: Model, width: Int, height: Int, options: ModelSnapshotOptions) {
        val box = model.boundingBox
        val aspect = width.toFloat() / height
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
        view.viewport = Viewport(0, 0, width, height)
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
     * one. The warm-up frame lets the backend link the model's shaders; frames are counted on both
     * sides, so the image returned is always this request's last frame.
     */
    @MainThread
    private suspend fun capture(width: Int, height: Int): Bitmap? {
        val target = captureTarget(width, height)
        // Frames of an abandoned request may still be on their way: let them land first.
        val drained = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            while (target.received.get() < target.submitted) delay(FRAME_MS)
        }
        if (drained == null || isDestroyed) {
            releaseTarget()
            return null
        }
        val result = CompletableDeferred<Bitmap?>()
        var rendered = 0
        var skipped = 0
        while (rendered <= WARMUP_FRAMES) {
            val isLast = rendered == WARMUP_FRAMES
            if (isLast) {
                target.captureFrame = target.submitted + 1
                target.pending = result
            }
            if (renderer.beginFrame(target.swapChain, System.nanoTime())) {
                renderer.render(view)
                renderer.endFrame()
                target.submitted++
                rendered++
            } else if (++skipped > MAX_SKIPPED_FRAMES) {
                break
            }
            if (rendered <= WARMUP_FRAMES) delay(FRAME_MS)
            if (isDestroyed) break
        }
        if (rendered <= WARMUP_FRAMES || isDestroyed) {
            target.pending = null
            return null
        }
        engine.flush()
        val bitmap = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { result.await() }
        target.pending = null
        // A frame that never arrived would shift every later count: start the next one afresh.
        if (bitmap == null) releaseTarget()
        return bitmap
    }

    @MainThread
    private fun captureTarget(width: Int, height: Int): CaptureTarget {
        target?.let { if (it.width == width && it.height == height) return it }
        releaseTarget()
        val thread = imageThread ?: HandlerThread("ModelSnapshot").also {
            it.start()
            imageThread = it
        }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        val swapChain = engine.createSwapChain(reader.surface, SwapChainFlags.CONFIG_TRANSPARENT)
        return CaptureTarget(width, height, reader, swapChain).also { capture ->
            reader.setOnImageAvailableListener({ capture.onImagesAvailable() }, Handler(thread.looper))
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
        engine.whenBackendIdle(onEngineGone = { old.reader.close() }) { old.reader.close() }
    }

    @MainThread
    private fun release() {
        if (isReleased) return
        isReleased = true
        cache?.evictAll()
        releaseTarget()
        modelLoader.destroy()
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

    /** One output size: an [ImageReader], the swap chain that draws into it, and the frame count. */
    private class CaptureTarget(
        val width: Int,
        val height: Int,
        val reader: ImageReader,
        val swapChain: SwapChain,
    ) {
        /** Frames rendered into [swapChain]. Main thread. */
        var submitted = 0L

        /** Images received from [reader]. Image thread. */
        val received = AtomicLong(0)

        /** The frame number whose image completes [pending]. */
        @Volatile
        var captureFrame = -1L

        @Volatile
        var pending: CompletableDeferred<Bitmap?>? = null

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
                                // Filament writes premultiplied RGBA: the byte layout of an
                                // ARGB_8888 bitmap.
                                Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                                    .apply { copyPixelsFromBuffer(pixels) }
                            }.getOrNull()
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
        private const val MAX_SKIPPED_FRAMES = 30
        private const val MAX_IMAGES = 3
        private const val FRAME_MS = 16L
        private const val TEXTURE_TIMEOUT_MS = 10_000L
        private const val CAPTURE_TIMEOUT_MS = 3_000L
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
 */
@Composable
fun rememberModelSnapshot(
    engine: Engine,
    maxCacheBytes: Int = ModelSnapshot.DEFAULT_MAX_CACHE_BYTES,
): ModelSnapshot {
    val context = LocalContext.current
    val snapshot = remember(engine, maxCacheBytes) { ModelSnapshot(engine, context, maxCacheBytes) }
    DisposableEffect(snapshot) {
        onDispose { snapshot.destroy() }
    }
    return snapshot
}

/**
 * The thumbnail of the model at [fileLocation], [size] wide and high, rendered by [snapshot].
 *
 * `null` until it is ready (draw a placeholder meanwhile), then the image on a transparent
 * background. Rendered once per file, size and [options] while the snapshot's cache holds it; a
 * cell that leaves the composition before its turn is never rendered.
 *
 * ```kotlin
 * val engine = rememberEngine()
 * val snapshot = rememberModelSnapshot(engine)
 * LazyVerticalGrid(columns = GridCells.Adaptive(96.dp)) {
 *     items(models) { path ->
 *         val thumbnail = rememberModelThumbnail(snapshot, path, size = 96.dp)
 *         if (thumbnail != null) Image(thumbnail, contentDescription = path)
 *         else CircularProgressIndicator()
 *     }
 * }
 * ```
 */
@Composable
fun rememberModelThumbnail(
    snapshot: ModelSnapshot,
    fileLocation: String,
    size: Dp,
    options: ModelSnapshotOptions = ModelSnapshotOptions(),
): ImageBitmap? {
    val pixels = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    val thumbnail by produceState(
        initialValue = snapshot.cached(fileLocation, pixels, pixels, options)?.asImageBitmap(),
        snapshot, fileLocation, pixels, options,
    ) {
        value = snapshot.snapshot(fileLocation, pixels, pixels, options)?.asImageBitmap()
    }
    return thumbnail
}
