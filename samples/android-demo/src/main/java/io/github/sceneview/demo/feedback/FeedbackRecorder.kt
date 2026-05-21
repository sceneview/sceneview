package io.github.sceneview.demo.feedback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** A finished screen + microphone recording. */
data class FeedbackRecording(
    val video: File,
    /** Audio track demuxed out of [video] for transcription; null if demux failed. */
    val audio: File?,
    val durationMs: Long,
    /** True when the clip was auto-stopped at the size/duration cap (#1933 review). */
    val capped: Boolean = false,
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

    /**
     * Monotonic id for the current recording attempt. Bumped on every
     * [setRecording]; the recording service captures it before its background
     * demux and a [setDone] / [setFailed] from a superseded attempt — e.g. when
     * the user re-records quickly — is dropped rather than clobbering the new
     * state (#1933 review).
     */
    private val sessionCounter = AtomicLong(0L)

    /** Begin a fresh recording attempt and return its session id. */
    fun beginSession(): Long {
        val id = sessionCounter.incrementAndGet()
        _state.value = RecordingState.Recording
        return id
    }

    /** Current (latest) recording session id. */
    val currentSession: Long get() = sessionCounter.get()

    /** True when [session] is still the active attempt. */
    fun isCurrentSession(session: Long): Boolean = session == sessionCounter.get()

    /** Category of the in-progress feedback — set by the UI before recording
     *  starts, so it survives the dialog being dismissed during capture.
     *  Mirrored to [FeedbackCategoryStore] so it also survives process death
     *  during the MediaProjection consent dialog (#1933 review). */
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

    /**
     * True while an upload coroutine still holds the recording files open.
     * [reset] must not delete media in this window — a dialog dismiss during
     * SENDING would otherwise pull the file out from under an in-flight read
     * (#1930 review). The upload path clears it in a `finally` and deletes the
     * media itself once it is done.
     */
    @Volatile
    private var uploadInProgress = false

    /** Mark that an upload has started reading the recording media. */
    fun beginUpload() {
        uploadInProgress = true
    }

    /**
     * Mark the upload finished and release the recording media. Call from the
     * upload coroutine's `finally`. Deletes the files if a [reset] was requested
     * while the upload was in flight.
     */
    fun endUpload() {
        uploadInProgress = false
        val deferred = mediaDeferredForDeletion
        if (deferred != null) {
            mediaDeferredForDeletion = null
            deferred.video.delete()
            deferred.audio?.delete()
        }
    }

    /** Recording whose deletion was deferred because an upload held it open. */
    @Volatile
    private var mediaDeferredForDeletion: FeedbackRecording? = null

    /** Marks the state as recording without minting a new session (legacy). */
    fun setRecording() {
        _state.value = RecordingState.Recording
    }

    /**
     * Publish a finished recording. Ignored when [session] is not the active
     * attempt — a stale demux thread from a superseded recording must not
     * clobber the current state.
     */
    fun setDone(recording: FeedbackRecording, session: Long = sessionCounter.get()) {
        if (session != sessionCounter.get()) {
            // Superseded attempt — discard its media and drop the callback.
            recording.video.delete()
            recording.audio?.delete()
            return
        }
        _state.value = RecordingState.Done(recording)
    }

    /** Publish a recording failure. Ignored when [session] is superseded. */
    fun setFailed(reason: String, session: Long = sessionCounter.get()) {
        if (session != sessionCounter.get()) return
        _state.value = RecordingState.Failed(reason)
    }

    /**
     * Clear the state and delete any media left by a finished recording.
     *
     * If an upload is currently reading the media ([uploadInProgress]), the
     * deletion is deferred — [endUpload] performs it once the upload coroutine
     * has released the files. This prevents a dialog dismiss during SENDING from
     * deleting the file out from under an in-flight read (#1930 review).
     */
    fun reset() {
        val recording = (_state.value as? RecordingState.Done)?.recording
        if (uploadInProgress && recording != null) {
            // An upload is still reading these files — hand them to endUpload()
            // for deletion instead of pulling them now.
            mediaDeferredForDeletion = recording
        } else {
            recording?.let { rec ->
                rec.video.delete()
                rec.audio?.delete()
            }
        }
        category = null
        _state.value = RecordingState.Idle
    }
}

/**
 * Process-wide one-shot signal asking the host to open the [FeedbackFlow].
 *
 * The feedback flow itself is owned by `SceneViewDemoApp` (it wraps the whole
 * `NavHost` in a `Box`), but a feedback entry point also lives in `DemoScaffold`
 * — the shared scaffold for every demo screen (#1930 requires the button "on
 * the 4 tabs AND inside every demo"). A demo's top-app-bar feedback action has
 * no direct handle on the host's `feedbackOpen` state, so it raises this flag;
 * `SceneViewDemoApp` observes it, opens the flow, and clears it.
 */
object FeedbackOpenRequest {
    private val _requested = MutableStateFlow(false)
    val requested: StateFlow<Boolean> = _requested.asStateFlow()

    /** Ask the host to open the feedback flow. */
    fun request() {
        _requested.value = true
    }

    /** Clear the flag once the host has handled it. */
    fun consume() {
        _requested.value = false
    }
}

/**
 * Persists the in-flight feedback [FeedbackCategory] across process death.
 *
 * The MediaProjection consent dialog is a separate system activity; on a
 * memory-tight device the demo process can be killed while it is up. The
 * category lives on [FeedbackRecorder] (process memory) and would be lost — an
 * "Idea" would silently become a "Bug" on the way back. This small prefs mirror
 * survives that (#1933 review).
 */
object FeedbackCategoryStore {
    private const val PREFS = "sceneview_feedback"
    private const val KEY_CATEGORY = "inflight_category"

    fun save(context: Context, category: FeedbackCategory?) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (category == null) {
            prefs.edit().remove(KEY_CATEGORY).apply()
        } else {
            prefs.edit().putString(KEY_CATEGORY, category.name).apply()
        }
    }

    fun load(context: Context): FeedbackCategory? {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CATEGORY, null) ?: return null
        return runCatching { FeedbackCategory.valueOf(raw) }.getOrNull()
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
