package io.github.sceneview.ar.camera

/** A point in Android view pixels. */
internal data class ViewPoint(val x: Float, val y: Float)

/** Positive pixel dimensions of an Android view. */
internal data class ViewSize(val width: Int, val height: Int) {
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
 *
 * Internal until a caller draws in view space (#4314 review): label placement goes through
 * world rays ([DetectorImageMapping]), not through the screen.
 */
internal data class CameraImageToViewTransform(
    val m00: Float,
    val m01: Float,
    val m02: Float,
    val m10: Float,
    val m11: Float,
    val m12: Float,
) {
    /** Maps one raw camera-image pixel into view pixels. */
    fun map(x: Float, y: Float): ViewPoint = ViewPoint(
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
    origin: ViewPoint,
    xAxisEnd: ViewPoint,
    yAxisEnd: ViewPoint,
): CameraImageToViewTransform = CameraImageToViewTransform(
    m00 = (xAxisEnd.x - origin.x) / imageSize.width,
    m01 = (yAxisEnd.x - origin.x) / imageSize.height,
    m02 = origin.x,
    m10 = (xAxisEnd.y - origin.y) / imageSize.width,
    m11 = (yAxisEnd.y - origin.y) / imageSize.height,
    m12 = origin.y,
)

/**
 * Immutable mapping from a detector's rotated output coordinates to view pixels: [detector]
 * undoes the input rotation, then the captured [imagePixelsToView] transform applies ARCore's
 * display crop and rotation.
 *
 * Internal until a caller draws in view space (#4314 review).
 */
internal class CameraImageToViewMapping(
    val detector: DetectorImageMapping,
    val imagePixelsToView: CameraImageToViewTransform,
) {
    /** Maps a detector-output pixel to Android view pixels. */
    fun mapPixel(x: Float, y: Float): ViewPoint {
        val raw = detector.detectorPixelToImagePixel(x, y)
        return imagePixelsToView.map(raw.x, raw.y)
    }

    /** Maps a detector-output point normalized to `[0, 1]` into Android view pixels. */
    fun mapNormalized(x: Float, y: Float): ViewPoint = mapPixel(
        x = x * detector.detectorWidth,
        y = y * detector.detectorHeight,
    )

    /** Maps a point in the unrotated CPU image directly into Android view pixels. */
    fun mapImagePixel(x: Float, y: Float): ViewPoint = imagePixelsToView.map(x, y)

    /** Maps an unrotated CPU-image point normalized to `[0, 1]` into Android view pixels. */
    fun mapImageNormalized(x: Float, y: Float): ViewPoint = mapImagePixel(
        x = x * detector.imageSize.width,
        y = y * detector.imageSize.height,
    )

    companion object {
        /**
         * Builds a rotation-aware centered-crop mapping without ARCore.
         *
         * The rotated image is uniformly scaled until it fills [viewSize], then equally cropped
         * on the overflowing axis. This matches a camera preview rendered with center-crop.
         */
        fun centerCrop(
            imageSize: CameraImageSize,
            viewSize: ViewSize,
            inputRotationDegrees: Int,
        ): CameraImageToViewMapping {
            val detector = DetectorImageMapping(imageSize, inputRotationDegrees)
            val detectorWidth = detector.detectorWidth.toFloat()
            val detectorHeight = detector.detectorHeight.toFloat()
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
            return CameraImageToViewMapping(detector = detector, imagePixelsToView = transform)
        }
    }
}
