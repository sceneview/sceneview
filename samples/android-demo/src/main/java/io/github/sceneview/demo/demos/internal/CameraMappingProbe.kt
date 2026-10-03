package io.github.sceneview.demo.demos.internal

import android.util.Log
import io.github.sceneview.ar.camera.CameraImageToViewMapping
import java.util.Locale

/**
 * One line that says whether a captured camera-image mapping is centred: where the centre of
 * the CPU image lands in the view, next to the centre of the view. ARCore draws the camera
 * image centred in the view, so the two must agree to within a pixel in every orientation; a
 * gap means the mapping was captured for another view than the one measured.
 */
internal fun cameraMappingReport(
    mapping: CameraImageToViewMapping,
    viewWidth: Int,
    viewHeight: Int,
): String {
    val image = mapping.imageSize
    val centre = mapping.mapImagePixel(image.width / 2f, image.height / 2f)
    return String.format(
        Locale.US,
        "mapImagePixel(w/2, h/2) = (%.1f, %.1f), viewSize/2 = (%.1f, %.1f) " +
            "[image %dx%d, view %dx%d, input rotation %d]",
        centre.x,
        centre.y,
        viewWidth / 2f,
        viewHeight / 2f,
        image.width,
        image.height,
        viewWidth,
        viewHeight,
        mapping.inputRotationDegrees,
    )
}

/**
 * Logs [cameraMappingReport] once per geometry (image size, view size, rotation), so a device
 * run shows one line per orientation instead of one per detector pass.
 */
internal class CameraMappingProbe(
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private var lastGeometry: List<Int>? = null

    fun report(mapping: CameraImageToViewMapping, viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val geometry = listOf(
            mapping.imageSize.width,
            mapping.imageSize.height,
            viewWidth,
            viewHeight,
            mapping.inputRotationDegrees,
        )
        if (geometry == lastGeometry) return
        lastGeometry = geometry
        log(cameraMappingReport(mapping, viewWidth, viewHeight))
    }

    private companion object {
        const val TAG = "CameraImageMapping"
    }
}
