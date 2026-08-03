package io.github.erkko68.filament

/**
 * A Scene is a collection of Renderables and Lights to be rendered together.
 *
 * A Scene must be associated with a View to be rendered. Renderables, Lights, Skyboxes, and
 * IndirectLight can be added to and removed from a Scene. Scenes are relatively lightweight
 * objects that mainly manage entity membership.
 *
 * @see View, Renderer, RenderableManager, LightManager
 */
expect class Scene {
    /**
     * Sets the skybox for this scene.
     *
     * A scene can have at most one skybox. The skybox is rendered last (after all renderables)
     * and doesn't participate in lighting or shadow receiving.
     */
    var skybox: Skybox?

    /**
     * Sets the indirect light (IBL) for this scene.
     *
     * The indirect light provides environmental lighting (irradiance and reflections). A scene
     * can have at most one indirect light. If multiple IndirectLight objects exist, only the one
     * most recently set is active.
     */
    var indirectLight: IndirectLight?

    /**
     * Adds a single entity to this scene.
     *
     * A Renderable entity added to a Scene can be rendered when the Scene is rendered.
     * Adding the same entity twice has no effect.
     *
     * @param entity The entity to add
     */
    fun addEntity(entity: Entity)

    /**
     * Adds multiple entities to this scene.
     *
     * @param entities Array of entity IDs to add
     */
    fun addEntities(entities: IntArray)

    /**
     * Removes a single entity from this scene.
     *
     * @param entity The entity to remove
     */
    fun removeEntity(entity: Entity)

    /**
     * Removes a single entity from this scene.
     *
     * This is a synonym for removeEntity(). Both have the same effect.
     *
     * @param entity The entity to remove
     */
    fun remove(entity: Entity)

    /**
     * Removes multiple entities from this scene.
     *
     * @param entities Array of entity IDs to remove
     */
    fun removeEntities(entities: IntArray)

    /**
     * Gets the number of entities in this scene.
     *
     * @return Number of entities (renderables + lights)
     */
    val entityCount: Int

    /**
     * Gets the number of renderable entities in this scene.
     *
     * @return Number of renderables
     */
    val renderableCount: Int

    /**
     * Gets the number of light entities in this scene.
     *
     * @return Number of lights
     */
    val lightCount: Int

    /**
     * Tests whether an entity is in this scene.
     *
     * @param entity The entity to test
     * @return true if the entity is in this scene, false otherwise
     */
    fun hasEntity(entity: Entity): Boolean

    /**
     * Gets all entities in this scene.
     *
     * @return Array of all entity IDs in the scene
     */
    fun getEntities(): IntArray

    /**
     * Gets all entities in this scene, optionally reusing an output array.
     *
     * @param outArray Optional array to fill with entity IDs. If null, a new array is created.
     * @return Array of all entity IDs in the scene
     */
    fun getEntities(outArray: IntArray?): IntArray

    /**
     * Iterates over all entities in this scene.
     *
     * @param block Lambda function to call for each entity
     */
    fun forEach(block: (Entity) -> Unit)
}
