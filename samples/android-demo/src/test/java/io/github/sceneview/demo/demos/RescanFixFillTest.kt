package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DenseSamples
import io.github.sceneview.demo.demos.internal.FillStall
import io.github.sceneview.demo.demos.internal.RescanKind
import io.github.sceneview.demo.demos.internal.RescanZone
import io.github.sceneview.demo.demos.internal.RescanZones
import io.github.sceneview.demo.demos.internal.ScanReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix now's "Fill the amber frame: N %" (#4903 walk, 0121–0205: stuck at 24 %, then at 0 %, for
 * 25 and 50 s of scanning). What the fix reads is not what froze it: the live snapshot it follows
 * grows the fill as a wall is scanned, past the snapshot's surfel budget and with the map at its
 * own. A part no new depth reaches stays where it is, and the line then says what to try.
 */
class RescanFixFillTest {

    @Test
    fun `a wall scanned during a fix fills it on the live snapshot, past both budgets`() {
        val fusion = DenseFusion(maxPoints = 30_000)
        // A floor bigger than both budgets, seen three times: the map compacts, the snapshot thins.
        val floor = grid(-2f, 2f, -2f, 2f) { x, z -> floatArrayOf(x, 0f, z) }
        repeat(3) { fusion.add(samples(floor, UP)) }
        assertTrue("the map made room", fusion.compactions > 0)
        val zone = wallZone()
        var last = -1f
        for (step in 1..3) {
            // One more stretch of the wall at z = 2.5, 2 m from the eye, three depth frames each.
            val wall = grid(-1f, -1f + step * 2f / 3f, 0f, 2.4f) { x, y -> floatArrayOf(x, y, 2.5f) }
            repeat(3) { fusion.add(samples(wall, BACK)) }
            val live = fusion.liveCloud(0f, 1.5f, 0f, maxPoints = 10_000)
            assertTrue("the snapshot is past its budget", fusion.count > 10_000)
            val fill = ScanReview.fillOf(zone, live, null)
            assertTrue("step $step: $fill after $last", fill > last + 0.2f)
            last = fill
        }
        assertTrue(last >= 0.95f)
    }

    @Test
    fun `a fix whose part stops filling says so, and time paused is no stall`() {
        var now = 0L
        val fix = SessionFix.start(wallZone(), null, now, nanoTime = { now })
        val empty = DenseCloud(floatArrayOf(10f, 0f, 10f), intArrayOf(0))
        fun read(seconds: Float) {
            now += (seconds * 1e9f).toLong()
            fix.follow(empty)
        }
        read(1.5f)
        repeat(7) { read(1.5f) }
        assertFalse("10.5 s scanning: not yet", fix.stalled)
        read(1.5f)
        assertTrue("12 s scanning with nothing new", fix.stalled)
        assertEquals(0f, (fix.status as RescanStatus.Lined).fill)
        // Paused, the clock does not count.
        val paused = SessionFix.start(wallZone(), null, 0L, nanoTime = { now })
        paused.scanning = false
        repeat(20) { now += 1_500_000_000L; paused.follow(empty) }
        assertFalse(paused.stalled)
    }

    @Test
    fun `a fill that grows is never a stall`() {
        val stall = FillStall()
        var t = 0L
        var fill = 0f
        repeat(30) {
            t += 1_500_000_000L
            fill += 0.02f
            assertFalse(stall.update(fill, t, scanning = true))
        }
        // It stops growing: twelve seconds later it is one.
        repeat(8) { t += 1_500_000_000L; stall.update(fill, t, scanning = true) }
        assertTrue(stall.update(fill, t + 1, scanning = true))
        // It grows again: no longer.
        assertFalse(stall.update(fill + FillStall.MIN_STEP, t + 2, scanning = true))
    }

    /** A 2 m wall along x at z = 2.5, its box as [RescanZones] builds one. */
    private fun wallZone(): RescanZone {
        val half = RescanZones.WALL_HALF_M
        return RescanZone(RescanKind.MissingWall, floatArrayOf(-1f - half, 0f, 2.5f - half, 1f + half, 2.6f, 2.5f + half), 2f, "test")
    }

    private fun grid(a0: Float, a1: Float, b0: Float, b1: Float, point: (Float, Float) -> FloatArray): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        var a = a0
        while (a < a1) {
            var b = b0
            while (b < b1) {
                out += point(a, b)
                b += STEP
            }
            a += STEP
        }
        return out
    }

    private fun samples(points: List<FloatArray>, normal: FloatArray): DenseSamples {
        val n = points.size
        val positions = FloatArray(n * 3)
        val normals = FloatArray(n * 3)
        for (i in 0 until n) {
            for (k in 0..2) {
                positions[i * 3 + k] = points[i][k]
                normals[i * 3 + k] = normal[k]
            }
        }
        return DenseSamples(n, positions, normals, IntArray(n) { GREY }, ByteArray(n) { -1 })
    }

    private companion object {
        const val STEP = 0.02f
        const val GREY = 0xFF808080.toInt()
        val UP = floatArrayOf(0f, 1f, 0f)
        val BACK = floatArrayOf(0f, 0f, -1f)
    }
}
