package io.github.sceneview.sample.common.update

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The two update messages the user can be shown. */
enum class UpdatePrompt {
    /** "Update available" + **Update** → [InAppUpdateManager.startUpdate]. */
    AVAILABLE,

    /** "Update ready" + **Restart** → [InAppUpdateManager.completeUpdate]. Stays until answered. */
    READY_TO_INSTALL,
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
 * against a `FakeAppUpdateManager`; [UpdateSnackbarEffect] only draws what [prompt] says.
 *
 * - `AVAILABLE` → [UpdatePrompt.AVAILABLE], at most once per session. Tapping **Update**
 *   starts the flexible flow; any other ending (timeout, swipe, close) is a dismissal.
 * - A dismissal is remembered for [snoozeMillis] (24 h). The window is read once, when the
 *   controller is built — i.e. per activity creation — so the prompt comes back on the first
 *   launch after the window, never in the middle of a session and never on every resume.
 * - `READY_TO_INSTALL` → [UpdatePrompt.READY_TO_INSTALL], whatever the snooze: the download
 *   the user asked for is done, and **Restart** is the only way to finish it.
 */
class UpdatePromptController(
    private val manager: InAppUpdateManager,
    private val store: UpdatePromptStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val snoozeMillis: Long = DEFAULT_SNOOZE_MILLIS,
) {

    // Read once: a dismissal inside the window keeps the "available" prompt away for this
    // whole session. `mutableStateOf` so a dismissal or an answer recomposes the reader.
    private var availableSuppressed by mutableStateOf(isInsideSnoozeWindow())

    /** The message that should be on screen right now, or `null` for none. */
    val prompt: UpdatePrompt?
        get() = when (manager.updateState) {
            InAppUpdateManager.UpdateState.AVAILABLE ->
                if (availableSuppressed) null else UpdatePrompt.AVAILABLE
            InAppUpdateManager.UpdateState.READY_TO_INSTALL -> UpdatePrompt.READY_TO_INSTALL
            else -> null
        }

    /** The user tapped the snackbar's action for [prompt]. */
    fun onAction(prompt: UpdatePrompt) {
        when (prompt) {
            UpdatePrompt.AVAILABLE -> {
                // Answered: never offered twice in one session, even if the user then
                // cancels Google's consent modal (the manager drops back to AVAILABLE).
                availableSuppressed = true
                manager.startUpdate()
            }
            UpdatePrompt.READY_TO_INSTALL -> manager.completeUpdate()
        }
    }

    /** The snackbar for [prompt] went away without its action being tapped. */
    fun onDismissed(prompt: UpdatePrompt) {
        if (prompt != UpdatePrompt.AVAILABLE) return
        store.availableDismissedAtMillis = clock()
        availableSuppressed = true
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
