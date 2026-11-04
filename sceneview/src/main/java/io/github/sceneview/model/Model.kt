package io.github.sceneview.model

import com.google.android.filament.Box
import com.google.android.filament.gltfio.Animator
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.isReleased
import io.github.sceneview.Entity
import io.github.sceneview.math.toVector3Box
import java.util.WeakHashMap

/**
 * Type alias mapping Filament's [FilamentAsset] to the SceneView domain name [Model].
 *
 * A [Model] represents a loaded glTF/GLB asset containing meshes, materials,
 * animations, lights, and cameras.
 */
typealias Model = FilamentAsset

/**
 * `true` once this model was destroyed — by `ModelLoader.destroyModel`, `clear` or `destroy`, or
 * by `rememberModelInstance` leaving the composition.
 *
 * gltfio frees a destroyed model's instances and animator without clearing their handles, so any
 * Filament call on them afterwards reads freed memory: a native crash no `try` catches. Check this
 * before using a model, or a [ModelInstance] of it, that something else may have destroyed.
 */
val Model.isDestroyed: Boolean get() = isReleased

/**
 * Returns the debug names of all renderable entities in this model.
 * Useful for finding sub-meshes by name (e.g. for selective material overrides).
 */
val Model.renderableNames get() = renderableEntities.map { getName(it) }

/**
 * Computes an axis-aligned collision shape from the model's [rest-pose bounding box][restPoseBoundingBox].
 * Used by the [CollisionSystem][io.github.sceneview.collision.CollisionSystem] for hit testing.
 */
val Model.collisionShape get() = restPoseBoundingBox.toVector3Box()

/**
 * The model's bounds in its rest pose, skinning included (#4949): gltfio's [Model.getBoundingBox]
 * for a model without skins, and for a skinned one the box its vertices fill once moved by their
 * joints — not the accessors' unskinned min / max, which for a rig exported in centimetres under
 * an armature scaled by 0.01 is a hundred times too large.
 *
 * Computed once at load by `ModelLoader`, from the glTF itself: `FilamentInstance
 * .recomputeBoundingBoxes()` is not in gltfio's Java API, and needs the source data SceneView
 * releases once a model is loaded. Animation and morph targets are not followed.
 *
 * Read it instead of [Model.getBoundingBox] wherever a model is framed, scaled or placed from its
 * size: `ModelNode.boundingBox`, `scaleToUnits` and the collision shape already do.
 */
val Model.restPoseBoundingBox: Box get() = RestPoseBounds[this] ?: boundingBox

/** The [restPoseBoundingBox] of each skinned model `ModelLoader` loaded, by identity. */
internal object RestPoseBounds {
    // Weak: a model dropped without destroyModel still frees its entry.
    private val bounds = WeakHashMap<FilamentAsset, FloatArray>()

    /** Records [minMax] (`[minX, minY, minZ, maxX, maxY, maxZ]`) for [model], or forgets it when `null`. */
    @Synchronized
    fun record(model: Model, minMax: FloatArray?) {
        if (minMax == null || minMax.size != 6) bounds.remove(model) else bounds[model] = minMax.copyOf()
    }

    @Synchronized
    fun forget(model: Model) {
        bounds.remove(model)
    }

    /** A fresh [Box] each call, as gltfio's own: a caller may change it. */
    @Synchronized
    operator fun get(model: Model): Box? = bounds[model]?.let { b ->
        Box(
            (b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2,
            (b[3] - b[0]) / 2, (b[4] - b[1]) / 2, (b[5] - b[2]) / 2,
        )
    }
}

/**
 * Finds a renderable entity by its glTF node name.
 *
 * @param name the glTF node name to search for.
 * @return the entity ID, or `null` if no entity with that name exists.
 */
fun Model.getRenderableByName(name: String): Entity? =
    getFirstEntityByName(name).takeIf { it != 0 }

/**
 * Finds the index of an animation by its name.
 *
 * @param animationName the name assigned to the animation in the glTF file.
 * @return the zero-based animation index, or `null` if no animation matches.
 */
fun Animator.getAnimationIndex(animationName: String) =
    (0 until animationCount).firstOrNull { getAnimationName(it) == animationName }