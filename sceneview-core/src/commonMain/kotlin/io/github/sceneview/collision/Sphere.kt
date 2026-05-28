package io.github.sceneview.collision

import io.github.sceneview.logging.logWarning
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mathematical representation of a sphere. Used to perform intersection and collision tests against
 * spheres.
 */
class Sphere : CollisionShape {
    private val center = Vector3()
    internal var radius = 1.0f

    /** Create a sphere with a center of (0,0,0) and a radius of 1. */
    constructor()

    /** Create a sphere centered at (0,0,0) with the given [radius]. */
    constructor(radius: Float) : this(radius, Vector3.zero())

    /**
     * Create a sphere with the given [radius] and [center].
     *
     * @param radius Sphere radius in local units. Must be positive.
     * @param center Sphere center in local space.
     */
    constructor(radius: Float, center: Vector3) {
        Preconditions.checkNotNull(center, "Parameter \"center\" was null.")
        setCenter(center)
        setRadius(radius)
    }

    /**
     * Sets the center of the sphere and notifies listeners that the shape changed.
     *
     * @param center New center in local space. The vector is copied, not retained.
     */
    fun setCenter(center: Vector3) {
        Preconditions.checkNotNull(center, "Parameter \"center\" was null.")
        this.center.set(center)
        onChanged()
    }

    /** Returns a copy of the sphere center. Mutating it does not affect the sphere. */
    fun getCenter(): Vector3 = Vector3(center)

    /**
     * Sets the radius of the sphere and notifies listeners that the shape changed.
     *
     * @param radius New radius in local units.
     */
    fun setRadius(radius: Float) {
        this.radius = radius
        onChanged()
    }

    /** Returns the sphere radius in local units. */
    fun getRadius(): Float = radius

    /** Returns an independent copy of this sphere with the same center and radius. */
    override fun makeCopy(): Sphere = Sphere(getRadius(), getCenter())

    /**
     * Tests whether [ray] intersects this sphere and, if so, fills [result] with the hit.
     *
     * The nearest non-negative intersection along the ray is reported. If the ray
     * origin is inside the sphere, the exit point is returned instead.
     *
     * @param ray Ray in the same space as the sphere.
     * @param result Mutated in place with the hit distance and point when an intersection occurs.
     * @return `true` if the ray intersects the sphere, `false` otherwise. [result] is left
     *   unchanged when `false` is returned.
     */
    override fun rayIntersection(ray: Ray, result: RayHit): Boolean {
        Preconditions.checkNotNull(ray, "Parameter \"ray\" was null.")
        Preconditions.checkNotNull(result, "Parameter \"result\" was null.")

        // Read-only refs: only components are read, fed into allocating static ops.
        val rayDirection = ray.directionRef()
        val rayOrigin = ray.originRef()

        val difference = Vector3.subtract(rayOrigin, center)
        val b = 2.0f * Vector3.dot(difference, rayDirection)
        val c = Vector3.dot(difference, difference) - radius * radius
        val discriminant = b * b - 4.0f * c

        if (discriminant < 0.0f) {
            return false
        }

        val discriminantSqrt = sqrt(discriminant.toDouble()).toFloat()
        val tMinus = (-b - discriminantSqrt) / 2.0f
        val tPlus = (-b + discriminantSqrt) / 2.0f

        if (tMinus < 0.0f && tPlus < 0.0f) {
            return false
        }

        if (tMinus < 0 && tPlus > 0) {
            result.setDistance(tPlus)
        } else {
            result.setDistance(tMinus)
        }

        result.setPoint(ray.getPoint(result.getDistance()))
        return true
    }

    /** Returns `true` if this sphere overlaps [shape] (dispatches by the shape's concrete type). */
    override fun shapeIntersection(shape: CollisionShape): Boolean {
        Preconditions.checkNotNull(shape, "Parameter \"shape\" was null.")
        return shape.sphereIntersection(this)
    }

    /** Returns `true` if this sphere overlaps [sphere]. */
    override fun sphereIntersection(sphere: Sphere): Boolean =
        Intersections.sphereSphereIntersection(this, sphere)

    /** Returns `true` if this sphere overlaps [box]. */
    override fun boxIntersection(box: Box): Boolean =
        Intersections.sphereBoxIntersection(this, box)

    /**
     * Returns a new sphere obtained by applying [transformProvider]'s transform to this one.
     *
     * The center is transformed by the model matrix and the radius is scaled by the
     * largest absolute component of the decomposed world scale (keeping the result a sphere).
     */
    override fun transform(transformProvider: TransformProvider): CollisionShape {
        Preconditions.checkNotNull(transformProvider, "Parameter \"transformProvider\" was null.")
        val result = Sphere()
        transform(transformProvider, result)
        return result
    }

    /**
     * Applies [transformProvider]'s transform to this sphere, writing into [result].
     *
     * @param result Must be a [Sphere]; otherwise the call is a no-op and a warning is logged.
     */
    override fun transform(transformProvider: TransformProvider, result: CollisionShape) {
        Preconditions.checkNotNull(transformProvider, "Parameter \"transformProvider\" was null.")
        Preconditions.checkNotNull(result, "Parameter \"result\" was null.")

        if (result !is Sphere) {
            logWarning(TAG, "Cannot pass CollisionShape of a type other than Sphere into Sphere.transform.")
            return
        }

        val modelMatrix = transformProvider.getTransformationMatrix()

        result.setCenter(modelMatrix.transformPoint(center))

        val worldScale = Vector3()
        modelMatrix.decomposeScale(worldScale)
        val maxScale = max(
            abs(min(min(worldScale.x, worldScale.y), worldScale.z)),
            max(max(worldScale.x, worldScale.y), worldScale.z)
        )
        result.radius = radius * maxScale
    }

    companion object {
        private const val TAG = "Sphere"
    }
}
