package io.github.sceneview.demo.demos.internal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/*
 * The dense half of a `.svscan` v2 (Rerun v2, tier B): ARCore's raw depth back-projected into
 * world space, fused into 2 cm surfels, and stored as the `dense/points.bin` blob ([SvpcCodec]),
 * the layout the iOS app is to write and read byte for byte. Pure Kotlin, no ARCore and no
 * Filament: the recorder hands it copies of the depth, the confidence and the camera colours,
 * and it runs off the main thread.
 */

/**
 * A dense coloured cloud: [positions] flat world-space xyz in metres, [colors] `0xFFRRGGBB` (`0`
 * = none), [normals] flat unit xyz (or `null`), [confidences] 0–255 (or `null`), one per point.
 */
class DenseCloud(
    val positions: FloatArray,
    val colors: IntArray,
    val normals: FloatArray? = null,
    val confidences: ByteArray? = null,
) {
    val count: Int get() = colors.size

    init {
        require(positions.size == colors.size * 3) { "${positions.size / 3} positions for ${colors.size} colours" }
        require(normals == null || normals.size == positions.size) { "one normal per point" }
        require(confidences == null || confidences.size == colors.size) { "one confidence per point" }
    }

    /** `[minX, minY, minZ, maxX, maxY, maxZ]`, all zero for an empty cloud. */
    fun bounds(): FloatArray {
        if (count == 0) return FloatArray(6)
        val b = floatArrayOf(
            Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
            -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE,
        )
        for (i in 0 until count) {
            for (a in 0 until 3) {
                val v = positions[i * 3 + a]
                if (v < b[a]) b[a] = v
                if (v > b[a + 3]) b[a + 3] = v
            }
        }
        return b
    }

    companion object {
        val Empty = DenseCloud(FloatArray(0), IntArray(0))
    }
}

/**
 * The `dense/points.bin` blob, SVPC version 1. Little-endian, a 32-byte header then one section
 * per attribute (structure of arrays), 12 bytes a point with every section:
 *
 * ```
 * 0  "SVPC"            magic
 * 4  u32 version       1
 * 8  u32 count         points
 * 12 u32 flags         bit 0 normals, bit 1 confidence; other bits reserved (0)
 * 16 f32 × 3 origin    metres
 * 28 f32 scale         metres per unit, 0.001 unless the cloud is wider than ±32.767 m
 * 32 i16 × 3 × count   position = origin + q · scale
 *    u8 × 3 × count    colour r, g, b (sRGB)
 *    u8 × count        confidence 0–255             (flag bit 1)
 *    i8 × 2 × count    octahedral unit normal ×127  (flag bit 0)
 * ```
 *
 * Encoder rules, so another writer gives the same bytes: origin is the centre of the cloud's
 * bounds; scale is a millimetre, or `half extent / 32767` for a wider cloud; a value is rounded
 * half away from zero, then clamped to ±32767; a normal maps onto the octahedron (lower half
 * folded over), each coordinate `×127`, rounded the same way. A reader skips a blob whose magic
 * or version it does not know.
 */
object SvpcCodec {
    /** `"SVPC"` read as a little-endian u32. */
    const val MAGIC = 0x43505653
    const val VERSION = 1
    const val HEADER_BYTES = 32
    const val FLAG_NORMALS = 1
    const val FLAG_CONFIDENCE = 2

    /** A millimetre: the step of every cloud that fits ±32.767 m around its centre. */
    const val SCALE_M = 0.001f
    const val Q_MAX = 32_767

    /** Bytes of a blob of [count] points with the sections [flags] names. */
    fun sizeOf(count: Int, flags: Int): Int {
        var perPoint = 6 + 3
        if (flags and FLAG_CONFIDENCE != 0) perPoint += 1
        if (flags and FLAG_NORMALS != 0) perPoint += 2
        return HEADER_BYTES + perPoint * count
    }

    /** [cloud] as an SVPC blob. The same cloud always gives the same bytes. */
    fun encode(cloud: DenseCloud): ByteArray {
        val n = cloud.count
        val b = cloud.bounds()
        val origin = floatArrayOf((b[0] + b[3]) * 0.5f, (b[1] + b[4]) * 0.5f, (b[2] + b[5]) * 0.5f)
        val half = maxOf(b[3] - origin[0], b[4] - origin[1], b[5] - origin[2], 0f)
        val scale = if (half <= Q_MAX * SCALE_M) SCALE_M else half / Q_MAX
        var flags = 0
        if (cloud.normals != null) flags = flags or FLAG_NORMALS
        if (cloud.confidences != null) flags = flags or FLAG_CONFIDENCE
        val out = ByteBuffer.allocate(sizeOf(n, flags)).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC).putInt(VERSION).putInt(n).putInt(flags)
        out.putFloat(origin[0]).putFloat(origin[1]).putFloat(origin[2]).putFloat(scale)
        for (i in 0 until n * 3) {
            out.putShort(quantize((cloud.positions[i] - origin[i % 3]) / scale, Q_MAX).toShort())
        }
        for (c in cloud.colors) {
            out.put((c shr 16).toByte()).put((c shr 8).toByte()).put(c.toByte())
        }
        cloud.confidences?.let { out.put(it) }
        cloud.normals?.let { normals ->
            for (i in 0 until n) {
                val oct = octEncode(normals[i * 3], normals[i * 3 + 1], normals[i * 3 + 2])
                out.put((oct shr 8).toByte()).put(oct.toByte())
            }
        }
        return out.array()
    }

    /** The cloud of the SVPC blob at [offset], `null` when it is not one this reader knows. */
    @Suppress("ReturnCount") // one early return per malformed-header case
    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): DenseCloud? {
        if (offset < 0 || length < HEADER_BYTES || offset + length > bytes.size) return null
        val input = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.LITTLE_ENDIAN)
        if (input.int != MAGIC || input.int != VERSION) return null
        val n = input.int
        val flags = input.int
        if (n < 0 || n > (length - HEADER_BYTES) / 9) return null
        if (length < sizeOf(n, flags and (FLAG_NORMALS or FLAG_CONFIDENCE))) return null
        val origin = floatArrayOf(input.float, input.float, input.float)
        val scale = input.float
        if (!(scale > 0f) || origin.any { !it.isFinite() }) return null
        val positions = FloatArray(n * 3)
        for (i in 0 until n * 3) positions[i] = origin[i % 3] + input.short * scale
        val colors = IntArray(n) {
            val r = input.get().toInt() and 0xFF
            val g = input.get().toInt() and 0xFF
            val b = input.get().toInt() and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val confidences = if (flags and FLAG_CONFIDENCE != 0) ByteArray(n).also { input.get(it) } else null
        val normals = if (flags and FLAG_NORMALS != 0) {
            FloatArray(n * 3).also { out ->
                for (i in 0 until n) octDecode(input.get().toInt(), input.get().toInt(), out, i * 3)
            }
        } else {
            null
        }
        return DenseCloud(positions, colors, normals, confidences)
    }

    /** Round half away from zero, then clamp to ±[limit]: Swift's `.rounded()`, not `Math.round`. */
    fun quantize(value: Float, limit: Int): Int {
        if (value.isNaN()) return 0
        val r = if (value >= 0f) floor(value + 0.5f) else -floor(-value + 0.5f)
        return r.coerceIn(-limit.toFloat(), limit.toFloat()).toInt()
    }

    /**
     * The unit normal ([x], [y], [z]) octahedron-mapped to two snorm8 values, packed as
     * `(u shl 8) or (v and 0xFF)`. A zero vector encodes as (0, 0), which decodes to +Z.
     */
    fun octEncode(x: Float, y: Float, z: Float): Int {
        val l1 = abs(x) + abs(y) + abs(z)
        if (!(l1 > 0f) || !l1.isFinite()) return 0
        var u = x / l1
        var v = y / l1
        if (z < 0f) {
            val pu = u
            u = (1f - abs(v)) * signNotZero(pu)
            v = (1f - abs(pu)) * signNotZero(v)
        }
        val qu = quantize(u.coerceIn(-1f, 1f) * OCT_MAX, OCT_MAX)
        val qv = quantize(v.coerceIn(-1f, 1f) * OCT_MAX, OCT_MAX)
        return (qu and 0xFF shl 8) or (qv and 0xFF)
    }

    /** The unit normal of the snorm8 pair ([u], [v]) (signed bytes), into [out] at [at]. */
    fun octDecode(u: Int, v: Int, out: FloatArray, at: Int) {
        var x = u.toByte() / OCT_MAX.toFloat()
        var y = v.toByte() / OCT_MAX.toFloat()
        val z = 1f - abs(x) - abs(y)
        if (z < 0f) {
            val px = x
            x = (1f - abs(y)) * signNotZero(px)
            y = (1f - abs(px)) * signNotZero(y)
        }
        val l = sqrt(x * x + y * y + z * z)
        out[at] = x / l
        out[at + 1] = y / l
        out[at + 2] = z / l
    }

    private fun signNotZero(v: Float) = if (v >= 0f) 1f else -1f

    private const val OCT_MAX = 127
}

/**
 * One raw-depth keyframe, copied out of ARCore: [depthMm] and [confidence] row-major
 * [width] × [height] (DEPTH16 millimetres, Y8 0–255), [colors] the camera image's colour at each
 * depth pixel (`0xFFRRGGBB`, `0` = none), the lens at the depth image's resolution, and the
 * [pose] of the camera that took it (ARCore's `Camera.getPose()`, the sensor's, not the display's).
 */
class DepthFrame(
    val width: Int,
    val height: Int,
    val depthMm: ShortArray,
    val confidence: ByteArray?,
    val colors: IntArray,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val pose: DebugPose,
) {
    init {
        require(depthMm.size >= width * height && colors.size >= width * height) { "short depth frame" }
        require(confidence == null || confidence.size >= width * height) { "short confidence" }
    }
}

/**
 * Surfels of one [DepthFrame]: flat world xyz, unit world normals, colours and confidences, and
 * how much the fusion trusts each ([weights], [DepthBackProjection.weight]; `null` = all alike).
 */
class DenseSamples(
    val count: Int,
    val positions: FloatArray,
    val normals: FloatArray,
    val colors: IntArray,
    val confidences: ByteArray,
    val weights: FloatArray? = null,
)

/** Raw depth → world-space surfels: [DepthMeshGeometry]'s back-projection, plus normals. */
object DepthBackProjection {
    /** ARCore's raw-depth confidence past which a pixel is kept: its own "medium" half. */
    const val MIN_CONFIDENCE = 128
    const val NEAR_M = 0.2f
    const val FAR_M = 5f

    /** Neighbours further apart in depth than this share of it lie on another surface. */
    const val EDGE_FRACTION = 0.05f

    /** A surface seen more edge-on than this (cosine to the view ray) is too noisy to keep. */
    const val MIN_VIEW_COSINE = 0.15f

    /**
     * The depth up to which a sample weighs in full. Past it the weight falls as 1/d², the way
     * ARCore's depth error grows with the square of the distance.
     */
    const val FULL_WEIGHT_M = 1f

    /**
     * How much a sample counts in [DenseFusion]: `confidence/255 × min(1, (FULL_WEIGHT_M/d)²) ×
     * cos θ`, θ between the surface normal and the ray to the camera. A point 4 m away at
     * confidence 130 seen face on counts 1/31st of one 0.8 m away at 250.
     */
    fun weight(confidence: Int, depthM: Float, cosine: Float): Float {
        val near = FULL_WEIGHT_M / depthM
        return confidence / 255f * minOf(1f, near * near) * cosine
    }

    /**
     * The camera-space point of depth pixel ([px], [py]) at [depthM] metres: ARCore camera
     * space, +X right, +Y up, -Z forward — `buildDepthMeshGeometry`'s own mapping.
     */
    fun cameraPoint(px: Int, py: Int, depthM: Float, fx: Float, fy: Float, cx: Float, cy: Float): Vec3 =
        Vec3((px - cx) * depthM / fx, -(py - cy) * depthM / fy, -depthM)

    /**
     * The surfels of [frame]: every pixel with depth in [[NEAR_M], [FAR_M]], confidence ≥
     * [minConfidence] and a normal — from its neighbours, none across a depth jump
     * ([EDGE_FRACTION]) — facing the camera by at least [MIN_VIEW_COSINE].
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "NestedBlockDepth")
    fun project(frame: DepthFrame, minConfidence: Int = MIN_CONFIDENCE): DenseSamples {
        val w = frame.width
        val h = frame.height
        val depth = FloatArray(w * h)
        for (i in 0 until w * h) {
            val d = (frame.depthMm[i].toInt() and 0xFFFF) / 1000f
            depth[i] = if (d in NEAR_M..FAR_M) d else 0f
        }
        val capacity = w * h
        val positions = FloatArray(capacity * 3)
        val normals = FloatArray(capacity * 3)
        val colors = IntArray(capacity)
        val confidences = ByteArray(capacity)
        val weights = FloatArray(capacity)
        var n = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val d = depth[i]
                if (d == 0f) continue
                val confidence = frame.confidence?.let { it[i].toInt() and 0xFF } ?: 255
                if (confidence < minConfidence) continue
                val edge = EDGE_FRACTION * d
                val left = neighbour(depth, w, x - 1, y, d, edge, x > 0)
                val right = neighbour(depth, w, x + 1, y, d, edge, x < w - 1)
                val up = neighbour(depth, w, x, y - 1, d, edge, y > 0)
                val down = neighbour(depth, w, x, y + 1, d, edge, y < h - 1)
                if (noNormal(left, right, up, down)) continue
                val p = cameraPoint(x, y, d, frame.fx, frame.fy, frame.cx, frame.cy)
                val px0 = if (left != 0f) cameraPoint(x - 1, y, left, frame.fx, frame.fy, frame.cx, frame.cy) else p
                val px1 = if (right != 0f) cameraPoint(x + 1, y, right, frame.fx, frame.fy, frame.cx, frame.cy) else p
                val py0 = if (up != 0f) cameraPoint(x, y - 1, up, frame.fx, frame.fy, frame.cx, frame.cy) else p
                val py1 = if (down != 0f) cameraPoint(x, y + 1, down, frame.fx, frame.fy, frame.cx, frame.cy) else p
                var normal = (px1 - px0).cross(py1 - py0)
                val length = normal.length()
                if (!(length > 0f)) continue
                normal = normal * (1f / length)
                // Towards the camera, which sits at the camera-space origin.
                val toCamera = p * (-1f / p.length())
                var cosine = normal.dot(toCamera)
                if (cosine < 0f) {
                    normal = normal * -1f
                    cosine = -cosine
                }
                if (cosine < MIN_VIEW_COSINE) continue
                val world = frame.pose.transform(p.x, p.y, p.z)
                val worldNormal = frame.pose.rotate(normal.x, normal.y, normal.z)
                positions[n * 3] = world.x
                positions[n * 3 + 1] = world.y
                positions[n * 3 + 2] = world.z
                normals[n * 3] = worldNormal.x
                normals[n * 3 + 1] = worldNormal.y
                normals[n * 3 + 2] = worldNormal.z
                colors[n] = frame.colors[i]
                confidences[n] = confidence.toByte()
                weights[n] = weight(confidence, d, cosine)
                n++
            }
        }
        return DenseSamples(n, positions, normals, colors, confidences, weights)
    }

    /** No neighbour on an axis: no normal. */
    private fun noNormal(left: Float, right: Float, up: Float, down: Float) =
        left == 0f && right == 0f || up == 0f && down == 0f

    /** The depth at ([x], [y]) when [inside] the image and within [edge] of [d]; `0` otherwise. */
    private fun neighbour(depth: FloatArray, w: Int, x: Int, y: Int, d: Float, edge: Float, inside: Boolean): Float {
        if (!inside) return 0f
        val v = depth[y * w + x]
        return if (v != 0f && abs(v - d) <= edge) v else 0f
    }
}

/**
 * What one [DenseFusion.add] did: voxels created, samples merged into voxels, voxels held in all,
 * and [points] — those of them a scan counts ([DenseFusion.points]).
 */
data class DenseFuseStats(val added: Int, val kept: Int, val total: Int, val points: Int)

/**
 * The dense map: samples merged into [voxelM] voxels, deduplicated by a primitive
 * open-addressing hash — a voxel seen again averages its position, colour and normal, each
 * sample weighted by [DenseSamples.weights] (a near, confident, face-on view outweighs a far,
 * doubtful, grazing one) up to [MAX_WEIGHT] full views, so it keeps following a better view, and
 * keeps its best confidence. It also counts the [add] calls — depth frames — that saw it, so
 * [cloud] can leave out a voxel one frame alone saw: the dust a noisy depth leaves in the air.
 *
 * A scan has one figure, [points]: the voxels [MIN_VIEWS] frames have seen. It is what the scan
 * shows while it records, what it saves, and what [maxPoints] caps — a phone once showed 32 k
 * "points" for a scan that stored 5.6 k, the first figure counting every voxel held and the
 * second the ones kept. The voxels seen once so far are held beside them, [maxVoxels] in all;
 * when that fills, those no frame has seen again for [STALE_VIEWS] frames are let go.
 *
 * Voxels keep their insertion order, so a prefix of the cloud is the map as it stood earlier.
 * Single-threaded: one fusion at a time, off the main thread.
 */
class DenseFusion(
    val voxelM: Float = VOXEL_M,
    val maxPoints: Int = MAX_POINTS,
    private val maxVoxels: Int = maxOf(MAX_VOXELS, maxPoints),
) {
    /** Voxels held: [points], and those one depth frame alone has seen so far. */
    var count: Int = 0
        private set

    /**
     * Voxels [MIN_VIEWS] depth frames have seen — what [cloud] returns at [MIN_VIEWS], so what a
     * saved scan holds. Never more than [maxPoints].
     */
    var points: Int = 0
        private set

    private var keys = LongArray(INITIAL_SLOTS) { EMPTY }
    private var slots = IntArray(INITIAL_SLOTS)
    private var px = FloatArray(INITIAL_POINTS)
    private var py = FloatArray(INITIAL_POINTS)
    private var pz = FloatArray(INITIAL_POINTS)
    private var nx = FloatArray(INITIAL_POINTS)
    private var ny = FloatArray(INITIAL_POINTS)
    private var nz = FloatArray(INITIAL_POINTS)
    private var r = FloatArray(INITIAL_POINTS)
    private var g = FloatArray(INITIAL_POINTS)
    private var b = FloatArray(INITIAL_POINTS)
    private var weight = FloatArray(INITIAL_POINTS)
    private var colorWeight = FloatArray(INITIAL_POINTS)
    private var confidence = ByteArray(INITIAL_POINTS)
    private var views = IntArray(INITIAL_POINTS)
    private var lastView = IntArray(INITIAL_POINTS)

    /** The current [add] call, numbered from 1: a voxel's views grow once per call. */
    private var view = 0

    /** The [add] call that last tried [dropStale]: once a call is enough. */
    private var staleDroppedAt = 0

    /** Merges [samples] into the map. */
    @Suppress("LoopWithTooManyJumpStatements") // one skip per rejected sample
    fun add(samples: DenseSamples): DenseFuseStats {
        view++
        var added = 0
        var kept = 0
        for (i in 0 until samples.count) {
            val x = samples.positions[i * 3]
            val y = samples.positions[i * 3 + 1]
            val z = samples.positions[i * 3 + 2]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
            val key = voxelKey(x, y, z, voxelM)
            var index = find(key)
            if (index < 0) {
                // Full: no point more to count, or no room for a voxel that could become one.
                if (points >= maxPoints || count >= maxVoxels && !dropStale()) continue
                index = insert(key)
                added++
            }
            merge(index, x, y, z, samples, i)
            kept++
        }
        return DenseFuseStats(added, kept, count, points)
    }

    /**
     * The map's first [limit] voxels as a cloud — averaged positions, colours, unit normals —
     * leaving out those fewer than [minViews] depth frames saw ([MIN_VIEWS] for a saved scan),
     * and [maxPoints] at most: past it, every n-th voxel, so a capped cloud still covers the whole
     * room — thinner — rather than the corner scanned first.
     */
    fun cloud(limit: Int = count, minViews: Int = 1, maxPoints: Int = Int.MAX_VALUE): DenseCloud {
        val first = limit.coerceIn(0, count)
        val cap = minOf(first, maxPoints.coerceAtLeast(0))
        val stride = if (cap == 0) 1 else (first + cap - 1) / cap
        val kept = IntArray(cap)
        var n = 0
        var i = 0
        while (i < first && n < cap) {
            if (views[i] >= minViews) kept[n++] = i
            i += stride
        }
        val positions = FloatArray(n * 3)
        val normals = FloatArray(n * 3)
        val colors = IntArray(n)
        val confidences = ByteArray(n)
        for (o in 0 until n) {
            val i = kept[o]
            positions[o * 3] = px[i]
            positions[o * 3 + 1] = py[i]
            positions[o * 3 + 2] = pz[i]
            val l = sqrt(nx[i] * nx[i] + ny[i] * ny[i] + nz[i] * nz[i])
            if (l > 1e-6f) {
                normals[o * 3] = nx[i] / l
                normals[o * 3 + 1] = ny[i] / l
                normals[o * 3 + 2] = nz[i] / l
            } else {
                normals[o * 3 + 1] = 1f // opposite views cancelled out: face up
            }
            colors[o] = if (colorWeight[i] == 0f) 0 else {
                (0xFF shl 24) or (channel(r[i]) shl 16) or (channel(g[i]) shl 8) or channel(b[i])
            }
            confidences[o] = confidence[i]
        }
        return DenseCloud(positions, colors, normals, confidences)
    }

    private fun merge(index: Int, x: Float, y: Float, z: Float, samples: DenseSamples, i: Int) {
        if (lastView[index] != view) {
            lastView[index] = view
            val seen = views[index] + 1
            // The view that makes a voxel a point counts only while the scan has room for one.
            if (seen != MIN_VIEWS) {
                views[index] = seen
            } else if (points < maxPoints) {
                views[index] = seen
                points++
            }
        }
        val sw = (samples.weights?.get(i) ?: 1f).coerceAtLeast(MIN_SAMPLE_WEIGHT)
        // A running weighted mean; past MAX_WEIGHT a new sample still moves it by sw / MAX_WEIGHT.
        val w = minOf(weight[index] + sw, MAX_WEIGHT)
        weight[index] = w
        val k = sw / w
        px[index] += (x - px[index]) * k
        py[index] += (y - py[index]) * k
        pz[index] += (z - pz[index]) * k
        nx[index] += samples.normals[i * 3] * sw
        ny[index] += samples.normals[i * 3 + 1] * sw
        nz[index] += samples.normals[i * 3 + 2] * sw
        // Keep the summed normal bounded so a long-seen voxel still turns with new views.
        val l = abs(nx[index]) + abs(ny[index]) + abs(nz[index])
        if (l > MAX_WEIGHT) {
            val s = MAX_WEIGHT / l
            nx[index] *= s
            ny[index] *= s
            nz[index] *= s
        }
        val c = samples.colors[i]
        if (c != 0) {
            val cw = minOf(colorWeight[index] + sw, MAX_WEIGHT)
            colorWeight[index] = cw
            val ck = sw / cw
            r[index] += ((c shr 16 and 0xFF) - r[index]) * ck
            g[index] += ((c shr 8 and 0xFF) - g[index]) * ck
            b[index] += ((c and 0xFF) - b[index]) * ck
        }
        val conf = samples.confidences[i].toInt() and 0xFF
        if (conf > (confidence[index].toInt() and 0xFF)) confidence[index] = conf.toByte()
    }

    private fun find(key: Long): Int {
        val mask = keys.size - 1
        var slot = mix(key) and mask
        while (true) {
            val k = keys[slot]
            if (k == EMPTY) return -1
            if (k == key) return slots[slot]
            slot = (slot + 1) and mask
        }
    }

    /**
     * Makes room by letting go of the voxels one depth frame alone saw, [STALE_VIEWS] frames ago
     * or more — noise a saved scan leaves out anyway. The others keep their order. Tried once
     * per [add]; `false` when nothing could go.
     */
    private fun dropStale(): Boolean {
        if (staleDroppedAt == view) return false
        staleDroppedAt = view
        val before = count
        val movedTo = IntArray(before)
        var n = 0
        for (i in 0 until before) {
            if (views[i] < MIN_VIEWS && view - lastView[i] >= STALE_VIEWS) {
                movedTo[i] = -1
                continue
            }
            movedTo[i] = n
            if (n != i) move(i, n)
            n++
        }
        if (n == before) return false
        val oldKeys = keys
        val oldSlots = slots
        keys = LongArray(oldKeys.size) { EMPTY }
        slots = IntArray(oldSlots.size)
        val mask = keys.size - 1
        for (s in oldKeys.indices) {
            val k = oldKeys[s]
            val to = if (k == EMPTY) -1 else movedTo[oldSlots[s]]
            if (to < 0) continue
            var slot = mix(k) and mask
            while (keys[slot] != EMPTY) slot = (slot + 1) and mask
            keys[slot] = k
            slots[slot] = to
        }
        count = n
        return true
    }

    private fun move(from: Int, to: Int) {
        px[to] = px[from]; py[to] = py[from]; pz[to] = pz[from]
        nx[to] = nx[from]; ny[to] = ny[from]; nz[to] = nz[from]
        r[to] = r[from]; g[to] = g[from]; b[to] = b[from]
        weight[to] = weight[from]
        colorWeight[to] = colorWeight[from]
        confidence[to] = confidence[from]
        views[to] = views[from]
        lastView[to] = lastView[from]
    }

    private fun insert(key: Long): Int {
        if ((count + 1) * 2 > keys.size) rehash(keys.size * 2)
        if (count == px.size) grow(minOf(px.size * 2, maxOf(maxVoxels, px.size + 1)))
        val mask = keys.size - 1
        var slot = mix(key) and mask
        while (keys[slot] != EMPTY) slot = (slot + 1) and mask
        keys[slot] = key
        slots[slot] = count
        weight[count] = 0f
        colorWeight[count] = 0f
        confidence[count] = 0
        views[count] = 0
        lastView[count] = 0
        px[count] = 0f; py[count] = 0f; pz[count] = 0f
        nx[count] = 0f; ny[count] = 0f; nz[count] = 0f
        r[count] = 0f; g[count] = 0f; b[count] = 0f
        return count++
    }

    private fun rehash(size: Int) {
        val oldKeys = keys
        val oldSlots = slots
        keys = LongArray(size) { EMPTY }
        slots = IntArray(size)
        val mask = size - 1
        for (s in oldKeys.indices) {
            val k = oldKeys[s]
            if (k == EMPTY) continue
            var slot = mix(k) and mask
            while (keys[slot] != EMPTY) slot = (slot + 1) and mask
            keys[slot] = k
            slots[slot] = oldSlots[s]
        }
    }

    private fun grow(size: Int) {
        px = px.copyOf(size); py = py.copyOf(size); pz = pz.copyOf(size)
        nx = nx.copyOf(size); ny = ny.copyOf(size); nz = nz.copyOf(size)
        r = r.copyOf(size); g = g.copyOf(size); b = b.copyOf(size)
        weight = weight.copyOf(size)
        colorWeight = colorWeight.copyOf(size)
        confidence = confidence.copyOf(size)
        views = views.copyOf(size)
        lastView = lastView.copyOf(size)
    }

    companion object {
        /** The design's surfel size: 2 cm. */
        const val VOXEL_M = 0.02f

        /** 500 k surfels: a room, 6 MB of SVPC. What a scan shows, saves and is capped at. */
        const val MAX_POINTS = 500_000

        /**
         * Voxels held at most, [MAX_POINTS] and those seen once so far: 2¹⁹, what the map's
         * arrays and its hash already held for [MAX_POINTS] — the memory of a full scan is the same.
         */
        const val MAX_VOXELS = 1 shl 19

        /**
         * Depth frames — three seconds of them — after which a voxel no second frame saw is let
         * go when the map needs its room.
         */
        const val STALE_VIEWS = 30

        /** A voxel's weight stops growing at this many full-weight views. */
        const val MAX_WEIGHT = 32f

        /**
         * Depth frames that must see a voxel for it to be a point of the scan ([points]): a voxel
         * only one frame saw is most often that frame's noise.
         */
        const val MIN_VIEWS = 2

        /** The least a sample counts, so a weight never divides by zero. */
        private const val MIN_SAMPLE_WEIGHT = 1e-4f

        private const val INITIAL_SLOTS = 1 shl 14
        private const val INITIAL_POINTS = 1 shl 13

        /** Never a key: [voxelKey] leaves the sign bit clear. */
        private const val EMPTY = -1L

        /** The voxel of ([x], [y], [z]) at [size]: 21 bits per axis, like [ArDebugTrace]'s map. */
        fun voxelKey(x: Float, y: Float, z: Float, size: Float): Long {
            val ix = floor(x / size).toLong() and 0x1FFFFF
            val iy = floor(y / size).toLong() and 0x1FFFFF
            val iz = floor(z / size).toLong() and 0x1FFFFF
            return (ix shl 42) or (iy shl 21) or iz
        }

        private fun mix(key: Long): Int {
            var h = key * -0x61c8864680b583ebL
            h = h xor (h ushr 29)
            return h.toInt() and Int.MAX_VALUE
        }

        private fun channel(v: Float) = (v + 0.5f).toInt().coerceIn(0, 255)
    }
}

/**
 * The replay's dense layer without a custom shader — the design's zero-shader parity fallback:
 * each surfel a square quad pre-expanded on the CPU, lying in the plane its normal gives, its
 * colour one texel of a [ATLAS_SIZE]² atlas read through the unlit image material.
 */
object DenseSurfels {
    /** 1024² = 1 048 576 texels: one per surfel, past [DenseFusion.MAX_POINTS]. */
    const val ATLAS_SIZE = 1024

    /**
     * Half the side of a surfel's square, in voxels: 1.3 voxels wide, so neighbours overlap a
     * little and a wall reads as a surface, not a grid of tiles.
     */
    const val HALF_SIDE_VOXELS = 0.65f

    /** Surfels [ATLAS_SIZE]² can colour. */
    const val MAX_SURFELS = ATLAS_SIZE * ATLAS_SIZE

    /** Two triangles and four vertices per surfel, surfel `i` at indices `6i until 6i + 6`. */
    fun mesh(cloud: DenseCloud, voxelM: Float): DebugMesh {
        val n = minOf(cloud.count, MAX_SURFELS)
        val mesh = DebugMesh(maxOf(n * 4, 1))
        val half = voxelM * HALF_SIDE_VOXELS
        val normals = cloud.normals
        for (i in 0 until n) {
            val x = cloud.positions[i * 3]
            val y = cloud.positions[i * 3 + 1]
            val z = cloud.positions[i * 3 + 2]
            val nxv = normals?.get(i * 3) ?: 0f
            val nyv = normals?.get(i * 3 + 1) ?: 1f
            val nzv = normals?.get(i * 3 + 2) ?: 0f
            // Two tangents: any vector not parallel to the normal, crossed twice.
            val ax = if (abs(nyv) < 0.9f) 0f else 1f
            val ay = if (abs(nyv) < 0.9f) 1f else 0f
            var tx = ay * nzv // a × n with a = (ax, ay, 0)
            var ty = -ax * nzv
            var tz = ax * nyv - ay * nxv
            val tl = sqrt(tx * tx + ty * ty + tz * tz).takeIf { it > 1e-6f } ?: 1f
            tx = tx / tl * half; ty = ty / tl * half; tz = tz / tl * half
            val bx = (nyv * tz - nzv * ty)
            val by = (nzv * tx - nxv * tz)
            val bz = (nxv * ty - nyv * tx)
            val (u, v) = uvOf(i)
            val a = mesh.vertex(x - tx - bx, y - ty - by, z - tz - bz, u, v)
            val b = mesh.vertex(x + tx - bx, y + ty - by, z + tz - bz, u, v)
            val c = mesh.vertex(x + tx + bx, y + ty + by, z + tz + bz, u, v)
            val d = mesh.vertex(x - tx + bx, y - ty + by, z - tz + bz, u, v)
            mesh.quad(a, b, c, d)
        }
        return mesh
    }

    /** Surfel [index]'s texel centre; V counts from the last row, as [PointColorAtlas.uvOf]. */
    fun uvOf(index: Int): Pair<Float, Float> = PointColorAtlas.uvOf(index, ATLAS_SIZE)

    /** The atlas's RGBA bytes: surfel `i` is texel `i`, row-major; no colour → [fallback]. */
    fun atlas(cloud: DenseCloud, fallback: Int): ByteArray = PointColorAtlas.pixels(cloud.colors, fallback, ATLAS_SIZE)
}
