package io.github.sceneview.splat

import io.github.sceneview.core.splat.SplatCloud
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM pins for the [SplatBuffers] contracts consumed by `SplatNode` (#2646): the per-splat
 * texel layout (splat → texel round-trip), the 65535-instances/draw batch splitting, the
 * painter's (back-to-front) sort order, and the culling AABB.
 *
 * The GPU side (texture upload, instanced renderables, the actual pixels) needs a device — it is
 * covered by the #2646 demo-integration follow-up; this suite locks the maths that decides *what*
 * is uploaded and *in which order*.
 */
class SplatBuffersTest {

    /** A tiny cloud with distinct per-splat values so any index mix-up changes the output. */
    private fun cloud(count: Int) = SplatCloud(
        count = count,
        positions = FloatArray(3 * count) { i -> 100f * (i / 3) + (i % 3).toFloat() },
        scales = FloatArray(3 * count) { i -> 0.01f * (i / 3 + 1) * (i % 3 + 1) },
        rotations = FloatArray(4 * count) { i -> if (i % 4 == 3) 1f else 0f },
        colors = FloatArray(3 * count) { i -> (i % 255) / 255f },
        opacities = FloatArray(count) { i -> (i + 1f) / (count + 1f) }
    )

    // ── textureSize ───────────────────────────────────────────────────────────────────────────

    @Test
    fun textureSizeIsSmallestSquareSideHoldingCount() {
        assertEquals(1, SplatBuffers.textureSize(0))
        assertEquals(1, SplatBuffers.textureSize(1))
        assertEquals(2, SplatBuffers.textureSize(2))
        assertEquals(2, SplatBuffers.textureSize(4))
        assertEquals(3, SplatBuffers.textureSize(5))
        assertEquals(3, SplatBuffers.textureSize(9))
        assertEquals(4, SplatBuffers.textureSize(10))
        assertEquals(71, SplatBuffers.textureSize(5000))
        assertEquals(256, SplatBuffers.textureSize(65535))
        assertEquals(256, SplatBuffers.textureSize(65536))
        assertEquals(257, SplatBuffers.textureSize(65537))
    }

    @Test
    fun textureSizeAlwaysHoldsCount() {
        // Sweep across perfect-square boundaries where float sqrt rounding could under-size.
        for (count in 0..20_000) {
            val w = SplatBuffers.textureSize(count)
            assertTrue("textureSize($count) = $w too small", w * w >= count)
            if (count > 1) assertTrue("textureSize($count) = $w not minimal", (w - 1) * (w - 1) < count)
        }
    }

    // ── batchRanges ───────────────────────────────────────────────────────────────────────────

    @Test
    fun batchRangesSplitsAtMaxInstancesPerDraw() {
        assertEquals(emptyList<IntRange>(), SplatBuffers.batchRanges(0))
        assertEquals(listOf(0..0), SplatBuffers.batchRanges(1))
        assertEquals(listOf(0..65534), SplatBuffers.batchRanges(65535))
        assertEquals(listOf(0..65534, 65535..65535), SplatBuffers.batchRanges(65536))
        assertEquals(
            listOf(0..65534, 65535..131069, 131070..196604, 196605..199999),
            SplatBuffers.batchRanges(200_000)
        )
    }

    @Test
    fun batchRangesAreContiguousCompleteAndCapped() {
        for (count in intArrayOf(1, 42, 65534, 65535, 65536, 131070, 131071, 500_000)) {
            val ranges = SplatBuffers.batchRanges(count)
            assertEquals("first batch starts at 0", 0, ranges.first().first)
            assertEquals("last batch ends at count-1", count - 1, ranges.last().last)
            ranges.forEach {
                assertTrue("batch $it exceeds cap", it.count() <= SplatBuffers.MAX_INSTANCES_PER_BATCH)
            }
            ranges.zipWithNext { a, b ->
                assertEquals("batches not contiguous: $a -> $b", a.last + 1, b.first)
            }
            assertEquals("batches cover every splat", count, ranges.sumOf { it.count() })
        }
    }

    // ── visibleInBatch (splatCount LOD cut) ───────────────────────────────────────────────────

    @Test
    fun visibleInBatchCutsTheGlobalOrderPerBatch() {
        val first = 0..65534
        val second = 65535..99999
        // Cut inside the first batch: second batch fully hidden.
        assertEquals(1000, SplatBuffers.visibleInBatch(1000, first))
        assertEquals(0, SplatBuffers.visibleInBatch(1000, second))
        // Cut inside the second batch: first batch fully visible.
        assertEquals(65535, SplatBuffers.visibleInBatch(70_000, first))
        assertEquals(4465, SplatBuffers.visibleInBatch(70_000, second))
        // Extremes.
        assertEquals(0, SplatBuffers.visibleInBatch(0, first))
        assertEquals(65535, SplatBuffers.visibleInBatch(100_000, first))
        assertEquals(34465, SplatBuffers.visibleInBatch(100_000, second))
    }

    // ── texel packing round-trip ──────────────────────────────────────────────────────────────

    @Test
    fun packPositionScaleRoundTripsSplatToTexel() {
        val cloud = cloud(5)
        val order = IntArray(5) { it }
        val textureSize = SplatBuffers.textureSize(5) // 3x3 = 9 texels
        val buffer = SplatBuffers.packPositionScale(cloud, order, textureSize)

        assertEquals(textureSize * textureSize * 4, buffer.remaining())
        for (i in 0 until 5) {
            assertEquals("texel $i x", cloud.positions[i * 3], buffer.get(i * 4), 0f)
            assertEquals("texel $i y", cloud.positions[i * 3 + 1], buffer.get(i * 4 + 1), 0f)
            assertEquals("texel $i z", cloud.positions[i * 3 + 2], buffer.get(i * 4 + 2), 0f)
            val expectedHalfExtent = SplatBuffers.HALF_EXTENT_SIGMA * maxOf(
                cloud.scales[i * 3], cloud.scales[i * 3 + 1], cloud.scales[i * 3 + 2]
            )
            assertEquals("texel $i half-extent", expectedHalfExtent, buffer.get(i * 4 + 3), 1e-7f)
        }
    }

    @Test
    fun packFollowsTheDrawOrderPermutation() {
        val cloud = cloud(4)
        val order = intArrayOf(2, 0, 3, 1) // texel i holds splat order[i]
        val textureSize = SplatBuffers.textureSize(4)
        val positionScale = SplatBuffers.packPositionScale(cloud, order, textureSize)
        val colorOpacity = SplatBuffers.packColorOpacity(cloud, order, textureSize)

        order.forEachIndexed { texel, splat ->
            assertEquals(cloud.positions[splat * 3], positionScale.get(texel * 4), 0f)
            assertEquals(cloud.positions[splat * 3 + 2], positionScale.get(texel * 4 + 2), 0f)
            assertEquals(cloud.colors[splat * 3], colorOpacity.get(texel * 4), 0f)
            assertEquals(cloud.colors[splat * 3 + 1], colorOpacity.get(texel * 4 + 1), 0f)
            assertEquals(cloud.opacities[splat], colorOpacity.get(texel * 4 + 3), 0f)
        }
    }

    @Test
    fun packLeavesUnusedTailTexelsZero() {
        val cloud = cloud(5)
        val textureSize = SplatBuffers.textureSize(5) // 9 texels, 4 unused
        val order = IntArray(5) { it }
        val positionScale = SplatBuffers.packPositionScale(cloud, order, textureSize)
        val colorOpacity = SplatBuffers.packColorOpacity(cloud, order, textureSize)
        for (i in 5 until textureSize * textureSize) {
            for (c in 0 until 4) {
                // A zero half-extent collapses the instanced quad — garbage texels never paint.
                assertEquals("positionScale tail texel $i.$c", 0f, positionScale.get(i * 4 + c), 0f)
                assertEquals("colorOpacity tail texel $i.$c", 0f, colorOpacity.get(i * 4 + c), 0f)
            }
        }
    }

    @Test
    fun packColorOpacityRoundTripsColorAndOpacity() {
        val cloud = cloud(3)
        val buffer = SplatBuffers.packColorOpacity(cloud, IntArray(3) { it }, SplatBuffers.textureSize(3))
        for (i in 0 until 3) {
            assertEquals(cloud.colors[i * 3], buffer.get(i * 4), 0f)
            assertEquals(cloud.colors[i * 3 + 1], buffer.get(i * 4 + 1), 0f)
            assertEquals(cloud.colors[i * 3 + 2], buffer.get(i * 4 + 2), 0f)
            assertEquals(cloud.opacities[i], buffer.get(i * 4 + 3), 0f)
        }
    }

    @Test
    fun packValidatesOrderAndTextureSize() {
        val cloud = cloud(4)
        assertThrows(IllegalArgumentException::class.java) {
            SplatBuffers.packPositionScale(cloud, IntArray(3), 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SplatBuffers.packPositionScale(cloud, IntArray(4), 1) // 1 texel < 4 splats
        }
    }

    // ── painter's sort ────────────────────────────────────────────────────────────────────────

    @Test
    fun sortBackToFrontOrdersFarthestFirst() {
        // Splats along -z; camera at the origin looking anywhere: distance decides.
        val positions = floatArrayOf(
            0f, 0f, -1f, // splat 0 — nearest
            0f, 0f, -5f, // splat 1 — farthest
            0f, 0f, -3f  // splat 2 — middle
        )
        assertArrayEquals(
            intArrayOf(1, 2, 0),
            SplatBuffers.sortBackToFront(positions, 3, 0f, 0f, 0f)
        )
    }

    @Test
    fun sortIsViewDependent() {
        // Same cloud, camera moved beyond the far end: the order flips.
        val positions = floatArrayOf(
            0f, 0f, -1f,
            0f, 0f, -5f,
            0f, 0f, -3f
        )
        assertArrayEquals(
            intArrayOf(0, 2, 1),
            SplatBuffers.sortBackToFront(positions, 3, 0f, 0f, -10f)
        )
    }

    @Test
    fun sortReturnsAPermutationOnLargeClouds() {
        val count = 10_000
        val cloud = cloud(count)
        val order = SplatBuffers.sortBackToFront(cloud.positions, count, 5f, -3f, 2f)
        assertEquals(count, order.size)
        assertEquals("order must be a permutation", count, order.toSortedSet().size)
        // Verify global monotonicity: each texel is at least as close as the previous one.
        fun d2(s: Int): Float {
            val dx = cloud.positions[s * 3] - 5f
            val dy = cloud.positions[s * 3 + 1] + 3f
            val dz = cloud.positions[s * 3 + 2] - 2f
            return dx * dx + dy * dy + dz * dz
        }
        order.toList().zipWithNext { far, near ->
            assertTrue("not back-to-front at $far -> $near", d2(far) >= d2(near))
        }
    }

    // ── bounding box ──────────────────────────────────────────────────────────────────────────

    @Test
    fun boundingBoxGrowsCentersByBillboardHalfExtent() {
        val cloud = SplatCloud(
            count = 2,
            positions = floatArrayOf(-1f, 0f, 0f, 1f, 0f, 0f),
            scales = FloatArray(6) { 0.1f }, // half-extent = 3σ = 0.3
            rotations = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            colors = FloatArray(6),
            opacities = floatArrayOf(1f, 1f)
        )
        val box = SplatBuffers.boundingBox(cloud)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), box.copyOfRange(0, 3), 1e-6f)
        assertEquals(1.3f, box[3], 1e-6f) // x: centers ±1 grown by 0.3
        assertEquals(0.3f, box[4], 1e-6f) // y: grown only
        assertEquals(0.3f, box[5], 1e-6f)
    }

    // ── SplatCloud stand-in validation (interface contract, #2646 P1a) ───────────────────────

    @Test
    fun splatCloudValidatesArraySizes() {
        assertThrows(IllegalArgumentException::class.java) {
            SplatCloud(2, FloatArray(5), FloatArray(6), FloatArray(8), FloatArray(6), FloatArray(2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SplatCloud(2, FloatArray(6), FloatArray(6), FloatArray(7), FloatArray(6), FloatArray(2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SplatCloud(2, FloatArray(6), FloatArray(6), FloatArray(8), FloatArray(6), FloatArray(3))
        }
        // Well-formed: does not throw.
        SplatCloud(2, FloatArray(6), FloatArray(6), FloatArray(8), FloatArray(6), FloatArray(2))
    }
}
