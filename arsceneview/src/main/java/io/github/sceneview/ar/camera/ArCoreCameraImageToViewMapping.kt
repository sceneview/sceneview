package io.github.sceneview.ar.camera

import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame

/**
 * Snapshots this current ARCore frame's `IMAGE_PIXELS -> VIEW` transform for an asynchronous
 * camera-image pipeline.
 *
 * Call this during `onSessionUpdated`, next to CPU-image acquisition, and retain the returned
 * immutable value with the detector request. [Frame.transformCoordinates2d] depends on the
 * frame's current display geometry and must not be deferred until the result callback.
 */
fun Frame.cameraImageToViewMapping(
    imageSize: CameraImageSize,
    viewSize: CameraImageSize,
    inputRotationDegrees: Int,
): CameraImageToViewMapping {
    val raw = floatArrayOf(
        0f, 0f,
        imageSize.width.toFloat(), 0f,
        0f, imageSize.height.toFloat(),
    )
    val mapped = FloatArray(raw.size)
    transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, raw, Coordinates2d.VIEW, mapped)
    val originX = mapped[0]
    val originY = mapped[1]
    return CameraImageToViewMapping(
        imageSize = imageSize,
        viewSize = viewSize,
        inputRotationDegrees = inputRotationDegrees,
        imagePixelsToView = CameraImageToViewTransform(
            m00 = (mapped[2] - originX) / imageSize.width,
            m01 = (mapped[4] - originX) / imageSize.height,
            m02 = originX,
            m10 = (mapped[3] - originY) / imageSize.width,
            m11 = (mapped[5] - originY) / imageSize.height,
            m12 = originY,
        ),
    )
}
