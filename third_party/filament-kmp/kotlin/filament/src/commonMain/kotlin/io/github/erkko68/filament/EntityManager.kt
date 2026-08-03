package io.github.erkko68.filament

/**
 * Manages entity creation, destruction, and lifecycle.
 *
 * EntityManager is responsible for allocating and releasing entity IDs, which are
 * opaque 32-bit handles used throughout Filament to refer to scene objects and their
 * components. It is recommended to cache the EntityManager instance.
 *
 * Thread Safe: All operations on EntityManager are thread-safe.
 */
expect class EntityManager {
    companion object {
        /**
         * Get the global EntityManager instance.
         *
         * It is recommended to cache this value.
         *
         * @return The global EntityManager instance (thread-safe).
         */
        fun get(): EntityManager
    }

    /**
     * Create a new Entity.
     *
     * Returns a new unique entity ID. If the entity cannot be allocated (max entity
     * count reached), the result will be a null entity.
     *
     * @return A new Entity ID, or NULL_ENTITY if allocation fails.
     */
    fun create(): Entity

    /**
     * Create multiple entities.
     *
     * Creates n new entities in a batch. This is more efficient than calling
     * create() multiple times.
     *
     * @param n The number of entities to create.
     * @return An array of n newly created Entity IDs.
     */
    fun create(n: Int): IntArray

    /**
     * Create entities into an existing array.
     *
     * Creates entities and stores them in the provided array. The array size
     * determines how many entities are created.
     *
     * @param entities The array to populate with newly created Entity IDs.
     * @return The same array that was passed in, now populated with new entities.
     */
    fun create(entities: IntArray): IntArray

    /**
     * Destroy an entity.
     *
     * Releases the entity ID and marks it as destroyed. The entity ID becomes
     * invalid and should not be used after destruction.
     *
     * @param entity The entity to destroy.
     */
    fun destroy(entity: Entity)

    /**
     * Destroy multiple entities.
     *
     * Destroys a batch of entities. This is more efficient than calling
     * destroy() multiple times.
     *
     * @param entities Array of entities to destroy.
     */
    fun destroy(entities: IntArray)

    /**
     * Check whether an entity is alive (not destroyed).
     *
     * Returns whether the given entity has been destroyed (false) or is still
     * valid (true).
     *
     * @param entity The entity to check.
     * @return true if the entity is alive, false if it has been destroyed.
     */
    fun isAlive(entity: Entity): Boolean
}
