package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.of
import io.github.sceneview.demo.demos.internal.ScanCopy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.sceneview.demo.demos.internal.CaptureChrome
import io.github.sceneview.demo.demos.internal.CaptureCue
import io.github.sceneview.demo.demos.internal.GuideSpeech
import io.github.sceneview.demo.demos.internal.ScanGuideHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The guide's arrow on screen: it says the turn and the distance as the coaching line does — what
 * a screen reader reads — and it goes once the phone lines up with the target.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rGB")
class ScanGuideArrowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val trace = ArDebugTrace.of(ScanGuideCorpus.syntheticEvents())
    private val walls = ScanWallsState(clock = { (time * 1000).toLong() })
    private val coverage = ScanCoverageState()
    private var time = 0f

    /** The synthetic scan's frames observed up to [until] seconds, as the live view observes them. */
    private fun observeUntil(until: Float) = composeRule.runOnUiThread {
        while (time < until) {
            val frame = trace.frameAt(time)
            coverage.observe(frame, pose = frame.camera, saved = trace)
            walls.observe(frame, coverage, frame.camera)
            time += STEP_S
        }
    }

    @Test
    fun `the arrow says the turn and the distance, then goes once lined up`() {
        observeUntil(2f)
        assertTrue("an arrow with the target behind", walls.arrowShown)
        composeRule.setContent { Box(Modifier.fillMaxSize()) { ScanGuideArrow(walls) } }
        composeRule.waitForIdle()
        val said = ScanCopy.guideHint(walls.hint!!, Locale.UK)
        composeRule.onNodeWithContentDescription(said).assertExists()
        // Turned round to the gap: lined up at some point during the turn, and the arrow went.
        var lined = false
        while (time < 24f && !lined) {
            observeUntil(time + STEP_S)
            lined = !walls.arrowShown
        }
        assertTrue("lined up during the turn", lined)
        composeRule.mainClock.advanceTimeBy(FADE_MS)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SCAN_TURN_TAG).assertDoesNotExist()
        assertTrue("a tick", walls.alignedTicks >= 1)
    }

    @Test
    fun `the guide's line is a polite live region, a new distance read every three seconds at most`() {
        var cue by mutableStateOf<CaptureCue?>(null)
        composeRule.setContent { cue?.let { ScanCueLine(it) } }
        observeUntil(WALK_FROM_S)
        val heard = ArrayList<Pair<Float, ScanGuideHint>>()
        val shown = HashSet<String>()
        while (time < WALK_TO_S) {
            observeUntil(time + STEP_S)
            val hint = walls.hint ?: continue
            val text = ScanCopy.guideHint(hint)
            shown += text
            composeRule.runOnUiThread { cue = CaptureChrome.spoken(CaptureCue.Next(text), hint, walls.spokenHint) }
            composeRule.waitForIdle()
            val config = composeRule.onNodeWithTag(SCAN_HINT_TAG).fetchSemanticsNode().config
            assertEquals(LiveRegionMode.Polite, config[SemanticsProperties.LiveRegion])
            val spoken = walls.spokenHint!!
            assertEquals(listOf(CaptureChrome.nextLine(ScanCopy.guideHint(spoken))), config[SemanticsProperties.ContentDescription])
            if (heard.lastOrNull()?.second != spoken) heard += time to spoken
        }
        assertTrue("the walk changed the line: $shown", shown.size >= 3)
        assertTrue("the walk was heard: $heard", heard.size >= 3)
        // A change of distance alone is read 3 s after the last one at the earliest (the first one
        // heard was said before the walk, at a time this loop did not see).
        for ((a, b) in heard.drop(1).zipWithNext()) {
            val distanceOnly = a.second.turn == b.second.turn && a.second.fix == b.second.fix &&
                a.second.metres != null && b.second.metres != null
            if (distanceOnly) assertTrue("$a then $b", b.first - a.first >= GuideSpeech.GAP_MS / 1000f - 1e-3f)
        }
        // Another line than the guide's is not a live region.
        composeRule.runOnUiThread { cue = CaptureChrome.spoken(CaptureCue.Next("Walk along this wall to finish it"), walls.hint, walls.spokenHint) }
        composeRule.waitForIdle()
        val other = composeRule.onNodeWithTag(SCAN_HINT_TAG).fetchSemanticsNode().config
        assertTrue(LiveRegionMode.Polite != other.getOrElseNullable(SemanticsProperties.LiveRegion) { null })
    }

    private companion object {
        const val STEP_S = 0.1f
        const val FADE_MS = 1_000L

        /** The synthetic walk towards the gap. */
        const val WALK_FROM_S = 22f
        const val WALK_TO_S = 31f
    }
}
