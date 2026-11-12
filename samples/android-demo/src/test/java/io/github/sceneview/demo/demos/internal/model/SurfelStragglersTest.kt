package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.DenseCloud
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Photo layer draws the place, not the specks a scan leaves round it. */
class SurfelStragglersTest {
    /** A [SIDE]×[SIDE] floor of level-0 surfels 2 cm apart, then [extra] (x, y, z, level) surfels. */
    private fun floorWith(vararg extra: FloatArray): DenseCloud {
        val n = SIDE * SIDE + extra.size
        val positions = FloatArray(n * 3)
        val levels = ByteArray(n)
        for (i in 0 until SIDE * SIDE) {
            positions[i * 3] = (i % SIDE) * STEP_M
            positions[i * 3 + 2] = (i / SIDE) * STEP_M
        }
        extra.forEachIndexed { k, e ->
            val i = SIDE * SIDE + k
            positions[i * 3] = e[0]
            positions[i * 3 + 1] = e[1]
            positions[i * 3 + 2] = e[2]
            levels[i] = e[3].toInt().toByte()
        }
        return DenseCloud(positions, IntArray(n) { GREY }, FloatArray(n * 3) { if (it % 3 == 1) 1f else 0f }, levels = levels)
    }

    @Test
    fun `specks in the air and off the rim go, the floor stays whole`() {
        val cloud = floorWith(
            floatArrayOf(1f, 1.5f, 1f, 0f), // in the air over the floor
            floatArrayOf(-2f, 0f, -2f, 0f), // off its rim
            floatArrayOf(-2.02f, 0f, -2f, 0f), // a pair is still a speck
        )
        val skip = requireNotNull(SurfelSplats.stragglers(cloud))
        assertEquals(3, skip.count { it })
        assertFalse((0 until SIDE * SIDE).any { skip[it] })
        // The floor's own edge and corners are surface, not specks.
        assertFalse(skip[0] || skip[SIDE - 1] || skip[SIDE * SIDE - 1])
        assertEquals(SIDE * SIDE, SurfelSplats.of(cloud, maxSplats = Int.MAX_VALUE, skip = skip).count)
        assertEquals(minOf(SIDE * SIDE, SurfelSplats.MAX_SPLATS), SurfelSplats.ofPlace(cloud).count)
    }

    @Test
    fun `a coarse surfel stands for the surface it covers`() {
        // One level-3 surfel, alone in its cell: a 16 cm patch of a far wall, not a speck.
        val cloud = floorWith(floatArrayOf(-3f, 1f, -3f, 3f))
        assertNull(SurfelSplats.stragglers(cloud))
    }

    @Test
    fun `a clean scan and a small one are kept whole`() {
        assertNull(SurfelSplats.stragglers(floorWith()))
        val small = DenseCloud(floatArrayOf(0f, 0f, 0f, 5f, 5f, 5f), intArrayOf(GREY, GREY))
        assertNull(SurfelSplats.stragglers(small))
        assertEquals(2, SurfelSplats.ofPlace(small).count)
        assertNotNull(SurfelSplats.stragglers(floorWith(floatArrayOf(9f, 9f, 9f, 0f))))
        assertTrue(SIDE * SIDE >= SurfelSplats.MIN_JUDGED)
    }

    private companion object {
        const val SIDE = 110
        const val STEP_M = 0.02f
        const val GREY = 0xFF808080.toInt()
    }
}
