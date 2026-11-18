package io.github.sceneview.demo.demos.internal.feel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * First-run guidance: "press and hold, swipe to change". Shown once per install, from the
 * moment the stage is ready, until the first press on a solid; then never again.
 *
 * Main thread only, updated once per frame. [Store] is the per-install flag (SharedPreferences
 * in the app, a fake in the JVM tests).
 */
internal class MatterHint(private val store: Store) {
    interface Store {
        val done: Boolean
        fun markDone()
    }

    var visible by mutableStateOf(false)
        private set
    private var done = store.done

    /**
     * [stageReady]: the scene is revealed and its opening has finished (or was skipped).
     * [pressed]: a finger is pressing a solid. The first press dismisses the hint for good,
     * even if the stage was not ready yet: the user has already found the gesture.
     */
    fun update(stageReady: Boolean, pressed: Boolean) {
        if (done) return
        if (pressed) {
            done = true
            visible = false
            store.markDone()
            return
        }
        if (stageReady) visible = true
    }
}
