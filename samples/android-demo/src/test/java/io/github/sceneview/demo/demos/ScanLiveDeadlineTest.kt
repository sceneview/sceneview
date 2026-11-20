package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseSamples
import io.github.sceneview.demo.demos.internal.DepthFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every unit of cooperative work has an injected cost; host CPU speed cannot affect a verdict. */
class ScanLiveDeadlineTest {
    @Test
    fun `the device analyzer retains add means and maxima alongside deferred work`() {
        val timings = ScanDenseTimings()
        timings.add(10_000_000L)
        timings.add(30_000_000L)
        timings.deferred(2_000_000L)
        timings.deferred(4_000_000L)
        val line = timings.line()
        val parsed = checkNotNull(Regex("add ([\\d.]+) ms \\(max ([\\d.]+)\\)").find(line))
        assertEquals("20.0", parsed.groupValues[1])
        assertEquals("30.0", parsed.groupValues[2])
        assertTrue(line.contains("deferred snapshot mean 3.0 ms (max 4.0)"))
    }

    @Test
    fun `150 seconds of 40 ms adds retain every slot through 600k voxels`() = replay(compacting = false)

    @Test
    fun `repeated compactions cannot starve publication or consume the next slot`() = replay(compacting = true)

    @Test
    fun `a pending build completes when no depth arrives for three seconds`() {
        val time = CostClock()
        val map = map(time, 600_000, 0)
        val samples = patch(600_000)
        // Populate without a projected eye, then make a large pending view with a known cost.
        map.fusion.add(samples)
        map.fusion.add(samples)
        val slot = time.now
        time.now += ADD_NS
        map.add(DenseSamples(0, FloatArray(0), FloatArray(0), IntArray(0), ByteArray(0)), deferLive = true, slotAtNanos = slot)
        assertTrue(map.hasPendingLive)
        val frames = map.frames
        for (tick in 1..300) {
            time.now = maxOf(time.now, slot + tick * TICK_NS)
            map.advancePendingLive()
            if (tick >= 100) assertTrue("build still pending at ${time.now}", !map.hasPendingLive)
        }
        assertEquals(frames, map.frames)
        assertTrue(map.lastBuildSpanNs <= ScanLiveDeadline.TARGET_NS)
        assertTrue(map.live != null)
    }

    @Test
    fun `an older build finishes on its pinned view through fifty real compactions`() {
        val fusion = io.github.sceneview.demo.demos.internal.DenseFusion(maxPoints = 12_000, maxLevel = 7)
        fusion.add(patch(12_000))
        val read = fusion.liveRead()
        val expected = read.liveCloud(0.5f, 1.4f, 0.5f, 5_000, minViews = 1)
        val build = read.liveCloudBuild(0.5f, 1.4f, 0.5f, 5_000, minViews = 1)
        org.junit.Assert.assertNull(build.advance(0))
        repeat(100) { i -> fusion.add(patch(8_000, (i + 1) * 3f)) }
        assertTrue("${fusion.compactions} compactions", fusion.compactions >= 50)
        val actual = checkNotNull(build.advance(Long.MAX_VALUE))
        assertEquals(ScanDenseDeterminismTest.digest(expected), ScanDenseDeterminismTest.digest(actual))
        println("Pinned view: ${fusion.compactions} compactions, copy max ${fusion.maxLiveDetachNanos / 1e6} ms")
        fusion.releaseLiveRead()
        assertEquals(0L, fusion.liveReadBytes)
    }

    @Test
    fun `only an age above two seconds permits unbounded work`() {
        assertTrue(ScanLiveDeadline.budget(0, ADD_NS, true) in 1..40_000_000L)
        assertEquals(40_000_000L, ScanLiveDeadline.budget(ScanLiveDeadline.TARGET_NS, ADD_NS, true))
        assertEquals(0L, ScanLiveDeadline.budget(0, ScanLiveDeadline.ADD_LIMIT_NS, true))
        assertEquals(40_000_000L, ScanLiveDeadline.budget(0, -ScanLiveDeadline.SLOT_NS, true))
        assertEquals(ScanLiveDeadline.IDLE_SLICE_NS, ScanLiveDeadline.budget(ScanLiveDeadline.HARD_NS, 0, false))
        assertEquals(Long.MAX_VALUE, ScanLiveDeadline.budget(ScanLiveDeadline.HARD_NS + 1, 0, false))
    }

    private fun replay(compacting: Boolean) {
        val time = CostClock()
        val map = map(time, if (compacting) 12_000 else 600_000, if (compacting) 7 else 0)
        var publications = 0
        var previous = map.live
        var lastPublication = time.now
        var firstBuild = time.now
        repeat(1_500) { i ->
            val slot = i * ScanLiveDeadline.SLOT_NS
            assertTrue("late add $i at ${time.now - slot}", time.now <= slot)
            time.now = slot + ADD_NS
            // Observe each 800-voxel strip twice, like overlapping depth, reaching 600k.
            // On the small map, a larger strip every N=4 adds causes real coarsening.
            val samples = patch(if (compacting && i % 4 == 0) 8_000 else 800, (i / 2) * 3f)
            map.add(samples, deferLive = true, slotAtNanos = slot)
            assertTrue("slot $i cost ${time.now - slot}", time.now - slot <= 81_000_000L)
            assertTrue("publication age at add $i", time.now - lastPublication <= ScanLiveDeadline.HARD_NS)
            assertTrue("snapshot data age at add $i", map.snapshotAgeNs <= ScanLiveDeadline.HARD_NS)
            if (map.live !== previous) {
                previous = map.live
                lastPublication = time.now
                publications++
                assertTrue("build span ${map.lastBuildSpanNs}", map.lastBuildSpanNs <= ScanLiveDeadline.TARGET_NS)
                firstBuild = time.now
            }
            // Stress a slower idle cadence than capture, with depth taking priority at the next slot.
            while (time.now + TICK_NS + ScanLiveDeadline.IDLE_SLICE_NS < slot + ScanLiveDeadline.SLOT_NS) {
                time.now += TICK_NS
                map.advancePendingLive()
                if (map.live !== previous) {
                    previous = map.live
                    lastPublication = time.now
                    publications++
                    assertTrue(map.lastBuildSpanNs <= ScanLiveDeadline.TARGET_NS)
                    firstBuild = time.now
                }
                assertTrue("publication age at idle $i", time.now - lastPublication <= ScanLiveDeadline.HARD_NS)
                assertTrue("snapshot data age at idle $i", map.snapshotAgeNs <= ScanLiveDeadline.HARD_NS)
            }
            assertTrue("starved since $firstBuild", time.now - firstBuild <= ScanLiveDeadline.HARD_NS)
        }
        assertTrue("only $publications publications", publications >= 100)
        assertEquals(1_500, map.frames)
        if (compacting) assertTrue("only ${map.fusion.compactions} compactions", map.fusion.compactions > 50)
        else assertEquals(600_000, map.fusion.count)
    }

    private fun map(time: CostClock, cap: Int, level: Int): ScanDenseMap =
        ScanDenseMap(maxPoints = cap, maxLevel = level, clock = time::read).also {
            it.project(DepthFrame(1, 1, shortArrayOf(0), null, IntArray(1), 1f, 1f, 0.5f, 0.5f,
                DebugPose(0.5f, 1.4f, 0.5f)), time.now)
            time.now = 0
        }

    private class CostClock {
        var now = 0L
        fun read(): Long = now.also { now += 250_000L }
    }

    private fun patch(count: Int, offset: Float = 0f): DenseSamples {
        val positions = FloatArray(count * 3)
        val normals = FloatArray(count * 3)
        repeat(count) { i ->
            positions[i * 3] = offset + (i % 100) * 0.0201f
            positions[i * 3 + 2] = (i / 100) * 0.0201f
            normals[i * 3 + 1] = 1f
        }
        return DenseSamples(count, positions, normals, IntArray(count) { 0xff8090a0.toInt() }, ByteArray(count) { -1 })
    }

    private companion object {
        const val ADD_NS = 40_000_000L
        const val TICK_NS = 10_000_000L
    }
}
