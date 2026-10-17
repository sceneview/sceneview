package io.github.sceneview.demo.demos.internal

import io.github.sceneview.ar.camera.CameraImageToViewMapping
import kotlin.math.sqrt

/** World pose of the physical camera when a detector input image was acquired. */
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
 * Pinhole intrinsics of the CPU camera image (ARCore `Camera.getImageIntrinsics()`): focal
 * length and principal point in pixels of an image [width] x [height].
 */
internal data class DetectionIntrinsics(
    val focalX: Float,
    val focalY: Float,
    val principalX: Float,
    val principalY: Float,
    val width: Int,
    val height: Int,
)

/** A world-space ray, in the two `FloatArray(3)` ARCore's ray `hitTest` takes. */
internal class WorldRay(val origin: FloatArray, val direction: FloatArray)

/**
 * Everything needed, later, to cast a ray through a pixel of a detector input image: the camera
 * pose and the intrinsics of the frame the image was acquired from.
 *
 * A detector answers one or several frames after its image was taken, and the phone has moved
 * in between. Mapping the detection to a *view* pixel and hit-testing that pixel in the current
 * frame casts the ray from where the camera is now, not from where it saw the object: the hit
 * lands beside it, by an amount that grows with the motion. Rejecting results taken "too far"
 * from the current pose only bounded that error. The ray built here starts at the acquisition
 * pose and goes through the detected pixel, in world space; the world has not moved, so it can
 * be hit-tested against any later frame.
 *
 * Conventions (ARCore): the camera pose follows the physical camera, not the display, with
 * OpenGL axes — +X along the image's u axis, +Y up (against the image's v axis), -Z forward.
 */
internal class DetectionCapture(
    val pose: DetectionCameraPose,
    val intrinsics: DetectionIntrinsics,
    val mapping: CameraImageToViewMapping,
) {
    /**
     * The world ray through a detector-output pixel ([detectorX], [detectorY]), i.e. a pixel of
     * the image as the detector saw it, after its input rotation.
     */
    fun worldRay(detectorX: Float, detectorY: Float): WorldRay {
        val pixel = mapping.detectorPixelToImagePixel(detectorX, detectorY)
        // The intrinsics describe the CPU image; rescale in case they are reported for another
        // resolution of the same sensor crop.
        val u = pixel.x * intrinsics.width / mapping.imageSize.width
        val v = pixel.y * intrinsics.height / mapping.imageSize.height
        val cameraX = (u - intrinsics.principalX) / intrinsics.focalX
        val cameraY = (intrinsics.principalY - v) / intrinsics.focalY
        val direction = rotate(cameraX, cameraY, -1f)
        val length = sqrt(
            direction[0] * direction[0] + direction[1] * direction[1] + direction[2] * direction[2]
        )
        for (i in direction.indices) direction[i] /= length
        return WorldRay(
            origin = floatArrayOf(pose.tx, pose.ty, pose.tz),
            direction = direction,
        )
    }

    /** Rotates a camera-space vector into world space: `v + 2 q × (q × v + w v)`. */
    private fun rotate(x: Float, y: Float, z: Float): FloatArray {
        val cx = pose.qy * z - pose.qz * y + pose.qw * x
        val cy = pose.qz * x - pose.qx * z + pose.qw * y
        val cz = pose.qx * y - pose.qy * x + pose.qw * z
        return floatArrayOf(
            x + 2f * (pose.qy * cz - pose.qz * cy),
            y + 2f * (pose.qz * cx - pose.qx * cz),
            z + 2f * (pose.qx * cy - pose.qy * cx),
        )
    }
}
