package io.github.sceneview.demo.demos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

class ScanCaptureCountersTest {
    @Test
    fun `capture totals survive repeated logs and count each loss separately`() {
        val counters = ScanFrameCounters()
        counters.acceptDepth()
        repeat(2) { counters.busyDepth() }
        repeat(3) { counters.staleDepth() }
        repeat(4) { counters.dropTsdf() }
        repeat(5) { counters.unavailableDepth() }
        val expected = ScanFrameCounts(1, 2, 3, 4, 5)
        repeat(2) {
            assertEquals(expected, counters.snapshot())
            assertEquals("depthAccepted=1 depthBusy=2 depthStale=3 tsdfDropped=4 depthUnavailable=5", counters.snapshot().logLine())
            assertEquals(
                """{"schema":2,"event":"depth","depth_accepted":1,"depth_busy":2,"depth_stale":3,"depth_unavailable":5,"tsdf_dropped":4}""",
                depthLine(counters.snapshot()),
            )
        }
        counters.acceptDepth()
        assertEquals(expected.copy(depthAccepted = 2), counters.snapshot())
    }

    @Test
    fun `main and fusion workers can count while the bench reads`() {
        val counters = ScanFrameCounters()
        val start = CountDownLatch(1)
        val threads = List(4) { Thread {
            start.await()
            repeat(1_000) {
                counters.acceptDepth()
                counters.busyDepth()
                counters.staleDepth()
                counters.dropTsdf()
                counters.unavailableDepth()
            }
        }.apply { start() } }
        start.countDown()
        var previous = ScanFrameCounts()
        while (threads.any { it.isAlive }) {
            val next = counters.snapshot()
            assertTrue(next.depthAccepted >= previous.depthAccepted)
            assertTrue(next.depthBusy >= previous.depthBusy)
            assertTrue(next.depthStale >= previous.depthStale)
            assertTrue(next.tsdfDropped >= previous.tsdfDropped)
            assertTrue(next.depthUnavailable >= previous.depthUnavailable)
            previous = next
        }
        threads.forEach { it.join() }
        assertEquals(ScanFrameCounts(4_000, 4_000, 4_000, 4_000, 4_000), counters.snapshot())
    }
}
