package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseSamples
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.ScanIntrinsics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanCaptureFinalCellsTest {
    @Test
    fun `finish journals final fusion coverage while live assembly is pending`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = RerunLiveModels.Owner()
        val capture = ScanCapture(
            ScanIntrinsics(100f, 100f, 50f, 50f, 100, 100), null, ReplayLens(0.5f, 0.5f),
            scope, owner, rawDepth = true,
        )
        var now = 0L
        val map = ScanDenseMap(maxPoints = 20_000, clock = { now }, liveDispatcher = Dispatchers.Unconfined,
            beforeBuild = { CompletableDeferred<Unit>().await() })
        // Install the pure map without ARCore sensor images or a racing worker.
        ScanCapture::class.java.getDeclaredField("dense").apply { isAccessible = true }.set(capture, map)
        try {
            capture.trace.addPose(0L, DebugPose(0f, 1.4f, 0f))
            capture.trace.addPoints(0L, floatArrayOf(0f, 0f, -1f))
            val positions = FloatArray(8_000 * 3)
            repeat(8_000) { i ->
                positions[i * 3] = i % 100 * 0.0201f
                positions[i * 3 + 2] = i / 100 * 0.0201f
            }
            val samples = DenseSamples(8_000, positions, FloatArray(positions.size), IntArray(8_000), ByteArray(8_000))
            map.add(samples)
            now += ScanDenseMap.LIVE_INTERVAL_NS
            map.add(samples)
            assertTrue(map.live == null)
            val cells = map.coveredCells()
            assertTrue(cells.isNotEmpty())
            val pack = checkNotNull(capture.finish())
            val saved = checkNotNull(pack.open()).trace
            assertEquals(cells.size, saved.floorCellsAt(saved.duration))
        } finally {
            capture.close()
            capture.releaseJob?.join()
            owner.close()
            scope.cancel()
        }
    }
}
