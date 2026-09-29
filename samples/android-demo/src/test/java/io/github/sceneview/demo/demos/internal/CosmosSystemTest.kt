package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

class CosmosSystemTest {

    private val portrait = 1080f / 2400f

    private fun length(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    @Test
    fun `ease-expressive runs from 0 to 1 and never goes back`() {
        assertEquals(0f, CosmosSystem.easeExpressive(0f), 0f)
        assertEquals(1f, CosmosSystem.easeExpressive(1f), 0f)
        var previous = 0f
        for (i in 1..100) {
            val y = CosmosSystem.easeExpressive(i / 100f)
            assertTrue("monotonic at ${i / 100f}", y >= previous - 1e-5f)
            previous = y
        }
        // A quick start that settles long: well past halfway at the midpoint.
        assertTrue(CosmosSystem.easeExpressive(0.5f) > 0.75f)
    }

    @Test
    fun `a flight starts and lands exactly on its two poses`() {
        val from = CosmosSystem.pose(CosmosFocus.System, 3f, portrait)
        val to = CosmosSystem.pose(CosmosFocus.Planet, 3f, portrait)
        val start = CosmosSystem.blend(from, to, 0f)
        val end = CosmosSystem.blend(from, to, 1f)
        for (i in 0 until 6) {
            assertEquals(from[i], start[i], 1e-4f)
            assertEquals(to[i], end[i], 1e-4f)
        }
    }

    @Test
    fun `a flight goes round the star, never through it`() {
        val from = CosmosSystem.pose(CosmosFocus.Star, 3f, portrait)
        val to = CosmosSystem.pose(CosmosFocus.Planet, 3f, portrait)
        val closest = minOf(length(from), length(to))
        for (i in 0..50) {
            val eye = CosmosSystem.blend(from, to, i / 50f)
            assertTrue("eye stays outside the star", length(eye) >= closest - 1e-3f)
        }
    }

    @Test
    fun `the overview frames the whole orbit, rings included`() {
        for (aspect in floatArrayOf(portrait, 1f, 2400f / 1080f)) {
            for (time in floatArrayOf(0f, 3f, 17f, 60f)) {
                val pose = CosmosSystem.systemPose(time, aspect)
                for (step in 0 until 72) {
                    val t = time + step * 60f / 72f
                    val s = CosmosSystem.project(pose, aspect, CosmosSystem.planetPosition(t, aspect))
                    assertNotNull(s)
                    assertTrue("x inside at aspect $aspect", abs(s!![0]) < 1f)
                    assertTrue("y inside at aspect $aspect", abs(s[1]) < 1f)
                }
            }
        }
    }

    @Test
    fun `tapping the planet where it is drawn finds it, empty space finds nothing`() {
        val width = 1080f
        val height = 2400f
        val time = 3f
        val pose = CosmosSystem.systemPose(time, width / height)
        val s = CosmosSystem.project(pose, width / height, CosmosSystem.planetPosition(time, width / height))!!
        val px = (s[0] + 1f) * 0.5f * width
        val py = (1f - s[1]) * 0.5f * height
        assertEquals(CosmosFocus.Planet, CosmosSystem.hit(pose, time, width, height, px, py, minRadiusPx = 84f))
        assertEquals(CosmosFocus.Star, CosmosSystem.hit(pose, time, width, height, width / 2f, height / 2f, 84f))
        assertNull(CosmosSystem.hit(pose, time, width, height, 10f, 10f, 84f))
    }

    @Test
    fun `the planet stays on its orbit`() {
        for (time in floatArrayOf(0f, 1f, 30f)) {
            assertEquals(CosmosSystem.ORBIT_RADIUS, length(CosmosSystem.planetPosition(time, portrait)), 1e-4f)
        }
    }

    @Test
    fun `quaternion rotation turns x into y about z`() {
        val q = CosmosSystem.axisAngle(0f, 0f, 1f, (PI / 2).toFloat())
        val v = CosmosSystem.rotate(q, floatArrayOf(1f, 0f, 0f))
        assertEquals(0f, v[0], 1e-5f)
        assertEquals(1f, v[1], 1e-5f)
        assertEquals(0f, v[2], 1e-5f)
    }
}
