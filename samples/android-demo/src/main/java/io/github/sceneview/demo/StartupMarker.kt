package io.github.sceneview.demo

import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Cold-start markers for the demo app, read from logcat by `tools/measure-demo-cold-start.sh`.
 *
 * Each [mark] logs, once per process, how long after the process started an event happened:
 * `SVStartup: first_model_frame 812ms`. The origin is `Process.getStartElapsedRealtime()`;
 * `am start -W`'s `TotalTime` starts at the launch request, a few ms earlier on a cold start,
 * so the two read on nearly the same clock.
 *
 * Demo-only on purpose: the SDK already exposes everything this reads (`onFrame`,
 * `ModelLoader.isLoading`); what a cold start *means* is an app decision.
 */
internal object StartupMarker {

    private const val TAG = "SVStartup"

    /** Main-thread only — every caller is a composition or a `SceneView.onFrame` callback. */
    private val logged = HashSet<String>()

    fun mark(event: String) {
        if (!logged.add(event)) return
        val sinceProcessStart = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        Log.i(TAG, "$event ${sinceProcessStart}ms")
    }
}
