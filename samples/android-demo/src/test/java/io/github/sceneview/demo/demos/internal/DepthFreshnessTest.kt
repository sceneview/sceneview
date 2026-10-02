package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.DepthFreshness.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dense scan's depth gate (#4221). Timestamps are ARCore's nanoseconds since boot; a camera
 * frame every 33.3 ms (30 fps), a new raw-depth estimate about every 100 ms.
 */
class DepthFreshnessTest {

    private val boot = 12_345_678_901_234L
    private val frame = 33_333_333L

    @Test
    fun `depth measured for this very frame is fused`() {
        assertEquals(Verdict.FRESH, DepthFreshness.judge(boot, boot, null))
    }

    @Test
    fun `depth from motion one or two frames behind is fused`() {
        // The Pixel 4a playback case: depth.timestamp != frame.timestamp fused 0 points.
        assertEquals(Verdict.FRESH, DepthFreshness.judge(boot + frame, boot, null))
        assertEquals(Verdict.FRESH, DepthFreshness.judge(boot + 2 * frame, boot, null))
    }

    @Test
    fun `depth exactly one raw-depth period behind is still fused`() {
        val frameNanos = boot + DepthFreshness.MAX_AGE_NANOS
        assertEquals(Verdict.FRESH, DepthFreshness.judge(frameNanos, boot, null))
    }

    @Test
    fun `depth older than one raw-depth period is stale`() {
        val frameNanos = boot + DepthFreshness.MAX_AGE_NANOS + 1
        assertEquals(Verdict.STALE, DepthFreshness.judge(frameNanos, boot, null))
        assertEquals(Verdict.STALE, DepthFreshness.judge(boot + 4 * frame, boot, null))
    }

    @Test
    fun `the same estimate reprojected to later frames is never fused twice`() {
        // ARCore keeps the estimate's timestamp on the reprojections between two estimates.
        assertEquals(Verdict.ALREADY_FUSED, DepthFreshness.judge(boot + frame, boot, lastFusedNanos = boot))
        assertEquals(Verdict.ALREADY_FUSED, DepthFreshness.judge(boot, boot, lastFusedNanos = boot))
    }

    @Test
    fun `an estimate older than the last fused one is not fused`() {
        val verdict = DepthFreshness.judge(boot + 4 * frame, boot + frame, lastFusedNanos = boot + 2 * frame)
        assertEquals(Verdict.ALREADY_FUSED, verdict)
    }

    @Test
    fun `the next estimate after a fused one is fused`() {
        val next = boot + 3 * frame
        assertEquals(Verdict.FRESH, DepthFreshness.judge(next + frame, next, lastFusedNanos = boot))
    }

    @Test
    fun `already fused wins over stale so the log names the reprojection`() {
        val verdict = DepthFreshness.judge(boot + 10 * frame, boot, lastFusedNanos = boot)
        assertEquals(Verdict.ALREADY_FUSED, verdict)
    }

    @Test
    fun `depth stamped after its frame is rejected`() {
        assertEquals(Verdict.AHEAD, DepthFreshness.judge(boot, boot + 1, null))
    }

    @Test
    fun `a clock that went back forgets the last fused depth`() {
        // A session restarted into the same scan: its frames start below the old last fused stamp.
        val restarted = 1_000_000_000L
        assertEquals(Verdict.FRESH, DepthFreshness.judge(restarted + frame, restarted, lastFusedNanos = boot))
    }

    @Test
    fun `the bound is one raw-depth period, three camera frames at 30 fps`() {
        assertEquals(100_000_000L, DepthFreshness.MAX_AGE_NANOS)
        assertEquals(3L, DepthFreshness.MAX_AGE_NANOS / frame)
    }
}
