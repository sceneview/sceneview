package io.github.sceneview.demo.feedback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** A finished screen + microphone recording. */
data class FeedbackRecording(
    val video: File,
    /** Audio track demuxed out of [video] for transcription; null if demux failed. */
    val audio: File?,
    val durationMs: Long,
)

/** Lifecycle of an in-app feedback recording. */
sealed interface RecordingState {
    data object Idle : RecordingState
    data object Recording : RecordingState
    data class Done(val recording: FeedbackRecording) : RecordingState
    data class Failed(val reason: String) : RecordingState
}

/**
 * Process-wide bridge between [FeedbackRecordingService] — which owns the
 * MediaProjection capture — and the Compose feedback flow.
 *
 * The feedback dialog is dismissed while recording so the user can navigate
 * the app and demonstrate the bug, so the recording state cannot live in
 * composition. The service publishes here; `MainActivity` observes it and
 * re-opens the flow at the review step once a recording is [RecordingState.Done].
 */
object FeedbackRecorder {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    /** Category of the in-progress feedback — set by the UI before recording
     *  starts, so it survives the dialog being dismissed during capture. */
    var category: FeedbackCategory? = null

    /**
     * Demo id of the screen the user is currently on — `null` on a tab screen.
     * `MainActivity` keeps this in sync with the `NavController`'s current
     * destination so that, when feedback is sent, the upload context can name
     * exactly which demo the bug was demonstrated in (#1934). Tracked process-
     * wide because the feedback dialog is dismissed during the recording, so
     * the route the user navigates to cannot be observed from composition.
     */
    @Volatile
    var currentDemoId: String? = null

    fun setRecording() {
        _state.value = RecordingState.Recording
    }

    fun setDone(recording: FeedbackRecording) {
        _state.value = RecordingState.Done(recording)
    }

    fun setFailed(reason: String) {
        _state.value = RecordingState.Failed(reason)
    }

    /** Clear the state and delete any media left by a finished recording. */
    fun reset() {
        (_state.value as? RecordingState.Done)?.recording?.let { rec ->
            rec.video.delete()
            rec.audio?.delete()
        }
        category = null
        _state.value = RecordingState.Idle
    }
}

/**
 * Delete any feedback recordings left in the cache by a previous run — call on
 * app start so a crash, an OOM-kill or a failed upload never strands a screen +
 * microphone capture on disk.
 */
fun sweepStaleFeedbackMedia(context: Context) {
    runCatching {
        context.cacheDir
            .listFiles { file -> file.name.startsWith("feedback-") }
            ?.forEach { it.delete() }
    }
}
