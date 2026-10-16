package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * A room scan's planes, textured with the room: each plane gets a photo of itself painted from
 * the scan's own photos, the way the bundled replay's floor and wall carry theirs. That texture is
 * what makes a scan look like the sample rather than a wireframe of it. Pure Kotlin: the photos
 * come in as pixels, the texture goes out as pixels.
 */

/** One of the scan's photos: the camera at [pose] (display-oriented) saw these upright pixels. */
class BakePhoto(val pose: DebugPose, val width: Int, val height: Int, val argb: IntArray)

/**
 * Where a plane's texture lies: its first texel corner at [origin], [u] and [v] its full edges in
 * world space, [normal] the side it is seen from. The rectangle bounds the plane's polygon.
 */
class PlaneRect(val origin: Vec3, val u: Vec3, val v: Vec3, val normal: Vec3) {
    /** The world point at texture coordinates ([s], [t]). */
    fun at(s: Float, t: Float): Vec3 = origin + u * s + v * t
}

object PlaneBake {
    /** One texel per centimetre: the photos' own resolution at the distance a room is scanned from. */
    const val TEXEL_M = 0.01f
    const val MAX_SIDE = 512
    const val MIN_SIDE = 8

    /** Photos a plane is painted from: the ones that see most of it, most squarely, from closest. */
    const val MAX_PHOTOS = 12

    /** How much of a photo's centre is preferred to its edges, where lens and pose error grow. */
    private const val EDGE_WEIGHT = 0.35f

    /** Nothing nearer the lens than this is painted from it. */
    private const val NEAR_M = 0.05f

    /** A normal with this much of it vertical makes a plane of unknown kind a floor or a ceiling. */
    private const val HORIZONTAL_NORMAL_Y = 0.7f

    /** Grid a photo is scored on against a plane, per side. */
    private const val PROBE_GRID = 5

    /**
     * A photo is black where the sensor saw nothing (`ScanProjection.uprightImage`); after JPEG
     * that black is only nearly black, so every channel at or under this level counts as unseen.
     */
    private const val OUTSIDE_SENSOR_LEVEL = 10

    private fun isOutsideSensor(color: Int): Boolean =
        (color shr 16 and 0xFF) <= OUTSIDE_SENSOR_LEVEL &&
            (color shr 8 and 0xFF) <= OUTSIDE_SENSOR_LEVEL &&
            (color and 0xFF) <= OUTSIDE_SENSOR_LEVEL

    /**
     * The texture rectangle of [plane]. A floor or a ceiling runs along X and Z, like the bundled
     * replay's; a wall runs along its length and down its height, so the texture reads upright
     * from the side [viewpoint] (where the scan was walked) is on. `null` for a degenerate plane.
     */
    fun rectOf(plane: DebugPlane, viewpoint: Vec3? = null): PlaneRect? {
        val polygon = plane.polygon
        val n = polygon.size / 3
        if (n < 3) return null
        var normal = newellNormal(polygon)
        if (normal.length() < 1e-6f) return null
        val first = Vec3(polygon[0], polygon[1], polygon[2])
        if (viewpoint != null && (viewpoint - first).dot(normal) < 0f) normal = normal * -1f
        val horizontal = when (plane.kind) {
            DebugPlaneKind.Floor, DebugPlaneKind.Ceiling -> true
            DebugPlaneKind.Wall -> false
            DebugPlaneKind.Unknown -> abs(normal.y) > HORIZONTAL_NORMAL_Y
        }
        val (a, b) = if (horizontal) {
            Vec3(1f, 0f, 0f) to Vec3(0f, 0f, 1f)
        } else {
            val across = Vec3.Up.cross(normal).normalized()
            val down = normal.cross(across).normalized().let { if (it.y > 0f) it * -1f else it }
            across to down
        }
        var minA = Float.MAX_VALUE
        var maxA = -Float.MAX_VALUE
        var minB = Float.MAX_VALUE
        var maxB = -Float.MAX_VALUE
        for (i in 0 until n) {
            val d = Vec3(polygon[i * 3], polygon[i * 3 + 1], polygon[i * 3 + 2]) - first
            val pa = d.dot(a)
            val pb = d.dot(b)
            minA = min(minA, pa)
            maxA = max(maxA, pa)
            minB = min(minB, pb)
            maxB = max(maxB, pb)
        }
        if (maxA - minA < 1e-3f || maxB - minB < 1e-3f) return null
        return PlaneRect(first + a * minA + b * minB, a * (maxA - minA), b * (maxB - minB), normal)
    }

    /** Texture size for [rect]: [TEXEL_M] per texel, the long side capped at [MAX_SIDE]. */
    fun sizeOf(rect: PlaneRect): Pair<Int, Int> {
        val w = rect.u.length() / TEXEL_M
        val h = rect.v.length() / TEXEL_M
        val scale = min(1f, MAX_SIDE / max(w, h))
        fun side(extent: Float) = ceil(extent * scale).toInt().coerceIn(MIN_SIDE, MAX_SIDE)
        return side(w) to side(h)
    }

    /**
     * Indices of the [poses] to paint [rect] from, best first: each is scored on a grid over the
     * plane by how much of it the camera saw, how squarely and from how close. At most [max]; the
     * ones that see none of it are left out.
     */
    fun pickPhotos(rect: PlaneRect, poses: List<DebugPose>, lens: ReplayLens, max: Int = MAX_PHOTOS): List<Int> {
        val scored = ArrayList<Pair<Int, Float>>()
        poses.forEachIndexed { index, pose ->
            val camera = PhotoCamera(pose, lens)
            var total = 0f
            for (j in 0 until PROBE_GRID) {
                for (i in 0 until PROBE_GRID) {
                    val p = rect.at((i + 0.5f) / PROBE_GRID, (j + 0.5f) / PROBE_GRID)
                    total += camera.weight(p, rect.normal)
                }
            }
            if (total > 0f) scored += index to total
        }
        return scored.sortedByDescending { it.second }.take(max).map { it.first }
    }

    /**
     * The texture of [rect], drawn at [width] × [height] texels and cropped to what the [photos]
     * saw. Each texel is a blend of the photos that see it squarely enough, from close enough —
     * and, with [occlusion], that see it at all: a photo whose ray to the texel meets something
     * nearer (a dense point, another plane than [planeId]) does not paint it. The photos that
     * agree on its colour are blended, weighted by angle, distance and centring; one that sees
     * something else there (a sofa's arm the depth missed) is outvoted.
     *
     * What none of them saw is not invented: small gaps take their neighbours' colour, the rest
     * a neutral grey as light as the room, and the texture stops a margin past the seen area,
     * a neutral rim on each side it was cut on — so a polygon reaching past it reads neutral too.
     * `null` when no photo saw any of it.
     */
    fun bake(
        rect: PlaneRect,
        width: Int,
        height: Int,
        photos: List<BakePhoto>,
        lens: ReplayLens,
        occlusion: BakeOcclusion? = null,
        planeId: Int = NO_PLANE,
    ): BakedPlane? {
        val cameras = photos.map { PhotoCamera(it.pose, lens, it, occlusion?.viewOf(it.pose, lens)) }
        val blend = TexelBlend(cameras.size)
        val out = IntArray(width * height)
        val seen = BooleanArray(width * height)
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = rect.at((x + 0.5f) / width, (y + 0.5f) / height)
                val color = blend.colorOf(p, rect.normal, cameras, planeId)
                if (color == 0) continue
                out[y * width + x] = color
                seen[y * width + x] = true
                count++
            }
        }
        if (count == 0) return null
        return BakeFinish.of(rect, width, height, out, seen)
    }

    /** No plane: nothing is left out of the occlusion. */
    const val NO_PLANE = Int.MIN_VALUE

    /** Steepest view a texel is painted from: about 72° off its normal. */
    internal const val MIN_FACING = 0.3f

    /** A photo is blended in while its weight is at least this share of the strongest one's. */
    private const val BLEND_SHARE = 0.35f

    /** Two photos agree on a texel when no channel differs by more than this. */
    private const val AGREE_LEVEL = 40

    /**
     * Depth an occluder must stand nearer than the texel by, before it hides it: the dense
     * cloud's voxel and ARCore's depth noise, growing with distance and with how edge-on the
     * plane is seen.
     */
    private const val OCCLUDER_MIN_M = 0.03f
    private const val OCCLUDER_SHARE = 0.02f
    private const val OCCLUDER_MAX_M = 0.08f

    private fun agree(a: Int, b: Int): Boolean =
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) <= AGREE_LEVEL &&
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) <= AGREE_LEVEL &&
            abs((a and 0xFF) - (b and 0xFF)) <= AGREE_LEVEL

    /** One texel's candidates, reused texel to texel. */
    private class TexelBlend(capacity: Int) {
        private val colors = IntArray(capacity)
        private val weights = FloatArray(capacity)
        private var n = 0

        /** The blended colour of [p], `0` when no photo sees it. */
        fun colorOf(p: Vec3, normal: Vec3, cameras: List<PhotoCamera>, planeId: Int): Int {
            n = 0
            for (camera in cameras) {
                val weight = camera.weight(p, normal, planeId)
                val color = if (weight > 0f) camera.sample() else 0
                if (color == 0) continue
                colors[n] = color
                weights[n] = weight
                n++
            }
            return if (n == 0) 0 else mix(anchor())
        }

        /** The candidate the most weight agrees with. */
        private fun anchor(): Int {
            var best = 0
            var bestSupport = -1f
            for (i in 0 until n) {
                var support = 0f
                for (j in 0 until n) if (agree(colors[i], colors[j])) support += weights[j]
                if (support > bestSupport) {
                    best = i
                    bestSupport = support
                }
            }
            return best
        }

        /** The candidates agreeing with [anchor], the strong ones blended by their squared weight. */
        private fun mix(anchor: Int): Int {
            var strongest = 0f
            for (i in 0 until n) if (agree(colors[i], colors[anchor])) strongest = max(strongest, weights[i])
            var r = 0f
            var g = 0f
            var b = 0f
            var total = 0f
            for (i in 0 until n) {
                val w = weights[i]
                if (w < BLEND_SHARE * strongest || !agree(colors[i], colors[anchor])) continue
                val c = colors[i]
                r += (c shr 16 and 0xFF) * w * w
                g += (c shr 8 and 0xFF) * w * w
                b += (c and 0xFF) * w * w
                total += w * w
            }
            return opaque((r / total + 0.5f).toInt(), (g / total + 0.5f).toInt(), (b / total + 0.5f).toInt())
        }
    }

    private fun opaque(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Newell's normal of a planar polygon, unit length, or zero for a degenerate one. */
    private fun newellNormal(polygon: FloatArray): Vec3 {
        val n = polygon.size / 3
        var nx = 0f
        var ny = 0f
        var nz = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            val (xi, yi, zi) = Triple(polygon[i * 3], polygon[i * 3 + 1], polygon[i * 3 + 2])
            val (xj, yj, zj) = Triple(polygon[j * 3], polygon[j * 3 + 1], polygon[j * 3 + 2])
            nx += (yi - yj) * (zi + zj)
            ny += (zi - zj) * (xi + xj)
            nz += (xi - xj) * (yi + yj)
        }
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        return if (length < 1e-9f) Vec3.Zero else Vec3(nx / length, ny / length, nz / length)
    }

    /**
     * One photo's camera: where a world point lands on its upright picture — the inverse of the
     * frustum the replay draws the photo in (`ReplayGeometry.addImageQuad`), top row at the top.
     */
    private class PhotoCamera(
        private val pose: DebugPose,
        private val lens: ReplayLens,
        private val photo: BakePhoto? = null,
        private val depth: OcclusionView? = null,
    ) {
        private val inverse = DebugPose(0f, 0f, 0f, -pose.qx, -pose.qy, -pose.qz, pose.qw)
        private var lastX = 0f
        private var lastY = 0f

        /**
         * How much this camera's view of [p], on a plane facing [normal], counts: squarer,
         * closer and nearer the picture's centre count more; `0` when [p] is behind it, off the
         * picture, seen more edge-on than [MIN_FACING], or hidden by something nearer than it
         * (anything but the plane [planeId]). Leaves where [p] lands for [sample].
         */
        fun weight(p: Vec3, normal: Vec3, planeId: Int = NO_PLANE): Float {
            val d = p - pose.position
            val local = inverse.rotate(d.x, d.y, d.z)
            val z = -local.z
            if (z < NEAR_M) return 0f
            val x = local.x / z / lens.halfWidthPerDepth
            val y = local.y / z / lens.halfHeightPerDepth
            if (abs(x) > 1f || abs(y) > 1f) return 0f
            val distance = d.length()
            val facing = -d.dot(normal) / distance
            if (facing < MIN_FACING) return 0f
            if (depth != null) {
                val tolerance = min(OCCLUDER_MAX_M, max(OCCLUDER_MIN_M, OCCLUDER_SHARE * z) / facing)
                if (z > depth.depthAt(x, y, planeId) + tolerance) return 0f
            }
            lastX = x
            lastY = y
            val centred = 1f - max(abs(x), abs(y))
            return facing * facing * (EDGE_WEIGHT + centred) / (distance * distance)
        }

        /**
         * The photo's colour where the last point [weight] accepted lands, filtered between its
         * four nearest pixels; `0` where the sensor saw nothing.
         */
        fun sample(): Int {
            val photo = photo ?: return 0
            val fx = ((lastX + 1f) / 2f * photo.width - 0.5f).coerceIn(0f, photo.width - 1f)
            val fy = ((1f - lastY) / 2f * photo.height - 0.5f).coerceIn(0f, photo.height - 1f)
            val x0 = fx.toInt()
            val y0 = fy.toInt()
            val x1 = min(x0 + 1, photo.width - 1)
            val y1 = min(y0 + 1, photo.height - 1)
            val c00 = photo.argb[y0 * photo.width + x0]
            val c10 = photo.argb[y0 * photo.width + x1]
            val c01 = photo.argb[y1 * photo.width + x0]
            val c11 = photo.argb[y1 * photo.width + x1]
            if (intArrayOf(c00, c10, c01, c11).any { isOutsideSensor(it) }) return 0
            val ax = fx - x0
            val ay = fy - y0
            fun channel(shift: Int): Int {
                val top = (c00 shr shift and 0xFF) * (1f - ax) + (c10 shr shift and 0xFF) * ax
                val bottom = (c01 shr shift and 0xFF) * (1f - ax) + (c11 shr shift and 0xFF) * ax
                return (top * (1f - ay) + bottom * ay + 0.5f).toInt()
            }
            return opaque(channel(16), channel(8), channel(0))
        }
    }
}

/** A plane's painted texture: [argb] `0xFFRRGGBB`, [width] × [height] texels over [rect], row 0 at `v = 0`. */
class BakedPlane(val rect: PlaneRect, val width: Int, val height: Int, val argb: IntArray)

/** What a bake does with what it did not see: gaps closed, the rest neutral, the texture cropped. */
internal object BakeFinish {
    /** Texels of unseen margin kept past the seen area. */
    const val MARGIN = 4

    /** Passes closing small gaps: a gap up to twice this wide takes its neighbours' colour. */
    private const val GAP_PASSES = 3

    /** Seen neighbours (of eight) an unseen texel needs to take their colour. */
    private const val GAP_NEIGHBOURS = 3

    private const val LUMA_R = 0.299f
    private const val LUMA_G = 0.587f
    private const val LUMA_B = 0.114f

    fun of(rect: PlaneRect, width: Int, height: Int, argb: IntArray, seen: BooleanArray): BakedPlane {
        val neutral = neutralOf(argb, seen)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (i in seen.indices) {
            if (!seen[i]) continue
            minX = min(minX, i % width)
            maxX = max(maxX, i % width)
            minY = min(minY, i / width)
            maxY = max(maxY, i / width)
        }
        repeat(GAP_PASSES) { closeGaps(argb, seen, width, height) }
        for (i in argb.indices) if (!seen[i]) argb[i] = neutral
        val x0 = max(0, minX - MARGIN)
        val y0 = max(0, minY - MARGIN)
        val x1 = min(width, maxX + 1 + MARGIN)
        val y1 = min(height, maxY + 1 + MARGIN)
        val w = x1 - x0
        val h = y1 - y0
        val out = IntArray(w * h) { argb[(y0 + it / w) * width + x0 + it % w] }
        // A rim on each side the texture was cut on: the polygon past it samples that rim.
        for (y in 0 until h) {
            if (x0 > 0) out[y * w] = neutral
            if (x1 < width) out[y * w + w - 1] = neutral
        }
        for (x in 0 until w) {
            if (y0 > 0) out[x] = neutral
            if (y1 < height) out[(h - 1) * w + x] = neutral
        }
        val cropped = PlaneRect(
            rect.at(x0.toFloat() / width, y0.toFloat() / height),
            rect.u * (w.toFloat() / width),
            rect.v * (h.toFloat() / height),
            rect.normal,
        )
        return BakedPlane(cropped, w, h, out)
    }

    /** A grey as light as the seen texels: says "not seen" without a colour the room does not have. */
    private fun neutralOf(argb: IntArray, seen: BooleanArray): Int {
        var luma = 0.0
        var count = 0
        for (i in argb.indices) {
            if (!seen[i]) continue
            val c = argb[i]
            luma += LUMA_R * (c shr 16 and 0xFF) + LUMA_G * (c shr 8 and 0xFF) + LUMA_B * (c and 0xFF)
            count++
        }
        val level = (luma / max(1, count) + 0.5).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (level shl 16) or (level shl 8) or level
    }

    /** One pass: each unseen texel with [GAP_NEIGHBOURS] seen neighbours takes their mean. */
    private fun closeGaps(argb: IntArray, seen: BooleanArray, width: Int, height: Int) {
        val filled = ArrayList<Pair<Int, Int>>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (seen[y * width + x]) continue
                val mean = neighbourMean(argb, seen, width, height, x, y)
                if (mean != 0) filled += (y * width + x) to mean
            }
        }
        for ((i, color) in filled) {
            argb[i] = color
            seen[i] = true
        }
    }

    private fun neighbourMean(argb: IntArray, seen: BooleanArray, width: Int, height: Int, x: Int, y: Int): Int {
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (ny in max(0, y - 1)..min(height - 1, y + 1)) {
            for (nx in max(0, x - 1)..min(width - 1, x + 1)) {
                val i = ny * width + nx
                if (!seen[i]) continue
                r += argb[i] shr 16 and 0xFF
                g += argb[i] shr 8 and 0xFF
                b += argb[i] and 0xFF
                n++
            }
        }
        if (n < GAP_NEIGHBOURS) return 0
        return (0xFF shl 24) or (r / n shl 16) or (g / n shl 8) or (b / n)
    }
}
