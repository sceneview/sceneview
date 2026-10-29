package io.github.sceneview.ar

import io.github.sceneview.ar.arcore.SessionCloseGate

/**
 * The main-thread half of closing an ARCore session (#4771), split out so its order is a JVM
 * test. `ARSession.beginClose()` runs it.
 *
 * A running session is marked paused and its host hears [onPaused] while the session is still
 * open; then [gate] closes it, so no main-thread reader enters it again, and the last frame is
 * dropped. A throwing [onPaused] does not keep the session from closing (like a failing pause,
 * #4026). The returned block is the native half — pause if it was running, then close — for
 * `ARCore.destroy()` to run where it chooses.
 *
 * @return the native half, or `null` when the session was already closed, including by its
 * own [onPaused].
 */
internal fun beginSessionClose(
    gate: SessionCloseGate,
    isResumed: Boolean,
    markPaused: () -> Unit,
    onPaused: () -> Unit,
    dropFrame: () -> Unit,
    pause: () -> Unit,
    close: () -> Unit,
    onPausedFailed: (RuntimeException) -> Unit = {
        android.util.Log.w("ARCore", "onSessionPaused failed while closing the session", it)
    },
): (() -> Unit)? {
    if (gate.isClosed) return null
    if (isResumed) {
        markPaused()
        try {
            onPaused()
        } catch (e: RuntimeException) {
            onPausedFailed(e)
        }
    }
    if (!gate.tryClose()) return null
    dropFrame()
    return { closeSessionInOrder(isResumed = isResumed, pause = pause, close = close) }
}

/**
 * Where `ARCore.destroy()` runs the native half of a close (#4771).
 *
 * In the background, then [onClosed] on the main thread — except for a session that is
 * recording: its pause finalizes the file, and a recorder stopped by the same disposal returns
 * that file at once, so it closes inline. With nothing to close, [onClosed] runs at once.
 */
internal fun runSessionClose(
    teardown: (() -> Unit)?,
    isRecording: Boolean,
    runInBackground: (teardown: () -> Unit, onDone: () -> Unit) -> Unit,
    onClosed: () -> Unit,
) {
    when {
        teardown == null -> onClosed()
        isRecording -> {
            teardown()
            onClosed()
        }
        else -> runInBackground(teardown, onClosed)
    }
}

/**
 * Resumes a session once the sessions still closing have released the camera (#4771).
 *
 * When [awaitTeardowns] gives up (a close running past its timeout), resuming would only throw
 * `CameraNotAvailableException` and drop the session from the camera queue. [onStillClosing]
 * runs instead and the session stays entitled to the camera: the close that holds it hands
 * the camera back when it is done (`CameraArbiter.destroy`).
 */
internal fun resumeOnceCameraFree(
    awaitTeardowns: () -> Boolean,
    resume: () -> Unit,
    onStillClosing: () -> Unit,
) {
    if (awaitTeardowns()) resume() else onStillClosing()
}
