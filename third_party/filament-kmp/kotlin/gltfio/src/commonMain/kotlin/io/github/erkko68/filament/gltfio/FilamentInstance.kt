package io.github.erkko68.filament.gltfio

import io.github.erkko68.filament.Box
import io.github.erkko68.filament.FilamentPlatform
import io.github.erkko68.filament.PlatformGap

/**
 * FilamentInstance provides access to a hierarchy of entities instanced from a glTF asset.
 *
 * Every entity has a TransformManager component. Some entities also have Name or Renderable
 * components. Instances share material and geometry data with the parent FilamentAsset but
 * maintain independent entity hierarchies, transforms, and animation states.
 *
 * **Key features:**
 * - Independent entity tree for each instance
 * - Skeletal animation via getAnimator() (independent per-instance or shared from asset)
 * - Material variant switching via applyMaterialVariant()
 * - Skin/skeleton access for joint manipulation
 * - Independent bounding box and transform root
 *
 * **Animation ownership:**
 * Each instance has its own Animator, but it can also be obtained from the parent
 * FilamentAsset. Using the asset's animator means all instances share the same animation frame;
 * using instance animators allows independent control.
 *
 * @see FilamentAsset
 * @see Animator
 * @see AssetLoader
 */
expect class FilamentInstance {
    /**
     * Create a new FilamentInstance.
     *
     * Instances are normally created via AssetLoader.createInstance(asset) but can be
     * manually constructed for advanced use cases.
     */
    constructor()

    /** Gets the transform root entity of this instance, which has no matching glTF node. */
    fun getRoot(): Int

    /**
     * Gets the entities of this instance, one per glTF node. All have a Transform component;
     * some also have a Renderable or Name component.
     */
    fun getEntities(): IntArray

    /** Gets the number of entities returned by [getEntities]. */
    fun getEntityCount(): Int

    /**
     * Returns the animation engine for this instance.
     *
     * An animator can be obtained either from an individual instance (independent per-instance
     * playback) or from the originating [FilamentAsset] (frame shared amongst all instances).
     * The animator is owned by the asset — do not destroy it manually.
     */
    fun getAnimator(): Animator

    /**
     * Gets the axis-aligned bounding box from the min/max values in the glTF accessors,
     * transformed for this instance.
     */
    fun getBoundingBox(): Box

    /** Gets the [FilamentAsset] that owns this instance. */
    fun getAsset(): FilamentAsset

    /** Gets the number of skins declared in the asset. */
    fun getSkinCount(): Int

    /** Gets the names of all skins, in skin-index order. */
    fun getSkinNames(): Array<String>

    /**
     * Attaches the given skin to the given node entity, which must have an associated mesh with
     * BONE_INDICES and BONE_WEIGHTS attributes. No-op if the skin index or target is invalid.
     */
    fun attachSkin(skinIndex: Int, target: Int)

    /** Detaches the given skin from the given node entity. No-op if skin index or target is invalid. */
    fun detachSkin(skinIndex: Int, target: Int)

    /** Gets the number of joints in the skin at [skinIndex]. */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "returns 0 — filament.js exposes no joint API.")
    fun getJointCountAt(skinIndex: Int): Int

    /** Gets the joint entities of the skin at [skinIndex]. */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "returns an empty array — filament.js exposes no joint API.")
    fun getJointsAt(skinIndex: Int): IntArray

    /**
     * Applies the material variant at [variantIndex] to all primitives in this instance.
     * Ignored if the index is out of bounds.
     *
     * @see getMaterialVariantNames
     */
    fun applyMaterialVariant(variantIndex: Int)

    /** Gets all material instances of this instance. These are already bound to renderables. */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws at runtime with embind 'unbound types' — the vector return type is unregistered in the web prebuilt.")
    fun getMaterialInstances(): Array<io.github.erkko68.filament.MaterialInstance>

    /** Gets the names of all material variants declared in the asset, in variant-index order. */
    fun getMaterialVariantNames(): Array<String>
}
