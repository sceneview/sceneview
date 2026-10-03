package io.github.sceneview.demo.common

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import com.google.ar.core.Session

/**
 * Maps the active camera sensor orientation and a `Surface.ROTATION_*` value to the clockwise
 * correction required by an image-processing pipeline.
 *
 * Shared by every demo that feeds [io.github.sceneview.ar.arcore.cameraImage] into an
 * off-device vision pipeline — ML Kit's `InputImage.fromMediaImage(image, rotationDegrees)` and
 * MediaPipe's `ImageProcessingOptions.setRotationDegrees(rotationDegrees)` both use this exact
 * convention (clockwise degrees to apply to the buffer to make it upright).
 *
 * Kept as a pure function so 90° and 270° camera mounts can be covered on the JVM. ARCore's
 * phone camera is rear-facing, but the front-facing formula is included for completeness.
 */
internal fun cameraImageRotationDegrees(
    sensorOrientationDegrees: Int,
    displayRotation: Int,
    isFrontFacing: Boolean = false,
): Int {
    val displayDegrees = when (displayRotation) {
        Surface.ROTATION_0 -> 0
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
    return if (isFrontFacing) {
        Math.floorMod(sensorOrientationDegrees + displayDegrees, 360)
    } else {
        Math.floorMod(sensorOrientationDegrees - displayDegrees, 360)
    }
}

/**
 * Degrees of clockwise rotation to apply to the active ARCore CPU camera image so it appears
 * upright on [context]'s current display.
 *
 * `Context.display` was added in API 30; falls back to the deprecated
 * `WindowManager.defaultDisplay` on API 28–29.
 */
internal fun cameraImageRotationDegrees(context: Context, session: Session): Int {
    val displayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display.rotation
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }
    val cameraManager = context.getSystemService(CameraManager::class.java)
    val characteristics = cameraManager.getCameraCharacteristics(session.cameraConfig.cameraId)
    val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    val isFrontFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ==
        CameraCharacteristics.LENS_FACING_FRONT
    return cameraImageRotationDegrees(sensorOrientation, displayRotation, isFrontFacing)
}
