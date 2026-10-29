package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import io.github.sceneview.demo.demos.internal.ScanCopy
import io.github.sceneview.demo.demos.internal.ScanTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The turn a room's scan points to ("Turn left — no wall yet at the dashed line") changes only once the
 * turn has left the side shown for a few seconds in a row, to the latest side: facing a wall, the
 * next gap's side flips with every small sway of the phone, and a hint that flips back and forth
 * says nothing. The live screen holds the turn once, for the arrow and the coaching line alike.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanTurnHoldTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var turn by mutableStateOf<ScanTurn?>(ScanTurn.Left)

    /** What the held turn says now, and how many times it changed. */
    private var shown: ScanTurn? = ScanTurn.Left
    private var changes = 0

    /** The live screen's hold over [turn]. */
    private fun hold() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val held = rememberHeld(turn)
            SideEffect {
                if (held != shown) changes++
                shown = held
            }
        }
    }

    private fun turnTo(next: ScanTurn?) = composeRule.runOnUiThread {
        turn = next
        Snapshot.sendApplyNotifications()
    }

    private fun advance(ms: Long) = composeRule.mainClock.advanceTimeBy(ms)

    @Test
    fun `a turn shown a second ago is not replaced by the other side`() {
        hold()
        advance(SHOWN_MS)
        turnTo(ScanTurn.Right)
        advance(SHOWN_MS)
        assertEquals(ScanTurn.Left, shown)
    }

    @Test
    fun `a new side shows once it has been the turn for the whole hold`() {
        hold()
        advance(SETTLED_MS)
        turnTo(ScanTurn.Right)
        advance(SCAN_HINT_HOLD_MS - SHOWN_MS)
        assertEquals("a side seen for less than the hold", ScanTurn.Left, shown)
        advance(SHOWN_MS + SLACK_MS)
        assertEquals(ScanTurn.Right, shown)
    }

    @Test
    fun `a side that comes and goes within the hold is never shown`() {
        hold()
        advance(SETTLED_MS)
        turnTo(ScanTurn.Right)
        advance(SHOWN_MS)
        turnTo(ScanTurn.Left)
        advance(SCAN_HINT_HOLD_MS + SLACK_MS)
        assertEquals(ScanTurn.Left, shown)
        assertEquals(0, changes)
    }

    @Test
    fun `a turn swaying from side to side every half second changes the hint at most once in ten seconds`() {
        hold()
        advance(SWAY_MS / 2)
        repeat(SWAYS) { i ->
            turnTo(if (i % 2 == 0) ScanTurn.Right else ScanTurn.Left)
            advance(SWAY_MS)
        }
        assertTrue("the hint changed $changes times", changes <= 1)
    }

    @Test
    fun `a turn that left the side shown, swaying between two others, changes the hint once the hold has passed`() {
        hold()
        advance(SETTLED_MS)
        repeat(SWAYS) { i ->
            turnTo(if (i % 2 == 0) ScanTurn.Right else ScanTurn.Ahead)
            advance(SWAY_MS)
        }
        assertTrue("the hint still says $shown, left ten seconds ago", shown != ScanTurn.Left)
        assertEquals(1, changes)
    }

    @Test
    fun `the arrow points where it is told at once, the screen holding the turn for it`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { Box(Modifier.fillMaxSize()) { ScanTurnArrow(turn) } }
        advance(SHOWN_MS)
        turnTo(ScanTurn.Right)
        advance(SHOWN_MS)
        composeRule.onNodeWithContentDescription(ScanCopy.turnHint(ScanTurn.Right)).assertExists()
    }

    private companion object {
        /** Well under the hold: the time a sway of the phone takes. */
        const val SHOWN_MS = 1_000L

        /** Past the first hold: the turn shown so far has been on screen long enough. */
        const val SETTLED_MS = SCAN_HINT_HOLD_MS + SHOWN_MS

        /** A few frames, for the change to reach the screen. */
        const val SLACK_MS = 100L

        /** A sway each half second (1 Hz from side to side), for ten seconds. */
        const val SWAY_MS = 500L
        const val SWAYS = 20
    }
}
