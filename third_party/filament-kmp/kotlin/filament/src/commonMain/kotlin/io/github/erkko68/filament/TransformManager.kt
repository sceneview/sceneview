package io.github.erkko68.filament

/**
 * TransformManager allows managing transforms (4x4 matrices) for entities.
 *
 * Entities can have a transform component that defines their position, rotation, and scale in
 * world space. Transforms can be organized in a hierarchy where each entity can have a parent,
 * allowing relative transformations.
 *
 * The TransformManager maintains the transform hierarchy and automatically computes world
 * transforms from local transforms and parent transforms.
 *
 * @see EntityManager, Scene
 */
expect class TransformManager {
    /**
     * Checks if an entity has a transform component.
     *
     * @param entity The entity to check
     * @return true if the entity has a transform component, false otherwise
     */
    fun hasComponent(entity: Entity): Boolean

    /**
     * Gets the transform instance for an entity.
     *
     * @param entity The entity
     * @return The transform instance for this entity
     */
    fun getInstance(entity: Entity): EntityInstance

    /**
     * Creates a transform component for an entity with identity transform.
     *
     * @param entity The entity to add a transform to
     * @return The newly created transform instance
     */
    fun create(entity: Entity): EntityInstance

    /**
     * Creates a transform component for an entity with a local transform and parent.
     *
     * @param entity The entity to add a transform to
     * @param parent The parent transform instance (use NULL_ENTITY for root)
     * @param localTransform The local transform as a 4x4 matrix in row-major order (or null)
     * @return The newly created transform instance
     */
    fun create(entity: Entity, parent: EntityInstance, localTransform: FloatArray?): EntityInstance

    /**
     * Creates a transform component for an entity with a local transform and parent.
     *
     * @param entity The entity to add a transform to
     * @param parent The parent transform instance (use NULL_ENTITY for root)
     * @param localTransform The local transform as a 4x4 matrix in row-major order (or null)
     * @return The newly created transform instance
     */
    fun create(entity: Entity, parent: EntityInstance, localTransform: DoubleArray?): EntityInstance

    /**
     * Destroys the transform component for an entity.
     *
     * @param entity The entity whose transform to destroy
     */
    fun destroy(entity: Entity)

    /**
     * Sets the parent of a transform.
     *
     * @param instance The transform instance
     * @param newParent The new parent transform instance
     */
    fun setParent(instance: EntityInstance, newParent: EntityInstance)

    /**
     * Gets the parent entity of a transform.
     *
     * @param instance The transform instance
     * @return The parent entity (or NULL_ENTITY if this is a root transform)
     */
    fun getParent(instance: EntityInstance): Entity

    /**
     * Gets the number of child transforms.
     *
     * @param instance The transform instance
     * @return The number of direct children
     */
    fun getChildCount(instance: EntityInstance): Int

    /**
     * Gets the child entities of a transform.
     *
     * @param instance The transform instance
     * @param outEntities Optional array to fill with child entity IDs
     * @return Array of child entity IDs
     */
    fun getChildren(instance: EntityInstance, outEntities: IntArray?): IntArray

    /**
     * Sets the local transform for a transform instance.
     *
     * @param instance The transform instance
     * @param localTransform A 4x4 matrix in row-major order
     */
    fun setTransform(instance: EntityInstance, localTransform: FloatArray)

    /**
     * Sets the local transform for a transform instance.
     *
     * @param instance The transform instance
     * @param localTransform A 4x4 matrix in row-major order
     */
    fun setTransform(instance: EntityInstance, localTransform: DoubleArray)

    /**
     * Gets the local transform for a transform instance.
     *
     * @param instance The transform instance
     * @param outLocalTransform Optional array to fill with the local transform (or null)
     * @return A 4x4 matrix in row-major order
     */
    fun getTransform(instance: EntityInstance, outLocalTransform: FloatArray?): FloatArray

    /**
     * Gets the local transform for a transform instance.
     *
     * @param instance The transform instance
     * @param outLocalTransform Optional array to fill with the local transform (or null)
     * @return A 4x4 matrix in row-major order
     */
    fun getTransform(instance: EntityInstance, outLocalTransform: DoubleArray?): DoubleArray

    /**
     * Gets the world transform for a transform instance (accounting for parent transforms).
     *
     * @param instance The transform instance
     * @param outWorldTransform Optional array to fill with the world transform (or null)
     * @return A 4x4 matrix in row-major order
     */
    fun getWorldTransform(instance: EntityInstance, outWorldTransform: FloatArray?): FloatArray

    /**
     * Gets the world transform for a transform instance (accounting for parent transforms).
     *
     * @param instance The transform instance
     * @param outWorldTransform Optional array to fill with the world transform (or null)
     * @return A 4x4 matrix in row-major order
     */
    fun getWorldTransform(instance: EntityInstance, outWorldTransform: DoubleArray?): DoubleArray

    /**
     * Opens a local transform transaction.
     *
     * Allows multiple transform updates to be batched together for efficiency. Call
     * commitLocalTransformTransaction() to commit the changes.
     */
    fun openLocalTransformTransaction()

    /**
     * Commits a local transform transaction.
     *
     * Must be called after openLocalTransformTransaction() to apply batched changes.
     */
    fun commitLocalTransformTransaction()

    /**
     * Enables or disables accurate translations (high precision translation for large worlds).
     *
     * When enabled, allows for more precise transforms for entities far from the origin.
     * This may have a small performance cost. Default: false.
     */
    var isAccurateTranslationsEnabled: Boolean
}
