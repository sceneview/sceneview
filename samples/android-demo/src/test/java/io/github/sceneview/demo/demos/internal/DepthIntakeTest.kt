package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DepthIntakeTest {
    @Test
    fun `a line counts each outcome since the last one, then starts over`() {
        val intake = DepthIntake(intervalNanos = SECOND)
        assertNull("nothing counted yet", intake.lineIfDue(0L, total = 0))
        repeat(3) { intake.count(DepthIntake.Outcome.Grew) }
        repeat(12) { intake.count(DepthIntake.Outcome.Stale) }
        intake.count(DepthIntake.Outcome.Busy)
        assertNull("not due before the interval", intake.lineIfDue(SECOND / 2, total = 80_000))
        assertEquals(
            "depth frames: busy=1 stale=12 grew=3 total=80000",
            intake.lineIfDue(SECOND, total = 80_000),
        )
        assertEquals(0, intake[DepthIntake.Outcome.Stale])
        assertNull("all counted already", intake.lineIfDue(2 * SECOND, total = 80_000))
    }

    /**
     * The Pixel 9 stall, replayed on the fusion: depth ARCore still gives, but doubtful everywhere
     * (confidence under [DepthBackProjection.MIN_CONFIDENCE], as on a blurred sweep) keeps the
     * dense map flat while the scan goes on, and the log line says why: `empty`, not `busy`.
     */
    @Test
    fun `doubtful depth keeps the map flat and the log names it`() {
        val fusion = DenseFusion()
        val intake = DepthIntake(intervalNanos = SECOND)
        fun fuse(frame: DepthFrame) {
            val samples = DepthBackProjection.project(frame)
            if (samples.count == 0) {
                intake.count(DepthIntake.Outcome.NoSamples)
                return
            }
            val stats = fusion.add(samples)
            intake.count(if (stats.added > 0) DepthIntake.Outcome.Grew else DepthIntake.Outcome.NothingNew)
        }
        intake.lineIfDue(0L, fusion.count)
        for (i in 0 until 10) fuse(wall(x = i * 0.5f, confidence = 250))
        val grown = fusion.count
        assertEquals("depth frames: grew=10 total=$grown", intake.lineIfDue(SECOND, fusion.count))
        // The phone sweeps new ground, but ARCore's depth is doubtful everywhere.
        for (i in 10 until 30) fuse(wall(x = i * 0.5f, confidence = 100))
        assertEquals("the map does not grow", grown, fusion.count)
        assertEquals("depth frames: empty=20 total=$grown", intake.lineIfDue(2 * SECOND, fusion.count))
        // Seeing the same wall again only merges.
        fuse(wall(x = 0f, confidence = 250))
        assertEquals("depth frames: known=1 total=$grown", intake.lineIfDue(3 * SECOND, fusion.count))
    }

    private companion object {
        const val SECOND = 1_000_000_000L
        const val W = 32
        const val H = 18

        /** A flat wall 1.5 m ahead of a camera at ([x], 0, 1.5), every pixel at [confidence]. */
        fun wall(x: Float, confidence: Int) = DepthFrame(
            width = W,
            height = H,
            depthMm = ShortArray(W * H) { 1500 },
            confidence = ByteArray(W * H) { confidence.toByte() },
            colors = IntArray(W * H) { 0xFF808080.toInt() },
            fx = 25f,
            fy = 25f,
            cx = W / 2f,
            cy = H / 2f,
            pose = DebugPose(x, 0f, 1.5f),
        )
    }
}
