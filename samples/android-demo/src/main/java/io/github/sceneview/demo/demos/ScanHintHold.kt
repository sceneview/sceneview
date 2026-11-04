package io.github.sceneview.demo.demos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import io.github.sceneview.demo.demos.internal.PointsRise
import kotlinx.coroutines.delay

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
