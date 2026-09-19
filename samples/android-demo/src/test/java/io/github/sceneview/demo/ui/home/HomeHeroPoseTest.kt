package io.github.sceneview.demo.ui.home

import dev.romainguy.kotlin.math.distance
import dev.romainguy.kotlin.math.length
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The hero camera's acceptance table — the same one the iOS port has to pass.
 *
 * The concept fixes a single vertical field of view on all four surfaces precisely so that one
 * table of poses can be the contract instead of four sets of screenshots. These are the five
 * sampled points of that table, at W = 350 dp, u = 0, psi = 0.
 *
 * The tolerance is stated, not discovered: the table was produced by the design's own sampler at
 * 65 entries, this implementation tabulates the same law at the same 65 entries, and the residual
 * is the interpolation of `s` between neighbouring samples. 3 % bounds it. Anything outside that
 * is a different curve, not a rounding difference.
 */
class HomeHeroPoseTest {

    private val width = 350f
    private val subjectUnits = 1f
    private val tolerance = 0.03f

    private fun pose(p: Float) =
        HomeHeroPose.pose(progress = p, widthDp = width, subjectUnits = subjectUnits)

    private fun assertClose(expected: Float, actual: Float, what: String) {
        val deviation = if (expected == 0f) abs(actual) else abs(actual - expected) / abs(expected)
        assertTrue(
            "$what: expected $expected, got $actual (${"%.1f".format(deviation * 100)} % off)",
            deviation <= tolerance,
        )
    }

    @Test
    fun `matches the shared pose table`() {
        // p, visH, s, cx, cy, azimuth, elevation, d/U
        val table = listOf(
            Row(0f, 420f, 225.1f, 175f, 138f, -28f, 4f, 2.18f),
            Row(0.33f, 307.8f, 168.2f, 175f, null, -8.2f, 13.8f, 2.91f),
            Row(0.5f, 250f, 143.5f, 131.4f, 88f, 2f, 16.2f, 3.41f),
            Row(0.66f, 195.6f, 126.9f, 79.4f, 87.2f, 11.6f, 17.4f, 3.86f),
            Row(1f, 80f, 48f, 40f, 40f, 32f, 18f, 10.21f),
        )
        for (row in table) {
            val pose = pose(row.p)
            assertEquals("visH at p=${row.p}", row.visibleHeight, pose.visibleHeightDp, 0.1f)
            assertClose(row.size, pose.sizeDp, "s at p=${row.p}")
            assertClose(row.centerX, pose.centerXDp, "cx at p=${row.p}")
            row.centerY?.let { assertClose(it, pose.centerYDp, "cy at p=${row.p}") }
            assertEquals("azimuth at p=${row.p}", row.azimuth, pose.azimuthDegrees, 0.1f)
            assertEquals("elevation at p=${row.p}", row.elevation, pose.elevationDegrees, 0.1f)
            assertClose(row.distanceOverUnits, pose.distance, "d/U at p=${row.p}")
        }
    }

    /**
     * Thomas' legibility floor: the subject is never smaller than 45 % of what the clip shows.
     *
     * Checked on 513 samples rather than the five tabulated ones, because the failure this guards
     * against — a hero that goes briefly empty halfway down — lives between the table's rows.
     */
    @Test
    fun `the subject never drops below 45 percent of the visible stage`() {
        var worst = Float.MAX_VALUE
        var worstAt = 0f
        for (i in 0..512) {
            val p = i / 512f
            val pose = pose(p)
            val ratio = pose.sizeDp / pose.visibleHeightDp
            if (ratio < worst) {
                worst = ratio
                worstAt = p
            }
        }
        assertTrue(
            "smallest subject was ${"%.1f".format(worst * 100)} % of the stage at p=$worstAt",
            worst >= 0.45f,
        )
    }

    /** A subject that grew back mid-scroll reads as a stutter. The table carries a running min. */
    @Test
    fun `the subject only ever shrinks`() {
        var previous = Float.MAX_VALUE
        for (i in 0..512) {
            val pose = pose(i / 512f)
            assertTrue("size grew at p=${i / 512f}", pose.sizeDp <= previous + 1e-3f)
            previous = pose.sizeDp
        }
    }

    /** The eye sits exactly `d` from the target, whatever the framing offset does. */
    @Test
    fun `the eye orbits the target at the tabulated distance`() {
        for (i in 0..64) {
            val pose = pose(i / 64f)
            assertEquals(pose.distance, distance(pose.eye, pose.target), 1e-3f)
        }
    }

    /**
     * The docked pose is exact whatever the turntable and the pager were doing — that is what
     * makes it safe for the band to be permanent.
     */
    @Test
    fun `idle yaw is fully faded out at the dock`() {
        val quiet = HomeHeroPose.pose(1f, widthDp = width, subjectUnits = subjectUnits)
        val spun = HomeHeroPose.pose(
            1f, pagerOffset = 0f, idleYaw = 720f, widthDp = width, subjectUnits = subjectUnits,
        )
        assertEquals(quiet.azimuthDegrees, spun.azimuthDegrees, 1e-3f)
        assertEquals(0f, distance(quiet.eye, spun.eye), 1e-3f)
    }

    /** A page swipe moves yaw and nothing else: 100 degrees per page, and no dolly. */
    @Test
    fun `a page offset turns the subject without moving the framing`() {
        val page0 = HomeHeroPose.pose(0f, widthDp = width, subjectUnits = subjectUnits)
        val half = HomeHeroPose.pose(
            0f, pagerOffset = 0.5f, widthDp = width, subjectUnits = subjectUnits,
        )
        assertEquals(50f, half.azimuthDegrees - page0.azimuthDegrees, 1e-3f)
        assertEquals(page0.distance, half.distance, 1e-3f)
        assertEquals(page0.sizeDp, half.sizeDp, 1e-3f)
        assertEquals(length(page0.target), length(half.target), 1e-3f)
    }

    private data class Row(
        val p: Float,
        val visibleHeight: Float,
        val size: Float,
        val centerX: Float,
        val centerY: Float?,
        val azimuth: Float,
        val elevation: Float,
        val distanceOverUnits: Float,
    )
}
