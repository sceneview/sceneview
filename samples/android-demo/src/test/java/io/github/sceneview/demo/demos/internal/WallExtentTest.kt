package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.ScanCoverageState
import io.github.sceneview.demo.demos.ScanWallsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A live wall is drawn as far as the scan saw it (QA 2026-10-22, remark 3): ARCore's plane, grown
 * past its points, no longer draws a rectangle wider than the wall.
 */
class WallExtentTest {
    private val floorY = 0f
    private val height = 2.5f

    /** A wall along x at z = -2, from [ax] to [bx]. */
    private fun wall(ax: Float, bx: Float, id: Int = 1, confidence: Float = 1f) =
        LiveWall(id, ax, -2f, bx, -2f, bornAt = 0f, confidence = confidence)

    /** Points on the wall at z = -2 (± [across]), x from [from] to [to], every 5 cm, at three heights. */
    private fun pointsOn(from: Float, to: Float, across: Float = 0.02f): FloatArray {
        val out = ArrayList<Float>()
        var x = from
        var k = 0
        while (x <= to + 1e-4f) {
            for (y in listOf(0.5f, 1.2f, 1.9f)) {
                out += x
                out += y
                out += -2f + if (k++ % 2 == 0) across else -across
            }
            x += 0.05f
        }
        return out.toFloatArray()
    }

    @Test
    fun `a plane grown past its points is cut back to them, a margin past each end`() {
        val walls = listOf(wall(-2f, 2f))
        val clipped = WallExtent.clip(walls, listOf(pointsOn(-0.5f, 1f)), floorY, height)
        val w = clipped.single()
        assertEquals(-0.5f - WallExtent.MARGIN_M, w.ax, 0.03f)
        assertEquals(1f + WallExtent.MARGIN_M, w.bx, 0.03f)
        assertEquals(-2f, w.az, 1e-4f)
        assertEquals(-2f, w.bz, 1e-4f)
        // 1.8 m left of 4 m: still sure, a 1.5 m wall is.
        assertEquals(1f, w.confidence, 1e-4f)
        assertEquals(1, w.id)
    }

    @Test
    fun `a wall seen end to end is the same wall, the same list`() {
        val walls = listOf(wall(-2f, 2f))
        val clipped = WallExtent.clip(walls, listOf(pointsOn(-2f, 2f)), floorY, height)
        assertSame(walls, clipped)
    }

    @Test
    fun `a wall with too few points on it is kept as ARCore found it`() {
        val walls = listOf(wall(-2f, 2f))
        val few = floatArrayOf(0f, 1f, -2f, 0.1f, 1f, -2f, 0.2f, 1f, -2f)
        assertSame(walls, WallExtent.clip(walls, listOf(few), floorY, height))
        assertSame(walls, WallExtent.clip(walls, emptyList(), floorY, height))
    }

    @Test
    fun `points off the wall, on the floor or across the room do not stretch it`() {
        val walls = listOf(wall(-2f, 2f))
        val floor = FloatArray(300) { i ->
            when (i % 3) {
                0 -> -2f + (i / 3) * 0.04f
                1 -> 0.05f // on the floor
                else -> -2f
            }
        }
        val acrossRoom = FloatArray(300) { i ->
            when (i % 3) {
                0 -> -2f + (i / 3) * 0.04f
                1 -> 1f
                else -> -1.5f // half a metre in front of the wall
            }
        }
        val w = WallExtent.clip(walls, listOf(pointsOn(0f, 0.8f), floor, acrossRoom), floorY, height).single()
        assertEquals(0f - WallExtent.MARGIN_M, w.ax, 0.03f)
        assertEquals(0.8f + WallExtent.MARGIN_M, w.bx, 0.03f)
    }

    @Test
    fun `a stray cluster far along the wall is left out, the fuller run wins`() {
        val walls = listOf(wall(-2f, 2f))
        val stray = pointsOn(1.8f, 1.9f) // a picture frame's edge, 1.3 m beyond the rest
        val w = WallExtent.clip(walls, listOf(pointsOn(-1f, 0.5f), stray), floorY, height).single()
        assertTrue("end ${w.bx}", w.bx < 0.5f + WallExtent.MARGIN_M + 0.05f)
    }

    @Test
    fun `a wall cut to a sliver is unsure, and the phone facing it is asked to finish it`() {
        val found = listOf(wall(-2f, 2f))
        val drawn = WallExtent.clip(found, listOf(pointsOn(0f, 0.3f)), floorY, height)
        assertTrue("confidence ${drawn.single().confidence}", drawn.single().confidence < WallExtent.SLIVER)
        // Standing at the room's centre, looking down -z, at the wall.
        val looking = DebugPose(0.5f, 1.4f, 0f)
        assertNotNull(WallExtent.facingUnfinished(looking, found, drawn))
        // Looking the other way, there is no wall ahead.
        val away = DebugPose(0.5f, 1.4f, 0f, qx = 0f, qy = 1f, qz = 0f, qw = 0f)
        assertNull(WallExtent.facingUnfinished(away, found, drawn))
        // The same wall seen end to end has nothing to finish.
        assertNull(WallExtent.facingUnfinished(looking, found, found))
    }

    @Test
    fun `an oblique wall is cut along its own direction`() {
        val angle = 0.6f
        val ux = cos(angle)
        val uz = sin(angle)
        val w = LiveWall(7, 0f, 0f, 4f * ux, 4f * uz, bornAt = 0f)
        val pts = ArrayList<Float>()
        var t = 1f
        while (t <= 2f) {
            for (y in listOf(0.6f, 1.5f)) {
                pts += t * ux
                pts += y
                pts += t * uz
            }
            t += 0.05f
        }
        val c = WallExtent.clip(listOf(w), listOf(pts.toFloatArray()), floorY, height).single()
        val start = c.ax * ux + c.az * uz
        val end = c.bx * ux + c.bz * uz
        assertEquals(1f - WallExtent.MARGIN_M, start, 0.03f)
        assertEquals(2f + WallExtent.MARGIN_M, end, 0.06f)
        // Still on the wall's line.
        assertEquals(0f, abs(-uz * c.bx + ux * c.bz), 1e-3f)
    }

    @Test
    fun `the synthetic turn's overgrown right wall is drawn only as far as its points midway`() {
        val trace = ArDebugTrace()
        ScanWallWalk.events().forEach { it.applyTo(trace) }
        val walls = ScanWallsState()
        val coverage = ScanCoverageState()
        var cutSeen = false
        var t = 0f
        while (t <= ScanWallWalk.DURATION_S) {
            val frame = trace.frameAt(t)
            coverage.observe(frame)
            walls.observe(frame, coverage, frame.camera, record = null)
            val right = walls.snapshot.walls.firstOrNull { abs(it.ax - ScanWallWalk.MAX_X) < 0.1f && abs(it.bx - ScanWallWalk.MAX_X) < 0.1f }
            val raw = PlaneWallSource().update(frame).walls
                .firstOrNull { abs(it.ax - ScanWallWalk.MAX_X) < 0.1f && abs(it.bx - ScanWallWalk.MAX_X) < 0.1f }
            if (right != null && raw != null && raw.length - right.length > 0.5f) cutSeen = true
            t += 0.5f
        }
        assertTrue("the right wall was never drawn shorter than its overgrown plane", cutSeen)
        assertFalse(walls.snapshot.walls.isEmpty())
    }
}
