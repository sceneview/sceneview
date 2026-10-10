package io.github.sceneview.ar

import android.Manifest
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import java.util.UUID
import java.util.WeakHashMap

/** The one key every [ActivityARPermissionHandler] registered under before #4467. */
internal const val SHARED_CAMERA_PERMISSION_KEY = "sceneview_camera_permission"
private const val SHARED_APP_SETTINGS_KEY = "sceneview_app_settings"

/**
 * What one `ARSceneView` keeps in saved state for its activity-result registration (#4467).
 *
 * An activity-result key has to be the same before and after the activity is recreated: the
 * registry saves which key a request was launched under, and hands the answer to whoever
 * registers that key again. So the key is drawn once per `ARSceneView` and saved with it —
 * what `rememberLauncherForActivityResult` does — instead of being one constant shared by
 * every AR view of the activity.
 *
 * @property key                The registration key, unique to one `ARSceneView`.
 * @property isCameraRequestOut A camera request was launched under [key] and has not been
 *                              answered. Restored `true`, it tells the recreated view that the
 *                              system dialog is already up: asking again would have Android
 *                              cancel the new request and the registry park the real answer.
 */
internal class ARPermissionRegistrationState(
    val key: String,
    var isCameraRequestOut: Boolean = false,
) {
    companion object {
        fun create() = ARPermissionRegistrationState("sceneview_ar_permission_${UUID.randomUUID()}")

        val Saver: Saver<ARPermissionRegistrationState, Any> = listSaver(
            save = { listOf(it.key, it.isCameraRequestOut) },
            restore = { ARPermissionRegistrationState(it[0] as String, it[1] as Boolean) },
        )
    }
}

/**
 * Requests that were still out when their view released its registration, per registry.
 *
 * A view that comes back in the same activity (a back stack restoring its saved state) must
 * not wait for such a request: its answer was dropped with the registration. A recreated
 * activity has a new registry and is not listed here, so it does wait. Main thread only.
 */
private val abandonedCameraRequests = WeakHashMap<ActivityResultRegistry, MutableSet<String>>()

/**
 * The activity-result registrations of one [ActivityARPermissionHandler] (#4467): the camera
 * permission dialog and the app settings screen.
 *
 * Split from the handler so the registration lifecycle is a JVM test against a plain
 * [ActivityResultRegistry], with no activity.
 *
 * @param registry        The host activity's registry.
 * @param state           The key, and whether a request made before a recreation is still out.
 * @param isOwnedByView   `true` for the registration an `ARSceneView` made for itself: it is
 *                        released with the view. A handler the host built with the public
 *                        constructor may be shared or outlive a view, and is left alone.
 * @param isCameraGranted Reads the permission, to tell a stale answer from a current one.
 */
internal class ARPermissionRegistration(
    private val registry: ActivityResultRegistry,
    private val state: ARPermissionRegistrationState,
    private val isOwnedByView: Boolean,
    private val isCameraGranted: () -> Boolean,
) {
    private val settingsKey =
        if (isOwnedByView) "${state.key}#settings" else SHARED_APP_SETTINGS_KEY

    private var cameraLauncher: ActivityResultLauncher<String>? = null
    private var settingsLauncher: ActivityResultLauncher<Intent>? = null

    /** Who asked, until the answer arrives. */
    private var cameraCallback: ((Boolean) -> Unit)? = null

    /** An answer that arrived for an inherited request before anybody here asked. */
    private var earlyCameraAnswer: Boolean? = null

    /**
     * Called instead of the request's `onResult` when Android cancelled the request without
     * showing it to the user (#4452) — see [cameraPermissionAnswer].
     */
    var onCameraRequestCancelled: (() -> Unit)? = null

    /**
     * `true` until the next [requestCamera] when a request launched before the activity was
     * recreated is still to be answered: that call takes the request over instead of
     * launching another one.
     */
    var inheritsCameraRequest: Boolean =
        state.isCameraRequestOut &&
            abandonedCameraRequests[registry]?.remove(state.key) != true
        private set

    /** `true` while the launchers are registered. */
    val isRegistered: Boolean get() = cameraLauncher != null || settingsLauncher != null

    val cameraPermissionLauncher: ActivityResultLauncher<String>
        get() = cameraLauncher
            ?: registry.register(state.key, CameraPermissionContract(), ::onCameraAnswer)
                .also { cameraLauncher = it }

    val appSettingsLauncher: ActivityResultLauncher<Intent>
        get() = settingsLauncher
            ?: registry.register(
                settingsKey,
                ActivityResultContracts.StartActivityForResult()
            ) { /* no-op — the onResume cycle will re-check permission */ }
                .also { settingsLauncher = it }

    init {
        if (!inheritsCameraRequest) state.isCameraRequestOut = false
        register()
    }

    /**
     * Registers both launchers; a no-op when they already are. Registering is also what
     * collects an answer the registry kept while nobody was registered under the key.
     */
    fun register() {
        cameraPermissionLauncher
        appSettingsLauncher
    }

    /**
     * Shows the camera permission dialog — or, when the dialog of a request made before the
     * activity was recreated is still up or was just answered, takes that request over.
     */
    fun requestCamera(onResult: (granted: Boolean) -> Unit) {
        if (inheritsCameraRequest) {
            inheritsCameraRequest = false
            val early = earlyCameraAnswer
            earlyCameraAnswer = null
            when {
                // The dialog is still on screen: its answer is for this caller.
                early == null -> {
                    register()
                    cameraCallback = onResult
                    return
                }
                // Answered while the activity was gone, and still true.
                early == isCameraGranted() -> {
                    state.isCameraRequestOut = false
                    onResult(early)
                    return
                }
                // Out of date (the permission changed since): ask for real.
            }
        }
        cameraCallback = onResult
        state.isCameraRequestOut = true
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun onCameraAnswer(answer: Boolean?) {
        val callback = cameraCallback
        if (answer != null && callback == null && inheritsCameraRequest) {
            // Delivered while registering, before the recreated view asked: keep it for it.
            earlyCameraAnswer = answer
            return
        }
        cameraCallback = null
        state.isCameraRequestOut = false
        if (answer == null) onCameraRequestCancelled?.invoke() else callback?.invoke(answer)
    }

    /**
     * The view is gone: unregisters both launchers, so the registry holds nothing for it and
     * a late answer reaches nobody. Does nothing for a registration the view does not own.
     * [register], or the next launch, registers again.
     */
    fun release() {
        if (!isOwnedByView) return
        if (state.isCameraRequestOut) {
            abandonedCameraRequests.getOrPut(registry) { mutableSetOf() } += state.key
            state.isCameraRequestOut = false
        }
        cameraLauncher?.unregister()
        settingsLauncher?.unregister()
        cameraLauncher = null
        settingsLauncher = null
        cameraCallback = null
        earlyCameraAnswer = null
        inheritsCameraRequest = false
        onCameraRequestCancelled = null
    }
}

/**
 * The [ActivityARPermissionHandler] an `ARSceneView` builds for itself when its host passes
 * none: registered under a key of its own, saved with the view (#4467).
 *
 * `null` when the composition is not hosted by a [ComponentActivity].
 */
@Composable
internal fun rememberActivityARPermissionHandler(): ARPermissionHandler? {
    val activity = LocalContext.current as? ComponentActivity ?: return null
    val state = rememberSaveable(saver = ARPermissionRegistrationState.Saver) {
        ARPermissionRegistrationState.create()
    }
    return remember(activity, state) { ActivityARPermissionHandler(activity, state) }
}
