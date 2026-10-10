package io.github.sceneview.ar.camera

/** A two-dimensional point expressed in pixels. */
data class CameraImagePoint(val x: Float, val y: Float)

/** Positive pixel dimensions of an image or view. */
data class CameraImageSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "width and height must be positive" }
    }
}

/**
 * An affine transform from ARCore `IMAGE_PIXELS` to Android `VIEW` pixels.
 *
 * The coefficients follow `x' = m00*x + m01*y + m02` and
 * `y' = m10*x + m11*y + m12`. Keeping this value independent of ARCore makes a captured
 * camera geometry safe to retain while an asynchronous vision request is in flight.
 */
data class CameraImageToViewTransform(
    val m00: Float,
    val m01: Float,
    val m02: Float,
    val m10: Float,
    val m11: Float,
    val m12: Float,
) {
    /** Maps one raw camera-image pixel into view pixels. */
    fun map(x: Float, y: Float): CameraImagePoint = CameraImagePoint(
        x = m00 * x + m01 * y + m02,
        y = m10 * x + m11 * y + m12,
    )
}

/**
 * The affine transform that sends three corners of the raw camera image onto three view points.
 *
 * `IMAGE_PIXELS -> VIEW` is a rotation by a quarter turn, a uniform scale, a crop offset and,
 * for a front camera, a mirror: an affine map, so three non-aligned points define it entirely.
 * ARCore is asked where the corners `(0, 0)`, `(width, 0)` and `(0, height)` land and this
 * solves the six coefficients from the answers. Split from the ARCore call so every rotation,
 * the mirror and the crop are JVM tests.
 *
 * @param imageSize dimensions of the unrotated CPU image.
 * @param origin view position of image pixel `(0, 0)`.
 * @param xAxisEnd view position of image pixel `(width, 0)`.
 * @param yAxisEnd view position of image pixel `(0, height)`.
 */
internal fun cameraImageToViewTransform(
    imageSize: CameraImageSize,
    origin: CameraImagePoint,
    xAxisEnd: CameraImagePoint,
    yAxisEnd: CameraImagePoint,
): CameraImageToViewTransform = CameraImageToViewTransform(
    m00 = (xAxisEnd.x - origin.x) / imageSize.width,
    m01 = (yAxisEnd.x - origin.x) / imageSize.height,
    m02 = origin.x,
    m10 = (xAxisEnd.y - origin.y) / imageSize.width,
    m11 = (yAxisEnd.y - origin.y) / imageSize.height,
    m12 = origin.y,
)

/**
 * Immutable mapping from a detector's rotated output coordinates to view pixels.
 *
 * Vision APIs accept a clockwise input rotation; APIs such as ML Kit report points in that
 * rotated image, while others retain the raw input coordinates. ARCore's
 * `Frame.transformCoordinates2d`, however, maps the unrotated CPU image. [mapPixel] first undoes
 * [inputRotationDegrees], then applies the captured
 * [imagePixelsToView] transform, preserving ARCore's display crop and rotation exactly.
 *
 * Capture this mapping while the ARCore frame used to acquire the CPU image is current by calling
 * `Frame.cameraImageToViewMapping`. Retaining a frame and asking it to transform coordinates
 * after an asynchronous detector completes is invalid. For pipelines without an ARCore frame,
 * [centerCrop] creates the equivalent pure-Kotlin rotation plus centered-crop mapping.
 *
 * @param imageSize dimensions of the unrotated CPU image.
 * @param inputRotationDegrees clockwise rotation passed to the vision API: 0, 90, 180, or 270.
 * @param imagePixelsToView captured raw `IMAGE_PIXELS` to `VIEW` affine transform.
 */
class CameraImageToViewMapping(
    val imageSize: CameraImageSize,
    val inputRotationDegrees: Int,
    val imagePixelsToView: CameraImageToViewTransform,
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
     * pixel comes from. This is the pixel the camera intrinsics describe, so it is also the
     * entry point for casting a world ray through a detection.
     */
    fun detectorPixelToImagePixel(x: Float, y: Float): CameraImagePoint =
        when (inputRotationDegrees) {
            0 -> CameraImagePoint(x, y)
            90 -> CameraImagePoint(y, imageSize.height - x)
            180 -> CameraImagePoint(imageSize.width - x, imageSize.height - y)
            else -> CameraImagePoint(imageSize.width - y, x) // 270° clockwise
        }

    /** Maps a detector-output pixel to Android view pixels. */
    fun mapPixel(x: Float, y: Float): CameraImagePoint {
        val raw = detectorPixelToImagePixel(x, y)
        return imagePixelsToView.map(raw.x, raw.y)
    }

    /** Maps a detector-output point normalized to `[0, 1]` into Android view pixels. */
    fun mapNormalized(x: Float, y: Float): CameraImagePoint = mapPixel(
        x = x * detectorWidth,
        y = y * detectorHeight,
    )

    /** Maps a point in the unrotated CPU image directly into Android view pixels. */
    fun mapImagePixel(x: Float, y: Float): CameraImagePoint = imagePixelsToView.map(x, y)

    /** Maps an unrotated CPU-image point normalized to `[0, 1]` into Android view pixels. */
    fun mapImageNormalized(x: Float, y: Float): CameraImagePoint = mapImagePixel(
        x = x * imageSize.width,
        y = y * imageSize.height,
    )

    companion object {
        private val VALID_ROTATIONS = setOf(0, 90, 180, 270)

        /**
         * Builds a rotation-aware centered-crop mapping without ARCore.
         *
         * The rotated image is uniformly scaled until it fills [viewSize], then equally cropped
         * on the overflowing axis. This matches a camera preview rendered with center-crop.
         */
        fun centerCrop(
            imageSize: CameraImageSize,
            viewSize: CameraImageSize,
            inputRotationDegrees: Int,
        ): CameraImageToViewMapping {
            require(inputRotationDegrees in VALID_ROTATIONS) {
                "inputRotationDegrees must be 0, 90, 180, or 270"
            }
            val detectorWidth = if (inputRotationDegrees % 180 == 0) {
                imageSize.width.toFloat()
            } else {
                imageSize.height.toFloat()
            }
            val detectorHeight = if (inputRotationDegrees % 180 == 0) {
                imageSize.height.toFloat()
            } else {
                imageSize.width.toFloat()
            }
            val scale = maxOf(viewSize.width / detectorWidth, viewSize.height / detectorHeight)
            val cropX = (viewSize.width - detectorWidth * scale) / 2f
            val cropY = (viewSize.height - detectorHeight * scale) / 2f

            // Compose raw-image -> clockwise-rotated detector pixels -> centered view crop.
            val transform = when (inputRotationDegrees) {
                0 -> CameraImageToViewTransform(scale, 0f, cropX, 0f, scale, cropY)
                90 -> CameraImageToViewTransform(
                    0f, -scale, imageSize.height * scale + cropX,
                    scale, 0f, cropY,
                )
                180 -> CameraImageToViewTransform(
                    -scale, 0f, imageSize.width * scale + cropX,
                    0f, -scale, imageSize.height * scale + cropY,
                )
                else -> CameraImageToViewTransform(
                    0f, scale, cropX,
                    -scale, 0f, imageSize.width * scale + cropY,
                )
            }
            return CameraImageToViewMapping(
                imageSize = imageSize,
                inputRotationDegrees = inputRotationDegrees,
                imagePixelsToView = transform,
            )
        }
    }
}
