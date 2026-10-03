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
 *
 * `VIEW` is the view ARCore was last given through `Session.setDisplayGeometry`, which
 * `ARSceneView` keeps in sync with its own size: there is no view size to pass.
 *
 * @param imageSize dimensions of the CPU image the detector is fed, unrotated.
 * @param inputRotationDegrees clockwise rotation passed to the vision API: 0, 90, 180, or 270.
 */
fun Frame.cameraImageToViewMapping(
    imageSize: CameraImageSize,
    inputRotationDegrees: Int,
): CameraImageToViewMapping {
    val raw = floatArrayOf(
        0f, 0f,
        imageSize.width.toFloat(), 0f,
        0f, imageSize.height.toFloat(),
    )
    val mapped = FloatArray(raw.size)
    transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, raw, Coordinates2d.VIEW, mapped)
    return CameraImageToViewMapping(
        imageSize = imageSize,
        inputRotationDegrees = inputRotationDegrees,
        imagePixelsToView = cameraImageToViewTransform(
            imageSize = imageSize,
            origin = CameraImagePoint(mapped[0], mapped[1]),
            xAxisEnd = CameraImagePoint(mapped[2], mapped[3]),
            yAxisEnd = CameraImagePoint(mapped[4], mapped[5]),
        ),
    )
}
