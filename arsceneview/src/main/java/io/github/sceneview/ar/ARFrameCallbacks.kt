package io.github.sceneview.ar

import com.google.ar.core.TrackingFailureReason
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs one host callback of the AR frame loop in its own guard (#4510).
 *
 * The loop used to run its callbacks inside the `try` that wraps `Session.update()`, so an
 * exception thrown by the app's own `onSessionUpdated` was logged as "ARCore session update
 * failed" — the wrong culprit. Guarded, a throwing callback is reported to [onThrow] under its
 * own [name] and the frame carries on.
 */
internal inline fun guardHostCallback(
    name: String,
    onThrow: (name: String, exception: Exception) -> Unit,
    block: () -> Unit,
) {
    try {
        block()
    } catch (exception: Exception) {
        onThrow(name, exception)
    }
}

/**
 * The two host callbacks of an AR frame, each in its own guard: `onTrackingFailureChanged` when
 * [newTrackingFailure] differs from the previous frame's, then `onSessionUpdated` on every frame.
 * A throwing `onTrackingFailureChanged` no longer skips that frame's `onSessionUpdated`.
 *
 * An exception from either is only reported to [onThrow]: it is the host's code, not the session,
 * so it never reaches `onSessionFailure`.
 */
internal inline fun dispatchHostFrameCallbacks(
    newTrackingFailure: TrackingFailureReason?,
    prevTrackingFailureRef: AtomicReference<TrackingFailureReason?>,
    onTrackingFailureChanged: (TrackingFailureReason?) -> Unit,
    onSessionUpdated: () -> Unit,
    onThrow: (name: String, exception: Exception) -> Unit,
) {
    if (prevTrackingFailureRef.get() != newTrackingFailure) {
        prevTrackingFailureRef.set(newTrackingFailure)
        guardHostCallback("onTrackingFailureChanged", onThrow) {
            onTrackingFailureChanged(newTrackingFailure)
        }
    }
    guardHostCallback("onSessionUpdated", onThrow) { onSessionUpdated() }
}
