// Derived from Google Sceneform (Apache-2.0), modified by the SceneView contributors.
package io.github.sceneview.collision

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A mutable float quaternion representing a 3D rotation.
 *
 * Quaternion operations are Hamiltonian using the right-hand-rule convention.
 * Components are stored normalized — most setters re-normalize automatically.
 * The identity quaternion `(0, 0, 0, 1)` represents no rotation.
 */
class Quaternion {
    /** The X component of the rotation axis (imaginary part). */
    var x: Float
    /** The Y component of the rotation axis (imaginary part). */
    var y: Float
    /** The Z component of the rotation axis (imaginary part). */
    var z: Float
    /** The scalar (real) component. */
    var w: Float

    /** Construct Quaternion and set to Identity */
    constructor() {
        x = 0f
        y = 0f
        z = 0f
        w = 1f
    }

    /**
     * Construct Quaternion and set each value. The Quaternion will be normalized during construction
     */
    constructor(x: Float, y: Float, z: Float, w: Float) {
        this.x = 0f
        this.y = 0f
        this.z = 0f
        this.w = 1f
        set(x, y, z, w)
    }

    /** Construct Quaternion using values from another Quaternion */
    constructor(q: Quaternion) {
        x = 0f
        y = 0f
        z = 0f
        w = 1f
        set(q)
    }

    /**
     * Construct Quaternion using an axis/angle to define the rotation
     *
     * @param axis Sets rotation direction
     * @param angle Angle size in degrees
     */
    constructor(axis: Vector3, angle: Float) {
        x = 0f
        y = 0f
        z = 0f
        w = 1f
        set(axisAngle(axis, angle))
    }

    /**
     * Construct Quaternion based on eulerAngles.
     *
     * @see eulerAngles
     * @param eulerAngles - the angle in degrees for each axis.
     */
    constructor(eulerAngles: Vector3) {
        x = 0f
        y = 0f
        z = 0f
        w = 1f
        set(eulerAngles(eulerAngles))
    }

    /** Copy values from another Quaternion into this one */
    fun set(q: Quaternion) {
        x = q.x
        y = q.y
        z = q.z
        w = q.w
        normalize()
    }

    /** Update this Quaternion using an axis/angle to define the rotation */
    fun set(axis: Vector3, angle: Float) {
        set(axisAngle(axis, angle))
    }

    /** Set each value and normalize the Quaternion */
    fun set(qx: Float, qy: Float, qz: Float, qw: Float) {
        x = qx
        y = qy
        z = qz
        w = qw
        normalize()
    }

    /** Set the Quaternion to identity */
    fun setIdentity() {
        x = 0f
        y = 0f
        z = 0f
        w = 1f
    }

    /**
     * Rescales the quaternion to the unit length.
     *
     * If the Quaternion can not be scaled, it is set to identity and false is returned.
     *
     * @return true if the Quaternion was non-zero
     */
    fun normalize(): Boolean {
        val normSquared = dot(this, this)
        if (MathHelper.almostEqualRelativeAndAbs(normSquared, 0.0f)) {
            setIdentity()
            return false
        } else if (normSquared != 1f) {
            val norm = (1.0 / sqrt(normSquared)).toFloat()
            x *= norm
            y *= norm
            z *= norm
            w *= norm
        }
        return true
    }

    /** Returns a normalized (unit-length) copy of this quaternion, leaving this one unchanged. */
    fun normalized(): Quaternion {
        val result = Quaternion(this)
        result.normalize()
        return result
    }

    /**
     * Returns the inverse rotation as a new quaternion (the conjugate, valid for unit quaternions).
     *
     * Applying this rotation after the original cancels it out.
     */
    fun inverted(): Quaternion = Quaternion(-this.x, -this.y, -this.z, this.w)

    /**
     * Returns a new quaternion with all components negated.
     *
     * This represents the *same* rotation as the original (q and -q are equivalent),
     * but is useful for taking the shortest interpolation path.
     */
    fun negated(): Quaternion = Quaternion(-this.x, -this.y, -this.z, -this.w)

    override fun toString(): String = "[x=$x, y=$y, z=$z, w=$w]"

    internal fun scaled(a: Float): Quaternion {
        val result = Quaternion()
        result.x = this.x * a
        result.y = this.y * a
        result.z = this.z * a
        result.w = this.w * a
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Quaternion) {
            return false
        }
        if (this === other) {
            return true
        }
        return equals(this, other)
    }

    override fun hashCode(): Int {
        val prime = 31
        var result = 1
        result = prime * result + w.toBits()
        result = prime * result + x.toBits()
        result = prime * result + y.toBits()
        result = prime * result + z.toBits()
        return result
    }

    /**
     * Returns this rotation as Euler angles in degrees (pitch, yaw, roll) on the X, Y, Z axes.
     *
     * @return A [Vector3] where x/y/z hold the rotation about each axis in degrees.
     */
    fun getEulerAngles(): Vector3 {
        val xRadians = atan2((2.0f * (y * z + w * x)).toDouble(), (w * w - x * x - y * y + z * z).toDouble())
        val yRadians = asin((-2.0f * (x * z - w * y)).toDouble())
        val zRadians = atan2((2.0f * (x * y + w * z)).toDouble(), (w * w + x * x - y * y - z * z).toDouble())
        return Vector3(
            (xRadians * (180.0 / PI)).toFloat(),
            (yRadians * (180.0 / PI)).toFloat(),
            (zRadians * (180.0 / PI)).toFloat()
        )
    }

    companion object {
        private const val SLERP_THRESHOLD = 0.9995f

        /** Rotates [src] by quaternion [q] and returns the rotated vector. */
        fun rotateVector(q: Quaternion, src: Vector3): Vector3 {
            val result = Vector3()
            val w2 = q.w * q.w
            val x2 = q.x * q.x
            val y2 = q.y * q.y
            val z2 = q.z * q.z
            val zw = q.z * q.w
            val xy = q.x * q.y
            val xz = q.x * q.z
            val yw = q.y * q.w
            val yz = q.y * q.z
            val xw = q.x * q.w
            val m00 = w2 + x2 - z2 - y2
            val m01 = xy + zw + zw + xy
            val m02 = xz - yw + xz - yw
            val m10 = -zw + xy - zw + xy
            val m11 = y2 - z2 + w2 - x2
            val m12 = yz + yz + xw + xw
            val m20 = yw + xz + xz + yw
            val m21 = yz + yz - xw - xw
            val m22 = z2 - y2 - x2 + w2
            val sx = src.x
            val sy = src.y
            val sz = src.z
            result.x = m00 * sx + m10 * sy + m20 * sz
            result.y = m01 * sx + m11 * sy + m21 * sz
            result.z = m02 * sx + m12 * sy + m22 * sz
            return result
        }

        /** Rotates [src] by the inverse of quaternion [q] and returns the rotated vector. */
        fun inverseRotateVector(q: Quaternion, src: Vector3): Vector3 {
            val result = Vector3()
            val w2 = q.w * q.w
            val x2 = -q.x * -q.x
            val y2 = -q.y * -q.y
            val z2 = -q.z * -q.z
            val zw = -q.z * q.w
            val xy = -q.x * -q.y
            val xz = -q.x * -q.z
            val yw = -q.y * q.w
            val yz = -q.y * -q.z
            val xw = -q.x * q.w
            val m00 = w2 + x2 - z2 - y2
            val m01 = xy + zw + zw + xy
            val m02 = xz - yw + xz - yw
            val m10 = -zw + xy - zw + xy
            val m11 = y2 - z2 + w2 - x2
            val m12 = yz + yz + xw + xw
            val m20 = yw + xz + xz + yw
            val m21 = yz + yz - xw - xw
            val m22 = z2 - y2 - x2 + w2
            val sx = src.x
            val sy = src.y
            val sz = src.z
            result.x = m00 * sx + m10 * sy + m20 * sz
            result.y = m01 * sx + m11 * sy + m21 * sz
            result.z = m02 * sx + m12 * sy + m22 * sz
            return result
        }

        /**
         * Returns the Hamilton product `lhs * rhs` — the rotation that applies [rhs] first,
         * then [lhs]. Quaternion multiplication is not commutative.
         */
        fun multiply(lhs: Quaternion, rhs: Quaternion): Quaternion {
            val lx = lhs.x
            val ly = lhs.y
            val lz = lhs.z
            val lw = lhs.w
            val rx = rhs.x
            val ry = rhs.y
            val rz = rhs.z
            val rw = rhs.w

            return Quaternion(
                lw * rx + lx * rw + ly * rz - lz * ry,
                lw * ry - lx * rz + ly * rw + lz * rx,
                lw * rz + lx * ry - ly * rx + lz * rw,
                lw * rw - lx * rx - ly * ry - lz * rz
            )
        }

        /**
         * Returns the component-wise sum of [lhs] and [rhs].
         *
         * Note: the result is *not* a normalized rotation — addition is only meaningful
         * as an intermediate step in interpolation (see [slerp]).
         */
        fun add(lhs: Quaternion, rhs: Quaternion): Quaternion {
            val result = Quaternion()
            result.x = lhs.x + rhs.x
            result.y = lhs.y + rhs.y
            result.z = lhs.z + rhs.z
            result.w = lhs.w + rhs.w
            return result
        }

        internal fun dot(lhs: Quaternion, rhs: Quaternion): Float =
            lhs.x * rhs.x + lhs.y * rhs.y + lhs.z * rhs.z + lhs.w * rhs.w

        internal fun lerp(a: Quaternion, b: Quaternion, ratio: Float): Quaternion = Quaternion(
            MathHelper.lerp(a.x, b.x, ratio),
            MathHelper.lerp(a.y, b.y, ratio),
            MathHelper.lerp(a.z, b.z, ratio),
            MathHelper.lerp(a.w, b.w, ratio)
        )

        /**
         * Spherical linear interpolation between rotations [start] and [end].
         *
         * Always takes the shortest path and falls back to linear interpolation for
         * nearly-parallel rotations to avoid numerical instability.
         *
         * @param t Interpolation factor; 0 returns [start], 1 returns [end].
         */
        fun slerp(start: Quaternion, end: Quaternion, t: Float): Quaternion {
            val orientation0 = start.normalized()
            var orientation1 = end.normalized()

            var cosTheta0 = dot(orientation0, orientation1).toDouble()

            if (cosTheta0 < 0.0f) {
                orientation1 = orientation1.negated()
                cosTheta0 = -cosTheta0
            }

            if (cosTheta0 > SLERP_THRESHOLD) {
                return lerp(orientation0, orientation1, t)
            }

            cosTheta0 = max(-1.0, min(1.0, cosTheta0))

            val theta0 = acos(cosTheta0)
            val thetaT = theta0 * t

            val s0 = cos(thetaT) - cosTheta0 * sin(thetaT) / sin(theta0)
            val s1 = sin(thetaT) / sin(theta0)
            val result = add(orientation0.scaled(s0.toFloat()), orientation1.scaled(s1.toFloat()))
            return result.normalized()
        }

        /**
         * Builds a rotation of [degrees] around the given [axis].
         *
         * @param axis Rotation axis. Need not be normalized — the result is normalized internally.
         * @param degrees Rotation angle in degrees (right-hand rule).
         */
        fun axisAngle(axis: Vector3, degrees: Float): Quaternion {
            val dest = Quaternion()
            val angle = degrees.toDouble() * (PI / 180.0)
            val factor = sin(angle / 2.0)

            dest.x = (axis.x * factor).toFloat()
            dest.y = (axis.y * factor).toFloat()
            dest.z = (axis.z * factor).toFloat()
            dest.w = cos(angle / 2.0).toFloat()
            dest.normalize()
            return dest
        }

        /**
         * Builds a rotation from Euler angles in degrees.
         *
         * @param eulerAngles Rotation about the X, Y and Z axes in degrees. Composed as
         *   intrinsic ZYX (extrinsic XYZ), matching [getEulerAngles].
         */
        fun eulerAngles(eulerAngles: Vector3): Quaternion {
            val qX = Quaternion(Vector3.right(), eulerAngles.x)
            val qY = Quaternion(Vector3.up(), eulerAngles.y)
            val qZ = Quaternion(Vector3.back(), eulerAngles.z)
            // Compose as intrinsic ZYX (extrinsic XYZ) to match getEulerAngles() decomposition
            return multiply(multiply(qZ, qY), qX)
        }

        /**
         * Returns the shortest-arc rotation that turns direction [start] into direction [end].
         *
         * Both inputs are normalized internally. Handles the antiparallel case (180° rotation).
         */
        fun rotationBetweenVectors(start: Vector3, end: Vector3): Quaternion {
            val startN = start.normalized()
            val endN = end.normalized()

            val cosTheta = Vector3.dot(startN, endN)
            val rotationAxis: Vector3

            if (cosTheta < -1.0f + 0.001f) {
                var axis = Vector3.cross(Vector3.back(), startN)
                if (axis.lengthSquared() < 0.01f) {
                    axis = Vector3.cross(Vector3.right(), startN)
                }

                rotationAxis = axis.normalized()
                return axisAngle(rotationAxis, 180.0f)
            }

            rotationAxis = Vector3.cross(startN, endN)

            val squareLength = sqrt((1.0 + cosTheta) * 2.0).toFloat()
            val inverseSquareLength = 1.0f / squareLength

            return Quaternion(
                rotationAxis.x * inverseSquareLength,
                rotationAxis.y * inverseSquareLength,
                rotationAxis.z * inverseSquareLength,
                squareLength * 0.5f
            )
        }

        /**
         * Builds a rotation that orients the local forward axis along [forwardInWorld]
         * while keeping the local up axis as close as possible to [desiredUpInWorld].
         *
         * @param forwardInWorld Desired forward (look) direction in world space.
         * @param desiredUpInWorld Desired up direction in world space (used to resolve roll).
         */
        fun lookRotation(forwardInWorld: Vector3, desiredUpInWorld: Vector3): Quaternion {
            val rotateForwardToDesiredForward = rotationBetweenVectors(Vector3.forward(), forwardInWorld)

            val rightInWorld = Vector3.cross(forwardInWorld, desiredUpInWorld)
            val desiredUpInWorldNew = Vector3.cross(rightInWorld, forwardInWorld)

            val newUp = rotateVector(rotateForwardToDesiredForward, Vector3.up())
            val rotateNewUpToUpwards = rotationBetweenVectors(newUp, desiredUpInWorldNew)

            return multiply(rotateNewUpToUpwards, rotateForwardToDesiredForward)
        }

        /**
         * Returns `true` if [lhs] and [rhs] represent the same rotation within floating-point
         * tolerance. `q` and `-q` are treated as equal since they encode the same orientation.
         */
        fun equals(lhs: Quaternion, rhs: Quaternion): Boolean {
            // Use abs(dot) to handle both q and -q representing the same rotation.
            // Compare abs(1.0 - abs(dot)) <= epsilon to tolerate float precision
            // where dot can slightly exceed 1.0 for normalized quaternions.
            val dotVal = kotlin.math.abs(dot(lhs, rhs))
            return kotlin.math.abs(1.0f - dotVal) <= MathHelper.FLT_EPSILON * 10f
        }

        /** Returns the identity quaternion `(0, 0, 0, 1)` — no rotation. */
        fun identity(): Quaternion = Quaternion()
    }
}
