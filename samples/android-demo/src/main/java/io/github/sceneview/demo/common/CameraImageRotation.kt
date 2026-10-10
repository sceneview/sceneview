package io.github.sceneview.demo.common

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
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

/** How a camera sensor is mounted in the device: what the rotation formula needs from it. */
internal data class CameraMount(val sensorOrientationDegrees: Int, val isFrontFacing: Boolean)

/** The mount of nearly every phone's rear camera, used when a camera cannot be described. */
internal val DEFAULT_CAMERA_MOUNT =
    CameraMount(sensorOrientationDegrees = 90, isFrontFacing = false)

/**
 * Remembers each camera's [CameraMount], which never changes while the app runs.
 *
 * Reading it is a `CameraManager.getCameraCharacteristics` call, which throws for an id the
 * framework does not know (a logical or vendor camera ARCore opened itself) and whenever the
 * camera service is unavailable. Asked once per detector pass, a failing camera threw several
 * times per second for the whole session, and each throw cost the pass its detection. Here the
 * answer — the real mount, or [DEFAULT_CAMERA_MOUNT] after a failure — is kept per camera id, so
 * a camera is read once and a failure is reported once through [onUnknown].
 *
 * Not thread-safe: call from the thread that drives the AR session.
 */
internal class CameraMountCache(
    private val read: (cameraId: String) -> CameraMount,
    private val onUnknown: (cameraId: String, error: Throwable) -> Unit = { _, _ -> },
) {
    private val mounts = HashMap<String, CameraMount>()

    fun mountOf(cameraId: String): CameraMount = mounts[cameraId]
        ?: runCatching { read(cameraId) }
            .getOrElse { error ->
                onUnknown(cameraId, error)
                DEFAULT_CAMERA_MOUNT
            }
            .also { mounts[cameraId] = it }
}

/**
 * Degrees of clockwise rotation to apply to the active ARCore CPU camera image so it appears
 * upright on [context]'s current display.
 *
 * Remember one instance per screen: the camera mount is read once per camera id (see
 * [CameraMountCache]) and only the display rotation is read on each call.
 *
 * `Context.display` was added in API 30; falls back to the deprecated
 * `WindowManager.defaultDisplay` on API 28–29.
 */
internal class CameraImageRotation(private val context: Context) {

    private val mounts = CameraMountCache(
        read = { cameraId ->
            val characteristics = context.getSystemService(CameraManager::class.java)
                .getCameraCharacteristics(cameraId)
            CameraMount(
                sensorOrientationDegrees =
                    characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                        ?: DEFAULT_CAMERA_MOUNT.sensorOrientationDegrees,
                isFrontFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT,
            )
        },
        onUnknown = { cameraId, error ->
            Log.w(
                TAG,
                "No characteristics for camera \"$cameraId\"; assuming a rear sensor mounted " +
                    "at ${DEFAULT_CAMERA_MOUNT.sensorOrientationDegrees} degrees",
                error,
            )
        },
    )

    /** The rotation for the camera [session] is running, on the current display rotation. */
    fun degrees(session: Session): Int {
        val displayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display.rotation
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.rotation
        }
        val mount = mounts.mountOf(session.cameraConfig.cameraId)
        return cameraImageRotationDegrees(
            sensorOrientationDegrees = mount.sensorOrientationDegrees,
            displayRotation = displayRotation,
            isFrontFacing = mount.isFrontFacing,
        )
    }

    private companion object {
        const val TAG = "CameraImageRotation"
    }
}
