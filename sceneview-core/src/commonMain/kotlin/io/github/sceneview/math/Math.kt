package io.github.sceneview.math

import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat3
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.RotationsOrder
import dev.romainguy.kotlin.math.clamp
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.eulerAngles
import dev.romainguy.kotlin.math.lookAt
import dev.romainguy.kotlin.math.lookTowards
import dev.romainguy.kotlin.math.mix
import dev.romainguy.kotlin.math.normalize
import dev.romainguy.kotlin.math.pow
import dev.romainguy.kotlin.math.rotation
import dev.romainguy.kotlin.math.scale
import dev.romainguy.kotlin.math.slerp
import dev.romainguy.kotlin.math.translation
import io.github.sceneview.collision.Matrix
import io.github.sceneview.collision.Vector3
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/** 2D position (x, y). */
typealias Position2 = Float2
/** 3D position (x, y, z) in world or local space. */
typealias Position = Float3
/** Euler angle rotation in degrees (pitch, yaw, roll). */
typealias Rotation = Float3
/**
 * Non-uniform scale (x, y, z).
 *
 * **Gotcha** — prefer the positional single-arg form `Scale(1f)` over the named
 * form `Scale(x = 1f)` when you want a uniform scale:
 *
 *   - `Scale(1f)` resolves to kotlin-math's `Float3(v: Float)` constructor and
 *     fills all three components → `(1, 1, 1)`.
 *   - `Scale(x = 1f)` resolves to the three-arg primary constructor with
 *     defaults `y = 0f, z = 0f` → `(1, 0, 0)`. Filament treats that as a
 *     singular transform: quaternion decomposition returns NaN, any child
 *     volume collapses, and frame-loop drivers (physics, animation) feed the
 *     NaN back through their integrators, silently breaking the whole scene
 *     subtree.
 *
 * Lesson from the `PhysicsDemo` spheres-invisible bug.
 */
typealias Scale = Float3
/** Unit direction vector (x, y, z). */
typealias Direction = Float3
/** 3D size / extents (width, height, depth). */
typealias Size = Float3
/** 4x4 transformation matrix (position + rotation + scale). */
typealias Transform = Mat4
/** RGBA color (r, g, b, a) with values typically in [0..1]. */
typealias Color = Float4

/**
 * Construct a [Transform] matrix from position, quaternion rotation, and scale.
 */
fun Transform(
    position: Position = Position(),
    quaternion: Quaternion = Quaternion(),
    scale: Scale = Scale(1.0f)
) = translation(position) * rotation(quaternion) * scale(scale)

/**
 * Construct a [Transform] matrix from position, Euler angle rotation (degrees), and scale.
 */
fun Transform(
    position: Position = Position(),
    rotation: Rotation,
    scale: Scale = Scale(1.0F)
) = translation(position) * rotation(rotation.toQuaternion()) * scale(scale)

fun FloatArray.toFloat3() = this.let { (x, y, z) -> Float3(x, y, z) }
fun FloatArray.toFloat4() = this.let { (x, y, z, w) -> Float4(x, y, z, w) }
fun DoubleArray.toFloat4() = this.map { it.toFloat() }.let { (x, y, z, w) -> Float4(x, y, z, w) }
fun FloatArray.toPosition() = this.let { (x, y, z) -> Position(x, y, z) }
fun FloatArray.toRotation() = this.let { (x, y, z) -> Rotation(x, y, z) }
fun FloatArray.toScale() = this.let { (x, y, z) -> Scale(x, y, z) }
fun FloatArray.toDirection() = this.let { (x, y, z) -> Direction(x, y, z) }

fun Rotation.toQuaternion(order: RotationsOrder = RotationsOrder.ZYX) =
    Quaternion.fromEuler(this, order)

fun Quaternion.toRotation(order: RotationsOrder = RotationsOrder.ZYX) = eulerAngles(this, order)

fun FloatArray.toSize() = this.let { (x, y, z) -> Size(x, y, z) }

//TODO: Remove when everything use Float3
fun Float3.toVector3() = Vector3(x, y, z)

//TODO: Remove when everything use Float3
fun Vector3.toFloat3() = Float3(x, y, z)


fun Mat3.toColumnsFloatArray() = floatArrayOf(
    x.x, x.y, x.z,
    y.x, y.y, y.z,
    z.x, z.y, z.z
)

/**
 * Writes this [Mat3]'s 9 columns into [out] starting at [offset] (column-major).
 *
 * Allocation-free counterpart to [toColumnsFloatArray]. Pass a reusable scratch
 * buffer to avoid the per-call `FloatArray(9)` allocation on hot paths.
 *
 * @return [out], for chaining.
 */
fun Mat3.copyColumnsInto(out: FloatArray, offset: Int = 0): FloatArray {
    require(out.size >= offset + 9) {
        "FloatArray needs >= 9 slots from offset $offset (size=${out.size})"
    }
    out[offset] = x.x; out[offset + 1] = x.y; out[offset + 2] = x.z
    out[offset + 3] = y.x; out[offset + 4] = y.y; out[offset + 5] = y.z
    out[offset + 6] = z.x; out[offset + 7] = z.y; out[offset + 8] = z.z
    return out
}

/**
 * Returns this [Mat4]'s 16 components as a column-major [DoubleArray].
 *
 * Fills the result directly rather than going through [toColumnsFloatArray] and a boxed
 * `List<Double>` — one allocation instead of three for the same conversion.
 */
fun Mat4.toColumnsDoubleArray() = doubleArrayOf(
    x.x.toDouble(), x.y.toDouble(), x.z.toDouble(), x.w.toDouble(),
    y.x.toDouble(), y.y.toDouble(), y.z.toDouble(), y.w.toDouble(),
    z.x.toDouble(), z.y.toDouble(), z.z.toDouble(), z.w.toDouble(),
    w.x.toDouble(), w.y.toDouble(), w.z.toDouble(), w.w.toDouble()
)

/** Extracts the rotation component of this transform as a [Quaternion]. */
val Mat4.quaternion: Quaternion
    get() = rotation(this).toQuaternion()

/** Transforms a 3D point by this matrix (applies translation, rotation, and scale). */
operator fun Mat4.times(v: Float3) = (this * Float4(v, 1f)).xyz

fun Mat4.toMatrix() = Matrix(toColumnsFloatArray())

fun Mat4.toColumnsFloatArray() = floatArrayOf(
    x.x, x.y, x.z, x.w,
    y.x, y.y, y.z, y.w,
    z.x, z.y, z.z, z.w,
    w.x, w.y, w.z, w.w
)

/**
 * Writes this [Mat4]'s 16 columns into [out] starting at [offset] (column-major).
 *
 * Allocation-free counterpart to [toColumnsFloatArray]. Pass a reusable scratch
 * buffer to avoid the per-call `FloatArray(16)` allocation on hot paths such as
 * `TransformManager.setTransform`, fired thousands of times per second on an
 * animated scene.
 *
 * @return [out], for chaining.
 */
fun Mat4.copyColumnsInto(out: FloatArray, offset: Int = 0): FloatArray {
    require(out.size >= offset + 16) {
        "FloatArray needs >= 16 slots from offset $offset (size=${out.size})"
    }
    out[offset] = x.x; out[offset + 1] = x.y; out[offset + 2] = x.z; out[offset + 3] = x.w
    out[offset + 4] = y.x; out[offset + 5] = y.y; out[offset + 6] = y.z; out[offset + 7] = y.w
    out[offset + 8] = z.x; out[offset + 9] = z.y; out[offset + 10] = z.z; out[offset + 11] = z.w
    out[offset + 12] = w.x; out[offset + 13] = w.y; out[offset + 14] = w.z; out[offset + 15] = w.w
    return out
}

fun FloatArray.toTransform() = Transform(
    x = Float4(this[0], this[1], this[2], this[3]),
    y = Float4(this[4], this[5], this[6], this[7]),
    z = Float4(this[8], this[9], this[10], this[11]),
    w = Float4(this[12], this[13], this[14], this[15])
)

fun DoubleArray.toTransform() = this.map { it.toFloat() }.toFloatArray().toTransform()

fun lerp(start: Float3, end: Float3, deltaSeconds: Float) = mix(start, end, deltaSeconds)

/**
 * Computes a quaternion that orients a surface given its [normal] direction.
 *
 * Derives a right-handed tangent/bitangent/normal frame and returns the
 * corresponding rotation quaternion. Used for orienting planar geometry
 * (planes, decals) to face a given direction.
 */
fun normalToTangent(normal: Float3): Quaternion {
    var tangent: Float3
    val bitangent: Float3

    // Calculate basis vectors (+x = tangent, +y = bitangent, +z = normal).
    tangent = cross(Direction(y = 1.0f), normal)

    // Uses almostEqualRelativeAndAbs for equality checks that account for float inaccuracy.
    if (dot(tangent, tangent) == 0.0f) {
        bitangent = normalize(cross(normal, Direction(x = 1.0f)))
        tangent = normalize(cross(bitangent, normal))
    } else {
        tangent = normalize(tangent)
        bitangent = normalize(cross(normal, tangent))
    }
    // Rotation of a 4x4 Transformation Matrix is represented by the top-left 3x3 elements.
    return Transform(right = tangent, up = bitangent, forward = normal).toQuaternion()
}

/**
 * Spherical / linear interpolate a transform toward [end] with frame-rate-independent
 * exponential decay.
 *
 * The interpolation factor is `1 - exp(-speed * deltaSeconds)`, which makes the result
 * invariant to the time-step distribution: 30 ticks at 1/30 s and 120 ticks at 1/120 s
 * produce the same trajectory over the same wall-clock total.
 *
 * Pre-#1126 this function used the linear approximation `clamp(speed * dt, 0, 1)` —
 * correct only at high frame rates, with visible divergence (and outright snap-to-target
 * when `speed * dt > 1`) at lower frame rates.
 *
 * @param start Current transform.
 * @param end Target transform.
 * @param deltaSeconds Time since last call. Negative values are clamped to 0.
 * @param speed Convergence rate (s⁻¹). Higher = faster approach. The 63 % point is
 * reached after `1 / speed` seconds regardless of the caller's tick rate.
 */
fun slerp(
    start: Transform,
    end: Transform,
    deltaSeconds: Double,
    speed: Float
): Transform {
    val (position, quaternion, scale) = slerp(
        startPosition = start.position,
        startQuaternion = start.quaternion,
        startScale = start.scale,
        endPosition = end.position,
        endQuaternion = end.quaternion,
        endScale = end.scale,
        deltaSeconds = deltaSeconds,
        speed = speed
    )
    return Transform(position, quaternion, scale)
}

/**
 * Pre-decomposed TRS-tuple variant of [slerp] — interpolates position/rotation/scale
 * components directly without ever building or decomposing a [Transform] (`Mat4`).
 *
 * The `slerp(start: Transform, end: Transform, …)` overload reads `.position`,
 * `.quaternion` and `.scale` off each [Transform], and **every one of those accessors runs
 * a matrix decomposition** (a W-column extract, a polar decomposition, and three column-length
 * `sqrt`s) — six decompositions per call. Callers that already hold the components (e.g. a
 * [io.github.sceneview.node.Node] whose `position` / `quaternion` / `scale` are cached since
 * #2187) should call this overload to skip all of them, then rebuild a `Transform` once from
 * the result if they need the matrix form.
 *
 * Uses the exact same frame-rate-independent factor `1 - exp(-speed * deltaSeconds)` as the
 * [Transform] overload, so trajectories are identical.
 *
 * @param startPosition Current position.
 * @param startQuaternion Current rotation.
 * @param startScale Current scale.
 * @param endPosition Target position.
 * @param endQuaternion Target rotation.
 * @param endScale Target scale.
 * @param deltaSeconds Time since last call. Negative values are clamped to 0.
 * @param speed Convergence rate (s⁻¹). The 63 % point is reached after `1 / speed` seconds
 * regardless of the caller's tick rate.
 * @return A [Triple] of the interpolated `(position, quaternion, scale)`.
 */
fun slerp(
    startPosition: Position,
    startQuaternion: Quaternion,
    startScale: Scale,
    endPosition: Position,
    endQuaternion: Quaternion,
    endScale: Scale,
    deltaSeconds: Double,
    speed: Float
): Triple<Position, Quaternion, Scale> {
    val lerpFactor = if (deltaSeconds <= 0.0 || speed <= 0f) {
        0f
    } else {
        (1.0 - exp(-speed.toDouble() * deltaSeconds)).toFloat().coerceIn(0f, 1f)
    }
    return Triple(
        lerp(startPosition, endPosition, lerpFactor),
        slerp(startQuaternion, endQuaternion, lerpFactor),
        lerp(startScale, endScale, lerpFactor)
    )
}

/**
 * If rendering in linear space, first convert the values to linear space by rising to the power 2.2
 *
 * Builds the result array in place rather than through a boxed `List<Float>` intermediate — one
 * allocation instead of two.
 */
fun FloatArray.toLinearSpace() = FloatArray(size) { pow(this[it], 2.2f) }

/**
 * Creates a view matrix looking from [eye] toward [target] with Y-up.
 */
fun lookAt(eye: Position, target: Position): Mat4 {
    return lookAt(eye, target - eye, Direction(y = 1.0f))
}

/**
 * Returns a quaternion that orients an object at [eye] to look along [direction] with Y-up.
 */
fun lookTowards(eye: Position, direction: Direction) =
    lookTowards(eye, direction, Direction(y = 1.0f)).toQuaternion()

fun List<Position2>.getCenter() = reduce { total, position -> total + position } / size.toFloat()
fun List<Position>.getCenter() = reduce { total, position -> total + position } / size.toFloat()

fun colorOf(r: Float = 0.0f, g: Float = 0.0f, b: Float = 0.0f, a: Float = 1.0f) = Color(r, g, b, a)
fun colorOf(rgb: Float = 0.0f, a: Float = 1.0f) = colorOf(r = rgb, g = rgb, b = rgb, a = a)

fun FloatArray.toColor() = Color(this[0], this[1], this[2], this.getOrNull(3) ?: 1.0f)

fun dev.romainguy.kotlin.math.Ray.toCollisionRay() =
    io.github.sceneview.collision.Ray(origin.toVector3(), direction.toVector3())

// Float comparison utilities
fun Float.equals(other: Float, delta: Float): Boolean = abs(this - other) <= delta

fun Float.compareTo(v: Float, delta: Float): Float = when {
    equals(v, delta) -> 0.0f
    else -> compareTo(v).toFloat()
}

/**
 * Returns true if two floats are equal within a tolerance. Useful for comparing floating point
 * numbers while accounting for the limitations in floating point precision.
 */
infix fun Float.almostEquals(other: Float): Boolean {
    val epsilon = max(ulp(this), ulp(other)) * 2
    return abs(this - other) <= epsilon
}

// Compare-with-tolerance helpers for Float2/Float3/Float4/Mat4 used to live here, but
// kotlin-math 1.8 shipped them as members with identical signatures. The extensions were
// shadowed and never actually invoked — the Kotlin compiler emitted "This extension is
// shadowed by a member" for each one. Removed in favour of the upstream members.
