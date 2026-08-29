package io.github.sceneview.ar

import android.content.Context
import android.os.Build
import com.google.ar.core.ArCoreApk.Availability
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.arcore.ARSession

/**
 * Assumed distance in meters from the device camera to the surface on which the user will
 * try to place models.
 *
 * This value affects the apparent scale of objects while the tracking method of the Instant
 * Placement point is `SCREENSPACE_WITH_APPROXIMATE_DISTANCE`. Values in the [0.2, 2.0] meter
 * range are a good choice for most AR experiences.
 */
const val kDefaultHitTestInstantDistance = 2.0f

/**
 * Manages an ARCore [Session] lifecycle.
 *
 * Before starting a session this class checks camera permission and ARCore availability
 * through an [ARPermissionHandler], which decouples the permission logic from
 * [android.app.Activity] and makes the class testable with a mock handler.
 *
 * @param onSessionCreated     Called once when the [Session] is created.
 * @param onSessionResumed     Called each time the session resumes.
 * @param onSessionPaused      Called each time the session pauses.
 * @param onArSessionFailed    Called when session creation or resume fails.
 * @param onSessionConfigChanged Called when the session configuration changes.
 */
class ARCore(
    val onSessionCreated: (session: Session) -> Unit,
    val onSessionResumed: (session: Session) -> Unit,
    val onSessionPaused: (session: Session) -> Unit,
    val onArSessionFailed: (exception: Exception) -> Unit,
    val onSessionConfigChanged: (session: Session, config: Config) -> Unit
) {

    /** Enable/disable the automatic camera permission check. */
    var checkCameraPermission = true

    /** Enable/disable Google Play Services for AR availability check, auto-install and update. */
    var checkAvailability = true

    lateinit var features: Set<Session.Feature>

    /** The permission handler used for camera and ARCore availability checks. */
    var permissionHandler: ARPermissionHandler? = null

    /**
     * Called on the main thread when the user denies the camera permission (#3308).
     *
     * `permanentlyDenied` is `true` when the system will no longer show the permission dialog
     * ("Don't ask again" / second denial on Android 11+); the only way forward is then
     * [openAppSettings]. Otherwise [retryCameraPermission] shows the dialog again.
     *
     * Nothing is opened automatically any more: before #3308 a denial jumped straight to the
     * system App Info screen with a toast, backgrounding the activity with no explanation.
     * [ARSceneView] wires this to its built-in `cameraPermissionOverlay`.
     */
    var onCameraPermissionDenied: ((permanentlyDenied: Boolean) -> Unit)? = null

    /**
     * `true` after the user denied the camera permission and until it is granted — while
     * set, session creation is held back so `resume()` never throws for a missing camera.
     */
    var isCameraPermissionDenied: Boolean = false
        private set

    /**
     * Called on the main thread whenever the ARCore availability verdict changes (#3374).
     *
     * `null` means "nothing to report": ARCore is installed and current, or the check is still
     * running. A non-null value is terminal until the user acts — the device is not capable,
     * Google Play Services for AR is missing or too old, or the check itself failed.
     *
     * Before #3374 an unsupported device produced no signal at all: the install request threw,
     * the exception was swallowed into [onArSessionFailed], and hosts that had not wired that
     * callback (every demo) sat on their own "initializing" copy forever.
     * [ARSceneView] wires this to its built-in `arCoreAvailabilityOverlay`.
     */
    var onARCoreAvailability: ((availability: ARCoreAvailability?) -> Unit)? = null

    /** The last verdict published through [onARCoreAvailability]. `null` when AR can start. */
    var arCoreAvailability: ARCoreAvailability? = null
        private set

    private var cameraPermissionRequested = false
    private var installRequested = false

    /** Last context a session was created from, so a retry can create another one. */
    private var lastContext: Context? = null

    internal var session: ARSession? = null
        private set

    /**
     * Initializes the ARCore session lifecycle.
     *
     * @param context  Android context for session creation.
     * @param handler  Permission handler for camera permission and ARCore install checks.
     *                 Pass `null` to skip all permission checks (useful for tests or
     *                 contexts where the camera permission is guaranteed).
     * @param features ARCore session features to enable.
     */
    fun create(context: Context, handler: ARPermissionHandler?, features: Set<Session.Feature>) {
        this.features = features
        this.permissionHandler = handler

        if (handler != null) {
            if (checkPermissionAndInstall(handler)) {
                createSession(context)
            }
        } else {
            createSession(context)
        }
    }

    /**
     * Resumes the ARCore session, creating it first if necessary.
     *
     * @param context Android context for session creation.
     * @param handler Permission handler, or `null` to skip permission checks.
     */
    fun resume(context: Context, handler: ARPermissionHandler?) {
        if (session == null) {
            if (handler == null || checkPermissionAndInstall(handler)) {
                createSession(context)
            }
        }
        session?.resume()
    }

    /** Pauses the current ARCore session. */
    fun pause() {
        session?.pause()
    }

    /**
     * Creates the ARCore session.
     *
     * @param context Android context.
     */
    fun createSession(context: Context) {
        lastContext = context
        try {
            session = ARSession(
                context,
                features,
                onResumed = onSessionResumed,
                onPaused = onSessionPaused,
                onConfigChanged = onSessionConfigChanged
            ).also(onSessionCreated)
            publishAvailability(null)
        } catch (exception: Exception) {
            onSessionCreateFailed(exception)
        }
    }

    /**
     * Reports a session-creation failure as a state the UI can show, then forwards it (#3374).
     *
     * `ArCoreApk` answering `SUPPORTED_INSTALLED` is not a promise that `Session()` will
     * succeed — an emulator with Google Play Services for AR installed still fails to create
     * one. That left the exact hang #3374 is about, one step further along: no verdict, no
     * session, and the host's "initializing" copy up forever. Publishing
     * [ARCoreAvailability.SessionFailed] closes that last silent path.
     */
    internal fun onSessionCreateFailed(exception: Exception) {
        publishAvailability(ARCoreAvailability.SessionFailed)
        onException(exception)
    }

    /**
     * Checks camera permission and ARCore installation, requesting them if needed.
     *
     * @param handler The permission handler to delegate checks to.
     * @return `true` if all checks pass and the session can be created.
     */
    fun checkPermissionAndInstall(handler: ARPermissionHandler): Boolean {
        // Camera permission
        if (checkCameraPermission && !handler.hasCameraPermission()) {
            if (!cameraPermissionRequested) {
                cameraPermissionRequested = true
                handler.requestCameraPermission { granted ->
                    isCameraPermissionDenied = !granted
                    if (!granted) {
                        // `shouldShowPermissionRationale()` is historically inverted on this
                        // interface: it answers `true` once the system stops asking.
                        onCameraPermissionDenied?.invoke(handler.shouldShowPermissionRationale())
                    }
                    // On grant the dialog's dismissal resumes the activity, and `resume()`
                    // creates the session through the branch below.
                }
            }
            // Denied (or still being asked): hold the session back instead of letting
            // `Session()` throw `CameraNotAvailable` on the next resume.
        } else {
            isCameraPermissionDenied = false
            try {
                if (checkAvailability && !installRequested) {
                    val availability = handler.checkARCoreAvailability()
                    val unavailable = availability.toARCoreAvailability()
                    if (unavailable == null) {
                        publishAvailability(null)
                        // Still `UNKNOWN_CHECKING`? ARCore has not answered yet: hold the
                        // session back and let the next resume ask again, rather than
                        // requesting an install for a device we have not classified.
                        return availability == Availability.SUPPORTED_INSTALLED
                    }
                    publishAvailability(unavailable)
                    // Only the install / update states have a Play Store flow to launch.
                    // `UNSUPPORTED_DEVICE_NOT_CAPABLE` makes `requestInstall` throw, and a
                    // failed check has nothing to install — both are surfaced, not retried
                    // behind the user's back (#3374).
                    if (unavailable == ARCoreAvailability.NotInstalled ||
                        unavailable == ARCoreAvailability.NeedsUpdate
                    ) {
                        if (handler.requestARCoreInstall(!installRequested)) {
                            installRequested = true
                        } else {
                            // ARCore reports it is installed after all.
                            publishAvailability(null)
                            return true
                        }
                    }
                } else {
                    // Availability checks disabled, or the user is coming back from the Play
                    // Store install we requested: the session may start, so drop any card.
                    publishAvailability(null)
                    return true
                }
            } catch (e: Exception) {
                // `requestInstall` throws `Unavailable*Exception` on a device it cannot serve.
                // Classify it so the UI can explain, then still report it to the host.
                publishAvailability(e.toARCoreAvailability())
                onException(e)
            }
        }
        return false
    }

    /** Publishes [availability] to [onARCoreAvailability] when it actually changed. */
    private fun publishAvailability(availability: ARCoreAvailability?) {
        if (arCoreAvailability == availability) return
        arCoreAvailability = availability
        onARCoreAvailability?.invoke(availability)
    }

    /**
     * Re-runs the ARCore availability check after a verdict reported through
     * [onARCoreAvailability], launching the install / update flow when there is one (#3374).
     *
     * Safe to call from an "Install" / "Update" / "Try again" tap: the install request is
     * un-latched first, so a user who cancelled the Play Store flow can start it again.
     *
     * @param handler the permission handler; defaults to the one passed to [create].
     */
    fun retryARCoreAvailability(handler: ARPermissionHandler? = permissionHandler) {
        // A session that failed to be created is retried by creating another one — the
        // availability check already said "installed" and would say so again (#3374).
        if (arCoreAvailability == ARCoreAvailability.SessionFailed) {
            retrySession()
            return
        }
        handler ?: return
        installRequested = false
        checkPermissionAndInstall(handler)
    }

    /**
     * Drops a session that failed to be created and creates a fresh one, resuming it when the
     * host is resumed. No-op before any creation attempt.
     */
    fun retrySession(context: Context? = lastContext) {
        val target = context ?: return
        publishAvailability(null)
        destroy()
        createSession(target)
        session?.resume()
    }

    /**
     * Shows the camera permission dialog again after a denial reported through
     * [onCameraPermissionDenied]. A grant resumes the activity, which creates the session.
     *
     * @param handler the permission handler; defaults to the one passed to [create].
     */
    fun retryCameraPermission(handler: ARPermissionHandler? = permissionHandler) {
        handler ?: return
        cameraPermissionRequested = false
        checkPermissionAndInstall(handler)
    }

    /**
     * Opens the app's system settings so the user can grant a permanently denied camera
     * permission. Only ever call this from an explicit user action (#3308).
     */
    fun openAppSettings(handler: ARPermissionHandler? = permissionHandler) {
        handler?.openAppSettings()
    }

    /**
     * Explicitly closes the ARCore session to release native resources.
     *
     * Review the API reference for important considerations before calling close() in apps with
     * more complicated lifecycle requirements: [Session.close]
     */
    fun destroy() {
        session?.let {
            synchronized(it) {
                if (session == null) return@synchronized
                it.close()
                session = null
            }
        }
    }

    /** Forwards an exception to the [onArSessionFailed] callback. */
    fun onException(exception: Exception) {
        onArSessionFailed(exception)
    }

    // ── Deprecated compatibility overloads ────────────────────────────────────────────────────────

    /**
     * @deprecated Use [create] with an [ARPermissionHandler] instead.
     */
    @Deprecated(
        "Use create(context, handler, features) instead",
        ReplaceWith("create(context, (context as? androidx.activity.ComponentActivity)?.let { ActivityARPermissionHandler(it) }, features)")
    )
    fun create(context: Context, features: Set<Session.Feature>) {
        val handler = (context as? androidx.activity.ComponentActivity)?.let {
            ActivityARPermissionHandler(it)
        }
        create(context, handler, features)
    }

    /**
     * @deprecated Use [resume] with an [ARPermissionHandler] instead.
     */
    @Deprecated(
        "Use resume(context, handler) instead",
        ReplaceWith("resume(context, (context as? androidx.activity.ComponentActivity)?.let { ActivityARPermissionHandler(it) })")
    )
    fun resume(context: Context) {
        val handler = (context as? androidx.activity.ComponentActivity)?.let {
            ActivityARPermissionHandler(it)
        }
        resume(context, handler)
    }
}

/**
 * Returns a human-readable description of the given [TrackingFailureReason].
 *
 * @param context Android context for string resource resolution.
 */
@Suppress("REDUNDANT_ELSE_IN_WHEN")
fun TrackingFailureReason.getDescription(context: Context) = when (this) {
    TrackingFailureReason.NONE -> ""
    TrackingFailureReason.BAD_STATE -> context.getString(R.string.sceneview_bad_state_message)
    TrackingFailureReason.INSUFFICIENT_LIGHT -> context.getString(
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            R.string.sceneview_insufficient_light_message
        } else {
            R.string.sceneview_insufficient_light_android_s_message
        }
    )
    TrackingFailureReason.EXCESSIVE_MOTION -> context.getString(R.string.sceneview_excessive_motion_message)
    TrackingFailureReason.INSUFFICIENT_FEATURES -> context.getString(R.string.sceneview_insufficient_features_message)
    TrackingFailureReason.CAMERA_UNAVAILABLE -> context.getString(R.string.sceneview_camera_unavailable_message)
    else -> context.getString(R.string.sceneview_unknown_tracking_failure, this)
}
