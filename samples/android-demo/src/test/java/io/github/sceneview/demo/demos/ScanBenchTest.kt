package io.github.sceneview.demo.demos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanBenchTest {
    private val lines = mutableListOf<String>()
    private var now = 1_794_500_000_000L
    private val bench = ScanBench(clockMs = { now }, sink = { lines += it })

    @Test
    fun cadenceCountsTheFramesOfEachOneSecondWindow() {
        repeat(30) { bench.frame("TRACKING", 0f); now += 33 }
        now += 100
        bench.frame("TRACKING", 0f)
        val cadence = lines.single()
        assertTrue(cadence, cadence.contains(""""event":"cadence","fps":30,"window_start_epoch_ms":1794500000000"""))
        assertTrue(cadence, cadence.startsWith("""{"schema":2"""))
    }

    @Test
    fun surfaceReadyIsSentOnceWhenTheFloorIsFirstCovered() {
        bench.frame("TRACKING", 0f)
        bench.frame("TRACKING", 0.04f)
        bench.frame("TRACKING", 0.5f)
        assertEquals(1, lines.size)
        assertEquals(
            """{"schema":2,"event":"surface_ready","surface_ready":true,"covered_m2":0.04,"outside_elements":null}""",
            lines.single(),
        )
    }

    @Test
    fun aNonFiniteAreaIsSentAsNull() {
        repeat(2) { bench.frame("PAUSED", Float.NaN); now += 600 }
        bench.frame("PAUSED", Float.NaN)
        assertTrue(lines.single(), lines.single().endsWith(""""covered_m2":null}"""))
    }
}
