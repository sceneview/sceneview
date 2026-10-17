package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Back on the Record screen: it asks only when leaving would drop a scan that holds something,
 * and the scan's clock stops at Stop. Both were read on a phone — a scan dropped by one swipe,
 * a line counting from 2:57 to 3:06 under "Building your scan…".
 */
class ScanBackTest {

    @Test
    fun `back asks during a scan that holds points`() {
        assertEquals(ScanBackAction.Ask, ScanBack.on(ScanBackState.Recording, points = 1))
        assertEquals(ScanBackAction.Ask, ScanBack.on(ScanBackState.Recording, points = 250_000))
    }

    @Test
    fun `back leaves at once from a scan that holds nothing yet`() {
        assertEquals(ScanBackAction.Leave, ScanBack.on(ScanBackState.Recording, points = 0))
    }

    @Test
    fun `back leaves at once before the scan starts`() {
        assertEquals(ScanBackAction.Leave, ScanBack.on(ScanBackState.Waiting, points = 0))
        // A count left over from a previous scan is not a scan in progress.
        assertEquals(ScanBackAction.Leave, ScanBack.on(ScanBackState.Waiting, points = 4_000))
    }

    @Test
    fun `back stays on the screen while the scan is built and saved`() {
        // #4500: leaving here cancelled the save and lost the scan. Neither Leave nor Ask —
        // "Discard" under "Building your scan…" would drop it just the same.
        assertEquals(ScanBackAction.Stay, ScanBack.on(ScanBackState.Building, points = 0))
        assertEquals(ScanBackAction.Stay, ScanBack.on(ScanBackState.Building, points = 4_000))
    }

    @Test
    fun `only the building scan holds back in place`() {
        val staying = ScanBackState.entries.filter { state ->
            listOf(0, 1, 4_000).any { ScanBack.on(state, it) == ScanBackAction.Stay }
        }
        assertEquals(listOf(ScanBackState.Building), staying)
    }

    @Test
    fun `the arrow and the gesture take the same decision`() {
        // The decision has no notion of where Back came from, so the screen must hand both to
        // the same handler: one `scanBack`, given to the system gesture and to the bar's arrow.
        val record = File("src/main/java/io/github/sceneview/demo/demos/ARRerunDemo.kt").readText()
            .substringAfter("val backAction = ScanBack.on(", missingDelimiterValue = "")
            .replace(Regex("""\s+"""), " ")
        assertTrue("the Record screen's Back decision was moved — point this test at it", record.isNotEmpty())
        assertTrue(
            "the gesture is not swallowed while the scan is built",
            record.contains("BackHandler(enabled = backAction != ScanBackAction.Leave, onBack = scanBack)"),
        )
        assertTrue(
            "the arrow does not go through the same decision as the gesture",
            record.substringAfter("DemoScaffold(").substringBefore("controls = {").contains("onBack = scanBack,"),
        )
        assertTrue(
            "Stay must do nothing: no dialog, no leaving",
            record.contains("ScanBackAction.Stay -> Unit"),
        )
    }

    @Test
    fun `every state and count has an answer and only one of them asks`() {
        val asking = ScanBackState.entries.flatMap { state ->
            listOf(0, 1, 4_000).filter { ScanBack.on(state, it) == ScanBackAction.Ask }.map { state to it }
        }
        assertEquals(listOf(ScanBackState.Recording to 1, ScanBackState.Recording to 4_000), asking)
    }

    @Test
    fun `the clock follows now while the scan runs`() {
        assertEquals(177_000L, ScanClock.readAt(nowNanos = 177_000L, stoppedNanos = null))
    }

    @Test
    fun `the clock holds the time of Stop while the scan is built`() {
        val stopped = 176_000L
        assertEquals(stopped, ScanClock.readAt(nowNanos = 177_000L, stoppedNanos = stopped))
        assertEquals(stopped, ScanClock.readAt(nowNanos = 186_000L, stoppedNanos = stopped))
    }

    @Test
    fun `a Stop stamped after the last tick does not run the clock ahead`() {
        assertEquals(176_000L, ScanClock.readAt(nowNanos = 176_000L, stoppedNanos = 176_400L))
    }
}
