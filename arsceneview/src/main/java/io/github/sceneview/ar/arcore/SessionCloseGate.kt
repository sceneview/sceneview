package io.github.sceneview.ar.arcore

import com.google.ar.core.Session

/**
 * Remembers that an ARCore session was closed, and closes it once (#4026).
 *
 * ARCore's `Session.close()` leaves the Java wrapper's native handle set, so nothing on the
 * ARCore side stops a call made after it: the call runs on freed native memory and the process
 * dies with a signal. The flag is set before the native close starts, so a reader that checks
 * it never enters a session that is being closed either. Plain Kotlin, so the contract is a
 * JVM test.
 */
internal class SessionCloseGate {

    @Volatile
    var isClosed: Boolean = false
        private set

    /**
     * Marks the session closed and returns `true` the first time only: the caller runs the
     * native close then, and never again. [isClosed] is already `true` while that close runs,
     * and stays `true` if it throws — a half-closed native session is no safer to call into
     * than a closed one.
     */
    @Synchronized
    fun tryClose(): Boolean {
        if (isClosed) return false
        isClosed = true
        return true
    }
}

/**
 * `true` when [session] is an [ARSession] that has been closed (#4026). A session of another
 * type cannot tell, so it counts as open.
 */
internal fun isClosedSession(session: Session): Boolean = (session as? ARSession)?.isClosed == true

/**
 * Reads [read] from a session unless it is closed (#4026), in which case [whenClosed] is
 * returned without touching ARCore. A [RuntimeException] from an open session also yields
 * [whenClosed]; JVM `Error`s propagate.
 */
internal inline fun <T> readUnlessClosed(isClosed: Boolean, whenClosed: T, read: () -> T): T {
    if (isClosed) return whenClosed
    return runCatching(read).getOrElse { failure ->
        if (failure is RuntimeException) whenClosed else throw failure
    }
}
