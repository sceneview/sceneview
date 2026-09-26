package io.github.sceneview.sample.common.update

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The update message on screen. One prompt moves through these in place, so a tap on
 * **Update** is answered in the same frame and something is always visible until the
 * update is ready or the user lets it go.
 */
sealed interface UpdatePrompt {
    /** "Update available" + **Update** → [InAppUpdateManager.startUpdate]. Dismissible. */
    data object Available : UpdatePrompt

    /** "Waiting for Google Play…", indeterminate: the consent modal is up, or Play has queued the download. */
    data object Waiting : UpdatePrompt

    /**
     * "Downloading update…" with a determinate indicator and a percentage once [progress]
     * is known (`0f..1f`), indeterminate while it is `null` (Play has not reported a size).
     */
    data class Downloading(val progress: Float?) : UpdatePrompt

    /** "Update ready" + **Restart** → [InAppUpdateManager.completeUpdate]. Stays until answered. */
    data object ReadyToInstall : UpdatePrompt

    /** "Update failed" + **Retry** → [InAppUpdateManager.retry]. Dismissible. */
    data object Failed : UpdatePrompt
}

/** Where the "available" dismissal is remembered across launches. */
interface UpdatePromptStore {
    /** Epoch millis of the last dismissal of the "available" prompt, `0` if never. */
    var availableDismissedAtMillis: Long
}

/** [UpdatePromptStore] backed by the app's private `SharedPreferences`. */
class SharedPreferencesUpdatePromptStore(context: Context) : UpdatePromptStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var availableDismissedAtMillis: Long
        get() = prefs.getLong(KEY_DISMISSED_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_DISMISSED_AT, value).apply()
        }

    private companion object {
        const val PREFS_NAME = "sceneview_update_prompt"
        const val KEY_DISMISSED_AT = "available_dismissed_at"
    }
}

/**
 * Decides which update message is on screen, and what answering it does.
 *
 * A plain class — no Compose UI, no Activity — so the whole policy runs on the JVM
 * against a `FakeAppUpdateManager`; the host's prompt composable only draws what [prompt]
 * says and reports taps back through [onAction] / [onDismissed].
 *
 * - `AVAILABLE` → [UpdatePrompt.Available]. Tapping **Update** starts the flexible flow;
 *   any other ending (timeout, close) is a dismissal. A cancelled consent modal brings the
 *   offer back — it is still the user's to take.
 * - A dismissal is remembered for [snoozeMillis] (24 h). The window is read once, when the
 *   controller is built — i.e. per activity creation — so the prompt comes back on the first
 *   launch after the window, never in the middle of a session and never on every resume.
 * - `PENDING` / `DOWNLOADING` → [UpdatePrompt.Waiting] / [UpdatePrompt.Downloading], whatever
 *   the snooze: the user asked for this download and watches it run.
 * - `READY_TO_INSTALL` → [UpdatePrompt.ReadyToInstall], whatever the snooze: **Restart** is the
 *   only way to finish the download the user asked for.
 * - `FAILED` → [UpdatePrompt.Failed]; **Retry** starts over, closing it forgets the failure.
 */
class UpdatePromptController(
    private val manager: InAppUpdateManager,
    private val store: UpdatePromptStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val snoozeMillis: Long = DEFAULT_SNOOZE_MILLIS,
) {

    // Read once: a dismissal inside the window keeps the "available" prompt away for this
    // whole session. `mutableStateOf` so a dismissal recomposes the reader.
    private var availableSuppressed by mutableStateOf(isInsideSnoozeWindow())

    /** The message that should be on screen right now, or `null` for none. */
    val prompt: UpdatePrompt?
        get() = promptFor(manager.updateState, manager.downloadProgress, availableSuppressed)

    /** The user tapped the action of [prompt]. */
    fun onAction(prompt: UpdatePrompt) {
        when (prompt) {
            UpdatePrompt.Available -> manager.startUpdate()
            UpdatePrompt.ReadyToInstall -> manager.completeUpdate()
            UpdatePrompt.Failed -> manager.retry()
            // Progress has no action.
            UpdatePrompt.Waiting, is UpdatePrompt.Downloading -> {}
        }
    }

    /** [prompt] went away without its action being tapped (timeout, close). */
    fun onDismissed(prompt: UpdatePrompt) {
        when (prompt) {
            UpdatePrompt.Available -> {
                store.availableDismissedAtMillis = clock()
                availableSuppressed = true
            }
            UpdatePrompt.Failed -> manager.dismissFailure()
            // Not dismissible: the user asked for this download.
            UpdatePrompt.Waiting, is UpdatePrompt.Downloading, UpdatePrompt.ReadyToInstall -> {}
        }
    }

    private fun isInsideSnoozeWindow(): Boolean {
        val dismissedAt = store.availableDismissedAtMillis
        if (dismissedAt <= 0L) return false
        val elapsed = clock() - dismissedAt
        // A clock that went backwards (elapsed < 0) must not snooze forever.
        return elapsed in 0 until snoozeMillis
    }

    companion object {
        /** How long a dismissed "Update available" stays away: until the first launch after 24 h. */
        const val DEFAULT_SNOOZE_MILLIS: Long = 24L * 60 * 60 * 1000
    }
}

/**
 * The prompt for a manager [state] — the whole state → UI mapping, pure so it is tested
 * on its own. [progress] is [InAppUpdateManager.downloadProgress]; [availableSuppressed]
 * is the snooze, which only ever hides the unsolicited offer.
 */
internal fun promptFor(
    state: InAppUpdateManager.UpdateState,
    progress: Float?,
    availableSuppressed: Boolean,
): UpdatePrompt? = when (state) {
    InAppUpdateManager.UpdateState.AVAILABLE -> if (availableSuppressed) null else UpdatePrompt.Available
    InAppUpdateManager.UpdateState.PENDING -> UpdatePrompt.Waiting
    InAppUpdateManager.UpdateState.DOWNLOADING -> UpdatePrompt.Downloading(progress)
    InAppUpdateManager.UpdateState.READY_TO_INSTALL -> UpdatePrompt.ReadyToInstall
    InAppUpdateManager.UpdateState.FAILED -> UpdatePrompt.Failed
    InAppUpdateManager.UpdateState.IDLE,
    InAppUpdateManager.UpdateState.CHECKING,
    InAppUpdateManager.UpdateState.UP_TO_DATE,
    -> null
}
