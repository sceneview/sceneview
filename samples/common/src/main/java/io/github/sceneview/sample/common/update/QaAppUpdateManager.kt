package io.github.sceneview.sample.common.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.tasks.Task
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager

/**
 * A Play update that can be driven on an emulator, for **debug QA only**.
 *
 * A real in-app update needs a Play-installed build and a newer release on its track, so
 * neither an emulator nor a sideloaded debug APK can ever see one. This wraps Google's own
 * `FakeAppUpdateManager` (shipped inside `app-update`) so the real [InAppUpdateManager] and
 * the real snackbar run end to end:
 *
 * - the fake starts with an update available;
 * - tapping **Update** plays the part of the user accepting Google's consent modal, then a
 *   short fake download, which ends in `DOWNLOADED` → the "Update ready" snackbar;
 * - tapping **Restart** completes the fake install, which ends the flow (a real update
 *   would restart the app here).
 *
 * Hosts gate it on `BuildConfig.DEBUG` and a QA intent extra; it must never reach a user.
 */
class QaAppUpdateManager private constructor(
    private val fake: FakeAppUpdateManager,
) : AppUpdateManager by fake {

    constructor(context: Context) : this(FakeAppUpdateManager(context.applicationContext)) {
        fake.setUpdateAvailable(QA_AVAILABLE_VERSION_CODE)
    }

    private val main = Handler(Looper.getMainLooper())

    override fun startUpdateFlowForResult(
        appUpdateInfo: AppUpdateInfo,
        activityResultLauncher: ActivityResultLauncher<IntentSenderRequest>,
        options: AppUpdateOptions,
    ): Boolean {
        val started = fake.startUpdateFlowForResult(appUpdateInfo, activityResultLauncher, options)
        if (started) {
            main.post {
                fake.userAcceptsUpdate()
                fake.setTotalBytesToDownload(QA_DOWNLOAD_BYTES)
                fake.downloadStarts()
            }
            main.postDelayed({ fake.downloadCompletes() }, QA_DOWNLOAD_MILLIS)
        }
        return started
    }

    override fun completeUpdate(): Task<Void> {
        val task = fake.completeUpdate()
        main.post { fake.installCompletes() }
        return task
    }

    private companion object {
        const val QA_AVAILABLE_VERSION_CODE = Int.MAX_VALUE
        const val QA_DOWNLOAD_BYTES = 1_000_000L
        const val QA_DOWNLOAD_MILLIS = 1_500L
    }
}
