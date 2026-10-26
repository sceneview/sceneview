package io.github.sceneview.loaders

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RawRes
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Fence
import com.google.android.filament.Texture
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import io.github.sceneview.bumpLightGeneration
import io.github.sceneview.bumpRenderableGeneration
import io.github.sceneview.bumpTransformGeneration
import io.github.sceneview.core.obj.ObjLoader
import io.github.sceneview.core.ply.PlyLoader
import io.github.sceneview.core.threemf.ThreeMfLoader
import io.github.sceneview.isAsyncLoadPending
import io.github.sceneview.isFenceBusy
import io.github.sceneview.model.Model
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.safeDestroyModel
import io.github.sceneview.utils.loadFileBuffer
import io.github.sceneview.utils.readBuffer
import io.github.sceneview.whenIdle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.Buffer
import java.nio.ByteBuffer

/**
 * Consumes a blob of glTF 2.0 content (either JSON or GLB) and produces a [Model] object, which is
 * a bundle of Filament textures, vertex buffers, index buffers, etc.
 *
 * A [Model] is composed of 1 or more [ModelInstance] objects which contain entities and components.
 *
 * **`.3mf` is loaded too, through every entry point on this class** (#3482). 3MF is what ChatGPT,
 * every slicer and every image-to-print flow emit for a printable model; a 3MF payload is detected
 * by its ZIP magic and converted to GLB in memory by [ThreeMfLoader] before it reaches Filament, so
 * `loadModel("print.3mf")`, `createModelInstance(uri)` and `rememberModelInstance(...)` need no
 * separate API and no separate code path. Conversion scales the model from its declared unit
 * (usually millimetres) to metres and rotates it from 3MF's Z-up to glTF's Y-up. A payload that is
 * not a ZIP costs one 4-byte comparison.
 *
 * **Binary and ASCII `.stl` also load through every entry point** (#3486):
 * `rememberModelInstance(modelLoader, "part.stl")`. Payload sniffing checks binary size/count
 * before ASCII solid/facet tokens, without copying other formats. STL has no units: the default
 * is millimetres, converted to metres, with the file's axes preserved. For another unit, first use
 * `StlLoader.toGlb(bytes, unit = ThreeMfUnit.INCH)` from `sceneview-core` and load that GLB.
 * Valid facet normals are kept; zero/invalid normals are repaired with smooth vertex normals.
 *
 * The `suspend` [loadModel] converts off the main thread; the [createModel] overloads convert on
 * the calling (main) thread, like the rest of their work.
 *
 * **A failed `load*Async` reports `null` (an empty list for [loadInstancedModelAsync]) and is
 * logged**: a missing file, an unreachable URL or an unparsable payload never reaches the app's
 * uncaught-exception handler (nor a `CoroutineExceptionHandler` in [coroutineScope]), and the
 * loader keeps loading. The `suspend` loads let the same exceptions through to their caller. A
 * `.gltf` whose external `.bin` buffer cannot be read fails to load (`null`, logged, its model
 * destroyed): gltfio would otherwise return a model with no geometry. A texture that cannot be
 * read is logged and skipped: the model loads without it.
 *
 * @param coroutineScope the scope the `load*Async` calls run in, inside a child scope of the
 * loader's own: [clear] cancels the loads it started there and nothing else, and [destroy] ends
 * that child scope, never [coroutineScope] itself.
 */
class ModelLoader(
    val engine: Engine,
    private val context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    // Where the `load*Async` calls run: a supervisor child of [coroutineScope], so a failed load
    // never cancels its siblings, and `clear()` / `destroy()` reach this loader's loads only — not
    // the other coroutines of a scope the app passed in (a `lifecycleScope`, say).
    private val loadScope = CoroutineScope(
        coroutineScope.coroutineContext + SupervisorJob(coroutineScope.coroutineContext[Job])
    )

    val materialProvider = UbershaderProvider(engine)
    val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())

    /**
     * The gltfio loader that finalises every model's textures, pumped by [updateLoad].
     *
     * Read it through this property each time rather than keeping your own reference: when a
     * model's load has to be cancelled before its textures finished decoding, this property moves
     * to a fresh loader (see [destroyModel]). The previous one stays valid until [destroy].
     */
    var resourceLoader = ResourceLoader(engine, true)

    // Loaders swapped out by an interrupted cancel (see `asyncLoad`), kept alive until `destroy()`:
    // gltfio's Java `ResourceLoader.destroy()` does not zero its native handle, so any caller still
    // holding the old reference (an app, a progress read on another thread) would otherwise call
    // into freed memory.
    private val retiredResourceLoaders = mutableListOf<ResourceLoader>()

    private val models = java.util.Collections.synchronizedList(mutableListOf<Model>())
//    private val modelInstances = mutableListOf<ModelInstance>()

    /**
     * Gets the status of an asynchronous resource load as a percentage in [0,1].
     *
     * **This is not a "still loading?" flag.** It reports Filament's progress over the resources of
     * the last [asyncBeginLoad][com.google.android.filament.gltfio.ResourceLoader.asyncBeginLoad],
     * and a loader that was never asked for one reports `0`, not `1`. Ask [isLoading] instead.
     */
    val progress get() = if (lifecycle.canTouchNatives) resourceLoader.asyncGetLoadProgress() else 0f

    // Written on the main thread only (both `asyncBeginLoad` call sites hop to it, and `updateLoad`
    // is the render loop). Volatile because `isLoading` is public: a progress UI reading it from a
    // composition on another dispatcher must not see a stale `true` forever.
    @Volatile
    private var asyncLoadStarted = false

    // The model gltfio's ResourceLoader last began an async load for, i.e. the only one whose
    // textures can still be decoding (#3868). Every destroy path goes through `destroyModel`, which
    // cancels that load before freeing the model. Main thread only, like both callbacks.
    private val asyncLoad = AsyncLoadSlot<Model>(
        beginLoad = { model ->
            asyncLoadStarted = true
            // `false` when the asset's resources were already loaded, or when its meshopt
            // compressed buffers fail to decode (ResourceLoader.cpp, `loadResources`). Both return
            // before any texture is queued. A missing buffer still returns `true`, see
            // GltfBufferUris: that is why every load checks the buffers itself before beginning.
            resourceLoader.asyncBeginLoad(model)
        },
        restoreLoad = { previous ->
            // A refused begin left the native load pointing at the refused asset, while
            // `previous` may still be decoding on this same loader (no cancel or swap happens with
            // an item in flight). Begin it again: it stores the pointer first, then returns
            // `false` at once because its resources are already loaded, with no other effect.
            resourceLoader.asyncBeginLoad(previous)
        },
        cancelLoad = { inFlight ->
            // `asyncCancelLoad` drops the asset pointer `updateLoad` would otherwise keep
            // dereferencing, after waiting for the decoder jobs already running (stb cannot
            // interrupt one), then flushes the engine. When it interrupted a load, the providers
            // retire the unfinished textures without counting them as finalised: progress could
            // never reach 1 again on this loader and `isLoading` would hold the render loop awake
            // forever. A fresh loader replaces it.
            val interrupted = inFlight != null && resourceLoader.asyncGetLoadProgress() < 1f
            resourceLoader.asyncCancelLoad()
            if (interrupted) {
                resourceLoader.evictResourceData()
                retiredResourceLoaders += resourceLoader
                resourceLoader = ResourceLoader(engine, true)
            }
            asyncLoadStarted = false
        },
        finishLoad = {
            // The load is done (#3981), or it was refused with no earlier load in flight, which
            // queued no texture: `asyncCancelLoad` would join no decoder and only `flushAndWait`
            // the engine, a backend round trip on the main thread. Its one other
            // effect, dropping the asset pointer before the asset is freed, comes from retiring
            // this loader instead: nothing of it is left to finalise, and a fresh one takes over.
            retiredResourceLoaders += resourceLoader
            resourceLoader = ResourceLoader(engine, true)
            asyncLoadStarted = false
        },
    )

    // Model destroys waiting for their load to settle (#3981): the loader's own natives outlive them.
    private val settlingDestroys = SettlingDestroys(engine)

    // Asked before every call into gltfio that a destroyed loader or engine would turn into a
    // native abort (see LoaderLifecycle).
    private val lifecycle = LoaderLifecycle(isEngineAlive = { engine.isValid })

    // The GPU's largest texture side, which GltfLimits holds every model's textures to: a larger
    // one is a PreconditionPanic in gltfio's texture providers. Read here, on the engine's thread.
    private val maxTextureSize = Texture.getMaxTextureSize(engine, Texture.Sampler.SAMPLER_2D)

    /**
     * Whether an asynchronous resource load is in flight — i.e. one was started and Filament has
     * not finished finalising its textures.
     *
     * This is the honest form of "is this scene still loading?", and the one `SceneView` renders
     * on: texture finalisation happens inside the frame loop, so a scene that parked while a load
     * was outstanding would sit there untextured. [progress] alone cannot answer it, because `0`
     * means both "nothing started" and "started, nothing done yet" — see
     * [io.github.sceneview.isAsyncLoadPending].
     */
    val isLoading: Boolean get() = lifecycle.canTouchNatives && isAsyncLoadPending(asyncLoadStarted, progress)

    /**
     * Creates a [Model] from the contents of a GLB or GLTF [Buffer].
     *
     * @param buffer             The binary glTF/GLB data.
     * @param releaseSourceData  When `true` (default), calls [FilamentAsset.releaseSourceData]
     *                           after resource loading to free the glTF hierarchy. Set to `false`
     *                           if you need to call [createInstance] on this model later.
     * @param resourceResolver   Resolves GLTF external resource file names to buffers.
     * @return The loaded [Model].
     * @throws IllegalArgumentException if the buffer cannot be parsed, or if one of its external
     * `.bin` buffers cannot be resolved (the model is destroyed and the miss logged). A texture the
     * resolver cannot read is logged and skipped.
     *
     * WebP glTF textures (`EXT_texture_webp`) embedded in the payload are re-encoded to PNG first,
     * because Filament's Android build registers no `image/webp` decoder and would otherwise render
     * the model untextured (#2305). That costs one decode + PNG encode per WebP texture, **on the
     * calling thread** here; the `suspend` [loadModel] does it off the main thread instead. A model
     * without WebP textures is untouched.
     *
     * @see AssetLoader.createAsset
     */
    @MainThread
    fun createModel(
        buffer: Buffer,
        releaseSourceData: Boolean = true,
        resourceResolver: (resourceFileName: String) -> Buffer? = { null }
    ): Model {
        lifecycle.checkCanCreate("create a model")
        val source = buffer.toFilamentModelSource()
        GltfLimits.check(source, maxTextureSize)
        val model = assetLoader.createAsset(source)
            ?: throw IllegalArgumentException("Failed to parse glTF model from buffer")
        models += model
        loadResources(model, source, resourceResolver)
        if (releaseSourceData) {
            model.releaseSourceData()
        }
        return model
    }

    /**
     * Creates a [Model] from the contents of a GLB or GLTF asset file.
     *
     * @see createModel
     */
    @MainThread
    fun createModel(
        assetFileLocation: String,
        releaseSourceData: Boolean = true,
        resourceResolver: (resourceFileName: String) -> Buffer? = { resourceFile ->
            context.assets.readBuffer(getFolderPath(assetFileLocation, resourceFile))
        }
    ): Model = createModel(context.assets.readBuffer(assetFileLocation), releaseSourceData, resourceResolver)

    /**
     * Creates a [Model] from the contents of a GLB or GLTF raw file.
     *
     * @see createModel
     */
    @MainThread
    fun createModel(
        @RawRes rawResId: Int,
        releaseSourceData: Boolean = true,
        resourceResolver: (resourceFileName: String) -> Buffer? = { null }
    ): Model = createModel(context.resources.readBuffer(rawResId), releaseSourceData, resourceResolver)

    /**
     * Creates a [Model] from the contents of a GLB or GLTF file.
     *
     * @see createModel
     */
    @MainThread
    fun createModel(
        file: File,
        releaseSourceData: Boolean = true,
        resourceResolver: (resourceFileName: String) -> Buffer? = { resourceFile ->
            File(file.parent, resourceFile).readBuffer()
        }
    ): Model = createModel(file.readBuffer(), releaseSourceData, resourceResolver)

    /**
     * Loads a [Model] from the contents of a GLB or GLTF file.
     *
     * @param fileLocation the .glb or .gltf file location:
     * - A relative asset file location *models/mymodel.glb*
     * - An android resource from the res folder *context.getResourceUri(R.raw.mymodel)*
     * - A File path *Uri.fromFile(myModelFile).path*
     * - An http or https url *https://mydomain.com/mymodel.glb*
     *
     * @see createModel
     */
    suspend fun loadModel(
        fileLocation: String,
        resourceResolver: (resourceFileName: String) -> String = { getFolderPath(fileLocation, it) }
    ): Model? = loadModelThen(fileLocation, resourceResolver, assetLoader::createAsset) { it }

    /**
     * Shared body of every suspend load: reads and converts the file off the main thread, creates
     * the asset with [create] on the main thread, fetches its external resources, then begins its
     * async load and runs [onLoadBegun] in **one** main-thread hop.
     *
     * That hop feeds the resources and begins the load together, so an interrupted cancel (which
     * swaps [resourceLoader], see `asyncLoad`) can never split a model's resources across two
     * loaders. It also checks the model is still alive first: `clear()` can destroy it while its
     * resources are fetched, and `asyncBeginLoad` or any JNI call on a freed asset is a native
     * crash (#3868). The result is `null` when the file, the parse or the model itself is gone,
     * and when one of the model's external buffers cannot be read: that model is destroyed rather
     * than handed out with no geometry (see [GltfBufferUris]). Each of these is logged.
     */
    private suspend fun <R : Any> loadModelThen(
        fileLocation: String,
        resourceResolver: (resourceFileName: String) -> String,
        create: (Buffer) -> Model?,
        onLoadBegun: (Model) -> R,
    ): R? {
        val buffer = context.loadFileBuffer(fileLocation) ?: run {
            Log.w(TAG, "Could not read $fileLocation")
            return null
        }
        // Transcoding decodes and re-encodes images: keep it off the main thread.
        val source = withContext(Dispatchers.Default) { buffer.toFilamentModelSource() }
        val violations = withContext(Dispatchers.Default) { GltfLimits.violations(source, maxTextureSize) }
        if (violations.isNotEmpty()) {
            Log.w(TAG, "Could not load $fileLocation: ${GltfLimitException(violations).message}")
            return null
        }
        // Registering into `models` happens *inside* the create block, in the same
        // uninterrupted hop onto `dispatcher` as the asset creation itself (#3523): that is
        // what guarantees a cancellation landing right after creation still finds the model
        // in the registry, so createOrDestroyOnCancel's own destroy(created) call below goes
        // through the same claim-then-destroy path as every other destroyModel() caller
        // instead of racing it.
        val model = createOrDestroyOnCancel(::destroyModel) {
            // A plain `loadModel` runs in its caller's scope, which `destroy()` does not cancel.
            if (!lifecycle.canCreate) return@createOrDestroyOnCancel null
            create(source)?.also { models += it }
        } ?: run {
            if (lifecycle.canCreate) {
                Log.w(TAG, "Could not parse $fileLocation")
            } else {
                Log.w(TAG, "Not loading $fileLocation: this ModelLoader or its Engine was destroyed")
            }
            return null
        }
        // Whatever stops the load from here on — a cancellation, a resource resolver that throws,
        // a missing buffer — destroys the model: it is registered, but no caller will ever hold it.
        return try {
            destroyOnFailure(model, ::destroyModel) {
                // `data:` URIs are decoded by gltfio itself; only the others are fetched here.
                val uris = withContext(Dispatchers.Main) {
                    model.takeIf { it in models }?.resourceUris
                }?.filterNot { it.startsWith("data:") } ?: return@destroyOnFailure null
                val bufferUris =
                    if (uris.isEmpty()) emptySet() else withContext(Dispatchers.Default) { GltfBufferUris.of(source, uris) }
                val resources = uris.mapNotNull { uri ->
                    loadResourceOrNull(fileLocation, resourceResolver(uri), isBuffer = uri in bufferUris)
                        ?.let { uri to it }
                }
                val missing = bufferUris - resources.mapTo(HashSet()) { it.first }
                if (missing.isNotEmpty()) {
                    throw UnloadableModelException("Could not load $fileLocation without ${missing.joinToString()}")
                }
                if (resources.isNotEmpty()) {
                    val loaded = resources.toMap()
                    val tooLarge = withContext(Dispatchers.Default) {
                        GltfLimits.violations(source, maxTextureSize, loaded::get)
                    }
                    if (tooLarge.isNotEmpty()) {
                        throw UnloadableModelException("Could not load $fileLocation: ${GltfLimitException(tooLarge).message}")
                    }
                }
                withContext(Dispatchers.Main) {
                    // `destroy()` frees the resource loader once the models settle: none to feed.
                    if (model !in models || !lifecycle.canCreate) return@withContext null
                    resources.forEach { (uri, data) -> resourceLoader.addResourceData(uri, data) }
                    if (!asyncLoad.begin(model)) {
                        // A fresh asset is refused only when its meshopt buffers fail to decode,
                        // before gltfio queues any texture. `asyncLoad.begin` already handed the
                        // slot back to the earlier load if one is in flight, so the destroy that
                        // follows the throw is immediate; otherwise it detaches this load through
                        // `finishLoad`, without `asyncCancelLoad`'s `flushAndWait`.
                        // Dropping the resources fed above is safe even with an earlier load still
                        // decoding: in Filament 1.72.1 the texture providers copy their source
                        // bytes when a texture is pushed, and the buffers were already uploaded.
                        resourceLoader.evictResourceData()
                        throw UnloadableModelException(
                            "gltfio refused to begin the resource load of $fileLocation " +
                                "(its meshopt-compressed data could not be decoded)",
                        )
                    }
                    onLoadBegun(model)
                }
            }
        } catch (failure: UnloadableModelException) {
            Log.w(TAG, "${failure.message}; the model was destroyed")
            null
        }
    }

    /** A load that cannot go on (a buffer missing, a texture too large); [loadModelThen] destroys its model. */
    private class UnloadableModelException(message: String) : Exception(message)

    /**
     * Reads one external resource of [fileLocation], or `null` when it cannot be read; either way
     * the miss is logged.
     *
     * A missing texture only leaves the model without it. A missing buffer ([isBuffer]) fails the
     * whole load instead (see [loadModelThen]): gltfio would begin it anyway and hand back a model
     * with no geometry.
     */
    private suspend fun loadResourceOrNull(
        fileLocation: String,
        resourceLocation: String,
        isBuffer: Boolean,
    ): Buffer? {
        val outcome = if (isBuffer) "the model cannot load without it" else "loading the model without it"
        return try {
            context.loadFileBuffer(resourceLocation)
                ?: null.also { Log.w(TAG, "Could not read $resourceLocation for $fileLocation; $outcome") }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "Could not load $resourceLocation for $fileLocation; $outcome", error)
            null
        }
    }

    /**
     * Loads a [Model] from the contents of a GLB or GLTF file within a self owned coroutine scope.
     *
     * @param fileLocation the .glb or .gltf file location:
     * - A relative asset file location *models/mymodel.glb*
     * - An android resource from the res folder *context.getResourceUri(R.raw.mymodel)*
     * - A File path *Uri.fromFile(myModelFile).path*
     * - An http or https url *https://mydomain.com/mymodel.glb*
     * @param onResult called on the main thread with the result, or `null` if the load failed (the
     * failure is logged). Not called when the load is cancelled ([clear], [destroy] or the
     * returned [Job]).
     *
     * @see createModel
     */
    fun loadModelAsync(
        fileLocation: String,
        resourceResolver: (resourceFileName: String) -> String = {
            getFolderPath(fileLocation, it)
        },
        onResult: (Model?) -> Unit
    ): Job = launchLoad(fileLocation, failed = null, onResult) {
        loadModel(fileLocation, resourceResolver)
    }

    /**
     * Creates a single [ModelInstance] from the contents of a GLB or GLTF [Buffer].
     *
     * Source data is released immediately since the model will not be re-instantiated.
     *
     * @see createModel
     */
    @MainThread
    fun createModelInstance(
        buffer: Buffer,
        resourceResolver: (resourceFileName: String) -> Buffer? = { null }
    ): ModelInstance = createModel(buffer, releaseSourceData = true, resourceResolver).instance

    /**
     * Creates a single [ModelInstance] from the contents of a GLB or GLTF asset file.
     *
     * Source data is released immediately since the model will not be re-instantiated.
     *
     * @see createModel
     */
    @MainThread
    fun createModelInstance(
        assetFileLocation: String,
        resourceResolver: (resourceFileName: String) -> Buffer? = {
            context.assets.readBuffer(getFolderPath(assetFileLocation, it))
        }
    ): ModelInstance = createModel(assetFileLocation, releaseSourceData = true, resourceResolver).instance

    /**
     * Creates a single [ModelInstance] from the contents of a GLB or GLTF raw resource.
     *
     * Source data is released immediately since the model will not be re-instantiated.
     *
     * @see createModel
     */
    @MainThread
    fun createModelInstance(
        @RawRes rawResId: Int,
        resourceResolver: (resourceFileName: String) -> Buffer? = { null }
    ): ModelInstance = createModel(rawResId, releaseSourceData = true, resourceResolver).instance

    /**
     * Creates a single [ModelInstance] from the contents of a GLB or GLTF file.
     *
     * Source data is released immediately since the model will not be re-instantiated.
     *
     * @see createModel
     */
    @MainThread
    fun createModelInstance(
        file: File,
        resourceResolver: (resourceFileName: String) -> Buffer? = { resourceFile ->
            File(file.parent, resourceFile).readBuffer()
        }
    ): ModelInstance = createModel(file, releaseSourceData = true, resourceResolver).instance

    /**
     * Loads a single [ModelInstance] from the contents of a GLB or GLTF file.
     *
     * Source data is released immediately since the model will not be re-instantiated.
     *
     * @see loadModel
     */
    suspend fun loadModelInstance(
        fileLocation: String,
        resourceResolver: (resourceFileName: String) -> String = { getFolderPath(fileLocation, it) }
    ): ModelInstance? = loadModelThen(fileLocation, resourceResolver, assetLoader::createAsset) { model ->
        // On the main thread, before anything else can destroy the model: releasing it after the
        // caller resumed (on the caller's thread) raced `clear()` into a JNI call on a freed
        // asset (#3868).
        model.releaseSourceData()
        model.instance
    }

    /**
     * Loads a single [ModelInstance] asynchronously from the contents of a GLB or GLTF file.
     *
     * Source data is released immediately since the model will not be re-instantiated.
     *
     * @param onResult called on the main thread with the result, or `null` if the load failed (the
     * failure is logged). Not called when the load is cancelled ([clear], [destroy] or the
     * returned [Job]).
     *
     * @see loadModel
     */
    fun loadModelInstanceAsync(
        fileLocation: String,
        resourceResolver: (resourceFileName: String) -> String = {
            getFolderPath(fileLocation, it)
        },
        onResult: (ModelInstance?) -> Unit
    ): Job = launchLoad(fileLocation, failed = null, onResult) {
        loadModelInstance(fileLocation, resourceResolver)
    }

    /**
     * Creates a [Model] with one or more [ModelInstance]s from the contents of a GLB or GLTF file.
     *
     * Consumes the contents of a glTF 2.0 file and produces a primary asset with one or more
     * instances.
     *
     * @param count must be sized to the desired number of instances. If successful, this method
     * will populate the array with secondary instances whose resources are shared with the primary
     * asset.
     *
     * Embedded WebP glTF textures are re-encoded to PNG on the calling thread first, as in
     * [createModel].
     *
     * @throws IllegalArgumentException if the buffer cannot be parsed, or if one of its external
     * `.bin` buffers cannot be resolved (the model is destroyed and the miss logged). A texture the
     * resolver cannot read is logged and skipped.
     *
     * @see AssetLoader.createInstancedAsset
     */
    @MainThread
    fun createInstancedModel(
        buffer: Buffer,
        count: Int,
        resourceResolver: (resourceFileName: String) -> Buffer? = { null }
    ): List<ModelInstance> =
        arrayOfNulls<ModelInstance>(count).apply {
            lifecycle.checkCanCreate("create a model")
            val source = buffer.toFilamentModelSource()
            GltfLimits.check(source, maxTextureSize)
            (assetLoader.createInstancedAsset(source, this)
                ?: throw IllegalArgumentException("Failed to parse glTF model from buffer")).also { model ->
                models += model
                loadResources(model, source, resourceResolver)
                model.releaseSourceData()
            }
        }.filterNotNull()

    /**
     * Creates a primary [Model] with one or more [ModelInstance]s from the contents of a GLB or
     * GLTF file.
     *
     * @param count must be sized to the desired number of instances. If successful, this method
     * will populate the array with secondary instances whose resources are shared with the primary
     * asset.
     *
     * @see createInstancedModel
     */
    @MainThread
    fun createInstancedModel(
        assetFileLocation: String,
        count: Int,
        resourceResolver: (resourceFileName: String) -> Buffer? = {
            context.assets.readBuffer(getFolderPath(assetFileLocation, it))
        }
    ) = createInstancedModel(
        context.assets.readBuffer(assetFileLocation),
        count,
        resourceResolver
    )

    /**
     * Creates a primary [Model] with one or more [ModelInstance]s from the contents of a GLB or
     * GLTF file.
     *
     * @param count must be sized to the desired number of instances. If successful, this method
     * will populate the array with secondary instances whose resources are shared with the primary
     * asset.
     *
     * @see createInstancedModel
     */
    @MainThread
    fun createInstancedModel(
        @RawRes rawResId: Int,
        count: Int,
        resourceResolver: (resourceFileName: String) -> Buffer? = { null }
    ) = createInstancedModel(context.resources.readBuffer(rawResId), count, resourceResolver)


    /**
     * Creates a primary [Model] with one or more [ModelInstance]s from the contents of a GLB or
     * GLTF file.
     *
     * @param count must be sized to the desired number of instances. If successful, this method
     * will populate the array with secondary instances whose resources are shared with the primary
     * asset.
     *
     * @see createInstancedModel
     */
    @MainThread
    fun createInstancedModel(
        file: File,
        count: Int,
        resourceResolver: (resourceFileName: String) -> Buffer? = { resourceFile ->
            File(file.parent, resourceFile).readBuffer()
        }
    ) = createInstancedModel(file.readBuffer(), count, resourceResolver)

    /**
     * Loads a primary [Model] with one or more [ModelInstance]s from the contents of a GLB or
     * GLTF file.
     *
     * @param fileLocation the .glb or .gltf file location:
     * - A relative asset file location *models/mymodel.glb*
     * - An android resource from the res folder *context.getResourceUri(R.raw.mymodel)*
     * - A File path *Uri.fromFile(myModelFile).path*
     * - An http or https url *https://mydomain.com/mymodel.glb*
     * @param count must be sized to the desired number of instances. If successful, this method
     * will populate the array with secondary instances whose resources are shared with the primary
     * asset.
     *
     * @see createInstancedModel
     */
    suspend fun loadInstancedModel(
        fileLocation: String,
        count: Int,
        resourceResolver: (resourceFileName: String) -> String = { getFolderPath(fileLocation, it) }
    ): List<ModelInstance> {
        val instances = arrayOfNulls<ModelInstance>(count)
        return loadModelThen(
            fileLocation,
            resourceResolver,
            create = { source ->
                assetLoader.createInstancedAsset(source, instances)
                    ?: throw IllegalArgumentException("Failed to parse glTF model from buffer")
            },
            onLoadBegun = { instances.filterNotNull() },
        ) ?: listOf()
    }

    /**
     * Loads a primary [Model] with one or more [ModelInstance]s from the contents of a GLB or
     * GLTF file.
     *
     * @param fileLocation the .glb or .gltf file location:
     * - A relative asset file location *models/mymodel.glb*
     * - An android resource from the res folder *context.getResourceUri(R.raw.mymodel)*
     * - A File path *Uri.fromFile(myModelFile).path*
     * - An http or https url *https://mydomain.com/mymodel.glb*
     * @param count must be sized to the desired number of instances. If successful, this method
     * will populate the array with secondary instances whose resources are shared with the primary
     * asset.
     * @param onResult called on the main thread with the result, or an empty list if the load failed (the
     * failure is logged). Not called when the load is cancelled ([clear], [destroy] or the
     * returned [Job]).
     *
     * @see loadInstancedModel
     */
    fun loadInstancedModelAsync(
        fileLocation: String,
        count: Int,
        resourceResolver: (resourceFileName: String) -> String = {
            getFolderPath(fileLocation, it)
        },
        onResult: (List<ModelInstance>) -> Unit
    ): Job = launchLoad(fileLocation, failed = listOf(), onResult) {
        loadInstancedModel(fileLocation, count, resourceResolver)
    }

    /**
     * Runs [load] in this loader's scope and hands its result to [onResult] on the main thread.
     *
     * A load that throws reports [failed] instead, and is logged: left to escape, the exception
     * reached the thread's uncaught-exception handler (a crash on Android) and, with a plain `Job`,
     * cancelled the scope, so every later `load*Async` on this loader did nothing at all.
     */
    private fun <T> launchLoad(
        fileLocation: String,
        failed: T,
        onResult: (T) -> Unit,
        load: suspend () -> T,
    ): Job = loadScope.launch {
        val result = try {
            load()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "Could not load $fileLocation", error)
            failed
        }
        withContext(Dispatchers.Main) { onResult(result) }
    }

    /**
     * Adds a new instance to the asset.
     *
     * Use this with caution. It is more efficient to pre-allocate a max number of instances, and
     * gradually add them to the scene as needed. Instances can also be "recycled" by removing and
     * re-adding them to the scene.
     *
     * NOTE: destroyInstance() does not exist because gltfio favors flat arrays for storage of
     * entity lists and instance lists, which would be slow to shift. We also wish to discourage
     * create/destroy churn, as noted above.
     *
     * This cannot be called after FilamentAsset#releaseSourceData().
     * Animation is not supported in new instances.
     *
     * @see AssetLoader.createInstance
     */
    @MainThread
    fun createInstance(model: Model): ModelInstance? {
        lifecycle.checkCanCreate("create an instance")
        // gltfio reads the asset's native pointer without checking it: 0 once the asset is freed.
        check(model in models) { "Cannot create an instance of a model this ModelLoader destroyed or did not create" }
        return assetLoader.createInstance(model)
    }

    /**
     * Destroys [model]'s native resources — a no-op if [model] is not (or is no longer)
     * registered in [models].
     *
     * Goes through [claimAndDestroy] so every destroy path — this call, a cancelled
     * [loadModel]/[loadInstancedModel] coroutine's cleanup, [clear] — shares the same
     * synchronized claim on [models]. That closes #3523: a `destroyOnFailure` continuation
     * resuming after [clear] already tore this same model down (or the reverse) now finds it
     * already gone and returns instead of double-freeing the native asset — the segfault
     * `runCatching` could never have caught in the first place. See [safeDestroyModel] for why
     * "at most once" has to be enforced here rather than there.
     *
     * Safe at any point of the model's load (#3868): if gltfio is still decoding this model's
     * textures, that async load is cancelled first, so the per-frame [updateLoad] never writes into
     * the freed textures. Destroying an older model cancels nothing.
     *
     * **Never blocks the calling thread** (#3981): gltfio's cancel waits for the decoder jobs
     * already running and then for the engine's whole backend queue, seconds on a slow device — an
     * ANR when a scene is left mid-load. A load whose textures are all finalised is detached
     * without that cancel, so the model is destroyed before this call returns, as every other
     * model is. A model whose textures are still decoding is destroyed once they are done (the
     * load is pumped from the main looper meanwhile); until then its asset stays alive, off the
     * registry.
     */
    fun destroyModel(model: Model) {
        destroyAfterCancellingLoad(
            models,
            model,
            asyncLoad,
            // Pumped first: pops whatever gltfio finished since the last frame, so no texture of
            // the model is left queued to be marked ready after it is freed.
            isLoadFinished = {
                updateLoad()
                resourceLoader.asyncGetLoadProgress() >= 1f
            },
            whenLoadSettles = { inFlight, cancelThenDestroy ->
                settlingDestroys.defer(
                    isInFlight = { asyncLoad.inFlight === inFlight },
                    pumpedProgress = {
                        updateLoad()
                        resourceLoader.asyncGetLoadProgress()
                    },
                    destroy = cancelThenDestroy,
                )
            },
        ) {
            assetLoader.safeDestroyModel(it)
            // destroyAsset destroys every entity's components directly, bypassing
            // Node.destroy() entirely — the sibling Nodes' cached handles need to know a
            // reindex may have happened here too (#2978 review gap 2). A glTF asset carries
            // renderable components on every mesh entity and light components whenever
            // KHR_lights_punctual is present, so all three packed arrays can compact here,
            // not just TransformManager (#3123).
            engine.bumpTransformGeneration()
            engine.bumpRenderableGeneration()
            engine.bumpLightGeneration()
        }
    }

    /**
     * Cancels every load still running on this loader's scope and destroys every model it made.
     * The model whose textures are still decoding is destroyed once they are done, without
     * blocking the calling thread (see [destroyModel]).
     *
     * The loader stays usable: loads started afterwards run as usual. [destroy] is the end of it.
     */
    fun clear() {
        // The running loads only: cancelling the scope itself left every later `load*Async` a
        // job that was already cancelled, whose `onResult` never came.
        loadScope.coroutineContext.cancelChildren()

        // No blanket `asyncLoad.cancel()` here any more: the only load it could interrupt belongs
        // to a model in `models`, whose destroyModel below cancels it — once doing so is instant
        // (#3981). With nothing in flight it was a pure `flushAndWait` on the main thread.
        if (lifecycle.canTouchNatives) resourceLoader.evictResourceData()

        // A cancelled load's own cleanup (destroyOnFailure/createOrDestroyOnCancel, both wired
        // to destroyModel) may still be queued behind this call on the main dispatcher. That is
        // no longer a race to win: destroyModel's claim-then-destroy means whichever of the two
        // calls on a given model runs first destroys it, and the other finds it already removed
        // from `models` and does nothing (#3523).
        models.toList().forEach { destroyModel(it) }
    }

    /**
     * Destroys every model, then the loader's own gltfio objects.
     *
     * Never blocks the calling thread (#3981): when a model is still decoding its textures, both
     * it and the loader's natives are released once the decoders are done, polled from the main
     * looper. The engine outlives them — its own deferred destroy waits for them.
     */
    fun destroy() {
        // A second call (a DisposableEffect and an explicit destroy both running) would free the
        // gltfio objects twice.
        if (!lifecycle.requestDestroy()) return
        clear()
        loadScope.cancel()

        settlingDestroys.afterAll {
            // From here on, the render loop's next `updateLoad` and any `progress` read do nothing.
            // An engine destroyed first took these objects with it: freeing them calls into it.
            if (!lifecycle.release()) return@afterAll
            assetLoader.destroy()
            materialProvider.destroyMaterials()
            materialProvider.destroy()
            resourceLoader.destroy()
            retiredResourceLoaders.forEach { it.destroy() }
            retiredResourceLoaders.clear()
        }
    }

    fun updateLoad() {
        // The render loop can run one more frame after a view disposed its loader, or after the
        // engine went first: gltfio's ResourceLoader keeps its native handle once destroyed.
        if (!lifecycle.canTouchNatives) return
        // Allow the resource loader to finalize textures that have become ready.
        resourceLoader.asyncUpdateLoad()
        // Drop the latch as soon as Filament says the outstanding set is finalised, so `isLoading`
        // stops holding the render loop awake. `asyncGetLoadProgress()` is global over the
        // outstanding set, so one boolean is the right shape here, not a counter.
        if (asyncLoadStarted && resourceLoader.asyncGetLoadProgress() >= 1f) {
            asyncLoadStarted = false
        }
    }

    /**
     * The synchronous counterpart of [loadModelThen]'s resource step, same rule: a missing texture
     * is logged and skipped, a missing buffer (or a begin gltfio refuses) destroys [model] and
     * throws an `IllegalArgumentException`, rather than handing out a model with no geometry. Its
     * cause is the resolver's `IOException` for that buffer, when there was one. A resolver that
     * throws anything other than an `IOException` also destroys [model], then the error is
     * rethrown.
     *
     * This runs on the main thread, like the whole synchronous `createModel`, so the glTF JSON is
     * parsed (to tell buffers from textures) only once a resource is missing: a model whose
     * resources all resolve never pays for it. [loadModelThen] does that parse off the main thread.
     */
    @MainThread
    @Suppress("TooGenericExceptionCaught") // Destroy on any failure, then rethrow it unchanged.
    private fun loadResources(model: Model, source: Buffer, resourceResolver: (String) -> Buffer?) {
        // `data:` URIs are decoded by gltfio itself; only the others are resolved here.
        val uris = model.resourceUris.filterNot { it.startsWith("data:") }
        // Each resource the resolver could not supply, with the IOException it threw if it did.
        val unresolved = LinkedHashMap<String, IOException?>()
        // Each resource it did supply, for GltfLimits to measure the external textures in.
        val resolved = HashMap<String, Buffer>()
        // The missing buffers, among them.
        val missing: Map<String, IOException?> = try {
            for (uri in uris) {
                val data = try {
                    resourceResolver(uri)
                } catch (error: IOException) {
                    unresolved[uri] = error
                    continue
                }
                if (data != null) {
                    resourceLoader.addResourceData(uri, data)
                    resolved[uri] = data
                } else {
                    unresolved[uri] = null
                }
            }
            val bufferUris = if (unresolved.isEmpty()) emptySet() else GltfBufferUris.of(source, unresolved.keys)
            // Logged once each: a missing texture here, a missing buffer with the failure below.
            unresolved.forEach { (uri, error) ->
                if (uri !in bufferUris) Log.w(TAG, "Could not read $uri; loading the model without it", error)
            }
            unresolved.filterKeys { it in bufferUris }
        } catch (failure: Throwable) {
            // Not begun yet, so nothing is decoding: the destroy is immediate.
            resourceLoader.evictResourceData()
            destroyModel(model)
            Log.w(TAG, "Could not resolve the resources of a glTF model; the model was destroyed", failure)
            throw failure
        }
        // The external textures, measured now that they are read (the embedded ones were, before
        // the asset was created).
        val tooLarge = if (missing.isEmpty() && resolved.isNotEmpty()) {
            GltfLimits.violations(source, maxTextureSize, resolved::get)
        } else {
            emptyList()
        }
        val failure = when {
            missing.isNotEmpty() -> {
                val causes = missing.values.filterNotNull()
                IllegalArgumentException(
                    "Could not load a glTF model without ${missing.keys.joinToString()}",
                    causes.firstOrNull(),
                ).apply { causes.drop(1).forEach(::addSuppressed) }
            }
            // An external texture too large for the GPU would abort the app inside asyncBeginLoad.
            tooLarge.isNotEmpty() -> GltfLimitException(tooLarge)
            // A freshly created asset is refused only when its meshopt-compressed buffers fail to
            // decode (its resources were never loaded before), and before gltfio queues any texture.
            !asyncLoad.begin(model) -> IllegalArgumentException(
                "gltfio refused to begin the resource load of a glTF model " +
                    "(its meshopt-compressed data could not be decoded)",
            )
            else -> null
        }
        resourceLoader.evictResourceData()
        if (failure != null) {
            // A refused begin queued no texture. `asyncLoad.begin` handed the slot back to an
            // earlier load still in flight, so this destroy is immediate and leaves that load's
            // decoding alone; with none, destroyModel detaches the load through `finishLoad`,
            // without `asyncCancelLoad`'s `flushAndWait` on the main thread. The eviction above is
            // safe either way: in Filament 1.72.1 the texture providers copy their source bytes.
            destroyModel(model)
            Log.w(TAG, "${failure.message}; the model was destroyed", failure.cause)
            throw failure
        }
    }

    companion object {
        private const val TAG = "ModelLoader"

        /**
         * The location of [resourceFileName], a resource a glTF names relative to itself, next to
         * [baseFileName]. A file at the root of `assets/` (no folder) gets the bare name.
         */
        fun getFolderPath(baseFileName: String, resourceFileName: String) =
            baseFileName.substringBeforeLast('/', missingDelimiterValue = "").let { folder ->
                if (folder.isEmpty()) resourceFileName else "$folder/$resourceFileName"
            }
    }
}

/**
 * Removes [item] from [registry] and, only if it was actually present, runs [destroy] on it.
 *
 * The membership check and the removal are the same call — `MutableList.remove` on the
 * `Collections.synchronizedList` every [ModelLoader] registry is — so of any number of
 * concurrent callers passing the same [item], exactly one sees `true` and runs [destroy]; every
 * other caller finds [item] already gone and returns without touching it (#3523). This is the
 * claim-then-destroy pattern [ModelLoader.destroyModel] relies on to stay safe under a
 * cancelled-coroutine cleanup racing [ModelLoader.clear]: a native double-free is a `SIGSEGV`
 * that crashes the process before any `try`/`catch` runs, so the guard has to sit here, in front
 * of the call into `gltfio`, not wrapped around it.
 */
internal fun <T> claimAndDestroy(registry: MutableList<T>, item: T, destroy: (T) -> Unit) {
    if (registry.remove(item)) destroy(item)
}

/**
 * Tracks the one item a gltfio `ResourceLoader` is running an async load for (#3868).
 *
 * `asyncBeginLoad` keeps a single raw asset pointer, and a load it accepts first drains the
 * previous load's decoder jobs, so only the item of the latest accepted [begin] can still have
 * textures decoding. Destroying that item without cancelling lets the next `asyncUpdateLoad`
 * upload into freed textures: a native crash. Destroying any older item must *not* cancel, because
 * that would abort the newer item's decoding and leave it untextured.
 *
 * A begin gltfio refuses drains nothing: Filament 1.72.1 returns before the drain when meshopt
 * decompression fails (`ResourceLoader.cpp`, `loadResources`). The previous item, whose textures
 * may still be decoding, therefore stays the one in flight: [begin] puts it back in the slot and
 * re-points the native load at it through [restoreLoad].
 *
 * Filament-free so the ordering can be pinned in a JVM test; [ModelLoader] wires [beginLoad] and
 * [restoreLoad] to `ResourceLoader.asyncBeginLoad` and [cancelLoad] to
 * `ResourceLoader.asyncCancelLoad`. Main thread only, like every gltfio call.
 */
internal class AsyncLoadSlot<T : Any>(
    private val beginLoad: (T) -> Boolean,
    private val cancelLoad: (inFlight: T?) -> Unit,
    private val finishLoad: () -> Unit = {},
    private val restoreLoad: (previous: T) -> Unit = {},
) {

    /** The item whose load was begun last and not cancelled since, or `null`. */
    var inFlight: T? = null
        private set

    /**
     * Records [item] as in flight, then begins its load. The slot is filled first: the native
     * begin stores the asset pointer before anything else, whatever it then returns. Returns what
     * [beginLoad] returned: whether the load was accepted.
     *
     * A refused begin with a previous item in flight hands the slot back to that item, through
     * [restoreLoad], so destroying it still waits for or cancels its decoding, and [item] can be
     * destroyed at once. With nothing in flight before, [item] stays in the slot, so its destroy
     * clears the native pointer the refused begin left on it.
     */
    fun begin(item: T): Boolean {
        val previous = inFlight
        inFlight = item
        val accepted = beginLoad(item)
        if (!accepted && previous != null) {
            inFlight = previous
            restoreLoad(previous)
        }
        return accepted
    }

    /** Cancels whatever load is running, then empties the slot. */
    fun cancel() {
        cancelLoad(inFlight)
        inFlight = null
    }

    /** Cancels the running load only if it belongs to [item]. Returns whether it did. */
    fun cancelIfInFlight(item: T): Boolean {
        if (inFlight !== item) return false
        cancel()
        return true
    }

    /**
     * Empties the slot through [finishLoad] instead of a cancel, if [item]'s load is the one in
     * flight — for a load whose textures are all finalised, which has nothing left to cancel
     * (#3981). Returns whether it did.
     */
    fun finishIfInFlight(item: T): Boolean {
        if (inFlight !== item) return false
        finishLoad()
        inFlight = null
        return true
    }
}

/**
 * [claimAndDestroy], with [item]'s async load cancelled first when it is still the one in flight:
 * the order Filament's own `ModelViewer` uses, applied to every single destroy (#3868). The cancel
 * sits inside the claim, so a second destroy of the same item finds it gone and cancels nothing.
 *
 * The item in flight whose load [isLoadFinished] is detached rather than cancelled and destroyed
 * right away. Otherwise its cancel-then-destroy goes through [whenLoadSettles], which may run it
 * later (#3981): the item is off the registry from this call on, so nothing else destroys it in
 * the meantime — and by then the load may have finished, which again skips the cancel. Any other
 * item is destroyed right away.
 */
internal fun <T : Any> destroyAfterCancellingLoad(
    registry: MutableList<T>,
    item: T,
    asyncLoad: AsyncLoadSlot<T>,
    isLoadFinished: () -> Boolean = { false },
    whenLoadSettles: (item: T, cancelThenDestroy: () -> Unit) -> Unit = { _, run -> run() },
    destroy: (T) -> Unit,
) = claimAndDestroy(registry, item) {
    // Only an unfinished load in flight has something to cancel, and so a reason to wait (#3981).
    when {
        asyncLoad.inFlight !== it -> destroy(it)
        isLoadFinished() -> {
            asyncLoad.finishIfInFlight(it)
            destroy(it)
        }
        else -> whenLoadSettles(it) {
            if (!(isLoadFinished() && asyncLoad.finishIfInFlight(it))) asyncLoad.cancelIfInFlight(it)
            destroy(it)
        }
    }
}

/** How long a load may make no progress before [AsyncLoadSettleGate] stops waiting for it. */
internal const val LOAD_SETTLE_STALL_MS = 10_000L

/**
 * The model destroys a [ModelLoader] holds back until their load settles (#3981), polled from the
 * main looper. `asyncCancelLoad` waits for every decoder job still running, then `flushAndWait`s
 * the engine: [defer] runs its destroy once an [AsyncLoadSettleGate] says the load has finished,
 * or stalled with the backend drained. [afterAll] runs the loader's own release after every
 * destroy still pending, since they need its natives — at once when there is none.
 *
 * Main thread only.
 */
private class SettlingDestroys(private val engine: Engine) {
    private var pending = 0

    fun defer(isInFlight: () -> Boolean, pumpedProgress: () -> Float, destroy: () -> Unit) {
        var fence: Fence? = null
        val gate = AsyncLoadSettleGate(
            isInFlight = isInFlight,
            pumpedProgress = pumpedProgress,
            isBackendBusy = { isFenceBusy(fence ?: engine.createFence().also { fence = it }) },
            now = SystemClock::uptimeMillis,
        )
        pending++
        engine.whenIdle(isBusy = gate::isBusy, onEngineGone = { pending-- }) {
            pending--
            fence?.let { runCatching { engine.destroyFence(it) } }
            destroy()
        }
    }

    fun afterAll(release: () -> Unit) {
        if (pending == 0) release() else engine.whenIdle(isBusy = { pending > 0 }) { release() }
    }
}

/**
 * Answers, poll after poll, "would gltfio's `asyncCancelLoad` still block right now?" for the
 * model a destroy is waiting on (#3981).
 *
 * `asyncCancelLoad` joins every texture decoder job still running (stb cannot interrupt one), then
 * `flushAndWait`s the engine. So it is busy while the model is still the load in flight and its
 * progress is below 1 — pumped by [pumpedProgress], which finalises the decoded textures. At 1 the
 * load is detached without a cancel ([destroyAfterCancellingLoad]), so nothing waits on the
 * backend. A model another load has replaced is never busy: `asyncBeginLoad` already joined its
 * decoders and there is nothing of it left to cancel.
 *
 * Progress can stay below 1 for good — a texture whose external file never arrived counts as
 * pending forever, with no job running for it — so a load that has not advanced for
 * [stallTimeoutMs] stops holding the destroy. That one is cancelled for real, so the gate then
 * waits for [isBackendBusy] to say the cancel's `flushAndWait` would be instant.
 *
 * Filament-free so the ordering can be pinned in a JVM test. Main thread only.
 */
internal class AsyncLoadSettleGate(
    private val isInFlight: () -> Boolean,
    private val pumpedProgress: () -> Float,
    private val isBackendBusy: () -> Boolean,
    private val now: () -> Long,
    private val stallTimeoutMs: Long = LOAD_SETTLE_STALL_MS,
) {
    private var lastProgress = Float.NaN
    private var lastAdvanceAt = 0L

    fun isBusy(): Boolean {
        if (!isInFlight()) return false
        val progress = pumpedProgress()
        if (progress >= 1f) return false
        val at = now()
        if (progress != lastProgress) {
            lastProgress = progress
            lastAdvanceAt = at
        }
        return at - lastAdvanceAt < stallTimeoutMs || isBackendBusy()
    }
}

/**
 * Runs [create] on [dispatcher] and returns its result — unless the caller was cancelled
 * in the meantime, in which case the result is handed to [destroy] before the
 * [CancellationException] propagates.
 *
 * This closes the window described in #3051: `withContext` delivers its result through
 * a cancellable resume, so a coroutine cancelled while the main-thread block is running
 * (or while the hop back is queued) resumes with [CancellationException] and the value
 * the block returned is dropped on the floor. For a Filament asset that value is a live
 * GPU-side model nothing else references — `rememberModelInstance` in `sceneview-compose`
 * swaps sources by cancelling the producer, which is exactly this sequence. The block
 * itself never starts once the caller is cancelled, so [created] is either unset or
 * holds the one value that would otherwise leak.
 *
 * The dispatcher is a parameter only so the mechanism can be pinned in a JVM unit test;
 * production callers leave it on [Dispatchers.Main], the Filament JNI contract.
 */
internal suspend fun <T : Any> createOrDestroyOnCancel(
    destroy: (T) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    create: () -> T?,
): T? {
    var created: T? = null
    try {
        return withContext(dispatcher) { create()?.also { created = it } }
    } catch (cancellation: CancellationException) {
        created?.let { withContext(NonCancellable + dispatcher) { destroy(it) } }
        throw cancellation
    }
}

/**
 * Runs [block] and, if it fails or is cancelled, destroys [created] — the model a load has
 * already built but not yet returned to the caller — before rethrowing.
 *
 * Resource loading suspends once per external buffer; a source swap that lands there, or a
 * resource that cannot be fetched, would otherwise leave the half-loaded model in
 * [ModelLoader]'s list until the loader is destroyed, with no caller holding a handle to free it
 * earlier — one more GPU-resident model at every retry.
 */
internal suspend fun <T : Any, R> destroyOnFailure(
    created: T,
    destroy: (T) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    block: suspend () -> R,
): R {
    try {
        return block()
    } catch (failure: Throwable) {
        withContext(NonCancellable + dispatcher) { destroy(created) }
        throw failure
    }
}

/**
 * Normalises whatever the caller handed us into something Filament's glTF loader accepts: 3MF,
 * STL, OBJ and PLY are converted to GLB, then WebP textures are transcoded.
 */
private fun Buffer.toFilamentModelSource(): Buffer =
    convertThreeMfToGlb().convertStlToGlb().convertObjToGlb().convertPlyToGlb()
        .transcodeWebPTextures()

/**
 * Converts a **3MF** payload to GLB, so `.3mf` is loadable through every entry point on this class
 * with no separate API — the format ChatGPT and every slicer emit for a printable model, which no
 * Android app opened in 3D before (#3482).
 *
 * The sniff is a ZIP-magic check plus a central-directory lookup for `3D/3dmodel.model`, so a glTF,
 * a GLB or anything else costs one 4-byte comparison and is returned untouched.
 */
private fun Buffer.convertThreeMfToGlb(): Buffer {
    val source = this as? ByteBuffer ?: return this
    // Cheap gate first: no ZIP magic, no copy. A 100 MB GLB must not be duplicated in memory just
    // to find out it is not a 3MF.
    if (!source.startsWithZipMagic()) return this
    val bytes = ByteArray(source.remaining()).also { source.duplicate().get(it) }
    if (!ThreeMfLoader.isThreeMf(bytes)) return this
    val glb = ThreeMfLoader.toGlb(bytes)
    // Direct, like every other buffer Filament's JNI layer reads (see WebPTextureTranscoder).
    return ByteBuffer.allocateDirect(glb.size).put(glb).apply { rewind() }
}

/**
 * OBJ has no magic: inspect only a bounded prefix before copying the full payload. Absolute reads
 * and a duplicate preserve the caller's position/limit, including sliced and read-only buffers.
 * Automatic loading assumes millimetres and grey; use ObjLoader.toGlb with a resolver for MTL.
 */
private fun Buffer.convertObjToGlb(): Buffer {
    val source = this as? ByteBuffer ?: return this
    if (!source.startsWithObjGeometry()) return this
    val bytes = ByteArray(source.remaining()).also { source.duplicate().get(it) }
    val glb = ObjLoader.toGlb(bytes)
    return ByteBuffer.allocateDirect(glb.size).put(glb).apply { rewind() }
}

/** PLY has an exact first line, so other formats cost at most a six-byte absolute comparison. */
private fun Buffer.convertPlyToGlb(): Buffer {
    val source = this as? ByteBuffer ?: return this
    if (!source.startsWithPlyHeader()) return this
    val bytes = ByteArray(source.remaining()).also { source.duplicate().get(it) }
    val glb = PlyLoader.toGlb(bytes)
    return ByteBuffer.allocateDirect(glb.size).put(glb).apply { rewind() }
}

private fun ByteBuffer.startsWithPlyHeader(): Boolean {
    val at = position()
    if (!startsWithAscii(at, "ply\n") && !startsWithAscii(at, "ply\r\n")) return false
    return true
}

/** JSON and binary GLB fail the cheap gate; other candidates cost at most a 4 KB prefix copy. */
private fun ByteBuffer.startsWithObjGeometry(): Boolean {
    if (!hasRemaining()) return false
    val start = position()
    val first = get(start).toInt() and 0xff
    if (first !in OBJ_LEAD_BYTES) return false
    if (startsWithAscii(start, "glTF")) return false
    // Include one extra byte so isObj can distinguish a truncated line from a real EOF at 4096.
    val prefix = ByteArray(minOf(remaining(), 4097)).also { duplicate().get(it) }
    return ObjLoader.isObj(prefix)
}

/** Whitespace, '#', the UTF-8 BOM lead byte, and the first letter of every OBJ keyword. */
private val OBJ_LEAD_BYTES: Set<Int> = (9..13).toSet() + setOf(32, 35, 0xef) + "vfogmuslp".map { it.code }

private fun ByteBuffer.startsWithAscii(start: Int, text: String): Boolean =
    remaining() >= text.length && text.indices.all { get(start + it) == text[it].code.toByte() }

/** `PK` — the local-file-header magic every ZIP, and so every 3MF, starts with. */
private fun ByteBuffer.startsWithZipMagic(): Boolean {
    val at = position()
    return remaining() >= 4 &&
        get(at) == 'P'.code.toByte() &&
        get(at + 1) == 'K'.code.toByte() &&
        get(at + 2) == 0x03.toByte() &&
        get(at + 3) == 0x04.toByte()
}

/**
 * Re-encodes WebP glTF textures to PNG, because Filament's Android build registers no
 * `image/webp` texture provider and would otherwise render the model untextured (#2305).
 *
 * A payload without WebP textures — the common case — is returned untouched.
 */
private fun Buffer.transcodeWebPTextures(): Buffer =
    (this as? ByteBuffer)?.let { WebPTextureTranscoder.transcode(it) } ?: this
