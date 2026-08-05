package io.github.erkko68.filament.gltfio

import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.FilamentPlatform
import io.github.erkko68.filament.PlatformGap

/**
 * MaterialProvider supplies materials to glTF assets during loading.
 *
 * Implementations determine how glTF materials are rendered:
 * - UbershaderProvider: Uses pre-compiled ubershader materials (recommended)
 * - Custom providers: Can implement custom material mapping strategies
 *
 * @see UbershaderProvider
 * @see AssetLoader
 */
expect interface MaterialProvider {
    /**
     * Creates or fetches a compiled Filament material, then creates an instance from it.
     *
     * @param config Properties of the glTF material; may be mutated to trim unsupported features.
     * @param uvmap Output: mapping from glTF texcoord sets to Filament UV sets, written by the provider.
     * @param label Optional debug name for the material instance.
     * @param extras Optional glTF extras as stringified JSON (not part of the cache key).
     */
    fun createMaterialInstance(config: MaterialKey, uvmap: IntArray, label: String? = null, extras: String? = null): io.github.erkko68.filament.MaterialInstance?

    /** Creates or fetches the compiled Filament material corresponding to [config], without instancing it. */
    fun getMaterial(config: MaterialKey, uvmap: IntArray, label: String? = null): io.github.erkko68.filament.Material?

    /** Gets the provider's cache of compiled materials (weak references). */
    fun getMaterials(): Array<io.github.erkko68.filament.Material>

    /**
     * Returns true if the given vertex attribute must be present. Some providers (e.g.
     * ubershader) require dummy attribute values when the glTF model does not provide them.
     */
    fun needsDummyData(attrib: Int): Boolean

    /**
     * Destroys all cached materials. NOT called automatically on [destroy], which lets clients
     * take ownership of the cache if desired.
     */
    fun destroyMaterials()

    /** Frees the provider itself (cached materials survive unless [destroyMaterials] was called). */
    fun destroy()
}

/**
 * UbershaderProvider uses pre-compiled ubershader materials.
 *
 * This is the recommended MaterialProvider for most use cases. It uses a small set of
 * pre-compiled, flexible materials that cover most glTF 2.0 features, avoiding the overhead
 * of JIT compilation while maintaining broad compatibility.
 *
 * @see MaterialProvider
 */
@PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "createMaterialInstance/getMaterial throw — filament.js does not expose the ubershader material provider; use precompiled .filamat materials on web.")
expect class UbershaderProvider : MaterialProvider {
    /**
     * Create an UbershaderProvider.
     *
     * @param engine Filament Engine to use for material creation.
     */
    constructor(engine: Engine)

    override fun createMaterialInstance(config: MaterialKey, uvmap: IntArray, label: String?, extras: String?): io.github.erkko68.filament.MaterialInstance?
    override fun getMaterial(config: MaterialKey, uvmap: IntArray, label: String?): io.github.erkko68.filament.Material?
    override fun getMaterials(): Array<io.github.erkko68.filament.Material>
    override fun needsDummyData(attrib: Int): Boolean
    override fun destroyMaterials()
    override fun destroy()
}
