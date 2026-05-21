package io.github.sceneview.sample.common.update

import android.app.Activity
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
 * Then compose [UpdateBanner] anywhere in the activity content — it stays a
 * no-op while [updateState] is `IDLE` / `CHECKING` / `UP_TO_DATE` and renders
 * during `AVAILABLE` / `DOWNLOADING` / `READY_TO_INSTALL`.
 *
 * Uses [AppUpdateType.FLEXIBLE] (background download + user-driven restart) — see
 * <https://developer.android.com/guide/playcore/in-app-updates>. The Play SDK
 * compares the installed version against the Play Store track automatically, so
 * there is no `VERSION_NAME` plumbing to wire here.
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
    // `startUpdateFlow` without re-querying the SDK.
    private var pendingUpdateInfo: AppUpdateInfo? = null

    // Re-entrancy guard. Set true on entry to `checkForUpdate()` AND held true
    // for the whole `AVAILABLE` window and the entire `startUpdate()` flow,
    // so a fast double-resume can never issue a parallel `appUpdateInfo`
    // request or a duplicate `startUpdateFlow`. Cleared only when the flow
    // definitively resolves: UP_TO_DATE, a failed round-trip, a started
    // download, or destruction.
    private var inFlight = false

    private val installStateListener: InstallStateUpdatedListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADING -> {
                // The download has definitively started: the user accepted the
                // Google modal, so release the re-entrancy guard.
                inFlight = false
                updateState = UpdateState.DOWNLOADING
                val totalBytes = state.totalBytesToDownload()
                if (totalBytes > 0) {
                    downloadProgress = state.bytesDownloaded().toFloat() / totalBytes.toFloat()
                }
            }
            InstallStatus.DOWNLOADED -> {
                inFlight = false
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
     * Queries the Play Store for a newer release. Safe to call on every
     * `onResume`. On `UPDATE_AVAILABLE` it sets [updateState] to `AVAILABLE`
     * and stops — it does **not** start the Google consent flow. Call
     * [startUpdate] from a deliberate user tap to do that.
     */
    fun checkForUpdate() {
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
                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                ) {
                    // Stash the info and surface the in-app banner. STOP here:
                    // no Google modal until the user taps Update. `inFlight`
                    // stays true through the AVAILABLE window — a second resume
                    // is already blocked by the AVAILABLE early-return above.
                    pendingUpdateInfo = info
                    updateState = UpdateState.AVAILABLE
                } else {
                    inFlight = false
                    updateState = UpdateState.UP_TO_DATE
                }
            }
            .addOnFailureListener {
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
     */
    fun startUpdate() {
        if (updateState != UpdateState.AVAILABLE) return
        val info = pendingUpdateInfo ?: return
        // Drop the stash immediately: a second tap before DOWNLOADING fires
        // would otherwise re-enter and pop a second Google modal.
        pendingUpdateInfo = null

        if (!listenerRegistered) {
            appUpdateManager.registerListener(installStateListener)
            listenerRegistered = true
        }
        appUpdateManager.startUpdateFlow(
            info,
            activity,
            AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
        )
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
        if (updateState != UpdateState.READY_TO_INSTALL) return
        appUpdateManager.completeUpdate()
    }

    /**
     * Picks up an update that was already downloaded in a previous session
     * (e.g. user backgrounded the app mid-install). Should be called from
     * `onResume()` *before* [checkForUpdate].
     */
    fun checkForStalledUpdate() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                // Re-register so the post-`completeUpdate()` INSTALLED event is
                // still observed on a fresh manager instance.
                if (!listenerRegistered) {
                    appUpdateManager.registerListener(installStateListener)
                    listenerRegistered = true
                }
                updateState = UpdateState.READY_TO_INSTALL
            }
        }
    }

    /** Must be called from `Activity.onDestroy()` to prevent listener leaks. */
    fun destroy() {
        inFlight = false
        pendingUpdateInfo = null
        unregisterListener()
    }

    enum class UpdateState {
        IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY_TO_INSTALL, UP_TO_DATE
    }
}
