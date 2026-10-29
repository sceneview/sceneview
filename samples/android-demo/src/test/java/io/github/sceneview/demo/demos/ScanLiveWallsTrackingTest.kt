package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.DebugPlaneKind
import io.github.sceneview.demo.demos.internal.DebugPose
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 3D Scan's live walls through a tracking loss. The recorder writes nothing while ARCore has lost
 * the camera, so the live trace stops, then jumps on when tracking comes back — often with the
 * walls' planes not found again yet, while the floor already is. The walls wait for them under
 * the same ids instead of going and coming back as new walls. A loss is read off the camera poses
 * the recorder journals, never off how often the view observes a frame: a busy or slow phone that
 * observes three frames a second still tracks, and its walls still come and go.
 */
class ScanLiveWallsTrackingTest {
    /** Recording, as a room scan's trace is: every camera pose kept in its journal. */
    private val trace = ArDebugTrace().apply { journal = ArrayList<ArDebugEvent>() }
    private val walls = ScanWallsState()
    private val coverage = ScanCoverageState()

    /** A vertical plane from (x0, z0) to (x1, z1), 0.1 m to 2.3 m over a floor at 0. */
    private fun wall(x0: Float, z0: Float, x1: Float, z1: Float) =
        floatArrayOf(x0, 0.1f, z0, x1, 0.1f, z1, x1, 2.3f, z1, x0, 2.3f, z0)

    private val front = wall(-2f, -2.6f, 2f, -2.6f)
    private val side = wall(-2f, -2.6f, -2f, 1f)
    private val right = wall(2f, -2.6f, 2f, 1f)
    private val floor = floatArrayOf(-2f, 0f, -2.6f, 2f, 0f, -2.6f, 2f, 0f, 1f, -2f, 0f, 1f)

    private fun nanos(seconds: Float) = (seconds * 1e9).toLong()

    /** The camera at [seconds], as the recorder writes it on every frame ARCore tracks. */
    private fun pose(seconds: Float) = trace.addPose(nanos(seconds), DebugPose(0f, 1.4f, 0f))

    /** The live view observes the trace's latest frame as the record screen does: the ids shown then. */
    private fun observe(): Set<Int> {
        val frame = trace.frameAt(trace.duration)
        coverage.observe(frame, pose = null, record = trace)
        walls.observe(frame, coverage, pose = null)
        return walls.snapshot.walls.mapTo(HashSet()) { it.id }
    }

    /** The camera at [seconds], that frame observed: the ids shown then. */
    private fun frame(seconds: Float): Set<Int> {
        pose(seconds)
        return observe()
    }

    /** The ids shown at each frame, 30 a second, from [from] up to [to] seconds. */
    private fun frames(from: Float, to: Float): List<Set<Int>> =
        (0..((to - from) * FPS).toInt()).map { frame(from + it / FPS) }

    /** The camera tracked 30 times a second for [count] frames after [last] seconds, none observed. */
    private fun posesAfter(last: Float, count: Int) = (1..count).forEach { pose(last + it / FPS) }

    private fun room(vararg walls: FloatArray) {
        trace.addPlane(0L, 1, DebugPlaneKind.Floor, floor)
        walls.forEachIndexed { i, polygon -> trace.addPlane(0L, 2 + i, DebugPlaneKind.Wall, polygon) }
    }

    @Test
    fun `the walls keep their ids through two seconds of tracking lost`() {
        room(front, side)
        val before = frames(0f, 2f).last()
        assertEquals("walls before the loss", 2, before.size)
        // Lost from 2 s to 4 s: nothing recorded. Back at 4 s with the floor, the walls' planes not
        // found again yet; ARCore has them again a third of a second later, under new ids.
        trace.addPlane(nanos(4f), 2, DebugPlaneKind.Wall, FloatArray(0))
        trace.addPlane(nanos(4f), 3, DebugPlaneKind.Wall, FloatArray(0))
        frames(4f, 4.3f).forEachIndexed { i, shown -> assertEquals("walls as tracking comes back, frame $i", before, shown) }
        trace.addPlane(nanos(4.35f), 4, DebugPlaneKind.Wall, front)
        trace.addPlane(nanos(4.35f), 5, DebugPlaneKind.Wall, side)
        frames(4.35f, 5f).forEachIndexed { i, shown -> assertEquals("walls with the planes back, frame $i", before, shown) }
    }

    @Test
    fun `a wall no plane holds up still goes while ARCore tracks`() {
        room(front, side)
        assertEquals(2, frames(0f, 2f).last().size)
        trace.addPlane(nanos(2.05f), 3, DebugPlaneKind.Wall, FloatArray(0))
        assertEquals("a second after a plane went for good", 1, frames(2.05f, 3f).last().size)
    }

    @Test
    fun `a frame observed 0,2 s after the last, the camera tracked in between, is tracking`() {
        room(front)
        assertEquals(1, frames(0f, 1f).last().size)
        // The view observes nothing for 0.2 s while the recorder keeps writing the camera.
        trace.addPlane(nanos(1.1f), 3, DebugPlaneKind.Wall, side)
        posesAfter(1f, count = 6)
        assertEquals("the wall found during the hole, on the frame after it", 2, observe().size)
    }

    @Test
    fun `a frame observed 0,4 s after the last, the camera tracked in between, is tracking`() {
        room(front)
        assertEquals(1, frames(0f, 1f).last().size)
        trace.addPlane(nanos(1.2f), 3, DebugPlaneKind.Wall, side)
        posesAfter(1f, count = 12)
        assertEquals("the wall found during the hole, on the frame after it", 2, observe().size)
    }

    @Test
    fun `observed three times a second, the walls still come and go`() {
        room(front, side)
        // The camera tracked 30 times a second throughout, one pose in ten observed.
        var poses = 0
        fun until(seconds: Float): Set<Int> {
            var shown = emptySet<Int>()
            while (poses / FPS <= seconds + 1e-4f) {
                pose(poses / FPS)
                if (poses % OBSERVED_EVERY == 0) shown = observe()
                poses++
            }
            return shown
        }
        assertEquals("walls at first", 2, until(2f).size)
        trace.addPlane(nanos(2.1f), 4, DebugPlaneKind.Wall, right)
        assertEquals("a wall found a second ago", 3, until(3.1f).size)
        trace.addPlane(nanos(3.2f), 3, DebugPlaneKind.Wall, FloatArray(0))
        assertEquals("a wall whose plane went a second and a half ago", 2, until(4.7f).size)
    }

    private companion object {
        const val FPS = 30f
        const val OBSERVED_EVERY = 10
    }
}
