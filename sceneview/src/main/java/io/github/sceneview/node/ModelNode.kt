package io.github.sceneview.node

import androidx.annotation.IntRange
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.gltfio.Animator
import com.google.android.filament.gltfio.FilamentAsset
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.max
import com.google.android.filament.Box
import io.github.sceneview.Entity
import io.github.sceneview.components.RenderableComponent
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.model.Model
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.camerasEntities
import io.github.sceneview.model.collisionShape
import io.github.sceneview.model.emptyNodeEntities
import io.github.sceneview.model.engine
import io.github.sceneview.model.getAnimationIndex
import io.github.sceneview.model.lightEntities
import io.github.sceneview.model.model
import io.github.sceneview.model.renderableEntities
import io.github.sceneview.utils.intervalSeconds
import kotlin.math.abs

/**
 * Create the ModelNode from a loaded model instance.
 *
 * Use your own single [MaterialLoader] instance to load your
 * gltF form different .glTF/.glb resource locations.
 *
 * @see ModelLoader
 */
open class ModelNode(
    /**
     * The [ModelInstance] to add to scene. Usually a renderable 3D model.
     */
    val modelInstance: ModelInstance,
    /**
     * Plays the animations automatically if the model has one.
     */
    autoAnimate: Boolean = true,
    /**
     * Scale the model to fit into a unit (usually meters) cube.
     *
     * Default is `null` to keep model original size.
     */
    scaleToUnits: Float? = null,
    /**
     * Center the model origin to this unit cube position.
     *
     * - `null` = Keep the original model center point
     * - `Position(x = 0.0f, y = 0.0f, z = 0.0f)` = Center the model horizontally and vertically
     * - `Position(x = 0.0f, y = -1.0f, z = 0.0f)` = center horizontal | bottom aligned
     * - `Position(x = -1.0f, y = 1.0f, z = 0.0f)` = left | top aligned
     * - ...
     */
    centerOrigin: Position? = null
) : Node(engine = modelInstance.engine, entity = modelInstance.root) {

    interface ChildNode {
        val entity: Entity
        val modelInstance: ModelInstance
        val model get() = modelInstance.asset

        /**
         * Gets the `NameComponentManager` label for the given node, if it exists.
         */
        val name: String? get() = model.getName(entity)

        /**
         * Gets the glTF extras string for the asset or a specific node.
         */
        val extras: String? get() = model.getExtras(entity)

        /**
         * Gets the names of all morph targets in the given entity.
         */
        val morphTargetNames: List<String> get() = model.getMorphTargetNames(entity).toList()
    }

    inner class RenderableNode internal constructor(
        override val modelInstance: ModelInstance, entity: Entity
    ) : io.github.sceneview.node.RenderableNode(engine = modelInstance.engine, entity = entity),
        ChildNode {
        override var name = super<ChildNode>.name
    }

    inner class LightNode internal constructor(
        override val modelInstance: ModelInstance, entity: Entity
    ) : io.github.sceneview.node.LightNode(engine = modelInstance.engine, entity = entity),
        ChildNode {
        override var name = super<ChildNode>.name
    }

    inner class CameraNode internal constructor(
        override val modelInstance: ModelInstance, entity: Entity
    ) : io.github.sceneview.node.CameraNode(engine = modelInstance.engine, entity = entity),
        ChildNode {
        override var name = super<ChildNode>.name
    }

    inner class EmptyNode internal constructor(
        override val modelInstance: ModelInstance, entity: Entity
    ) : Node(engine = modelInstance.engine, entity = entity),
        ChildNode {
        override var name = super<ChildNode>.name
    }

    data class PlayingAnimation(
        val startTime: Long = System.nanoTime(),
        var speed: Float = 1f,
        val loop: Boolean = true
    )

    val renderableNodes = modelInstance.renderableEntities.map {
        RenderableNode(modelInstance, it)
    }
    val lightNodes = modelInstance.lightEntities.map {
        LightNode(modelInstance, it).apply { parent = this@ModelNode }
    }
    val cameraNodes = modelInstance.camerasEntities.map {
        CameraNode(modelInstance, it).apply { parent = this@ModelNode }
    }
    val emptyNodes = modelInstance.emptyNodeEntities.map {
        EmptyNode(modelInstance, it)
    }

    val nodes: List<Node> =
        (renderableNodes + emptyNodes + lightNodes + cameraNodes)

    /**
     * The source [Model] ([FilamentAsset]) from the [ModelInstance].
     */
    val model get() = modelInstance.model

    /**
     * Called when an exception occurs during [onFrame] (e.g. bone matrix update, popRenderable).
     *
     * Set this to handle frame errors programmatically (crash reporting, analytics, UI feedback).
     * When `null` (default), errors are logged via `android.util.Log.e`.
     */
    var onFrameError: ((Exception) -> Unit)? = null

    /**
     * Gets the bounding box computed from the supplied min / max values in glTF accessors.
     *
     * This does not return a bounding box over all FilamentInstance, it's just a straightforward
     * AAAB that can be determined at load time from the asset data.
     */
    val boundingBox get() = model.boundingBox
    val halfExtent get() = boundingBox.halfExtent.let { v -> Float3(v[0], v[1], v[2]) }
    val extents get() = halfExtent * 2.0f
    val center get() = boundingBox.center.let { v -> Float3(v[0], v[1], v[2]) }
    val size get() = extents * scale

    /**
     * Retrieves the [Animator] for this instance.
     */
    var animator = modelInstance.animator

    /**
     * The number of animation definitions in the glTF asset.
     */
    val animationCount get() = animator.animationCount

    var playingAnimations = mutableMapOf<Int, PlayingAnimation>()

    /**
     * Tracks renderable entities whose AABB was empty and had culling/shadows disabled.
     * Once the AABB becomes valid, culling and shadows are re-enabled and the entity is
     * removed from this set.
     */
    private val sanitizedEntities = mutableSetOf<Entity>()

    /**
     * Entities observed at least once with a non-empty AABB.
     *
     * Once an entity is here, its AABB is considered stable and we skip per-frame
     * re-checks — the only common reasons an AABB starts empty (skinning awaiting bone
     * transforms, async resource loads) are one-shot startup transitions that don't
     * recur during steady-state rendering. Allows [sanitizeEmptyBoundingBoxes] to
     * early-exit on the hot path once a model is fully loaded (#2273).
     */
    private val validatedEntities = mutableSetOf<Entity>()

    /**
     * Gets the skin count of this instance.
     */
    val skinCount: Int get() = modelInstance.skinCount

    /**
     * Returns the names of all material variants.
     */
    val materialVariantNames: List<String>
        get() = modelInstance.materialVariantNames.toList()

    /**
     * Gets the skin name at skin index in this instance.
     */
    val skinNames: List<String> get() = modelInstance.skinNames.toList()

    /**
     * Changes whether or not the renderable casts shadows.
     *
     * @see RenderableComponent.isShadowCaster
     */
    var isShadowCaster: Boolean
        get() = renderableNodes.all { it.isShadowCaster }
        set(value) = renderableNodes.forEach { it.isShadowCaster = value }

    /**
     * Changes whether or not the renderable can receive shadows.
     *
     * @see RenderableComponent.isShadowReceiver
     */
    var isShadowReceiver: Boolean
        get() = renderableNodes.all { it.isShadowReceiver }
        set(value) = renderableNodes.forEach { it.isShadowReceiver = value }

    init {
        nodes.forEach { node ->
            node.parent = when (val parentEntity = node.parentEntity) {
                this.entity -> this
                else -> nodes.firstOrNull { it.entity == parentEntity } ?: this
            }
        }
        // Sanitize renderable entities with empty AABBs to prevent Filament crash.
        // Filament 1.70+ enforces: "AABB can't be empty, unless culling is disabled
        // and the object is not a shadow caster/receiver".
        sanitizeEmptyBoundingBoxes()

        if (autoAnimate && animator.animationCount > 0) {
            for (i in 0 until animator.animationCount) { playAnimation(i) }
        }
        scaleToUnits?.let { scaleToUnitCube(it) }
        centerOrigin?.let { centerOrigin(it) }
        collisionShape = model.collisionShape
    }

    /**
     * Sets up a root transform on the current model to make it fit into a unit cube.
     *
     * @param units the number of units of the cube to scale into.
     */
    fun scaleToUnitCube(units: Float = 1.0f) {
        val halfExtent = boundingBox.halfExtent.let { v -> Float3(v[0], v[1], v[2]) }
        val maxExtent = max(halfExtent) * 2.0f
        // Guard against empty bounding boxes (all-zero extents) which would cause
        // division by zero, producing Infinity/NaN scale values.
        if (maxExtent > 0f) {
            scale = Scale(units / maxExtent)
        }
    }

    /**
     * Sets up a root transform on the current model to make it centered.
     *
     * @param origin Coordinate inside the model unit cube from where it is centered
     * - defaults to [0, 0, 0] will center the model on its center
     * - center horizontal | bottom aligned = [0, -1, 0]
     * - left | top aligned: [-1, 1, 0]
     */
    fun centerOrigin(origin: Position = Position(x = 0.0f, y = 0.0f, z = 0.0f)) {
        position += origin * size
    }

    /**
     * Applies rotation, translation, and scale to entities that have been targeted by the given
     * animation definition. Uses `TransformManager`.
     *
     * @param animationIndex Zero-based index for the `animation` of interest.
     * @param speed The rate at which the `animation` plays. Reverses the `animation` if negative.
     * Pauses the `animation` if zero.
     * @param loop Specifies if the `animation` should repeat forever.
     *
     * @see Animator.getAnimationCount
     */
    fun playAnimation(animationIndex: Int, speed: Float = 1f, loop: Boolean = true) {
        if (animationIndex < animationCount) {
            playingAnimations[animationIndex] = PlayingAnimation(speed = speed, loop = loop)
        }
    }

    /**
     * @see playAnimation
     * @see Animator.getAnimationName
     */
    fun playAnimation(animationName: String, speed: Float = 1f, loop: Boolean = true) {
        animator.getAnimationIndex(animationName)?.let { playAnimation(it, speed, loop) }
    }

    /**
     * Sets the rate at which the `animation` is played.
     *
     * @param animationIndex Zero-based index for the `animation` of interest.
     * @param speed The rate at which the `animation` plays. Reverses the `animation` if negative.
     * Pauses the `animation` if zero.
     * @see playAnimation
     */
    fun setAnimationSpeed(animationIndex: Int, speed: Float) {
        if (animationIndex < animationCount) {
            playingAnimations[animationIndex]?.speed = speed
        }
    }

    /**
     * @see setAnimationSpeed
     * @see Animator.getAnimationName
     */
    fun setAnimationSpeed(animationName: String, speed: Float) {
        animator.getAnimationIndex(animationName)?.let { setAnimationSpeed(it, speed) }
    }

    /**
     * Stops the animation at [animationIndex], removing it from the set of playing animations.
     *
     * @param animationIndex Zero-based index for the animation to stop.
     */
    fun stopAnimation(animationIndex: Int) {
        playingAnimations.remove(animationIndex)
    }

    /**
     * Stops the named animation, removing it from the set of playing animations.
     *
     * @param animationName The glTF animation name to stop.
     * @see stopAnimation
     * @see Animator.getAnimationName
     */
    fun stopAnimation(animationName: String) {
        animator.getAnimationIndex(animationName)?.let { stopAnimation(it) }
    }

    /**
     * Attaches the given skin to the given node, which must have an associated mesh with
     * BONE_INDICES and BONE_WEIGHTS attributes.
     *
     * This is a no-op if the given skin index or target is invalid.
     */
    fun attachSkin(target: Node, @IntRange(from = 0) skinIndex: Int = 0) =
        modelInstance.attachSkin(skinIndex, target.entity)

    /**
     * Attaches the given skin to the given node, which must have an associated mesh with
     * BONE_INDICES and BONE_WEIGHTS attributes.
     *
     * This is a no-op if the given skin index or target is invalid.
     */
    fun detachSkin(target: Node, @IntRange(from = 0) skinIndex: Int = 0) =
        modelInstance.detachSkin(skinIndex, target.entity)

    /**
     * Gets the joint count at skin index in this instance.
     */
    open fun getJointCount(@IntRange(from = 0) skinIndex: Int = 0): Int =
        modelInstance.getJointCountAt(skinIndex)

    /**
     * Changes the material instances binding for the given primitives.
     *
     * @see RenderableComponent.materialInstances
     */
    var materialInstances: List<List<MaterialInstance>>
        get() = renderableNodes.map { it.materialInstances }
        set(value) {
            value.forEachIndexed { index, materialInstances ->
                renderableNodes[index].materialInstances = materialInstances
            }
        }

    /**
     * Changes the material instance binding for all primitives.
     *
     * @see RenderableComponent.setMaterialInstances
     */
    fun setMaterialInstance(materialInstance: MaterialInstance) {
        renderableNodes.forEach { it.setMaterialInstances(materialInstance) }
    }

    /**
     * Updates the vertex morphing weights on a renderable, all zeroes by default.
     *
     * The renderable must be built with morphing enabled. In legacy morphing mode, only the first
     * 4 weights are considered.
     *
     * @see RenderableComponent.setMorphWeights
     */
    fun setMorphWeights(weights: FloatArray, @IntRange(from = 0) offset: Int = weights.size) =
        renderableNodes.forEach { it.setMorphWeights(weights, offset) }

    /**
     * Changes the visibility.
     *
     * @see RenderableManager.setLayerMask
     */
    fun setLayerVisible(visible: Boolean) = renderableNodes.forEach { it.setLayerVisible(visible) }

    /**
     * Changes the visibility bits.
     *
     * @see RenderableComponent.setLayerMask
     */
    fun setLayerMask(
        @IntRange(from = 0, to = 255) select: Int, @IntRange(from = 0, to = 255) value: Int
    ) = renderableNodes.forEach { it.setLayerMask(select, value) }

    /**
     * Changes the coarse-level draw ordering.
     *
     * @see RenderableComponent.setPriority
     */
    fun setPriority(@IntRange(from = 0, to = 7) priority: Int) =
        renderableNodes.forEach { it.setPriority(priority) }

    /**
     * Changes whether or not frustum culling is on.
     *
     * @see RenderableComponent.setCulling
     */
    fun setCulling(enabled: Boolean) = renderableNodes.forEach { it.setCulling(enabled) }

    /**
     * Changes whether or not the renderable can use screen-space contact shadows.
     *
     * @see RenderableComponent.setScreenSpaceContactShadows
     */
    fun setScreenSpaceContactShadows(enabled: Boolean) =
        renderableNodes.forEach { it.setScreenSpaceContactShadows(enabled) }

    /**
     * Changes the drawing order for blended primitives.
     *
     * The drawing order is either global or local (default) to this Renderable.
     * In either case, the Renderable priority takes precedence.
     *
     * @param blendOrder draw order number (0 by default). Only the lowest 15 bits are used.
     *
     * @see RenderableComponent.setBlendOrder
     */
    fun setBlendOrder(@IntRange(from = 0, to = 65535) blendOrder: Int) {
        renderableNodes.forEach { it.setBlendOrder(blendOrder) }
    }

    /**
     * Changes whether the blend order is global or local to this Renderable (by default).
     *
     * @param enabled true for global, false for local blend ordering.
     *
     * @see RenderableComponent.setGlobalBlendOrderEnabled
     */
    fun setGlobalBlendOrderEnabled(enabled: Boolean) {
        renderableNodes.forEach { it.setGlobalBlendOrderEnabled(enabled) }
    }

    override fun onFrame(frameTimeNanos: Long) {
        super.onFrame(frameTimeNanos)

        try {
            model.popRenderable()
            // Re-sanitize after popRenderable() which may make new entities available
            // for rendering that could have empty AABBs.
            sanitizeEmptyBoundingBoxes()
            applyAnimations(frameTimeNanos)
            animator.updateBoneMatrices()
        } catch (e: Exception) {
            onFrameError?.invoke(e)
                ?: android.util.Log.e("SceneView", "ModelNode.onFrame error", e)
        }
    }

    /**
     * Checks renderable entities for empty AABBs and disables culling + shadows on them.
     *
     * Filament 1.70+ crashes with "AABB can't be empty, unless culling is disabled and the
     * object is not a shadow caster/receiver". This can happen when:
     * - glTF sub-entities have no geometry yet (skinned meshes awaiting bone transforms)
     * - Resources are still loading asynchronously (popRenderable makes them available gradually)
     * - The model has auxiliary entities with no mesh data
     *
     * Once the AABB becomes valid (non-empty), culling and shadows are re-enabled.
     */
    private fun sanitizeEmptyBoundingBoxes() {
        // Steady-state fast path: every renderable has been observed valid at least
        // once and nothing is currently disabled. No per-frame JNI traffic needed
        // until popRenderable() yields a new entity.
        if (sanitizedEntities.isEmpty() && validatedEntities.size == renderableNodes.size) {
            return
        }
        val renderableManager = modelInstance.engine.renderableManager
        val box = Box()
        for (renderableNode in renderableNodes) {
            val entity = renderableNode.entity
            // Skip entities already known to be stable.
            if (entity in validatedEntities) continue

            val instance = renderableManager.getInstance(entity)
            if (instance == 0) continue

            renderableManager.getAxisAlignedBoundingBox(instance, box)
            val halfExtent = box.halfExtent
            val isEmpty = halfExtent[0] == 0f && halfExtent[1] == 0f && halfExtent[2] == 0f

            if (isEmpty && entity !in sanitizedEntities) {
                // Empty AABB: disable culling and shadows to prevent Filament crash
                renderableManager.setCulling(instance, false)
                renderableManager.setCastShadows(instance, false)
                renderableManager.setReceiveShadows(instance, false)
                sanitizedEntities += entity
            } else if (!isEmpty && entity in sanitizedEntities) {
                // AABB is now valid: re-enable culling and shadows
                renderableManager.setCulling(instance, true)
                renderableManager.setCastShadows(instance, true)
                renderableManager.setReceiveShadows(instance, true)
                sanitizedEntities -= entity
                validatedEntities += entity
            } else if (!isEmpty) {
                // First-time observation of a valid AABB: mark stable so future
                // frames skip this entity in the fast path above.
                validatedEntities += entity
            }
        }
    }

    private fun applyAnimations(frameTimeNanos: Long) {
        val iterator = playingAnimations.iterator()
        while (iterator.hasNext()) {
            val (index, animation) = iterator.next()
            if (animation.speed == 0f) continue

            animator.let { animator ->
                val elapsedTimeSeconds = frameTimeNanos.intervalSeconds(animation.startTime)
                val adjustedTimeSeconds = elapsedTimeSeconds.toFloat() * abs(animation.speed)
                val animationDuration = animator.getAnimationDuration(index)
                val animationTime: Float = if (animation.speed > 0) {
                    adjustedTimeSeconds
                } else {
                    animationDuration - adjustedTimeSeconds
                }

                animator.applyAnimation(index, animationTime)

                if (!animation.loop && adjustedTimeSeconds >= animationDuration) {
                    iterator.remove()
                }
            }
        }
    }
}

/** Returns the first child node whose [ModelNode.ChildNode.name] matches [name]. */
inline operator fun <reified T : ModelNode.ChildNode> List<T>.get(name: String): T =
    first { it.name == name }

/** Returns the first child node whose [ModelNode.ChildNode.name] matches [name], or `null`. */
inline fun <reified T : ModelNode.ChildNode> List<T>.getOrNull(name: String): T? =
    firstOrNull { it.name == name }

/** Returns the first [MaterialInstance] whose [MaterialInstance.getName] matches [name]. */
inline operator fun <reified T : MaterialInstance> List<T>.get(name: String): T =
    first { it.name == name }

/** Returns the first [MaterialInstance] whose name matches [name], or `null`. */
inline fun <reified T : MaterialInstance> List<T>.getOrNull(name: String): T? =
    firstOrNull { it.name == name }