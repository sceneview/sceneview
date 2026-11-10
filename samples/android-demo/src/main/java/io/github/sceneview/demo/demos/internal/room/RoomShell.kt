package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.BakePhoto
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.model.IntList
import io.github.sceneview.demo.demos.internal.model.ModelPhotos
import io.github.sceneview.demo.demos.internal.model.PhotoTexturing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One flat side of a [RoomShell]: a rectangle from [origin] along the unit vectors [u] and [v]
 * ([lengthU] × [lengthV] metres), seen from the side its [normal] points to — into the room — and
 * its photo texture, [columns] × [rows] texels, ARGB, rows top first (the top row is the far end
 * of [v]). World space, the scan's own.
 */
internal class ShellFace(
    val name: String,
    val origin: FloatArray,
    val u: FloatArray,
    val v: FloatArray,
    val lengthU: Float,
    val lengthV: Float,
    val normal: FloatArray,
    val columns: Int,
    val rows: Int,
) {
    /** The texture, once the photos coloured it. */
    var argb: IntArray = IntArray(0)
        internal set

    /** The share of its texels a photo saw; the rest took the nearest seen texel's colour. */
    var seenShare: Float = 0f
        internal set

    /** The mean colour of its texture, ARGB: the face's vertex colour, for a reader without textures. */
    var meanColor: Int = GREY
        internal set

    val texelU: Float get() = lengthU / columns
    val texelV: Float get() = lengthV / rows

    /** World xyz of the corner at ([s], [t]) metres along [u] and [v], into [out]. */
    fun at(s: Float, t: Float, out: FloatArray) {
        for (k in 0 until 3) out[k] = origin[k] + u[k] * s + v[k] * t
    }

    private companion object {
        const val GREY = 0xFF808080.toInt()
    }
}

/** What building a shell found and took, for the log, the tests and the corpus report. */
internal data class RoomShellStats(
    val points: Int,
    val cleanPoints: Int,
    val objectPoints: Int,
    val objectsRecoloured: Int,
    val photosIn: Int,
    val photosUsed: Int,
    val texels: Int,
    val seenShare: Float,
    val boxMs: Long,
    val neighboursMs: Long,
    val photosMs: Long,
    val finishMs: Long,
) {
    val totalMs: Long get() = boxMs + neighboursMs + photosMs + finishMs
}

/**
 * A room as a dollhouse reads it: its [box]'s floor and four walls as flat textured [faces] —
 * coloured from the scan's photos — and what stands in the room, [objects], as coloured points.
 */
internal class RoomShell(val box: RoomBox, val faces: List<ShellFace>, val objects: DenseCloud, val stats: RoomShellStats)

/*
 * The room shell, from a scan's dense cloud and its key photos, off the main thread, pure Kotlin:
 *
 * 1. The box ([RoomBox]): the room's frame, its four walls, its floor and ceiling.
 * 2. The cloud cleaned: a point is kept for the depth maps when [CLEAN_MIN_NEIGHBOURS] others lie
 *    within [CLEAN_RADIUS_M] and its confidence is [MIN_CONFIDENCE] or more (a voxel hash, no tree).
 * 3. The faces: floor and walls as [TEXEL_M] texel grids, each texel the weighted mean of every
 *    photo that sees it — not hidden behind the cleaned cloud's depth in that photo by more than
 *    [FACE_OCCLUSION_M], seen face on rather than edge-on, from near, away from the photo's
 *    border: `(cos / distance² · border)^1.5`, sampled bilinearly. A texel no photo saw takes the
 *    nearest seen one's colour; a 3 × 3 median then smooths the speckle.
 * 4. The objects: cleaned points with [OBJECT_MIN_NEIGHBOURS] within [OBJECT_RADIUS_M], inside
 *    the box by its margins, not part of a wall, coloured from the photos the same way (with the
 *    tighter [OBJECT_OCCLUSION_M]) or keeping their own colour when none sees them.
 */
internal object RoomShellBuilder {
    /**
     * The shell of [cloud] — its floor at [floorY], the manifest's — textured from [photos];
     * `null` when the cloud does not stand as a room ([RoomBox.of]) or has no normals. [progress]
     * (0–1) may throw to stop it.
     */
    @Suppress("LongMethod")
    suspend fun build(
        cloud: DenseCloud,
        photos: ModelPhotos?,
        floorY: Float?,
        progress: (Float) -> Unit = {},
    ): RoomShell? {
        val started = System.nanoTime()
        val normals = cloud.normals ?: return null
        val box = RoomBox.of(cloud, floorY) ?: return null
        val boxMs = since(started)
        val neighboursStarted = System.nanoTime()
        val n = cloud.count
        val p = cloud.positions
        val near = neighbourCounts(p, CLEAN_RADIUS_M)
        val confidences = cloud.confidences
        val clean = BooleanArray(n) {
            near[it] >= CLEAN_MIN_NEIGHBOURS && (confidences?.get(it)?.toInt()?.and(0xFF) ?: FULL_CONFIDENCE) >= MIN_CONFIDENCE
        }
        val wide = neighbourCounts(p, OBJECT_RADIUS_M)
        val objectIndices = objects(cloud, normals, box, clean, wide)
        val cleanPositions = IntList(n).also { list -> for (i in 0 until n) if (clean[i]) list.add(i) }.let { list ->
            FloatArray(list.size * 3) { p[list[it / 3] * 3 + it % 3] }
        }
        val neighboursMs = since(neighboursStarted)
        progress(PROGRESS_NEIGHBOURS)

        val photosStarted = System.nanoTime()
        val faces = faces(box)
        val faceAcc = faces.map { Accumulator(it.columns * it.rows) }
        val objectPositions = FloatArray(objectIndices.size * 3) { p[objectIndices[it / 3] * 3 + it % 3] }
        val objectNormals = FloatArray(objectIndices.size * 3) { normals[objectIndices[it / 3] * 3 + it % 3] }
        val objectAcc = Accumulator(objectIndices.size)
        val all = photos?.photos.orEmpty()
        var used = 0
        all.forEachIndexed { index, photo ->
            coroutineContext.ensureActive()
            val decoded = photo.load()?.takeIf { it.width > 2 && it.height > 2 }
            if (decoded != null) {
                val view = PhotoView(photo.pose, PhotoTexturing.downscaled(decoded, MAX_PHOTO_PIXELS), checkNotNull(photos).lens)
                view.depthFrom(cleanPositions)
                var seen = false
                faces.forEachIndexed { f, face -> if (colour(face, view, faceAcc[f])) seen = true }
                if (objectIndices.isNotEmpty()) colourPoints(objectPositions, objectNormals, view, objectAcc)
                if (seen) used++
            }
            progress(PROGRESS_NEIGHBOURS + (PROGRESS_PHOTOS - PROGRESS_NEIGHBOURS) * (index + 1) / all.size)
        }
        val photosMs = since(photosStarted)

        val finishStarted = System.nanoTime()
        coroutineScope {
            faces.mapIndexed { f, face -> async(Dispatchers.Default) { finish(face, faceAcc[f]) } }.awaitAll()
        }
        var recoloured = 0
        val objectColors = IntArray(objectIndices.size) { k ->
            val w = objectAcc.weight[k]
            if (w > 0f) {
                recoloured++
                rgb(objectAcc.rgb[k * 3] / w, objectAcc.rgb[k * 3 + 1] / w, objectAcc.rgb[k * 3 + 2] / w)
            } else {
                cloud.colors[objectIndices[k]]
            }
        }
        val texels = faces.sumOf { it.columns * it.rows }
        val seenTexels = faces.sumOf { (it.seenShare * it.columns * it.rows).toDouble() }
        progress(1f)
        return RoomShell(
            box = box,
            faces = faces,
            objects = DenseCloud(objectPositions, objectColors, objectNormals),
            stats = RoomShellStats(
                points = n,
                cleanPoints = cleanPositions.size / 3,
                objectPoints = objectIndices.size,
                objectsRecoloured = recoloured,
                photosIn = all.size,
                photosUsed = used,
                texels = texels,
                seenShare = (seenTexels / texels.coerceAtLeast(1)).toFloat(),
                boxMs = boxMs,
                neighboursMs = neighboursMs,
                photosMs = photosMs,
                finishMs = since(finishStarted),
            ),
        )
    }

    /**
     * The floor and the four walls of [box], each facing into the room, in world space: named as
     * the prototype names them (`wx0` the wall at the low end of the room's first axis).
     */
    fun faces(box: RoomBox): List<ShellFace> {
        val a = floatArrayOf(box.worldX(1f, 0f), 0f, box.worldZ(1f, 0f))
        val b = floatArrayOf(box.worldX(0f, 1f), 0f, box.worldZ(0f, 1f))
        val up = floatArrayOf(0f, 1f, 0f)
        fun corner(ra: Float, rb: Float) = floatArrayOf(box.worldX(ra, rb), box.floorY, box.worldZ(ra, rb))
        fun neg(x: FloatArray) = FloatArray(3) { -x[it] }
        fun face(name: String, origin: FloatArray, u: FloatArray, v: FloatArray, lu: Float, lv: Float, normal: FloatArray): ShellFace {
            val texel = max(TEXEL_M, max(lu, lv) / MAX_TEXTURE_PX)
            return ShellFace(
                name, origin, u, v, lu, lv, normal,
                columns = (lu / texel).roundToInt().coerceAtLeast(1),
                rows = (lv / texel).roundToInt().coerceAtLeast(1),
            )
        }
        return listOf(
            face("floor", corner(box.a0, box.b0), a, b, box.width, box.depth, up),
            face("wx0", corner(box.a0, box.b0), b, up, box.depth, box.height, a),
            face("wx1", corner(box.a1, box.b0), b, up, box.depth, box.height, neg(a)),
            face("wz0", corner(box.a0, box.b0), a, up, box.width, box.height, b),
            face("wz1", corner(box.a0, box.b1), a, up, box.width, box.height, neg(b)),
        )
    }

    /**
     * The points that stand in the room: cleaned, [OBJECT_MIN_NEIGHBOURS] within
     * [OBJECT_RADIUS_M], inside the box by its margins, and not a wall's — a point facing
     * sideways within [WALL_BAND_M] of one.
     */
    private fun objects(cloud: DenseCloud, normals: FloatArray, box: RoomBox, clean: BooleanArray, wide: IntArray): IntArray {
        val p = cloud.positions
        val out = IntList(cloud.count / 4 + 1)
        for (i in 0 until cloud.count) {
            if (!clean[i] || wide[i] < OBJECT_MIN_NEIGHBOURS) continue
            val x = p[i * 3]
            val y = p[i * 3 + 1]
            val z = p[i * 3 + 2]
            val a = box.a(x, z)
            val b = box.b(x, z)
            val toWall = minOf(a - box.a0, box.a1 - a, b - box.b0, box.b1 - b)
            if (abs(normals[i * 3 + 1]) < WALL_MAX_NY && toWall < WALL_BAND_M) continue
            if (toWall <= OBJECT_WALL_MARGIN_M) continue
            if (y <= box.floorY + OBJECT_FLOOR_MARGIN_M || y >= box.ceilingY - OBJECT_CEILING_MARGIN_M) continue
            out.add(i)
        }
        return out.toArray()
    }

    /** Per point, how many points (itself included) lie within [radius] of it: a voxel hash of [radius] cells. */
    suspend fun neighbourCounts(positions: FloatArray, radius: Float): IntArray {
        val n = positions.size / 3
        val cells = HashMap<Long, IntList>()
        for (i in 0 until n) {
            cells.getOrPut(key(positions, i, radius)) { IntList(CELL_CAPACITY) }.add(i)
        }
        val counts = IntArray(n)
        val r2 = radius * radius
        bands(n) { from, to ->
            for (i in from until to) {
                val x = positions[i * 3]
                val y = positions[i * 3 + 1]
                val z = positions[i * 3 + 2]
                val cx = floor(x / radius).toLong()
                val cy = floor(y / radius).toLong()
                val cz = floor(z / radius).toLong()
                var count = 0
                for (dz in -1..1) for (dy in -1..1) for (dx in -1..1) {
                    val list = cells[pack(cx + dx, cy + dy, cz + dz)] ?: continue
                    for (j in 0 until list.size) {
                        val o = list[j] * 3
                        val ex = positions[o] - x
                        val ey = positions[o + 1] - y
                        val ez = positions[o + 2] - z
                        if (ex * ex + ey * ey + ez * ez <= r2) count++
                    }
                }
                counts[i] = count
            }
        }
        return counts
    }

    /** Colours from [view] summed into [acc] for each texel of [face] it sees; whether it saw one. */
    private suspend fun colour(face: ShellFace, view: PhotoView, acc: Accumulator): Boolean {
        // The camera behind the face's plane sees none of it.
        val toPlane = (face.origin[0] - view.x) * face.normal[0] + (face.origin[1] - view.y) * face.normal[1] +
            (face.origin[2] - view.z) * face.normal[2]
        if (toPlane >= 0f) return false
        val blocksU = (face.columns + BLOCK - 1) / BLOCK
        val blocksV = (face.rows + BLOCK - 1) / BLOCK
        val corner = FloatArray(3)
        val kept = IntList(blocksU * blocksV)
        for (bv in 0 until blocksV) for (bu in 0 until blocksU) {
            val s0 = bu * BLOCK * face.texelU
            val s1 = min(face.columns, (bu + 1) * BLOCK) * face.texelU
            val t0 = bv * BLOCK * face.texelV
            val t1 = min(face.rows, (bv + 1) * BLOCK) * face.texelV
            var outside = ALL_SIDES
            for (k in 0 until 4) {
                face.at(if (k and 1 == 0) s0 else s1, if (k < 2) t0 else t1, corner)
                outside = outside and view.outsideSides(corner[0], corner[1], corner[2])
            }
            if (outside == 0) kept.add(bv * blocksU + bu)
        }
        if (kept.size == 0) return false
        var any = false
        bands(kept.size) { from, to ->
            val x = FloatArray(3)
            val sample = FloatArray(3)
            var seen = false
            for (k in from until to) {
                val block = kept[k]
                val bu = block % blocksU
                val bv = block / blocksU
                for (j in bv * BLOCK until min(face.rows, (bv + 1) * BLOCK)) {
                    for (i in bu * BLOCK until min(face.columns, (bu + 1) * BLOCK)) {
                        face.at((i + HALF) * face.texelU, (j + HALF) * face.texelV, x)
                        val w = view.weigh(x[0], x[1], x[2], face.normal[0], face.normal[1], face.normal[2], FACE_OCCLUSION_M, sample)
                        if (w <= 0f) continue
                        acc.add(j * face.columns + i, w, sample)
                        seen = true
                    }
                }
            }
            if (seen) any = true
        }
        return any
    }

    /** Colours from [view] summed into [acc] for each of the points it sees. */
    private suspend fun colourPoints(positions: FloatArray, normals: FloatArray, view: PhotoView, acc: Accumulator) {
        bands(positions.size / 3) { from, to ->
            val sample = FloatArray(3)
            for (k in from until to) {
                val o = k * 3
                val w = view.weigh(
                    positions[o], positions[o + 1], positions[o + 2],
                    normals[o], normals[o + 1], normals[o + 2], OBJECT_OCCLUSION_M, sample,
                )
                if (w > 0f) acc.add(k, w, sample)
            }
        }
    }

    /** [face]'s texture from its sums: the mean where seen, the nearest seen texel elsewhere, then a 3 × 3 median. */
    private fun finish(face: ShellFace, acc: Accumulator) {
        val w = face.columns
        val h = face.rows
        val n = w * h
        val rgb = FloatArray(n * 3)
        val seen = BooleanArray(n)
        var seenCount = 0
        for (i in 0 until n) {
            val weight = acc.weight[i]
            if (weight > 0f) {
                seen[i] = true
                seenCount++
                for (c in 0 until 3) rgb[i * 3 + c] = acc.rgb[i * 3 + c] / weight
            }
        }
        if (seenCount == 0) {
            rgb.fill(UNSEEN_GREY)
        } else if (seenCount < n) {
            val nearest = TexelFill.nearest(seen, w, h)
            for (i in 0 until n) if (!seen[i]) for (c in 0 until 3) rgb[i * 3 + c] = rgb[nearest[i] * 3 + c]
        }
        val smooth = TexelFill.median3(rgb, w, h)
        val argb = IntArray(n)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        for (j in 0 until h) {
            val row = h - 1 - j
            for (i in 0 until w) {
                val t = (j * w + i) * 3
                argb[row * w + i] = rgb(smooth[t], smooth[t + 1], smooth[t + 2])
                r += smooth[t]
                g += smooth[t + 1]
                b += smooth[t + 2]
            }
        }
        face.argb = argb
        face.seenShare = seenCount.toFloat() / n
        face.meanColor = rgb((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat())
    }

    /** Runs [work] over `0 until count` in one band per core, at most [MAX_BANDS]. */
    private suspend fun bands(count: Int, work: (from: Int, to: Int) -> Unit) {
        if (count == 0) return
        val n = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_BANDS).coerceAtMost(count)
        if (n == 1) return work(0, count)
        coroutineScope {
            (0 until n).map { k -> async(Dispatchers.Default) { work(k * count / n, (k + 1) * count / n) } }.awaitAll()
        }
    }

    private fun key(p: FloatArray, i: Int, cell: Float): Long =
        pack(floor(p[i * 3] / cell).toLong(), floor(p[i * 3 + 1] / cell).toLong(), floor(p[i * 3 + 2] / cell).toLong())

    private fun pack(x: Long, y: Long, z: Long): Long =
        ((x and KEY_MASK) shl (2 * KEY_BITS)) or ((y and KEY_MASK) shl KEY_BITS) or (z and KEY_MASK)

    private fun rgb(r: Float, g: Float, b: Float): Int =
        PhotoTexturing.rgb((r + HALF).toInt(), (g + HALF).toInt(), (b + HALF).toInt())

    private fun since(start: Long): Long = (System.nanoTime() - start) / NANOS_PER_MS

    /** A texel: a centimetre, the photos' own resolution at the distance a room is scanned from. */
    const val TEXEL_M = 0.01f

    /** The widest a face's texture is: past it, its texels grow (a 20 m wall). */
    const val MAX_TEXTURE_PX = 2048

    /** A photo's pixels at most: a bigger one is box-filtered down first. */
    const val MAX_PHOTO_PIXELS = 640L * 480L

    const val CLEAN_RADIUS_M = 0.05f
    const val CLEAN_MIN_NEIGHBOURS = 14
    const val MIN_CONFIDENCE = 200
    const val OBJECT_RADIUS_M = 0.08f
    const val OBJECT_MIN_NEIGHBOURS = 45

    /** How far inside the walls, over the floor and under the ceiling an object point is. */
    const val OBJECT_WALL_MARGIN_M = 0.07f
    const val OBJECT_FLOOR_MARGIN_M = 0.08f
    const val OBJECT_CEILING_MARGIN_M = 0.07f

    /** A point facing sideways (normal Y under [WALL_MAX_NY]) this near a wall is the wall's. */
    const val WALL_BAND_M = 0.22f
    const val WALL_MAX_NY = 0.5f

    /** How far behind the cleaned cloud's depth a texel, or an object point, still counts as seen. */
    const val FACE_OCCLUSION_M = 0.18f
    const val OBJECT_OCCLUSION_M = 0.08f

    private const val FULL_CONFIDENCE = 255
    private const val UNSEEN_GREY = 128f
    private const val BLOCK = 32
    private const val ALL_SIDES = 0x3F
    private const val HALF = 0.5f
    private const val MAX_BANDS = 8
    private const val CELL_CAPACITY = 8
    private const val KEY_BITS = 21
    private const val KEY_MASK = (1L shl KEY_BITS) - 1
    private const val NANOS_PER_MS = 1_000_000L
    private const val PROGRESS_NEIGHBOURS = 0.1f
    private const val PROGRESS_PHOTOS = 0.9f
}

/** Weighted colour sums, one slot per texel or point. Each slot is written by one band only. */
private class Accumulator(size: Int) {
    val rgb = FloatArray(size * 3)
    val weight = FloatArray(size)

    fun add(i: Int, w: Float, sample: FloatArray) {
        rgb[i * 3] += sample[0] * w
        rgb[i * 3 + 1] += sample[1] * w
        rgb[i * 3 + 2] += sample[2] * w
        weight[i] += w
    }
}

/**
 * One key photo as the shell reads it: its camera through [lens] at [pose] — the mapping
 * [PhotoTexturing.Camera] uses, so a texture lands where the replay draws the photo — its pixels,
 * and the depth of the cleaned cloud it sees, at half its resolution, for occlusion. Thread-safe
 * once [depthFrom] is done: nothing in it changes after.
 */
private class PhotoView(pose: DebugPose, val photo: BakePhoto, lens: ReplayLens) {
    val x = pose.x
    val y = pose.y
    val z = pose.z
    private val halfWidth = lens.halfWidthPerDepth
    private val halfHeight = lens.halfHeightPerDepth
    private val width = photo.width
    private val height = photo.height
    private val depthWidth = max(1, width / 2)
    private val depthHeight = max(1, height / 2)
    private var depth = FloatArray(0)

    // World to camera: the transpose of the pose's rotation.
    private val m: FloatArray = run {
        val qx = pose.qx
        val qy = pose.qy
        val qz = pose.qz
        val qw = pose.qw
        val r00 = 1 - 2 * (qy * qy + qz * qz)
        val r01 = 2 * (qx * qy - qz * qw)
        val r02 = 2 * (qx * qz + qy * qw)
        val r10 = 2 * (qx * qy + qz * qw)
        val r11 = 1 - 2 * (qx * qx + qz * qz)
        val r12 = 2 * (qy * qz - qx * qw)
        val r20 = 2 * (qx * qz - qy * qw)
        val r21 = 2 * (qy * qz + qx * qw)
        val r22 = 1 - 2 * (qx * qx + qy * qy)
        floatArrayOf(r00, r10, r20, r01, r11, r21, r02, r12, r22)
    }

    /** The cleaned cloud's nearest depth per half-resolution pixel, spread over its 3 × 3 neighbours. */
    fun depthFrom(points: FloatArray) {
        val raw = FloatArray(depthWidth * depthHeight) { Float.POSITIVE_INFINITY }
        for (i in 0 until points.size / 3) {
            val lx = points[i * 3] - x
            val ly = points[i * 3 + 1] - y
            val lz = points[i * 3 + 2] - z
            val d = -(m[6] * lx + m[7] * ly + m[8] * lz)
            if (d <= DEPTH_NEAR_M) continue
            val px = ((m[0] * lx + m[1] * ly + m[2] * lz) / d / halfWidth + 1f) / 2f * depthWidth
            val py = (1f - (m[3] * lx + m[4] * ly + m[5] * lz) / d / halfHeight) / 2f * depthHeight
            if (px < 0f || py < 0f || px >= depthWidth || py >= depthHeight) continue
            val at = py.toInt() * depthWidth + px.toInt()
            if (d < raw[at]) raw[at] = d
        }
        depth = FloatArray(raw.size) { at ->
            val cx = at % depthWidth
            val cy = at / depthWidth
            var nearest = Float.POSITIVE_INFINITY
            for (dy in -1..1) for (dx in -1..1) {
                val sx = (cx + dx).coerceIn(0, depthWidth - 1)
                val sy = (cy + dy).coerceIn(0, depthHeight - 1)
                nearest = min(nearest, raw[sy * depthWidth + sx])
            }
            nearest
        }
    }

    /**
     * The sides of the view volume — near, far, left, right, bottom, top — the world point
     * ([wx], [wy], [wz]) lies outside of, one bit each: a convex patch all of whose corners lie
     * outside the same side is out of the photo.
     */
    fun outsideSides(wx: Float, wy: Float, wz: Float): Int {
        val lx = wx - x
        val ly = wy - y
        val lz = wz - z
        val cx = m[0] * lx + m[1] * ly + m[2] * lz
        val cy = m[3] * lx + m[4] * ly + m[5] * lz
        val d = -(m[6] * lx + m[7] * ly + m[8] * lz)
        var out = 0
        if (d < MIN_DEPTH_M) out = out or 1
        if (d > MAX_DEPTH_M) out = out or 2
        if (cx < -halfWidth * d) out = out or 4
        if (cx > halfWidth * d) out = out or 8
        if (cy < -halfHeight * d) out = out or 16
        if (cy > halfHeight * d) out = out or 32
        return out
    }

    /**
     * How much this photo's colour of the world point ([wx], [wy], [wz]) on a surface facing
     * ([nx], [ny], [nz]) counts — `(cos / distance² · border)^1.5` — its colour then in [out]
     * (0–255 floats); `0` when the photo does not see it: off the picture, nearer than
     * [MIN_DEPTH_M] or farther than [MAX_DEPTH_M], seen edge-on, or hidden more than [tolerance]
     * behind the cleaned cloud.
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun weigh(wx: Float, wy: Float, wz: Float, nx: Float, ny: Float, nz: Float, tolerance: Float, out: FloatArray): Float {
        val lx = wx - x
        val ly = wy - y
        val lz = wz - z
        val d = -(m[6] * lx + m[7] * ly + m[8] * lz)
        if (d <= MIN_DEPTH_M || d >= MAX_DEPTH_M) return 0f
        val px = ((m[0] * lx + m[1] * ly + m[2] * lz) / d / halfWidth + 1f) / 2f * width
        val py = (1f - (m[3] * lx + m[4] * ly + m[5] * lz) / d / halfHeight) / 2f * height
        val fx = px - HALF
        val fy = py - HALF
        if (fx < 1f || fy < 1f || fx >= width - 2 || fy >= height - 2) return 0f
        val distance = sqrt(lx * lx + ly * ly + lz * lz)
        val cos = -(lx * nx + ly * ny + lz * nz) / distance
        if (cos <= MIN_COS) return 0f
        val dx = (px * depthWidth / width).toInt().coerceIn(0, depthWidth - 1)
        val dy = (py * depthHeight / height).toInt().coerceIn(0, depthHeight - 1)
        if (d >= depth[dy * depthWidth + dx] + tolerance) return 0f
        val border = min(
            min(fx, width - 1 - fx) / (width * BORDER_SHARE),
            min(fy, height - 1 - fy) / (height * BORDER_SHARE),
        ).coerceIn(MIN_BORDER, 1f)
        val base = cos / (distance * distance) * border
        bilinear(fx, fy, out)
        return base * sqrt(base)
    }

    private fun bilinear(fx: Float, fy: Float, out: FloatArray) {
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val ax = fx - x0
        val ay = fy - y0
        val argb = photo.argb
        val c00 = argb[y0 * width + x0]
        val c10 = argb[y0 * width + x0 + 1]
        val c01 = argb[(y0 + 1) * width + x0]
        val c11 = argb[(y0 + 1) * width + x0 + 1]
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            val top = (c00 shr shift and 0xFF) * (1f - ax) + (c10 shr shift and 0xFF) * ax
            val bottom = (c01 shr shift and 0xFF) * (1f - ax) + (c11 shr shift and 0xFF) * ax
            out[c] = top * (1f - ay) + bottom * ay
        }
    }

    private companion object {
        const val DEPTH_NEAR_M = 0.15f
        const val MIN_DEPTH_M = 0.25f
        const val MAX_DEPTH_M = 4.5f
        const val MIN_COS = 0.2f
        const val BORDER_SHARE = 0.15f
        const val MIN_BORDER = 0.05f
        const val HALF = 0.5f
    }
}

/** A texture's holes and speckle: filled from the nearest seen texel, then median-filtered. */
internal object TexelFill {
    /**
     * For each texel of a [w] × [h] grid, the index of the nearest [seen] one (itself when seen):
     * two chamfer passes carrying each texel's nearest seed, within a texel of exact. Needs one seen.
     */
    fun nearest(seen: BooleanArray, w: Int, h: Int): IntArray {
        val n = w * h
        val seed = IntArray(n) { if (seen[it]) it else -1 }
        val dist = FloatArray(n) { if (seen[it]) 0f else Float.POSITIVE_INFINITY }
        fun relax(i: Int, x: Int, y: Int, nx: Int, ny: Int) {
            if (nx < 0 || ny < 0 || nx >= w || ny >= h) return
            val s = seed[ny * w + nx]
            if (s < 0) return
            val ex = (s % w - x).toFloat()
            val ey = (s / w - y).toFloat()
            val d = ex * ex + ey * ey
            if (d < dist[i]) {
                dist[i] = d
                seed[i] = s
            }
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            relax(i, x, y, x - 1, y)
            relax(i, x, y, x - 1, y - 1)
            relax(i, x, y, x, y - 1)
            relax(i, x, y, x + 1, y - 1)
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            relax(i, x, y, x + 1, y)
            relax(i, x, y, x + 1, y + 1)
            relax(i, x, y, x, y + 1)
            relax(i, x, y, x - 1, y + 1)
        }
        // A third pass catches what the first two left (a seed reached round a corner).
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            relax(i, x, y, x - 1, y)
            relax(i, x, y, x, y - 1)
            relax(i, x, y, x - 1, y + 1)
            relax(i, x, y, x + 1, y - 1)
        }
        return IntArray(n) { if (seed[it] >= 0) seed[it] else it }
    }

    /** Each channel of the [w] × [h] RGB grid [rgb] through a 3 × 3 median, its edge texels repeated. */
    fun median3(rgb: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(rgb.size)
        val window = FloatArray(WINDOW)
        for (y in 0 until h) for (x in 0 until w) {
            for (c in 0 until 3) {
                var k = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val sx = (x + dx).coerceIn(0, w - 1)
                    val sy = (y + dy).coerceIn(0, h - 1)
                    window[k++] = rgb[(sy * w + sx) * 3 + c]
                }
                window.sort()
                out[(y * w + x) * 3 + c] = window[WINDOW / 2]
            }
        }
        return out
    }

    private const val WINDOW = 9
}
