package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.BakePhoto
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.ReplayLens
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A model's photo texture: which chart each face lies in, where its corners fall in the atlas,
 * and the atlas itself.
 */
internal class PhotoTexture(
    /** Per face, its chart; `-1` for a face no photo saw well: it keeps its vertex colours. */
    val chart: IntArray,
    /** Per corner (face × 3 + k): u then v in the atlas, origin at its top-left as glTF has it. */
    val uv: FloatArray,
    /** ARGB, rows top first. */
    val atlas: IntArray,
    val width: Int,
    val height: Int,
    val stats: PhotoTextureStats,
) {
    /**
     * This texture with its atlas half as fine, each texel the mean of the two by two it covers:
     * the corners' [uv] are fractions of the atlas, so they hold. A last-odd row or column folds
     * into the one before.
     */
    fun halved(): PhotoTexture {
        val w = maxOf(1, width / 2)
        val h = maxOf(1, height / 2)
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0
            var g = 0
            var b = 0
            var n = 0
            val y1 = if (y == h - 1) height else y * 2 + 2
            val x1 = if (x == w - 1) width else x * 2 + 2
            for (sy in y * 2 until y1) for (sx in x * 2 until x1) {
                val c = atlas[sy * width + sx]
                r += c shr 16 and 0xFF
                g += c shr 8 and 0xFF
                b += c and 0xFF
                n++
            }
            out[y * w + x] = PhotoTexturing.rgb(r / n, g / n, b / n)
        }
        return PhotoTexture(chart, uv, out, w, h, stats)
    }
}

/** What texturing found and did, for the log, the tests and the corpus report. */
internal data class PhotoTextureStats(
    val photosIn: Int,
    val photosUsed: Int,
    val charts: Int,
    /** The share of the surface's area a photo textures; the rest keeps vertex colours. */
    val texturedShare: Float,
    val gainMin: Float,
    val gainMax: Float,
    /**
     * Mean colour step (0–255) across chart seams, read at points along each seam edge, between
     * its vertices: the photos as taken, the atlas after exposure, the atlas after levelling.
     */
    val seamRaw: Float,
    val seamExposed: Float,
    val seamFinal: Float,
    /** Atlas texels per metre of surface, area-weighted. */
    val texelsPerMetre: Float,
)

/*
 * Texturing a cleaned mesh from the scan's key photos, on the phone, off the main thread:
 *
 * 1. Photos — at most [PhotoTexturing.MAX_PHOTOS], picked greedily for the surface they cover
 *    best, then decoded, brought within [PhotoTexturing.MAX_PHOTO_PIXELS], and graded for sharpness (variance of the Laplacian).
 * 2. Views — each photo's depth buffer (the mesh rasterised at a quarter of its pixels) tells what
 *    it really sees; each face keeps its best few views, scored by facing, distance, centring and
 *    sharpness, with its mean colour in each.
 * 3. Exposure — one gain per photo and channel, solved over the colours faces seen twice disagree
 *    on (least squares in log space, regularised towards no change).
 * 4. Labels — each face takes its best view, then a few passes let it follow its neighbours when
 *    their view is nearly as good: fewer, larger charts, fewer seams.
 * 5. Atlas — each chart's footprint in its photo is copied, gain applied, into a compact
 *    shelf-packed atlas; a colour offset diffused from the seams levels what remains.
 */
internal object PhotoTexturing {
    fun texture(mesh: WorkMesh, photos: ModelPhotos, progress: (Float) -> Unit = {}): PhotoTexture? {
        val faces = FaceGeometry(mesh.positions, mesh.indices)
        if (faces.count == 0 || photos.photos.isEmpty()) return null
        val chosen = choose(mesh, faces, photos)
        progress(PROGRESS_CHOSEN)
        val views = chosen.mapNotNull { i ->
            val photo = photos.photos[i]
            photo.load()?.takeIf { it.width > 1 && it.height > 1 }?.let { View(photo.pose, downscaled(it), photos.lens) }
        }
        if (views.isEmpty()) return null
        grade(views)
        val candidates = Candidates(faces.count)
        views.forEachIndexed { i, view ->
            see(mesh, faces, view, i, candidates)
            progress(PROGRESS_CHOSEN + (PROGRESS_SEEN - PROGRESS_CHOSEN) * (i + 1) / views.size)
        }
        val gains = exposure(faces, candidates, views.size)
        val topology = MeshTopology(mesh.vertexCount, mesh.indices)
        val label = labels(topology, candidates, faces.count)
        if (label.none { it >= 0 }) return null
        val built = TextureAtlas(mesh, faces, topology, views, gains, label) { progress(PROGRESS_SEEN) }.build()
        progress(1f)
        val (gainMin, gainMax) = gains.fold(Float.MAX_VALUE to 0f) { (lo, hi), g -> min(lo, g) to max(hi, g) }
        return PhotoTexture(
            chart = built.chart,
            uv = built.uv,
            atlas = built.pixels,
            width = built.width,
            height = built.height,
            stats = PhotoTextureStats(
                photosIn = photos.photos.size,
                photosUsed = label.filter { it >= 0 }.distinct().size,
                charts = built.charts,
                texturedShare = built.texturedArea / faces.areas.sum().coerceAtLeast(MIN_AREA),
                gainMin = gainMin,
                gainMax = gainMax,
                seamRaw = built.seamRaw,
                seamExposed = built.seamExposed,
                seamFinal = built.seamFinal,
                texelsPerMetre = built.texelsPerMetre,
            ),
        )
    }

    /**
     * The photos worth decoding: all of them up to [MAX_PHOTOS]; past that, picked one at a time
     * for the area they would see better than the photos already picked, from the poses alone.
     */
    private fun choose(mesh: WorkMesh, faces: FaceGeometry, photos: ModelPhotos): List<Int> {
        val all = photos.photos
        if (all.size <= MAX_PHOTOS) return all.indices.toList()
        val pool = if (all.size <= MAX_CANDIDATES) {
            all.indices.toList()
        } else {
            List(MAX_CANDIDATES) { it * all.size / MAX_CANDIDATES }
        }
        val stride = max(1, faces.count / SAMPLE_FACES)
        val samples = (0 until faces.count step stride).toList().toIntArray()
        val quality = Array(pool.size) { FloatArray(samples.size) }
        val good = (EDGE_WEIGHT + 1f) / (GOOD_DISTANCE_M * GOOD_DISTANCE_M)
        pool.forEachIndexed { pi, photoIndex ->
            val camera = Camera(all[photoIndex].pose, photos.lens, 1, 1)
            samples.forEachIndexed { si, f ->
                val score = camera.score(faces, f, mesh, insideMargin = 0f)
                quality[pi][si] = min(1f, score / good)
            }
        }
        val best = FloatArray(samples.size)
        val picked = ArrayList<Int>()
        val used = BooleanArray(pool.size)
        val total = samples.sumOf { faces.areas[it].toDouble() }
        while (picked.size < MAX_PHOTOS) {
            var bestGain = 0.0
            var bestPhoto = -1
            for (pi in pool.indices) {
                if (used[pi]) continue
                var gain = 0.0
                val q = quality[pi]
                for (si in samples.indices) {
                    val d = q[si] - best[si]
                    if (d > 0f) gain += d * faces.areas[samples[si]]
                }
                if (gain > bestGain) {
                    bestGain = gain
                    bestPhoto = pi
                }
            }
            if (bestPhoto < 0 || bestGain < total * MIN_PHOTO_GAIN) break
            used[bestPhoto] = true
            picked += pool[bestPhoto]
            val q = quality[bestPhoto]
            for (si in samples.indices) best[si] = max(best[si], q[si])
        }
        return picked
    }

    /**
     * [photo] within [MAX_PHOTO_PIXELS], by an integer box filter: the texture keeps up to
     * [MAX_PHOTOS] at once, so their bytes, not only their number, stay bounded whatever size the
     * recorder keeps them at (at most ~24 MB). A photo already that small is returned as is.
     */
    fun downscaled(photo: BakePhoto): BakePhoto {
        val pixels = photo.width.toLong() * photo.height
        if (pixels <= MAX_PHOTO_PIXELS) return photo
        val k = ceil(sqrt(pixels.toDouble() / MAX_PHOTO_PIXELS)).toInt()
        val w = max(1, photo.width / k)
        val h = max(1, photo.height / k)
        val argb = IntArray(w * h)
        val n = k * k
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0 until k) {
                    val row = (y * k + dy) * photo.width + x * k
                    for (dx in 0 until k) {
                        val c = photo.argb[row + dx]
                        r += c shr 16 and 0xFF
                        g += c shr 8 and 0xFF
                        b += c and 0xFF
                    }
                }
                argb[y * w + x] = rgb(r / n, g / n, b / n)
            }
        }
        return BakePhoto(photo.pose, w, h, argb)
    }

    /**
     * The part of an upright photo the sensor covered: `PlaneBake` frames the photo with the
     * lens, so the sensor's image fills it but for a strip a few pixels wide along an edge or
     * two, where the principal point sits off centre — written black. The trace keeps no sensor
     * intrinsics to place it, so the strip is read from the photo: whole rows or columns at an
     * edge, within [MAX_STRIP_SHARE] of it, that are all but entirely near-black. A black sofa or
     * a switched-off TV inside the picture is never cut, whatever its colour.
     */
    class SensorArea(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        /** Whether the bilinear footprint at pixel-centre ([fx], [fy]) lies on the sensor's image. */
        fun covers(fx: Float, fy: Float): Boolean =
            fx >= x0 && fy >= y0 && fx <= x1 && fy <= y1

        companion object {
            fun of(photo: BakePhoto): SensorArea {
                val w = photo.width
                val h = photo.height
                fun offRow(y: Int) =
                    (0 until w).count { outsideSensor(photo.argb[y * w + it]) } >= w * OFF_SENSOR_SHARE
                fun offColumn(x: Int) =
                    (0 until h).count { outsideSensor(photo.argb[it * w + x]) } >= h * OFF_SENSOR_SHARE
                val maxRows = (h * MAX_STRIP_SHARE).toInt()
                val maxColumns = (w * MAX_STRIP_SHARE).toInt()
                var top = 0
                while (top < maxRows && offRow(top)) top++
                var bottom = 0
                while (bottom < maxRows && offRow(h - 1 - bottom)) bottom++
                var left = 0
                while (left < maxColumns && offColumn(left)) left++
                var right = 0
                while (right < maxColumns && offColumn(w - 1 - right)) right++
                return SensorArea(left, top, w - 1 - right, h - 1 - bottom)
            }
        }
    }

    /** Sharpness: each photo's Laplacian variance against the median's, a weight in [MIN_SHARPNESS], 1. */
    private fun grade(views: List<View>) {
        val variance = views.map { laplacianVariance(it.photo, it.sensor) }
        val median = variance.sorted()[variance.size / 2].coerceAtLeast(MIN_AREA)
        views.forEachIndexed { i, view ->
            view.sharpness = sqrt(variance[i] / median).coerceIn(MIN_SHARPNESS, 1f)
        }
    }

    private fun laplacianVariance(photo: BakePhoto, sensor: SensorArea): Float {
        val w = photo.width
        val h = photo.height
        val luma = FloatArray(w * h) { luma(photo.argb[it]) }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in max(1, sensor.y0 + 1) until min(h - 1, sensor.y1)) {
            for (x in max(1, sensor.x0 + 1) until min(w - 1, sensor.x1)) {
                val i = y * w + x
                val l = 4f * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - w] - luma[i + w]
                sum += l
                sumSq += l * l
                n++
            }
        }
        if (n == 0) return 0f
        val mean = sum / n
        return (sumSq / n - mean * mean).toFloat()
    }

    /** Rasterises the mesh into [view]'s depth buffer, then scores and samples every face it sees. */
    private fun see(mesh: WorkMesh, faces: FaceGeometry, view: View, viewIndex: Int, candidates: Candidates) {
        val camera = view.camera
        val p = mesh.positions
        val n = mesh.vertexCount
        val bufW = max(1, view.photo.width / DEPTH_DOWNSAMPLE)
        val bufH = max(1, view.photo.height / DEPTH_DOWNSAMPLE)
        val sx = FloatArray(n)
        val sy = FloatArray(n)
        val iz = FloatArray(n)
        for (v in 0 until n) {
            if (camera.project(p[v * 3], p[v * 3 + 1], p[v * 3 + 2])) {
                sx[v] = (camera.x + 1f) / 2f * bufW
                sy[v] = (1f - camera.y) / 2f * bufH
                iz[v] = 1f / camera.z
            }
        }
        val depth = FloatArray(bufW * bufH)
        val idx = mesh.indices
        for (t in 0 until faces.count) {
            val a = idx[t * 3]
            val b = idx[t * 3 + 1]
            val c = idx[t * 3 + 2]
            if (iz[a] == 0f || iz[b] == 0f || iz[c] == 0f) continue
            rasterise(depth, bufW, bufH, sx, sy, iz, a, b, c)
        }
        view.depth = depth
        view.depthWidth = bufW
        view.depthHeight = bufH
        for (t in 0 until faces.count) {
            val a = idx[t * 3]
            val b = idx[t * 3 + 1]
            val c = idx[t * 3 + 2]
            if (iz[a] == 0f || iz[b] == 0f || iz[c] == 0f) continue
            val score = camera.score(faces, t, mesh, insideMargin = view.marginX) * view.sharpness
            if (score <= 0f) continue
            val color = view.faceColor(faces, t, mesh) ?: continue
            // A white wall a bright exposure burnt out has lost its colour: any unclipped view
            // of it is better, and no exposure gain brings the lost part back.
            val clipped = max(color shr 16 and 0xFF, max(color shr 8 and 0xFF, color and 0xFF)) > UNCLIPPED.last
            candidates.offer(t, viewIndex, if (clipped) score * CLIPPED_PENALTY else score, color)
        }
    }

    /** Nearest inverse depth per buffer pixel: the triangle's, where it covers a pixel centre. */
    private fun rasterise(
        depth: FloatArray,
        w: Int,
        h: Int,
        sx: FloatArray,
        sy: FloatArray,
        iz: FloatArray,
        a: Int,
        b: Int,
        c: Int,
    ) {
        val area = (sx[b] - sx[a]) * (sy[c] - sy[a]) - (sy[b] - sy[a]) * (sx[c] - sx[a])
        if (abs(area) < MIN_SCREEN_AREA) return
        val x0 = max(0, floor(min(sx[a], min(sx[b], sx[c]))).toInt())
        val x1 = min(w - 1, ceil(max(sx[a], max(sx[b], sx[c]))).toInt())
        val y0 = max(0, floor(min(sy[a], min(sy[b], sy[c]))).toInt())
        val y1 = min(h - 1, ceil(max(sy[a], max(sy[b], sy[c]))).toInt())
        if (x0 > x1 || y0 > y1) return
        val inv = 1f / area
        for (y in y0..y1) {
            val py = y + HALF
            for (x in x0..x1) {
                val px = x + HALF
                val wa = ((sx[b] - px) * (sy[c] - py) - (sy[b] - py) * (sx[c] - px)) * inv
                val wb = ((sx[c] - px) * (sy[a] - py) - (sy[c] - py) * (sx[a] - px)) * inv
                val wc = 1f - wa - wb
                if (wa < 0f || wb < 0f || wc < 0f) continue
                val z = wa * iz[a] + wb * iz[b] + wc * iz[c]
                val i = y * w + x
                if (z > depth[i]) depth[i] = z
            }
        }
    }

    /**
     * Per photo and channel, the gain that makes faces seen in two photos agree: least squares on
     * the log of their colours, each photo pulled towards no change, clamped to [MIN_GAIN]..[MAX_GAIN].
     * Returned as three gains a photo (r, g, b).
     */
    private fun exposure(faces: FaceGeometry, candidates: Candidates, views: Int): FloatArray {
        val gains = FloatArray(views * 3) { 1f }
        val from = ArrayList<Int>()
        val to = ArrayList<Int>()
        val weight = ArrayList<Float>()
        val ratio = ArrayList<Float>()
        for (f in 0 until faces.count) {
            val k = candidates.count[f]
            if (k < 2) continue
            val base = f * Candidates.K
            for (j in 1 until k) {
                for (channel in 0 until 3) {
                    val shift = 16 - 8 * channel
                    val ca = candidates.color[base] shr shift and 0xFF
                    val cb = candidates.color[base + j] shr shift and 0xFF
                    if (ca !in UNCLIPPED || cb !in UNCLIPPED) continue
                    from += candidates.view[base] * 3 + channel
                    to += candidates.view[base + j] * 3 + channel
                    weight += faces.areas[f]
                    ratio += ln(cb.toFloat() / ca)
                }
            }
        }
        if (from.isEmpty()) return gains
        // Least squares on x = ln(gain): Σ w (x[from] − x[to] − ratio)² + prior Σ own x², per
        // channel a dense system of one unknown per view (MAX_PHOTOS at most), solved exactly.
        val x = DoubleArray(views * 3)
        for (channel in 0 until 3) {
            val a = Array(views) { DoubleArray(views) }
            val b = DoubleArray(views)
            for (e in from.indices) {
                if (from[e] % 3 != channel) continue
                val i = from[e] / 3
                val j = to[e] / 3
                val w = weight[e].toDouble()
                a[i][i] += w
                a[j][j] += w
                a[i][j] -= w
                a[j][i] -= w
                b[i] += w * ratio[e]
                b[j] -= w * ratio[e]
            }
            var total = 0.0
            for (i in 0 until views) total += a[i][i]
            // A view no pair links keeps its gain (x = 0); the prior also fixes the overall level.
            for (i in 0 until views) a[i][i] += EXPOSURE_PRIOR * a[i][i] + total * EXPOSURE_FLOOR / views
            val solved = solve(a, b) ?: continue
            for (i in 0 until views) x[i * 3 + channel] = solved[i]
        }
        for (i in x.indices) gains[i] = exp(x[i]).toFloat().coerceIn(MIN_GAIN, MAX_GAIN)
        return gains
    }

    /** [a] x = [b] by Gaussian elimination with partial pivoting; `null` when singular. Both are overwritten. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (abs(a[pivot][col]) < SINGULAR) return null
            if (pivot != col) {
                val row = a[pivot]
                a[pivot] = a[col]
                a[col] = row
                val v = b[pivot]
                b[pivot] = b[col]
                b[col] = v
            }
            for (r in col + 1 until n) {
                val f = a[r][col] / a[col][col]
                if (f == 0.0) continue
                for (c in col until n) a[r][c] -= f * a[col][c]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var sum = b[r]
            for (c in r + 1 until n) sum -= a[r][c] * x[c]
            x[r] = sum / a[r][r]
        }
        return x
    }

    /** Each face's view: its best, then its neighbours' where theirs is nearly as good. */
    private fun labels(topology: MeshTopology, candidates: Candidates, faceCount: Int): IntArray {
        var label = IntArray(faceCount) { f -> if (candidates.count[f] > 0) candidates.view[f * Candidates.K] else -1 }
        repeat(LABEL_PASSES) {
            val next = label.copyOf()
            for (f in 0 until faceCount) {
                val count = candidates.count[f]
                if (count == 0) continue
                val n0 = topology.neighbours[f * 3]
                val n1 = topology.neighbours[f * 3 + 1]
                val n2 = topology.neighbours[f * 3 + 2]
                val l0 = if (n0 >= 0) label[n0] else -1
                val l1 = if (n1 >= 0) label[n1] else -1
                val l2 = if (n2 >= 0) label[n2] else -1
                val major = when {
                    l0 >= 0 && (l0 == l1 || l0 == l2) -> l0
                    l1 >= 0 && l1 == l2 -> l1
                    else -> -1
                }
                if (major < 0 || major == label[f]) continue
                val best = candidates.score[f * Candidates.K]
                for (j in 0 until count) {
                    val i = f * Candidates.K + j
                    if (candidates.view[i] == major && candidates.score[i] >= best * SWITCH_SHARE) {
                        next[f] = major
                        break
                    }
                }
            }
            label = next
        }
        return label
    }

    /** Each face's best few views, best first: which, how well, and the face's mean colour there. */
    private class Candidates(faces: Int) {
        val count = IntArray(faces)
        val view = IntArray(faces * K)
        val score = FloatArray(faces * K)
        val color = IntArray(faces * K)

        fun offer(f: Int, v: Int, s: Float, c: Int) {
            val base = f * K
            var n = count[f]
            if (n == K && score[base + K - 1] >= s) return
            if (n < K) n++
            var i = n - 1
            while (i > 0 && score[base + i - 1] < s) {
                view[base + i] = view[base + i - 1]
                score[base + i] = score[base + i - 1]
                color[base + i] = color[base + i - 1]
                i--
            }
            view[base + i] = v
            score[base + i] = s
            color[base + i] = c
            count[f] = n
        }

        companion object {
            const val K = 4
        }
    }

    /**
     * A camera through [lens] at [pose]: [project] puts a world point at normalised image
     * coordinates [x], [y] (−1..1, y up) and view depth [z] — `PlaneBake`'s own mapping, so a
     * texture lands where the replay draws the photo.
     */
    class Camera(private val pose: DebugPose, lens: ReplayLens, val width: Int, val height: Int) {
        private val qx = -pose.qx
        private val qy = -pose.qy
        private val qz = -pose.qz
        private val qw = pose.qw
        private val halfWidth = lens.halfWidthPerDepth
        private val halfHeight = lens.halfHeightPerDepth
        var x = 0f
            private set
        var y = 0f
            private set
        var z = 0f
            private set

        fun project(wx: Float, wy: Float, wz: Float): Boolean {
            val lx = wx - pose.x
            val ly = wy - pose.y
            val lz = wz - pose.z
            val tx = 2f * (qy * lz - qz * ly)
            val ty = 2f * (qz * lx - qx * lz)
            val tz = 2f * (qx * ly - qy * lx)
            val rx = lx + qw * tx + (qy * tz - qz * ty)
            val ry = ly + qw * ty + (qz * tx - qx * tz)
            val rz = lz + qw * tz + (qx * ty - qy * tx)
            z = -rz
            if (z < NEAR_M) return false
            x = rx / z / halfWidth
            y = ry / z / halfHeight
            return true
        }

        /** Pixel coordinates, edges at whole numbers, of the last [project]ed point. */
        val pixelX: Float get() = (x + 1f) / 2f * width
        val pixelY: Float get() = (1f - y) / 2f * height

        /**
         * How well this camera sees face [t]: squarer, closer and more central is better; `0` when a
         * corner is behind it or off the picture (by [insideMargin], normalised), or the face is
         * seen more edge-on than [MIN_FACING]. Occlusion is the caller's.
         */
        fun score(faces: FaceGeometry, t: Int, mesh: WorkMesh, insideMargin: Float): Float {
            val cx = faces.centroids[t * 3]
            val cy = faces.centroids[t * 3 + 1]
            val cz = faces.centroids[t * 3 + 2]
            val dx = cx - pose.x
            val dy = cy - pose.y
            val dz = cz - pose.z
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            if (distance < NEAR_M) return 0f
            val facing = -(dx * faces.normals[t * 3] + dy * faces.normals[t * 3 + 1] + dz * faces.normals[t * 3 + 2]) / distance
            if (facing < MIN_FACING) return 0f
            val limit = 1f - insideMargin
            val p = mesh.positions
            for (k in 0 until 3) {
                val v = mesh.indices[t * 3 + k]
                if (!project(p[v * 3], p[v * 3 + 1], p[v * 3 + 2])) return 0f
                if (abs(x) > limit || abs(y) > limit) return 0f
            }
            if (!project(cx, cy, cz)) return 0f
            val centred = 1f - max(abs(x), abs(y))
            return facing * facing * (EDGE_WEIGHT + centred) / (distance * distance)
        }
    }

    /** A decoded photo, its camera, its sharpness weight and, once rasterised, its depth buffer. */
    class View(pose: DebugPose, val photo: BakePhoto, lens: ReplayLens) {
        val camera = Camera(pose, lens, photo.width, photo.height)
        val sensor = SensorArea.of(photo)
        var sharpness = 1f

        /** Two pixels of margin, normalised: bilinear sampling never leaves the picture. */
        val marginX = EDGE_PIXELS * 2f / photo.width
        var depth = FloatArray(0)
        var depthWidth = 0
        var depthHeight = 0

        /**
         * Face [t]'s mean colour in this photo, from its centroid and three points towards its
         * corners; `null` when one of them is hidden behind nearer surface or off the sensor.
         */
        fun faceColor(faces: FaceGeometry, t: Int, mesh: WorkMesh): Int? {
            val cx = faces.centroids[t * 3]
            val cy = faces.centroids[t * 3 + 1]
            val cz = faces.centroids[t * 3 + 2]
            var r = 0
            var g = 0
            var b = 0
            for (k in -1 until 3) {
                var px = cx
                var py = cy
                var pz = cz
                if (k >= 0) {
                    val v = mesh.indices[t * 3 + k]
                    px += (mesh.positions[v * 3] - cx) * PROBE_REACH
                    py += (mesh.positions[v * 3 + 1] - cy) * PROBE_REACH
                    pz += (mesh.positions[v * 3 + 2] - cz) * PROBE_REACH
                }
                if (!camera.project(px, py, pz) || hidden()) return null
                val fx = camera.pixelX - HALF
                val fy = camera.pixelY - HALF
                if (!sensor.covers(fx, fy)) return null
                val c = sample(photo, fx, fy)
                r += c shr 16 and 0xFF
                g += c shr 8 and 0xFF
                b += c and 0xFF
            }
            return rgb(r / PROBES, g / PROBES, b / PROBES)
        }

        /** Whether the last projected point lies behind what the depth buffer holds there. */
        private fun hidden(): Boolean {
            val bx = ((camera.x + 1f) / 2f * depthWidth).toInt().coerceIn(0, depthWidth - 1)
            val by = ((1f - camera.y) / 2f * depthHeight).toInt().coerceIn(0, depthHeight - 1)
            val nearest = depth[by * depthWidth + bx]
            if (nearest <= 0f) return false
            val front = 1f / nearest
            return camera.z > front + OCCLUSION_SLACK_M + OCCLUSION_SLACK_SHARE * front
        }
    }

    /** Bilinear sample at pixel-centre coordinates, clamped to the photo. */
    fun sample(photo: BakePhoto, fx: Float, fy: Float): Int {
        val x = fx.coerceIn(0f, photo.width - 1f)
        val y = fy.coerceIn(0f, photo.height - 1f)
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = min(x0 + 1, photo.width - 1)
        val y1 = min(y0 + 1, photo.height - 1)
        val c00 = photo.argb[y0 * photo.width + x0]
        val c10 = photo.argb[y0 * photo.width + x1]
        val c01 = photo.argb[y1 * photo.width + x0]
        val c11 = photo.argb[y1 * photo.width + x1]
        val ax = x - x0
        val ay = y - y0
        fun channel(shift: Int): Int {
            val top = (c00 shr shift and 0xFF) * (1f - ax) + (c10 shr shift and 0xFF) * ax
            val bottom = (c01 shr shift and 0xFF) * (1f - ax) + (c11 shr shift and 0xFF) * ax
            return (top * (1f - ay) + bottom * ay + HALF).toInt()
        }
        return rgb(channel(16), channel(8), channel(0))
    }

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun luma(c: Int): Float =
        LUMA_R * (c shr 16 and 0xFF) + LUMA_G * (c shr 8 and 0xFF) + LUMA_B * (c and 0xFF)

    /** Black as `PlaneBake` writes what the sensor did not cover, give or take the JPEG's noise. */
    private fun outsideSensor(c: Int): Boolean =
        (c shr 16 and 0xFF) <= OUTSIDE_SENSOR_LEVEL &&
            (c shr 8 and 0xFF) <= OUTSIDE_SENSOR_LEVEL &&
            (c and 0xFF) <= OUTSIDE_SENSOR_LEVEL

    /** Photos decoded at most: ~60 photos of 240 × 320 are 18 MB of pixels. */
    const val MAX_PHOTOS = 60

    /** A kept photo's pixels at most: 240 × 320 passes as is; 720 × 960 comes down to it. */
    const val MAX_PHOTO_PIXELS = 100_000L
    private const val OFF_SENSOR_SHARE = 0.98f
    private const val MAX_STRIP_SHARE = 0.05f
    private const val MAX_CANDIDATES = 400
    private const val SAMPLE_FACES = 6000
    private const val GOOD_DISTANCE_M = 1.5f
    private const val MIN_PHOTO_GAIN = 0.002
    private const val MIN_SHARPNESS = 0.3f
    private const val DEPTH_DOWNSAMPLE = 2
    private const val MIN_SCREEN_AREA = 1e-6f
    private const val NEAR_M = 0.05f
    private const val MIN_FACING = 0.25f
    private const val EDGE_WEIGHT = 0.25f
    private const val EDGE_PIXELS = 2f
    private const val PROBE_REACH = 0.75f
    private const val PROBES = 4
    private const val OCCLUSION_SLACK_M = 0.03f
    private const val OCCLUSION_SLACK_SHARE = 0.02f
    private val UNCLIPPED = 12..250
    private const val CLIPPED_PENALTY = 0.25f
    private const val EXPOSURE_PRIOR = 0.01
    private const val EXPOSURE_FLOOR = 1e-4
    private const val SINGULAR = 1e-12
    private const val MIN_GAIN = 0.5f
    private const val MAX_GAIN = 2f
    private const val LABEL_PASSES = 4
    private const val SWITCH_SHARE = 0.4f
    private const val OUTSIDE_SENSOR_LEVEL = 10
    private const val LUMA_R = 0.299f
    private const val LUMA_G = 0.587f
    private const val LUMA_B = 0.114f
    private const val MIN_AREA = 1e-9f
    private const val HALF = 0.5f
    private const val PROGRESS_CHOSEN = 0.1f
    private const val PROGRESS_SEEN = 0.7f
}
