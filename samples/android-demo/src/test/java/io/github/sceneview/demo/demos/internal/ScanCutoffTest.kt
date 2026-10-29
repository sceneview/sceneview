package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** An object's sparse capture keeps arm's length, depth or not (#4683). */
class ScanCutoffTest {
    private val base = 1_000_000_000L

    @Test
    fun `only an object's scan is cut, at its preset's distance`() {
        assertNull(ScanCutoff.of(ScanPreset.ROOM))
        assertEquals(ScanPreset.OBJECT.farM, ScanCutoff.of(ScanPreset.OBJECT)!!.farM)
    }

    @Test
    fun `an object's trace keeps the points near the phone, and saves only those`() {
        val trace = objectTrace()
        trace.addPose(base, DebugPose(0f, 1.3f, 0f))
        // The object 60 cm in front, the far wall 3 m away.
        val points = floatArrayOf(0f, 1f, -0.6f, 0.1f, 1f, -0.6f, 0f, 1.5f, -3f)
        trace.addPoints(base + 1, points, null, intArrayOf(1, 2, 3))
        val frame = trace.frameAt(trace.duration)
        assertEquals(2, frame.mapPoints.size / 3)
        assertTrue((0 until 2).all { frame.mapPoints[it * 3 + 2] > -1f })

        // The saved file is the journal: replayed with no cutoff, it holds the same two points.
        val saved = ArDebugTrace.of(trace.journal!!.toList())
        assertEquals(2, saved.frameAt(saved.duration).mapPoints.size / 3)
    }

    @Test
    fun `an object's planes are cut round the phone's path, and the far walls go`() {
        val trace = objectTrace()
        // The phone circles a point at the origin, 80 cm out.
        for (i in 0 until 8) {
            val a = i * Math.PI / 4
            val pose = DebugPose((0.8 * cos(a)).toFloat(), 1.3f, (0.8 * sin(a)).toFloat())
            trace.addPose(base + i * 100_000_000L, pose)
        }
        val t = base + 1_000_000_000L
        // The whole room's floor, 10 m by 10 m.
        trace.addPlane(t, 1, DebugPlaneKind.Floor, quad(-5f, 0f, -5f, 5f, 5f))
        // A wall 4 m away, out of reach; a wall 1 m away, the object stands against it.
        trace.addPlane(t, 2, DebugPlaneKind.Wall, wall(z = -4f))
        trace.addPlane(t, 3, DebugPlaneKind.Wall, wall(z = -1f))

        val planes = trace.frameAt(trace.duration).planes.associateBy { it.id }
        assertEquals(setOf(1, 3), planes.keys)
        val floor = planes.getValue(1).polygon
        val flat = floor.indices.filter { it % 3 != 1 }
        assertTrue("floor cut to 1.5 m round the object", flat.all { abs(floor[it]) <= REACH })
        val near = planes.getValue(3).polygon
        assertTrue("near wall cut to the same square", (0 until near.size / 3).all { abs(near[it * 3]) <= REACH })
        // The far wall was never kept, so the saved file has no trace of it either.
        assertTrue(trace.journal!!.none { it is ArDebugEvent.Plane && it.id == 2 })
    }

    @Test
    fun `a plane that leaves the square is taken off, and a room's scan keeps everything`() {
        val trace = objectTrace()
        trace.addPose(base, DebugPose(0f, 1.3f, 0f))
        trace.addPlane(base + 1, 1, DebugPlaneKind.Wall, wall(z = -1f))
        assertNotNull(trace.frameAt(trace.duration).planes.firstOrNull { it.id == 1 })
        // ARCore moves the same wall's polygon out of reach: it leaves the scan.
        trace.addPlane(base + 2, 1, DebugPlaneKind.Wall, wall(z = -4f))
        assertTrue(trace.frameAt(trace.duration).planes.none { it.id == 1 && it.polygon.isNotEmpty() })

        val room = ArDebugTrace()
        room.addPose(base, DebugPose(0f, 1.3f, 0f))
        room.addPoints(base + 1, floatArrayOf(0f, 1.5f, -3f))
        room.addPlane(base + 2, 2, DebugPlaneKind.Wall, wall(z = -4f))
        val frame = room.frameAt(room.duration)
        assertEquals(1, frame.mapPoints.size / 3)
        assertEquals(1, frame.planes.size)
    }

    private fun objectTrace() = ArDebugTrace().apply {
        journal = ArrayList()
        cutoff = ScanCutoff.of(ScanPreset.OBJECT)
    }

    private fun quad(x0: Float, y: Float, z0: Float, x1: Float, z1: Float) =
        floatArrayOf(x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1)

    private fun wall(z: Float) = floatArrayOf(-5f, 0f, z, 5f, 0f, z, 5f, 2.5f, z, -5f, 2.5f, z)

    private companion object {
        const val REACH = 1.5f + 1e-4f
    }
}
