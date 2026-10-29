package io.github.sceneview.demo.feedback

import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Bounded history of AR tracking lines: the newest [capacity], oldest first (#4529).
 *
 * Only whole AR tracking lines get in (see [AR_TRACKING_LINE]) — the same filter the
 * report applies to the log buffer, so nothing a user picked (a file name, a URI) can
 * reach a public issue through it. Memory is bounded by [capacity], whatever the
 * session length: the newest line is always the current state, the older ones say how
 * it got there.
 *
 * Thread-safe: fed by the reader thread of [ArTrackingHistory], read by the report.
 */
internal class ArTrackingRing(private val capacity: Int = AR_TRACKING_MAX_LINES) {
    private val lines = ArrayDeque<String>(capacity)

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    /** Keep [line] if it is an AR tracking line, dropping the oldest when full. */
    @Synchronized
    fun offer(line: String): Boolean {
        if (!AR_TRACKING_LINE.matches(line)) return false
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(line)
        return true
    }

    /** The kept lines, oldest first. */
    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()
}

/**
 * Keeps the AR tracking block of the bug report for the whole app process (#4529).
 *
 * `ARSceneView` logs one `AR tracking:` line per tracking transition under the
 * `SceneView` tag. Read back from the log buffer at report time, those lines were
 * gone within a minute of an AR session: ARCore fills a 256 KiB buffer that fast.
 * So the app follows its own log from the start — one `logcat` process filtered to
 * its own pid and the `SceneView` tag — and keeps the lines in an [ArTrackingRing].
 * No SDK hook and no change to the 27 AR demos: the history is fed by exactly the
 * lines the library already writes.
 *
 * An app reads its own log lines without any permission. If `logcat` cannot start,
 * the history stays empty and the report falls back to the buffer
 * ([reportArTracking]).
 */
internal object ArTrackingHistory {
    private val ring = ArTrackingRing()
    private val started = AtomicBoolean(false)

    /** Start following the log, once per process; later calls are no-ops while it runs. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        thread(name = "ar-tracking-history", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            runCatching { follow() }
                .onFailure { Log.w(TAG, "AR tracking history stopped", it) }
            // Let a later start() try again (a killed logcat, say).
            started.set(false)
        }
    }

    /** The kept AR tracking lines, oldest first. */
    fun snapshot(): List<String> = ring.snapshot()

    private fun follow() {
        // No `-d`: logcat dumps what the buffer still holds, then blocks for new lines.
        val command = arrayOf(
            "logcat", "-v", "threadtime", "--pid=${Process.myPid()}", "-s", "SceneView:I",
        )
        val process = Runtime.getRuntime().exec(command)
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { ring.offer(it.trimEnd()) }
            }
        } finally {
            process.destroy()
        }
    }

    private const val TAG = "BugReport"
}
