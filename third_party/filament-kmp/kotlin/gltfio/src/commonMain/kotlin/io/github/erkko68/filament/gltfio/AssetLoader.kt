package io.github.erkko68.filament.gltfio

import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.EntityManager

/**
 * AssetLoader consumes glTF 2.0 content and produces FilamentAsset objects.
 *
 * AssetLoader parses a blob of glTF 2.0 content (either JSON or GLB format) and produces a
 * FilamentAsset object, which is a bundle of Filament textures, vertex buffers, index buffers,
 * and entities. An asset is composed of one or more FilamentInstance objects containing the
 * loaded scene hierarchy.
 *
 * **Clients must use AssetLoader to:**
 * - Create and destroy FilamentAsset objects (similar to how Engine creates core objects)
 * - Not fetch external buffer data or create textures (use ResourceLoader for this)
 *
 * **Material providers:**
 * AssetLoader uses MaterialProvider to determine how materials are created:
 * - UbershaderProvider: Uses a pre-compiled set of materials (recommended for performance)
 * - JIT-compiled materials: Generated on-the-fly using Filamat (more flexible)
 *
 * @see FilamentAsset
 * @see FilamentInstance
 * @see MaterialProvider
 * @see ResourceLoader
 */
expect class AssetLoader {
    companion object {
        /**
         * Create an AssetLoader instance.
         *
         * @param engine Filament Engine to use for creating buffers and textures.
         * @param materials MaterialProvider for supplying materials to the asset.
         * @param entities Optional EntityManager override (uses singleton if not provided).
         * @return A new AssetLoader instance.
         */
        fun create(engine: Engine, materials: MaterialProvider, entities: EntityManager? = null): AssetLoader

        /**
         * Destroy an AssetLoader and release all internal resources.
         *
         * @param loader The AssetLoader to destroy.
         */
        fun destroy(loader: AssetLoader)
    }

    /**
     * Create a FilamentAsset from glTF 2.0 data.
     *
     * Parses the glTF content and creates an asset with entities, materials, and buffers.
     * External resources (textures, buffer data) must be loaded separately using ResourceLoader.
     *
     * @param buffer ByteArray containing glTF 2.0 data (JSON or GLB format).
     * @return A new FilamentAsset, or null if parsing failed.
     */
    fun createAsset(buffer: ByteArray): FilamentAsset?

    /**
     * Create a FilamentAsset with pre-allocated instances.
     *
     * Similar to createAsset(), but allows specifying pre-built FilamentInstance objects
     * to avoid repeated instantiation of the same asset.
     *
     * @param buffer ByteArray containing glTF 2.0 data.
     * @param instances Array of pre-created FilamentInstance objects.
     * @return A new FilamentAsset with the provided instances, or null if parsing failed.
     */
    fun createInstancedAsset(buffer: ByteArray, instances: Array<FilamentInstance>): FilamentAsset?

    /**
     * Create a FilamentInstance from an existing FilamentAsset.
     *
     * Instances share material and geometry data but have independent entity hierarchies
     * and animations, allowing multiple renderings of the same asset with different transforms.
     *
     * @param asset The FilamentAsset to instantiate.
     * @return A new FilamentInstance, or null if creation failed.
     */
    fun createInstance(asset: FilamentAsset): FilamentInstance?

    /**
     * Enable diagnostic output for asset loading.
     *
     * When enabled, the loader produces verbose output useful for debugging glTF parsing issues.
     *
     * @param enable true to enable diagnostics, false to disable.
     */
    fun enableDiagnostics(enable: Boolean)

    /**
     * Destroy a FilamentAsset and release all associated resources.
     *
     * @param asset The FilamentAsset to destroy.
     */
    fun destroyAsset(asset: FilamentAsset)
}
