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
 * Then compose [UpdateBanner] anywhere in the activity content — it stays a no-op
 * while [updateState] is `IDLE` / `CHECKING` / `UP_TO_DATE` and only renders during
 * `DOWNLOADING` / `READY_TO_INSTALL` so it doesn't steal screen real estate.
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

    // Re-entrancy guard: a fast double-resume can land two `checkForUpdate()`
    // calls back-to-back while the first SDK round-trip is still in flight
    // (state is still CHECKING / AVAILABLE, not yet DOWNLOADING). Without this
    // flag both calls would reach `startUpdateFlow`, double-prompting the user.
    // Set on entry, cleared on BOTH the success AND failure listener so a
    // network failure can't permanently lock out future checks.
    private var inFlight = false

    private val installStateListener: InstallStateUpdatedListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADING -> {
                updateState = UpdateState.DOWNLOADING
                val totalBytes = state.totalBytesToDownload()
                if (totalBytes > 0) {
                    downloadProgress = state.bytesDownloaded().toFloat() / totalBytes.toFloat()
                }
            }
            InstallStatus.DOWNLOADED -> {
                updateState = UpdateState.READY_TO_INSTALL
            }
            InstallStatus.FAILED -> {
                updateState = UpdateState.IDLE
                unregisterListener()
            }
            InstallStatus.INSTALLED -> {
                updateState = UpdateState.IDLE
                unregisterListener()
            }
            InstallStatus.CANCELED -> {
                updateState = UpdateState.IDLE
                unregisterListener()
            }
            else -> {}
        }
    }

    fun checkForUpdate() {
        // Early-return if a flow is already in progress: a second `onResume`
        // landing here while we're DOWNLOADING / READY_TO_INSTALL would call
        // `startUpdateFlow` again, re-prompting the user mid-download. Play
        // Core surfaces this via `info.updateAvailability() ==
        // DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS` AFTER the fact, but bailing
        // out before the SDK round-trip is cheaper.
        if (updateState == UpdateState.DOWNLOADING
            || updateState == UpdateState.READY_TO_INSTALL
        ) return

        // Re-entrancy guard for the CHECKING / AVAILABLE window: those states
        // aren't covered by the early-return above, so a second `onResume`
        // arriving before the SDK round-trip resolves would issue a parallel
        // `appUpdateInfo` request and a duplicate `startUpdateFlow`.
        if (inFlight) return
        inFlight = true

        updateState = UpdateState.CHECKING
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                inFlight = false
                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                ) {
                    updateState = UpdateState.AVAILABLE
                    startFlexibleUpdate(info)
                } else {
                    updateState = UpdateState.UP_TO_DATE
                }
            }
            .addOnFailureListener {
                inFlight = false
                updateState = UpdateState.IDLE
            }
    }

    private fun startFlexibleUpdate(info: AppUpdateInfo) {
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

    fun completeUpdate() {
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
                updateState = UpdateState.READY_TO_INSTALL
            }
        }
    }

    /** Must be called from `Activity.onDestroy()` to prevent listener leaks. */
    fun destroy() {
        unregisterListener()
    }

    enum class UpdateState {
        IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY_TO_INSTALL, UP_TO_DATE
    }
}
