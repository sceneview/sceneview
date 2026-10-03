package io.github.sceneview.web.splat

import io.github.sceneview.core.splat.SplatCloud
import kotlin.math.pow
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

    @Test
    fun rotationScaleArePackedInCloudOrder() {
        val c = cloud(3)
        // Nontrivial normalized rotation, signed components, and three distinct scales.
        c.rotations[0] = -0.5f; c.rotations[1] = 0.5f
        c.rotations[2] = -0.5f; c.rotations[3] = 0.5f
        val (rotation, scale) = SplatWebBuffers.packRotationScale(c, 2)
        for (i in 0 until c.count) {
            val decoded = SplatWebBuffers.unpackRotationScale(rotation, scale, i)
            // Written as they are — the GPU upload does the half-float rounding.
            for (j in 0..3) assertEquals(c.rotations[i * 4 + j], decoded[j])
            for (j in 0..2) assertEquals(c.scales[i * 3 + j], decoded[4 + j])
        }
        assertContentEquals(FloatArray(7), SplatWebBuffers.unpackRotationScale(rotation, scale, 3))
        assertEquals(0f, scale.asDynamic()[3].unsafeCast<Float>())
    }

    @Test
    fun supportedCountIsOneToTwoPow24WithTheLimitInTheMessage() {
        SplatWebBuffers.requireSupportedCount(1)
        SplatWebBuffers.requireSupportedCount(16777216)
        assertFailsWith<IllegalArgumentException> { SplatWebBuffers.requireSupportedCount(0) }
        val tooMany = assertFailsWith<IllegalArgumentException> {
            SplatWebBuffers.requireSupportedCount(16777217)
        }
        val message = tooMany.message.orEmpty()
        assertTrue("16777216" in message && "16777217" in message, message)
    }

    @Test
    fun orderTextureFollowsSortWithoutRepackingAttributes() {
        val c = cloud(3)
        val cloudOrder = intArrayOf(0, 1, 2)
        // The four attribute buffers, uploaded once in cloud order.
        fun attributes(): List<FloatArray> {
            val (rotation, scale) = SplatWebBuffers.packRotationScale(c, 2)
            return listOf(
                SplatWebBuffers.packPositionScale(c, cloudOrder, 2),
                SplatWebBuffers.packColorOpacity(c, cloudOrder, 2),
                rotation,
                scale,
            ).map { buffer -> FloatArray(buffer.length) { buffer.asDynamic()[it].unsafeCast<Float>() } }
        }
        val before = attributes()
        val order = SplatWebBuffers.sortBackToFront(c.positions, c.count, 0f, 0f, 0f)
        val packed = SplatWebBuffers.packOrder(order, 2)
        assertContentEquals(intArrayOf(2, 1, 0), order)
        for (i in order.indices) {
            val decoded = packed.asDynamic()[i * 4].unsafeCast<Int>() +
                256 * packed.asDynamic()[i * 4 + 1].unsafeCast<Int>() +
                65536 * packed.asDynamic()[i * 4 + 2].unsafeCast<Int>()
            assertEquals(order[i], decoded)
        }
        // RGBA8: four bytes per slot, padding slot left at zero.
        assertEquals(2 * 2 * 4, packed.length)
        assertEquals(0, packed.asDynamic()[12].unsafeCast<Int>())
        // "Without repacking": the sort reversed the draw order, and the attribute buffers
        // built after it are the ones built before it — only the order texture moved.
        val after = attributes()
        for (i in before.indices) assertContentEquals(before[i], after[i])
    }

    @Test
    fun orderDigitsAreExactAcrossRowsAndBatchBoundaries() {
        val count = 233808 // Raccoon scan count; exercises multi-batch indexing.
        val packed = SplatWebBuffers.packOrder(IntArray(count) { count - 1 - it }, 484)
        for (slot in listOf(0, 1, 255, 256, 65534, 65535, count - 1)) {
            // Read back as the unsigned bytes the GPU sees.
            val digits = (0..2).map { packed.asDynamic()[slot * 4 + it].unsafeCast<Int>() }
            assertTrue(digits.all { it in 0..255 }, "digits out of byte range: $digits")
            assertEquals(count - 1 - slot, digits[0] + 256 * digits[1] + 65536 * digits[2])
        }
        assertEquals(484 * 484 * 4, packed.length)
        assertFailsWith<IllegalArgumentException> { SplatWebBuffers.packOrder(intArrayOf(1), 1) }
    }

    // What the view applies after the shader: ToneMapper.Filmic (Filament's FilmicToneMapper,
    // the Narkowicz 2015 ACES fit) then the sRGB OETF (IEC 61966-2-1).
    private fun filmic(x: Double): Double =
        (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14)

    private fun linearToSrgb(value: Double): Double =
        if (value < 0.0031308) value * 12.92 else 1.055 * value.pow(1.0 / 2.4) - 0.055

    @Test
    fun colourRoundTripThroughFilmicAndSrgbIsTheIdentity() {
        // Every 8-bit display level: shader side (EOTF, inverse Filmic), then view side
        // (Filmic, OETF), must give the level back. Both inverses are analytic and this is
        // Double arithmetic, so the only error is rounding: 1e-9 is a millionth of the
        // 1/255 step, and far below the two levels the browser spec allows for the view's
        // colour-grading LUT.
        for (level in 0..255) {
            val srgb = level / 255.0
            val shader = SplatWebMath.inverseFilmic(SplatWebMath.srgbToLinear(srgb))
            assertEquals(srgb, linearToSrgb(filmic(shader)), 1e-9, "level $level")
        }
    }

    @Test
    fun srgbEotfAndInverseFilmicMatchAndroidConstants() {
        val srgb = doubleArrayOf(0.0, 0.5, 1.0)
        val linear = doubleArrayOf(0.0, 0.21404114048223255, 1.0)
        val inverse = doubleArrayOf(0.0, 0.14927107629807476, 7.241657386774)
        for (i in srgb.indices) {
            assertEquals(linear[i], SplatWebMath.srgbToLinear(srgb[i]), 1e-12)
            assertEquals(inverse[i], SplatWebMath.inverseFilmic(linear[i]), 1e-9)
        }
        assertEquals(0.3563298719772007, SplatWebMath.inverseFilmic(0.5), 1e-12)
        assertEquals(0.04044 / 12.92, SplatWebMath.srgbToLinear(0.04044), 1e-12)
    }

    @Test
    fun projectedCovarianceMatchesAxisAlignedAnd45DegreeEllipse() {
        // fx = fy = 100 px, depth = 10: projected σ are 20 px and 10 px.
        val scale = floatArrayOf(2f, 1f, 0.5f)
        val center = floatArrayOf(0f, 0f, -10f)
        val aligned = SplatWebMath.covariance2D(floatArrayOf(0f, 0f, 0f, 1f), scale, center, 1.0, 1.0, 200.0, 200.0)
        assertEquals(400.3, aligned[0], 1e-6)
        assertEquals(0.0, aligned[1], 1e-6)
        assertEquals(100.3, aligned[2], 1e-6)
        // R(45°) diag(400,100) Rᵀ = [[250,150],[150,250]], plus low-pass.
        val rotated = SplatWebMath.covariance2D(floatArrayOf(0f, 0f, 0.3826834324f, 0.9238795325f), scale, center, 1.0, 1.0, 200.0, 200.0)
        assertEquals(250.3, rotated[0], 0.0001)
        assertEquals(150.0, rotated[1], 0.0001)
        assertEquals(250.3, rotated[2], 0.0001)
        // Off-axis x/depth=2 clamps to 1.3: Jz=13, adds 13² * 0.5² = 42.25.
        val clamped = SplatWebMath.covariance2D(floatArrayOf(0f, 0f, 0f, 1f), scale, floatArrayOf(20f, 0f, -10f), 1.0, 1.0, 200.0, 200.0)
        assertEquals(442.55, clamped[0], 1e-6)
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
