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
                    total += camera.score(p, rect.normal)
                }
            }
            if (total > 0f) scored += index to total
        }
        return scored.sortedByDescending { it.second }.take(max).map { it.first }
    }

    /**
     * The texture of [rect], [width] × [height] `0xFFRRGGBB` texels, row 0 at `v = 0` — each texel
     * taken from the photo that sees it best (squarest, closest, nearest its centre). A texel no
     * photo saw takes the mean of the ones that were seen, so the plane reads as one surface.
     * `null` when no photo saw any of it.
     */
    fun bake(rect: PlaneRect, width: Int, height: Int, photos: List<BakePhoto>, lens: ReplayLens): IntArray? {
        val cameras = photos.map { PhotoCamera(it.pose, lens) }
        val out = IntArray(width * height)
        var seen = 0
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = rect.at((x + 0.5f) / width, (y + 0.5f) / height)
                val color = bestColor(p, rect.normal, cameras, photos)
                if (color == 0) continue
                out[y * width + x] = color
                seen++
                sumR += color shr 16 and 0xFF
                sumG += color shr 8 and 0xFF
                sumB += color and 0xFF
            }
        }
        if (seen == 0) return null
        val mean = opaque((sumR / seen).toInt(), (sumG / seen).toInt(), (sumB / seen).toInt())
        for (i in out.indices) if (out[i] == 0) out[i] = mean
        return out
    }

    /** The colour of [p] in the photo that sees it best, `0` when none does. */
    private fun bestColor(p: Vec3, normal: Vec3, cameras: List<PhotoCamera>, photos: List<BakePhoto>): Int {
        var best = 0
        var bestScore = 0f
        for (k in cameras.indices) {
            val camera = cameras[k]
            val score = camera.score(p, normal)
            val color = if (score > bestScore) camera.pixel(photos[k]) else 0
            if (color != 0 && !isOutsideSensor(color)) {
                best = color
                bestScore = score
            }
        }
        return best
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
    private class PhotoCamera(private val pose: DebugPose, private val lens: ReplayLens) {
        private val inverse = DebugPose(0f, 0f, 0f, -pose.qx, -pose.qy, -pose.qz, pose.qw)
        private var lastX = 0f
        private var lastY = 0f

        /**
         * How well this camera sees [p] on a plane facing [normal]: squarer, closer and nearer the
         * picture's centre score higher; `0` when [p] is behind it, off the picture, or seen from
         * behind the plane. Leaves where [p] lands for [pixel].
         */
        fun score(p: Vec3, normal: Vec3): Float {
            val d = p - pose.position
            val local = inverse.rotate(d.x, d.y, d.z)
            if (local.z > -NEAR_M) return 0f
            val x = local.x / -local.z / lens.halfWidthPerDepth
            val y = local.y / -local.z / lens.halfHeightPerDepth
            if (abs(x) > 1f || abs(y) > 1f) return 0f
            val distance = d.length()
            val facing = -d.dot(normal) / distance
            if (facing <= 0f) return 0f
            lastX = x
            lastY = y
            val centred = 1f - max(abs(x), abs(y))
            return facing * (EDGE_WEIGHT + centred) / distance
        }

        /** The pixel of [photo] where the last point [score] accepted lands. */
        fun pixel(photo: BakePhoto): Int {
            val px = ((lastX + 1f) / 2f * photo.width).toInt().coerceIn(0, photo.width - 1)
            val py = ((1f - lastY) / 2f * photo.height).toInt().coerceIn(0, photo.height - 1)
            return photo.argb[py * photo.width + px]
        }
    }
}
