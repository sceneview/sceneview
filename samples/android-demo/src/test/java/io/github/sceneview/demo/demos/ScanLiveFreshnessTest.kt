package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.CoverageGrid
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DenseSamples
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.ScanMemoryBudget
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanLiveFreshnessTest {
    @Test
    fun `adds and compaction cannot postpone a pending snapshot beyond three adds`() {
        var now = 0L
        // Each clock read costs more than the slice, regardless of the host's CPU speed.
        val map = ScanDenseMap(maxPoints = 12_000, clock = { now.also { now += 10_000_000L } })
        map.project(eye(), now)
        val near = patch(8_000)
        map.add(near)
        assertNull(map.live)
        val compactions = map.fusion.compactions
        map.add(patch(8_000, 2f))
        assertTrue(map.fusion.compactions > compactions)
        map.add(near)
        assertTrue(map.live != null)
        assertEquals(ScanDenseMap.LIVE_MAX_ADDS, map.lastBuildAdds)
        assertTrue(map.lastBuildSpanNs <= ScanDenseMap.LIVE_INTERVAL_NS)
        assertTrue(map.logLine().contains("live span"))
        val expected = map.fusion.liveCloud(0.5f, 1.4f, 0.5f, ScanDenseMap.LIVE_DRAWN_SURFELS, minViews = 1)
        assertArrayEquals(expected.positions, checkNotNull(map.live).cloud.positions, 0f)
        assertArrayEquals(expected.colors, checkNotNull(map.live).cloud.colors)
        assertArrayEquals(map.coveredCells(), checkNotNull(map.live).coveredCells)
        // Repeated close compactions still cannot keep the previous snapshot indefinitely.
        var published = map.live
        var lastPublication = now
        repeat(12) { i ->
            now += 100_000_000L
            map.add(patch(8_000, (i + 2) * 2f))
            if (map.live !== published) {
                published = map.live
                lastPublication = now
                assertTrue(map.lastBuildAdds <= ScanDenseMap.LIVE_MAX_ADDS)
            }
            assertTrue("snapshot age ${now - lastPublication}", now - lastPublication <= 2 * ScanDenseMap.LIVE_INTERVAL_NS)
        }
    }

    @Test
    fun `the worker completes pending live work without another depth frame`() {
        var now = 0L
        val map = ScanDenseMap(maxPoints = 20_000, clock = { now.also { now += 10_000_000L } })
        map.project(eye(), now)
        val samples = patch(8_000)
        map.add(samples, deferLive = true)
        map.add(samples, deferLive = true)
        assertNull(map.live)
        val frames = map.frames
        map.finishPendingLive()
        assertEquals(frames, map.frames)
        assertTrue(map.live != null)
        assertEquals(2, map.lastBuildAdds)
    }

    @Test
    fun `final floor cells include depth added while live is not due`() {
        var now = 0L
        val map = ScanDenseMap(maxPoints = 20_000, clock = { now })
        val first = patch(2_000)
        map.add(first)
        now += ScanDenseMap.LIVE_INTERVAL_NS
        map.add(first)
        val before = checkNotNull(map.live)
        val next = patch(2_000, 3f)
        map.add(next)
        map.add(next)
        assertTrue(before === map.live)
        val cells = map.coveredCells()
        assertTrue(cells.size > before.coveredCells.size)
        assertArrayEquals(CoverageGrid.coveredKeys(map.fusion.cloudPositions(minViews = DenseFusion.MIN_VIEWS)), cells)
    }

    @Test
    fun `near field snapshots and the released index fit the dense memory allowance`() {
        val cap = 250_000
        var now = 0L
        val map = ScanDenseMap(maxPoints = cap, maxLevel = 0, clock = { now })
        map.project(eye(), now)
        // 2 cm samples of layered furniture and room surfaces around the camera, then a room
        // beyond it. The near field exceeds the draw cap and used to retain a large live index.
        val samples = patch(cap)
        map.add(samples)
        now += ScanDenseMap.LIVE_INTERVAL_NS
        map.add(samples)
        val first = map.live
        assertTrue(checkNotNull(first).cloud.count > 0)
        assertTrue(map.fusion.count > 200_000)
        assertTrue("${map.storageBytes} bytes for $cap points", map.storageBytes <= cap * ScanMemoryBudget.DENSE_BYTES_PER_POINT)
        println("Near field retained ${map.storageBytes} / ${cap * ScanMemoryBudget.DENSE_BYTES_PER_POINT} bytes")
        now += ScanDenseMap.LIVE_INTERVAL_NS
        map.add(samples)
        assertNotSame(first, map.live)
        assertTrue(map.storageBytes <= cap * ScanMemoryBudget.DENSE_BYTES_PER_POINT)
    }

    private fun eye() = DepthFrame(1, 1, shortArrayOf(0), null, IntArray(1), 1f, 1f, 0.5f, 0.5f, DebugPose(0.5f, 1.4f, 0.5f))

    private fun patch(count: Int, offset: Float = 0f): DenseSamples {
        val positions = FloatArray(count * 3)
        val normals = FloatArray(count * 3)
        repeat(count) { i ->
            positions[i * 3] = offset + (i % 100) * 0.0201f
            positions[i * 3 + 1] = (i / 10_000) * 0.08f
            positions[i * 3 + 2] = (i / 100 % 100) * 0.0201f
            normals[i * 3 + 1] = 1f
        }
        return DenseSamples(count, positions, normals, IntArray(count) { 0xff8090a0.toInt() }, ByteArray(count) { -1 })
    }
}
