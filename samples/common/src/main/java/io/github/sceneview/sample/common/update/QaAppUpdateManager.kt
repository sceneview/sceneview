package io.github.sceneview.sample.common.update

import android.app.Activity
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
 * the real prompt run end to end. The fake starts with an update available; tapping
 * **Update** plays the part of Google's consent modal and of Play, paced so every state is
 * on screen long enough to be seen and captured:
 *
 * - [Scenario.AVAILABLE] — the user accepts, Play queues the download (`PENDING`), starts it
 *   before knowing its size, then reports bytes from 0 to 100 % over a few seconds, and ends
 *   in `DOWNLOADED` → "Update ready". **Restart** completes the fake install, which ends the
 *   flow (a real update would restart the app here).
 * - [Scenario.CANCEL] — the first consent modal is cancelled (`RESULT_CANCELED`, delivered
 *   through the same entry point as the real activity result) → back to "Update available";
 *   the next tap is accepted and runs the download above.
 * - [Scenario.FAIL] — the first download fails part-way → "Update failed · Retry"; the retry
 *   runs the download above.
 *
 * **Process-scoped** ([forProcess]): the fake plays Play, and Play outlives an activity. A
 * recreated activity builds a new [InAppUpdateManager] on the same fake, whose first check
 * re-attaches to the download in flight.
 *
 * Hosts gate it on `BuildConfig.DEBUG` and the `update_qa` intent extra; it must never
 * reach a user.
 */
class QaAppUpdateManager private constructor(
    private val fake: FakeAppUpdateManager,
    val scenario: Scenario,
) : AppUpdateManager by fake {

    /** The flows `--es update_qa <id>` can drive. */
    enum class Scenario(val id: String) {
        AVAILABLE("available"),
        CANCEL("cancel"),
        FAIL("fail"),
        ;

        companion object {
            /** The scenario named by the `update_qa` extra, or `null` for none / an unknown id. */
            fun fromId(id: String?): Scenario? = entries.firstOrNull { it.id == id }
        }
    }

    /**
     * Where the answer of the pretend consent modal goes: the manager built on this fake
     * points it at its own activity-result handler. The real modal answers through the
     * activity result, which the fake never drives.
     */
    internal var consentResults: ((Int) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    // The scripted twist (cancel, failure) happens once; every later attempt succeeds.
    private var twistPlayed = false

    override fun startUpdateFlowForResult(
        appUpdateInfo: AppUpdateInfo,
        activityResultLauncher: ActivityResultLauncher<IntentSenderRequest>,
        options: AppUpdateOptions,
    ): Boolean {
        val started = fake.startUpdateFlowForResult(appUpdateInfo, activityResultLauncher, options)
        if (!started) return started
        val twist = !twistPlayed && scenario != Scenario.AVAILABLE
        twistPlayed = true
        // Google's consent modal would be on screen for this long.
        main.postDelayed({
            if (twist && scenario == Scenario.CANCEL) {
                fake.userRejectsUpdate()
                consentResults?.invoke(Activity.RESULT_CANCELED)
            } else {
                fake.userAcceptsUpdate()
                consentResults?.invoke(Activity.RESULT_OK)
                playDownload(failPartWay = twist && scenario == Scenario.FAIL)
            }
        }, CONSENT_MILLIS)
        return started
    }

    // PENDING (queued) → DOWNLOADING with no size yet → bytes 0 → 100 % → DOWNLOADED.
    private fun playDownload(failPartWay: Boolean) {
        var at = PENDING_MILLIS
        main.postDelayed({ fake.downloadStarts() }, at)
        at += SIZE_UNKNOWN_MILLIS
        main.postDelayed({ fake.setTotalBytesToDownload(DOWNLOAD_BYTES) }, at)
        for (step in 0..PROGRESS_STEPS) {
            if (failPartWay && step == FAIL_AT_STEP) {
                main.postDelayed({ fake.downloadFails() }, at)
                return
            }
            val bytes = DOWNLOAD_BYTES * step / PROGRESS_STEPS
            main.postDelayed({ fake.setBytesDownloaded(bytes) }, at)
            at += PROGRESS_STEP_MILLIS
        }
        main.postDelayed({ fake.downloadCompletes() }, at)
    }

    override fun completeUpdate(): Task<Void> {
        val task = fake.completeUpdate()
        main.post { fake.installCompletes() }
        return task
    }

    companion object {
        private const val QA_AVAILABLE_VERSION_CODE = Int.MAX_VALUE
        private const val DOWNLOAD_BYTES = 48_000_000L
        private const val CONSENT_MILLIS = 1_200L
        private const val PENDING_MILLIS = 1_500L
        private const val SIZE_UNKNOWN_MILLIS = 1_000L
        private const val PROGRESS_STEPS = 40
        private const val PROGRESS_STEP_MILLIS = 150L // 40 steps: 0 → 100 % in 6 s
        private const val FAIL_AT_STEP = 25

        private var process: QaAppUpdateManager? = null

        /**
         * The process's QA fake for [scenario]: the same instance for every activity of the
         * process, so a recreated activity finds the download where it was. A different
         * scenario (a new QA launch without a process restart) starts a new fake.
         */
        fun forProcess(context: Context, scenario: Scenario): QaAppUpdateManager {
            process?.takeIf { it.scenario == scenario }?.let { return it }
            val fake = FakeAppUpdateManager(context.applicationContext)
            fake.setUpdateAvailable(QA_AVAILABLE_VERSION_CODE)
            return QaAppUpdateManager(fake, scenario).also { process = it }
        }
    }
}
