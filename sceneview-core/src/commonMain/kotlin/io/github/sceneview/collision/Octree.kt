package io.github.sceneview.collision

/**
 * Octree spatial partitioning for efficient broad-phase collision queries.
 *
 * Subdivides 3D space into eight equal octants recursively. Objects (represented
 * as indexed AABBs) are stored in the smallest node that fully contains them.
 * This accelerates ray queries and nearest-neighbor searches from O(n) to ~O(log n).
 *
 * @param bounds The axis-aligned bounding box of this node.
 * @param maxDepth Maximum subdivision depth. Default 6.
 * @param maxObjectsPerNode Maximum objects before subdivision. Default 8.
 */
class Octree(
    val bounds: AABB,
    private val maxDepth: Int = 6,
    private val maxObjectsPerNode: Int = 8
) {
    /**
     * An object stored in the octree with its AABB and an arbitrary index/ID.
     */
    data class OctreeObject(
        val aabb: AABB,
        val index: Int
    )

    private val objects = mutableListOf<OctreeObject>()
    private var children: Array<Octree>? = null
    private var depth = 0

    /** Total number of objects stored in this node and all descendants. */
    val objectCount: Int
        get() = objects.size + (children?.sumOf { it.objectCount } ?: 0)

    /** Whether this node has been subdivided. */
    val isSubdivided: Boolean get() = children != null

    /**
     * Insert an object into the octree.
     *
     * @param aabb The axis-aligned bounding box of the object.
     * @param index An identifier for the object (e.g., triangle index).
     */
    fun insert(aabb: AABB, index: Int) {
        insert(OctreeObject(aabb, index))
    }

    /**
     * Insert an object into the octree.
     */
    fun insert(obj: OctreeObject) {
        if (!bounds.intersects(obj.aabb)) return

        children?.let { kids ->
            for (child in kids) {
                if (childFullyContains(child.bounds, obj.aabb)) {
                    child.insert(obj)
                    return
                }
            }
            // Doesn't fully fit in any child — store here
            objects.add(obj)
            return
        }

        objects.add(obj)

        // Subdivide if we have too many objects and haven't reached max depth
        if (objects.size > maxObjectsPerNode && depth < maxDepth) {
            subdivide()
        }
    }

    /**
     * Query all objects whose AABBs intersect the given AABB.
     *
     * @param queryAabb The query region.
     * @return List of object indices that potentially overlap.
     */
    fun query(queryAabb: AABB): List<Int> {
        val results = mutableListOf<Int>()
        query(queryAabb, results)
        return results
    }

    /**
     * Allocation-light variant of [query] that appends matching object indices into the
     * caller-supplied [out] list instead of allocating a fresh list per recursion level.
     *
     * The single [out] list is threaded through the whole recursion (no per-node list +
     * `addAll` copy). [out] is appended to, never cleared — clear it yourself for reuse.
     * Returns [out] for convenience.
     */
    fun query(queryAabb: AABB, out: MutableList<Int>): MutableList<Int> {
        if (!bounds.intersects(queryAabb)) return out

        for (obj in objects) {
            if (obj.aabb.intersects(queryAabb)) {
                out.add(obj.index)
            }
        }

        children?.let { kids ->
            for (child in kids) {
                child.query(queryAabb, out)
            }
        }

        return out
    }

    /**
     * Query all objects whose AABBs are intersected by a ray.
     *
     * @param ray The ray to test against.
     * @return List of object indices whose AABBs the ray passes through.
     */
    fun queryRay(ray: Ray): List<Int> {
        val results = mutableListOf<Int>()
        queryRay(ray, results)
        return results
    }

    /**
     * Allocation-light variant of [queryRay] that appends matching object indices into the
     * caller-supplied [out] list instead of allocating a fresh list per recursion level.
     *
     * The single [out] list is threaded through the whole recursion (no per-node list +
     * `addAll` copy). [out] is appended to, never cleared — clear it yourself for reuse.
     * Returns [out] for convenience.
     */
    fun queryRay(ray: Ray, out: MutableList<Int>): MutableList<Int> {
        if (!bounds.rayIntersection(ray)) return out

        for (obj in objects) {
            if (obj.aabb.rayIntersection(ray)) {
                out.add(obj.index)
            }
        }

        children?.let { kids ->
            for (child in kids) {
                child.queryRay(ray, out)
            }
        }

        return out
    }

    /**
     * Remove all objects from this node and its children.
     */
    fun clear() {
        objects.clear()
        children = null
    }

    private fun subdivide() {
        val c = bounds.center
        val min = bounds.min
        val max = bounds.max

        val kids = Array(8) { i ->
            val childMin = Vector3(
                if (i and 1 == 0) min.x else c.x,
                if (i and 2 == 0) min.y else c.y,
                if (i and 4 == 0) min.z else c.z
            )
            val childMax = Vector3(
                if (i and 1 == 0) c.x else max.x,
                if (i and 2 == 0) c.y else max.y,
                if (i and 4 == 0) c.z else max.z
            )
            Octree(AABB(childMin, childMax), maxDepth, maxObjectsPerNode).also {
                it.depth = depth + 1
            }
        }

        // Redistribute existing objects
        val remaining = mutableListOf<OctreeObject>()
        for (obj in objects) {
            var placed = false
            for (child in kids) {
                if (childFullyContains(child.bounds, obj.aabb)) {
                    child.insert(obj)
                    placed = true
                    break
                }
            }
            if (!placed) remaining.add(obj)
        }

        objects.clear()
        objects.addAll(remaining)
        children = kids
    }

    private fun childFullyContains(childBounds: AABB, objectAabb: AABB): Boolean =
        objectAabb.min.x >= childBounds.min.x && objectAabb.max.x <= childBounds.max.x &&
                objectAabb.min.y >= childBounds.min.y && objectAabb.max.y <= childBounds.max.y &&
                objectAabb.min.z >= childBounds.min.z && objectAabb.max.z <= childBounds.max.z

    companion object {
        /**
         * Build an octree from a list of triangles.
         *
         * @param triangles The triangles to insert.
         * @param maxDepth Maximum subdivision depth.
         * @return An octree containing all triangles.
         */
        fun fromTriangles(
            triangles: List<MeshCollider.Triangle>,
            maxDepth: Int = 6
        ): Octree {
            val bounds = AABB.fromTriangles(triangles)
            // Slightly expand bounds to avoid edge cases
            val padding = Vector3(0.01f, 0.01f, 0.01f)
            val paddedBounds = AABB(
                Vector3.subtract(bounds.min, padding),
                Vector3.add(bounds.max, padding)
            )
            val octree = Octree(paddedBounds, maxDepth)
            // Index loop instead of withIndex() to avoid boxing an IndexedValue per triangle.
            for (index in triangles.indices) {
                octree.insert(triangles[index].aabb(), index)
            }
            return octree
        }
    }
}

/**
 * Performs accelerated ray-mesh intersection using an octree for broad-phase culling.
 *
 * @param ray The ray to test.
 * @param triangles The mesh triangles.
 * @param octree Pre-built octree for the mesh.
 * @return The nearest intersection result.
 */
fun acceleratedRayMeshIntersection(
    ray: Ray,
    triangles: List<MeshCollider.Triangle>,
    octree: Octree
): MeshCollider.MeshHitResult {
    // Reuse a single candidate list (sink overload) rather than the per-recursion
    // list + addAll that the no-arg queryRay allocates internally.
    val candidates = octree.queryRay(ray, ArrayList())
    var bestResult = MeshCollider.MeshHitResult(false)

    for (i in candidates.indices) {
        val index = candidates[i]
        val tri = triangles[index]
        val result = MeshCollider.rayTriangleIntersection(ray, tri.v0, tri.v1, tri.v2)
        if (result.hit && result.distance < bestResult.distance) {
            bestResult = result.copy(triangleIndex = index)
        }
    }

    return bestResult
}
