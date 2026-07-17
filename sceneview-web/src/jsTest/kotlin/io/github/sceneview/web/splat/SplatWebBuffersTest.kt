package io.github.sceneview.web.splat

import io.github.sceneview.core.splat.SplatCloud
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pure Kotlin/JS pins for the [SplatWebBuffers] contracts consumed by the web `SplatNode`
 * (#2646 P2) — the jsTest mirror of the Android `SplatBuffersTest`: per-splat texel
 * layout, the 65535-instances/draw batch splitting, the painter's (back-to-front) sort
 * order, and the culling AABB.
 *
 * The GPU side (texture upload, instanced renderables, the actual pixels) needs a real
 * browser + the Filament WASM module — that is `splat-bundle.spec.ts` in the Playwright
 * suite; this suite locks the maths that decides *what* is uploaded and *in which order*.
 */
class SplatWebBuffersTest {

    /** A tiny cloud with distinct per-splat values so any index mix-up changes the output. */
    private fun cloud(count: Int) = SplatCloud(
        count = count,
        positions = FloatArray(3 * count) { i -> 100f * (i / 3) + (i % 3).toFloat() },
        scales = FloatArray(3 * count) { i -> 0.01f * (i / 3 + 1) * (i % 3 + 1) },
        rotations = FloatArray(4 * count) { i -> if (i % 4 == 3) 1f else 0f },
        colors = FloatArray(3 * count) { i -> (i % 255) / 255f },
        opacities = FloatArray(count) { i -> (i + 1f) / (count + 1f) }
    )

    // ── textureSize ─────────────────────────────────────────────────────────────────────

    @Test
    fun textureSizeIsSmallestSquareSideHoldingCount() {
        assertEquals(1, SplatWebBuffers.textureSize(0))
        assertEquals(1, SplatWebBuffers.textureSize(1))
        assertEquals(2, SplatWebBuffers.textureSize(2))
        assertEquals(2, SplatWebBuffers.textureSize(4))
        assertEquals(3, SplatWebBuffers.textureSize(5))
        assertEquals(3, SplatWebBuffers.textureSize(9))
        assertEquals(90, SplatWebBuffers.textureSize(8000))
    }

    @Test
    fun textureSizeRejectsNegativeCount() {
        assertFailsWith<IllegalArgumentException> { SplatWebBuffers.textureSize(-1) }
    }

    // ── batchRanges ─────────────────────────────────────────────────────────────────────

    @Test
    fun batchRangesSplitAtInstanceCap() {
        assertEquals(emptyList(), SplatWebBuffers.batchRanges(0))
        assertEquals(listOf(0 until 8000), SplatWebBuffers.batchRanges(8000))
        assertEquals(listOf(0 until 65535), SplatWebBuffers.batchRanges(65535))
        assertEquals(
            listOf(0 until 65535, 65535 until 65536),
            SplatWebBuffers.batchRanges(65536)
        )
        assertEquals(
            listOf(0 until 65535, 65535 until 131070, 131070 until 150000),
            SplatWebBuffers.batchRanges(150000)
        )
    }

    @Test
    fun visibleInBatchClampsToTheCut() {
        val batch = 100 until 200
        assertEquals(0, SplatWebBuffers.visibleInBatch(50, batch))
        assertEquals(0, SplatWebBuffers.visibleInBatch(100, batch))
        assertEquals(25, SplatWebBuffers.visibleInBatch(125, batch))
        assertEquals(100, SplatWebBuffers.visibleInBatch(200, batch))
        assertEquals(100, SplatWebBuffers.visibleInBatch(9999, batch))
    }

    // ── texel packing ───────────────────────────────────────────────────────────────────

    @Test
    fun packPositionScaleWritesDrawOrderTexels() {
        val c = cloud(3)
        val order = intArrayOf(2, 0, 1)
        val texSize = SplatWebBuffers.textureSize(3)
        val packed = SplatWebBuffers.packPositionScale(c, order, texSize)
        // Texel 0 = splat 2: positions [200, 201, 202], half-extent = 3 * max scales of splat 2.
        assertEquals(200f, packed.asDynamic()[0].unsafeCast<Float>())
        assertEquals(201f, packed.asDynamic()[1].unsafeCast<Float>())
        assertEquals(202f, packed.asDynamic()[2].unsafeCast<Float>())
        val expectedHalfExtent =
            SplatWebBuffers.HALF_EXTENT_SIGMA * maxOf(c.scales[6], c.scales[7], c.scales[8])
        assertEquals(expectedHalfExtent, packed.asDynamic()[3].unsafeCast<Float>())
        // Texel 1 = splat 0.
        assertEquals(0f, packed.asDynamic()[4].unsafeCast<Float>())
        // Tail texels stay zero (degenerate quads — see the layout contract).
        val lastTexel = (texSize * texSize - 1) * 4
        assertEquals(0f, packed.asDynamic()[lastTexel + 3].unsafeCast<Float>())
    }

    @Test
    fun packColorOpacityWritesDrawOrderTexels() {
        val c = cloud(3)
        val order = intArrayOf(1, 2, 0)
        val packed = SplatWebBuffers.packColorOpacity(c, order, SplatWebBuffers.textureSize(3))
        // Texel 0 = splat 1: colors [3/255, 4/255, 5/255], opacity 2/4.
        assertEquals(c.colors[3], packed.asDynamic()[0].unsafeCast<Float>())
        assertEquals(c.colors[4], packed.asDynamic()[1].unsafeCast<Float>())
        assertEquals(c.colors[5], packed.asDynamic()[2].unsafeCast<Float>())
        assertEquals(c.opacities[1], packed.asDynamic()[3].unsafeCast<Float>())
    }

    @Test
    fun packRejectsMismatchedOrderOrTooSmallTexture() {
        val c = cloud(3)
        assertFailsWith<IllegalArgumentException> {
            SplatWebBuffers.packPositionScale(c, intArrayOf(0, 1), 2)
        }
        assertFailsWith<IllegalArgumentException> {
            SplatWebBuffers.packColorOpacity(c, intArrayOf(0, 1, 2), 1)
        }
    }

    // ── painter's sort ──────────────────────────────────────────────────────────────────

    @Test
    fun sortBackToFrontOrdersFarthestFirst() {
        // Three splats on the z axis at z = 0, 10, 20; camera at z = 25.
        val positions = floatArrayOf(
            0f, 0f, 0f,
            0f, 0f, 10f,
            0f, 0f, 20f
        )
        val order = SplatWebBuffers.sortBackToFront(positions, 3, 0f, 0f, 25f)
        assertContentEquals(intArrayOf(0, 1, 2), order)
        // Camera on the other side flips the order.
        val flipped = SplatWebBuffers.sortBackToFront(positions, 3, 0f, 0f, -5f)
        assertContentEquals(intArrayOf(2, 1, 0), flipped)
    }

    @Test
    fun sortBackToFrontIsAPermutation() {
        val c = cloud(257) // > one texel row, odd size
        val order = SplatWebBuffers.sortBackToFront(c.positions, c.count, 3f, -2f, 7f)
        assertEquals(c.count, order.size)
        assertContentEquals(IntArray(c.count) { it }, order.copyOf().apply { sort() })
        // Distances are non-increasing along the returned order (back-to-front).
        fun d2(s: Int): Float {
            val dx = c.positions[s * 3] - 3f
            val dy = c.positions[s * 3 + 1] + 2f
            val dz = c.positions[s * 3 + 2] - 7f
            return dx * dx + dy * dy + dz * dz
        }
        for (i in 1 until order.size) {
            assertTrue(
                d2(order[i - 1]) >= d2(order[i]),
                "order[$i] is nearer than order[${i - 1}] — not back-to-front"
            )
        }
    }

    @Test
    fun sortRejectsShortPositionsArray() {
        assertFailsWith<IllegalArgumentException> {
            SplatWebBuffers.sortBackToFront(FloatArray(5), 2, 0f, 0f, 0f)
        }
    }

    // ── bounding box ────────────────────────────────────────────────────────────────────

    @Test
    fun boundingBoxGrowsCentresByHalfExtent() {
        val c = SplatCloud(
            count = 2,
            positions = floatArrayOf(-1f, 0f, 0f, 1f, 0f, 0f),
            scales = floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f),
            rotations = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            colors = FloatArray(6),
            opacities = floatArrayOf(1f, 1f)
        )
        val box = SplatWebBuffers.boundingBox(c)
        val r = SplatWebBuffers.HALF_EXTENT_SIGMA * 0.1f
        val eps = 1e-5f
        assertEquals(0f, box[0], eps) // centre x
        assertEquals(1f + r, box[3], eps, "half-extent x must include the billboard radius")
        assertEquals(r, box[4], eps)
        assertEquals(r, box[5], eps)
    }

    @Test
    fun boundingBoxOfEmptyCloudIsUnitFallback() {
        val empty = SplatCloud(0, FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
        assertContentEquals(floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f), SplatWebBuffers.boundingBox(empty))
    }
}
