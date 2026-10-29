package io.github.sceneview.demo.demos.internal

import android.util.Log
import io.github.sceneview.ar.camera.CameraImageSize
import java.util.Locale

/**
 * Where ARCore draws the centre of the CPU image in the view (`Frame.transformCoordinates2d`,
 * `IMAGE_PIXELS -> VIEW`), for the image a detector is fed with [inputRotationDegrees].
 */
internal data class CameraImageCentreSample(
    val imageSize: CameraImageSize,
    val inputRotationDegrees: Int,
    val centreInViewX: Float,
    val centreInViewY: Float,
)

/**
 * One line that says whether ARCore draws the camera image centred: where the centre of the CPU
 * image lands in the view, next to the centre of the view measured by Compose. The two must
 * agree to within a pixel in every orientation; a gap means ARCore's display geometry is not
 * the view the demo is laid out in. Diagnostic only: labels are placed by world rays, not
 * through the screen.
 */
internal fun cameraMappingReport(
    sample: CameraImageCentreSample,
    viewWidth: Int,
    viewHeight: Int,
): String = String.format(
    Locale.US,
    "image centre in view = (%.1f, %.1f), viewSize/2 = (%.1f, %.1f) " +
        "[image %dx%d, view %dx%d, input rotation %d]",
    sample.centreInViewX,
    sample.centreInViewY,
    viewWidth / 2f,
    viewHeight / 2f,
    sample.imageSize.width,
    sample.imageSize.height,
    viewWidth,
    viewHeight,
    sample.inputRotationDegrees,
)

/**
 * Logs [cameraMappingReport] once per geometry (image size, view size, rotation), so a device
 * run shows one line per orientation instead of one per detector pass.
 */
internal class CameraMappingProbe(
    private val log: (String) -> Unit = { Log.i(CAMERA_IMAGE_MAPPING_TAG, it) },
) {
    private var lastGeometry: List<Int>? = null

    fun report(sample: CameraImageCentreSample, viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val geometry = listOf(
            sample.imageSize.width,
            sample.imageSize.height,
            viewWidth,
            viewHeight,
            sample.inputRotationDegrees,
        )
        if (geometry == lastGeometry) return
        lastGeometry = geometry
        log(cameraMappingReport(sample, viewWidth, viewHeight))
    }
}

/** Logcat tag of the camera-image device checks: `adb logcat -s CameraImageMapping`. */
internal const val CAMERA_IMAGE_MAPPING_TAG = "CameraImageMapping"
