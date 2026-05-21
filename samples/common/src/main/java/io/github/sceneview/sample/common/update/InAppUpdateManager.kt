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

/**
 * Shared in-app update manager for every Android-host SceneView sample.
 *
 * Wraps Google Play Core's `AppUpdateManager` with a Compose-friendly
 * [updateState] + [downloadProgress] pair driven by [InstallStateUpdatedListener].
 *
 * ## Demo-UI-native flexible flow (#1941)
 *
 * The Google Play consent modal is shown **exactly once**, only after a
 * deliberate user tap — never unprompted on resume:
 *
 * 1. [checkForUpdate] runs on every `onResume`. On `UPDATE_AVAILABLE` it sets
 *    [updateState] to `AVAILABLE` and **stops** — it does NOT pop the Google
 *    modal. The detected [AppUpdateInfo] is stashed for later.
 * 2. [UpdateBanner] renders an integrated "A new version is available — Update"
 *    card. Only the user's tap on that in-app **Update** button calls
 *    [startUpdate], which triggers Google's single consent modal.
 * 3. Download progress and the "Restart" prompt are the demo's own Material 3
 *    surfaces — no further Google modals.
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
 *     updateManager.checkForStalledUpdate()
 *     updateManager.checkForUpdate()
 * }
 * override fun onDestroy() {
 *     super.onDestroy()
 *     updateManager.destroy()
 * }
 * ```
 *
 * Then compose [UpdateBanner] anywhere in the activity content. The in-app
 * **Update** button it renders calls [startUpdate] — that is the single tap
 * that pops Google's consent modal. The banner stays a no-op while
 * [updateState] is `IDLE` / `CHECKING` / `UP_TO_DATE` and renders during
 * `AVAILABLE` / `DOWNLOADING` / `READY_TO_INSTALL`:
 *
 * ```kotlin
 * UpdateBanner(updateManager = updateManager)
 * ```
 *
 * Uses [AppUpdateType.FLEXIBLE] (background download + user-driven restart) — see
 * <https://developer.android.com/guide/playcore/in-app-updates>. The Play SDK
 * compares the installed version against the Play Store track automatically, so
 * there is no `VERSION_NAME` plumbing to wire here.
 *
 * ## Threading
 *
 * [inFlight], [pendingUpdateInfo], [listenerRegistered] and [destroyed] are
 * plain (non-`@Volatile`) fields: all mutations are main-thread-confined. Play
 * Core posts every `appUpdateInfo` success/failure callback and every
 * [InstallStateUpdatedListener] event to the main `Looper`, and the public
 * methods are all called from `Activity` lifecycle / Compose, so no two threads
 * ever touch this state.
 */
class InAppUpdateManager(
    private val activity: Activity,
    // Hook for unit tests: a `FakeAppUpdateManager` can be passed instead of
    // the production factory result. The single-argument public constructor
    // below uses `AppUpdateManagerFactory.create(activity)` so callers don't
    // have to know this exists.
    private val appUpdateManager: AppUpdateManager,
) {

    constructor(activity: Activity) : this(activity, AppUpdateManagerFactory.create(activity))

    var updateState by mutableStateOf(UpdateState.IDLE)
        private set

    var downloadProgress by mutableStateOf(0f)
        private set

    private var listenerRegistered = false

    // The `UPDATE_AVAILABLE` AppUpdateInfo detected by `checkForUpdate()`,
    // stashed so the user's later `startUpdate()` tap can hand it to
    // `startUpdateFlowForResult` without re-querying the SDK. Kept alive until
    // the download is confirmed started (state DOWNLOADING) so a cancelled
    // consent modal can still be retried from the in-app Update button.
    private var pendingUpdateInfo: AppUpdateInfo? = null

    // Re-entrancy guard. Set true on entry to `checkForUpdate()` AND held true
    // for the whole `AVAILABLE` window and the entire `startUpdate()` flow,
    // so a fast double-resume can never issue a parallel `appUpdateInfo`
    // request or a duplicate `startUpdateFlowForResult`. Cleared only when the
    // flow definitively resolves: UP_TO_DATE, a failed round-trip, a started
    // download, a cancelled consent modal, or destruction.
    private var inFlight = false

    // Set in `destroy()`. Every async Play Task callback early-returns on this
    // so a late `appUpdateInfo` / install-state event arriving after
    // `onDestroy()` can't mutate Compose state or re-register the listener.
    private var destroyed = false

    // The activity-result launcher for Google's consent modal. Registered by
    // `registerForResult()` from the host activity's `onCreate`. The FLEXIBLE
    // consent modal's CANCEL is delivered HERE (RESULT_CANCELED), not via the
    // install-state listener — without this launcher a cancel would leave the
    // banner's Update button a permanent no-op.
    private var updateResultLauncher: ActivityResultLauncher<IntentSenderRequest>? = null

    private val installStateListener: InstallStateUpdatedListener = InstallStateUpdatedListener { state ->
        if (destroyed) return@InstallStateUpdatedListener
        when (state.installStatus()) {
            InstallStatus.DOWNLOADING -> {
                // The download has definitively started: the user accepted the
                // Google modal, so release the re-entrancy guard and drop the
                // stash — there is nothing left to retry.
                inFlight = false
                pendingUpdateInfo = null
                updateState = UpdateState.DOWNLOADING
                val totalBytes = state.totalBytesToDownload()
                if (totalBytes > 0) {
                    downloadProgress = state.bytesDownloaded().toFloat() / totalBytes.toFloat()
                }
            }
            InstallStatus.DOWNLOADED -> {
                inFlight = false
                pendingUpdateInfo = null
                updateState = UpdateState.READY_TO_INSTALL
                // Keep the listener registered — it must still be live to
                // observe INSTALLED after `completeUpdate()` (#1941).
            }
            InstallStatus.FAILED -> {
                inFlight = false
                updateState = UpdateState.IDLE
                unregisterListener()
            }
            InstallStatus.INSTALLED -> {
                inFlight = false
                updateState = UpdateState.IDLE
                unregisterListener()
            }
            InstallStatus.CANCELED -> {
                // The user dismissed the Google consent modal. Drop back to
                // AVAILABLE so the in-app banner stays and the user can retry.
                inFlight = false
                updateState = UpdateState.AVAILABLE
            }
            else -> {}
        }
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
     * a cancel would strand the manager with `inFlight == true` and the
     * banner's Update button would be a permanent no-op.
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
        if (result.resultCode == Activity.RESULT_OK) {
            // The user accepted: the flexible download proceeds and the
            // install-state listener takes over from DOWNLOADING onwards.
            // `inFlight` stays true until DOWNLOADING confirms the start.
            return
        }
        // RESULT_CANCELED (or any non-OK code): the user dismissed the consent
        // modal. Reset to a retryable AVAILABLE and KEEP `pendingUpdateInfo`
        // so the in-app Update button works again.
        inFlight = false
        if (pendingUpdateInfo != null) {
            updateState = UpdateState.AVAILABLE
        }
    }

    /**
     * Queries the Play Store for a newer release. Safe to call on every
     * `onResume`. On `UPDATE_AVAILABLE` it sets [updateState] to `AVAILABLE`
     * and stops — it does **not** start the Google consent flow. Call
     * [startUpdate] from a deliberate user tap to do that.
     */
    fun checkForUpdate() {
        if (destroyed) return
        // Early-return once a flow has been surfaced or is running: AVAILABLE,
        // DOWNLOADING and READY_TO_INSTALL all mean a flow is already live, so a
        // second `onResume` landing here must not re-query or re-prompt (#1941).
        if (updateState == UpdateState.AVAILABLE
            || updateState == UpdateState.DOWNLOADING
            || updateState == UpdateState.READY_TO_INSTALL
        ) return

        // Re-entrancy guard for the CHECKING window and the in-progress
        // `startUpdate()` flow: a second `onResume` arriving before either
        // resolves would otherwise issue a parallel `appUpdateInfo` request.
        if (inFlight) return
        inFlight = true

        updateState = UpdateState.CHECKING
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (destroyed) return@addOnSuccessListener
                when {
                    // An update is already mid-download (e.g. the manager was
                    // recreated by a rotation while the flexible download ran).
                    // Re-attach the install-state listener and resume the
                    // DOWNLOADING state instead of re-popping the consent modal.
                    info.installStatus() == InstallStatus.DOWNLOADING -> {
                        registerListener()
                        updateState = UpdateState.DOWNLOADING
                        // `inFlight` left true: the live listener owns the
                        // flow until DOWNLOADED / FAILED resolves it.
                    }
                    info.installStatus() == InstallStatus.DOWNLOADED -> {
                        registerListener()
                        inFlight = false
                        updateState = UpdateState.READY_TO_INSTALL
                    }
                    info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                        && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> {
                        // Stash the info and surface the in-app banner. STOP
                        // here: no Google modal until the user taps Update.
                        // `inFlight` stays true through the AVAILABLE window —
                        // a second resume is already blocked by the AVAILABLE
                        // early-return above.
                        pendingUpdateInfo = info
                        updateState = UpdateState.AVAILABLE
                    }
                    else -> {
                        inFlight = false
                        updateState = UpdateState.UP_TO_DATE
                    }
                }
            }
            .addOnFailureListener {
                if (destroyed) return@addOnFailureListener
                inFlight = false
                updateState = UpdateState.IDLE
            }
    }

    /**
     * Starts the Play in-app update flow. Triggers Google's **single** consent
     * modal — call this **only** from a deliberate user tap (the in-app
     * "Update" button in [UpdateBanner]), never automatically.
     *
     * A no-op unless [updateState] is `AVAILABLE` with a stashed
     * [AppUpdateInfo], so a double tap or a stray call cannot double-prompt.
     * [pendingUpdateInfo] is intentionally **kept** here — it is cleared only
     * once the download is confirmed started (state DOWNLOADING), so a
     * cancelled consent modal can be retried from the same Update button.
     */
    fun startUpdate() {
        if (destroyed) return
        if (updateState != UpdateState.AVAILABLE) return
        val info = pendingUpdateInfo ?: return
        val launcher = updateResultLauncher ?: return

        registerListener()
        // Flip out of AVAILABLE immediately so a fast double tap is a no-op
        // (the `updateState != AVAILABLE` guard above swallows it). The stash
        // is retained for a CANCELED retry; `onUpdateFlowResult` restores
        // AVAILABLE if the user dismisses the modal.
        updateState = UpdateState.CHECKING
        appUpdateManager.startUpdateFlowForResult(
            info,
            launcher,
            AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
        )
    }

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
     * [updateState] is `READY_TO_INSTALL` — guards the [UpdateBanner] "Restart"
     * button against firing while the install isn't actually ready (#1941).
     */
    fun completeUpdate() {
        if (destroyed) return
        if (updateState != UpdateState.READY_TO_INSTALL) return
        appUpdateManager.completeUpdate()
    }

    /**
     * Picks up an update that was already downloaded — or is still downloading —
     * from a previous foreground (e.g. the user backgrounded the app
     * mid-install, or a rotation recreated the activity). Should be called from
     * `onResume()` *before* [checkForUpdate].
     */
    fun checkForStalledUpdate() {
        if (destroyed) return
        // Hold the re-entrancy guard for the round-trip so a `checkForUpdate()`
        // landing in the same `onResume` can't race a parallel `appUpdateInfo`
        // request. Released in the callback unless an in-progress download is
        // picked up (then the live listener owns the flow).
        if (inFlight) return
        inFlight = true
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            if (destroyed) return@addOnSuccessListener
            when (info.installStatus()) {
                InstallStatus.DOWNLOADED -> {
                    // Re-register so the post-`completeUpdate()` INSTALLED event
                    // is still observed on a fresh manager instance.
                    registerListener()
                    inFlight = false
                    updateState = UpdateState.READY_TO_INSTALL
                }
                InstallStatus.DOWNLOADING -> {
                    // A flexible download is still running (rotation mid-download).
                    // Re-attach the listener and resume the DOWNLOADING state so
                    // the recreated manager tracks it instead of showing "Update".
                    // `inFlight` stays true: the listener owns the flow.
                    registerListener()
                    updateState = UpdateState.DOWNLOADING
                }
                else -> {
                    inFlight = false
                }
            }
        }.addOnFailureListener {
            if (destroyed) return@addOnFailureListener
            inFlight = false
        }
    }

    /** Must be called from `Activity.onDestroy()` to prevent listener leaks. */
    fun destroy() {
        destroyed = true
        inFlight = false
        pendingUpdateInfo = null
        unregisterListener()
    }

    enum class UpdateState {
        IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY_TO_INSTALL, UP_TO_DATE
    }
}
