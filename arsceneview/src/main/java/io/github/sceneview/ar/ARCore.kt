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
     *
     * Since #4452 it is invoked each time the verdict changes — from "ask again" to "open
     * settings" and back — and `permanentlyDenied` no longer mirrors
     * [ARPermissionHandler.shouldShowPermissionRationale] alone: a dialog the user dismissed
     * without answering leaves no rationale flag either, and is not a permanent denial (see
     * [isCameraPromptBlocked]).
     */
    var onCameraPermissionDenied: ((permanentlyDenied: Boolean) -> Unit)? = null

    /**
     * Called on the main thread when a denial reported through [onCameraPermissionDenied] no
     * longer holds — the camera was granted, from the dialog or from system settings (#4452).
     *
     * Before #4452 only a successfully created session took the explanation down, so a grant
     * followed by anything else (ARCore missing, a session that fails) left "camera access
     * needed" on screen over the real reason.
     */
    var onCameraPermissionGranted: (() -> Unit)? = null

    /**
     * `true` after the user denied the camera permission and until it is granted — while
     * set, session creation is held back so `resume()` never throws for a missing camera.
     */
    var isCameraPermissionDenied: Boolean = false
        private set

    /** The last verdict sent to [onCameraPermissionDenied]; `null` while nothing is denied. */
    private var publishedCameraDenial: Boolean? = null

    /** The camera request that has not been answered yet, if any. */
    private var cameraRequest: CameraRequest? = null

    /** Refusals in a row that came back with no rationale flag — see [isCameraPromptBlocked]. */
    private var unexplainedCameraRefusals = 0

    /** `true` between [resume] and [pause]. */
    private var isHostResumed = false

    /** `true` from [detachHost] until the next [create]: nothing may start or publish. */
    private var isHostDetached = false

    /**
     * Creates and resumes the session outside a host resume. A seam for JVM tests, which
     * cannot create an ARCore session.
     */
    internal var startSession: (Context) -> Unit = { context ->
        createSession(context)
        session?.resume()
    }

    /** Monotonic clock, in nanoseconds. Replaced in tests. */
    internal var nanoTime: () -> Long = System::nanoTime

    /** What a camera request started from, so its answer can be read in context. */
    private class CameraRequest(
        val handler: ARPermissionHandler,
        val rationaleBefore: Boolean,
        val startedAtNanos: Long,
    ) {
        /** The host was paused after the request went out — the system dialog took over. */
        var hostPausedSince = false

        /** The host left while the request was out: its answer is for nobody. */
        var isAbandoned = false
    }

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

    /** Context of the last [resume], so a grant that arrives while resumed can start AR. */
    private var resumeContext: Context? = null

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
        isHostDetached = false
        // A host that comes back after [detachHost] finds its registration released (#4467).
        (handler as? ActivityARPermissionHandler)?.register()

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
        isHostResumed = true
        resumeContext = context
        if (session == null) {
            if (handler == null || checkPermissionAndInstall(handler)) {
                createSession(context)
            }
        }
        session?.resume()
    }

    /** Pauses the current ARCore session. */
    fun pause() {
        isHostResumed = false
        cameraRequest?.hostPausedSince = true
        session?.pause()
    }

    /**
     * The host is gone — `ARSceneView` left composition (#4452). Call it before [destroy].
     *
     * [destroy] closes the session and nothing else, and what outlives the host can still
     * reach this instance: the answer to a camera request that was out, or an
     * [ARCameraPermissionState] the host kept from `onCameraPermissionStateChanged`. Either
     * one used to be able to create and resume a session for a scene that no longer exists,
     * and keep the camera open behind the app. From here until the next [create], a late
     * answer is dropped, [retryCameraPermission] does nothing and no verdict is published.
     *
     * It also releases the activity-result registration `ARSceneView` made for itself.
     */
    internal fun detachHost() {
        isHostDetached = true
        isHostResumed = false
        resumeContext = null
        cameraRequest?.let {
            it.isAbandoned = true
            // Nobody saw its answer: the next host may ask.
            cameraPermissionRequested = false
        }
        cameraRequest = null
        // The registration of the view's own handler goes with the view (#4467): what the
        // activity's registry keeps under a key is never collected otherwise.
        (permissionHandler as? ActivityARPermissionHandler)?.releaseRegistration()
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
            requestCameraPermissionOnce(handler)
            // Denied (or still being asked): hold the session back instead of letting
            // `Session()` throw `CameraNotAvailable` on the next resume.
            return false
        }
        onCameraPermissionAvailable()
        return try {
            checkARCoreInstall(handler)
        } catch (e: Exception) {
            // `requestInstall` throws `Unavailable*Exception` on a device it cannot serve.
            // Classify it so the UI can explain, then still report it to the host.
            publishAvailability(e.toARCoreAvailability())
            onException(e)
            false
        }
    }

    /**
     * Asks for the camera permission once per instance, and never loses track of the answer
     * (#4452).
     *
     * A request used to be latched the moment it went out, and the explanation only ever came
     * from its answer: an answer that did not arrive — the handler was replaced, another
     * registration took the result — left the session held back with nothing on screen, and
     * no later resume asked again or said why.
     */
    private fun requestCameraPermissionOnce(handler: ARPermissionHandler) {
        val pending = cameraRequest
        if (pending != null) {
            // The system dialog pauses the host and its answer is delivered before the host
            // resumes. Being here on a resume that followed that pause — or with another
            // handler than the one that was asked — means the answer is not coming.
            if (pending.handler === handler && !pending.hostPausedSince) return
            cameraRequest = null
            // Nothing says the dialog is blocked: offer to ask again.
            publishCameraDenial(permanentlyDenied = false)
            return
        }
        if (cameraPermissionRequested) {
            // Already refused. A rationale flag raised since then (the answer went to another
            // registration, say) means the dialog will show: do not keep sending to settings.
            if (publishedCameraDenial == true && !handler.shouldShowPermissionRationale()) {
                publishCameraDenial(permanentlyDenied = false)
            }
            return
        }
        cameraPermissionRequested = true
        // The dialog of a request made before the activity was recreated is already up, or
        // was answered while the activity was gone (#4467). The handler takes that request
        // over; here it must not read as "answered at once", which means a blocked dialog.
        val inherited = (handler as? ActivityARPermissionHandler)?.inheritsCameraRequest == true
        val shownForNanos =
            if (inherited) CAMERA_PROMPT_INSTANT_RETURN_MS * NANOS_PER_MILLI else 0L
        val request = CameraRequest(
            handler = handler,
            // `shouldShowPermissionRationale()` is historically inverted on this interface:
            // it answers `true` when there is NO rationale to show.
            rationaleBefore = !handler.shouldShowPermissionRationale(),
            startedAtNanos = nanoTime() - shownForNanos,
        )
        cameraRequest = request
        // Android cancels a request it cannot show — another permission is being asked, the
        // activity is being recreated — with an empty result. That is not an answer.
        (handler as? ActivityARPermissionHandler)?.onCameraRequestCancelled = {
            if (cameraRequest === request) cancelPendingCameraRequest()
        }
        handler.requestCameraPermission { granted ->
            if (request.isAbandoned || isHostDetached) return@requestCameraPermission
            val isCurrent = cameraRequest === request
            if (isCurrent) cameraRequest = null
            when {
                granted -> onCameraPermissionGrantedByUser(handler)
                // A late refusal for a request that was given up on still knows more than
                // "no answer" did — unless a newer request is out.
                isCurrent || cameraRequest == null -> onCameraPermissionRefused(handler, request)
            }
        }
    }

    /**
     * The pending request was cancelled before the user could answer it. Nobody refused
     * anything: offer to ask again, and do not count it towards "the dialog is blocked".
     */
    internal fun cancelPendingCameraRequest() {
        cameraRequest ?: return
        cameraRequest = null
        publishCameraDenial(permanentlyDenied = false)
    }

    private fun onCameraPermissionRefused(handler: ARPermissionHandler, request: CameraRequest) {
        val rationaleAfter = !handler.shouldShowPermissionRationale()
        val blocked = isCameraPromptBlocked(
            rationaleBefore = request.rationaleBefore,
            rationaleAfter = rationaleAfter,
            elapsedMs = (nanoTime() - request.startedAtNanos) / NANOS_PER_MILLI,
            earlierUnexplainedRefusals = unexplainedCameraRefusals,
        )
        unexplainedCameraRefusals = if (rationaleAfter) 0 else unexplainedCameraRefusals + 1
        publishCameraDenial(permanentlyDenied = blocked)
    }

    /**
     * The dialog was answered with a grant. Its dismissal normally resumes the host, and
     * [resume] creates the session; a handler that never paused the host (an in-app prompt,
     * a late answer) gets the session started from here instead.
     */
    private fun onCameraPermissionGrantedByUser(handler: ARPermissionHandler) {
        onCameraPermissionAvailable()
        if (isHostResumed && session == null && checkPermissionAndInstall(handler)) {
            startSessionIfHostResumed()
        }
    }

    /** Creates and resumes the session now when no host resume is coming to do it. */
    private fun startSessionIfHostResumed() {
        val context = resumeContext ?: return
        if (isHostDetached || !isHostResumed || session != null) return
        startSession(context)
    }

    /** The camera can be used: forget the refusal and take its explanation down. */
    private fun onCameraPermissionAvailable() {
        cameraRequest = null
        unexplainedCameraRefusals = 0
        isCameraPermissionDenied = false
        if (publishedCameraDenial != null && !isHostDetached) {
            publishedCameraDenial = null
            onCameraPermissionGranted?.invoke()
        }
    }

    /** Publishes a refusal to [onCameraPermissionDenied] when the verdict actually changed. */
    private fun publishCameraDenial(permanentlyDenied: Boolean) {
        isCameraPermissionDenied = true
        if (isHostDetached || publishedCameraDenial == permanentlyDenied) return
        publishedCameraDenial = permanentlyDenied
        onCameraPermissionDenied?.invoke(permanentlyDenied)
    }

    /**
     * Asks ARCore whether it can serve this device and launches the install / update flow
     * when there is one, publishing the verdict on the way (#3374).
     *
     * @return `true` when the session may be created now.
     */
    private fun checkARCoreInstall(handler: ARPermissionHandler): Boolean {
        if (!checkAvailability || installRequested) {
            // Availability checks disabled, or the user is coming back from the Play Store
            // install we requested: the session may start, so drop any card.
            publishAvailability(null)
            return true
        }
        val availability = handler.checkARCoreAvailability()
        val unavailable = availability.toARCoreAvailability()
        if (unavailable == null) {
            publishAvailability(null)
            // Still `UNKNOWN_CHECKING`? ARCore has not answered yet: hold the session back
            // and let the next resume ask again, rather than requesting an install for a
            // device we have not classified.
            return availability == Availability.SUPPORTED_INSTALLED
        }
        publishAvailability(unavailable)
        // Only the install / update states have a Play Store flow to launch.
        // `UNSUPPORTED_DEVICE_NOT_CAPABLE` makes `requestInstall` throw, and a failed check
        // has nothing to install — both are surfaced, not retried behind the user's back.
        if (unavailable != ARCoreAvailability.NotInstalled &&
            unavailable != ARCoreAvailability.NeedsUpdate
        ) {
            return false
        }
        if (!handler.requestARCoreInstall(!installRequested)) {
            // ARCore reports it is installed after all.
            publishAvailability(null)
            return true
        }
        installRequested = true
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
     * [onCameraPermissionDenied]. A grant resumes the activity, which creates the session;
     * when the camera was granted in the meantime no dialog shows and the session starts
     * right away (#4452).
     *
     * @param handler the permission handler; defaults to the one passed to [create].
     */
    fun retryCameraPermission(handler: ARPermissionHandler? = permissionHandler) {
        handler ?: return
        // A state the host kept after the scene left composition must not open a dialog,
        // let alone a session.
        if (isHostDetached) return
        cameraRequest = null
        cameraPermissionRequested = false
        // Granted in the meantime, with no pause to resume from: start now.
        if (checkPermissionAndInstall(handler)) startSessionIfHostResumed()
    }

    /**
     * Opens the app's system settings so the user can grant a permanently denied camera
     * permission. Only ever call this from an explicit user action (#3308).
     */
    fun openAppSettings(handler: ARPermissionHandler? = permissionHandler) {
        // Like [retryCameraPermission]: a state kept after the scene left opens nothing.
        if (isHostDetached) return
        handler?.openAppSettings()
    }

    /**
     * Explicitly closes the ARCore session to release native resources.
     *
     * A session that is still resumed is paused first (#4026). `ARSceneView` leaving
     * composition while its Activity stays in the foreground — a demo swapping a replay for a
     * gallery, a screen dropping its AR view — closes a session no lifecycle `ON_PAUSE` ever
     * reached, and ARCore's documented order is `pause()` then `close()`, both on the main
     * thread.
     *
     * Review the API reference for important considerations before calling close() in apps with
     * more complicated lifecycle requirements: [Session.close]
     */
    fun destroy() {
        session?.let {
            synchronized(it) {
                if (session == null) return@synchronized
                closeSessionInOrder(isResumed = it.isResumed, pause = it::pause, close = it::close)
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

/** Below this, a refusal came back too fast for a dialog to have been read and answered. */
internal const val CAMERA_PROMPT_INSTANT_RETURN_MS = 1000L

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Whether a refused camera request means Android has stopped showing its dialog (#4452).
 *
 * Android has no "permanently denied" query. A refusal with no rationale flag is what a
 * permanent denial looks like — and also what a dialog dismissed with Back, or with a tap
 * outside it, looks like. Reading the flag alone sent the second kind to system settings
 * with "camera access was turned off", when asking again would simply have worked. The two
 * are told apart by what surrounds the answer:
 *  - the rationale flag was up before and is down after: that was the second refusal;
 *  - the answer came back at once ([CAMERA_PROMPT_INSTANT_RETURN_MS]): no dialog was shown.
 *    A Pixel 4a answers a blocked request in 290 ms on an idle screen and 510 ms while a
 *    scene is still loading; nobody reads and dismisses a dialog inside a second;
 *  - it is the second such answer in a row ([earlierUnexplainedRefusals]): whichever it was,
 *    asking again is not getting anywhere, so settings is the way out.
 *
 * A first slow, unexplained refusal is therefore not blocked: a dismissed dialog keeps its
 * "ask again".
 */
internal fun isCameraPromptBlocked(
    rationaleBefore: Boolean,
    rationaleAfter: Boolean,
    elapsedMs: Long,
    earlierUnexplainedRefusals: Int,
): Boolean = !rationaleAfter && (
    rationaleBefore ||
        elapsedMs < CAMERA_PROMPT_INSTANT_RETURN_MS ||
        earlierUnexplainedRefusals >= 1
    )

/**
 * Pause-then-close for an ARCore session (#4026), split out so the order is a JVM test.
 *
 * A failing `pause()` must not keep the session from being closed: the caller is tearing it
 * down either way, and leaking the native session is worse than a pause that did not happen.
 */
internal fun closeSessionInOrder(
    isResumed: Boolean,
    pause: () -> Unit,
    close: () -> Unit,
    onPauseFailed: (RuntimeException) -> Unit = {
        android.util.Log.w("ARCore", "Session pause before close failed", it)
    },
) {
    if (isResumed) {
        try {
            pause()
        } catch (e: RuntimeException) {
            onPauseFailed(e)
        }
    }
    close()
}
