package io.github.sceneview.demo.demos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.github.sceneview.demo.demos.internal.PointsRise
import kotlinx.coroutines.delay

/** How long a new scan hint must hold before it replaces the one on screen. */
internal const val SCAN_HINT_HOLD_MS = 3_000L

/**
 * [value], but the one on screen changes only once [value] has left it for [holdMs] in a row, and
 * then to the latest: one that comes and goes within the hold is never shown, and one that flips
 * back to the value on screen starts the hold over. Facing a wall, the side of a room's next gap
 * flips with every sway of the phone; a hint that flips with it says nothing — and one that waits
 * for the sway to settle on one new side says nothing more while it sways between two.
 */
@Composable
internal fun <T> rememberHeld(value: T, holdMs: Long = SCAN_HINT_HOLD_MS): T {
    var shown by remember { mutableStateOf(value) }
    val latest by rememberUpdatedState(value)
    // The hold starts when the value leaves the one shown, and only its return starts it over.
    val away = value != shown
    LaunchedEffect(away, holdMs) {
        if (away) {
            delay(holdMs)
            shown = latest
        }
    }
    return shown
}

/** How often [rememberPointsRising] reads the scan's count of points. */
internal const val POINTS_TICK_MS = 250L

/**
 * Whether [points] rose within the last few seconds of the scan's clock ([seconds], [PointsRise]):
 * a scan whose points keep rising adds to the room, so neither its top line nor its coaching line
 * says it is stuck. Read on a tick of its own, so a count that stopped rising turns it off even
 * when nothing else on screen changes.
 */
@Composable
internal fun rememberPointsRising(points: Int, seconds: () -> Float): Boolean {
    val count by rememberUpdatedState(points)
    val clock by rememberUpdatedState(seconds)
    val rising by produceState(false) {
        val rise = PointsRise()
        while (true) {
            value = rise.rising(count, clock())
            delay(POINTS_TICK_MS)
        }
    }
    return rising
}
