package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DebugPlaneKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DollhouseReplayTest {
    @Test
    fun `loop wraps at the end and across multiple turns`() {
        assertEquals(0f, dollhouseLoopTime(10f, 10f), 0f)
        assertEquals(2.5f, dollhouseLoopTime(32.5f, 10f), 0.0001f)
        assertEquals(9f, dollhouseLoopTime(-1f, 10f), 0f)
    }

    @Test
    fun `empty and invalid durations hold the start`() {
        assertEquals(0f, dollhouseLoopTime(3f, 0f), 0f)
        assertEquals(0f, dollhouseLoopTime(3f, Float.NaN), 0f)
        assertEquals(0f, dollhouseLoopTime(Float.POSITIVE_INFINITY, 10f), 0f)
        assertNull(dollhouseReplayFrame(ArDebugTrace(), 3f).camera)
    }

    @Test
    fun `the camera and points advance then return to the first frame`() {
        val trace = ArDebugTrace()
        trace.addPose(0L, DebugPose(0f, 0f, 0f))
        trace.addPoints(0L, floatArrayOf(0f, 0f, 0f))
        trace.addPose(2_000_000_000L, DebugPose(2f, 0f, 0f))
        trace.addPoints(2_000_000_000L, floatArrayOf(1f, 0f, 0f))
        trace.addPlane(2_000_000_000L, 1, DebugPlaneKind.Floor,
            floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f))
        trace.addPose(4_000_000_000L, DebugPose(4f, 0f, 0f))
        assertEquals(0f, dollhouseReplayFrame(trace, 1f).camera!!.x, 0f)
        assertEquals(2f, dollhouseReplayFrame(trace, 3f).camera!!.x, 0f)
        assertEquals(0, dollhouseReplayFrame(trace, 1f).planes.size)
        assertEquals(1, dollhouseReplayFrame(trace, 3f).planes.size)
        assertEquals(2, dollhouseReplayFrame(trace, 3f).mapPointCount)
        assertEquals(0f, dollhouseReplayFrame(trace, 4f).camera!!.x, 0f)
        assertEquals(1, dollhouseReplayFrame(trace, 5f).mapPointCount)
        assertEquals(0, dollhouseReplayFrame(trace, 5f).planes.size)
    }

    @Test
    fun `dense replay reveals only retained points already recorded`() {
        val kept = intArrayOf(1, 4, 7)
        assertEquals(0, dollhouseRetainedPointCount(kept, 1))
        assertEquals(1, dollhouseRetainedPointCount(kept, 4))
        assertEquals(2, dollhouseRetainedPointCount(kept, 7))
        assertEquals(3, dollhouseRetainedPointCount(kept, 10))
        assertEquals(0, dollhouseRetainedPointCount(intArrayOf(), 10))
    }
}
