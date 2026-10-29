package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * #4742: a surfel nearer than [DenseSurfels.NEAR_FADE_M] shrinks towards a dot, and none is drawn
 * past [DenseSurfels.MAX_SCREEN_SIDE] of the view's height. `scan_surfel.mat` does it on the GPU from the corner offsets built here;
 * [DenseSurfels.screenScale] is the same arithmetic on the JVM.
 */
class DenseSurfelClampTest {

    @Test
    fun `each corner offset leads back to its surfel's centre`() {
        val cloud = DenseCloud(
            floatArrayOf(0.5f, 1f, -2f, 3f, 0.2f, 0.7f),
            intArrayOf(0, 0),
            normals = floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f),
        )
        val mesh = DenseSurfels.mesh(cloud, DenseFusion.VOXEL_M)
        val offsets = DenseSurfels.cornerOffsets(mesh)
        assertEquals(mesh.vertexCount * 3, offsets.size)
        val halfDiagonal = DenseFusion.VOXEL_M * DenseSurfels.HALF_SIDE_VOXELS * sqrt(2f)
        for (v in 0 until mesh.vertexCount) {
            val surfel = v / 4
            for (axis in 0 until 3) {
                val centre = mesh.positions[v * 3 + axis] - offsets[v * 3 + axis]
                assertEquals(cloud.positions[surfel * 3 + axis], centre, 1e-5f)
            }
            val o = offsets.copyOfRange(v * 3, v * 3 + 3)
            assertEquals(halfDiagonal, sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2]), 1e-5f)
        }
    }

    @Test
    fun `a fine surfel past the fade distance is drawn at its own size`() {
        val half = halfDiagonal(level = 0)
        // Across a room, through a phone's ~60° lens.
        assertEquals(1f, DenseSurfels.screenScale(half, depth = 2f, clipYScale = PHONE_CLIP_Y), 0f)
        assertEquals(1f, DenseSurfels.screenScale(half, depth = 0.6f, clipYScale = PHONE_CLIP_Y), 0f)
        assertEquals(1f, DenseSurfels.screenScale(half, depth = DenseSurfels.NEAR_FADE_M, clipYScale = PHONE_CLIP_Y), 0f)
    }

    @Test
    fun `the coarsest surfel a metre away is drawn at its own size, so walls keep no holes`() {
        // Level MAX_LEVEL: about 10 cm on an 8 cm grid. Any shrink here opens gaps between them.
        val half = halfDiagonal(level = DenseFusion.MAX_LEVEL)
        assertEquals(1f, DenseSurfels.screenScale(half, depth = 1f, clipYScale = PHONE_CLIP_Y), 0f)
        assertEquals(1f, DenseSurfels.screenScale(half, depth = 0.5f, clipYScale = PHONE_CLIP_Y), 0f)
    }

    @Test
    fun `a surfel a few centimetres from the camera shrinks to a dot`() {
        // The coarsest surfel the map draws, 3 cm ahead: the #4742 splat.
        val half = halfDiagonal(level = DenseFusion.MAX_LEVEL)
        val depth = 0.03f
        val unclamped = sideOnScreen(half, depth)
        assertTrue("unclamped it is wider than the screen is tall: $unclamped", unclamped > 1f)
        val drawn = unclamped * DenseSurfels.screenScale(half, depth, PHONE_CLIP_Y)
        assertTrue("drawn at $drawn of the view's height", drawn < 0.05f)
    }

    @Test
    fun `no surfel is ever drawn past the cap, behind the near plane included`() {
        for (level in 0..DenseFusion.MAX_LEVEL) {
            val half = halfDiagonal(level)
            for (depth in listOf(0f, 1e-6f, 0.01f, 0.1f, 0.2f, 0.3f, 0.36f, 0.5f)) {
                val scale = DenseSurfels.screenScale(half, depth, PHONE_CLIP_Y)
                assertTrue(scale.isFinite() && scale in 0f..1f)
                val drawn = sideOnScreen(half, depth.coerceAtLeast(1e-4f)) * scale
                assertTrue("level $level, depth $depth: $drawn", drawn <= DenseSurfels.MAX_SCREEN_SIDE + 1e-5f)
            }
        }
    }

    @Test
    fun `nearer than the fade distance a surfel only gets smaller as the camera closes in`() {
        val half = halfDiagonal(level = 0)
        val depths = listOf(0.29f, 0.2f, 0.1f, 0.05f, 0.01f)
        val drawn = depths.map { sideOnScreen(half, it) * DenseSurfels.screenScale(half, it, PHONE_CLIP_Y) }
        assertEquals(drawn.sortedDescending(), drawn)
    }

    private fun halfDiagonal(level: Int) =
        DenseFusion.VOXEL_M * DenseSurfels.HALF_SIDE_VOXELS * (1 shl level) * sqrt(2f)

    private fun sideOnScreen(half: Float, depth: Float) = half * sqrt(2f) * PHONE_CLIP_Y / (2f * depth)

    private companion object {
        /** `[1][1]` of a 60° vertical field of view: 1 / tan(30°). */
        const val PHONE_CLIP_Y = 1.7320508f
    }
}
