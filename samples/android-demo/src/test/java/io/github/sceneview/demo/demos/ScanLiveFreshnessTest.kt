package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.CoverageGrid
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DenseSamples
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.ScanMemoryBudget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Both lanes use virtual time: no sleeps, host CPU timing or frozen-clock freshness claims. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanLiveFreshnessTest {
    @Test
    fun `fifty adds finish at fusion cost while the preview is blocked`() = runTest {
        val blocked = CompletableDeferred<Unit>()
        var builds = 0
        val map = ScanDenseMap(maxPoints = 12_000, maxLevel = 7,
            clock = { testScheduler.currentTime * NS_PER_MS }, liveDispatcher = StandardTestDispatcher(testScheduler),
            beforeBuild = { builds++; blocked.await() },
            fuse = { fusion, samples -> testScheduler.advanceTimeBy(40); fusion.add(samples) })
        val first = patch(8_000)
        map.fusion.add(first)
        map.add(first)
        runCurrent()
        repeat(50) { i ->
            map.add(patch(8_000, (i + 1) * 3f))
            assertEquals(40f, map.lastAddMs, 0f)
        }
        assertEquals(52, map.frames)
        assertEquals(1, builds)
        assertTrue(map.fusion.compactions > 0)
        assertNull(map.live)
        blocked.complete(Unit)
        map.finishPendingLive()
        assertNotNull(map.live)
        map.discardPendingLive()
    }

    @Test
    fun `1300 ms builds and 40 ms adds every 100 ms stay within three seconds for 150 seconds`() = runTest {
        var builds = 0
        val map = ScanDenseMap(maxPoints = 12_000, clock = { testScheduler.currentTime * NS_PER_MS },
            liveDispatcher = StandardTestDispatcher(testScheduler), beforeBuild = { builds++; delay(1_300) },
            fuse = { fusion, samples -> testScheduler.advanceTimeBy(40); fusion.add(samples) })
        val samples = patch(800)
        repeat(1_500) {
            map.add(samples)
            assertEquals(40f, map.lastAddMs, 0f)
            advanceTimeBy(60)
            runCurrent()
            assertTrue("age at $it: ${map.snapshotAgeNs}", map.takeSnapshotAgePeakNs() <= 3_000 * NS_PER_MS)
        }
        assertEquals(150_000, testScheduler.currentTime)
        assertEquals(1_500, map.frames)
        assertTrue("only $builds builds", builds > 100)
        map.finishPendingLive()
        assertTrue(map.takeSnapshotAgePeakNs() <= 3_000 * NS_PER_MS)
        map.discardPendingLive()
    }

    @Test
    fun `120 ms back to back adds do not postpone preview publication`() = runTest {
        var builds = 0
        val map = ScanDenseMap(maxPoints = 12_000, clock = { testScheduler.currentTime * NS_PER_MS },
            liveDispatcher = StandardTestDispatcher(testScheduler), beforeBuild = { builds++; delay(1_300) },
            fuse = { fusion, samples -> testScheduler.advanceTimeBy(120); fusion.add(samples) })
        val samples = patch(800)
        repeat(1_250) {
            map.add(samples)
            runCurrent()
            assertEquals(120f, map.lastAddMs, 0f)
            assertTrue(map.takeSnapshotAgePeakNs() <= 3_000 * NS_PER_MS)
            if (it > 24) assertNotNull(map.live)
            if (map.live != null) assertEquals(1_300 * NS_PER_MS, map.lastBuildSpanNs)
        }
        assertTrue(builds > 100)
        map.discardPendingLive()
    }

    @Test
    fun `the last snapshot finishes and publishes without another add`() = runTest {
        val map = ScanDenseMap(maxPoints = 20_000, clock = { testScheduler.currentTime * NS_PER_MS },
            liveDispatcher = StandardTestDispatcher(testScheduler), beforeBuild = { delay(1_300) })
        val samples = patch(800)
        map.fusion.add(samples)
        map.add(samples)
        assertNull(map.live)
        map.finishPendingLive()
        assertEquals(1_300, testScheduler.currentTime)
        assertEquals(2, map.frames)
        val completed = checkNotNull(map.live)
        map.discardPendingLive()
        assertSame("finish holds its last complete preview", completed, map.live)
        assertEquals(0L, map.snapshotStorageBytes)
        map.close().join()
        map.releaseLiveBuffers()
        assertNull(map.live)
    }

    @Test
    fun `parallel publication equals synchronous assembly from the same seeded scene`() = runTest {
        val blocked = CompletableDeferred<Unit>()
        val parallel = ScanDenseMap(maxPoints = 150_000, clock = { testScheduler.currentTime * NS_PER_MS },
            liveDispatcher = StandardTestDispatcher(testScheduler), beforeBuild = { blocked.await() })
        val synchronous = ScanDenseMap(maxPoints = 150_000, clock = { testScheduler.currentTime * NS_PER_MS },
            liveDispatcher = Dispatchers.Unconfined)
        val scene = io.github.sceneview.demo.demos.internal.ReconstructionBench.QUICK.frames().take(12)
        for (map in listOf(parallel, synchronous)) {
            for (frame in scene) map.fusion.add(map.project(frame, 0L))
            map.add(patch(0))
        }
        runCurrent()
        val expected = checkNotNull(synchronous.live)
        // Fusion keeps changing and compacting while the first snapshot stays blocked.
        repeat(50) { parallel.add(patch(800, (it + 1) * 3f)) }
        blocked.complete(Unit)
        parallel.finishPendingLive()
        val actual = checkNotNull(parallel.live)
        assertArrayEquals(expected.cloud.positions, actual.cloud.positions, 0f)
        assertArrayEquals(expected.cloud.normals, actual.cloud.normals, 0f)
        assertArrayEquals(expected.cloud.colors, actual.cloud.colors)
        assertArrayEquals(expected.cloud.confidences, actual.cloud.confidences)
        assertArrayEquals(expected.cloud.levels, actual.cloud.levels)
        assertArrayEquals(expected.whole, actual.whole, 0f)
        assertArrayEquals(expected.coveredCells, actual.coveredCells)
        assertArrayEquals(expected.weakCells, actual.weakCells)
        assertArrayEquals(expected.mesh.positions, actual.mesh.positions, 0f)
        assertArrayEquals(expected.mesh.indices, actual.mesh.indices)
        assertArrayEquals(expected.mesh.uvs, actual.mesh.uvs, 0f)
        assertArrayEquals(expected.offsets, actual.offsets, 0f)
        assertArrayEquals(expected.atlas, actual.atlas)
        parallel.discardPendingLive()
        synchronous.discardPendingLive()
    }

    @Test
    fun `close during a build releases copied buffers and prevents all later publication`() = runTest {
        val blocked = CompletableDeferred<Unit>()
        val map = ScanDenseMap(maxPoints = 20_000, clock = { testScheduler.currentTime * NS_PER_MS },
            liveDispatcher = StandardTestDispatcher(testScheduler), beforeBuild = { blocked.await() })
        map.add(patch(8_000))
        runCurrent()
        assertTrue(map.snapshotStorageBytes > 0)
        val job = map.close()
        runCurrent()
        job.join()
        map.releaseLiveBuffers()
        assertEquals(0L, map.snapshotStorageBytes)
        blocked.complete(Unit)
        advanceUntilIdle()
        assertNull(map.live)
        assertFalse(map.hasPendingLive)
        assertEquals(0L, map.snapshotStorageBytes)
    }

    @Test
    fun `final floor cells include depth added while live is not due`() {
        var now = 0L
        val map = ScanDenseMap(maxPoints = 20_000, clock = { now }, liveDispatcher = Dispatchers.Unconfined)
        val first = patch(2_000)
        map.add(first)
        now += ScanDenseMap.LIVE_INTERVAL_NS
        map.add(first)
        val before = checkNotNull(map.live)
        val next = patch(2_000, 3f)
        map.add(next)
        map.add(next)
        assertSame(before, map.live)
        val cells = map.coveredCells()
        assertTrue(cells.size > before.coveredCells.size)
        assertArrayEquals(CoverageGrid.coveredKeys(map.fusion.cloudPositions(minViews = DenseFusion.MIN_VIEWS)), cells)
    }

    @Test
    fun `both snapshot buffers and retained work fit the dense memory allowance`() {
        val cap = 250_000
        var now = 0L
        val map = ScanDenseMap(maxPoints = cap, maxLevel = 0, clock = { now }, liveDispatcher = Dispatchers.Unconfined)
        map.project(eye(), now)
        val samples = patch(cap)
        repeat(3) {
            map.add(samples)
            now += ScanDenseMap.LIVE_INTERVAL_NS
        }
        assertTrue(checkNotNull(map.live).cloud.count > 0)
        assertTrue(map.fusion.count > 200_000)
        assertTrue(map.snapshotStorageBytes >= map.fusion.count * 53L * 2)
        assertTrue("${map.storageBytes} bytes for $cap points", map.storageBytes <= cap * ScanMemoryBudget.DENSE_BYTES_PER_POINT)
        println("Near field retained ${map.storageBytes} / ${cap * ScanMemoryBudget.DENSE_BYTES_PER_POINT} bytes, snapshots=${map.snapshotStorageBytes}")
    }

    @Test
    fun `log intervals include copy build add means maxima and preceding publication age`() {
        val timings = ScanDenseTimings()
        timings.add(10 * NS_PER_MS); timings.add(30 * NS_PER_MS)
        timings.copy(12 * NS_PER_MS); timings.copy(20 * NS_PER_MS)
        timings.build(1_200 * NS_PER_MS); timings.build(1_300 * NS_PER_MS)
        val line = timings.line()
        assertTrue(line, line.contains("add 20.0 ms (max 30.0)"))
        assertTrue(line, line.contains("snapshot copy mean 16.0 ms (max 20.0)"))
        assertTrue(line, line.contains("build duration mean 1250.0 ms (max 1300.0)"))
        assertTrue(timings.line().contains("build duration mean 0.0 ms (max 0.0)"))
    }

    companion object {
        private const val NS_PER_MS = 1_000_000L
        internal fun eye() = DepthFrame(1, 1, shortArrayOf(0), null, IntArray(1), 1f, 1f, 0.5f, 0.5f, DebugPose(0.5f, 1.4f, 0.5f))
        internal fun patch(count: Int, offset: Float = 0f): DenseSamples {
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
}
