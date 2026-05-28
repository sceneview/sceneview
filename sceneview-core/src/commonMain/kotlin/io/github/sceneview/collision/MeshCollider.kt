package io.github.sceneview.collision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Triangle-level ray intersection for mesh collision detection.
 *
 * Given a list of triangle vertices and a ray, performs per-triangle
 * Moller-Trumbore intersection tests to find the nearest hit point.
 * This is more precise than bounding-volume tests but more expensive.
 *
 * For large meshes, use an [Octree] for broad-phase culling first.
 */
object MeshCollider {

    /**
     * A single triangle defined by three vertex positions.
     */
    data class Triangle(
        val v0: Vector3,
        val v1: Vector3,
        val v2: Vector3
    ) {
        /** Returns the face normal (not normalized). */
        fun normal(): Vector3 = Vector3.cross(
            Vector3.subtract(v1, v0),
            Vector3.subtract(v2, v0)
        )

        /** Returns the centroid of the triangle. */
        fun centroid(): Vector3 = Vector3(
            (v0.x + v1.x + v2.x) / 3f,
            (v0.y + v1.y + v2.y) / 3f,
            (v0.z + v1.z + v2.z) / 3f
        )

        /** Returns the axis-aligned bounding box of this triangle. */
        fun aabb(): AABB = AABB(
            min = Vector3(
                min(v0.x, min(v1.x, v2.x)),
                min(v0.y, min(v1.y, v2.y)),
                min(v0.z, min(v1.z, v2.z))
            ),
            max = Vector3(
                max(v0.x, max(v1.x, v2.x)),
                max(v0.y, max(v1.y, v2.y)),
                max(v0.z, max(v1.z, v2.z))
            )
        )
    }

    /**
     * Result of a mesh ray intersection test.
     *
     * @param hit Whether any triangle was intersected.
     * @param distance Distance along the ray to the hit point.
     * @param point World-space hit point.
     * @param triangleIndex Index of the hit triangle in the input list.
     * @param normal Face normal of the hit triangle (not normalized).
     * @param u Barycentric U coordinate.
     * @param v Barycentric V coordinate.
     */
    data class MeshHitResult(
        val hit: Boolean,
        val distance: Float = Float.MAX_VALUE,
        val point: Vector3 = Vector3.zero(),
        val triangleIndex: Int = -1,
        val normal: Vector3 = Vector3.zero(),
        val u: Float = 0f,
        val v: Float = 0f
    )

    private const val EPSILON = 1e-7f

    /**
     * Moller-Trumbore ray-triangle intersection algorithm.
     *
     * @param ray The ray to test.
     * @param v0 First vertex of the triangle.
     * @param v1 Second vertex of the triangle.
     * @param v2 Third vertex of the triangle.
     * @return (hit, t, u, v) where t is the distance, u and v are barycentric coords.
     */
    fun rayTriangleIntersection(
        ray: Ray, v0: Vector3, v1: Vector3, v2: Vector3
    ): MeshHitResult {
        val edge1 = Vector3.subtract(v1, v0)
        val edge2 = Vector3.subtract(v2, v0)

        // Read-only refs: the Moller-Trumbore math only reads components and
        // feeds them into allocating static ops — no mutation of the ray's vectors.
        val rayDirection = ray.directionRef()
        val rayOrigin = ray.originRef()

        val h = Vector3.cross(rayDirection, edge2)
        val a = Vector3.dot(edge1, h)

        if (abs(a) < EPSILON) return MeshHitResult(false) // Parallel

        val f = 1f / a
        val s = Vector3.subtract(rayOrigin, v0)
        val u = f * Vector3.dot(s, h)

        if (u < 0f || u > 1f) return MeshHitResult(false)

        val q = Vector3.cross(s, edge1)
        val v = f * Vector3.dot(rayDirection, q)

        if (v < 0f || u + v > 1f) return MeshHitResult(false)

        val t = f * Vector3.dot(edge2, q)

        if (t > EPSILON) {
            val normal = Vector3.cross(edge1, edge2)
            return MeshHitResult(
                hit = true,
                distance = t,
                point = ray.getPoint(t),
                normal = normal,
                u = u,
                v = v
            )
        }

        return MeshHitResult(false)
    }

    /**
     * Test a ray against a list of triangles and return the nearest hit.
     *
     * @param ray The ray to test.
     * @param triangles List of triangles to test against.
     * @return The nearest intersection result.
     */
    fun rayMeshIntersection(ray: Ray, triangles: List<Triangle>): MeshHitResult {
        var bestResult = MeshHitResult(false)

        for ((index, tri) in triangles.withIndex()) {
            val result = rayTriangleIntersection(ray, tri.v0, tri.v1, tri.v2)
            if (result.hit && result.distance < bestResult.distance) {
                bestResult = result.copy(triangleIndex = index)
            }
        }

        return bestResult
    }
}

/**
 * Axis-aligned bounding box for spatial partitioning.
 */
data class AABB(
    val min: Vector3,
    val max: Vector3
) {
    /** The geometric center (midpoint of [min] and [max]). */
    val center: Vector3 get() = Vector3(
        (min.x + max.x) / 2f,
        (min.y + max.y) / 2f,
        (min.z + max.z) / 2f
    )

    /** The full extents of the box along each axis (`max - min`). */
    val size: Vector3 get() = Vector3(
        max.x - min.x,
        max.y - min.y,
        max.z - min.z
    )

    /** Test whether a point is inside this AABB. */
    fun contains(point: Vector3): Boolean =
        point.x in min.x..max.x &&
                point.y in min.y..max.y &&
                point.z in min.z..max.z

    /** Test whether this AABB intersects another AABB. */
    fun intersects(other: AABB): Boolean =
        min.x <= other.max.x && max.x >= other.min.x &&
                min.y <= other.max.y && max.y >= other.min.y &&
                min.z <= other.max.z && max.z >= other.min.z

    /** Ray-AABB intersection test (slab method). Returns true if the ray hits. */
    fun rayIntersection(ray: Ray): Boolean {
        // Read-only refs: the slab test only reads the components.
        val origin = ray.originRef()
        val dir = ray.directionRef()

        var tMin = -Float.MAX_VALUE
        var tMax = Float.MAX_VALUE

        for (axis in 0..2) {
            val o = when (axis) { 0 -> origin.x; 1 -> origin.y; else -> origin.z }
            val d = when (axis) { 0 -> dir.x; 1 -> dir.y; else -> dir.z }
            val bMin = when (axis) { 0 -> min.x; 1 -> min.y; else -> min.z }
            val bMax = when (axis) { 0 -> max.x; 1 -> max.y; else -> max.z }

            // 1e-6f matches the parallel-ray epsilon used in `Box.rayIntersection`
            // after #1096. Pre-existing `1e-10f` was below FLT_EPSILON for typical
            // normalised ray directions in [-1,1] → "parallel" branch effectively
            // never fired, near-parallel rays divided by ~0 producing ±Inf t-values
            // that false-passed through the slab math.
            if (abs(d) < 1.0E-6f) {
                if (o < bMin || o > bMax) return false
            } else {
                var t1 = (bMin - o) / d
                var t2 = (bMax - o) / d
                if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
                tMin = max(tMin, t1)
                tMax = min(tMax, t2)
                if (tMin > tMax) return false
            }
        }

        return tMax >= 0f
    }

    /** Expand this AABB to include another AABB. */
    fun union(other: AABB): AABB = AABB(
        min = Vector3(
            min(min.x, other.min.x),
            min(min.y, other.min.y),
            min(min.z, other.min.z)
        ),
        max = Vector3(
            max(max.x, other.max.x),
            max(max.y, other.max.y),
            max(max.z, other.max.z)
        )
    )

    companion object {
        /** Compute the AABB that encloses all given triangles. */
        fun fromTriangles(triangles: List<MeshCollider.Triangle>): AABB {
            require(triangles.isNotEmpty()) { "Cannot compute AABB from empty triangle list" }
            var minV = Vector3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            var maxV = Vector3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (tri in triangles) {
                for (v in listOf(tri.v0, tri.v1, tri.v2)) {
                    minV = Vector3.min(minV, v)
                    maxV = Vector3.max(maxV, v)
                }
            }
            return AABB(minV, maxV)
        }
    }
}
