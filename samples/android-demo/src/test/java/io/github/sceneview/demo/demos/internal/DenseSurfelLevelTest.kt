package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * A coarsened surfel is drawn as wide as the region it stands for, so a wall the map coarsened
 * keeps no holes. Over the camera its size on screen is capped instead
 * ([DenseSurfels.maxScreenSide]): close by it is drawn no larger than a fine one, farther it is
 * drawn whole.
 */
class DenseSurfelLevelTest {

    /** One surfel of [level], facing up. */
    private fun surfel(level: Int) = DenseCloud(
        positions = floatArrayOf(0f, 0f, 0f),
        colors = intArrayOf(0),
        normals = floatArrayOf(0f, 1f, 0f),
        levels = byteArrayOf(level.toByte()),
    )

    /** The side of the first quad of [mesh]: its first two corners apart. */
    private fun side(mesh: DebugMesh): Float {
        val p = mesh.positions
        val dx = p[3] - p[0]
        val dy = p[4] - p[1]
        val dz = p[5] - p[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** What a view scales a surfel of [level] by, [depth] metres ahead on a phone's lens. */
    private fun scale(level: Int, depth: Float, live: Boolean): Float {
        val halfSide = FINE_SIDE / 2f * (1 shl level)
        return DenseSurfels.screenScale(
            halfDiagonal = halfSide * sqrt(2f),
            depth = depth,
            clipYScale = PHONE_CLIP_Y,
            maxSide = DenseSurfels.maxScreenSide(live),
        )
    }

    /** The side on screen, as a fraction of the view's height, of a [side]-metre surfel [depth] ahead. */
    private fun onScreen(side: Float, depth: Float) = side * PHONE_CLIP_Y / (2f * depth)

    @Test
    fun `a coarse surfel is drawn as wide as its level`() {
        val side = side(DenseSurfels.mesh(surfel(LEVEL), VOXEL_M))
        assertEquals(FINE_SIDE * (1 shl LEVEL), side, EPS)
    }

    @Test
    fun `over the camera a coarse surfel close by is drawn no wider than a tenth of the view`() {
        val coarse = FINE_SIDE * (1 shl LEVEL)
        val drawn = onScreen(coarse, NEAR_M) * scale(LEVEL, NEAR_M, live = true)
        assertTrue("drawn $drawn of the view's height", drawn <= DenseSurfels.LIVE_MAX_SCREEN_SIDE + EPS)
    }

    @Test
    fun `over the camera a coarse surfel a step away is drawn whole`() {
        assertEquals(1f, scale(LEVEL, depth = FAR_M, live = true), 0f)
    }

    @Test
    fun `over the camera a fine surfel is drawn as built until it fades`() {
        assertEquals(1f, scale(0, depth = DenseSurfels.NEAR_FADE_M + 0.05f, live = true), 0f)
    }

    @Test
    fun `the replay draws the coarse surfel close by whole`() {
        assertEquals(1f, scale(LEVEL, NEAR_M, live = false), 0f)
    }

    private companion object {
        const val VOXEL_M = 0.02f
        const val LEVEL = 2
        const val FINE_SIDE = 2f * VOXEL_M * DenseSurfels.HALF_SIDE_VOXELS
        const val EPS = 1e-5f

        /** A phone camera's vertical projection term: 60° of field of view. */
        const val PHONE_CLIP_Y = 1.7320508f

        /** Half a metre: a wall the phone is held close to. */
        const val NEAR_M = 0.5f

        /** A metre and a half: a wall across a small room. */
        const val FAR_M = 1.5f
    }
}
