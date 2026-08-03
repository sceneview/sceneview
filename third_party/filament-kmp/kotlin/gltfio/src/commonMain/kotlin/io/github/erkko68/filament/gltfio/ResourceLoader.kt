package io.github.erkko68.filament.gltfio

import io.github.erkko68.filament.Engine

/**
 * ResourceLoader loads external resources referenced by glTF assets.
 *
 * ResourceLoader handles:
 * - Loading textures from URIs
 * - Uploading vertex/index buffer data
 * - Computing tangent quaternions (via Mikktspace when needed)
 * - Async resource loading for progressive rendering
 *
 * **Typical usage:**
 * ```
 * val resourceLoader = ResourceLoader(engine)
 * resourceLoader.loadResources(asset)  // Synchronously load all resources
 * resourceLoader.destroy()
 * ```
 *
 * **Async usage:**
 * ```
 * resourceLoader.asyncBeginLoad(asset)
 * while (resourceLoader.asyncGetLoadProgress() < 1.0f) {
 *     resourceLoader.asyncUpdateLoad()  // Load in chunks
 * }
 * ```
 *
 * @see FilamentAsset
 * @see AssetLoader
 */
expect class ResourceLoader {
    /**
     * Create a ResourceLoader.
     *
     * @param engine Filament Engine to use for loading resources.
     * @param normalizeSkinningWeights Whether to normalize skinning weights to [0, 1] range.
     */
    constructor(engine: Engine, normalizeSkinningWeights: Boolean = false)

    /**
     * Destroys this loader and frees its internal caches. Must happen on the same thread that
     * calls `Renderer.render()`, because the loader listens to buffer callbacks to know when
     * CPU-side data blobs can be freed.
     */
    fun destroy()

    /**
     * Feeds the binary content of an external resource into the loader's URI cache.
     *
     * Every resource returned by [FilamentAsset.getResourceUris] should be added before calling
     * [loadResources] or [asyncBeginLoad]. Self-contained GLB files typically need no calls.
     */
    fun addResourceData(url: String, data: ByteArray)

    /** Checks whether the given resource URI has already been added via [addResourceData]. */
    fun hasResourceData(url: String): Boolean

    /**
     * Synchronously loads resources for [asset] from the URI cache and finalizes the asset:
     * transforms vertex data if necessary, decodes images, and supplies tangent data.
     *
     * @return false if resources were already loaded, or if one or more could not be loaded.
     * @see asyncBeginLoad
     */
    fun loadResources(asset: FilamentAsset): Boolean

    /**
     * Starts an asynchronous resource load (texture decoding may use worker threads).
     * Requires periodic calls to [asyncUpdateLoad] until [asyncGetLoadProgress] reaches 1.0.
     *
     * @return false if the loading process could not start.
     */
    fun asyncBeginLoad(asset: FilamentAsset): Boolean

    /** Gets the status of an asynchronous load as a percentage in `[0, 1]`. */
    fun asyncGetLoadProgress(): Float

    /**
     * Performs any pending main-thread work of an asynchronous load. Call periodically until
     * [asyncGetLoadProgress] returns 1.0; harmless after that.
     */
    fun asyncUpdateLoad()

    /**
     * Cancels pending decoder jobs, frees all CPU-side texel data, and flushes the Engine.
     * Only needed if [asyncBeginLoad] was used and cancellation is required before completion.
     */
    fun asyncCancelLoad()

    /**
     * Frees memory by evicting the URI cache populated via [addResourceData]. Call only after a
     * model is fully loaded or loading has been cancelled.
     */
    fun evictResourceData()
}
