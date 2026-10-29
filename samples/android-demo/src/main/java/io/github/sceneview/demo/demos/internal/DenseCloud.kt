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
 * = none), [normals] flat unit xyz (or `null`), [confidences] 0–255 (or `null`), and [levels],
 * each point's detail level (or `null`, all level 0), one per point. A point at level L stands
 * for a voxel 2^L times the cloud's own (#4629: [DenseFusion] coarsens broad surfaces).
 */
class DenseCloud(
    val positions: FloatArray,
    val colors: IntArray,
    val normals: FloatArray? = null,
    val confidences: ByteArray? = null,
    val levels: ByteArray? = null,
) {
    val count: Int get() = colors.size

    init {
        require(positions.size == colors.size * 3) { "${positions.size / 3} positions for ${colors.size} colours" }
        require(normals == null || normals.size == positions.size) { "one normal per point" }
        require(confidences == null || confidences.size == colors.size) { "one confidence per point" }
        require(levels == null || levels.size == colors.size) { "one level per point" }
    }

    /** Point [index]'s detail level: its surfel is `voxel · 2^level` wide. */
    fun levelOf(index: Int): Int = levels?.get(index)?.toInt()?.and(0xFF) ?: 0

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
 * per attribute (structure of arrays), 12 bytes a point with the first four sections:
 *
 * ```
 * 0  "SVPC"            magic
 * 4  u32 version       1
 * 8  u32 count         points
 * 12 u32 flags         bit 0 normals, bit 1 confidence, bit 2 levels; other bits reserved (0)
 * 16 f32 × 3 origin    metres
 * 28 f32 scale         metres per unit, 0.001 unless the cloud is wider than ±32.767 m
 * 32 i16 × 3 × count   position = origin + q · scale
 *    u8 × 3 × count    colour r, g, b (sRGB)
 *    u8 × count        confidence 0–255             (flag bit 1)
 *    i8 × 2 × count    octahedral unit normal ×127  (flag bit 0)
 *    u8 × count        detail level, voxel × 2^level (flag bit 2)
 * ```
 *
 * Encoder rules, so another writer gives the same bytes: origin is the centre of the cloud's
 * bounds; scale is a millimetre, or `half extent / 32767` for a wider cloud; a value is rounded
 * half away from zero, then clamped to ±32767; a normal maps onto the octahedron (lower half
 * folded over), each coordinate `×127`, rounded the same way. The levels section is written only
 * when a point is coarser than level 0 (#4629), so a scan that never coarsened keeps the bytes it
 * had; it comes last, so a reader that does not know it reads the rest unchanged. A reader skips
 * a blob whose magic or version it does not know.
 */
object SvpcCodec {
    /** `"SVPC"` read as a little-endian u32. */
    const val MAGIC = 0x43505653
    const val VERSION = 1
    const val HEADER_BYTES = 32
    const val FLAG_NORMALS = 1
    const val FLAG_CONFIDENCE = 2
    const val FLAG_LEVELS = 4

    /** A millimetre: the step of every cloud that fits ±32.767 m around its centre. */
    const val SCALE_M = 0.001f
    const val Q_MAX = 32_767

    /** Bytes of a blob of [count] points with the sections [flags] names. */
    fun sizeOf(count: Int, flags: Int): Int {
        var perPoint = 6 + 3
        if (flags and FLAG_CONFIDENCE != 0) perPoint += 1
        if (flags and FLAG_NORMALS != 0) perPoint += 2
        if (flags and FLAG_LEVELS != 0) perPoint += 1
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
        val levels = cloud.levels?.takeIf { l -> l.any { it.toInt() != 0 } }
        if (levels != null) flags = flags or FLAG_LEVELS
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
        levels?.let { out.put(it) }
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
        if (length < sizeOf(n, flags and KNOWN_FLAGS)) return null
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
        val levels = if (flags and FLAG_LEVELS != 0) ByteArray(n).also { input.get(it) } else null
        return DenseCloud(positions, colors, normals, confidences, levels)
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
    private const val KNOWN_FLAGS = FLAG_NORMALS or FLAG_CONFIDENCE or FLAG_LEVELS
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
     * The surfels of [frame]: every pixel with depth in [[NEAR_M], [farM]], confidence ≥
     * [minConfidence] and a normal — from its neighbours, none across a depth jump
     * ([EDGE_FRACTION]) — facing the camera by at least [MIN_VIEW_COSINE].
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "NestedBlockDepth")
    fun project(frame: DepthFrame, minConfidence: Int = MIN_CONFIDENCE, farM: Float = FAR_M): DenseSamples {
        val w = frame.width
        val h = frame.height
        val depth = FloatArray(w * h)
        for (i in 0 until w * h) {
            val d = (frame.depthMm[i].toInt() and 0xFFFF) / 1000f
            depth[i] = if (d in NEAR_M..farM) d else 0f
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

    /**
     * The distance under which a surfel shrinks towards a dot (#4742): `scan_surfel.mat` scales
     * it about its centre by `(depth / NEAR_FADE_M)²`, so its side on screen falls to nothing as
     * the camera reaches it. A surfel is a few centimetres wide in the world, so one the camera
     * passed close to — the saving orbit leaving the room through a wall, the live eye next to a
     * surface — grew into a square over most of the screen. Farther, every level is drawn as
     * built, so walls keep no holes.
     */
    const val NEAR_FADE_M = 0.3f

    /**
     * The largest side a surfel is drawn at, as a fraction of the view's height: a backstop for
     * the coarsest levels just past [NEAR_FADE_M]. A level-[DenseFusion.MAX_LEVEL] surfel (about
     * 10 cm) reaches it only closer than ~0.36 m on a phone's lens; a level-0 one never does.
     */
    const val MAX_SCREEN_SIDE = 0.25f

    /**
     * [MAX_SCREEN_SIDE] over the camera: a tenth of the view's height, about the largest a finest
     * surfel is drawn (at [NEAR_FADE_M]). A surfel the map coarsened keeps its width in the room,
     * so a wall past about a metre stays whole at every level; nearer, it shrinks to the size of a
     * fine one instead of growing into a square over the camera.
     */
    const val LIVE_MAX_SCREEN_SIDE = 0.1f

    /** The largest side a view draws a surfel at: [LIVE_MAX_SCREEN_SIDE] over the camera ([live]). */
    fun maxScreenSide(live: Boolean): Float = if (live) LIVE_MAX_SCREEN_SIDE else MAX_SCREEN_SIDE

    /**
     * What `scan_surfel.mat`'s vertex stage scales a surfel's corners by about its centre — its
     * centre [depth] metres ahead, [halfDiagonal] metres from centre to corner, through a
     * projection whose `[1][1]` term is [clipYScale]: `1` past [nearFade] while its side on
     * screen stays under [maxSide] of the view's height, `(depth / nearFade)²` nearer, and never
     * more than keeps it under [maxSide].
     */
    fun screenScale(
        halfDiagonal: Float,
        depth: Float,
        clipYScale: Float,
        maxSide: Float = MAX_SCREEN_SIDE,
        nearFade: Float = NEAR_FADE_M,
    ): Float {
        val d = depth.coerceAtLeast(MIN_DEPTH_M)
        val side = halfDiagonal * SQRT_2 * clipYScale / (2f * d)
        val fade = (d / nearFade).coerceAtMost(1f)
        return minOf(fade * fade, maxSide / side.coerceAtLeast(MIN_SIDE))
    }

    /**
     * Each vertex of a [mesh] made by [mesh] minus the centre of its surfel, xyz per vertex: the
     * `custom0` attribute `scan_surfel.mat` finds the centre with. Four vertices per surfel.
     */
    fun cornerOffsets(mesh: DebugMesh): FloatArray {
        val p = mesh.positions
        val out = FloatArray(mesh.vertexCount * 3)
        var q = 0
        while (q + 4 <= mesh.vertexCount) {
            for (axis in 0 until 3) {
                val base = q * 3 + axis
                val centre = (p[base] + p[base + 3] + p[base + 6] + p[base + 9]) / 4f
                for (corner in 0 until 4) out[base + corner * 3] = p[base + corner * 3] - centre
            }
            q += 4
        }
        return out
    }

    private const val SQRT_2 = 1.4142135f
    private const val MIN_DEPTH_M = 1e-4f
    private const val MIN_SIDE = 1e-6f

    /**
     * Two triangles and four vertices per surfel, surfel `i` at indices `6i until 6i + 6`; a
     * surfel of level L ([DenseCloud.levelOf]) 2^L times as wide, so a coarsened wall stays whole.
     */
    fun mesh(cloud: DenseCloud, voxelM: Float): DebugMesh {
        val n = minOf(cloud.count, MAX_SURFELS)
        val mesh = DebugMesh(maxOf(n * 4, 1))
        val normals = cloud.normals
        for (i in 0 until n) {
            val half = voxelM * HALF_SIDE_VOXELS * (1 shl cloud.levelOf(i).coerceAtMost(DenseFusion.LEVEL_LIMIT))
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
