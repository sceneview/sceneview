package io.github.sceneview.web.splat

import io.github.sceneview.core.splat.SplatCloud
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.set
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure packing, batching and sorting for the web splat renderer.
 * Position/bound and display-referred colour/opacity are RGBA16F, uploaded once in
 * cloud order. Rotation xyzw and scale xyz use two more RGBA16F textures.
 * The RGBA8 order texture stores the splat index as three bytes (base-256 digits); only
 * it changes on a sort. The shader reconstructs the index before fetching static attributes.
 */
object SplatWebBuffers {

    /**
     * Filament hardware-instancing cap per renderable: a single
     * `RenderableManager$Builder.instances(n)` draw supports at most 65535 instances.
     * Clouds above this are split into multiple renderables ("batches") sharing the same
     * static data textures, each shifted by its `instanceOffset` material parameter.
     * Same constant as Android `SplatBuffers.MAX_INSTANCES_PER_BATCH`.
     */
    const val MAX_INSTANCES_PER_BATCH = 65535

    /**
     * Largest cloud the web renderer draws: 2^24 splats. The order texture stores an index
     * in three bytes, and `texWidth` / `instanceOffset` reach the shader as floats, exact
     * up to the same bound.
     */
    const val MAX_SPLATS = 1 shl 24

    /**
     * Throws an [IllegalArgumentException] naming the limit when a cloud of [count] splats
     * cannot be rendered. Called before any Filament object is created for the cloud.
     */
    fun requireSupportedCount(count: Int) {
        require(count in 1..MAX_SPLATS) {
            "SceneView web renders clouds of 1 to $MAX_SPLATS (2^24) splats; this one has $count"
        }
    }

    /** Conservative culling radius in standard deviations; shader reach is at most 3σ. */
    const val HALF_EXTENT_SIGMA = 3f

    /** Floats per texel in both data textures (RGBA). */
    private const val FLOATS_PER_TEXEL = 4

    /**
     * Side of the square data texture needed to hold [count] texels:
     * the smallest `w` with `w * w >= count` (minimum 1 — Filament rejects 0-sized
     * textures). Square because `splat_web.mat` derives both texel coordinates from the
     * single `texWidth` parameter.
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
     * Splits [count] splats into contiguous draw batches of at most
     * [MAX_INSTANCES_PER_BATCH]. Batch `b` renders texels `[first..last]`; its material
     * gets `instanceOffset = first` so `getInstanceIndex() + instanceOffset` indexes the
     * shared textures globally. An empty cloud yields no batches.
     */
    fun batchRanges(count: Int): List<IntRange> {
        require(count >= 0) { "count must be >= 0, was $count" }
        if (count == 0) return emptyList()
        return (0 until count step MAX_INSTANCES_PER_BATCH).map { start ->
            start until min(start + MAX_INSTANCES_PER_BATCH, count)
        }
    }

    /**
     * How many of [batch]'s instances are rendered when only the first [splatCount] texels
     * of the global draw order are visible: `0` (batch entirely past the cut — hide it) up
     * to the full batch size. Drives `SplatNode.splatCount`'s per-batch rebuild/hide
     * decision.
     */
    fun visibleInBatch(splatCount: Int, batch: IntRange): Int =
        (splatCount - batch.first).coerceIn(0, batch.count())

    /**
     * Packs the `splatPositionScale` texture: texel `i` = centre `xyz` of splat
     * [order]`[i]` plus its conservative bounding half-extent in `a` (see the texel layout
     * contract above).
     *
     * The shader uses rotation/scale for the ellipse; this bound is for CPU culling.
     * Unused tail texels stay zero.
     *
     * @param order draw order: `order[i]` = cloud index of the splat stored at texel `i`.
     *              Must be a permutation of `0 until cloud.count` (only size is validated).
     * @return a [Float32Array] of `textureSize² * 4` floats.
     */
    fun packPositionScale(cloud: SplatCloud, order: IntArray, textureSize: Int): Float32Array {
        require(order.size == cloud.count) {
            "order must have one entry per splat: expected ${cloud.count}, was ${order.size}"
        }
        require(textureSize * textureSize >= cloud.count) {
            "textureSize $textureSize (${textureSize * textureSize} texels) < count ${cloud.count}"
        }
        val buffer = Float32Array(textureSize * textureSize * FLOATS_PER_TEXEL)
        for (i in 0 until cloud.count) {
            val s = order[i]
            val p = s * 3
            buffer[i * FLOATS_PER_TEXEL] = cloud.positions[p]
            buffer[i * FLOATS_PER_TEXEL + 1] = cloud.positions[p + 1]
            buffer[i * FLOATS_PER_TEXEL + 2] = cloud.positions[p + 2]
            buffer[i * FLOATS_PER_TEXEL + 3] = HALF_EXTENT_SIGMA * maxScale(cloud.scales, s)
        }
        return buffer
    }

    /**
     * Packs the `splatColorOpacity` texture: texel `i` = display-referred `rgb` colour of splat
     * [order]`[i]` plus its straight opacity in `a`. Same ordering/tail semantics as
     * [packPositionScale].
     */
    fun packColorOpacity(cloud: SplatCloud, order: IntArray, textureSize: Int): Float32Array {
        require(order.size == cloud.count) {
            "order must have one entry per splat: expected ${cloud.count}, was ${order.size}"
        }
        require(textureSize * textureSize >= cloud.count) {
            "textureSize $textureSize (${textureSize * textureSize} texels) < count ${cloud.count}"
        }
        val buffer = Float32Array(textureSize * textureSize * FLOATS_PER_TEXEL)
        for (i in 0 until cloud.count) {
            val s = order[i]
            val c = s * 3
            buffer[i * FLOATS_PER_TEXEL] = cloud.colors[c]
            buffer[i * FLOATS_PER_TEXEL + 1] = cloud.colors[c + 1]
            buffer[i * FLOATS_PER_TEXEL + 2] = cloud.colors[c + 2]
            buffer[i * FLOATS_PER_TEXEL + 3] = cloud.opacities[s]
        }
        return buffer
    }

    /**
     * Cloud order: quaternion xyzw, then scale xyz with zero padding. Values are written as
     * they are; the upload into the RGBA16F textures rounds them to half floats.
     */
    internal fun packRotationScale(cloud: SplatCloud, textureSize: Int): Pair<Float32Array, Float32Array> {
        require(textureSize > 0 && textureSize.toDouble() * textureSize >= cloud.count)
        val rotation = Float32Array(textureSize * textureSize * 4)
        val scale = Float32Array(textureSize * textureSize * 4)
        for (i in 0 until cloud.count) {
            for (j in 0..3) rotation[i * 4 + j] = cloud.rotations[i * 4 + j]
            for (j in 0..2) scale[i * 4 + j] = cloud.scales[i * 3 + j]
        }
        return rotation to scale
    }

    internal fun unpackRotationScale(rotation: Float32Array, scale: Float32Array, splat: Int): FloatArray =
        FloatArray(7) { j ->
            (if (j < 4) rotation.asDynamic()[splat * 4 + j]
            else scale.asDynamic()[splat * 4 + j - 4]).unsafeCast<Float>()
        }

    /**
     * The RGBA8 order texture: slot `i` holds the index of the `i`-th splat to draw as three
     * bytes, least significant in `r`; `a` and the padding slots are zero. Upload size is
     * `4 * texWidth²` bytes.
     */
    internal fun packOrder(order: IntArray, textureSize: Int): Uint8Array {
        require(order.size <= MAX_SPLATS) { "The order texture indexes at most 2^24 splats" }
        require(textureSize > 0 && textureSize.toDouble() * textureSize >= order.size)
        val buffer = Uint8Array(textureSize * textureSize * 4)
        for (i in order.indices) {
            val index = order[i]
            require(index in order.indices) { "Order index outside cloud: $index" }
            // Kotlin's Byte is signed; the typed array stores the low 8 bits unchanged.
            buffer[i * 4] = (index and 255).toByte()
            buffer[i * 4 + 1] = ((index ushr 8) and 255).toByte()
            buffer[i * 4 + 2] = ((index ushr 16) and 255).toByte()
        }
        return buffer
    }

    /**
     * Painter's sort: returns the splat indices ordered **back-to-front** (farthest first)
     * relative to the camera at (model-space) position ([camX], [camY], [camZ]).
     *
     * The depth key is the squared euclidean distance to the camera — the standard
     * splat-viewer approximation of view-space z (identical ordering along the view axis;
     * may differ from a true z-sort for extreme off-axis pairs, which is visually
     * negligible for alpha compositing).
     *
     * Implementation note (vs Android): the JVM packs `(distanceBits << 32) | index` into
     * a primitive `LongArray`. Kotlin/JS emulates `Long`, so that trick would be slower
     * than a plain comparator here — instead the `IntArray` (an `Int32Array` in JS) is
     * sorted in place with `TypedArray.prototype.sort` and a distance comparator.
     *
     * The camera position must be in the **cloud's model space** (i.e. the camera world
     * position transformed by the inverse of the node's world transform) whenever the node
     * is transformed; for an untransformed node, world space is model space.
     */
    fun sortBackToFront(positions: FloatArray, count: Int, camX: Float, camY: Float, camZ: Float): IntArray {
        require(positions.size >= 3 * count) {
            "positions must hold 3 floats per splat: expected >= ${3 * count}, was ${positions.size}"
        }
        val dist = DoubleArray(count)
        for (i in 0 until count) {
            val dx = (positions[i * 3] - camX).toDouble()
            val dy = (positions[i * 3 + 1] - camY).toDouble()
            val dz = (positions[i * 3 + 2] - camZ).toDouble()
            dist[i] = dx * dx + dy * dy + dz * dz
        }
        val order = IntArray(count) { it }
        // Kotlin/JS IntArray IS an Int32Array; its native in-place sort accepts a
        // comparator. Descending distance = back-to-front (farthest texel first).
        order.asDynamic().sort { a: Int, b: Int -> dist[b] - dist[a] }
        return order
    }

    /**
     * Model-space axis-aligned bounding box of the rendered cloud — every splat centre
     * grown by its billboard half-extent, so camera-facing quads never poke outside the
     * culling box.
     *
     * @return `[centerX, centerY, centerZ, halfX, halfY, halfZ]` (the `Filament.Box`
     *         shape), or a unit-half-extent box at the origin for an empty cloud
     *         (Filament rejects fully degenerate boxes on renderables).
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
