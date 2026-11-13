package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ScanActivity
import io.github.sceneview.demo.demos.internal.ScanCoach
import io.github.sceneview.demo.demos.internal.ScanCopy
import io.github.sceneview.demo.demos.internal.ScanPhase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A scan is stuck only when it adds nothing: a count of points that keeps rising is the scan
 * working, whatever the coverage of the floor says, and the activity and the coaching line agree.
 * A surface with too little texture or no depth is still stuck. The capture's top pill no longer
 * shows this status (2026-11-13: duration · floor area only), so only the logic is pinned here.
 */
class ScanHudStuckTest {

    @Test
    fun `with points rising the top line says stuck exactly when the coaching line says why`() {
        val stuckLines = setOf(ScanCopy.COACH_NOTHING_NEW, ScanCopy.COACH_LOW_TEXTURE, ScanCopy.COACH_NO_DEPTH)
        // (coach, low texture, no depth): the coverage finding nothing new, a blank surface, no depth.
        val causes = listOf(
            Triple(ScanCoach.Line.NothingNew, false, false),
            Triple(null, true, false),
            Triple(null, false, true),
        )
        val said = causes.map { (coach, lowTexture, depthStarved) ->
            val activity = ScanActivity.of(
                ScanPhase.Scanning, false, coach,
                lowTexture = lowTexture, depthStarved = depthStarved, pointsRising = true,
            )
            val line = ScanCopy.coaching(
                ScanPhase.Scanning, false, false,
                coach = coach, lowTexture = lowTexture, depthStarved = depthStarved, pointsRising = true,
            )
            assertEquals("\"$line\" below", line in stuckLines, activity == ScanActivity.Stuck)
            activity
        }
        // Rising points answer the coverage, not a surface the depth cannot read.
        assertEquals(listOf(ScanActivity.Working, ScanActivity.Stuck, ScanActivity.Stuck), said)
        assertEquals(
            ScanActivity.Paused,
            ScanActivity.of(ScanPhase.Scanning, trackingLost = true, coach = null, pointsRising = true),
        )
    }
}
