package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the data behind the Rerun demo's in-app 3D view (#3950): what [ArDebugTrace.frameAt]
 * returns is exactly what the view draws, so a scrub to any instant must show the session as it
 * stood then — nothing from the future, nothing lost from the past.
 */
class ArDebugTraceTest {

    private fun seconds(s: Float) = BASE + (s * 1e9).toLong()

    @Test
    fun `the trail at an instant holds only the poses before it`() {
        val trace = ArDebugTrace()
        for (i in 0..10) trace.addPose(seconds(i * 0.1f), DebugPose(i * 0.1f, 0f, 0f))

        assertEquals(1f, trace.duration, 1e-4f)
        assertEquals(11, trace.frameAt(trace.duration).trailLength)
        val half = trace.frameAt(0.5f)
        assertEquals(6, half.trailLength)
        assertEquals(0.5f, half.camera!!.x, 1e-4f)
    }

    @Test
    fun `a phone held still does not grow the trail, but time moves on`() {
        val trace = ArDebugTrace()
        repeat(300) { trace.addPose(seconds(it / 30f), DebugPose(0f, 0f, 0f)) }

        assertEquals(1, trace.poseCount)
        assertEquals(299 / 30f, trace.duration, 1e-3f)
    }

    @Test
    fun `a turn in place is kept even without moving`() {
        val trace = ArDebugTrace()
        trace.addPose(seconds(0f), DebugPose(0f, 0f, 0f))
        // 20 degrees about Y.
        trace.addPose(seconds(0.1f), DebugPose(0f, 0f, 0f, 0f, 0.1736f, 0f, 0.9848f))

        assertEquals(2, trace.poseCount)
    }

    @Test
    fun `the pose count stays bounded on a long walk`() {
        val trace = ArDebugTrace()
        repeat(ArDebugTrace.MAX_POSES + 500) { trace.addPose(seconds(it / 30f), DebugPose(it * 0.01f, 0f, 0f)) }

        assertTrue(trace.poseCount <= ArDebugTrace.MAX_POSES)
        // The head survives the thinning: the live frustum is where the phone is.
        val head = trace.frameAt(trace.duration).camera!!
        assertEquals((ArDebugTrace.MAX_POSES + 499) * 0.01f, head.x, 1e-3f)
    }

    @Test
    fun `points seen twice are merged, low-confidence points dropped`() {
        val trace = ArDebugTrace()
        trace.addPoints(seconds(0f), floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f), floatArrayOf(0.9f, 0.9f))
        trace.addPoints(seconds(0.2f), floatArrayOf(0.001f, 0.001f, 0.001f, 2f, 0f, 0f), floatArrayOf(0.9f, 0.05f))

        assertEquals(2, trace.mapPointCount)
        val frame = trace.frameAt(0.2f)
        assertEquals(2, frame.mapPointCount)
        // The live cloud is the last observation: only the merged point passed the confidence bar.
        assertEquals(3, frame.livePoints.size)
    }

    @Test
    fun `the map at an instant holds only points first seen before it`() {
        val trace = ArDebugTrace()
        trace.addPoints(seconds(0f), floatArrayOf(0f, 0f, 0f))
        trace.addPoints(seconds(1f), floatArrayOf(1f, 0f, 0f))

        assertEquals(1, trace.frameAt(0.5f).mapPointCount)
        assertEquals(2, trace.frameAt(1f).mapPointCount)
    }

    @Test
    fun `live points fade out once the cloud goes quiet`() {
        val trace = ArDebugTrace()
        trace.addPoints(seconds(0f), floatArrayOf(0f, 0f, 0f))
        trace.addPose(seconds(5f), DebugPose(1f, 0f, 0f))

        assertEquals(3, trace.frameAt(0.5f).livePoints.size)
        assertEquals(0, trace.frameAt(5f).livePoints.size)
        assertEquals(1, trace.frameAt(5f).mapPointCount)
    }

    @Test
    fun `a plane grows, then goes away when its polygon is emptied`() {
        val trace = ArDebugTrace()
        val small = square(0.5f)
        val large = square(1f)
        trace.addPlane(seconds(0f), 7, DebugPlaneKind.Floor, small)
        trace.addPlane(seconds(1f), 7, DebugPlaneKind.Floor, large)
        trace.addPlane(seconds(2f), 7, DebugPlaneKind.Floor, FloatArray(0))

        assertTrue(trace.frameAt(0.5f).planes.single().polygon.contentEquals(small))
        assertTrue(trace.frameAt(1.5f).planes.single().polygon.contentEquals(large))
        assertTrue(trace.frameAt(2f).planes.isEmpty())
    }

    @Test
    fun `an unchanged plane snapshot is not stored twice`() {
        val trace = ArDebugTrace()
        trace.addPlane(seconds(0f), 1, DebugPlaneKind.Wall, square(1f))
        val version = trace.version
        trace.addPlane(seconds(1f), 1, DebugPlaneKind.Wall, square(1f))

        assertEquals(version, trace.version)
        // Same object across instants: the view keys its plane mesh on that identity.
        assertTrue(trace.frameAt(0f).planes.single() === trace.frameAt(0f).planes.single())
    }

    @Test
    fun `anchors appear at the instant they were placed`() {
        val trace = ArDebugTrace()
        trace.addPose(seconds(0f), DebugPose(0f, 0f, 0f))
        trace.addAnchor(seconds(2f), 1, DebugPose(0f, -1f, -1f))

        assertTrue(trace.frameAt(1f).anchors.isEmpty())
        val anchor = trace.frameAt(2f).anchors.single()
        assertEquals(1, anchor.id)
        assertEquals(2f, anchor.placedAt, 1e-4f)
    }

    @Test
    fun `an empty trace draws nothing`() {
        val frame = ArDebugTrace().frameAt(0f)

        assertEquals(0, frame.trailLength)
        assertNull(frame.camera)
        assertEquals(0, frame.mapPointCount)
        assertTrue(frame.planes.isEmpty())
    }

    @Test
    fun `keyframes are spaced along the path and never sit on the live frustum`() {
        val trace = ArDebugTrace()
        for (i in 0..100) trace.addPose(seconds(i * 0.1f), DebugPose(i * 0.05f, 0f, 0f))
        val frame = trace.frameAt(trace.duration)

        assertTrue(frame.keyframes.size >= 5)
        for (i in 1 until frame.keyframes.size) {
            assertTrue(frame.keyframes[i].x - frame.keyframes[i - 1].x >= ArDebugTrace.KEYFRAME_SPACING_M - 0.06f)
        }
        assertTrue(frame.camera!!.x - frame.keyframes.last().x >= ArDebugTrace.KEYFRAME_SPACING_M * 0.5f)
    }

    private fun square(half: Float) = floatArrayOf(
        -half, 0f, -half,
        half, 0f, -half,
        half, 0f, half,
        -half, 0f, half,
    )

    private companion object {
        const val BASE = 5_000_000_000L
    }
}
