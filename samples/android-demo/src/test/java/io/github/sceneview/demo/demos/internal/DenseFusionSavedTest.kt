package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A saved scan keeps what the live view showed: a surface one depth frame swept stays, a speck
 * alone in the air a noisy frame left does not (2026-11-13, a Pixel 4a fusing one frame in ten).
 */
class DenseFusionSavedTest {
    private val voxel = DenseFusion.VOXEL_M

    private fun samples(positions: FloatArray): DenseSamples {
        val n = positions.size / 3
        return DenseSamples(
            count = n,
            positions = positions,
            normals = FloatArray(positions.size) { if (it % 3 == 0) 1f else 0f },
            colors = IntArray(n) { 0xFF808080.toInt() },
            confidences = ByteArray(n) { 200.toByte() },
        )
    }

    /** A [side]×[side] patch of a wall, one sample per voxel, at x = [x0] voxels. */
    private fun wall(side: Int, x0: Int = 0): FloatArray = FloatArray(side * side * 3).also { p ->
        for (j in 0 until side) for (k in 0 until side) {
            val o = (j * side + k) * 3
            p[o] = (x0 + 0.5f) * voxel
            p[o + 1] = (j + 0.5f) * voxel
            p[o + 2] = (k + 0.5f) * voxel
        }
    }

    @Test
    fun `a wall one depth frame swept is kept`() {
        val fusion = DenseFusion()
        fusion.add(samples(wall(6)))
        assertEquals(0, fusion.cloud(minViews = DenseFusion.MIN_VIEWS).count)
        // Its four corners hold three neighbours each: a patch's edge loses a corner voxel, no more.
        assertEquals(32, fusion.savedCloud().count)
    }

    @Test
    fun `a speck alone in the air is left out`() {
        val fusion = DenseFusion()
        fusion.add(samples(wall(6)))
        // One sample a metre off the wall, and a pair beside each other.
        fusion.add(samples(floatArrayOf(1.01f, 1.01f, 1.01f, 2.01f, 2.01f, 2.01f, 2.01f + voxel, 2.01f, 2.01f)))
        assertEquals(39, fusion.count)
        assertEquals(32, fusion.savedCloud().count)
    }

    @Test
    fun `a speck two frames saw is kept as before`() {
        val fusion = DenseFusion()
        fusion.add(samples(floatArrayOf(1f, 1f, 1f)))
        fusion.add(samples(floatArrayOf(1f, 1f, 1f)))
        assertEquals(1, fusion.savedCloud().count)
    }

    @Test
    fun `a replay reveals the saved cloud whole by its last frame`() {
        val fusion = DenseFusion()
        fusion.add(samples(wall(4)))
        fusion.add(samples(wall(4, x0 = 20)))
        fusion.add(samples(floatArrayOf(1f, 1f, 1f)))
        // Each 4×4 patch keeps 12, its corners out.
        assertArrayEquals(intArrayOf(12, 24, 24), fusion.savedRevealCounts(intArrayOf(1, 2, 3)))
        assertEquals(24, fusion.savedCloud().count)
    }
}
