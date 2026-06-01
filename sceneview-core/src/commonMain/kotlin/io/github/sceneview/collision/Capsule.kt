package io.github.sceneview.collision

import io.github.sceneview.logging.logWarning
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mathematical representation of a capsule (a swept sphere / cylinder with hemispherical caps).
 *
 * The capsule is defined by two endpoints (defining the axis) and a radius.
 * The axis runs along local Y by default, centered at [center].
 *
 * @see CollisionShape
 */
class Capsule : CollisionShape {

    private val center = Vector3.zero()
    private var height = 2.0f
    internal var radius = 0.5f
    private val rotationMatrix = Matrix()

    companion object {
        private const val TAG = "Capsule"
    }

    /** Create a capsule with height 2, radius 0.5, centered at origin. */
    constructor()

    /**
     * Create a capsule with the given dimensions.
     *
     * @param radius Capsule radius.
     * @param height Full height including hemispherical caps.
     * @param center Center position.
     */
    constructor(radius: Float, height: Float, center: Vector3 = Vector3.zero()) {
        this.radius = radius
        this.height = height
        this.center.set(center)
    }

    /** Sets the capsule center and notifies listeners that the shape changed. The vector is copied. */
    fun setCenter(center: Vector3) {
        this.center.set(center)
        onChanged()
    }

    /** Returns a copy of the capsule center. */
    fun getCenter(): Vector3 = Vector3(center)

    /** Sets the capsule radius and notifies listeners that the shape changed. */
    fun setRadius(radius: Float) {
        this.radius = radius
        onChanged()
    }

    /** Returns the capsule radius. */
    fun getRadius(): Float = radius

    /** Sets the full capsule height (including the hemispherical caps) and notifies listeners. */
    fun setHeight(height: Float) {
        this.height = height
        onChanged()
    }

    /** Returns the full capsule height, including the hemispherical caps. */
    fun getHeight(): Float = height

    /** Sets the capsule orientation and notifies listeners that the shape changed. */
    fun setRotation(rotation: Quaternion) {
        rotationMatrix.makeRotation(rotation)
        onChanged()
    }

    /**
     * Returns the two endpoints of the capsule's internal line segment (sphere centers).
     * The segment runs from bottom to top along the capsule axis.
     */
    fun getSegmentEndpoints(): Pair<Vector3, Vector3> {
        val bottom = Vector3()
        val top = Vector3()
        writeSegmentEndpoints(bottom, top)
        return Pair(bottom, top)
    }

    /**
     * Allocation-free variant of [getSegmentEndpoints]: writes the bottom and top
     * segment endpoints into the caller-supplied [bottom] / [top] scratch vectors.
     *
     * Hot-path helper for the collision math (ray + shape intersection), which would
     * otherwise allocate a [Pair] plus 5 [Vector3] per call. The two output vectors
     * must be distinct instances. The result is identical to [getSegmentEndpoints].
     */
    internal fun writeSegmentEndpoints(bottom: Vector3, top: Vector3) {
        val halfSegment = max(0f, height / 2f - radius)
        // localUp = rotation column 1, read as scalars (was an allocated Vector3).
        val upX = rotationMatrix.data[1]
        val upY = rotationMatrix.data[5]
        val upZ = rotationMatrix.data[9]
        bottom.set(
            center.x - upX * halfSegment,
            center.y - upY * halfSegment,
            center.z - upZ * halfSegment
        )
        top.set(
            center.x + upX * halfSegment,
            center.y + upY * halfSegment,
            center.z + upZ * halfSegment
        )
    }

    /** Returns an independent copy of this capsule with the same dimensions and orientation. */
    override fun makeCopy(): Capsule {
        val copy = Capsule(radius, height, getCenter())
        copy.rotationMatrix.set(rotationMatrix)
        return copy
    }

    /**
     * Tests whether [ray] intersects this capsule and, if so, fills [result] with the nearest hit.
     *
     * The capsule is tested as an infinite cylinder body plus two hemispherical caps;
     * the closest non-negative intersection across all parts is reported.
     *
     * @param ray Ray in the same space as the capsule.
     * @param result Mutated in place with the hit distance and point when an intersection occurs.
     * @return `true` if the ray intersects the capsule.
     */
    override fun rayIntersection(ray: Ray, result: RayHit): Boolean {
        // Ray-capsule intersection: test ray against the infinite cylinder,
        // then against the two hemispherical caps.
        // Function-local scratch endpoints (no Pair / per-call Vector3 alloc).
        val a = Vector3()
        val b = Vector3()
        writeSegmentEndpoints(a, b)

        // Read-only refs: the cylinder + cap math only reads components — no
        // mutation. All vector ops below are inlined as scalar math so no
        // Vector3 is allocated per intersection test.
        val rayOrigin = ray.originRef()
        val abDir = ray.directionRef()

        // ab = b - a
        val abX = b.x - a.x; val abY = b.y - a.y; val abZ = b.z - a.z
        // ao = rayOrigin - a
        val aoX = rayOrigin.x - a.x; val aoY = rayOrigin.y - a.y; val aoZ = rayOrigin.z - a.z

        val abDotAb = abX * abX + abY * abY + abZ * abZ
        val abDotD = abX * abDir.x + abY * abDir.y + abZ * abDir.z
        val abDotAo = abX * aoX + abY * aoY + abZ * aoZ

        // Quadratic equation for cylinder intersection
        val m = abDotD / abDotAb
        val n = abDotAo / abDotAb

        // q = abDir - ab * m
        val qX = abDir.x - abX * m; val qY = abDir.y - abY * m; val qZ = abDir.z - abZ * m
        // r = ao - ab * n
        val rX = aoX - abX * n; val rY = aoY - abY * n; val rZ = aoZ - abZ * n

        val qA = qX * qX + qY * qY + qZ * qZ
        val qB = 2f * (qX * rX + qY * rY + qZ * rZ)
        val qC = (rX * rX + rY * rY + rZ * rZ) - radius * radius

        val discriminant = qB * qB - 4f * qA * qC

        var bestT = Float.MAX_VALUE

        if (discriminant >= 0f && qA > 1e-10f) {
            val sqrtD = sqrt(discriminant)
            val t1 = (-qB - sqrtD) / (2f * qA)
            val t2 = (-qB + sqrtD) / (2f * qA)

            bestT = considerCylinderRoot(
                rayOrigin, abDir, t1, a.x, a.y, a.z, abX, abY, abZ, abDotAb, bestT
            )
            bestT = considerCylinderRoot(
                rayOrigin, abDir, t2, a.x, a.y, a.z, abX, abY, abZ, abDotAb, bestT
            )
        }

        // Test hemispherical caps (as sphere intersections), each cap center in turn.
        bestT = considerCap(rayOrigin, abDir, a.x, a.y, a.z, radius, bestT)
        bestT = considerCap(rayOrigin, abDir, b.x, b.y, b.z, radius, bestT)

        if (bestT < Float.MAX_VALUE) {
            result.setDistance(bestT)
            result.setPoint(ray.getPoint(bestT))
            return true
        }
        return false
    }

    /**
     * Returns `true` if this capsule overlaps [shape].
     *
     * Supports [Sphere], [Box] and [Capsule]; any other shape type returns `false`.
     */
    override fun shapeIntersection(shape: CollisionShape): Boolean {
        return when (shape) {
            is Sphere -> capsuleSphereIntersection(this, shape)
            is Box -> capsuleBoxIntersection(this, shape)
            is Capsule -> capsuleCapsuleIntersection(this, shape)
            else -> false
        }
    }

    /** Returns `true` if this capsule overlaps [sphere]. */
    override fun sphereIntersection(sphere: Sphere): Boolean =
        capsuleSphereIntersection(this, sphere)

    /** Returns `true` if this capsule overlaps [box]. */
    override fun boxIntersection(box: Box): Boolean =
        capsuleBoxIntersection(this, box)

    /** Returns `true` if this capsule overlaps another [Capsule]. */
    fun capsuleIntersection(other: Capsule): Boolean =
        capsuleCapsuleIntersection(this, other)

    /**
     * Returns a new capsule obtained by applying [transformProvider]'s transform to this one.
     *
     * Position, orientation, radius (scaled by the larger radial scale) and height are carried through.
     */
    override fun transform(transformProvider: TransformProvider): CollisionShape {
        val result = Capsule()
        transform(transformProvider, result)
        return result
    }

    /**
     * Applies [transformProvider]'s transform to this capsule, writing into [result].
     *
     * @param result Must be a [Capsule] and must not be this same instance.
     * @throws IllegalArgumentException if [result] is this same capsule instance.
     */
    override fun transform(transformProvider: TransformProvider, result: CollisionShape) {
        if (result !is Capsule) {
            logWarning(TAG, "Cannot pass CollisionShape of a type other than Capsule into Capsule.transform.")
            return
        }
        if (result === this) throw IllegalArgumentException("Capsule cannot transform itself.")

        val modelMatrix = transformProvider.getTransformationMatrix()
        result.center.set(modelMatrix.transformPoint(center))

        val worldScale = Vector3()
        modelMatrix.decomposeScale(worldScale)
        val maxRadialScale = max(abs(worldScale.x), abs(worldScale.z))
        result.radius = radius * maxRadialScale
        result.height = height * abs(worldScale.y)

        modelMatrix.decomposeRotation(worldScale, result.rotationMatrix)
        Matrix.multiply(rotationMatrix, result.rotationMatrix, result.rotationMatrix)
    }
}

// --- Capsule intersection helpers ---

/**
 * Considers one cylinder-body quadratic root for [Capsule.rayIntersection].
 *
 * Inlined scalar form of the original `for (t in listOf(t1, t2))` body: a non-negative
 * root whose projection onto the capsule axis falls within `[0,1]` and which beats the
 * current [bestT] becomes the new best. Returns the (possibly updated) best `t`. Pure
 * scalar math — no [Vector3] / list allocation. The hit-point projection is computed
 * directly from the ray origin/direction components rather than `ray.getPoint(t)`.
 */
private fun considerCylinderRoot(
    rayOrigin: Vector3,
    rayDir: Vector3,
    t: Float,
    aX: Float, aY: Float, aZ: Float,
    abX: Float, abY: Float, abZ: Float,
    abDotAb: Float,
    bestT: Float
): Float {
    if (t < 0f || t >= bestT) return bestT
    // hitPoint = rayOrigin + rayDir * t ; hp - a, dotted with ab.
    val hpaX = (rayOrigin.x + rayDir.x * t) - aX
    val hpaY = (rayOrigin.y + rayDir.y * t) - aY
    val hpaZ = (rayOrigin.z + rayDir.z * t) - aZ
    val projection = (hpaX * abX + hpaY * abY + hpaZ * abZ) / abDotAb
    return if (projection in 0f..1f) t else bestT
}

/**
 * Considers one hemispherical cap (treated as a sphere centered at the cap point) for
 * [Capsule.rayIntersection]. Inlined scalar form of the original cap loop: returns the
 * smallest non-negative root that beats [bestT], else [bestT] unchanged. No allocation.
 */
private fun considerCap(
    rayOrigin: Vector3,
    rayDir: Vector3,
    cX: Float, cY: Float, cZ: Float,
    radius: Float,
    bestT: Float
): Float {
    // oc = rayOrigin - capCenter
    val ocX = rayOrigin.x - cX; val ocY = rayOrigin.y - cY; val ocZ = rayOrigin.z - cZ
    val bCoeff = 2f * (ocX * rayDir.x + ocY * rayDir.y + ocZ * rayDir.z)
    val cCoeff = (ocX * ocX + ocY * ocY + ocZ * ocZ) - radius * radius
    val disc = bCoeff * bCoeff - 4f * cCoeff
    if (disc < 0f) return bestT

    val sqrtDisc = sqrt(disc)
    var best = bestT
    val t1 = (-bCoeff - sqrtDisc) / 2f
    if (t1 >= 0f && t1 < best) best = t1
    val t2 = (-bCoeff + sqrtDisc) / 2f
    if (t2 >= 0f && t2 < best) best = t2
    return best
}

/**
 * Closest point on a line segment AB to point P.
 * Returns the parameter t in [0,1] and the closest point.
 */
internal fun closestPointOnSegment(
    a: Vector3, b: Vector3, p: Vector3
): Pair<Float, Vector3> {
    val ab = Vector3.subtract(b, a)
    val ap = Vector3.subtract(p, a)
    val abLenSq = Vector3.dot(ab, ab)
    if (abLenSq < 1e-10f) return Pair(0f, Vector3(a))
    val t = (Vector3.dot(ap, ab) / abLenSq).coerceIn(0f, 1f)
    return Pair(t, Vector3.add(a, ab.scaled(t)))
}

/**
 * Closest points between two line segments AB and CD.
 * Returns (closest on AB, closest on CD).
 *
 * Implements Christer Ericson, *Real-Time Collision Detection* §5.1.9.
 *
 * Pre-#1126 this routine used `r = c - a` and inherited a consistent sign
 * flip on `d4`/`d5` (Ericson's `c`/`f`). The bug was masked for collinear
 * inputs (where `denom ≈ 0` and the degenerate branch dominates) but
 * surfaced as wildly wrong closest pairs on perpendicular / skew segments —
 * e.g. AB=(0,0,0)→(2,0,0), CD=(1,1,0)→(1,3,0) returned `(0,0,0)/(1,2,0)`
 * (distance √5) instead of the correct `(1,0,0)/(1,1,0)` (distance 1).
 * Fix: align with Ericson's convention `r = a - c`.
 */
internal fun closestPointsBetweenSegments(
    a: Vector3, b: Vector3, c: Vector3, d: Vector3
): Pair<Vector3, Vector3> {
    val ab = Vector3.subtract(b, a)
    val cd = Vector3.subtract(d, c)
    val r = Vector3.subtract(a, c)  // Ericson's `r = p1 - p2`

    val d1 = Vector3.dot(ab, ab)         // a in Ericson
    val d2 = Vector3.dot(ab, cd)         // b in Ericson
    val d3 = Vector3.dot(cd, cd)         // e in Ericson
    val d4 = Vector3.dot(ab, r)          // c in Ericson
    val d5 = Vector3.dot(cd, r)          // f in Ericson

    val denom = d1 * d3 - d2 * d2

    var s = if (denom > 1e-10f) ((d2 * d5 - d3 * d4) / denom).coerceIn(0f, 1f) else 0f
    var t = (d2 * s + d5) / d3.coerceAtLeast(1e-10f)

    if (t < 0f) {
        t = 0f
        s = (-d4 / d1.coerceAtLeast(1e-10f)).coerceIn(0f, 1f)
    } else if (t > 1f) {
        t = 1f
        s = ((d2 - d4) / d1.coerceAtLeast(1e-10f)).coerceIn(0f, 1f)
    }

    val pointOnAb = Vector3.add(a, ab.scaled(s))
    val pointOnCd = Vector3.add(c, cd.scaled(t))
    return Pair(pointOnAb, pointOnCd)
}

internal fun capsuleSphereIntersection(capsule: Capsule, sphere: Sphere): Boolean {
    val (a, b) = capsule.getSegmentEndpoints()
    val (_, closest) = closestPointOnSegment(a, b, sphere.getCenter())
    val diff = Vector3.subtract(closest, sphere.getCenter())
    val distSq = Vector3.dot(diff, diff)
    val combinedRadius = capsule.radius + sphere.getRadius()
    return distSq <= combinedRadius * combinedRadius
}

internal fun capsuleCapsuleIntersection(c1: Capsule, c2: Capsule): Boolean {
    val (a1, b1) = c1.getSegmentEndpoints()
    val (a2, b2) = c2.getSegmentEndpoints()
    val (p1, p2) = closestPointsBetweenSegments(a1, b1, a2, b2)
    val diff = Vector3.subtract(p1, p2)
    val distSq = Vector3.dot(diff, diff)
    val combinedRadius = c1.radius + c2.radius
    return distSq <= combinedRadius * combinedRadius
}

internal fun capsuleBoxIntersection(capsule: Capsule, box: Box): Boolean {
    // Approximate: test the capsule segment endpoints + center as spheres.
    // Function-local scratch endpoints; the per-point sphere test is done
    // allocation-free via Intersections.pointWithinBoxDistance (no Sphere alloc,
    // no listOf).
    val a = Vector3()
    val b = Vector3()
    capsule.writeSegmentEndpoints(a, b)
    val r = capsule.radius
    if (Intersections.pointWithinBoxDistance(a.x, a.y, a.z, r, box)) return true
    if (Intersections.pointWithinBoxDistance(b.x, b.y, b.z, r, box)) return true
    val mid = capsule.getCenter()
    return Intersections.pointWithinBoxDistance(mid.x, mid.y, mid.z, r, box)
}
