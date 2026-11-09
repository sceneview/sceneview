package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthBackProjection
import io.github.sceneview.demo.ui.viewer.ViewerBackdrop
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** The scan's surfels as flat splats ([SurfelSplats]): their axes, sizes, opacity, colour and cap. */
class SurfelSplatsTest {
    @Test
    fun `a splat's thin axis turns onto its surfel's normal, whichever way it faces`() {
        val normals = listOf(
            floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f), floatArrayOf(0.3f, -0.8f, 0.52f),
            floatArrayOf(-0.6f, 0.1f, -0.79f), floatArrayOf(2f, 2f, 0f), // not unit: normalised
        )
        val q = FloatArray(4)
        for (n in normals) {
            SurfelSplats.rotationToNormal(n[0], n[1], n[2], q, 0)
            assertEquals("unit quaternion for ${n.toList()}", 1f, sqrt(q.sumOf { (it * it).toDouble() }).toFloat(), 1e-5f)
            val z = rotate(q, 0f, 0f, 1f)
            val len = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
            assertArrayEquals("+Z onto ${n.toList()}", floatArrayOf(n[0] / len, n[1] / len, n[2] / len), z, 1e-5f)
        }
        SurfelSplats.rotationToNormal(0f, 0f, 0f, q, 0)
        assertArrayEquals("a zero normal leaves the splat as it is", floatArrayOf(0f, 0f, 0f, 1f), q, 0f)
    }

    @Test
    fun `a splat is as wide as its surfel on both tangent axes and thin along its normal`() {
        val cloud = DenseCloud(
            positions = floatArrayOf(1f, 2f, 3f, 0f, 0f, 0f, 0f, 1f, 0f),
            colors = intArrayOf(0xFF808080.toInt(), 0xFF808080.toInt(), 0xFF808080.toInt()),
            normals = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, -1f),
            levels = byteArrayOf(0, 2, 9), // the last beyond the coarsest level: widened as that level
        )
        val splats = SurfelSplats.of(cloud)

        assertEquals(3, splats.count)
        assertArrayEquals(cloud.positions, splats.positions, 0f)
        val level0 = DenseFusion.VOXEL_M * SurfelSplats.SIGMA_VOXELS
        val sigmas = floatArrayOf(level0, level0 * 4, level0 * (1 shl DenseFusion.LEVEL_LIMIT))
        for (i in 0 until 3) {
            val sigma = sigmas[i]
            assertEquals("tangent x of $i", sigma, splats.scales[i * 3], 1e-7f)
            assertEquals("tangent y of $i", sigma, splats.scales[i * 3 + 1], 1e-7f)
            val thin = maxOf(sigma * SurfelSplats.THICKNESS_SHARE, SurfelSplats.MIN_THICKNESS_M)
            assertEquals("normal axis of $i", thin, splats.scales[i * 3 + 2], 1e-7f)
            assertTrue("thin along the normal", splats.scales[i * 3 + 2] < sigma / 5)
            // The splat's flat disc spans the surfel's plane: its thin axis is the normal.
            val q = splats.rotations.copyOfRange(i * 4, i * 4 + 4)
            val n = cloud.normals!!.copyOfRange(i * 3, i * 3 + 3)
            assertArrayEquals(n, rotate(q, 0f, 0f, 1f), 1e-5f)
        }
    }

    @Test
    fun `a cloud without normals gets round splats`() {
        val cloud = DenseCloud(floatArrayOf(0f, 0f, 0f), intArrayOf(0xFFFFFFFF.toInt()))
        val splats = SurfelSplats.of(cloud)
        val sigma = DenseFusion.VOXEL_M * SurfelSplats.SIGMA_VOXELS
        assertArrayEquals(floatArrayOf(sigma, sigma, sigma), splats.scales, 1e-7f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), splats.rotations, 0f)
        assertEquals(SurfelSplats.MAX_OPACITY, splats.opacities[0], 0f)
    }

    @Test
    fun `opacity rises with confidence, from faint at the floor to never quite opaque`() {
        assertEquals(SurfelSplats.MIN_OPACITY, SurfelSplats.opacity(0), 0f)
        assertEquals(SurfelSplats.MIN_OPACITY, SurfelSplats.opacity(SurfelSplats.CONFIDENCE_FLOOR), 0f)
        assertEquals(SurfelSplats.MAX_OPACITY, SurfelSplats.opacity(255), 1e-6f)
        assertEquals("a cloud without confidence", SurfelSplats.MAX_OPACITY, SurfelSplats.opacity(null), 0f)
        var last = 0f
        for (c in 0..255) {
            val o = SurfelSplats.opacity(c)
            assertTrue("monotone at $c", o >= last)
            assertTrue("within bounds at $c", o in SurfelSplats.MIN_OPACITY..SurfelSplats.MAX_OPACITY)
            last = o
        }
        assertTrue(SurfelSplats.MAX_OPACITY < 1f)
        // Read from the cloud's bytes as unsigned: 255 is the byte -1.
        val cloud = DenseCloud(
            floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f),
            intArrayOf(0xFF102030.toInt(), 0xFF102030.toInt()),
            confidences = byteArrayOf(SurfelSplats.CONFIDENCE_FLOOR.toByte(), 255.toByte()),
        )
        val splats = SurfelSplats.of(cloud)
        assertEquals(SurfelSplats.MIN_OPACITY, splats.opacities[0], 1e-6f)
        assertEquals(SurfelSplats.MAX_OPACITY, splats.opacities[1], 1e-6f)
    }

    @Test
    fun `a surfel without colour is left out`() {
        val cloud = DenseCloud(
            floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f, 2f, 2f, 2f),
            intArrayOf(0xFF336699.toInt(), 0, 0xFF996633.toInt()),
        )
        val splats = SurfelSplats.of(cloud)
        assertEquals(2, splats.count)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 2f, 2f, 2f), splats.positions, 0f)
        assertEquals(0, SurfelSplats.of(DenseCloud(floatArrayOf(0f, 0f, 0f), intArrayOf(0))).count)
        assertEquals(0, SurfelSplats.of(DenseCloud.Empty).count)
    }

    @Test
    fun `over the cap, an even share is kept and each splat widens to cover for the rest`() {
        val count = 10_007
        val positions = FloatArray(count * 3) { if (it % 3 == 0) (it / 3).toFloat() else 0f }
        val colors = IntArray(count) { if (it % 11 == 5) 0 else 0xFF808080.toInt() }
        val coloured = colors.count { it != 0 }
        val cloud = DenseCloud(positions, colors, normals = FloatArray(count * 3) { if (it % 3 == 1) 1f else 0f })

        for (cap in listOf(1, 999, 4_000, coloured - 1, coloured, coloured + 50)) {
            val splats = SurfelSplats.of(cloud, maxSplats = cap)
            val kept = minOf(cap, coloured)
            assertEquals("cap $cap", kept, splats.count)
            val widen = sqrt(coloured.toFloat() / kept)
            val sigma = DenseFusion.VOXEL_M * SurfelSplats.SIGMA_VOXELS * widen
            assertEquals("cap $cap widens by sqrt($coloured / $kept)", sigma, splats.scales[0], 1e-6f)
            // Kept evenly: the gaps between kept surfels never differ by more than one.
            val xs = (0 until kept).map { splats.positions[it * 3] }
            assertTrue("in order", xs.zipWithNext().all { (a, b) -> b > a })
            if (kept > 2) {
                val stride = count.toFloat() / kept
                val gaps = xs.zipWithNext { a, b -> b - a }
                assertTrue("cap $cap gaps ${gaps.min()}..${gaps.max()} for a stride of $stride", gaps.max() <= stride * 1.25f + 2f)
            }
        }
        assertEquals(0, SurfelSplats.of(cloud, maxSplats = 0).count)
    }

    @Test
    fun `the cap is exact, the last splat is a kept surfel, never an empty slot`() {
        // Sizes where a floating-point share loses the last one (22 for 15, 262 672 for 250 000).
        for ((coloured, cap) in listOf(22 to 15, 23 to 13, 39 to 31, 262_672 to SurfelSplats.MAX_SPLATS)) {
            val positions = FloatArray(coloured * 3) { if (it % 3 == 0) (it / 3 + 1).toFloat() else 0f }
            val cloud = DenseCloud(positions, IntArray(coloured) { 0xFF808080.toInt() })
            val splats = SurfelSplats.of(cloud, maxSplats = cap)
            val last = cap - 1
            assertEquals("$coloured for $cap", cap, splats.count)
            assertTrue("$coloured for $cap: last splat at ${splats.positions[last * 3]}", splats.positions[last * 3] > 0f)
            val q = (0 until 4).sumOf { (splats.rotations[last * 4 + it] * splats.rotations[last * 4 + it]).toDouble() }
            assertEquals("$coloured for $cap: unit rotation", 1.0, q, 1e-5)
        }
    }

    @Test
    fun `under a linear tone mapper, the splat's colour is the surfel's once the material undoes PBR Neutral`() {
        val out = FloatArray(3)
        SurfelSplats.colour(0xFF8040C0.toInt(), linearView = false, out)
        assertArrayEquals(floatArrayOf(0x80 / 255f, 0x40 / 255f, 0xC0 / 255f), out, 1e-6f)

        for (argb in intArrayOf(0xFF000000.toInt(), 0xFF8040C0.toInt(), 0xFFC8B496.toInt(), 0xFF2A5F3B.toInt())) {
            SurfelSplats.colour(argb, linearView = true, out)
            // What the screen shows: the material decodes sRGB, the default view would apply PBR
            // Neutral (which the material inverts), the result view leaves it linear. So the screen
            // shows PBRNeutral(decoded) after the material's inverse, i.e. decoded(out) here must
            // equal PBRNeutral(decoded(surfel)).
            val surfel = floatArrayOf((argb shr 16 and 0xFF) / 255f, (argb shr 8 and 0xFF) / 255f, (argb and 0xFF) / 255f)
            val expected = ViewerBackdrop.pbrNeutral(FloatArray(3) { SurfelSplats.srgbToLinear(surfel[it]) })
            for (c in 0 until 3) {
                assertEquals("channel $c of ${Integer.toHexString(argb)}", expected[c], SurfelSplats.srgbToLinear(out[c]), 2e-4f)
            }
        }
        for (v in listOf(0f, 0.002f, 0.04f, 0.2f, 0.5f, 1f)) {
            assertEquals(v, SurfelSplats.srgbToLinear(SurfelSplats.linearToSrgb(v)), 1e-5f)
        }
    }

    @Test
    fun `the synthetic room's surfels become a whole room of splats, quickly`() {
        val fusion = DenseFusion()
        TexturedSyntheticRoom.frames().forEach { fusion.add(DepthBackProjection.project(it)) }
        val cloud = fusion.cloud(minViews = DenseFusion.MIN_VIEWS)
        val started = System.nanoTime()
        val splats = SurfelSplats.of(cloud)
        val ms = (System.nanoTime() - started) / 1_000_000
        println("synthetic room: ${cloud.count} surfels -> ${splats.count} splats in $ms ms")

        assertTrue("a room's worth of splats: ${splats.count}", splats.count > 1_000)
        // Every coloured surfel, up to the cap (#4896).
        assertEquals(minOf((0 until cloud.count).count { cloud.colors[it] != 0 }, SurfelSplats.MAX_SPLATS), splats.count)
        for (i in 0 until splats.count) {
            val q = splats.rotations.copyOfRange(i * 4, i * 4 + 4)
            assertEquals(1f, sqrt(q.sumOf { (it * it).toDouble() }).toFloat(), 1e-4f)
            assertTrue(splats.scales[i * 3 + 2] < splats.scales[i * 3])
            assertTrue(splats.opacities[i] in SurfelSplats.MIN_OPACITY..SurfelSplats.MAX_OPACITY)
            for (c in 0 until 3) assertTrue(splats.colors[i * 3 + c] in 0f..1f)
        }
    }

    /** [q] (x, y, z, w) applied to (x, y, z). */
    private fun rotate(q: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val (qx, qy, qz, qw) = q
        // t = 2 q × v; v' = v + w t + q × t
        val tx = 2 * (qy * z - qz * y)
        val ty = 2 * (qz * x - qx * z)
        val tz = 2 * (qx * y - qy * x)
        return floatArrayOf(
            x + qw * tx + (qy * tz - qz * ty),
            y + qw * ty + (qz * tx - qx * tz),
            z + qw * tz + (qx * ty - qy * tx),
        ).also { v -> for (i in v.indices) if (abs(v[i]) < 1e-7f) v[i] = 0f }
    }
}
