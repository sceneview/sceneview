package io.github.erkko68.filament

/**
 * An Entity is an opaque 32-bit identifier for objects in the scene.
 *
 * Entities are created by EntityManager and are used throughout Filament to refer to
 * objects and their components (Transform, Light, Renderable, etc.). An Entity value
 * can be checked for validity using EntityManager.isAlive().
 *
 * @see EntityManager
 */
typealias Entity = Int

/**
 * An EntityInstance is an opaque 32-bit identifier representing a component instance.
 *
 * Component instances are created when a component is attached to an entity via managers
 * like RenderableManager, LightManager, or TransformManager. Instances are ephemeral
 * handles and should not be stored; use Entity IDs instead.
 *
 * @see RenderableManager
 * @see LightManager
 * @see TransformManager
 */
typealias EntityInstance = Int

/**
 * A special Entity value representing no entity (null).
 *
 * This constant can be used to check if an Entity ID is uninitialized or invalid.
 * Always use NULL_ENTITY for comparisons rather than a literal 0.
 */
const val NULL_ENTITY: Entity = 0
