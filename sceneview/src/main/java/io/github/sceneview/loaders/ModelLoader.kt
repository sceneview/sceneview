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
import io.github.sceneview.bumpTransformGeneration
import io.github.sceneview.model.Model
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.safeDestroyModel
import io.github.sceneview.utils.loadFileBuffer
import io.github.sceneview.utils.readBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.Buffer

/**
 * Consumes a blob of glTF 2.0 content (either JSON or GLB) and produces a [Model] object, which is
 * a bundle of Filament textures, vertex buffers, index buffers, etc.
 *
 * A [Model] is composed of 1 or more [ModelInstance] objects which contain entities and components.
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
     * @see AssetLoader.createAsset
     */
    @MainThread
    fun createModel(
        buffer: Buffer,
        releaseSourceData: Boolean = true,
        resourceResolver: (resourceFileName: String) -> Buffer? = { null }
    ): Model = (assetLoader.createAsset(buffer)
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
        val model = withContext(Dispatchers.Main) {
            assetLoader.createAsset(buffer)
        } ?: return@let null
        models += model
        loadResourcesSuspended(model) { resourceFileName: String ->
            context.loadFileBuffer(resourceResolver(resourceFileName))
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
     * @see AssetLoader.createInstancedAsset
     */
    @MainThread
    fun createInstancedModel(
        buffer: Buffer,
        count: Int,
        resourceResolver: (resourceFileName: String) -> Buffer? = { null }
    ): List<ModelInstance> =
        arrayOfNulls<ModelInstance>(count).apply {
            (assetLoader.createInstancedAsset(buffer, this)
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
        val model = withContext(Dispatchers.Main) {
            assetLoader.createInstancedAsset(buffer, instances)
        } ?: throw IllegalArgumentException("Failed to parse glTF model from buffer")
        models += model
        loadResourcesSuspended(model) { resourceFileName: String ->
            context.loadFileBuffer(resourceResolver(resourceFileName))
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
        // destroyAsset destroys every entity's transform component directly, bypassing
        // Node.destroy() entirely — the sibling Nodes' cached transformInstance/parentInstance
        // handles need to know a reindex may have happened here too (#2978 review gap 2).
        engine.bumpTransformGeneration()
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