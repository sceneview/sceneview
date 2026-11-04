package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.of
import io.github.sceneview.demo.demos.internal.ScanTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * QA-24, "the arrows are useless": the old guide's turn stayed frozen on screen for 36 s while the
 * phone turned. A synthetic scan whose camera sweeps a wall, turns round to the open side and walks
 * to it ([ScanGuideCorpus.syntheticEvents]), replayed through the live screen's walls: the old
 * guide's text, held as the old screen held it, stands still for long stretches while the target
 * swings round the phone; the new guide's heading follows the phone, its words change as it turns
 * and its distance as it walks, and it ticks when the phone lines up.
 */
class ScanGuideReplayTest {
    private val trace = ArDebugTrace.of(ScanGuideCorpus.syntheticEvents())
    private val result = ScanGuideCorpus.replay("synthetic-turn", trace)
    private val samples = result.samples

    @Test
    fun `the new guide's text and heading change as the camera turns, where the old one stood still`() {
        println(result)
        System.getenv(BOARD_OUT)?.takeIf { it.isNotBlank() }?.let { dir ->
            File(dir).mkdirs()
            File(dir, "synthetic-turn-guide.png").writeBytes(ScanGuideCorpus.board(result))
            File(dir, "synthetic-turn-guide.csv").writeText(ScanGuideCorpus.csv(result))
        }
        assertTrue("two walls found", samples.last().walls >= 2)
        // While the phone turns round (6 s to 22 s), the target swings from behind to ahead.
        val turning = samples.filter { it.time in 6f..22f }
        val headings = turning.mapNotNull { it.degrees }
        assertTrue("the heading followed the turn: ${headings.min()}..${headings.max()}", headings.max() - headings.min() > 150f)
        val said = turning.mapNotNull { it.turn }.toSet()
        assertTrue("the words went round: $said", said.containsAll(listOf(ScanTurn.Behind, ScanTurn.Right, ScanTurn.Ahead)))
        // Walking to it (22 s to 30 s), the distance said comes down a stride at a time, then it is reached.
        val walking = samples.filter { it.time in 22f..30f }
        val distances = walking.mapNotNull { it.said?.takeIf { _ -> !it.reached } }.distinct()
        assertTrue("the distance said came down: $distances", distances.size >= 2 && distances.zipWithNext().all { (a, b) -> b < a })
        assertTrue("reached at the end", samples.last().reached)
        assertTrue("one tick or more on lining up", result.ticks >= 1)
        // The new text changes more, and never stands still as long as the old one did.
        assertTrue(result.toString(), result.newChanges > result.oldChanges)
        assertTrue(result.toString(), result.newLongestS < result.oldLongestS)
        // The old text stood still for long stretches: it said a side its own guide no longer gave
        // (a sway back to the side on screen started the 3 s hold over), and walking up to the gap
        // it never said how far.
        assertTrue(result.toString(), result.oldLongestS >= OLD_FROZEN_S && result.newChangesInOldLongest >= 2)
        assertTrue(result.toString(), result.oldStaleS >= OLD_STALE_S)
    }

    @Test
    fun `the arrow goes once the phone lines up, and the ribbon once the target is reached`() {
        for (s in samples) {
            if (s.target == null) continue
            assertEquals("arrow at ${s.time} s", !s.aligned, s.arrow)
            assertEquals("ribbon at ${s.time} s", !s.reached, s.ribbon != null)
            s.ribbon?.let { r ->
                assertEquals(s.pose.x, r[0], 1e-5f)
                assertEquals(s.pose.z, r[1], 1e-5f)
            }
            if (s.aligned) assertTrue("lined up at ${s.time} s: ${s.degrees}°", abs(s.degrees!!) <= 25f)
        }
        val ticks = samples.filter { it.tick }.map { it.time }
        assertTrue("ticks a second apart at least: $ticks", ticks.zipWithNext().all { (a, b) -> b - a >= 1f - 1e-3f })
    }

    @Test
    fun `during Fix now the guide leads to the part being fixed`() {
        val part = floatArrayOf(1.6f, -1.2f)
        val fixing = ScanGuideCorpus.replay("synthetic-fix", trace, fixAt = 10f to part).samples
        val before = fixing.filter { it.time < 10f && it.target != null }
        val during = fixing.filter { it.time >= 10f }
        assertTrue(before.none { it.fix })
        assertTrue(during.all { it.fix && it.target!![0] == part[0] && it.target!![1] == part[1] })
        val text = during.mapNotNull { it.newText }
        assertNotNull(text.firstOrNull())
        assertTrue(text.toString(), text.all { "part to fix" in it })
        assertTrue(text.toString(), text.any { it.contains(" m away") || it.contains(" m ahead") })
    }

    private companion object {
        /** Where the synthetic board and CSV go when set, to look at. */
        const val BOARD_OUT = "SCENEVIEW_SYNTHETIC_OUT"

        /** The old text stood this long or more, and said a side its guide no longer gave this long in all. */
        const val OLD_FROZEN_S = 8f
        const val OLD_STALE_S = 3f
    }
}
