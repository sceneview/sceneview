package io.github.sceneview.ar.arcore

import com.google.ar.core.GeospatialPose
import com.google.ar.core.Pose
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.rotation
import dev.romainguy.kotlin.math.translation
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Transform
import io.github.sceneview.math.toPosition
import io.github.sceneview.math.toTransform

/** The translation component of this [Pose] as a SceneView [Position]. */
val Pose.position: Position
    get() = Position(x = tx(), y = ty(), z = tz())

/** The rotation component of this [Pose] as a [Quaternion]. */
val Pose.quaternion: Quaternion
    get() = Quaternion(x = qx(), y = qy(), z = qz(), w = qw())

/** Converts this [Pose] to a 4x4 [Transform] matrix. */
val Pose.transform: Transform
    get() = FloatArray(16).apply { toMatrix(this, 0) }.toTransform()
//    get() = translation(position) * rotation(quaternion)

/**
 * Converts this [Pose] to a 4x4 [Transform] matrix, writing the column-major floats into the
 * caller-supplied [out] scratch array instead of allocating a fresh `FloatArray(16)`.
 *
 * Hot-path callers that refresh the matrix every frame (e.g. [ARCameraNode]) should keep a single
 * reusable `FloatArray(16)` field and pass it here to avoid per-frame allocation churn (#2266 /
 * umbrella #2263).
 *
 * @param out a `FloatArray(16)` scratch buffer the matrix is written into; reused across calls.
 * @return [out] wrapped as a [Transform] (`Mat4`). The returned matrix shares the backing floats
 * with [out], so callers must not retain it past the next reuse of [out].
 */
fun Pose.toTransform(out: FloatArray): Transform {
    require(out.size == 16) { "Pose.toTransform out array must have size 16, was ${out.size}" }
    toMatrix(out, 0)
    return out.toTransform()
}

/** The rotation component of this [Pose] as Euler angles (pitch, yaw, roll) in degrees. */
val Pose.rotation: Rotation
    get() = quaternion.toEulerAngles()

/** The local X axis direction of this [Pose]. Non-null per ARCore API contract. */
val Pose.xDirection: Direction
    get() = checkNotNull(xAxis) { "Pose.xAxis was null" }.let { (x, y, z) ->
        Direction(x, y, z)
    }

/** The local Y axis direction (up) of this [Pose]. Non-null per ARCore API contract. */
val Pose.yDirection: Direction
    get() = checkNotNull(yAxis) { "Pose.yAxis was null" }.let { (x, y, z) ->
        Direction(x, y, z)
    }

/** The local Z axis direction (forward) of this [Pose]. Non-null per ARCore API contract. */
val Pose.zDirection: Direction
    get() = checkNotNull(zAxis) { "Pose.zAxis was null" }.let { (x, y, z) ->
        Direction(x, y, z)
    }

/**
 * Calculate the normal distance from this to the other.
 *
 * The given other pose should have y axis parallel to plane's normal, for example plane's center
 * pose or hit test pose.
 */
fun Pose.distanceTo(other: Pose): Float {
    val normal = FloatArray(3)
    // Get transformed Y axis of plane's coordinate system.
    getTransformedAxis(1, 1.0f, normal, 0)
    // Compute dot product of plane's normal with vector from camera to plane center.
    return (other.tx() - tx()) * normal[0] + (other.ty() - ty()) * normal[1] + (other.tz() - tz()) * normal[2]
}

/**
 * Calculate the normal distance to plane from camera Pose
 *
 * The given planePose should have y axis parallel to plane's normal, for example plane's center
 * pose or hit test pose.
 */
fun Pose.distanceToPlane(cameraPose: Pose): Float {
    val normal = FloatArray(3).apply {
        // Get transformed Y axis of plane's coordinate system.
        getTransformedAxis(1, 1.0f, this, 0)
    }.toPosition()
    val position = this.position
    val cameraPosition = cameraPose.position
    // Compute dot product of plane's normal with vector from camera to plane center.
    // Signed distance from camera to plane along the plane normal
    return dot((cameraPosition - position), normal)
//    return (cameraPosition.x - this.tx()) * normal[0] +
//            (cameraPosition.y - this.ty()) * normal[1] +
//            (cameraPosition.z - this.tz()) * normal[2]
}

/** Converts this [GeospatialPose] to a 4x4 [Transform] matrix using lat/lon/alt as position. */
val GeospatialPose.transform: Transform
    get() = translation(
        Position(
            latitude.toFloat(),
            longitude.toFloat(),
            altitude.toFloat()
        )
    ) * rotation(eastUpSouthQuaternion.let { Quaternion(it[0], it[1], it[2], it[3]) })