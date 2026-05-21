package io.github.sceneview.ar.arcore

import com.google.ar.core.Camera
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Float2
import io.github.sceneview.math.Transform
import io.github.sceneview.math.toTransform

/**
 * Whether the AR camera is currently in [TrackingState.TRACKING].
 *
 * When `true`, the camera pose is reliable and AR content can be rendered.
 * When `false`, the screen may be kept unlocked but content should be hidden or frozen.
 */
val Camera.isTracking get() = trackingState == TrackingState.TRACKING

/**
 * Returns the camera's projection matrix as a [Transform] for the given near/far clip planes.
 *
 * @param near Near clip plane distance in meters.
 * @param far  Far clip plane distance in meters.
 */
fun Camera.getProjectionTransform(near: Float, far: Float) = FloatArray(16).apply {
    getProjectionMatrix(this, 0, near, far)
}.toTransform()

/**
 * The camera's view matrix as a [Transform].
 *
 * Converts the ARCore camera's 4x4 view matrix into a SceneView [Transform].
 */
val Camera.viewTransform: Transform
    get() = FloatArray(16).apply { getViewMatrix(this, 0) }.toTransform()

/**
 * Camera pose for use in **rendering**, accounting for current display rotation.
 *
 * Differs from [Camera.getPose]: `getPose()` is the physical sensor pose in OpenGL world
 * coordinates (Y up, -Z forward), whereas `displayOrientedPose` rotates that pose to match
 * what the user actually sees on the display — taking landscape/portrait orientation
 * changes and reverse-portrait into account.
 *
 * Use [displayOrientedPose] (not [Camera.getPose]) when:
 * - placing UI/billboard nodes that should face the user regardless of device orientation
 * - building a screen-space ray for hit-testing from a tap location
 *
 * Use [Camera.getPose] (the sensor pose) for raw IMU/sensor calculations.
 *
 * @see com.google.ar.core.Camera.getDisplayOrientedPose
 */
val Camera.displayOrientedPose: Pose get() = getDisplayOrientedPose()

/**
 * SceneView-friendly snapshot of [com.google.ar.core.CameraIntrinsics] — focal length,
 * principal point and image dimensions for either the GPU texture stream or the CPU image
 * stream.
 *
 * Used by ML pipelines that need to project 2D pixel detections (e.g. MediaPipe Pose, MLKit
 * Face Detection on the CPU image) back into 3D world coordinates via the pinhole model:
 *
 * ```kotlin
 * val k = camera.intrinsics(useTexture = false) // CPU image stream
 * val xWorld = (pxX - k.principalPoint.x) * z / k.focalLength.x
 * ```
 *
 * @property focalLength    focal length in pixels (`fx`, `fy`)
 * @property principalPoint principal point in pixels (`cx`, `cy`)
 * @property imageWidth     stream width in pixels
 * @property imageHeight    stream height in pixels
 */
data class CameraIntrinsicsSnapshot(
    val focalLength: Float2,
    val principalPoint: Float2,
    val imageWidth: Int,
    val imageHeight: Int
)

/**
 * Returns a [CameraIntrinsicsSnapshot] for either the GPU texture stream (default) or the CPU
 * image stream.
 *
 * - `useTexture = true` (default) → [Camera.getTextureIntrinsics] — matches the EGL texture
 *   you'd sample in a custom background shader. Use this for AR overlays.
 * - `useTexture = false`          → [Camera.getImageIntrinsics] — matches the CPU image
 *   delivered by [com.google.ar.core.Frame.acquireCameraImage]. Use this for ML pipelines
 *   running on CPU image bytes.
 *
 * Note: the underlying [CameraIntrinsics] handles are pinned to this [Camera] instance —
 * the snapshot copies the values into POD types so callers can store them across frames.
 *
 * @see com.google.ar.core.Camera.getTextureIntrinsics
 * @see com.google.ar.core.Camera.getImageIntrinsics
 */
fun Camera.intrinsics(useTexture: Boolean = true): CameraIntrinsicsSnapshot {
    val intrinsics: CameraIntrinsics =
        if (useTexture) textureIntrinsics else imageIntrinsics
    return cameraIntrinsicsSnapshot(
        focalLength = intrinsics.focalLength,
        principalPoint = intrinsics.principalPoint,
        imageDimensions = intrinsics.imageDimensions
    )
}

/**
 * Pure conversion from ARCore's raw float/int arrays to [CameraIntrinsicsSnapshot].
 * Extracted so JVM unit tests can pin the layout without instantiating ARCore's JNI
 * [CameraIntrinsics] type.
 */
internal fun cameraIntrinsicsSnapshot(
    focalLength: FloatArray,
    principalPoint: FloatArray,
    imageDimensions: IntArray
): CameraIntrinsicsSnapshot = CameraIntrinsicsSnapshot(
    focalLength = Float2(focalLength[0], focalLength[1]),
    principalPoint = Float2(principalPoint[0], principalPoint[1]),
    imageWidth = imageDimensions[0],
    imageHeight = imageDimensions[1]
)