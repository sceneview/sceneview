package io.github.sceneview.collision

import kotlin.math.max
import kotlin.math.min

/** Implementation of common intersection tests used for collision detection. */
internal object Intersections {
    private const val NUM_VERTICES_PER_BOX = 8
    private const val NUM_TEST_AXES = 15

    /** Determine if two spheres intersect with each other. */
    fun sphereSphereIntersection(sphere1: Sphere, sphere2: Sphere): Boolean {
        Preconditions.checkNotNull(sphere1, "Parameter \"sphere1\" was null.")
        Preconditions.checkNotNull(sphere2, "Parameter \"sphere2\" was null.")

        val combinedRadius = sphere1.getRadius() + sphere2.getRadius()
        val combinedRadiusSquared = combinedRadius * combinedRadius
        val difference = Vector3.subtract(sphere2.getCenter(), sphere1.getCenter())
        val differenceLengthSquared = Vector3.dot(difference, difference)

        return differenceLengthSquared - combinedRadiusSquared <= 0.0f
                && differenceLengthSquared != 0.0f
    }

    /** Determine if two boxes intersect with each other. */
    fun boxBoxIntersection(box1: Box, box2: Box): Boolean {
        Preconditions.checkNotNull(box1, "Parameter \"box1\" was null.")
        Preconditions.checkNotNull(box2, "Parameter \"box2\" was null.")

        // Function-local scratch buffers (thread-safe — no shared mutable state).
        // Each box's 8 vertices packed as flat xyz triples (24 floats).
        val box1Vertices = FloatArray(NUM_VERTICES_PER_BOX * 3)
        val box2Vertices = FloatArray(NUM_VERTICES_PER_BOX * 3)
        writeBoxVertices(box1, box1Vertices)
        writeBoxVertices(box2, box2Vertices)

        val r1 = box1.getRawRotationMatrix().data
        val r2 = box2.getRawRotationMatrix().data

        // 15 SAT axes packed as flat xyz triples: box1's 3 axes, box2's 3 axes,
        // then the 9 cross products. Same ordering as the previous List-based code.
        val axes = FloatArray(NUM_TEST_AXES * 3)
        // box1 X/Y/Z axes (rotation columns)
        axes[0] = r1[0]; axes[1] = r1[4]; axes[2] = r1[8]
        axes[3] = r1[1]; axes[4] = r1[5]; axes[5] = r1[9]
        axes[6] = r1[2]; axes[7] = r1[6]; axes[8] = r1[10]
        // box2 X/Y/Z axes
        axes[9] = r2[0]; axes[10] = r2[4]; axes[11] = r2[8]
        axes[12] = r2[1]; axes[13] = r2[5]; axes[14] = r2[9]
        axes[15] = r2[2]; axes[16] = r2[6]; axes[17] = r2[10]

        // Cross products: for i in 0..2 → cross(box1Axis[i], box2Axis[0..2]).
        var dst = 18
        for (i in 0..2) {
            val aOff = i * 3
            val aX = axes[aOff]; val aY = axes[aOff + 1]; val aZ = axes[aOff + 2]
            for (j in 0..2) {
                val bOff = (3 + j) * 3
                val bX = axes[bOff]; val bY = axes[bOff + 1]; val bZ = axes[bOff + 2]
                axes[dst] = aY * bZ - aZ * bY
                axes[dst + 1] = aZ * bX - aX * bZ
                axes[dst + 2] = aX * bY - aY * bX
                dst += 3
            }
        }

        for (i in 0 until NUM_TEST_AXES) {
            val off = i * 3
            if (!testSeparatingAxis(
                    box1Vertices, box2Vertices, axes[off], axes[off + 1], axes[off + 2]
                )
            ) {
                return false
            }
        }

        return true
    }

    /** Determine if a sphere and a box intersect with each other. */
    fun sphereBoxIntersection(sphere: Sphere, box: Box): Boolean {
        Preconditions.checkNotNull(sphere, "Parameter \"sphere\" was null.")
        Preconditions.checkNotNull(box, "Parameter \"box\" was null.")

        val c = sphere.getCenter()
        return pointWithinBoxDistance(c.x, c.y, c.z, sphere.getRadius(), box)
    }

    /**
     * Returns `true` if the point `(px, py, pz)` is within [radius] of [box]
     * (i.e. the squared distance from the point to the closest point on the oriented
     * box does not exceed `radius²`). Allocation-free — used by the sphere/box and
     * capsule/box tests. Mirrors the old `closestPointOnBox` + distance check exactly.
     */
    fun pointWithinBoxDistance(
        px: Float, py: Float, pz: Float, radius: Float, box: Box
    ): Boolean {
        val center = box.getCenter()
        val extents = box.getExtents()
        val rot = box.getRawRotationMatrix().data

        // diff = point - center
        val diffX = px - center.x
        val diffY = py - center.y
        val diffZ = pz - center.z

        // Accumulate the closest point starting from the box center, clamping the
        // projection onto each box axis to ±extent (read axes as scalars).
        var resX = center.x; var resY = center.y; var resZ = center.z

        // X axis (column 0)
        run {
            val axX = rot[0]; val axY = rot[4]; val axZ = rot[8]
            var d = diffX * axX + diffY * axY + diffZ * axZ
            if (d > extents.x) d = extents.x else if (d < -extents.x) d = -extents.x
            resX += axX * d; resY += axY * d; resZ += axZ * d
        }
        // Y axis (column 1)
        run {
            val axX = rot[1]; val axY = rot[5]; val axZ = rot[9]
            var d = diffX * axX + diffY * axY + diffZ * axZ
            if (d > extents.y) d = extents.y else if (d < -extents.y) d = -extents.y
            resX += axX * d; resY += axY * d; resZ += axZ * d
        }
        // Z axis (column 2)
        run {
            val axX = rot[2]; val axY = rot[6]; val axZ = rot[10]
            var d = diffX * axX + diffY * axY + diffZ * axZ
            if (d > extents.z) d = extents.z else if (d < -extents.z) d = -extents.z
            resX += axX * d; resY += axY * d; resZ += axZ * d
        }

        val dx = resX - px; val dy = resY - py; val dz = resZ - pz
        val distSq = dx * dx + dy * dy + dz * dz
        return distSq <= radius * radius
    }

    private fun testSeparatingAxis(
        vertices1: FloatArray, vertices2: FloatArray, axX: Float, axY: Float, axZ: Float
    ): Boolean {
        var min1 = Float.MAX_VALUE
        var max1 = -Float.MAX_VALUE
        var i = 0
        while (i < vertices1.size) {
            val projection = axX * vertices1[i] + axY * vertices1[i + 1] + axZ * vertices1[i + 2]
            min1 = min(projection, min1)
            max1 = max(projection, max1)
            i += 3
        }

        var min2 = Float.MAX_VALUE
        var max2 = -Float.MAX_VALUE
        i = 0
        while (i < vertices2.size) {
            val projection = axX * vertices2[i] + axY * vertices2[i + 1] + axZ * vertices2[i + 2]
            min2 = min(projection, min2)
            max2 = max(projection, max2)
            i += 3
        }

        return min2 <= max1 && min1 <= max2
    }

    /**
     * Writes the box's 8 corner vertices into [out] as flat xyz triples (24 floats),
     * in the same ±extent ordering as the previous List-based `getVerticesFromBox`.
     * Allocation-free apart from the caller-supplied buffer.
     */
    private fun writeBoxVertices(box: Box, out: FloatArray) {
        val center = box.getCenter()
        val extents = box.getExtents()
        val rot = box.getRawRotationMatrix().data

        // Scaled axis vectors (xyz), read as scalars.
        val xx = rot[0] * extents.x; val xy = rot[4] * extents.x; val xz = rot[8] * extents.x
        val yx = rot[1] * extents.y; val yy = rot[5] * extents.y; val yz = rot[9] * extents.y
        val zx = rot[2] * extents.z; val zy = rot[6] * extents.z; val zz = rot[10] * extents.z

        // Signs per corner match the original add/subtract sequence exactly:
        //  v0:+x+y+z  v1:-x+y+z  v2:+x-y+z  v3:+x+y-z
        //  v4:-x-y-z  v5:+x-y-z  v6:-x+y-z  v7:-x-y+z
        setVertex(out, 0, center,  xx,  xy,  xz,  yx,  yy,  yz,  zx,  zy,  zz)
        setVertex(out, 1, center, -xx, -xy, -xz,  yx,  yy,  yz,  zx,  zy,  zz)
        setVertex(out, 2, center,  xx,  xy,  xz, -yx, -yy, -yz,  zx,  zy,  zz)
        setVertex(out, 3, center,  xx,  xy,  xz,  yx,  yy,  yz, -zx, -zy, -zz)
        setVertex(out, 4, center, -xx, -xy, -xz, -yx, -yy, -yz, -zx, -zy, -zz)
        setVertex(out, 5, center,  xx,  xy,  xz, -yx, -yy, -yz, -zx, -zy, -zz)
        setVertex(out, 6, center, -xx, -xy, -xz,  yx,  yy,  yz, -zx, -zy, -zz)
        setVertex(out, 7, center, -xx, -xy, -xz, -yx, -yy, -yz,  zx,  zy,  zz)
    }

    private fun setVertex(
        out: FloatArray, index: Int, center: Vector3,
        sxX: Float, sxY: Float, sxZ: Float,
        syX: Float, syY: Float, syZ: Float,
        szX: Float, szY: Float, szZ: Float
    ) {
        val o = index * 3
        out[o] = center.x + sxX + syX + szX
        out[o + 1] = center.y + sxY + syY + szY
        out[o + 2] = center.z + sxZ + syZ + szZ
    }
}
