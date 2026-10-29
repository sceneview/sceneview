package io.github.sceneview.ar

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Runs the native half of closing ARCore sessions off the main thread, one at a time (#4771).
 *
 * Pausing a running session closes the camera; with the close that follows it takes about
 * 350 ms on a Pixel 4a. `ARSceneView` closes its session when it leaves the composition, at
 * the end of a navigation transition, and doing that on the main thread froze the screen
 * being navigated to.
 *
 * A session that resumes waits for the teardowns still running ([awaitIdle]): it cannot open
 * the camera before the previous session has let it go. That wait only happens when an AR
 * screen reopens within a fraction of a second of the last one closing.
 *
 * @param executor runs the teardowns; a single thread, so they never overlap.
 * @param postToMainThread delivers each teardown's completion on the main thread.
 */
internal class SessionTeardown(
    private val executor: Executor,
    private val postToMainThread: (() -> Unit) -> Unit,
) {
    private val lock = ReentrantLock()
    private val idle = lock.newCondition()
    private var pending = 0

    /** Teardowns submitted and not finished yet. */
    val pendingCount: Int get() = lock.withLock { pending }

    /**
     * Runs [teardown] on the executor, then [onDone] on the main thread. A teardown that throws
     * is logged: the session is gone either way, and [onDone] still runs.
     */
    fun run(teardown: () -> Unit, onDone: () -> Unit) {
        lock.withLock { pending++ }
        executor.execute {
            try {
                teardown()
            } catch (e: RuntimeException) {
                Log.w("ARCore", "Closing the ARCore session failed", e)
            } finally {
                postToMainThread(onDone)
                lock.withLock {
                    pending--
                    if (pending == 0) idle.signalAll()
                }
            }
        }
    }

    /**
     * Blocks until no teardown is running, for at most [timeoutMs]. Returns `true` when none is
     * left.
     */
    @MainThread
    fun awaitIdle(timeoutMs: Long = AWAIT_TIMEOUT_MS): Boolean = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (pending > 0 && remaining > 0) remaining = idle.awaitNanos(remaining)
        pending == 0
    }

    companion object {
        /** Longer than a camera close ever took in testing, short of an ANR. */
        const val AWAIT_TIMEOUT_MS = 2_000L
    }
}

/** The process's one teardown thread, next to its one camera arbitration. */
internal val sessionTeardown = SessionTeardown(
    executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ARCore-teardown").apply { isDaemon = true }
    },
    postToMainThread = { block -> Handler(Looper.getMainLooper()).post(block) },
)
