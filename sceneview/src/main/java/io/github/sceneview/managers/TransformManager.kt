package io.github.sceneview.managers

import com.google.android.filament.Entity
import com.google.android.filament.EntityInstance
import com.google.android.filament.TransformManager
import io.github.sceneview.FilamentEntity
import io.github.sceneview.FilamentEntityInstance
import io.github.sceneview.math.Transform
import io.github.sceneview.math.copyColumnsInto
import io.github.sceneview.math.toTransform

/**
 * Main-thread scratch buffer reused by [setTransform] to avoid allocating a
 * `FloatArray(16)` on every transform write.
 *
 * Safe because Filament JNI calls run exclusively on the main thread (no
 * concurrent access) and `TransformManager.setTransform(instance, FloatArray)`
 * copies the array into native memory synchronously before returning, so the
 * buffer can be reused immediately on the next call.
 */
private val transformScratch = FloatArray(16)

/**
 * Returns the local transform of a transform component.
 *
 * @param transformInstance the [EntityInstance] of the transform component to query the
 * local transform from.
 * @return the local transform of the component (i.e. relative to the parent). This always
 * returns the value set by setTransform().
 * @see TransformManager.setTransform
 */
fun TransformManager.getTransform(@FilamentEntityInstance transformInstance: Int) =
    FloatArray(16).apply {
        getTransform(transformInstance, this)
    }.toTransform()

/**
 * Sets a local transform of a transform component.
 *
 * This operation can be slow if the hierarchy of transform is too deep, and this
 * will be particularly bad when updating a lot of transforms. In that case,
 * consider using [TransformManager.openLocalTransformTransaction] /
 * [TransformManager.commitLocalTransformTransaction].
 *
 * @param transformInstance the [EntityInstance] of the transform component to set the local
 * transform to.
 * @param localTransform the local transform (i.e. relative to the parent).
 * @see TransformManager.getTransform
 */
fun TransformManager.setTransform(
    @FilamentEntityInstance transformInstance: Int,
    localTransform: Transform
) = setTransform(transformInstance, localTransform.copyColumnsInto(transformScratch))

/**
 * Returns the world transform of a transform component.
 *
 * @param transformInstance the [EntityInstance] of the transform component to query the
 * world transform from.
 * @return The world transform of the component (i.e. relative to the root). This is the
 * composition of this component's local transform with its parent's world transform.
 * @see TransformManager.setTransform
 */
fun TransformManager.getWorldTransform(@FilamentEntityInstance transformInstance: Int) =
    FloatArray(16).apply {
        getWorldTransform(transformInstance, this)
    }.toTransform()

/**
 * Returns the actual parent entity of an [EntityInstance] originally defined by
 * [TransformManager.setParent].
 *
 * @param transformInstance the [EntityInstance] of the transform component to get the parent from.
 * @return the parent [Entity].
 * @see TransformManager.getInstance
 */
@FilamentEntity
fun TransformManager.getParentOrNull(@FilamentEntityInstance transformInstance: Int): Int? =
    getParent(transformInstance).takeIf { it != 0 }

/**
 * Re-parents an entity to a new one.
 *
 * @param transformInstance the [EntityInstance] of the transform component to re-parent.
 * @param newParent the [EntityInstance] of the new parent transform.
 * It is an error to re-parent an entity to a descendant and will cause undefined behaviour.
 * @see TransformManager.getInstance
 */
fun TransformManager.setParent(
    @FilamentEntityInstance transformInstance: Int,
    @FilamentEntityInstance newParent: Int?
) = setParent(transformInstance, newParent ?: 0)

/**
 * Gets a list of children for a transform component.
 *
 * @return Array of retrieved children [Entity].
 *
 * @see TransformManager.getChildren
 */
@FilamentEntity
fun TransformManager.getChildren(@FilamentEntityInstance transformInstance: Int): List<Int> =
    getChildren(transformInstance, null).toList()

/**
 * Gets a flat list of all children within the hierarchy for a transform component.
 *
 * @return Array of retrieved children [Entity].
 *
 * @see TransformManager.getChildren
 */
@FilamentEntity
fun TransformManager.getAllChildren(@FilamentEntityInstance transformInstance: Int): List<Int> {
    val children = getChildren(transformInstance)
    return children + children.flatMap { getAllChildren(getInstance(it)) }
}