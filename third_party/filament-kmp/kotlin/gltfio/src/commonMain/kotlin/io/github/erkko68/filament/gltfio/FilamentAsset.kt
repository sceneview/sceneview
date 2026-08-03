package io.github.erkko68.filament.gltfio

import io.github.erkko68.filament.Box
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Entity
import io.github.erkko68.filament.FilamentPlatform
import io.github.erkko68.filament.PlatformGap

/**
 * FilamentAsset owns a loaded glTF 2.0 asset and all its Filament objects.
 *
 * A FilamentAsset represents a complete glTF scene with a hierarchy of entities, each having
 * a Transform component. Some entities also have Renderable, Light, Camera, or animation components.
 *
 * Assets own strong references to VertexBuffer, IndexBuffer, Texture objects, and optionally
 * an Animator for skeletal animations. External resource loading (textures, buffer data) is
 * handled separately via ResourceLoader.
 *
 * **Note:** Only the default glTF scene is loaded; other glTF scenes are ignored.
 *
 * @see AssetLoader
 * @see FilamentInstance
 * @see ResourceLoader
 */
expect class FilamentAsset {
    /**
     * Gets the transform root of the asset, an extra entity with no matching glTF node.
     *
     * It exists so the entire asset can be transformed as one. For instanced assets this is a
     * "super root" whose children are the per-instance roots, allowing all instances to be
     * moved en masse.
     */
    fun getRoot(): Entity

    /**
     * Pops a ready renderable off the async-load queue, or returns 0 if none is ready.
     *
     * Allows progressive reveal: add renderables to the scene as their textures become ready
     * during [ResourceLoader.asyncBeginLoad], e.g. `while ((e = popRenderable()) != 0) scene.addEntity(e)`.
     * Use [ResourceLoader.asyncGetLoadProgress] for the overall progress. Progressive reveal is
     * not supported for dynamically added instances.
     */
    fun popRenderable(): Entity

    /**
     * Pops up to `entities.size` ready renderables off the async-load queue into [entities].
     *
     * @return the number of entities written.
     * @see popRenderable
     */
    fun popRenderables(entities: IntArray): Int

    /**
     * Gets the list of entities, one per glTF node. All have a Transform component; some also
     * have a Renderable and/or Light component.
     */
    fun getEntities(): IntArray

    /** Gets the entities representing lights. All of these have a Light component. */
    fun getLightEntities(): IntArray

    /** Gets the entities that have Renderable components. */
    fun getRenderableEntities(): IntArray

    /**
     * Gets the entities representing cameras. All of these have a Camera component.
     *
     * gltfio always sets a perspective projection with aspect ratio 1.0 and then applies the
     * glTF file's aspect ratio through the camera's *scaling* matrix, so clients can adjust
     * the aspect ratio independently of the projection:
     * `camera.setScaling(1.0 / newAspectRatio, 1.0)`.
     */
    fun getCameraEntities(): IntArray

    /** Gets all entities whose name label matches [name] exactly. */
    fun getEntitiesByName(name: String): IntArray

    /** Gets all entities whose name label starts with [prefix]. */
    fun getEntitiesByPrefix(prefix: String): IntArray

    /** Returns the first entity with the given name, or 0 if none exists. */
    fun getFirstEntityByName(name: String): Entity

    /** Gets the number of entities returned by [getEntities]. */
    fun getEntityCount(): Int

    /** Returns the number of instances created from this asset (>= 1 unless detached). */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws at runtime with embind 'unbound types' — the vector return type is unregistered in the web prebuilt.")
    fun getAssetInstanceCount(): Int

    /** Returns every [FilamentInstance] created from this asset. */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws at runtime with embind 'unbound types' — the vector return type is unregistered in the web prebuilt.")
    fun getAssetInstances(): Array<FilamentInstance>

    /**
     * Gets the bounding box computed from the min/max values in the glTF accessors.
     *
     * This is a straightforward load-time AABB over the asset data — it does not account for
     * per-instance transforms (see [FilamentInstance.getBoundingBox] for that).
     */
    fun getBoundingBox(): Box

    /** Gets the name label for the given entity, or null if it has none. */
    fun getName(entity: Entity): String?

    /** Gets the glTF `extras` string for the given node entity (or for the asset itself), if any. */
    fun getExtras(entity: Entity): String?

    /** Gets the morph target names declared on the given entity, in target order. */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "returns an empty array — not exposed by filament.js.")
    fun getMorphTargetNames(entity: Entity): Array<String>

    /** Gets the URIs of all externally-referenced buffers/textures (to feed [ResourceLoader]). */
    fun getResourceUris(): Array<String>

    /**
     * Reclaims CPU-side memory for URI strings, binding lists, and raw animation data.
     *
     * Call only after [ResourceLoader.loadResources]. On an instanced asset this prevents the
     * creation of new instances.
     */
    fun releaseSourceData()

    /** Returns the [Engine] associated with the [AssetLoader] that created this asset. */
    fun getEngine(): Engine

    /** Convenience accessor for the first instance ([getAssetInstances]`[0]`). */
    fun getInstance(): FilamentInstance
}
