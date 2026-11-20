package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArDebugTraceMemoryTest {
    @Test
    fun `seven minute coloured scan respects the total budget and retries replay across the timeline`() {
        var warnings = 0
        val trace = ArDebugTrace(
            observationMaxBytes = ArDebugTrace.MAX_OBSERVATION_BYTES,
            observationMaxCount = ArDebugTrace.MAX_OBSERVATIONS,
        ) { warnings++ }.apply { journal = emptyList() }
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
    fun `many large observations bound their actual indices`() {
        val trace = ArDebugTrace(
            observationMaxBytes = ArDebugTrace.MAX_OBSERVATION_BYTES,
            observationMaxCount = ArDebugTrace.MAX_OBSERVATIONS,
        ).apply { journal = emptyList() }
        val points = FloatArray(ArDebugTrace.MAX_MAP_POINTS * 3) { (it / 3) * 0.04f }
        repeat(300) {
            trace.addPoints(it * 100_000_000L, points)
        }
        repeat(ArDebugTrace.MAX_OBSERVATIONS + 100) {
            trace.addPoints((it + 300) * 100_000_000L, FloatArray(0))
        }
        assertTrue(observationBytes(trace) <= ArDebugTrace.MAX_OBSERVATION_BYTES)
        assertTrue(trace.observationCount <= ArDebugTrace.MAX_OBSERVATIONS)
    }

    @Test
    fun `capped journal replays every image floor cell anchor and plane tombstone`() {
        val journal = ArDebugJournal(16_384)
        val critical = ArrayList<ArDebugEvent>()
        repeat(80) { tick ->
            val nanos = tick * 1_000_000_000L
            journal.append(ArDebugEvent.CameraPose(nanos, DebugPose(tick.toFloat(), 1f, 0f)))
            journal.append(ArDebugEvent.Points(nanos, FloatArray(300) { it * 0.04f }, null))
            journal.append(ArDebugEvent.Plane(nanos, 1, DebugPlaneKind.Floor, FloatArray(90) { tick.toFloat() }))
            if (tick % 10 == 0) {
                journal.append(ArDebugEvent.Plane(nanos, tick + 10, DebugPlaneKind.Wall,
                    floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f)))
                val events = listOf(
                    ArDebugEvent.Image(nanos, "frames/$tick.jpg"),
                    ArDebugEvent.FloorCells(nanos, longArrayOf(tick.toLong())),
                    ArDebugEvent.Anchor(nanos, tick, DebugPose(tick.toFloat(), 0f, 0f)),
                    ArDebugEvent.Plane(nanos + 500_000_000L, tick + 10, DebugPlaneKind.Wall, FloatArray(0)),
                )
                critical.addAll(events)
                events.forEach(journal::append)
            }
        }
        assertTrue(journal.capped)
        assertTrue(journal.bytes <= journal.maxBytes)
        critical.forEach { event -> assertTrue("lost ${event.javaClass.simpleName} at ${event.nanos}", event in journal) }
        val replay = ArDebugTrace.of(journal)
        assertEquals(8, replay.imageCount)
        assertEquals(8, replay.floorCellsAt(replay.duration))
        assertEquals(8, replay.frameAt(replay.duration).anchors.size)
        critical.filterIsInstance<ArDebugEvent.Image>().forEach {
            val frame = replay.frameAt(it.nanos / 1e9f)
            assertEquals(it.path, frame.image)
            assertEquals(it.nanos / 1e9f, frame.camera!!.x, 0.0001f)
        }
        critical.filterIsInstance<ArDebugEvent.Plane>().forEach { tombstone ->
            val seconds = (tombstone.nanos / 1e9).toFloat()
            assertTrue(replay.frameAt(seconds - 0.25f).planes.any { it.id == tombstone.id })
            assertTrue(replay.frameAt(seconds).planes.none { it.id == tombstone.id })
        }
        println("capped journal: ${journal.size} events, ${journal.bytes} bytes; all 32 critical events replayed")
    }

    @Test
    fun `plane endpoints and tombstones survive even an irreducible metadata overflow`() {
        val journal = ArDebugJournal(700)
        val pose = ArDebugEvent.CameraPose(0, DebugPose(0f, 0f, 0f))
        journal.append(pose)
        repeat(9) { journal.append(ArDebugEvent.Plane(it.toLong(), 1, DebugPlaneKind.Wall, FloatArray(30) { it.toFloat() })) }
        val snapshots = journal.filterIsInstance<ArDebugEvent.Plane>()
        assertEquals(2, snapshots.size)
        assertEquals(0L, snapshots.first().nanos)
        assertEquals(8L, snapshots.last().nanos)
        assertTrue(pose in journal)
        val tombstone = ArDebugEvent.Plane(10, 1, DebugPlaneKind.Wall, FloatArray(0))
        journal.append(tombstone)
        val floor = ArDebugEvent.FloorCells(11, LongArray(100))
        journal.append(floor)
        assertTrue(tombstone in journal)
        assertTrue(floor in journal)
        assertEquals(3, journal.filterIsInstance<ArDebugEvent.Plane>().size)
        assertEquals(journal.sumOf(ArDebugJournal::bytesOf), journal.bytes)
    }

    @Test
    fun `plane thinning frees live history and preserves original appearance throughout replay`() {
        val trace = ArDebugTrace(journalMaxBytes = 2_048).apply { journal = emptyList() }
        trace.addPose(0L, DebugPose(0f, 1f, 0f))
        repeat(100) { tick ->
            trace.addPlane((tick + 1) * 1_000_000_000L, 7, DebugPlaneKind.Floor,
                FloatArray(90) { if (it % 3 == 1) 0f else tick + it * 0.01f })
        }
        val journal = checkNotNull(trace.journal)
        val snapshots = journal.filterIsInstance<ArDebugEvent.Plane>()
        assertTrue(snapshots.size in 2 until 100)
        assertEquals(1_000_000_000L, snapshots.first().nanos)
        assertEquals(100_000_000_000L, snapshots.last().nanos)
        assertTrue(trace.journalBytes <= 2_048)
        // Inspect the actual strong references, not just the journal's byte counter.
        val field = ArDebugTrace::class.java.getDeclaredField("planeHistory").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val history = field.get(trace) as Map<Int, List<Pair<Float, DebugPlane>>>
        assertEquals(snapshots.size, history.getValue(7).size)
        history.getValue(7).forEach { (_, plane) ->
            assertTrue(snapshots.any { it.polygon === plane.polygon })
        }
        for (timeline in listOf(trace, ArDebugTrace.of(journal))) {
            assertTrue(timeline.frameAt(0.999f).planes.isEmpty())
            for (seconds in listOf(1f, 2f, 50f, 99f, 100f)) {
                assertEquals("floor at $seconds", 7, timeline.frameAt(seconds).planes.single().id)
            }
        }
    }

    @Test
    fun `saved replay retains observations beyond live byte and count caps without blinking`() {
        val events = ArrayList<ArDebugEvent>()
        val points = FloatArray(3_000) { it * 0.04f }
        repeat(6_101) { tick ->
            val nanos = tick * 200_000_000L
            events.add(ArDebugEvent.CameraPose(nanos, DebugPose(tick * 0.01f, 1f, 0f)))
            events.add(ArDebugEvent.Points(nanos, points, null))
        }
        val replay = ArDebugTrace.of(events)
        assertEquals(6_101, replay.observationCount)
        assertTrue(observationBytes(replay) > ArDebugTrace.MAX_OBSERVATION_BYTES)
        for (tick in 0..6_100 step 7) {
            assertTrue("live points blinked at $tick", replay.frameAt(tick * 0.2f).livePoints.isNotEmpty())
        }
    }

    @Test
    fun `live trace uses the supplied observation budget`() {
        val small = ArDebugTrace(observationMaxBytes = 48_000)
        val large = ArDebugTrace(observationMaxBytes = 96_000)
        val points = FloatArray(3_000) { it * 0.04f }
        repeat(20) {
            small.addPoints(it * 200_000_000L, points)
            large.addPoints(it * 200_000_000L, points)
        }
        assertTrue(observationBytes(small) <= 48_000)
        assertTrue(observationBytes(large) <= 96_000)
        assertTrue(large.observationCount > small.observationCount)
    }

    @Test(timeout = 2_000)
    fun `an observation larger than its allowance cannot stall thinning`() {
        val trace = ArDebugTrace()
        // Fault-inject an allowance below today's constructor minimum to exercise the guard
        // for a future larger observation. Half-thinning an empty or singleton list cannot help.
        ArDebugTrace::class.java.getDeclaredField("observationMaxBytes").apply {
            isAccessible = true
            setLong(trace, 4L)
        }
        repeat(10) { tick ->
            trace.addPoints(tick * 1_000_000_000L, floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 2f, 0f, 0f))
        }
        assertTrue(trace.observationCount <= 2)
        assertEquals(9f, trace.observationTime(trace.observationCount - 1), 0f)
        assertEquals(9, trace.frameAt(9f).livePoints.size)
    }

    private fun observationBytes(trace: ArDebugTrace): Long =
        (0 until trace.observationCount).sumOf { trace.observationPoints(it).size * 4L }
}
