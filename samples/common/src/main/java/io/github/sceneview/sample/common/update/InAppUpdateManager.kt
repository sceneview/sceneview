package io.github.sceneview.sample.common.update

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Shared in-app update manager for the Android SceneView demo.
 *
 * Wraps Google Play Core's `AppUpdateManager` with a Compose-friendly
 * [updateState] + [downloadProgress] pair driven by [InstallStateUpdatedListener].
 * What the user sees is decided by [UpdatePromptController]: one prompt that goes
 * "Update available · Update" → "Waiting for Google Play…" → "Downloading update… 42 %"
 * → "Update ready · Restart", or "Update failed · Retry".
 *
 * ## Flexible flow, one Google modal
 *
 * 1. [checkForUpdate] runs on every `onResume`. On `UPDATE_AVAILABLE` it sets
 *    [updateState] to `AVAILABLE` and **stops** — it does NOT pop the Google
 *    modal. The detected [AppUpdateInfo] is stashed for later. The same call
 *    also re-attaches to a download a previous foreground left pending, running
 *    or finished, so it is the only call `onResume` needs.
 * 2. The user taps **Update**, which calls [startUpdate]: the state flips to
 *    `PENDING` on the spot (the prompt shows it in the same frame) and Google's
 *    consent modal is popped — the single modal of the flow.
 * 3. Play reports `PENDING`, then `DOWNLOADING` with byte counts ([downloadProgress]),
 *    then `DOWNLOADED`, which flips [updateState] to `READY_TO_INSTALL`; the
 *    prompt's **Restart** calls [completeUpdate].
 * 4. A cancelled consent modal returns to `AVAILABLE`; a failed download or a
 *    refused launch lands on `FAILED`, from which [retry] starts over.
 *
 * Wire it from a [ComponentActivity]:
 *
 * ```kotlin
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     updateManager = InAppUpdateManager(this)
 *     // Register the activity-result launcher BEFORE the activity is STARTED
 *     // so cancelling Google's consent modal is delivered back to the manager.
 *     updateManager.registerForResult(this)
 * }
 * override fun onResume() {
 *     super.onResume()
 *     // Cheap on the main thread, but still best posted after the first frame.
 *     updateManager.checkForUpdate()
 * }
 * override fun onDestroy() {
 *     super.onDestroy()
 *     updateManager.destroy()
 * }
 * ```
 *
 * **One call on resume, never two.** Until this was folded into [checkForUpdate]
 * the demo called a separate `checkForStalledUpdate()` first; it took the
 * re-entrancy guard for its own round-trip, so the `checkForUpdate()` that
 * followed in the same `onResume` always returned early and `UPDATE_AVAILABLE`
 * was never read — no Android user ever saw the prompt.
 *
 * **One manager per activity, one download per process.** The manager lives and
 * dies with its activity; Play owns the download. A recreated activity (a locale
 * or font-scale change — rotation and dark mode are handled in place by the demo's
 * `configChanges`) builds a new manager whose first [checkForUpdate] reads the
 * running download back from Play, byte counts included, so the prompt picks up
 * where it was.
 *
 * Uses [AppUpdateType.FLEXIBLE] (background download + user-driven restart) — see
 * <https://developer.android.com/guide/playcore/in-app-updates>. The Play SDK
 * compares the installed version against the Play Store track automatically, so
 * there is no `VERSION_NAME` plumbing to wire here. In-app updates only exist on
 * phones, tablets and ChromeOS, and only for a Play-installed build.
 *
 * ## Threading
 *
 * [inFlight], [pendingUpdateInfo], [listenerRegistered] and [destroyed] are
 * plain (non-`@Volatile`) fields: all mutations are main-thread-confined. Play
 * Core posts every `appUpdateInfo` success/failure callback and every
 * [InstallStateUpdatedListener] event to the main `Looper`, and the public
 * methods are all called from `Activity` lifecycle / Compose, so no two threads
 * ever touch this state.
 *
 * The one thing that does not run on the main thread is the Play round-trip
 * itself (#3939): creating the Play `AppUpdateManager` and asking it for
 * `appUpdateInfo` bind to the Play Store service, and the calling thread waits
 * on `system_server` and on Play Core's own `AppUpdateService` thread while it
 * does. Under load that wait reached 1.5 s inside `onResume`, in front of the
 * first frame. [checkForUpdate] therefore only flips state on the caller's
 * thread and hands the round-trip to [checkExecutor]; the Task's listeners
 * still land on the main thread. [UpdateCheckThrottle] then skips the
 * round-trip on resumes that follow a quiet answer.
 */
class InAppUpdateManager @VisibleForTesting constructor(
    private val activity: Activity,
    appUpdateManagerFactory: () -> AppUpdateManager,
    // Where the Play round-trip runs. A background thread in production; tests
    // and the debug QA fake pass an inline executor.
    private val checkExecutor: Executor,
    private val throttle: UpdateCheckThrottle,
) {

    /** Production wiring: real Play, round-trip on a background thread, process-wide throttle. */
    constructor(activity: Activity) : this(
        activity,
        { AppUpdateManagerFactory.create(activity) },
        backgroundCheckExecutor,
        UpdateCheckThrottle.PROCESS,
    )

    /**
     * A given [AppUpdateManager] — Google's `FakeAppUpdateManager` for tests, or
     * [QaAppUpdateManager] for debug QA. Queried inline (a fake never binds to
     * Play) and never throttled: every call reaches the fake.
     */
    constructor(activity: Activity, appUpdateManager: AppUpdateManager) : this(
        activity,
        { appUpdateManager },
        Executor { it.run() },
        UpdateCheckThrottle(minIntervalMillis = 0L),
    ) {
        // The QA fake answers the consent modal itself; it reports through the
        // same entry point the real activity result uses.
        (appUpdateManager as? QaAppUpdateManager)?.consentResults = { resultCode ->
            onUpdateFlowResult(ActivityResult(resultCode, null))
        }
    }

    // Created on first use, which is the first round-trip on [checkExecutor]:
    // `AppUpdateManagerFactory.create` stays off the main thread and out of
    // `onCreate`. Every later main-thread use (listener, update flow, restart)
    // happens after a round-trip has already created it.
    private val appUpdateManager: AppUpdateManager by lazy(appUpdateManagerFactory)

    var updateState by mutableStateOf(UpdateState.IDLE)
        private set

    /**
     * How much of the update has downloaded, `0f..1f`, while [updateState] is
     * `DOWNLOADING`. `null` until Play reports a total size — draw an
     * indeterminate indicator then.
     */
    var downloadProgress by mutableStateOf<Float?>(null)
        private set

    private var listenerRegistered = false

    // The `UPDATE_AVAILABLE` AppUpdateInfo read by the last round-trip, handed
    // to `startUpdateFlowForResult` by the user's tap without re-querying Play.
    // Play accepts an AppUpdateInfo for ONE flow only (a second
    // `startUpdateFlowForResult` with it returns false), so it is dropped the
    // moment a flow is launched with it; a later tap (after a cancelled consent
    // modal, or a Retry) asks Play for a fresh one.
    private var pendingUpdateInfo: AppUpdateInfo? = null

    // True while a Play `appUpdateInfo` round-trip is outstanding — the
    // CHECKING window of `checkForUpdate()`, or the re-query of a tap. A second
    // `onResume` landing inside it must not issue a parallel request.
    private var inFlight = false

    // Set in `destroy()`. Every async Play Task callback early-returns on this
    // so a late `appUpdateInfo` / install-state event arriving after
    // `onDestroy()` can't mutate Compose state or re-register the listener.
    private var destroyed = false

    // The activity-result launcher for Google's consent modal. Registered by
    // `registerForResult()` from the host activity's `onCreate`. The FLEXIBLE
    // consent modal's CANCEL is delivered HERE (RESULT_CANCELED), not via the
    // install-state listener — without this launcher a cancel would leave the
    // prompt waiting on Google Play forever.
    private var updateResultLauncher: ActivityResultLauncher<IntentSenderRequest>? = null

    private val installStateListener: InstallStateUpdatedListener = InstallStateUpdatedListener { state ->
        if (destroyed) return@InstallStateUpdatedListener
        val next = updateStateFor(state.installStatus()) ?: return@InstallStateUpdatedListener
        when (next) {
            UpdateState.DOWNLOADING ->
                downloadProgress = downloadFractionOf(state.bytesDownloaded(), state.totalBytesToDownload())
            UpdateState.PENDING -> downloadProgress = null
            // Terminal for this flow: nothing left to listen to until the next
            // one registers again. READY_TO_INSTALL keeps the listener — it must
            // still be live to observe INSTALLED after `completeUpdate()` (#1941).
            UpdateState.FAILED, UpdateState.IDLE, UpdateState.AVAILABLE -> unregisterListener()
            else -> {}
        }
        updateState = next
    }

    /**
     * Registers the [ActivityResultLauncher] used to receive the result of
     * Google's FLEXIBLE consent modal. **Must be called from the host
     * activity's `onCreate`**, before the activity reaches `STARTED`, because
     * `registerForActivityResult` cannot be called once the activity is
     * started.
     *
     * Cancelling the consent modal is delivered ONLY through this result
     * (`RESULT_CANCELED`) — not through the install-state listener. Without it
     * a cancel would leave the prompt on "Waiting for Google Play…" for good.
     */
    fun registerForResult(activity: ComponentActivity) {
        updateResultLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result -> onUpdateFlowResult(result) }
    }

    /**
     * Handles the result of Google's FLEXIBLE consent modal. Wired internally
     * by [registerForResult]; exposed [VisibleForTesting] so the
     * `RESULT_CANCELED` / `RESULT_OK` branches can be exercised on the JVM —
     * the [androidx.activity.result.ActivityResultLauncher] itself is not
     * driven by `FakeAppUpdateManager`.
     */
    @VisibleForTesting
    internal fun onUpdateFlowResult(result: ActivityResult) {
        if (destroyed) return
        // Only the wait on the modal is answered here. A late result landing
        // after the listener already moved on (DOWNLOADING…) changes nothing.
        if (updateState != UpdateState.PENDING) return
        when (result.resultCode) {
            // Accepted: Play takes over and the listener reports PENDING →
            // DOWNLOADING → DOWNLOADED. The prompt keeps waiting until then.
            Activity.RESULT_OK -> {}
            // Dismissed: back to a retryable offer. The next tap asks Play for a
            // fresh AppUpdateInfo — the one this modal was launched with is spent.
            Activity.RESULT_CANCELED -> {
                unregisterListener()
                updateState = UpdateState.AVAILABLE
            }
            // `ActivityResult.RESULT_IN_APP_UPDATE_FAILED` and anything unknown.
            else -> {
                unregisterListener()
                updateState = UpdateState.FAILED
            }
        }
    }

    /**
     * Queries the Play Store for a newer release. Safe to call on every
     * `onResume`, and the only call `onResume` needs. On `UPDATE_AVAILABLE` it
     * sets [updateState] to `AVAILABLE` and stops — it does **not** start the
     * Google consent flow. Call [startUpdate] from a deliberate user tap to do
     * that. A download a previous foreground left pending, running or finished is
     * picked up by the same round-trip.
     *
     * Never blocks the calling thread: the round-trip runs on the background
     * executor and its result is delivered on the main thread. After a quiet
     * answer (up to date, or a failed query) the next calls are skipped until
     * [UpdateCheckThrottle.DEFAULT_MIN_INTERVAL_MILLIS] has passed.
     */
    fun checkForUpdate() {
        if (destroyed) return
        // A flow on screen owns the state: a second `onResume` (returning from
        // Google's consent modal, a config change) must not re-query or
        // re-prompt (#1941), nor replace "Update failed · Retry".
        if (updateState in FLOW_STATES) return
        // Re-entrancy guard for the CHECKING window.
        if (inFlight) return
        // Play already answered "nothing to do" moments ago in this process.
        if (!throttle.shouldCheck()) return
        inFlight = true

        updateState = UpdateState.CHECKING
        checkExecutor.execute { requestAppUpdateInfo(launchIfAvailable = false) }
    }

    /**
     * Starts the Play in-app update flow. Triggers Google's **single** consent
     * modal — call this **only** from a deliberate user tap (the prompt's
     * **Update** action), never automatically.
     *
     * [updateState] becomes `PENDING` before this returns, so the prompt switches
     * to "Waiting for Google Play…" in the frame of the tap. A no-op unless
     * [updateState] is `AVAILABLE`, so a double tap cannot double-prompt.
     */
    fun startUpdate() {
        if (destroyed) return
        if (updateState != UpdateState.AVAILABLE) return
        beginUserFlow()
    }

    /**
     * Starts over after a failure: asks Play for a fresh [AppUpdateInfo] and, if
     * the update is still there, pops the consent modal again. Called from the
     * prompt's **Retry**. A no-op unless [updateState] is `FAILED`.
     */
    fun retry() {
        if (destroyed) return
        if (updateState != UpdateState.FAILED) return
        beginUserFlow()
    }

    /**
     * The user closed "Update failed" without retrying: forget the failure so the
     * next resume checks Play again (and offers the update again if it is still
     * there). A no-op unless [updateState] is `FAILED`.
     */
    fun dismissFailure() {
        if (destroyed) return
        if (updateState != UpdateState.FAILED) return
        updateState = UpdateState.IDLE
    }

    private fun beginUserFlow() {
        if (updateResultLauncher == null) return
        downloadProgress = null
        updateState = UpdateState.PENDING
        val info = pendingUpdateInfo
        if (info != null) {
            launchFlow(info)
        } else if (!inFlight) {
            inFlight = true
            checkExecutor.execute { requestAppUpdateInfo(launchIfAvailable = true) }
        }
    }

    // Runs on [checkExecutor]. Only touches Play: the Task's listeners (added
    // without an executor) are delivered on the main thread, where every state
    // mutation below happens.
    private fun requestAppUpdateInfo(launchIfAvailable: Boolean) {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                // Recorded even when destroyed: the answer holds for the whole
                // process, and the next manager's first resume relies on it.
                if (info.showsAFlow()) throttle.recordFlowResult() else throttle.recordQuietResult()
                if (destroyed) return@addOnSuccessListener
                inFlight = false
                onAppUpdateInfo(info, launchIfAvailable)
            }
            .addOnFailureListener {
                // A device without Play, or a build Play did not install, fails
                // the same way on every resume: do not rebind just to hear it again.
                throttle.recordQuietResult()
                if (destroyed) return@addOnFailureListener
                inFlight = false
                // A background check stays silent; a tap the user is watching
                // gets an answer they can act on.
                updateState = if (launchIfAvailable) UpdateState.FAILED else UpdateState.IDLE
            }
    }

    private fun onAppUpdateInfo(info: AppUpdateInfo, launchIfAvailable: Boolean) {
        when (info.installStatus()) {
            // A flow a previous foreground (or a destroyed manager) started is
            // still running in Play: re-attach and show where it is instead of
            // offering the update again.
            InstallStatus.PENDING -> {
                registerListener()
                downloadProgress = null
                updateState = UpdateState.PENDING
                return
            }
            InstallStatus.DOWNLOADING -> {
                registerListener()
                downloadProgress = downloadFractionOf(info.bytesDownloaded(), info.totalBytesToDownload())
                updateState = UpdateState.DOWNLOADING
                return
            }
            InstallStatus.DOWNLOADED -> {
                registerListener()
                updateState = UpdateState.READY_TO_INSTALL
                return
            }
        }
        if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
            && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
        ) {
            pendingUpdateInfo = info
            // A background check STOPS here: no Google modal until the user taps
            // Update. A tap that had to re-query goes straight on.
            if (launchIfAvailable) launchFlow(info) else updateState = UpdateState.AVAILABLE
        } else {
            pendingUpdateInfo = null
            updateState = UpdateState.UP_TO_DATE
        }
    }

    // Main thread, state already PENDING. Spends [info]: Play refuses to launch a
    // second flow from the same AppUpdateInfo.
    private fun launchFlow(info: AppUpdateInfo) {
        val launcher = updateResultLauncher ?: return
        pendingUpdateInfo = null
        registerListener()
        val launched = appUpdateManager.startUpdateFlowForResult(
            info,
            launcher,
            AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
        )
        if (!launched) {
            unregisterListener()
            updateState = UpdateState.FAILED
        }
    }

    // True when [onAppUpdateInfo] surfaces or resumes a flow rather than UP_TO_DATE.
    private fun AppUpdateInfo.showsAFlow(): Boolean =
        installStatus() == InstallStatus.PENDING
            || installStatus() == InstallStatus.DOWNLOADING
            || installStatus() == InstallStatus.DOWNLOADED
            || (updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                && isUpdateTypeAllowed(AppUpdateType.FLEXIBLE))

    private fun registerListener() {
        if (!listenerRegistered) {
            appUpdateManager.registerListener(installStateListener)
            listenerRegistered = true
        }
    }

    private fun unregisterListener() {
        if (listenerRegistered) {
            appUpdateManager.unregisterListener(installStateListener)
            listenerRegistered = false
        }
    }

    /**
     * Finishes a downloaded update and restarts the app. A **no-op unless**
     * [updateState] is `READY_TO_INSTALL` — guards the prompt's **Restart**
     * action against firing while the install isn't actually ready (#1941).
     */
    fun completeUpdate() {
        if (destroyed) return
        if (updateState != UpdateState.READY_TO_INSTALL) return
        appUpdateManager.completeUpdate()
    }

    /** Must be called from `Activity.onDestroy()` to prevent listener leaks. */
    fun destroy() {
        destroyed = true
        inFlight = false
        pendingUpdateInfo = null
        unregisterListener()
    }

    enum class UpdateState {
        /** Nothing known yet, or a background check could not reach Play. */
        IDLE,

        /** A background check is asking Play. */
        CHECKING,

        /** An update can be started; nothing is running. */
        AVAILABLE,

        /** The user asked for the update; Google's consent modal is up, or Play has queued the download. */
        PENDING,

        /** Play is downloading; [downloadProgress] says how far, once the size is known. */
        DOWNLOADING,

        /** Downloaded; [completeUpdate] installs it and restarts the app. */
        READY_TO_INSTALL,

        /** The download, or the launch of the flow, failed; [retry] starts over. */
        FAILED,

        /** Play has no newer release for this build. */
        UP_TO_DATE,
    }

    private companion object {
        // States a resume must leave alone (see `checkForUpdate`).
        val FLOW_STATES = setOf(
            UpdateState.AVAILABLE,
            UpdateState.PENDING,
            UpdateState.DOWNLOADING,
            UpdateState.READY_TO_INSTALL,
            UpdateState.FAILED,
        )

        // One daemon thread for the whole process, started by the first check.
        // A check is rare and short, so a single thread is plenty, and its
        // `appUpdateInfo` binder calls never touch the main thread.
        val backgroundCheckExecutor: Executor by lazy {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "InAppUpdateCheck").apply { isDaemon = true }
            }
        }
    }
}

/**
 * What a Play [InstallStatus] means for the prompt, or `null` when it changes
 * nothing (`UNKNOWN`, `INSTALLING`, `REQUIRES_UI_INTENT`…).
 *
 * `CANCELED` (the user cancelled the download from Play's notification) returns
 * to a retryable `AVAILABLE`; `INSTALLED` ends the flow.
 */
@VisibleForTesting
internal fun updateStateFor(installStatus: Int): InAppUpdateManager.UpdateState? = when (installStatus) {
    InstallStatus.PENDING -> InAppUpdateManager.UpdateState.PENDING
    InstallStatus.DOWNLOADING -> InAppUpdateManager.UpdateState.DOWNLOADING
    InstallStatus.DOWNLOADED -> InAppUpdateManager.UpdateState.READY_TO_INSTALL
    InstallStatus.FAILED -> InAppUpdateManager.UpdateState.FAILED
    InstallStatus.CANCELED -> InAppUpdateManager.UpdateState.AVAILABLE
    InstallStatus.INSTALLED -> InAppUpdateManager.UpdateState.IDLE
    else -> null
}

/**
 * The downloaded fraction, `0f..1f`, or `null` while Play has not reported a
 * total size (`totalBytesToDownload` is `0` until the download really starts).
 */
@VisibleForTesting
internal fun downloadFractionOf(bytesDownloaded: Long, totalBytesToDownload: Long): Float? =
    if (totalBytesToDownload > 0L) {
        (bytesDownloaded.toDouble() / totalBytesToDownload.toDouble()).toFloat().coerceIn(0f, 1f)
    } else {
        null
    }
