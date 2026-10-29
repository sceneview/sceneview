package io.github.sceneview.demo.common

/**
 * Opens one round of the system "Speak now" dialog (`ACTION_RECOGNIZE_SPEECH`).
 * [rememberVoiceDictation] wraps an activity-result launcher; tests pass a fake.
 */
fun interface SpeechDialogLauncher {
    /** Opens the dialog. Returns false when it could not be opened. */
    fun launch(): Boolean
}

/** What a dictation shows: whether it is still going, and everything heard so far. */
data class DictationState(
    val listening: Boolean = false,
    val transcript: String = "",
)

/**
 * A dictation that keeps going through natural pauses (#4618), with no permission.
 *
 * The system dialog needs no `RECORD_AUDIO`, but Google's recognizer closes it after about
 * half a second of silence and ignores the `EXTRA_SPEECH_INPUT_*_SILENCE_LENGTH_MILLIS`
 * extras. So this session chains rounds instead: each round that comes back with text is
 * added to the transcript, and the dialog reopens at once, so a pause only means "next round".
 *
 * Chaining stops when the user cancels the dialog (back, tap outside), when a round returns
 * nothing, or when the user taps stop (also called when the app goes to the background
 * between rounds). A round returns nothing once the dialog has heard no speech for its own
 * no-speech timeout (several seconds), so a long silence ends the dictation by itself while
 * a pause between sentences only closes one round. Whatever was heard stays.
 * All calls must come from the main thread.
 */
class DictationSession(
    private val launcher: SpeechDialogLauncher,
    private val onUpdate: (DictationState) -> Unit,
) {
    private val segments = mutableListOf<String>()

    /** True from [start] until chaining stops. */
    var listening = false
        private set

    val transcript: String
        get() = segments.filter { it.isNotBlank() }.joinToString(" ")

    /** Starts a new dictation with an empty transcript. No-op while already going. */
    fun start() {
        if (listening) return
        segments.clear()
        listening = true
        if (!launcher.launch()) listening = false
        emit()
    }

    /**
     * One round came back. [text] is the recognized text, or null when the dialog returned
     * nothing; [canceled] is true for `RESULT_CANCELED` (back, tap outside the dialog).
     */
    fun onRoundResult(text: String?, canceled: Boolean) {
        if (!listening) return
        val heard = text?.takeIf { it.isNotBlank() }
        if (heard != null) segments += heard
        listening = !canceled && heard != null && launcher.launch()
        emit()
    }

    /** The user tapped stop between two rounds. Nothing reopens after this. */
    fun stop() {
        if (!listening) return
        listening = false
        emit()
    }

    private fun emit() {
        onUpdate(DictationState(listening = listening, transcript = transcript))
    }
}
