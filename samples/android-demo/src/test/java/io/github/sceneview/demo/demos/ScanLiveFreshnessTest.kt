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
    fun `600k fusion snapshot and workspace arrays fit the measured allowance`() {
        val cap = 600_000
        // A compact near field prevents distance merging: all 600k surfels need workspace,
        // even though only 50k are drawn. This exercises the worst merge/index capacity.
        val fusion = DenseFusion(voxelM = 0.005f, maxPoints = cap, maxLevel = 0)
        val samples = patch(cap)
        for (i in samples.positions.indices) samples.positions[i] *= 0.25f
        fusion.add(samples)
        fusion.add(samples)
        assertEquals(cap, fusion.count)
        val snapshot = DenseFusion.LiveSnapshot(fusion)
        snapshot.copyFrom(fusion)
        val drawn = snapshot.liveCloud(0.25f, 0.6f, 0.25f, ScanDenseMap.LIVE_DRAWN_SURFELS)
        assertEquals(50_000, drawn.count)
        val fusionArrays = primitiveArrayBytes(fusion)
        val snapshotArrays = primitiveArrayBytes(snapshot)
        val measured = fusionArrays + snapshotArrays
        val bytesPerPoint = measured.toDouble() / cap
        // Independent reflection counts real arrays, including hash tables and geometric growth.
        assertTrue("$bytesPerPoint bytes/point", bytesPerPoint in 290.0..320.0)
        assertTrue(snapshot.storageBytes >= cap * 53L)
        assertTrue(snapshot.storageBytes < cap * 54L)
        println("600k fusion + one snapshot + workspace arrays: $measured bytes ($bytesPerPoint bytes/point); " +
            "fusion=$fusionArrays snapshotAndWorkspace=$snapshotArrays workspace=${snapshot.workspaceBytes}")
    }

    @Test
    fun `retained layer and one snapshot fit the dense memory allowance`() {
        val cap = 600_000
        var now = 0L
        val map = ScanDenseMap(maxPoints = cap, maxLevel = 0, clock = { now }, liveDispatcher = Dispatchers.Unconfined)
        map.project(eye(), now)
        val samples = patch(cap)
        repeat(3) {
            map.add(samples)
            now += ScanDenseMap.LIVE_INTERVAL_NS
        }
        assertTrue(checkNotNull(map.live).cloud.count > 0)
        assertEquals(cap, map.fusion.count)
        assertTrue("${map.storageBytes} bytes for $cap points", map.storageBytes <= cap * ScanMemoryBudget.DENSE_BYTES_PER_POINT)
        println("Near field retained ${map.storageBytes} / ${cap * ScanMemoryBudget.DENSE_BYTES_PER_POINT} bytes, snapshot=${map.snapshotStorageBytes}")
    }

    @Test
    fun `completion waits for the interval then takes exactly one trailing snapshot`() = runTest {
        var builds = 0
        val map = ScanDenseMap(maxPoints = 20_000, clock = { testScheduler.currentTime * NS_PER_MS },
            liveDispatcher = StandardTestDispatcher(testScheduler), beforeBuild = { builds++; delay(100) })
        map.add(patch(800))
        runCurrent()
        advanceTimeBy(50)
        map.add(patch(800, 3f))
        map.add(patch(800, 3f))
        map.finishPendingLive()
        assertEquals(1_100, testScheduler.currentTime)
        assertEquals(2, builds)
        assertArrayEquals(map.fusion.cloudPositions(DenseFusion.MIN_VIEWS), checkNotNull(map.live).whole, 0f)
        advanceUntilIdle()
        assertEquals(2, builds)
        map.discardPendingLive()
    }

    @Test
    fun `QA flush publishes depth left below the interval after an idle build`() = runTest {
        var builds = 0
        val map = ScanDenseMap(maxPoints = 20_000, clock = { testScheduler.currentTime * NS_PER_MS },
            liveDispatcher = StandardTestDispatcher(testScheduler), beforeBuild = { builds++ })
        map.add(patch(800))
        map.finishPendingLive()
        map.add(patch(800, 3f))
        map.add(patch(800, 3f))
        assertEquals(1, builds)
        map.flushLive()
        map.finishPendingLive()
        assertEquals(2, builds)
        assertArrayEquals(map.fusion.cloudPositions(DenseFusion.MIN_VIEWS), checkNotNull(map.live).whole, 0f)
        map.discardPendingLive()
    }

    @Test
    fun `QA flush during a build forces its trailing snapshot before the interval`() = runTest {
        var builds = 0
        val map = ScanDenseMap(maxPoints = 20_000, clock = { testScheduler.currentTime * NS_PER_MS },
            liveDispatcher = StandardTestDispatcher(testScheduler), beforeBuild = { builds++; delay(300) })
        map.add(patch(800))
        runCurrent()
        advanceTimeBy(100)
        map.add(patch(800))
        map.flushLive()
        map.finishPendingLive()
        assertEquals(600, testScheduler.currentTime)
        assertEquals(2, builds)
        assertArrayEquals(map.fusion.cloudPositions(DenseFusion.MIN_VIEWS), checkNotNull(map.live).whole, 0f)
        map.discardPendingLive()
    }

    @Test
    fun `published layer survives reuse of the single snapshot and merge workspace`() = runTest {
        val map = ScanDenseMap(maxPoints = 20_000, clock = { testScheduler.currentTime * NS_PER_MS },
            liveDispatcher = StandardTestDispatcher(testScheduler))
        map.project(eye(), 0L)
        map.add(patch(8_000))
        map.add(patch(8_000))
        map.finishPendingLive()
        val layer = checkNotNull(map.live)
        val before = layerArrays(layer)
        map.add(patch(8_000, 3f))
        map.add(patch(8_000, 3f))
        map.flushLive() // overwrites the snapshot before the next build reads it
        assertEquals(before, layerArrays(layer))
        map.finishPendingLive() // overwrites the merge scratch too
        assertNotSame(layer, map.live)
        assertEquals(before, layerArrays(layer))
        map.discardPendingLive()
    }

    @Test
    fun `exception and OOM drop a preview and the next build succeeds`() = runTest {
        for (failure in listOf(IllegalStateException("test"), OutOfMemoryError("test"))) {
            var attempts = 0
            val logs = mutableListOf<String>()
            val map = ScanDenseMap(maxPoints = 20_000, clock = { testScheduler.currentTime * NS_PER_MS },
                liveDispatcher = StandardTestDispatcher(testScheduler), previewLog = { logs += it },
                beforeBuild = { if (++attempts == 1) throw failure })
            map.add(patch(800))
            map.finishPendingLive()
            assertNull(map.live)
            assertFalse(map.hasPendingLive)
            assertEquals(listOf("preview dropped: ${failure.javaClass.simpleName}"), logs)
            map.add(patch(800))
            map.finishPendingLive()
            assertNotNull(map.live)
            assertEquals(2, attempts)
            assertEquals(1, logs.size)
            map.discardPendingLive()
        }
    }

    @Test
    fun `save releases an idle snapshot before assembling the saved cloud`() = runTest {
        val map = ScanDenseMap(maxPoints = 20_000, clock = { 0L }, liveDispatcher = Dispatchers.Unconfined)
        val samples = patch(800)
        map.fusion.add(samples)
        map.add(samples)
        val layer = checkNotNull(map.live)
        assertTrue(map.snapshotStorageBytes > 0L)
        map.closeForSave()
        assertEquals("workspace must be released before savedCloud", 0L, map.snapshotStorageBytes)
        assertSame(layer, map.live)
        assertTrue(map.savedCloud().count > 0)
        map.releaseLiveBuffers()
    }

    @Test
    fun `save cancellation returns while an uninterruptible preview still owns its buffers`() = runTest {
        val blocked = CompletableDeferred<Unit>()
        val map = ScanDenseMap(maxPoints = 20_000, liveDispatcher = StandardTestDispatcher(testScheduler),
            beforeBuild = { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { blocked.await() } })
        map.add(patch(800))
        runCurrent()
        map.closeForSave() // Save returns while the reader still owns its buffers
        val pending = map.close()
        assertFalse(pending.isCompleted)
        assertTrue(map.snapshotStorageBytes > 0L)
        blocked.complete(Unit)
        pending.join()
        assertEquals("completion releases before saved cloud assembly", 0L, map.snapshotStorageBytes)
        assertTrue(map.savedCloud().count > 0)
        map.releaseLiveBuffers()
        assertEquals(0L, map.snapshotStorageBytes)
        assertNull(map.live)
    }

    @Test
    fun `preview thread keeps Java normal priority and sets nice once inside the thread`() = runTest {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val logged = CompletableDeferred<String>()
        val map = ScanDenseMap(maxPoints = 20_000, previewPriority = {
            assertEquals("SceneView-ScanPreview", Thread.currentThread().name)
            assertEquals(Thread.NORM_PRIORITY, Thread.currentThread().priority)
            calls.incrementAndGet()
            2
        }, previewLog = { logged.complete(it) })
        map.add(patch(800))
        map.finishPendingLive()
        assertTrue(logged.isCompleted)
        assertEquals("preview thread nice 2", logged.await())
        assertEquals(1, calls.get())
        map.discardPendingLive()
    }

    @Test
    fun `sixty seconds of ten Hz adds builds at most once a second and trails the last frame`() = runTest {
        val starts = mutableListOf<Long>()
        var active = 0
        val map = ScanDenseMap(maxPoints = 20_000, clock = { testScheduler.currentTime * NS_PER_MS },
            liveDispatcher = StandardTestDispatcher(testScheduler), beforeBuild = {
                assertEquals(0, active++)
                starts += testScheduler.currentTime
                delay(300)
                active--
            })
        repeat(600) { i ->
            map.add(patch(20, i * 0.001f))
            advanceTimeBy(100)
            runCurrent()
        }
        val atStop = starts.size
        map.finishPendingLive()
        assertTrue("${starts.size} builds", starts.size <= 61)
        assertTrue(starts.zipWithNext().all { (a, b) -> b - a >= 1_000 })
        assertEquals(61, starts.size)
        assertTrue(starts.size - atStop <= 1)
        assertEquals(600, map.frames)
        assertArrayEquals(map.fusion.cloudPositions(DenseFusion.MIN_VIEWS), checkNotNull(map.live).whole, 0f)
        advanceUntilIdle()
        assertEquals(61, starts.size)
        map.discardPendingLive()
    }

    @Test
    fun `allocation failure midway through snapshot growth drops preview and capture recovers`() = runTest {
        var allocations = 0
        var fail = true
        val map = ScanDenseMap(maxPoints = 20_000, clock = { testScheduler.currentTime * NS_PER_MS },
            liveDispatcher = StandardTestDispatcher(testScheduler), previewLog = {},
            snapshotAllocation = { if (fail && ++allocations == 3) throw OutOfMemoryError("injected") })
        map.add(patch(8_000))
        assertEquals(1, map.frames)
        assertNull(map.live)
        assertEquals(0L, map.snapshotStorageBytes)
        fail = false
        map.add(patch(8_000))
        map.finishPendingLive()
        assertEquals(2, map.frames)
        assertNotNull(map.live)
        map.discardPendingLive()
    }

    @Test
    fun `close returns while fusion holds the gate and forbids a late preview`() = runTest {
        val entered = java.util.concurrent.CountDownLatch(1)
        val unblock = java.util.concurrent.CountDownLatch(1)
        val map = ScanDenseMap(maxPoints = 20_000, liveDispatcher = StandardTestDispatcher(testScheduler),
            fuse = { fusion, samples ->
                entered.countDown()
                check(unblock.await(5, java.util.concurrent.TimeUnit.SECONDS))
                fusion.add(samples)
            })
        val worker = Thread { map.add(patch(800)) }
        worker.start()
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // Would deadlock until the fuse timeout if close acquired publicationGate.
            map.close()
            assertTrue(worker.isAlive)
        } finally {
            unblock.countDown()
            worker.join(5_000)
        }
        assertFalse(worker.isAlive)
        advanceUntilIdle()
        map.releaseLiveBuffers()
        assertNull(map.live)
        assertEquals(0L, map.snapshotStorageBytes)
    }

    private fun layerArrays(layer: ReplayDenseLayer): List<Any?> = listOf(
        layer.cloud.positions.toList(), layer.cloud.normals?.toList(), layer.cloud.colors.toList(),
        layer.cloud.confidences?.toList(), layer.cloud.levels?.toList(), layer.whole.toList(),
        layer.coveredCells.toList(), layer.weakCells.toList(), layer.mesh.positions.toList(),
        layer.mesh.indices.toList(), layer.mesh.uvs.toList(), layer.atlas.toList(), layer.offsets.toList(),
    )

    private fun primitiveArrayBytes(root: Any?): Long {
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        fun bytes(value: Any?): Long {
            if (value == null || seen.put(value, true) != null) return 0L
            return when (value) {
                is FloatArray -> value.size * 4L
                is IntArray -> value.size * 4L
                is LongArray -> value.size * 8L
                is ByteArray -> value.size.toLong()
                is BooleanArray -> value.size.toLong()
                is Array<*> -> value.sumOf { bytes(it) }
                else -> if (value.javaClass.name.startsWith("io.github.sceneview.demo.demos.internal.")) {
                    value.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                        .sumOf { it.isAccessible = true; bytes(it.get(value)) }
                } else 0L
            }
        }
        return bytes(root)
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
