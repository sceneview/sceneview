package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A scan without photos is drawn from above in its thumbnail (#4680). */
class ScanFootprintTest {
    @Test
    fun `a footprint fits the floor into the unit square, centred, one scale for both axes`() {
        // A 4 m x 2 m rectangle of points on the floor, at height 1 m.
        val xyz = FloatArray(40 * 3)
        for (i in 0 until 40) {
            xyz[i * 3] = if (i % 2 == 0) -2f else 2f
            xyz[i * 3 + 1] = 1f
            xyz[i * 3 + 2] = if (i % 4 < 2) -1f else 1f
        }
        val footprint = ScanFootprint.of(xyz, trail = floatArrayOf(0f, 1.5f, 0f, 1f, 1.5f, 0f))!!
        val u = footprint.points.filterIndexed { i, _ -> i % 2 == 0 }
        val v = footprint.points.filterIndexed { i, _ -> i % 2 == 1 }
        assertEquals(0.1f, u.min(), 1e-4f)
        assertEquals(0.9f, u.max(), 1e-4f)
        // 2 m deep at 0.2 per metre, centred: 0.3 to 0.7.
        assertEquals(0.3f, v.min(), 1e-4f)
        assertEquals(0.7f, v.max(), 1e-4f)
        assertEquals(4, footprint.path.size)
        assertEquals(0.5f, footprint.path[0], 1e-4f)
        assertEquals(0.5f, footprint.path[1], 1e-4f)
    }

    @Test
    fun `a stray point far away does not shrink the room to a corner`() {
        // 100 points spread over 1 m x 1 m, and one 20 m away.
        val xyz = FloatArray(101 * 3)
        for (i in 0 until 100) {
            xyz[i * 3] = (i % 10) / 9f
            xyz[i * 3 + 2] = (i / 10) / 9f
        }
        xyz[100 * 3] = 20f
        xyz[100 * 3 + 2] = 20f
        val footprint = ScanFootprint.of(xyz, FloatArray(0))!!
        val u = footprint.points.filterIndexed { i, _ -> i % 2 == 0 }
        assertTrue("the room spans most of the square: ${u.max() - u.min()}", u.max() - u.min() > 0.7f)
        assertTrue(footprint.points.all { it in 0f..1f })
        assertEquals(100, footprint.pointCount)
    }

    @Test
    fun `a footprint keeps at most its budget of points, and too few points draw nothing`() {
        val many = FloatArray(10_000 * 3) { (it % 97).toFloat() / 10f }
        val footprint = ScanFootprint.of(many, FloatArray(0), maxPoints = 500)!!
        assertTrue(footprint.pointCount in 1..500)
        assertNull(ScanFootprint.of(FloatArray(3 * 3), FloatArray(0)))
        assertNull(ScanFootprint.of(FloatArray(40 * 3) { Float.NaN }, FloatArray(0)))
    }

    @Test
    fun `the bundled sample draws from above`() {
        val dir = listOf(
            File("src/main/assets/${RerunReplayAssets.DIR}"),
            File("samples/android-demo/src/main/assets/${RerunReplayAssets.DIR}"),
        ).first { it.isDirectory }
        val opened = RerunCapturePack(
            manifest = File(dir, "showcase-manifest.json").readBytes(),
            log = File(dir, "showcase-session.jsonl").readBytes(),
            media = File(dir, "showcase-media.bin").readBytes(),
        ).open()!!
        val footprint = ScanFootprint.of(opened)
        assertNotNull(footprint)
        footprint!!
        assertTrue(footprint.pointCount >= ScanFootprint.MIN_POINTS)
        assertTrue(footprint.points.all { it in 0f..1f })
        assertTrue(footprint.path.all { it in 0f..1f })
    }
}
