package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.core.splat.SplatCloud
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One recording's surfels as the result's Photo preview draws them ([SurfelSplats]), off the
 * phone: writes `splats.png`, a dollhouse view of the splats composited back to front as the
 * splat material blends them, and `splats.json`, the conversion's figures. Colours are the
 * surfels' own (no tone-mapping compensation): the image is what the preview aims to show.
 */
internal object SurfelSplatsCorpus {
    const val IMAGE_PX = 900

    fun run(file: File, out: File): JsonObject {
        out.mkdirs()
        val stats = runCatching { measure(file, out) }.getOrElse { failure ->
            buildJsonObject {
                put("file", file.name)
                put("error", "${failure::class.simpleName}: ${failure.message}")
            }
        }
        File(out, "splats.json").writeText(stats.toString())
        return stats
    }

    private fun measure(file: File, out: File): JsonObject {
        val capture = checkNotNull(CleanModelCorpus.read(file).open()) { "unreadable manifest" }
        val cloud = CleanModelCorpus.denseCloud(capture) ?: return buildJsonObject {
            put("file", file.name)
            put("skipped", "no dense cloud: the scan has no surfels to splat")
        }
        val convertStart = System.nanoTime()
        val splats = SurfelSplats.of(cloud, linearView = false)
        val convertMs = (System.nanoTime() - convertStart) / NANOS_PER_MS
        val renderStart = System.nanoTime()
        val image = dollhouse(splats, IMAGE_PX)
        val renderMs = (System.nanoTime() - renderStart) / NANOS_PER_MS
        File(out, "splats.png").writeBytes(JvmImages.png(image.argb, IMAGE_PX, IMAGE_PX))
        val sigmas = (0 until splats.count).map { splats.scales[it * 3] }.sorted()
        return buildJsonObject {
            put("file", file.name)
            put("surfels", cloud.count)
            put("normals", cloud.normals != null)
            put("confidences", cloud.confidences != null)
            put("levels", cloud.levels != null)
            put("splats", splats.count)
            put("cap", SurfelSplats.MAX_SPLATS)
            put("convert_ms", convertMs)
            put("median_sigma_mm", if (sigmas.isEmpty()) 0f else sigmas[sigmas.size / 2] * MM_PER_M)
            put("mean_opacity", if (splats.count == 0) 0f else splats.opacities.average().toFloat())
            put("drawn", image.drawn)
            put("covered_share", image.coveredShare)
            put("render_ms", renderMs)
        }
    }

    class Image(val argb: IntArray, val drawn: Int, val coveredShare: Float)

    /**
     * [splats] seen from above a corner, orthographic, the top of the room cut away and the
     * splats facing away culled (a dollhouse), each an elliptical gaussian of its projected
     * covariance, blended back to front over a dark stage.
     */
    fun dollhouse(splats: SplatCloud, px: Int): Image {
        val n = splats.count
        val background = floatArrayOf(0.12f, 0.125f, 0.135f)
        val rgb = FloatArray(px * px * 3) { background[it % 3] }
        val alpha = FloatArray(px * px)
        if (n == 0) return Image(pack(rgb, px), 0, 0f)
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            minY = minOf(minY, splats.positions[i * 3 + 1])
            maxY = maxOf(maxY, splats.positions[i * 3 + 1])
        }
        val cutY = minY + CUT_SHARE * (maxY - minY)
        // Looking down from a corner: d is the view direction, right and up the screen's axes.
        val az = Math.toRadians(AZIMUTH_DEG).toFloat()
        val el = Math.toRadians(ELEVATION_DEG).toFloat()
        val d = floatArrayOf(-cos(el) * cos(az), -sin(el), -cos(el) * sin(az))
        val right = normalize(floatArrayOf(-d[2], 0f, d[0]))
        val up = cross(right, d)
        // Which way the surfels' normals face: towards the sensor, so a floor's point up.
        val normal = FloatArray(3)
        var floorUp = 0
        var floorDown = 0
        for (i in 0 until n) {
            normalOf(splats, i, normal)
            if (splats.positions[i * 3 + 1] < minY + 0.1f * (maxY - minY) && kotlin.math.abs(normal[1]) > 0.9f) {
                if (normal[1] > 0) floorUp++ else floorDown++
            }
        }
        val facing = if (floorDown > floorUp) -1f else 1f
        val kept = ArrayList<Int>(n)
        for (i in 0 until n) {
            if (splats.positions[i * 3 + 1] > cutY) continue
            normalOf(splats, i, normal)
            val towardsViewer = -(normal[0] * d[0] + normal[1] * d[1] + normal[2] * d[2]) * facing
            if (towardsViewer < BACKFACE) continue
            kept += i
        }
        if (kept.isEmpty()) return Image(pack(rgb, px), 0, 0f)
        val sx = FloatArray(n)
        val sy = FloatArray(n)
        val depth = FloatArray(n)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minS = Float.MAX_VALUE; var maxS = -Float.MAX_VALUE
        for (i in kept) {
            val p = floatArrayOf(splats.positions[i * 3], splats.positions[i * 3 + 1], splats.positions[i * 3 + 2])
            sx[i] = dot(p, right); sy[i] = dot(p, up); depth[i] = dot(p, d)
            minX = minOf(minX, sx[i]); maxX = maxOf(maxX, sx[i]); minS = minOf(minS, sy[i]); maxS = maxOf(maxS, sy[i])
        }
        val scale = (px * (1f - 2 * MARGIN)) / maxOf(maxX - minX, maxS - minS, 1e-3f)
        val ox = px / 2f - (minX + maxX) / 2f * scale
        val oy = px / 2f + (minS + maxS) / 2f * scale
        val order = kept.sortedByDescending { depth[it] }
        val m = FloatArray(9)
        for (i in order) {
            // Σ = R S² Rᵀ, projected on the screen's axes, in pixels², plus a pixel's low-pass.
            rotationMatrix(splats.rotations, i * 4, m)
            var a = 0f; var b = 0f; var c = 0f
            for (k in 0 until 3) {
                val s = splats.scales[i * 3 + k]
                val rk = (right[0] * m[k] + right[1] * m[3 + k] + right[2] * m[6 + k]) * s
                val uk = (up[0] * m[k] + up[1] * m[3 + k] + up[2] * m[6 + k]) * s
                a += rk * rk; b += rk * uk; c += uk * uk
            }
            a = a * scale * scale + LOW_PASS; b *= scale * scale; c = c * scale * scale + LOW_PASS
            val det = a * c - b * b
            if (det <= 0f) continue
            val ia = c / det; val ib = -b / det; val ic = a / det
            val radius = 3f * sqrt(maxOf(a, c))
            val cx = ox + sx[i] * scale
            val cy = oy - sy[i] * scale
            val x0 = maxOf(0, (cx - radius).toInt()); val x1 = minOf(px - 1, (cx + radius).toInt())
            val y0 = maxOf(0, (cy - radius).toInt()); val y1 = minOf(px - 1, (cy + radius).toInt())
            val opacity = splats.opacities[i]
            for (y in y0..y1) {
                val dy = y + 0.5f - cy
                for (x in x0..x1) {
                    val dx = x + 0.5f - cx
                    // Screen y runs down: the conic's cross term flips with it.
                    val power = -0.5f * (ia * dx * dx - 2f * ib * dx * dy + ic * dy * dy)
                    if (power < -MAX_POWER) continue
                    val al = minOf(MAX_ALPHA, opacity * exp(power))
                    if (al < MIN_ALPHA) continue
                    val p = y * px + x
                    for (ch in 0 until 3) rgb[p * 3 + ch] = rgb[p * 3 + ch] * (1f - al) + splats.colors[i * 3 + ch] * al
                    alpha[p] = alpha[p] * (1f - al) + al
                }
            }
        }
        val covered = alpha.count { it > 0.5f }.toFloat() / alpha.size
        return Image(pack(rgb, px), order.size, covered)
    }

    private fun normalOf(splats: SplatCloud, i: Int, out: FloatArray) {
        val x = splats.rotations[i * 4]; val y = splats.rotations[i * 4 + 1]
        val z = splats.rotations[i * 4 + 2]; val w = splats.rotations[i * 4 + 3]
        // The rotation's third column: where +Z goes.
        out[0] = 2f * (x * z + w * y)
        out[1] = 2f * (y * z - w * x)
        out[2] = 1f - 2f * (x * x + y * y)
    }

    private fun rotationMatrix(q: FloatArray, at: Int, m: FloatArray) {
        val x = q[at]; val y = q[at + 1]; val z = q[at + 2]; val w = q[at + 3]
        m[0] = 1 - 2 * (y * y + z * z); m[1] = 2 * (x * y - w * z); m[2] = 2 * (x * z + w * y)
        m[3] = 2 * (x * y + w * z); m[4] = 1 - 2 * (x * x + z * z); m[5] = 2 * (y * z - w * x)
        m[6] = 2 * (x * z - w * y); m[7] = 2 * (y * z + w * x); m[8] = 1 - 2 * (x * x + y * y)
    }

    private fun pack(rgb: FloatArray, px: Int) = IntArray(px * px) { p ->
        val r = (rgb[p * 3].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val g = (rgb[p * 3 + 1].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val b = (rgb[p * 3 + 2].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: FloatArray, b: FloatArray) =
        floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun normalize(v: FloatArray): FloatArray {
        val l = sqrt(dot(v, v)).takeIf { it > 0f } ?: 1f
        return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }

    private const val NANOS_PER_MS = 1_000_000L
    private const val MM_PER_M = 1000f
    private const val AZIMUTH_DEG = 225.0
    private const val ELEVATION_DEG = 52.0
    private const val CUT_SHARE = 0.72f
    private const val BACKFACE = -0.05f
    private const val MARGIN = 0.04f
    private const val LOW_PASS = 0.3f
    private const val MAX_POWER = 4.5f
    private const val MIN_ALPHA = 1f / 255f
    private const val MAX_ALPHA = 0.99f
}
