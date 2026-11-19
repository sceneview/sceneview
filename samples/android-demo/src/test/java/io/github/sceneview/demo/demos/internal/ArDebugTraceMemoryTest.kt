package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArDebugTraceMemoryTest {
    @Test
    fun `seven minute coloured scan respects the total budget and retries replay across the timeline`() {
        var warnings = 0
        val trace = ArDebugTrace { warnings++ }.apply { journal = emptyList() }
        val points = FloatArray(3_000) { (it % 100) * 0.04f }
        val colors = IntArray(1_000) { 0xff224466.toInt() }
        repeat(4_201) { tick ->
            val nanos = tick * 100_000_000L
            trace.addPose(nanos, DebugPose(tick * 0.01f, 1f, 0f))
            trace.addPoints(nanos, points, colors = colors)
            if (tick % 100 == 0) trace.addImage(nanos, "frames/$tick.jpg")
            assertTrue("journal at $tick", trace.journalBytes <= ArDebugJournal.MAX_BYTES)
            assertTrue("observations at $tick", observationBytes(trace) <= ArDebugTrace.MAX_OBSERVATION_BYTES)
            assertTrue(trace.observationCount <= ArDebugTrace.MAX_OBSERVATIONS)
        }
        assertEquals(1, warnings)
        val finishedEvents = checkNotNull(trace.journal).toList()
        trace.journal = null
        assertTrue(finishedEvents.sumOf { ArDebugJournal.bytesOf(it) } <= ArDebugJournal.MAX_BYTES)
        // A failed build and Try again read the same bounded events, without an Android camera.
        repeat(2) {
            val replay = ArDebugTrace.of(finishedEvents)
            assertEquals(420f, replay.duration, 0.001f)
            for (seconds in listOf(0f, 210f, 420f)) {
                val frame = replay.frameAt(seconds)
                assertTrue("trail at $seconds", frame.trailLength > 0)
                assertTrue("map at $seconds", frame.mapPointCount > 0)
            }
            assertEquals(42f, replay.frameAt(420f).camera!!.x, 0.001f)
            finishedEvents.filterIsInstance<ArDebugEvent.Image>().forEach { image ->
                val frame = replay.frameAt((image.nanos / 1e9).toFloat())
                assertEquals("photo pose ${image.path}", image.nanos / 1e10f, frame.camera!!.x, 0.001f)
            }
        }
    }

    @Test
    fun `metadata and oversized arrays cannot bypass the total cap`() {
        var warnings = 0
        val journal = ArDebugJournal { warnings++ }
        repeat(70_000) { journal.append(ArDebugEvent.DepthStats(it.toLong(), 0, 0, it)) }
        assertTrue(journal.bytes <= ArDebugJournal.MAX_BYTES)
        assertEquals(69_999L, journal.last().nanos)
        journal.append(ArDebugEvent.Points(70_000L, FloatArray(1_100_000), null))
        assertTrue(journal.bytes <= ArDebugJournal.MAX_BYTES)
        assertEquals(1, warnings)
        assertEquals(journal.sumOf { ArDebugJournal.bytesOf(it) }, journal.bytes)
    }

    @Test
    fun `many large observations bound their actual indices and clear releases the trace`() {
        val trace = ArDebugTrace().apply { journal = emptyList() }
        val points = FloatArray(ArDebugTrace.MAX_MAP_POINTS * 3) { (it / 3) * 0.04f }
        repeat(300) {
            trace.addPoints(it * 100_000_000L, points)
        }
        repeat(ArDebugTrace.MAX_OBSERVATIONS + 100) {
            trace.addPoints((it + 300) * 100_000_000L, FloatArray(0))
        }
        assertTrue(observationBytes(trace) <= ArDebugTrace.MAX_OBSERVATION_BYTES)
        assertTrue(trace.observationCount <= ArDebugTrace.MAX_OBSERVATIONS)
        trace.clear()
        assertTrue(trace.isEmpty)
        assertEquals(0, trace.mapPointCount)
        assertEquals(0, trace.observationCount)
        assertEquals(0L, trace.journalBytes)
        assertEquals(0, trace.frameAt(0f).mapPoints.size)
    }

    private fun observationBytes(trace: ArDebugTrace): Long =
        (0 until trace.observationCount).sumOf { trace.observationPoints(it).size * 4L }
}
