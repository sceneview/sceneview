package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The status card's and the controls' edges, measured where the 3D Scan screen measures them:
 * held for the capture, so a pill showing up or a coaching line wrapping does not move the
 * thumbnail, the map's framing or a drag in progress.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h851dp")
class ScanStageEdgesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the controls' edge keeps its highest while the bottom stack grows and shrinks`() {
        val edges = StageEdges()
        var pill by mutableStateOf(false)
        composeRule.setContent {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    Spacer(Modifier.fillMaxWidth().controlsEdge(edges, measuring = true))
                    if (pill) Box(Modifier.fillMaxWidth().height(48.dp))
                    Box(Modifier.fillMaxWidth().height(120.dp))
                }
            }
        }
        composeRule.waitForIdle()
        val resting = edges.controlsTop
        assertTrue("measured: $resting", resting.isFinite())
        pill = true
        composeRule.waitForIdle()
        val raised = edges.controlsTop
        assertTrue("$raised above $resting", raised < resting)
        pill = false
        composeRule.waitForIdle()
        assertEquals(raised, edges.controlsTop, 0f)
    }

    @Test
    fun `the card's edge keeps its lowest while the card wraps and unwraps`() {
        val edges = StageEdges()
        var wrapped by mutableStateOf(false)
        composeRule.setContent {
            Column(Modifier.fillMaxWidth().cardEdge(edges, measuring = true)) {
                Box(Modifier.fillMaxWidth().height(if (wrapped) 96.dp else 64.dp))
            }
        }
        composeRule.waitForIdle()
        val resting = edges.cardBottom
        wrapped = true
        composeRule.waitForIdle()
        val lowered = edges.cardBottom
        assertTrue("$lowered under $resting", lowered > resting)
        wrapped = false
        composeRule.waitForIdle()
        assertEquals(lowered, edges.cardBottom, 0f)
    }

    @Test
    fun `outside the map's phases nothing is measured`() {
        val edges = StageEdges()
        composeRule.setContent {
            Box(Modifier.fillMaxSize().cardEdge(edges, measuring = false).controlsEdge(edges, measuring = false))
        }
        composeRule.waitForIdle()
        assertTrue(edges.cardBottom.isNaN())
        assertTrue(edges.controlsTop.isNaN())
    }
}
