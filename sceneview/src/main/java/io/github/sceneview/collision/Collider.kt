// Derived from Google Sceneform (Apache-2.0), modified by the SceneView contributors.
package io.github.sceneview.collision

import io.github.sceneview.math.toMatrix
import io.github.sceneview.node.Node

/**
 * Represents the collision information associated with a transformation that can be attached to the
 * collision system. Not publicly exposed.
 *
 * @hide
 */
class Collider(
    @JvmField var node: Node
) {
    private var attachedCollisionSystem: CollisionSystem? = null

    private lateinit var localShape: CollisionShape
    private var cachedWorldShape: CollisionShape? = null

    private var isWorldShapeDirty = false
    private var shapeId = ChangeId.EMPTY_ID

    /**
     * @hide
     */
    fun setShape(localCollisionShape: CollisionShape) {
        Preconditions.checkNotNull(localCollisionShape, "Parameter \"localCollisionShape\" was null.")

        if (!::localShape.isInitialized || localShape !== localCollisionShape) {
            localShape = localCollisionShape
            cachedWorldShape = null
        }
    }

    /**
     * @hide
     */
    fun getShape(): CollisionShape = localShape

    /**
     * @hide
     */
    fun getTransformedShape(): CollisionShape? {
        updateCachedWorldShape()
        return cachedWorldShape
    }

    /**
     * @hide
     */
    fun setAttachedCollisionSystem(collisionSystem: CollisionSystem?) {
        attachedCollisionSystem?.removeCollider(this)

        attachedCollisionSystem = collisionSystem

        attachedCollisionSystem?.addCollider(this)
    }

    /**
     * @hide
     */
    fun markWorldShapeDirty() {
        isWorldShapeDirty = true
    }

    private fun doesCachedWorldShapeNeedUpdate(): Boolean {
        if (!::localShape.isInitialized) {
            return false
        }

        val changeId = localShape.getId()
        return changeId.checkChanged(shapeId) || isWorldShapeDirty || cachedWorldShape == null
    }

    private val transformProvider = TransformProvider {
        node.worldTransform.toMatrix()
    }

    private fun updateCachedWorldShape() {
        if (!doesCachedWorldShapeNeedUpdate()) {
            return
        }

        if (cachedWorldShape == null) {
            cachedWorldShape = localShape.transform(transformProvider)
        } else {
            localShape.transform(transformProvider, cachedWorldShape!!)
        }

        val changeId = localShape.getId()
        shapeId = changeId.get()
    }
}
