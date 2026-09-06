package io.github.sceneview.loaders

import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.RawRes
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import io.github.sceneview.bumpLightGeneration
import io.github.sceneview.bumpRenderableGeneration
import io.github.sceneview.bumpTransformGeneration
import io.github.sceneview.core.obj.ObjLoader
import io.github.sceneview.core.threemf.ThreeMfLoader
import io.github.sceneview.model.Model
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.safeDestroyModel
import io.github.sceneview.utils.loadFileBuffer
import io.github.sceneview.utils.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
 */
class ModelLoader(
    val engine: Engine,
    private val context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    val materialProvider = UbershaderProvider(engine)
    val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
    var resourceLoader = ResourceLoader(engine, true)

    private val models = java.util.Collections.synchronizedList(mutableListOf<Model>())
//    private val modelInstances = mutableListOf<ModelInstance>()

    /**
     * Gets the status of an asynchronous resource load as a percentage in [0,1].
     */
    val progress get() = resourceLoader.asyncGetLoadProgress()

    /**
     * Creates a [Model] from the contents of a GLB or GLTF [Buffer].
     *
     * @param buffer             The binary glTF/GLB data.
     * @param releaseSourceData  When `true` (default), calls [FilamentAsset.releaseSourceData]
     *                           after resource loading to free the glTF hierarchy. Set to `false`
     *                           if you need to call [createInstance] on this model later.
     * @param resourceResolver   Resolves GLTF external resource file names to buffers.
     * @return The loaded [Model].
     * @throws IllegalArgumentException if the buffer cannot be parsed.
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
    ): Model = (assetLoader.createAsset(buffer.toFilamentModelSource())
        ?: throw IllegalArgumentException("Failed to parse glTF model from buffer")).also { model ->
        models += model
        loadResources(model, resourceResolver)
        if (releaseSourceData) {
            model.releaseSourceData()
        }
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
    ): Model? = context.loadFileBuffer(fileLocation)?.let { buffer ->
        // Transcoding decodes and re-encodes images: keep it off the main thread.
        val source = withContext(Dispatchers.Default) { buffer.toFilamentModelSource() }
        val model = createOrDestroyOnCancel(::destroyModel) {
            assetLoader.createAsset(source)
        } ?: return@let null
        models += model
        destroyOnCancel(model, ::destroyModel) {
            loadResourcesSuspended(model) { resourceFileName: String ->
                context.loadFileBuffer(resourceResolver(resourceFileName))
            }
        }
        model
    }

    /**
     * Loads a [Model] from the contents of a GLB or GLTF file within a self owned coroutine scope.
     *
     * @param fileLocation the .glb or .gltf file location:
     * - A relative asset file location *models/mymodel.glb*
     * - An android resource from the res folder *context.getResourceUri(R.raw.mymodel)*
     * - A File path *Uri.fromFile(myModelFile).path*
     * - An http or https url *https://mydomain.com/mymodel.glb*
     *
     * @see createModel
     */
    fun loadModelAsync(
        fileLocation: String,
        resourceResolver: (resourceFileName: String) -> String = {
            getFolderPath(fileLocation, it)
        },
        onResult: (Model?) -> Unit
    ): Job = coroutineScope.launch {
        val result = loadModel(fileLocation, resourceResolver)
        withContext(Dispatchers.Main) { onResult(result) }
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
    ): ModelInstance? = loadModel(fileLocation, resourceResolver)?.also {
        it.releaseSourceData()
    }?.instance

    /**
     * Loads a single [ModelInstance] asynchronously from the contents of a GLB or GLTF file.
     *
     * Source data is released immediately since the model will not be re-instantiated.
     *
     * @see loadModel
     */
    fun loadModelInstanceAsync(
        fileLocation: String,
        resourceResolver: (resourceFileName: String) -> String = {
            getFolderPath(fileLocation, it)
        },
        onResult: (ModelInstance?) -> Unit
    ): Job = loadModelAsync(fileLocation, resourceResolver) {
        it?.releaseSourceData()
        onResult.invoke(it?.instance)
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
     * @see AssetLoader.createInstancedAsset
     */
    @MainThread
    fun createInstancedModel(
        buffer: Buffer,
        count: Int,
        resourceResolver: (resourceFileName: String) -> Buffer? = { null }
    ): List<ModelInstance> =
        arrayOfNulls<ModelInstance>(count).apply {
            (assetLoader.createInstancedAsset(buffer.toFilamentModelSource(), this)
                ?: throw IllegalArgumentException("Failed to parse glTF model from buffer")).also { model ->
                models += model
                loadResources(model, resourceResolver)
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
    ): List<ModelInstance> = context.loadFileBuffer(fileLocation)?.let { buffer ->
        val instances = arrayOfNulls<ModelInstance>(count)
        val source = withContext(Dispatchers.Default) { buffer.toFilamentModelSource() }
        val model = createOrDestroyOnCancel(::destroyModel) {
            assetLoader.createInstancedAsset(source, instances)
        } ?: throw IllegalArgumentException("Failed to parse glTF model from buffer")
        models += model
        destroyOnCancel(model, ::destroyModel) {
            loadResourcesSuspended(model) { resourceFileName: String ->
                context.loadFileBuffer(resourceResolver(resourceFileName))
            }
        }
        instances.filterNotNull()
    } ?: listOf()

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
     * @see loadInstancedModel
     */
    fun loadInstancedModelAsync(
        fileLocation: String,
        count: Int,
        resourceResolver: (resourceFileName: String) -> String = {
            getFolderPath(fileLocation, it)
        },
        onResult: (List<ModelInstance>) -> Unit
    ): Job = coroutineScope.launch {
        val instances = loadInstancedModel(fileLocation, count, resourceResolver)
        withContext(Dispatchers.Main) {
            onResult(instances)
        }
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
    fun createInstance(model: Model): ModelInstance? = assetLoader.createInstance(model)

    fun destroyModel(model: Model) {
        assetLoader.safeDestroyModel(model)
        // destroyAsset destroys every entity's components directly, bypassing Node.destroy()
        // entirely — the sibling Nodes' cached handles need to know a reindex may have happened
        // here too (#2978 review gap 2). A glTF asset carries renderable components on every
        // mesh entity and light components whenever KHR_lights_punctual is present, so all three
        // packed arrays can compact here, not just TransformManager (#3123).
        engine.bumpTransformGeneration()
        engine.bumpRenderableGeneration()
        engine.bumpLightGeneration()
        models -= model
    }

    fun clear() {
        runCatching { coroutineScope.cancel() }

        resourceLoader.asyncCancelLoad()
        resourceLoader.evictResourceData()

        models.toList().forEach { destroyModel(it) }
        models.clear()
    }

    fun destroy() {
        clear()

        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()
        resourceLoader.destroy()
    }

    fun updateLoad() {
        // Allow the resource loader to finalize textures that have become ready.
        resourceLoader.asyncUpdateLoad()
    }

    /**
     * Feeds the binary content of an external resource into the loader's URI cache.
     */
    private fun loadResources(model: Model, resourceResolver: (String) -> Buffer?) {
        for (uri in model.resourceUris) {
            resourceResolver(uri)?.let { resourceLoader.addResourceData(uri, it) }
        }
        resourceLoader.asyncBeginLoad(model)
        resourceLoader.evictResourceData()
    }

    /**
     * Feeds the binary content of an external resource into the loader's URI cache.
     */
    private suspend fun loadResourcesSuspended(
        model: Model,
        resourceResolver: (suspend (String) -> Buffer?)
    ) {
        for (uri in model.resourceUris) {
            resourceResolver(uri)?.let {
                withContext(Dispatchers.Main) {
                    resourceLoader.addResourceData(uri, it)
                }
            }
        }
        withContext(Dispatchers.Main) {
            resourceLoader.asyncBeginLoad(model)
        }
    }

    companion object {
        fun getFolderPath(baseFileName: String, resourceFileName: String) =
            "${baseFileName.substringBeforeLast("/")}/$resourceFileName"
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
 * Runs [block] and, if it is cancelled, destroys [created] — the model a load has
 * already built but not yet returned to the caller — before rethrowing.
 *
 * Resource loading suspends once per external buffer; a source swap that lands there
 * would otherwise leave the half-loaded model in [ModelLoader]'s list until the loader
 * is destroyed, with no caller holding a handle to free it earlier.
 */
internal suspend fun <T : Any> destroyOnCancel(
    created: T,
    destroy: (T) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (cancellation: CancellationException) {
        withContext(NonCancellable + dispatcher) { destroy(created) }
        throw cancellation
    }
}

/**
 * Normalises whatever the caller handed us into something Filament's glTF loader accepts: 3MF, STL
 * and OBJ are converted to GLB, then WebP textures are transcoded.
 */
private fun Buffer.toFilamentModelSource(): Buffer =
    convertThreeMfToGlb().convertStlToGlb().convertObjToGlb().transcodeWebPTextures()

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