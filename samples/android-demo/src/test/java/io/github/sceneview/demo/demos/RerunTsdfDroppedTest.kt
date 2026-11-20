package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DepthFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class RerunTsdfDroppedTest {
    @Test
    fun `a busy TSDF drops its offer and retains the total after release`() {
        val counters = ScanFrameCounters()
        val owner = RerunLiveModels.Owner()
        val model = RerunLiveModel(owner, frameCounters = counters)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        // Hold the real busy gate deterministically, without racing a short integration or
        // adding a test dispatcher to the production API.
        val gate = RerunLiveModel::class.java.getDeclaredField("busy").apply { isAccessible = true }
            .get(model) as AtomicBoolean
        gate.set(true)
        try {
            val frame = DepthFrame(2, 2, ShortArray(4), null, IntArray(4), 1f, 1f, 0f, 0f, DebugPose(0f, 0f, 0f))
            repeat(3) { model.offer(frame, scope) }
            assertEquals(ScanFrameCounts(tsdfDropped = 3), counters.snapshot())
            model.close()
            model.offer(frame, scope)
            assertEquals(ScanFrameCounts(tsdfDropped = 3), counters.snapshot())
        } finally {
            model.close()
            owner.close()
            scope.cancel()
        }
    }
}
