package io.github.sceneview.collision

import io.github.sceneview.logging.logWarning
import kotlin.math.max
import kotlin.math.min

/**
 * Mathematical representation of a box. Used to perform intersection and collision tests against
 * oriented boxes.
 */
class Box : CollisionShape {
    private val center = Vector3.zero()
    private val size = Vector3.one()
    private val rotationMatrix = Matrix()

    companion object {
        private const val TAG = "Box"
    }

    /** Create a box with a center of (0,0,0) and a size of (1,1,1). */
    constructor()

    /** Create an axis-aligned box centered at (0,0,0) with the given full [size]. */
    constructor(size: Vector3) : this(size, Vector3.zero())

    /**
     * Create a box with the given [size] and [center].
     *
     * @param size Full extents along each axis (not half-extents).
     * @param center Box center in local space.
     */
    constructor(size: Vector3, center: Vector3) {
        Preconditions.checkNotNull(center, "Parameter \"center\" was null.")
        Preconditions.checkNotNull(size, "Parameter \"size\" was null.")
        setCenter(center)
        setSize(size)
    }

    /**
     * Sets the box center and notifies listeners that the shape changed.
     *
     * @param center New center in local space. The vector is copied, not retained.
     */
    fun setCenter(center: Vector3) {
        Preconditions.checkNotNull(center, "Parameter \"center\" was null.")
        this.center.set(center)
        onChanged()
    }

    /** Returns a copy of the box center. Mutating it does not affect the box. */
    fun getCenter(): Vector3 = Vector3(center)

    /**
     * Sets the full box size and notifies listeners that the shape changed.
     *
     * @param size Full extents along each axis (not half-extents).
     */
    fun setSize(size: Vector3) {
        Preconditions.checkNotNull(size, "Parameter \"size\" was null.")
        this.size.set(size)
        onChanged()
    }

    /** Returns a copy of the full box size (extents along each axis). */
    fun getSize(): Vector3 = Vector3(size)

    /** Returns the box half-extents (half the full size along each axis). */
    fun getExtents(): Vector3 = getSize().scaled(0.5f)

    /**
     * Sets the box orientation, turning it into an oriented bounding box.
     *
     * @param rotation Orientation of the box relative to its parent space.
     */
    fun setRotation(rotation: Quaternion) {
        Preconditions.checkNotNull(rotation, "Parameter \"rotation\" was null.")
        rotationMatrix.makeRotation(rotation)
        onChanged()
    }

    /** Returns the current box orientation as a quaternion. */
    fun getRotation(): Quaternion {
        val result = Quaternion()
        rotationMatrix.extractQuaternion(result)
        return result
    }

    /** Returns an independent copy of this box with the same size and center. */
    override fun makeCopy(): Box = Box(getSize(), getCenter())

    internal fun getRawRotationMatrix(): Matrix = rotationMatrix

    /**
     * Tests whether [ray] intersects this oriented box and, if so, fills [result] with the hit.
     *
     * Uses the slab method against the box's three local axes. The nearest non-negative
     * intersection is reported; if the ray origin is inside the box, the exit point is used.
     *
     * @param ray Ray in the same space as the box.
     * @param result Mutated in place with the hit distance and point when an intersection occurs.
     * @return `true` if the ray intersects the box, `false` otherwise.
     */
    override fun rayIntersection(ray: Ray, result: RayHit): Boolean {
        Preconditions.checkNotNull(ray, "Parameter \"ray\" was null.")
        Preconditions.checkNotNull(result, "Parameter \"result\" was null.")

        // Read-only refs: the slab test only reads the components — no mutation of
        // the ray's vectors. Half-extents and the per-axis vectors are read as
        // scalars from the backing arrays so no Vector3 is allocated per test.
        val rayDirection = ray.directionRef()
        val rayOrigin = ray.originRef()
        // Half-extents (was getExtents() = getSize().scaled(0.5f)); min = -max per axis.
        val maxX = size.x * 0.5f
        val maxY = size.y * 0.5f
        val maxZ = size.z * 0.5f

        var tMin = -Float.MAX_VALUE
        var tMax = Float.MAX_VALUE

        // delta = center - rayOrigin (read as scalars; was Vector3.subtract).
        val deltaX = center.x - rayOrigin.x
        val deltaY = center.y - rayOrigin.y
        val deltaZ = center.z - rayOrigin.z

        val axes = rotationMatrix.data

        // --- X axis (rotation column 0) ---
        var ax = axes[0]; var ay = axes[1]; var az = axes[2]
        var e = ax * deltaX + ay * deltaY + az * deltaZ
        var f = rayDirection.x * ax + rayDirection.y * ay + rayDirection.z * az

        if (kotlin.math.abs(f) >= 1.0E-6f) {
            var t1 = (e - maxX) / f
            var t2 = (e + maxX) / f

            if (t1 > t2) {
                val temp = t1; t1 = t2; t2 = temp
            }

            tMax = min(t2, tMax)
            tMin = max(t1, tMin)

            if (tMax < tMin) {
                return false
            }
        } else if (-e - maxX > 0.0f || -e + maxX < 0.0f) {
            return false
        }

        // --- Y axis (rotation column 1) ---
        ax = axes[4]; ay = axes[5]; az = axes[6]
        e = ax * deltaX + ay * deltaY + az * deltaZ
        f = rayDirection.x * ax + rayDirection.y * ay + rayDirection.z * az

        if (kotlin.math.abs(f) >= 1.0E-6f) {
            var t1 = (e - maxY) / f
            var t2 = (e + maxY) / f

            if (t1 > t2) {
                val temp = t1; t1 = t2; t2 = temp
            }

            tMax = min(t2, tMax)
            tMin = max(t1, tMin)

            if (tMax < tMin) {
                return false
            }
        } else if (-e - maxY > 0.0f || -e + maxY < 0.0f) {
            return false
        }

        // --- Z axis (rotation column 2) ---
        ax = axes[8]; ay = axes[9]; az = axes[10]
        e = ax * deltaX + ay * deltaY + az * deltaZ
        f = rayDirection.x * ax + rayDirection.y * ay + rayDirection.z * az

        if (kotlin.math.abs(f) >= 1.0E-6f) {
            var t1 = (e - maxZ) / f
            var t2 = (e + maxZ) / f

            if (t1 > t2) {
                val temp = t1; t1 = t2; t2 = temp
            }

            tMax = min(t2, tMax)
            tMin = max(t1, tMin)

            if (tMax < tMin) {
                return false
            }
        } else if (-e - maxZ > 0.0f || -e + maxZ < 0.0f) {
            return false
        }

        // If tMax < 0, the box is entirely behind the ray origin
        if (tMax < 0f) {
            return false
        }

        // Use tMin if it's non-negative (ray starts outside box), else tMax (ray starts inside box)
        val distance = if (tMin >= 0f) tMin else tMax
        result.setDistance(distance)
        result.setPoint(ray.getPoint(distance))
        return true
    }

    /** Returns `true` if this box overlaps [shape] (dispatches by the shape's concrete type). */
    override fun shapeIntersection(shape: CollisionShape): Boolean {
        Preconditions.checkNotNull(shape, "Parameter \"shape\" was null.")
        return shape.boxIntersection(this)
    }

    /** Returns `true` if this box overlaps [sphere]. */
    override fun sphereIntersection(sphere: Sphere): Boolean =
        Intersections.sphereBoxIntersection(sphere, this)

    /** Returns `true` if this box overlaps [box]. */
    override fun boxIntersection(box: Box): Boolean =
        Intersections.boxBoxIntersection(this, box)

    /**
     * Returns a new box obtained by applying [transformProvider]'s transform to this one.
     *
     * Position, size (scaled per-axis) and rotation are all carried through.
     */
    override fun transform(transformProvider: TransformProvider): CollisionShape {
        Preconditions.checkNotNull(transformProvider, "Parameter \"transformProvider\" was null.")
        val result = Box()
        transform(transformProvider, result)
        return result
    }

    /**
     * Applies [transformProvider]'s transform to this box, writing into [result].
     *
     * @param result Must be a [Box] and must not be this same instance; otherwise the call
     *   is a no-op (wrong type) or throws (self).
     * @throws IllegalArgumentException if [result] is this same box instance.
     */
    override fun transform(transformProvider: TransformProvider, result: CollisionShape) {
        Preconditions.checkNotNull(transformProvider, "Parameter \"transformProvider\" was null.")
        Preconditions.checkNotNull(result, "Parameter \"result\" was null.")

        if (result !is Box) {
            logWarning(TAG, "Cannot pass CollisionShape of a type other than Box into Box.transform.")
            return
        }

        if (result === this) {
            throw IllegalArgumentException("Box cannot transform itself.")
        }

        val modelMatrix = transformProvider.getTransformationMatrix()

        result.center.set(modelMatrix.transformPoint(center))

        val worldScale = Vector3()
        modelMatrix.decomposeScale(worldScale)
        result.size.x = size.x * worldScale.x
        result.size.y = size.y * worldScale.y
        result.size.z = size.z * worldScale.z

        modelMatrix.decomposeRotation(worldScale, result.rotationMatrix)
        Matrix.multiply(rotationMatrix, result.rotationMatrix, result.rotationMatrix)
    }
}
