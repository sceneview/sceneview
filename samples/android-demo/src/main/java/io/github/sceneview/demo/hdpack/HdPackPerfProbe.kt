package io.github.sceneview.demo.hdpack

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import io.github.sceneview.demo.BuildConfig

/**
 * Debug-only timing of a model pick in the viewer, for the HD pack QA (2026-09-29): how long from
 * the tap to the instance being handed over, to the first Filament frame with every resource
 * uploaded, and the longest main-thread stall in the [WINDOW_MS] after the tap. One logcat line
 * per pick, tag `HdPackPerf`. Release builds return at the first line of every call.
 *
 * Main thread only: [start] from the picker's click, [instanceReady] from composition,
 * [onFrame] from the scene's frame callback.
 */
object HdPackPerfProbe {
    private const val TAG = "HdPackPerf"
    private const val WINDOW_MS = 10_000L
    private const val NANOS_PER_MILLI = 1_000_000L

    private var key: String? = null
    private var tapAt = 0L
    private var readyAt = 0L
    private var frameAt = 0L
    private var longestGapMs = 0L
    private var lastVsyncNanos = 0L
    private var generation = 0

    fun start(key: String) {
        if (!BuildConfig.DEBUG) return
        this.key = key
        tapAt = SystemClock.uptimeMillis()
        readyAt = 0L
        frameAt = 0L
        longestGapMs = 0L
        lastVsyncNanos = 0L
        val mine = ++generation
        // Every vsync for the window: the gap between two callbacks is how long the main
        // thread could not draw — the freeze a user feels.
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (mine != generation) return
                val now = System.nanoTime()
                if (lastVsyncNanos != 0L) {
                    longestGapMs = maxOf(longestGapMs, (now - lastVsyncNanos) / NANOS_PER_MILLI)
                }
                lastVsyncNanos = now
                if (SystemClock.uptimeMillis() - tapAt < WINDOW_MS) {
                    Choreographer.getInstance().postFrameCallback(this)
                } else {
                    report()
                }
            }
        }
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun instanceReady() {
        if (!BuildConfig.DEBUG || key == null || readyAt != 0L) return
        readyAt = SystemClock.uptimeMillis()
    }

    fun onFrame(resourcesComplete: () -> Boolean) {
        if (!BuildConfig.DEBUG || key == null) return
        val waitingForFrame = readyAt != 0L && frameAt == 0L
        if (waitingForFrame && resourcesComplete()) frameAt = SystemClock.uptimeMillis()
    }

    private fun report() {
        val k = key ?: return
        Log.i(
            TAG,
            "model=$k tapToInstanceMs=${if (readyAt > 0) readyAt - tapAt else -1} " +
                "tapToFrameMs=${if (frameAt > 0) frameAt - tapAt else -1} " +
                "longestMainThreadGapMs=$longestGapMs windowMs=$WINDOW_MS",
        )
        key = null
    }
}
