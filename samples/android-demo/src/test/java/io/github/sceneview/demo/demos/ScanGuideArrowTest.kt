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

    private companion object {
        const val STEP_S = 0.1f
        const val FADE_MS = 1_000L
    }
}
