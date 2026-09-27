package io.github.sceneview.splat

import io.github.sceneview.core.splat.SplatCloud
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure CPU-side math for [io.github.sceneview.node.SplatNode]: per-splat data-texture packing,
 * draw batching, and the painter's (back-to-front) sort.
 *
 * Everything here is plain JVM Kotlin (no Filament / Android calls) so the texel layout,
 * batch-splitting, and sort-order contracts are unit-testable without a device — the GPU
 * upload itself lives in `SplatNode`.
 *
 * ### Texel layout contract (mirrors `splat.mat`)
 *
 * Per-splat attributes live in three square `texWidth x texWidth` data textures, uploaded
 * **once**, in the [SplatCloud]'s own order: splat `s` is texel `(s % texWidth, s / texWidth)`.
 * A fourth texture, `splatOrder`, maps each draw slot to a splat index; it carries the
 * painter's sort and is the only one re-uploaded when the camera moves (4 bytes per splat).
 * The instance `i` of a batch with base offset `o` draws slot `o + i`.
 *
 * | Texture              | Format    | Content |
 * |----------------------|-----------|---------|
 * | `splatOrder`         | `R32UI`   | splat index drawn at this slot (back-to-front) |
 * | `splatPositionScale` | `RGBA16F` | centre `xyz` (model space), `a` = [HALF_EXTENT_SIGMA]·max(scaleX, scaleY, scaleZ) |
 * | `splatColorOpacity`  | `RGBA16F` | SH0 colour `rgb`, opacity `0..1` in `a` (straight — the fragment premultiplies) |
 * | `splatRotationScale` | `RGBA32UI`| two IEEE half floats per channel, low half first: `(qx,qy) (qz,qw) (sx,sy) (sz,0)` |
 *
 * The three data textures accept any `order` (texel `i` holds splat `order[i]`) — pass the
 * identity to get the static layout above.
 */
object SplatBuffers {

    /**
     * Filament hardware-instancing cap per renderable ([Phase-0 spike, #2646]): a single
     * `RenderableManager.Builder.instances(n)` draw supports at most 65535 instances. Clouds
     * above this are split into multiple renderables ("batches") sharing the same two data
     * textures, each shifted by its `instanceOffset` material parameter.
     */
    const val MAX_INSTANCES_PER_BATCH = 65535

    /**
     * Furthest reach of a splat quad in gaussian standard deviations: `splat.mat` never draws
     * past 3σ along either ellipse axis (beyond it a gaussian contributes < 1.1%), and shrinks
     * the quad further for faint splats. The culling box uses this radius on the largest axis,
     * so it always contains every quad. Keep in sync with the `min(3.0, …)` in `splat.mat`.
     */
    const val HALF_EXTENT_SIGMA = 3f

    /** Floats per texel in both data textures (RGBA). */
    private const val FLOATS_PER_TEXEL = 4

    /**
     * Side of the square data texture needed to hold [count] texels:
     * the smallest `w` with `w * w >= count` (minimum 1 — Filament rejects 0-sized textures).
     *
     * Square because `splat.mat` derives both texel coordinates from the single `texWidth`
     * parameter.
     */
    fun textureSize(count: Int): Int {
        require(count >= 0) { "count must be >= 0, was $count" }
        if (count <= 1) return 1
        var w = ceil(sqrt(count.toDouble())).toInt()
        // Guard the double→int boundary (e.g. counts just above a perfect square).
        while (w * w < count) w++
        return w
    }

    /**
     * Splits [count] splats into contiguous draw batches of at most [MAX_INSTANCES_PER_BATCH].
     *
     * Batch `b` renders texels `[first..last]`; its material gets `instanceOffset = first` so
     * `getInstanceIndex() + instanceOffset` indexes the shared textures globally. An empty
     * cloud yields no batches.
     */
    fun batchRanges(count: Int): List<IntRange> {
        require(count >= 0) { "count must be >= 0, was $count" }
        if (count == 0) return emptyList()
        return (0 until count step MAX_INSTANCES_PER_BATCH).map { start ->
            start until min(start + MAX_INSTANCES_PER_BATCH, count)
        }
    }

    /**
     * How many of [batch]'s instances are rendered when only the first [splatCount] texels of
     * the global draw order are visible: `0` (batch entirely past the cut — hide it) up to the
     * full batch size. Drives `SplatNode.splatCount`'s per-batch rebuild/hide decision.
     */
    fun visibleInBatch(splatCount: Int, batch: IntRange): Int =
        (splatCount - batch.first).coerceIn(0, batch.count())

    /** Allocates a direct, native-ordered float buffer for one `texWidth²` RGBA data texture. */
    private fun allocateTexels(textureSize: Int): FloatBuffer =
        ByteBuffer.allocateDirect(textureSize * textureSize * FLOATS_PER_TEXEL * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    /**
     * Packs the `splatPositionScale` texture: texel `i` = centre `xyz` of splat [order]`[i]`
     * plus its bounding half-extent in `a` (see the texel layout contract above):
     * [HALF_EXTENT_SIGMA] times the *largest* of the three per-axis standard deviations. The
     * shader shapes each splat from `splatRotationScale` ([packRotationScale]); `a` is the
     * conservative radius the culling box ([boundingBox]) is built from.
     *
     * Unused tail texels (`i >= count`) stay zero: a zero half-extent collapses the quad to a
     * degenerate point which rasterizes nothing, so garbage texels can never paint.
     *
     * @param order draw order: `order[i]` = cloud index of the splat stored at texel `i`.
     *              Must be a permutation of `0 until cloud.count` (only size is validated).
     * @return a direct, native-ordered buffer of `textureSize² * 4` floats, positioned at 0.
     */
    fun packPositionScale(cloud: SplatCloud, order: IntArray, textureSize: Int): FloatBuffer {
        require(order.size == cloud.count) {
            "order must have one entry per splat: expected ${cloud.count}, was ${order.size}"
        }
        require(textureSize * textureSize >= cloud.count) {
            "textureSize $textureSize (${textureSize * textureSize} texels) < count ${cloud.count}"
        }
        val buffer = allocateTexels(textureSize)
        for (i in 0 until cloud.count) {
            val s = order[i]
            val p = s * 3
            buffer.put(i * FLOATS_PER_TEXEL, cloud.positions[p])
            buffer.put(i * FLOATS_PER_TEXEL + 1, cloud.positions[p + 1])
            buffer.put(i * FLOATS_PER_TEXEL + 2, cloud.positions[p + 2])
            buffer.put(i * FLOATS_PER_TEXEL + 3, HALF_EXTENT_SIGMA * maxScale(cloud.scales, s))
        }
        return buffer
    }

    /**
     * Packs the `splatColorOpacity` texture: texel `i` = linear `rgb` colour of splat
     * [order]`[i]` plus its straight opacity in `a`. Same ordering/tail semantics as
     * [packPositionScale].
     */
    fun packColorOpacity(cloud: SplatCloud, order: IntArray, textureSize: Int): FloatBuffer {
        require(order.size == cloud.count) {
            "order must have one entry per splat: expected ${cloud.count}, was ${order.size}"
        }
        require(textureSize * textureSize >= cloud.count) {
            "textureSize $textureSize (${textureSize * textureSize} texels) < count ${cloud.count}"
        }
        val buffer = allocateTexels(textureSize)
        for (i in 0 until cloud.count) {
            val s = order[i]
            val c = s * 3
            buffer.put(i * FLOATS_PER_TEXEL, cloud.colors[c])
            buffer.put(i * FLOATS_PER_TEXEL + 1, cloud.colors[c + 1])
            buffer.put(i * FLOATS_PER_TEXEL + 2, cloud.colors[c + 2])
            buffer.put(i * FLOATS_PER_TEXEL + 3, cloud.opacities[s])
        }
        return buffer
    }

    /**
     * Packs the `splatRotationScale` texture (`RGBA32UI`): texel `i` holds splat [order]`[i]`'s
     * unit rotation quaternion and three per-axis standard deviations as IEEE half floats, two
     * per 32-bit channel with the first value in the low 16 bits — the layout GLSL's
     * `unpackHalf2x16` reads: `(qx,qy) (qz,qw) (sx,sy) (sz,0)`.
     *
     * This is what turns each splat from a round disc into its real oriented ellipse. Unused
     * tail texels stay zero (a zero scale rasterizes nothing).
     *
     * @return a direct, native-ordered buffer of `textureSize² * 4` ints, positioned at 0.
     */
    fun packRotationScale(cloud: SplatCloud, order: IntArray, textureSize: Int): IntBuffer {
        require(order.size == cloud.count) {
            "order must have one entry per splat: expected ${cloud.count}, was ${order.size}"
        }
        require(textureSize * textureSize >= cloud.count) {
            "textureSize $textureSize (${textureSize * textureSize} texels) < count ${cloud.count}"
        }
        val buffer = allocateUintTexels(textureSize, FLOATS_PER_TEXEL)
        for (i in 0 until cloud.count) {
            val s = order[i]
            val q = s * 4
            val k = s * 3
            val base = i * FLOATS_PER_TEXEL
            buffer.put(base, packHalf2x16(cloud.rotations[q], cloud.rotations[q + 1]))
            buffer.put(base + 1, packHalf2x16(cloud.rotations[q + 2], cloud.rotations[q + 3]))
            buffer.put(base + 2, packHalf2x16(cloud.scales[k], cloud.scales[k + 1]))
            buffer.put(base + 3, packHalf2x16(cloud.scales[k + 2], 0f))
        }
        return buffer
    }

    /**
     * Packs the `splatOrder` texture (`R32UI`): slot `i` holds [order]`[i]`, the index of the
     * splat drawn there. This is the whole per-sort upload — 4 bytes per splat, versus
     * re-packing every attribute in draw order.
     *
     * @return a direct, native-ordered buffer of `textureSize²` ints, positioned at 0.
     */
    fun packOrder(order: IntArray, textureSize: Int): IntBuffer {
        require(textureSize * textureSize >= order.size) {
            "textureSize $textureSize (${textureSize * textureSize} texels) < count ${order.size}"
        }
        val buffer = allocateUintTexels(textureSize, 1)
        buffer.put(order)
        buffer.rewind()
        return buffer
    }

    /**
     * Two floats as IEEE-754 half floats in one 32-bit word, [low] in bits 0–15 and [high] in
     * bits 16–31 — the inverse of GLSL `unpackHalf2x16`.
     */
    fun packHalf2x16(low: Float, high: Float): Int =
        (floatToHalf(high) shl 16) or floatToHalf(low)

    /**
     * Rounds [value] to the nearest IEEE-754 binary16 and returns its 16 bits (in the low half
     * of the int). Overflow saturates to ±infinity, NaN stays NaN, and values under the
     * smallest subnormal flush to a signed zero — `android.util.Half` behaviour, reimplemented
     * so the packing stays plain-JVM testable.
     */
    fun floatToHalf(value: Float): Int {
        val bits = java.lang.Float.floatToRawIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        val exponent = (bits ushr 23) and 0xFF
        val mantissa = bits and 0x7FFFFF
        if (exponent == 0xFF) {
            // Infinity or NaN (keep a quiet-NaN payload bit so it stays a NaN).
            return sign or 0x7C00 or (if (mantissa != 0) 0x200 else 0)
        }
        val halfExponent = exponent - 127 + 15
        if (halfExponent >= 0x1F) return sign or 0x7C00 // overflow → infinity
        if (halfExponent <= 0) {
            // Subnormal half (or zero): shift the implicit-1 mantissa into place, round to nearest even.
            if (halfExponent < -10) return sign
            val m = mantissa or 0x800000
            val shift = 14 - halfExponent
            var half = m ushr shift
            val remainder = m and ((1 shl shift) - 1)
            val halfway = 1 shl (shift - 1)
            if (remainder > halfway || (remainder == halfway && (half and 1) == 1)) half++
            return sign or half
        }
        // Normal half: round the 23-bit mantissa to 10 bits, nearest even; a carry bumps the exponent.
        var half = (halfExponent shl 10) or (mantissa ushr 13)
        val remainder = mantissa and 0x1FFF
        if (remainder > 0x1000 || (remainder == 0x1000 && (half and 1) == 1)) half++
        return sign or half
    }

    /** Allocates a direct, native-ordered int buffer for one `texWidth²` unsigned-int texture. */
    private fun allocateUintTexels(textureSize: Int, channels: Int): IntBuffer =
        ByteBuffer.allocateDirect(textureSize * textureSize * channels * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()

    /**
     * Painter's sort: returns the splat indices ordered **back-to-front** (farthest first)
     * relative to the camera at (model-space) position ([camX], [camY], [camZ]).
     *
     * The depth key is the squared euclidean distance to the camera — the standard splat-viewer
     * approximation of view-space z (identical ordering along the view axis; may differ from a
     * true z-sort for extreme off-axis pairs, which is visually negligible for alpha
     * compositing). Squared distances are non-negative, so their raw float bits are
     * monotonically ordered — each `(distanceBits << 32) | index` key is sorted as a primitive
     * `long` (no boxing) and read out in reverse.
     *
     * The camera position must be in the **cloud's model space** (i.e. the camera world
     * position transformed by the inverse of the node's world transform) whenever the node is
     * transformed; for an untransformed node, world space is model space.
     */
    fun sortBackToFront(positions: FloatArray, count: Int, camX: Float, camY: Float, camZ: Float): IntArray {
        require(positions.size >= 3 * count) {
            "positions must hold 3 floats per splat: expected >= ${3 * count}, was ${positions.size}"
        }
        val keys = LongArray(count)
        for (i in 0 until count) {
            val dx = positions[i * 3] - camX
            val dy = positions[i * 3 + 1] - camY
            val dz = positions[i * 3 + 2] - camZ
            val d2 = dx * dx + dy * dy + dz * dz
            // d2 >= 0 ⇒ raw IEEE-754 bits sort in numeric order as a (positive) int.
            keys[i] = (java.lang.Float.floatToRawIntBits(d2).toLong() shl 32) or i.toLong()
        }
        keys.sort() // ascending distance = front-to-back
        val order = IntArray(count)
        for (i in 0 until count) {
            order[i] = (keys[count - 1 - i] and 0xFFFFFFFFL).toInt() // reversed = back-to-front
        }
        return order
    }

    /**
     * Model-space axis-aligned bounding box of the rendered cloud — every splat centre grown
     * by its billboard half-extent, so camera-facing quads never poke outside the culling box.
     *
     * @return `[centerX, centerY, centerZ, halfX, halfY, halfZ]` (the `Filament.Box` shape),
     *         or a unit-half-extent box at the origin for an empty cloud (Filament rejects
     *         fully degenerate boxes on renderables).
     */
    fun boundingBox(cloud: SplatCloud): FloatArray {
        if (cloud.count == 0) return floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (i in 0 until cloud.count) {
            val r = HALF_EXTENT_SIGMA * maxScale(cloud.scales, i)
            val x = cloud.positions[i * 3]
            val y = cloud.positions[i * 3 + 1]
            val z = cloud.positions[i * 3 + 2]
            minX = min(minX, x - r); maxX = max(maxX, x + r)
            minY = min(minY, y - r); maxY = max(maxY, y + r)
            minZ = min(minZ, z - r); maxZ = max(maxZ, z + r)
        }
        return floatArrayOf(
            (minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f,
            // Never fully degenerate — a flat (planar) cloud still needs a valid box.
            max((maxX - minX) / 2f, 1e-4f),
            max((maxY - minY) / 2f, 1e-4f),
            max((maxZ - minZ) / 2f, 1e-4f)
        )
    }

    private fun maxScale(scales: FloatArray, splat: Int): Float {
        val s = splat * 3
        return max(scales[s], max(scales[s + 1], scales[s + 2]))
    }
}
