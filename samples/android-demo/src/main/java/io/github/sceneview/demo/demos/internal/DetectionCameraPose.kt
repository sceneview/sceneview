package io.github.sceneview.demo.demos.internal

import io.github.sceneview.ar.camera.CameraImageToViewMapping
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** Immutable camera pose captured when a detector input image was acquired. */
internal data class DetectionCameraPose(
    val tx: Float,
    val ty: Float,
    val tz: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float,
)

/**
 * Rejects an asynchronous detection when its camera pose, age, or display geometry is stale.
 */
internal fun isDetectionCaptureCompatible(
    capturedPose: DetectionCameraPose,
    currentPose: DetectionCameraPose,
    capturedTimestampNanos: Long,
    currentTimestampNanos: Long,
    capturedMapping: CameraImageToViewMapping,
    currentMapping: CameraImageToViewMapping,
    maxTranslationMeters: Float = 0.05f,
    maxRotationDegrees: Float = 5f,
    maxAgeNanos: Long = 500_000_000L,
): Boolean {
    val dx = currentPose.tx - capturedPose.tx
    val dy = currentPose.ty - capturedPose.ty
    val dz = currentPose.tz - capturedPose.tz
    if (sqrt(dx * dx + dy * dy + dz * dz) > maxTranslationMeters) return false

    val dot = abs(
        capturedPose.qx * currentPose.qx +
            capturedPose.qy * currentPose.qy +
            capturedPose.qz * currentPose.qz +
            capturedPose.qw * currentPose.qw
    ).coerceIn(0f, 1f)
    val angleDegrees = (2.0 * acos(dot.toDouble()) * 180.0 / PI).toFloat()
    if (angleDegrees > maxRotationDegrees) return false

    val age = currentTimestampNanos - capturedTimestampNanos
    if (age < 0L || age > maxAgeNanos) return false
    return capturedMapping.hasSameGeometryAs(currentMapping)
}

private fun CameraImageToViewMapping.hasSameGeometryAs(
    other: CameraImageToViewMapping,
): Boolean {
    if (imageSize != other.imageSize || viewSize != other.viewSize ||
        inputRotationDegrees != other.inputRotationDegrees
    ) return false
    val a = imagePixelsToView
    val b = other.imagePixelsToView
    return abs(a.m00 - b.m00) <= GEOMETRY_SCALE_EPSILON &&
        abs(a.m01 - b.m01) <= GEOMETRY_SCALE_EPSILON &&
        abs(a.m02 - b.m02) <= GEOMETRY_TRANSLATION_EPSILON_PX &&
        abs(a.m10 - b.m10) <= GEOMETRY_SCALE_EPSILON &&
        abs(a.m11 - b.m11) <= GEOMETRY_SCALE_EPSILON &&
        abs(a.m12 - b.m12) <= GEOMETRY_TRANSLATION_EPSILON_PX
}

private const val GEOMETRY_SCALE_EPSILON = 1e-3f
private const val GEOMETRY_TRANSLATION_EPSILON_PX = 0.5f
