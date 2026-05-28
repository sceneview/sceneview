package io.github.sceneview.collision

import kotlin.math.abs

/**
 * Mathematical representation of an infinite plane. Used for ray intersection tests.
 *
 * @param center A point lying on the plane.
 * @param normal The plane's surface normal. Stored normalized.
 */
class Plane(center: Vector3, normal: Vector3) {
    private val center = Vector3()
    private val normal = Vector3()

    companion object {
        private const val NEAR_ZERO_THRESHOLD = 1e-6
    }

    init {
        setCenter(center)
        setNormal(normal)
    }

    /** Sets a point lying on the plane. The vector is copied, not retained. */
    fun setCenter(center: Vector3) {
        Preconditions.checkNotNull(center, "Parameter \"center\" was null.")
        this.center.set(center)
    }

    /** Returns a copy of the plane's reference point. */
    fun getCenter(): Vector3 = Vector3(center)

    /** Sets the plane's surface normal. The value is normalized before being stored. */
    fun setNormal(normal: Vector3) {
        Preconditions.checkNotNull(normal, "Parameter \"normal\" was null.")
        this.normal.set(normal.normalized())
    }

    /** Returns a copy of the plane's (normalized) surface normal. */
    fun getNormal(): Vector3 = Vector3(normal)

    /**
     * Tests whether [ray] intersects this plane and, if so, fills [result] with the hit.
     *
     * Only forward intersections (distance >= 0) are reported. A ray parallel to the
     * plane never intersects.
     *
     * @param ray Ray in the same space as the plane.
     * @param result Mutated in place with the hit distance and point when an intersection occurs.
     * @return `true` if the ray intersects the plane in front of its origin.
     */
    fun rayIntersection(ray: Ray, result: RayHit): Boolean {
        Preconditions.checkNotNull(ray, "Parameter \"ray\" was null.")
        Preconditions.checkNotNull(result, "Parameter \"result\" was null.")

        // Read-only refs: only components are read, fed into allocating static ops.
        val rayDirection = ray.directionRef()
        val rayOrigin = ray.originRef()

        val denominator = Vector3.dot(normal, rayDirection)
        if (abs(denominator) > NEAR_ZERO_THRESHOLD) {
            val delta = Vector3.subtract(center, rayOrigin)
            val distance = Vector3.dot(delta, normal) / denominator
            if (distance >= 0) {
                result.setDistance(distance)
                result.setPoint(ray.getPoint(result.getDistance()))
                return true
            }
        }

        return false
    }
}
