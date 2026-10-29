package io.github.sceneview.demo.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-memory AR tracking history behind the bug report (#4529). */
class ArTrackingRingTest {

    private fun line(seconds: Int) =
        "10-13 16:55:46.108 29383 29383 I SceneView: AR tracking: state=PAUSED reason=NONE for=${seconds}s"

    @Test
    fun `keeps the newest lines up to its capacity, oldest first`() {
        val ring = ArTrackingRing(capacity = 3)
        (1..5).forEach { ring.offer(line(it)) }
        assertEquals(listOf(line(3), line(4), line(5)), ring.snapshot())
    }

    @Test
    fun `memory stays bounded over a long session`() {
        val ring = ArTrackingRing()
        // An hour of a camera that never tracks, one line every ten seconds.
        repeat(360) { ring.offer(line(it * 10)) }
        assertEquals(AR_TRACKING_MAX_LINES, ring.snapshot().size)
        assertEquals(line(3590), ring.snapshot().last())
    }

    @Test
    fun `only whole AR tracking lines get in`() {
        val ring = ArTrackingRing()
        val path = "10-13 16:55:40.000 29383 29383 E SceneView: cannot load content://x/AR tracking: a.spz"
        assertFalse(ring.offer(path))
        assertFalse(ring.offer("--------- beginning of main"))
        assertTrue(ring.offer(line(10)))
        assertEquals(listOf(line(10)), ring.snapshot())
    }

    @Test
    fun `a snapshot is a copy`() {
        val ring = ArTrackingRing()
        ring.offer(line(1))
        val snapshot = ring.snapshot()
        ring.offer(line(2))
        assertEquals(1, snapshot.size)
        ring.clear()
        assertTrue(ring.snapshot().isEmpty())
    }

    @Test
    fun `the report prefers the history over the log buffer`() {
        var readLogcat = false
        val lines = reportArTracking(listOf(line(1))) { readLogcat = true; listOf(line(99)) }
        assertEquals(listOf(line(1)), lines)
        assertFalse("the log buffer is not read when the history has lines", readLogcat)
    }

    @Test
    fun `the report falls back to the log buffer when the history is empty`() {
        assertEquals(listOf(line(99)), reportArTracking(emptyList()) { listOf(line(99)) })
    }

    @Test
    fun `the report carries no more than sixty history lines`() {
        val history = (1..AR_TRACKING_MAX_LINES + 10).map(::line)
        val lines = reportArTracking(history) { emptyList() }
        assertEquals(AR_TRACKING_MAX_LINES, lines.size)
        assertEquals(line(AR_TRACKING_MAX_LINES + 10), lines.last())
    }
}
