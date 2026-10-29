package io.github.sceneview.ar.camera

/** Positive pixel dimensions of a camera CPU image, as acquired: never a view or screen size. */
class CameraImageSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "width and height must be positive" }
    }

    override fun equals(other: Any?): Boolean =
        other is CameraImageSize && other.width == width && other.height == height

    override fun hashCode(): Int = 31 * width + height

    override fun toString(): String = "CameraImageSize(${width}x$height)"
}

/** A point of a camera CPU image, in pixels of that image. */
class CameraImagePoint(val x: Float, val y: Float) {
    override fun equals(other: Any?): Boolean =
        other is CameraImagePoint && other.x == x && other.y == y

    override fun hashCode(): Int = 31 * x.hashCode() + y.hashCode()

    override fun toString(): String = "CameraImagePoint($x, $y)"
}

/**
 * Maps a detector's output pixels back to the camera CPU image it was fed.
 *
 * Vision APIs take a clockwise input rotation, and some (ML Kit) report their results in the
 * rotated image. ARCore's camera intrinsics (`Camera.getImageIntrinsics()`) describe the
 * unrotated CPU image. [detectorPixelToImagePixel] undoes the rotation, which gives the pixel to
 * cast a world ray through with those intrinsics: a ray built from the camera pose and intrinsics
 * captured with the image can be hit-tested against any later frame (`Frame.hitTest(origin,
 * originOffset, direction, directionOffset)`), however far the phone moved during detection.
 *
 * Pure Kotlin, no ARCore state: safe to keep while an asynchronous detector runs.
 *
 * @param imageSize dimensions of the unrotated CPU image the detector is fed.
 * @param inputRotationDegrees clockwise rotation passed to the vision API: 0, 90, 180, or 270.
 */
class DetectorImageMapping(
    val imageSize: CameraImageSize,
    val inputRotationDegrees: Int,
) {
    init {
        require(inputRotationDegrees in VALID_ROTATIONS) {
            "inputRotationDegrees must be 0, 90, 180, or 270"
        }
    }

    /** Width of the detector's rotated output image. */
    val detectorWidth: Int
        get() = if (inputRotationDegrees % 180 == 0) imageSize.width else imageSize.height

    /** Height of the detector's rotated output image. */
    val detectorHeight: Int
        get() = if (inputRotationDegrees % 180 == 0) imageSize.height else imageSize.width

    /**
     * Undoes [inputRotationDegrees]: the pixel of the unrotated CPU image a detector-output
     * pixel comes from. This is the pixel the camera intrinsics describe.
     */
    fun detectorPixelToImagePixel(x: Float, y: Float): CameraImagePoint =
        when (inputRotationDegrees) {
            0 -> CameraImagePoint(x, y)
            90 -> CameraImagePoint(y, imageSize.height - x)
            180 -> CameraImagePoint(imageSize.width - x, imageSize.height - y)
            else -> CameraImagePoint(imageSize.width - y, x) // 270° clockwise
        }

    private companion object {
        val VALID_ROTATIONS = setOf(0, 90, 180, 270)
    }
}
