// Derived from Google Sceneform (Apache-2.0), modified by the SceneView contributors.
package io.github.sceneview.collision

/**
 * Base class for all types of shapes that collision checks can be performed against.
 *
 * Subclasses include [Box] (oriented bounding box) and [Sphere]. Each shape
 * supports ray intersection, shape-vs-shape intersection, and transformation
 * via a [TransformProvider].
 */
abstract class CollisionShape {
    private val changeId = ChangeId()

    /** Create a deep copy of this collision shape. */
    abstract fun makeCopy(): CollisionShape

    /**
     * Must be called by subclasses when the shape changes to inform listeners of the change.
     */
    protected fun onChanged() {
        changeId.update()
    }

    /**
     * Test whether a [ray] intersects this shape.
     *
     * @param ray The ray to test.
     * @param result Receives the hit distance and point if the ray intersects.
     * @return `true` if the ray intersects this shape.
     */
    abstract fun rayIntersection(ray: Ray, result: RayHit): Boolean

    /**
     * Test whether this shape intersects another [CollisionShape].
     *
     * Uses double-dispatch: calls the appropriate typed intersection method on [shape].
     */
    abstract fun shapeIntersection(shape: CollisionShape): Boolean

    /** Test whether this shape intersects a [Sphere]. */
    abstract fun sphereIntersection(sphere: Sphere): Boolean

    /** Test whether this shape intersects a [Box]. */
    abstract fun boxIntersection(box: Box): Boolean

    init {
        changeId.update()
    }

    /** Returns the [ChangeId] that tracks mutations to this shape. */
    fun getId(): ChangeId = changeId

    /**
     * Returns a new collision shape transformed by the given [transformProvider].
     */
    abstract fun transform(transformProvider: TransformProvider): CollisionShape

    /**
     * Transforms this shape by the given [transformProvider], writing into [result].
     *
     * @param result Must be the same concrete type as this shape.
     */
    abstract fun transform(transformProvider: TransformProvider, result: CollisionShape)
}
