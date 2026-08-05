package io.github.erkko68.filament.gltfio

/**
 * gltfio (glTF I/O) is a loader and pipeline for glTF 2.0 assets in Filament.
 *
 * gltfio consumes glTF 2.0 content (JSON or GLB format) and produces FilamentAsset objects
 * containing Filament textures, vertex buffers, index buffers, and entities. Assets can be
 * composed of one or more FilamentInstance objects with entities and components.
 *
 * **Typical workflow:**
 *
 * ```
 * Gltfio.init()  // Initialize once at startup
 *
 * val loader = AssetLoader.create(engine, materialProvider)
 * val asset = loader.createAsset(gltfData)
 *
 * val resourceLoader = ResourceLoader(engine)
 * resourceLoader.loadResources(asset)  // Load textures and data
 *
 * val instance = asset.getInstance()
 * scene.addEntity(instance.getRoot())
 *
 * loader.destroyAsset(asset)
 * AssetLoader.destroy(loader)
 * ```
 *
 * **Key classes:**
 * - AssetLoader: Parses glTF and creates FilamentAsset objects
 * - FilamentAsset: Owns loaded entities, materials, and resources
 * - FilamentInstance: A single instance of an asset with animations and skins
 * - Animator: Applies skeletal animations
 * - MaterialProvider: Supplies materials (ubershader or JIT-compiled)
 * - ResourceLoader: Loads textures and vertex/index buffer data
 *
 * @see AssetLoader
 * @see FilamentAsset
 * @see ResourceLoader
 */
expect object Gltfio {
    /**
     * Initialize gltfio.
     *
     * Initializes the gltfio JNI layer on Android and sets up any necessary native state on
     * other platforms. Must be called once before using any gltfio functionality.
     */
    fun init()
}
