package io.github.sceneview.demo.common

import android.content.Context
import android.os.Build
import android.view.Surface
import android.view.WindowManager

/**
 * Maps a `Surface.ROTATION_*` display-rotation constant to the number of degrees ARCore's CPU
 * camera image (delivered in the device's natural sensor orientation) must be rotated to appear
 * upright on the current display.
 *
 * Shared by every demo that feeds [io.github.sceneview.ar.arcore.cameraImage] into an
 * off-device vision pipeline — ML Kit's `InputImage.fromMediaImage(image, rotationDegrees)` and
 * MediaPipe's `ImageProcessingOptions.setRotationDegrees(rotationDegrees)` both use this exact
 * convention (clockwise degrees to apply to the buffer to make it upright).
 *
 * ARCore's back-camera sensor orientation on Android phones is 90°, so a
 * `Surface.ROTATION_0` (portrait, the common case) needs 90° of correction. Kept as a pure
 * function — separate from [displayRotationDegrees] — so it is unit-testable without a real
 * [android.view.Display].
 */
internal fun rotationDegreesForDisplayRotation(displayRotation: Int): Int = when (displayRotation) {
    Surface.ROTATION_0 -> 90
    Surface.ROTATION_90 -> 0
    Surface.ROTATION_180 -> 270
    Surface.ROTATION_270 -> 180
    else -> 90
}

/**
 * Degrees of clockwise rotation to apply to the ARCore CPU camera image so it appears upright
 * on [context]'s current display. See [rotationDegreesForDisplayRotation].
 *
 * `Context.display` was added in API 30; falls back to the deprecated
 * `WindowManager.defaultDisplay` on API 28–29.
 */
internal fun displayRotationDegrees(context: Context): Int {
    val displayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display.rotation
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }
    return rotationDegreesForDisplayRotation(displayRotation)
}
