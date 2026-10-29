package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** [RescanGuidance] (#4736): how much of a part a re-scan fills, and where its frame is drawn. */
class RescanGuidanceTest {

    @Test
    fun `a floor part fills with the points on it`() {
        assertEquals(0f, RescanGuidance.fill(RescanKind.FloorHole, HOLE, DenseCloud.Empty), 0f)
        val half = floorPoints { x, _ -> x < 0.2f }
        assertEquals(0.5f, RescanGuidance.fill(RescanKind.FloorHole, HOLE, half), 0.05f)
        val all = floorPoints { _, _ -> true }
        assertEquals(1f, RescanGuidance.fill(RescanKind.LowDensity, HOLE, all), 0f)
        assertTrue(RescanGuidance.covered(RescanGuidance.fill(RescanKind.FloorHole, HOLE, all)))
        assertFalse(RescanGuidance.covered(RescanGuidance.fill(RescanKind.FloorHole, HOLE, half)))
    }

    @Test
    fun `a point above a floor part does not fill it`() {
        val shelf = floorPoints(y = 0.6f) { _, _ -> true }
        assertEquals(0f, RescanGuidance.fill(RescanKind.FloorHole, HOLE, shelf), 0f)
    }

    @Test
    fun `a slanted wall fills along its own foot, either diagonal`() {
        for (falling in listOf(false, true)) {
            val (ax, az, bx, bz) = if (falling) listOf(0f, 1f, 2f, 0f) else listOf(0f, 0f, 2f, 1f)
            val box = wallBox(ax, az, bx, bz)
            val whole = wallPoints(ax, az, bx, bz) { _, _ -> true }
            assertEquals("falling=$falling", 1f, RescanGuidance.fill(RescanKind.MissingWall, box, whole), 0.05f)
            val left = wallPoints(ax, az, bx, bz) { t, _ -> t < 0.5f }
            assertEquals("falling=$falling", 0.5f, RescanGuidance.fill(RescanKind.MissingWall, box, left), 0.08f)
            val high = wallPoints(ax, az, bx, bz) { _, y -> y > RescanGuidance.WALL_HIGH_M }
            assertEquals("falling=$falling", 0f, RescanGuidance.fill(RescanKind.MissingWall, box, high), 0.12f)
        }
    }

    @Test
    fun `a box in front of the eye is drawn whole, one behind it not at all`() {
        val front = RescanPose.Identity.corners(floatArrayOf(-0.5f, -0.5f, -3f, 0.5f, 0.5f, -2f))
        val edges = RescanGuidance.outline(front, IDENTITY, PROJECTION, VIEW, VIEW)
        assertEquals(EDGE_COUNT * 4, edges.size)
        // The near face at 2 m: ±0.5 m on a unit focal is ±0.25 of the half view.
        assertEquals(37.5f, edges.filterIndexed { i, _ -> i % 2 == 0 }.min(), 0.01f)
        assertEquals(62.5f, edges.filterIndexed { i, _ -> i % 2 == 0 }.max(), 0.01f)
        val behind = RescanPose.Identity.corners(floatArrayOf(-0.5f, -0.5f, 1f, 0.5f, 0.5f, 2f))
        assertEquals(0, RescanGuidance.outline(behind, IDENTITY, PROJECTION, VIEW, VIEW).size)
        val across = RescanPose.Identity.corners(floatArrayOf(-0.5f, -0.5f, -2f, 0.5f, 0.5f, 1f))
        assertEquals(8 * 4, RescanGuidance.outline(across, IDENTITY, PROJECTION, VIEW, VIEW).size)
    }

    @Test
    fun `the eye's own pose moves the frame`() {
        // The eye 1 m to the right: the box straight ahead of the origin shows left of centre.
        val moved = IDENTITY.copyOf().also { it[12] = 1f }
        val front = RescanPose.Identity.corners(floatArrayOf(-0.1f, -0.1f, -2.1f, 0.1f, 0.1f, -2f))
        val xs = RescanGuidance.outline(front, moved, PROJECTION, VIEW, VIEW).filterIndexed { i, _ -> i % 2 == 0 }
        assertTrue(xs.max() < VIEW / 2f)
    }

    private fun floorPoints(y: Float = 0f, keep: (Float, Float) -> Boolean): DenseCloud {
        val out = ArrayList<Float>()
        var x = HOLE[0] + STEP_M / 2f
        while (x < HOLE[3]) {
            var z = HOLE[2] + STEP_M / 2f
            while (z < HOLE[5]) {
                if (keep(x, z)) out += listOf(x, y, z)
                z += STEP_M
            }
            x += STEP_M
        }
        return cloudOf(out)
    }

    private fun wallPoints(ax: Float, az: Float, bx: Float, bz: Float, keep: (Float, Float) -> Boolean): DenseCloud {
        val length = sqrt((bx - ax) * (bx - ax) + (bz - az) * (bz - az))
        val out = ArrayList<Float>()
        var s = STEP_M / 2f
        while (s < length) {
            var y = STEP_M / 2f
            while (y < HEIGHT_M) {
                val t = s / length
                if (keep(t, y)) out += listOf(ax + (bx - ax) * t, y, az + (bz - az) * t)
                y += STEP_M
            }
            s += STEP_M
        }
        return cloudOf(out)
    }

    private fun wallBox(ax: Float, az: Float, bx: Float, bz: Float): FloatArray {
        val h = RescanZones.WALL_HALF_M
        return floatArrayOf(minOf(ax, bx) - h, 0f, minOf(az, bz) - h, maxOf(ax, bx) + h, HEIGHT_M, maxOf(az, bz) + h)
    }

    private fun cloudOf(positions: List<Float>) = DenseCloud(positions.toFloatArray(), IntArray(positions.size / 3))

    private companion object {
        const val STEP_M = 0.02f
        const val HEIGHT_M = 2.4f
        const val VIEW = 100f
        const val EDGE_COUNT = 12
        val HOLE = floatArrayOf(0f, -0.05f, 0f, 0.4f, 0.05f, 0.4f)
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

        /** A unit-focal square perspective from 0.05 m to 20 m, column-major. */
        val PROJECTION = run {
            val n = 0.05f
            val f = 20f
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, -(f + n) / (f - n), -1f, 0f, 0f, -2f * f * n / (f - n), 0f)
        }
    }
}
