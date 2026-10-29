package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import io.github.sceneview.demo.demos.internal.RerunScanModel
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The model screen's status line under the model, as the screen lays it out ([ModelStatusLine]):
 * one indicator, the line itself, its ellipsis breathing while a step is in flight (`DESIGN.md` →
 * `motion-narration`); heard as it changes and nothing else heard; the same room whatever a big
 * scan's finish ends in, so the model framed above it never moves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RerunModelStatusCardTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the line is the one indicator and the only thing announced`() {
        val finishing = checkNotNull(ModelStatus.slot(mesh(finished = false), settled = false, rawShownFirst = false))
        rule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                Box(Modifier.width(CARD_WIDTH.dp)) { ModelStatusLine(finishing) }
            }
        }
        val bars = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals("progress indicators beside the line: $bars", 0, bars.size)
        val announced = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals("live regions: ${announced.map { it.config }}", 1, announced.size)
        val line = rule.onAllNodes(hasText(FINISHING) and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals("the live region is not the line's", 1, line.size)
        assertEquals(LiveRegionMode.Polite, line.single().config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `the line keeps its room when the finish fails, as when it succeeds`() {
        var slot by mutableStateOf(checkNotNull(ModelStatus.slot(mesh(finished = false), settled = false, rawShownFirst = false)))
        rule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                Box(Modifier.width(CARD_WIDTH.dp).testTag(PROBE)) { ModelStatusLine(slot) }
            }
        }
        // A big scan's raw surface finishing, then each way its finish can end.
        val endings = listOf(
            Triple("finishing", mesh(finished = false), false),
            Triple("out of memory", mesh(finished = false, outOfMemoryInHeap = HEAP), true),
            Triple("unfinished", mesh(finished = false), true),
            Triple("finished", mesh(finished = true), true),
        )
        val heights = endings.associate { (name, model, settled) ->
            slot = checkNotNull(ModelStatus.slot(model, settled, rawShownFirst = true))
            rule.waitForIdle()
            name to rule.onNodeWithTag(PROBE).getUnclippedBoundsInRoot().height.value
        }
        assertEquals("card heights by ending: $heights", 1, heights.values.distinct().size)
    }

    @Test
    fun `the finished model taking the raw surface's place is announced once`() {
        val swapped = checkNotNull(ModelStatus.slot(mesh(finished = true), settled = true, rawShownFirst = true))
        rule.setContent {
            SceneViewDemoTheme(darkTheme = false) {
                Box(Modifier.width(CARD_WIDTH.dp)) { ModelStatusLine(swapped) }
            }
        }
        val said = rule.onAllNodes(hasText(FINISHED) and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals("the end announced: ${swapped.status}, visible ${swapped.visible}", 1, said.size)
        // Laid out only to hold the card's room, the other lines are never heard.
        assertEquals(0, rule.onAllNodesWithText(FINISHING, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private fun mesh(finished: Boolean, outOfMemoryInHeap: Long? = null) = RerunScanModel(
        RerunScanModel.Kind.Mesh,
        ByteArray(0),
        FloatArray(BOUNDS),
        1,
        3,
        finished = finished,
        outOfMemoryInHeap = outOfMemoryInHeap,
    )

    private companion object {
        const val FINISHING = "Finishing your model…"
        const val FINISHED = "Your model is finished."
        const val CARD_WIDTH = 240
        const val PROBE = "status-probe"
        const val BOUNDS = 6
        const val HEAP = 192L * 1024 * 1024
    }
}
