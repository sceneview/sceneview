package io.github.sceneview.demo.demos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The scans being built and saved, in a scope that outlives the capture screen (#4500): Back
 * while "Saving scan…" leaves the screen at once, and the scan is still built, written and listed
 * under "Your scans". Main thread, as the build was on the screen: [ScanCapture.finish] takes the
 * journal there, and the work it hands off runs on its own dispatchers.
 */
internal object RerunScanBuilds {
    /** Where every save runs, attached to the screen or not. Never cancelled. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** A capture's own work — its photo encoding, its depth fusion — kept alive while it is saved. */
    fun captureScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Saves under way, on screen or behind it: the landing says one is coming. */
    var saving by mutableIntStateOf(0)
        private set

    /** Bumped when a save finishes behind a screen that was left: the sessions list reads again. */
    var savedAway by mutableIntStateOf(0)
        private set

    fun started() {
        saving++
    }

    fun ended(away: Boolean) {
        saving = (saving - 1).coerceAtLeast(0)
        if (away) savedAway++
    }
}
