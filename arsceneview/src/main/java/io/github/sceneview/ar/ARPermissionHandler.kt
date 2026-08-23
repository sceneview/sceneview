package io.github.sceneview.ar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk

/**
 * Abstracts camera permission and ARCore availability checks away from [ComponentActivity].
 *
 * By depending on this interface rather than a concrete activity, [ARCore] becomes testable
 * with a simple mock implementation that returns predetermined permission and availability
 * values.
 *
 * @see ActivityARPermissionHandler for the production implementation backed by an activity.
 */
interface ARPermissionHandler {
    /** Returns `true` when the `CAMERA` permission is already granted. */
    fun hasCameraPermission(): Boolean

    /**
     * Requests the `CAMERA` permission from the user.
     *
     * Implementations should launch the system permission dialog and invoke [onResult]
     * with `true` if the permission was granted, `false` otherwise.
     */
    fun requestCameraPermission(onResult: (granted: Boolean) -> Unit)

    /**
     * Returns `true` when the user has permanently denied the camera permission
     * (i.e. "Don't ask again" was checked).
     */
    fun shouldShowPermissionRationale(): Boolean

    /** Opens the app's system settings page so the user can manually grant the permission. */
    fun openAppSettings()

    /** Returns the current ARCore availability on this device. */
    fun checkARCoreAvailability(): ArCoreApk.Availability

    /**
     * Requests ARCore installation or update if necessary.
     *
     * @param userRequestedInstall `true` when the user explicitly triggered the install flow.
     * @return `true` if an install was requested (the activity will be paused), `false` if
     *         ARCore is already installed.
     */
    fun requestARCoreInstall(userRequestedInstall: Boolean): Boolean
}

/**
 * Production [ARPermissionHandler] backed by a [ComponentActivity].
 *
 * Registers an [ActivityResultLauncher] for the camera permission and delegates ARCore
 * install requests to the host activity. Typical usage: create one instance in
 * [ARCore.create] and store it for the session lifetime.
 *
 * @param activity The host activity used for permission requests and ARCore install.
 */
class ActivityARPermissionHandler(
    private val activity: ComponentActivity
) : ARPermissionHandler {

    private var permissionCallback: ((Boolean) -> Unit)? = null

    /** Launcher for the camera permission dialog. */
    val cameraPermissionLauncher: ActivityResultLauncher<String> =
        activity.activityResultRegistry.register(
            "sceneview_camera_permission",
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            permissionCallback?.invoke(isGranted)
            permissionCallback = null
        }

    /** Launcher that opens the app settings and clears the "settings requested" flag. */
    val appSettingsLauncher: ActivityResultLauncher<Intent> =
        activity.activityResultRegistry.register(
            "sceneview_app_settings",
            ActivityResultContracts.StartActivityForResult()
        ) { /* no-op — the onResume cycle will re-check permission */ }

    override fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            activity, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    override fun requestCameraPermission(onResult: (granted: Boolean) -> Unit) {
        permissionCallback = onResult
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun shouldShowPermissionRationale(): Boolean =
        !ActivityCompat.shouldShowRequestPermissionRationale(
            activity, Manifest.permission.CAMERA
        )

    override fun openAppSettings() {
        // No toast (#3308): this is only reached from an explicit "Open settings" tap on an
        // in-app explanation, which already says why the user is being sent there.
        appSettingsLauncher.launch(Intent().apply {
            action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            data = Uri.fromParts("package", activity.packageName, null)
        })
    }

    override fun checkARCoreAvailability(): ArCoreApk.Availability =
        ArCoreApk.getInstance().checkAvailability(activity)

    override fun requestARCoreInstall(userRequestedInstall: Boolean): Boolean =
        ArCoreApk.getInstance().requestInstall(
            activity, userRequestedInstall
        ) == ArCoreApk.InstallStatus.INSTALL_REQUESTED
}
