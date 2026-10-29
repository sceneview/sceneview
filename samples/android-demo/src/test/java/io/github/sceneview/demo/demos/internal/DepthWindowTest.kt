package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.DepthFreshness.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Pixel 9 room scan stood at 274,778 points with "No depth yet" for two minutes (scan
 * time 1:32–3:32) while its wall count rose from 3 to 10: tracking and planes were fine, but
 * every new depth estimate fell outside [DepthFreshness]'s window and nothing was fused again.
 *
 * Each test drives a scan's frame loop as `ScanCapture.acquireDepth` does: a camera frame every
 * 33.3 ms, a raw-depth estimate every 100 ms of camera time, handed over [lagAt] after the camera
 * frame it was computed from, one depth keyframe per 100 ms at most.
 */
class DepthWindowTest {

    private val boot = 50_000_000_000L
    private val frame = 33_333_333L
    private val period = 100_000_000L

    /** What one scan of [seconds] fused, and what the coaching line said. */
    private class Run(val fusedAt: List<Long>, val starvedFrames: List<Long>, val takenTwice: Boolean)

    /**
     * Drives [seconds] of a scan whose estimates arrive [lagAt] (ns after their camera frame, as a
     * function of the scan's time) late and stamped [skewAt] after it; [take] decides, from the
     * verdict, whether the scan fuses one.
     */
    private fun scan(
        seconds: Int,
        lagAt: (Long) -> Long,
        skewAt: (Long) -> Long = { 0L },
        window: DepthWindow? = DepthWindow(),
    ): Run {
        val fused = ArrayList<Long>()
        val starved = ArrayList<Long>()
        val taken = HashSet<Long>()
        var twice = false
        var lastFused: Long? = null
        var gate: Long? = null
        // The plain window alone, as the scan had it before this fix.
        var waitingFrom = 0L
        val frames = seconds * 1_000_000_000L / frame
        for (n in 0 until frames) {
            val t = n * frame
            val nanos = boot + t
            window?.frame(nanos)
            if (waitingFrom == 0L) waitingFrom = nanos
            val due = gate == null || nanos - gate >= period
            // The newest estimate ARCore has finished by this frame.
            var k = t / period
            while (k >= 0 && k * period + lagAt(k * period) > t) k--
            if (due && k >= 0) {
                val depthNanos = boot + k * period + skewAt(k * period)
                val last = window?.lastTakenNanos ?: lastFused
                val verdict = DepthFreshness.judge(nanos, depthNanos, last)
                val take = window?.take(verdict, nanos, depthNanos) ?: (verdict == Verdict.FRESH)
                if (take) {
                    if (!taken.add(depthNanos)) twice = true
                    fused += t
                    lastFused = depthNanos
                    gate = nanos
                    waitingFrom = nanos
                }
            }
            val isStarved = window?.starved ?: (nanos - waitingFrom > DepthFreshness.STARVED_AFTER_NANOS)
            if (isStarved) starved += t
        }
        return Run(fused, starved, twice)
    }

    /** Depth from motion falling behind as the scan loads the phone: 40 ms, then 250 ms from 1:32. */
    private val fallingBehind: (Long) -> Long = { t -> if (t < FREEZE_AT) 40_000_000L else 250_000_000L }

    @Test
    fun `the plain window freezes the scan once ARCore's depth runs late — the Pixel 9 freeze`() {
        val run = scan(seconds = SCAN_SECONDS, lagAt = fallingBehind, window = null)
        assertTrue("it fused before 1:32", run.fusedAt.count { it < FREEZE_AT } > 600)
        assertEquals("nothing after 1:32", 0, run.fusedAt.count { it > FREEZE_AT + period })
        assertTrue("\"No depth yet\" for two minutes", run.starvedFrames.count { it > FREEZE_AT } > 3_000)
    }

    @Test
    fun `late depth keeps the scan fusing at ARCore's own pace, and it never says no depth`() {
        val window = DepthWindow()
        val run = scan(seconds = SCAN_SECONDS, lagAt = fallingBehind, window = window)
        val after = run.fusedAt.count { it > FREEZE_AT + DepthFreshness.LATE_AFTER_NANOS }
        val seconds = (SCAN_SECONDS * 1_000_000_000L - FREEZE_AT) / 1_000_000_000L
        assertTrue("$after estimates fused in the last $seconds s", after >= seconds * 7)
        assertTrue("never starved: ${run.starvedFrames.size} frames", run.starvedFrames.isEmpty())
        assertFalse("an estimate is never fused twice", run.takenTwice)
        assertTrue(window.late)
        assertTrue(window.lateTaken > 800)
    }

    @Test
    fun `a depth clock drifting ahead of the camera's keeps fusing too, each estimate once`() {
        // From 1:32 the depth is stamped well after the frame that hands it over: every estimate
        // is AHEAD, and the plain window fused nothing more.
        val skew: (Long) -> Long = { t -> if (t < FREEZE_AT) 1_000_000L else 200_000_000L }
        val plain = scan(seconds = SCAN_SECONDS, lagAt = { 40_000_000L }, skewAt = skew, window = null)
        assertEquals(0, plain.fusedAt.count { it > FREEZE_AT + period })
        val run = scan(seconds = SCAN_SECONDS, lagAt = { 40_000_000L }, skewAt = skew)
        assertTrue(run.fusedAt.count { it > FREEZE_AT + DepthFreshness.LATE_AFTER_NANOS } > 800)
        assertTrue(run.starvedFrames.isEmpty())
        assertFalse(run.takenTwice)
    }

    @Test
    fun `on time depth never goes past the window`() {
        val window = DepthWindow()
        val run = scan(seconds = 30, lagAt = { 40_000_000L }, window = window)
        assertTrue(run.fusedAt.size >= 200)
        assertEquals(0, window.lateTaken)
        assertFalse(window.late)
    }

    @Test
    fun `a late estimate waits half a second, then fresh depth ends the late mode`() {
        val window = DepthWindow()
        window.frame(boot)
        assertTrue(window.take(Verdict.FRESH, boot, boot))
        val stale = boot + 200_000_000L
        window.frame(stale)
        assertFalse("a hiccup is not late depth", window.take(Verdict.STALE, stale, boot + 50_000_000L))
        val later = boot + DepthFreshness.LATE_AFTER_NANOS
        window.frame(later)
        assertTrue(window.take(Verdict.STALE, later, boot + 300_000_000L))
        assertTrue(window.late)
        assertFalse("the same estimate twice", window.take(Verdict.STALE, later + frame, boot + 300_000_000L))
        assertTrue("late mode takes the next one at once", window.take(Verdict.STALE, later + 2 * frame, boot + 400_000_000L))
        assertTrue(window.take(Verdict.FRESH, later + 3 * frame, later + 3 * frame))
        assertFalse(window.late)
        assertFalse(window.take(Verdict.ALREADY_FUSED, later + 4 * frame, later + 3 * frame))
    }

    @Test
    fun `no depth at all still says so after two seconds, and a pause does not`() {
        val window = DepthWindow()
        var t = boot
        while (t < boot + DepthFreshness.STARVED_AFTER_NANOS) {
            window.frame(t)
            t += frame
        }
        assertFalse(window.starved)
        window.frame(t + frame)
        assertTrue("ARCore gives nothing: the scan is stuck, and says so", window.starved)
        // A pause: frames stop, then come back; the wait starts again.
        window.frame(t + 10_000_000_000L)
        assertFalse(window.starved)
    }

    @Test
    fun `a scan with points that loses its depth says it was lost, not that none came yet`() {
        assertEquals(
            ScanCopy.COACH_NO_DEPTH,
            ScanCopy.coaching(ScanPhase.Scanning, trackingLost = false, photoLimitReached = false, depthStarved = true),
        )
        assertEquals(
            ScanCopy.COACH_DEPTH_LOST,
            ScanCopy.coaching(
                ScanPhase.Scanning, trackingLost = false, photoLimitReached = false,
                depthStarved = true, hasPoints = true,
            ),
        )
    }

    private companion object {
        /** The scan time the Pixel 9 froze at: 1:32. */
        const val FREEZE_AT = 92_000_000_000L
        const val SCAN_SECONDS = 212
    }
}
