package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the reader of the Rerun bridge's wire format behind the in-app 3D view's QA path (#3950),
 * and the QA fixture itself: it must parse, and it must hold a session worth looking at.
 */
class ArDebugLogTest {

    @Test
    fun `each drawn event type parses`() {
        val pose = parseArDebugEvent(
            """{"t":1000,"type":"camera_pose","entity":"world/camera","translation":[1,2,3],"quaternion":[0,0,0,1]}"""
        ) as ArDebugEvent.CameraPose
        assertEquals(DebugPose(1f, 2f, 3f), pose.pose)
        assertEquals(1000L, pose.nanos)

        val points = parseArDebugEvent(
            """{"t":2,"type":"point_cloud","entity":"world/points","positions":[[0,0,0],""" +
                """[1,1,1]],"confidences":[0.5,0.9]}"""
        ) as ArDebugEvent.Points
        assertEquals(6, points.positions.size)
        assertEquals(2, points.confidences!!.size)

        val plane = parseArDebugEvent(
            """{"t":3,"type":"plane","entity":"world/planes/42","kind":"vertical","polygon":[[0,0,0],""" +
                """[1,0,0],[1,1,0]]}"""
        ) as ArDebugEvent.Plane
        assertEquals(42, plane.id)
        assertEquals(DebugPlaneKind.Wall, plane.kind)

        val anchor = parseArDebugEvent(
            """{"t":4,"type":"anchor","entity":"world/anchors/3","translation":[0,-1,-1]}"""
        ) as ArDebugEvent.Anchor
        assertEquals(3, anchor.id)
        assertEquals(1f, anchor.pose.qw, 0f)
    }

    @Test
    fun `what the view does not draw, and broken lines, are skipped`() {
        assertNull(parseArDebugEvent("""{"t":1,"type":"scalar","entity":"x","value":3}"""))
        assertNull(parseArDebugEvent("""{"t":1,"type":"camera_pose","translation":[1,2]}"""))
        assertNull(parseArDebugEvent("""{"t":1,"type":"camera_pose","trans"""))
        assertNull(parseArDebugEvent(""))
        assertNull(parseArDebugEvent("not json"))

        val log = parseArDebugLog(
            sequenceOf(
                """{"t":1,"type":"camera_pose","translation":[0,0,0]}""",
                """{"t":2,"type":"camera_pose","trans""",
                """{"t":3,"type":"camera_pose","translation":[1,0,0]}""",
            )
        )
        assertEquals(2, log.size)
    }

    @Test
    fun `the QA fixture is a full session — a walk, a map, planes and an anchor`() {
        val events = parseArDebugLog(fixture().readLines().asSequence())
        val trace = ArDebugTrace.of(events)
        val frame = trace.frameAt(trace.duration)

        assertTrue(trace.duration > 20f)
        assertTrue(frame.trailLength > 100)
        assertNotNull(frame.camera)
        assertTrue(frame.mapPointCount > 500)
        assertTrue(frame.planes.any { it.kind == DebugPlaneKind.Floor })
        assertTrue(frame.planes.any { it.kind == DebugPlaneKind.Wall })
        assertTrue(frame.anchors.isNotEmpty())
        val path = ArDebugStats.pathLength(frame.trail)
        assertTrue("a room-sized walk, got $path m", path in 2f..40f)
    }

    @Test
    fun `the player fills the trace as time passes, then starts a fresh one`() {
        val events = (0..10).map {
            ArDebugEvent.CameraPose(1_000_000_000L + it * 100_000_000L, DebugPose(it * 0.1f, 0f, 0f))
        }
        val player = ArDebugLogPlayer(events, loopPauseSeconds = 0.5f)

        assertTrue(player.trace.isEmpty)
        player.advance(0.2f)
        player.advance(0.2f)
        val first = player.trace
        assertEquals(5, first.poseCount)

        repeat(10) { player.advance(0.2f) }
        assertTrue(player.trace !== first)
    }

    private fun fixture(): File = listOf(
        File("src/main/assets/rerun/sample-session.jsonl"),
        File("samples/android-demo/src/main/assets/rerun/sample-session.jsonl"),
    ).first { it.exists() }
}
