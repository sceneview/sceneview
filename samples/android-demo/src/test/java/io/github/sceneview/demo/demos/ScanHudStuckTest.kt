package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.sceneview.demo.demos.internal.ScanActivity
import io.github.sceneview.demo.demos.internal.ScanCoach
import io.github.sceneview.demo.demos.internal.ScanCopy
import io.github.sceneview.demo.demos.internal.ScanPhase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A scan says it is stuck only when it adds nothing: a count of points that keeps rising is the
 * scan working, whatever the coverage of the floor says — on its top line and on its coaching
 * line alike, so the two never contradict each other. A surface with too little texture or no
 * depth still says so on both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanHudStuckTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var points by mutableIntStateOf(START_POINTS)

    /** The scan's clock: read on the count's own tick, never by the screen. */
    private var seconds = START_S

    /** The live screen's top line and coaching line, the coverage finding nothing new. */
    private fun showScan() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val rising = rememberPointsRising(points) { seconds }
            Column {
                ScanHud(
                    phase = ScanPhase.Scanning,
                    seconds = START_S,
                    points = points,
                    activity = ScanActivity.of(
                        phase = ScanPhase.Scanning,
                        trackingLost = false,
                        coach = ScanCoach.Line.NothingNew,
                        pointsRising = rising,
                    ),
                )
                Text(
                    ScanCopy.coaching(
                        phase = ScanPhase.Scanning,
                        trackingLost = false,
                        photoLimitReached = false,
                        coach = ScanCoach.Line.NothingNew,
                        pointsRising = rising,
                    ).orEmpty(),
                )
            }
        }
        tick()
    }

    /** One more second of scan, [added] points more. */
    private fun scanOn(added: Int) {
        seconds += 1f
        composeRule.runOnUiThread {
            points += added
            Snapshot.sendApplyNotifications()
        }
        tick()
    }

    private fun tick() = composeRule.mainClock.advanceTimeBy(POINTS_TICK_MS + SLACK_MS)

    private fun saysStuck(stuck: Boolean) {
        val status = composeRule.onNodeWithText(ScanCopy.STUCK, substring = true)
        val coaching = composeRule.onNodeWithText(ScanCopy.COACH_NOTHING_NEW)
        if (stuck) {
            status.assertExists()
            coaching.assertExists()
        } else {
            status.assertDoesNotExist()
            coaching.assertDoesNotExist()
        }
    }

    @Test
    fun `a scan whose points keep rising says neither on top nor below that it is stuck`() {
        showScan()
        scanOn(added = 200)
        saysStuck(false)
    }

    @Test
    fun `a scan whose points stay flat says it is stuck and why`() {
        showScan()
        scanOn(added = 0)
        saysStuck(true)
    }

    @Test
    fun `a scan whose points stopped rising says so once the stall has lasted, with nothing else changing`() {
        showScan()
        scanOn(added = 200)
        saysStuck(false)
        seconds += ScanCoach.STALL_SECONDS + 1f
        tick()
        saysStuck(true)
    }

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

    private companion object {
        const val START_S = 10f
        const val START_POINTS = 1_000

        /** A few frames, for the change to reach the screen. */
        const val SLACK_MS = 50L
    }
}
