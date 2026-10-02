package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/*
 * The Rerun demo's final model, step one: ARCore's raw depth fused into a truncated signed
 * distance field (TSDF). [RerunMarchingCubes] then extracts the room's surface from it as a
 * coloured triangle mesh. Pure Kotlin, no ARCore and no Filament: the recorder hands it the same
 * [DepthFrame] copies the surfel map ([DenseFusion]) gets — the depth image's own lens and only
 * fresh depth, as `ScanCapture.acquireDepth` hands them out — and it runs off the main thread.
 */

/** What one [RerunTsdf.integrate] did: the blocks it touched, those it created, those it dropped. */
data class TsdfIntegration(val touched: Int, val created: Int, val dropped: Int, val voxels: Int)

/**
 * A sparse TSDF: voxels of [voxelM] metres, allocated by blocks of [BLOCK]³ in a hash, only
 * where a depth ray ends. Each voxel keeps its signed distance to the nearest surface along the
 * view ray (metres, positive in front of it, clamped to ±[truncationM]), the sum of the weights
 * that shaped it, and the average camera colour of the views that saw it close up.
 *
 * Memory is capped at [maxBytes]: past it, new blocks are dropped (never the ones already
 * built), and [budgetReached] and [droppedBlocks] say so — the trace a scan logs.
 *
 * Voxel `(i, j, k)` samples the field at `(i, j, k) · voxelM` in world space. Single-threaded:
 * one integration at a time, off the main thread; [RerunMarchingCubes] reads it once it is done.
 */
class RerunTsdf(
    val voxelM: Float = VOXEL_M,
    val truncationM: Float = voxelM * TRUNCATION_VOXELS,
    val maxBytes: Long = MAX_BYTES,
) {
    init {
        require(voxelM > 0f && truncationM >= voxelM) { "voxel $voxelM m, truncation $truncationM m" }
    }

    /** The most blocks [maxBytes] holds. */
    val maxBlocks: Int = (maxBytes / BYTES_PER_BLOCK).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private val index = LongIntMap()
    private var keys = LongArray(INITIAL_BLOCKS)
    internal val sdf = ArrayList<FloatArray>()
    internal val weight = ArrayList<FloatArray>()

    /** Three bytes a voxel: r, g, b (sRGB). */
    internal val rgb = ArrayList<ByteArray>()
    internal val colorWeight = ArrayList<ByteArray>()

    /** Blocks allocated so far. */
    var blockCount: Int = 0
        private set

    /** Blocks a full budget turned away, over the TSDF's life. */
    var droppedBlocks: Int = 0
        private set

    /** Depth frames integrated so far. */
    var frames: Int = 0
        private set

    val bytes: Long get() = blockCount.toLong() * BYTES_PER_BLOCK
    val budgetReached: Boolean get() = droppedBlocks > 0

    // The last block [update] wrote into: neighbouring updates mostly land in the same one.
    private var lastKey = Long.MIN_VALUE
    private var lastBlock = -1

    /** The block at block coordinates ([bx], [by], [bz]), `-1` when none. */
    fun blockAt(bx: Int, by: Int, bz: Int): Int = index.get(blockKey(bx, by, bz))

    /** Block [block]'s coordinates, into [out]. */
    fun blockCoord(block: Int, out: IntArray) {
        val key = keys[block]
        out[0] = ((key ushr 42) and MASK21).toInt() - OFFSET21
        out[1] = ((key ushr 21) and MASK21).toInt() - OFFSET21
        out[2] = (key and MASK21).toInt() - OFFSET21
    }

    /**
     * Fuses [frame] — raw depth in millimetres, its confidence, a colour per pixel, the depth
     * image's own lens and the pose of the camera that took it — into the field. Pixels outside
     * [[DepthBackProjection.NEAR_M], [DepthBackProjection.FAR_M]] or under [minConfidence] are
     * skipped. Voxel-centric: every voxel of every block a ray ends in is projected into the
     * depth image, and takes the projective distance `depth − z` of the pixel it lands on.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
    fun integrate(frame: DepthFrame, minConfidence: Int = DepthBackProjection.MIN_CONFIDENCE): TsdfIntegration {
        val w = frame.width
        val h = frame.height
        val depth = FloatArray(w * h)
        val conf = FloatArray(w * h)
        for (i in 0 until w * h) {
            val d = (frame.depthMm[i].toInt() and 0xFFFF) / MM_PER_M
            val c = frame.confidence?.let { it[i].toInt() and 0xFF } ?: FULL_CONFIDENCE
            if (d in DepthBackProjection.NEAR_M..DepthBackProjection.FAR_M && c >= minConfidence) {
                depth[i] = d
                conf[i] = c / FULL_CONFIDENCE.toFloat()
            }
        }
        val pose = Rigid.of(frame.pose)
        val blockM = voxelM * BLOCK
        // 1. The blocks each ray crosses within the truncation band around its end.
        val touched = LongIntMap()
        var touchedList = LongArray(INITIAL_BLOCKS)
        var touchedCount = 0
        val steps = ceil(2f * truncationM / (blockM * 0.5f)).toInt().coerceAtLeast(1)
        for (py in 0 until h) {
            for (px in 0 until w) {
                val d = depth[py * w + px]
                if (d == 0f) continue
                val rx = (px - frame.cx) / frame.fx
                val ry = -(py - frame.cy) / frame.fy
                for (s in 0..steps) {
                    val t = d - truncationM + 2f * truncationM * s / steps
                    val wx = pose.x(rx * t, ry * t, -t)
                    val wy = pose.y(rx * t, ry * t, -t)
                    val wz = pose.z(rx * t, ry * t, -t)
                    val key = blockKey(
                        floor(wx / blockM).toInt(),
                        floor(wy / blockM).toInt(),
                        floor(wz / blockM).toInt(),
                    )
                    if (touched.get(key) >= 0) continue
                    touched.put(key, touchedCount)
                    if (touchedCount == touchedList.size) touchedList = touchedList.copyOf(touchedCount * 2)
                    touchedList[touchedCount++] = key
                }
            }
        }
        // 2. Every voxel of those blocks, projected into the depth image.
        var created = 0
        var dropped = 0
        var voxels = 0
        for (b in 0 until touchedCount) {
            val key = touchedList[b]
            var block = index.get(key)
            if (block < 0) {
                block = allocate(key)
                if (block < 0) {
                    dropped++
                    continue
                }
                created++
            }
            val bx = ((key ushr 42) and MASK21).toInt() - OFFSET21
            val by = ((key ushr 21) and MASK21).toInt() - OFFSET21
            val bz = (key and MASK21).toInt() - OFFSET21
            val sdfs = sdf[block]
            val weights = weight[block]
            val colours = rgb[block]
            val colourWeights = colorWeight[block]
            for (v in 0 until VOXELS) {
                val gx = bx * BLOCK + (v and 7)
                val gy = by * BLOCK + ((v shr 3) and 7)
                val gz = bz * BLOCK + (v shr 6)
                // World → camera: the inverse pose, ARCore camera space (-Z forward, +Y up).
                val dx = gx * voxelM - pose.tx
                val dy = gy * voxelM - pose.ty
                val dz = gz * voxelM - pose.tz
                val cx = pose.m00 * dx + pose.m10 * dy + pose.m20 * dz
                val cy = pose.m01 * dx + pose.m11 * dy + pose.m21 * dz
                val z = -(pose.m02 * dx + pose.m12 * dy + pose.m22 * dz)
                if (z < DepthBackProjection.NEAR_M * 0.5f) continue
                val u = frame.fx * cx / z + frame.cx
                val uv = frame.cy - frame.fy * cy / z
                if (u < -0.5f || uv < -0.5f) continue
                val px = (u + 0.5f).toInt()
                val py = (uv + 0.5f).toInt()
                if (px >= w || py >= h) continue
                val pixel = py * w + px
                val d = depth[pixel]
                if (d == 0f) continue
                val distance = d - z
                if (distance < -truncationM) continue // behind the surface: never seen
                val tsdf = minOf(distance, truncationM)
                val wNew = conf[pixel]
                val wOld = weights[v]
                val total = wOld + wNew
                sdfs[v] = (sdfs[v] * wOld + tsdf * wNew) / total
                weights[v] = minOf(total, MAX_WEIGHT)
                if (abs(distance) < truncationM * COLOR_BAND) blendColor(colours, colourWeights, v, frame.colors[pixel])
                voxels++
            }
        }
        droppedBlocks += dropped
        frames++
        return TsdfIntegration(touchedCount, created, dropped, voxels)
    }

    /**
     * Fuses a saved dense cloud — surfels with unit normals, [DenseFusion]'s output — into the
     * field: each surfel writes its signed distance `(v − p) · n` into the voxels of a thin
     * column along its normal, fading with the distance off that axis. The model of a scan whose
     * raw depth is gone: a kept session, reopened. [progress] gets the share done now and then.
     */
    @Suppress("NestedBlockDepth", "LoopWithTooManyJumpStatements")
    fun integrateSurfels(cloud: DenseCloud, progress: (Float) -> Unit = {}) {
        val normals = cloud.normals ?: return
        val reach = ceil(truncationM / voxelM).toInt()
        val lateral = LATERAL_VOXELS * voxelM
        for (i in 0 until cloud.count) {
            if (i % PROGRESS_STEP == 0) progress(i.toFloat() / cloud.count)
            val px = cloud.positions[i * 3]
            val py = cloud.positions[i * 3 + 1]
            val pz = cloud.positions[i * 3 + 2]
            val nx = normals[i * 3]
            val ny = normals[i * 3 + 1]
            val nz = normals[i * 3 + 2]
            if (!finite(px, py, pz) || !finite(nx, ny, nz)) continue
            val colour = cloud.colors[i]
            for (k in -reach..reach) {
                val qx = px + nx * k * voxelM
                val qy = py + ny * k * voxelM
                val qz = pz + nz * k * voxelM
                val ix = floor(qx / voxelM).toInt()
                val iy = floor(qy / voxelM).toInt()
                val iz = floor(qz / voxelM).toInt()
                for (c in 0 until 8) {
                    val gx = ix + (c and 1)
                    val gy = iy + ((c shr 1) and 1)
                    val gz = iz + (c shr 2)
                    val ox = gx * voxelM - px
                    val oy = gy * voxelM - py
                    val oz = gz * voxelM - pz
                    val along = ox * nx + oy * ny + oz * nz
                    if (abs(along) > truncationM) continue
                    val off = sqrt(maxOf(0f, ox * ox + oy * oy + oz * oz - along * along))
                    if (off > lateral) continue
                    val w = maxOf(MIN_SURFEL_WEIGHT, 1f - off / lateral)
                    update(gx, gy, gz, along, w, if (abs(along) < voxelM) colour else 0)
                }
            }
        }
        progress(1f)
    }

    /**
     * Blends the signed distance [distanceM] with weight [w] into voxel ([gx], [gy], [gz]), and
     * [color] (`0xFFRRGGBB`, `0` = none) into its average. `false` when the budget has no room
     * for its block.
     */
    fun update(gx: Int, gy: Int, gz: Int, distanceM: Float, w: Float, color: Int = 0): Boolean {
        val key = blockKey(gx shr 3, gy shr 3, gz shr 3)
        val block = if (key == lastKey) {
            lastBlock
        } else {
            var found = index.get(key)
            if (found < 0) found = allocate(key)
            if (found < 0) {
                droppedBlocks++
                return false
            }
            lastKey = key
            lastBlock = found
            found
        }
        val v = ((gz and 7) shl 6) or ((gy and 7) shl 3) or (gx and 7)
        val sdfs = sdf[block]
        val weights = weight[block]
        val tsdf = distanceM.coerceIn(-truncationM, truncationM)
        val total = weights[v] + w
        sdfs[v] = (sdfs[v] * weights[v] + tsdf * w) / total
        weights[v] = minOf(total, MAX_WEIGHT)
        blendColor(rgb[block], colorWeight[block], v, color)
        return true
    }

    /** Voxel ([gx], [gy], [gz])'s signed distance, `NaN` when no view reached it. */
    fun distanceAt(gx: Int, gy: Int, gz: Int): Float {
        val block = index.get(blockKey(gx shr 3, gy shr 3, gz shr 3))
        if (block < 0) return Float.NaN
        val v = ((gz and 7) shl 6) or ((gy and 7) shl 3) or (gx and 7)
        return if (weight[block][v] > 0f) sdf[block][v] else Float.NaN
    }

    private fun blendColor(colours: ByteArray, weights: ByteArray, v: Int, color: Int) {
        if (color == 0) return
        val cw = minOf((weights[v].toInt() and 0xFF) + 1, MAX_COLOR_WEIGHT)
        weights[v] = cw.toByte()
        for (ch in 0 until 3) {
            val at = v * 3 + ch
            val old = colours[at].toInt() and 0xFF
            val new = (color shr (16 - ch * 8)) and 0xFF
            colours[at] = (old + (new - old + if (new >= old) cw / 2 else -cw / 2) / cw).coerceIn(0, 255).toByte()
        }
    }

    private fun allocate(key: Long): Int {
        if (blockCount >= maxBlocks) return -1
        if (blockCount == keys.size) keys = keys.copyOf(keys.size * 2)
        keys[blockCount] = key
        sdf += FloatArray(VOXELS)
        weight += FloatArray(VOXELS)
        rgb += ByteArray(VOXELS * 3)
        colorWeight += ByteArray(VOXELS)
        index.put(key, blockCount)
        return blockCount++
    }

    /** A camera pose as a rotation matrix and a translation, camera → world. */
    private class Rigid(
        val m00: Float, val m01: Float, val m02: Float,
        val m10: Float, val m11: Float, val m12: Float,
        val m20: Float, val m21: Float, val m22: Float,
        val tx: Float, val ty: Float, val tz: Float,
    ) {
        fun x(lx: Float, ly: Float, lz: Float) = m00 * lx + m01 * ly + m02 * lz + tx
        fun y(lx: Float, ly: Float, lz: Float) = m10 * lx + m11 * ly + m12 * lz + ty
        fun z(lx: Float, ly: Float, lz: Float) = m20 * lx + m21 * ly + m22 * lz + tz

        companion object {
            fun of(p: DebugPose): Rigid {
                val l = sqrt(p.qx * p.qx + p.qy * p.qy + p.qz * p.qz + p.qw * p.qw).takeIf { it > 0f } ?: 1f
                val x = p.qx / l
                val y = p.qy / l
                val z = p.qz / l
                val w = p.qw / l
                return Rigid(
                    1f - 2f * (y * y + z * z), 2f * (x * y - z * w), 2f * (x * z + y * w),
                    2f * (x * y + z * w), 1f - 2f * (x * x + z * z), 2f * (y * z - x * w),
                    2f * (x * z - y * w), 2f * (y * z + x * w), 1f - 2f * (x * x + y * y),
                    p.x, p.y, p.z,
                )
            }
        }
    }

    companion object {
        /** 2.5 cm: a room's walls, doors and furniture, in a few tens of megabytes. */
        const val VOXEL_M = 0.025f

        /** The truncation band, in voxels either side of the surface. */
        const val TRUNCATION_VOXELS = 3f

        /** Voxels along a block's edge. */
        const val BLOCK = 8
        const val VOXELS = BLOCK * BLOCK * BLOCK

        /** Per voxel: distance and weight (floats), colour (3 bytes), colour weight (1 byte). */
        const val BYTES_PER_BLOCK = VOXELS * (4 + 4 + 3 + 1) + 64L

        /** 48 MB: ~7 900 blocks of 20 cm, a furnished room's surfaces with room to spare. */
        const val MAX_BYTES = 48L * 1024 * 1024

        const val MAX_WEIGHT = 64f
        const val MAX_COLOR_WEIGHT = 64

        /** Only the views that saw a voxel within this share of the truncation colour it. */
        private const val COLOR_BAND = 0.5f

        /** A surfel reaches this many voxels off its normal. */
        private const val LATERAL_VOXELS = 1.5f
        private const val MIN_SURFEL_WEIGHT = 0.05f
        private const val PROGRESS_STEP = 16_384

        private const val MM_PER_M = 1000f
        private const val FULL_CONFIDENCE = 255
        private const val INITIAL_BLOCKS = 1024

        private const val OFFSET21 = 1 shl 20
        private const val MASK21 = 0x1FFFFFL

        /** Block ([bx], [by], [bz]) as one key: 21 bits an axis, ±1 048 576 blocks (±200 km). */
        fun blockKey(bx: Int, by: Int, bz: Int): Long =
            ((bx + OFFSET21).toLong() and MASK21 shl 42) or
                ((by + OFFSET21).toLong() and MASK21 shl 21) or
                ((bz + OFFSET21).toLong() and MASK21)
    }
}

/**
 * A primitive open-addressing `Long → Int` map (no boxing), for block and edge keys. Keys must
 * not be [Long.MIN_VALUE]. [get] is `-1` for a missing key.
 */
class LongIntMap(initialSlots: Int = 1 shl 12) {
    private var keys = LongArray(initialSlots) { EMPTY }
    private var values = IntArray(initialSlots)

    var size: Int = 0
        private set

    fun get(key: Long): Int {
        val mask = keys.size - 1
        var slot = mix(key) and mask
        while (true) {
            val k = keys[slot]
            if (k == EMPTY) return -1
            if (k == key) return values[slot]
            slot = (slot + 1) and mask
        }
    }

    fun put(key: Long, value: Int) {
        if ((size + 1) * 2 > keys.size) rehash(keys.size * 2)
        val mask = keys.size - 1
        var slot = mix(key) and mask
        while (true) {
            val k = keys[slot]
            if (k == EMPTY) {
                keys[slot] = key
                values[slot] = value
                size++
                return
            }
            if (k == key) {
                values[slot] = value
                return
            }
            slot = (slot + 1) and mask
        }
    }

    private fun rehash(capacity: Int) {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(capacity) { EMPTY }
        values = IntArray(capacity)
        size = 0
        for (i in oldKeys.indices) if (oldKeys[i] != EMPTY) put(oldKeys[i], oldValues[i])
    }

    private companion object {
        const val EMPTY = Long.MIN_VALUE

        fun mix(key: Long): Int {
            var h = key * -0x61c8864680b583ebL
            h = h xor (h ushr 29)
            return h.toInt() and Int.MAX_VALUE
        }
    }
}

private fun finite(x: Float, y: Float, z: Float) = x.isFinite() && y.isFinite() && z.isFinite()
