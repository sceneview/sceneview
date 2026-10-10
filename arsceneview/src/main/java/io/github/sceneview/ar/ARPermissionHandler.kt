package io.github.sceneview.ar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
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
 * install requests to the host activity.
 *
 * `ARScene` builds its own instance when you pass none, registered under a key that belongs
 * to that one AR view, saved with it and released when it leaves composition (#4467). An
 * instance built with this constructor registers under one activity-wide key and is never
 * unregistered: keep **at most one** alive per activity, or the answer to a camera request
 * made through one can reach another.
 *
 * @param activity The host activity used for permission requests and ARCore install.
 */
class ActivityARPermissionHandler private constructor(
    private val activity: ComponentActivity,
    state: ARPermissionRegistrationState,
    isOwnedByView: Boolean,
) : ARPermissionHandler {

    constructor(activity: ComponentActivity) : this(
        activity,
        ARPermissionRegistrationState(SHARED_CAMERA_PERMISSION_KEY),
        isOwnedByView = false,
    )

    /** The handler of one `ARSceneView`: its own key, released with the view (#4467). */
    internal constructor(activity: ComponentActivity, state: ARPermissionRegistrationState) :
        this(activity, state, isOwnedByView = true)

    private val registration = ARPermissionRegistration(
        registry = activity.activityResultRegistry,
        state = state,
        isOwnedByView = isOwnedByView,
        isCameraGranted = { hasCameraPermission() },
    )

    /**
     * Called instead of the request's `onResult` when Android cancelled the request without
     * showing it to the user (#4452) — see [cameraPermissionAnswer].
     */
    internal var onCameraRequestCancelled: (() -> Unit)?
        get() = registration.onCameraRequestCancelled
        set(value) { registration.onCameraRequestCancelled = value }

    /** See [ARPermissionRegistration.inheritsCameraRequest]. */
    internal val inheritsCameraRequest: Boolean get() = registration.inheritsCameraRequest

    internal val isRegistered: Boolean get() = registration.isRegistered

    /** Launcher for the camera permission dialog. */
    val cameraPermissionLauncher: ActivityResultLauncher<String>
        get() = registration.cameraPermissionLauncher

    /** Launcher that opens the app settings and clears the "settings requested" flag. */
    val appSettingsLauncher: ActivityResultLauncher<Intent>
        get() = registration.appSettingsLauncher

    /** Registers the launchers again after [releaseRegistration]; otherwise a no-op. */
    internal fun register() = registration.register()

    /** See [ARPermissionRegistration.release]. */
    internal fun releaseRegistration() = registration.release()

    override fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            activity, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    override fun requestCameraPermission(onResult: (granted: Boolean) -> Unit) {
        registration.requestCamera(onResult)
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

/**
 * What a camera permission result says: `true` granted, `false` refused, `null` when the
 * result is empty — Android cancelled the request before the user saw it (#4452).
 *
 * That happens when another permission request is already on screen, or when the activity is
 * recreated with the dialog up. `ActivityResultContracts.RequestPermission` reports it as a
 * plain `false`, and it comes back at once: read as an answer, it is "refused instantly",
 * which is exactly what a permanently denied permission looks like.
 */
internal fun cameraPermissionAnswer(result: Map<String, Boolean>): Boolean? =
    if (result.isEmpty()) null else result.values.all { it }

/** `RequestPermission`, except that a cancelled request is `null` instead of `false`. */
internal class CameraPermissionContract : ActivityResultContract<String, Boolean?>() {
    private val delegate = ActivityResultContracts.RequestMultiplePermissions()

    override fun createIntent(context: Context, input: String): Intent =
        delegate.createIntent(context, arrayOf(input))

    override fun getSynchronousResult(context: Context, input: String): SynchronousResult<Boolean?>? =
        delegate.getSynchronousResult(context, arrayOf(input))
            ?.let { SynchronousResult(cameraPermissionAnswer(it.value)) }

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean? =
        cameraPermissionAnswer(delegate.parseResult(resultCode, intent))
}
