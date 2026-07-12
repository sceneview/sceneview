package io.github.sceneview.splat

import io.github.sceneview.core.splat.SplatCloud
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
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
 * Per-splat attributes are packed into two square `texWidth x texWidth` RGBA float textures
 * (uploaded as `RGBA16F`). The splat drawn by instance `i` of a batch with base offset `o`
 * reads texel `idx = o + i` at pixel `(idx % texWidth, idx / texWidth)`:
 *
 * | Texture              | r, g, b                        | a                                  |
 * |----------------------|--------------------------------|------------------------------------|
 * | `splatPositionScale` | splat centre `xyz` (model space) | billboard half-extent = [HALF_EXTENT_SIGMA]·max(scaleX, scaleY, scaleZ) |
 * | `splatColorOpacity`  | linear SH0 colour              | opacity `0..1` (straight, NOT premultiplied — the fragment premultiplies after the gaussian falloff) |
 *
 * Texels are written in **draw order**: `order[i]` is the index into the [SplatCloud] arrays of
 * the splat stored at texel `i`. Re-sorting therefore re-packs + re-uploads the textures and the
 * shader's indexing never changes.
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
     * Billboard half-extent in gaussian standard deviations. The quad edge (`|corner| = 1`)
     * sits at 3σ, matching the fragment falloff `exp(-0.5 * dot(uv, uv) * k)` with
     * `k = HALF_EXTENT_SIGMA²  = 9` in `splat.mat` — beyond 3σ a gaussian contributes < 1.1%,
     * so nothing visible is clipped. Keep the two constants in sync.
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
     * plus its isotropic billboard half-extent in `a` (see the texel layout contract above).
     *
     * P1 scope: the half-extent is **isotropic** — [HALF_EXTENT_SIGMA] times the *largest* of
     * the three per-axis standard deviations, so anisotropic gaussians render as their
     * circumscribed disc (correct 2D-covariance ellipses are P2, #2646).
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
