package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.core.splat.SplatCloud
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.ui.viewer.ViewerBackdrop
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A scan's surfels ([DenseCloud]) as flat gaussian splats ([SplatCloud]), for the result's
 * "Photo" preview: the same points, drawn soft and continuous by the library's `SplatNode`
 * instead of meshed. Nothing is trained: every splat is one surfel, read as it was fused.
 *
 * - **Shape.** A surfel is a small disc in the plane its normal gives. Its splat is as wide as
 *   that disc on its two tangent axes — [SIGMA_VOXELS] voxels of standard deviation, times
 *   `2^level` for a surfel the map coarsened ([DenseCloud.levelOf]) — and [THICKNESS_SHARE] of
 *   that along the normal, so a wall stays a thin sheet seen from any side. Neighbours, one
 *   voxel apart, overlap at about 0.7 of their peak: a surface reads continuous, with no gaps
 *   and no grid.
 * - **Rotation.** The shortest arc from the splat's local +Z (its thin axis) to the surfel's
 *   normal. In-plane both axes are equal, so the twist about the normal does not matter. A
 *   cloud without normals gets round splats ([SIGMA_VOXELS] on all three axes).
 * - **Opacity.** From the surfel's depth confidence: [MIN_OPACITY] at the back-projection's
 *   floor ([CONFIDENCE_FLOOR]) up to [MAX_OPACITY] at full confidence; [MAX_OPACITY] when the
 *   cloud has none. Never fully opaque, so overlapping splats blend instead of tiling.
 * - **Cap.** At most `maxSplats`. A bigger cloud keeps an even share of its surfels, every
 *   n-th in its own order (the map's insertion order, which already spreads a room), and widens
 *   each splat by `sqrt(count / kept)`, so the room is drawn whole, softer, not with holes.
 * - **Colour.** A surfel's colour is sRGB, as the splat material expects. The material undoes
 *   the PBR Neutral tone mapper of a default view; the result's view tone-maps linearly, so
 *   with [linearView] the colour is first put through PBR Neutral, and the two cancel: the
 *   splats show the colours the model shows. A surfel with no colour (`0`) is left out.
 *
 * Pure Kotlin, no Filament: it runs off the main thread, and on the JVM in tests.
 */
internal object SurfelSplats {
    /**
     * The most splats the preview draws. A phone fills every pixel of a splat several times over,
     * and each splat costs four vertices that read four textures: on the Pixel 4a a whole home at
     * [DenseFusion.MAX_POINTS] (~300k drawn) orbits at ~16 fps, half the model's rate (#4896).
     * Fewer splats are each widened to cover the same surface (`widen` in [of]), so the cap trades
     * detail, not coverage. Two `SplatNode` draw batches, now both drawn whole (#4896).
     */
    const val MAX_SPLATS = 65_535

    /** A level-0 splat's standard deviation on its two tangent axes, in voxels. */
    const val SIGMA_VOXELS = 0.6f

    /** A splat's standard deviation along its normal, as a share of its tangent one. */
    const val THICKNESS_SHARE = 0.08f

    /** The thinnest a splat is along its normal, metres: a half-float still holds it. */
    const val MIN_THICKNESS_M = 0.0005f

    /** Opacity of a surfel at the back-projection's confidence floor. */
    const val MIN_OPACITY = 0.55f

    /** Opacity of a surfel at full confidence (and of every surfel of a cloud with none). */
    const val MAX_OPACITY = 0.92f

    /** The lowest confidence a surfel is fused with (`DepthBackProjection.MIN_CONFIDENCE`). */
    const val CONFIDENCE_FLOOR = 128

    /** The coarsest level a splat is widened for, as the surfel mesh does. */
    private const val LEVEL_LIMIT = DenseFusion.LEVEL_LIMIT

    /**
     * [cloud]'s surfels as splats, [voxelM] the size of its level-0 voxels, at most [maxSplats];
     * [linearView] when the view they are drawn in tone-maps linearly (the scan result's).
     * Empty when the cloud has no coloured surfel.
     */
    fun of(
        cloud: DenseCloud,
        voxelM: Float = DenseFusion.VOXEL_M,
        maxSplats: Int = MAX_SPLATS,
        linearView: Boolean = true,
    ): SplatCloud {
        require(maxSplats >= 0) { "maxSplats must be >= 0, was $maxSplats" }
        val coloured = (0 until cloud.count).count { cloud.colors[it] != 0 }
        val n = minOf(coloured, maxSplats)
        val positions = FloatArray(n * 3)
        val scales = FloatArray(n * 3)
        val rotations = FloatArray(n * 4)
        val colors = FloatArray(n * 3)
        val opacities = FloatArray(n)
        if (n == 0) return SplatCloud(0, positions, scales, rotations, colors, opacities)
        // Kept evenly: the k-th coloured surfel is kept when it crosses the next multiple of the share,
        // in whole numbers so that exactly n cross (a share in floating point can lose the last one).
        val widen = sqrt(coloured.toFloat() / n)
        val normals = cloud.normals
        val confidences = cloud.confidences
        val rgb = FloatArray(3)
        var seen = 0
        var o = 0
        for (i in 0 until cloud.count) {
            val argb = cloud.colors[i]
            if (argb == 0) continue
            val keep = (seen + 1).toLong() * n / coloured > seen.toLong() * n / coloured
            seen++
            if (!keep || o >= n) continue
            for (a in 0 until 3) positions[o * 3 + a] = cloud.positions[i * 3 + a]
            val sigma = voxelM * SIGMA_VOXELS * (1 shl cloud.levelOf(i).coerceAtMost(LEVEL_LIMIT)) * widen
            if (normals != null) {
                scales[o * 3] = sigma
                scales[o * 3 + 1] = sigma
                scales[o * 3 + 2] = maxOf(sigma * THICKNESS_SHARE, MIN_THICKNESS_M)
                rotationToNormal(normals[i * 3], normals[i * 3 + 1], normals[i * 3 + 2], rotations, o * 4)
            } else {
                scales[o * 3] = sigma
                scales[o * 3 + 1] = sigma
                scales[o * 3 + 2] = sigma
                rotations[o * 4 + 3] = 1f
            }
            colour(argb, linearView, rgb)
            colors[o * 3] = rgb[0]
            colors[o * 3 + 1] = rgb[1]
            colors[o * 3 + 2] = rgb[2]
            opacities[o] = opacity(confidences?.get(i)?.toInt()?.and(0xFF))
            o++
        }
        return SplatCloud(n, positions, scales, rotations, colors, opacities)
    }

    /** A surfel's opacity for its [confidence] (0–255), [MAX_OPACITY] when unknown (`null`). */
    fun opacity(confidence: Int?): Float {
        confidence ?: return MAX_OPACITY
        val t = ((confidence - CONFIDENCE_FLOOR).toFloat() / (255 - CONFIDENCE_FLOOR)).coerceIn(0f, 1f)
        // Smoothstep: a surfel just over the floor stays faint, a confident one near the top.
        val s = t * t * (3f - 2f * t)
        return MIN_OPACITY + (MAX_OPACITY - MIN_OPACITY) * s
    }

    /**
     * Writes into [out] at [at] the unit quaternion `x, y, z, w` that turns +Z onto the normal
     * `(nx, ny, nz)` by the shortest arc; the identity for a zero normal, a half turn about X
     * for a normal pointing down -Z.
     */
    fun rotationToNormal(nx: Float, ny: Float, nz: Float, out: FloatArray, at: Int) {
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-6f) {
            out[at] = 0f; out[at + 1] = 0f; out[at + 2] = 0f; out[at + 3] = 1f
            return
        }
        val x = nx / len
        val y = ny / len
        val z = nz / len
        // q = (ez × n, 1 + ez · n), normalised; ez × n = (-y, x, 0).
        val w = 1f + z
        if (w < 1e-6f) {
            out[at] = 1f; out[at + 1] = 0f; out[at + 2] = 0f; out[at + 3] = 0f
            return
        }
        val qx = -y
        val qy = x
        val norm = sqrt(qx * qx + qy * qy + w * w)
        out[at] = qx / norm
        out[at + 1] = qy / norm
        out[at + 2] = 0f
        out[at + 3] = w / norm
    }

    /**
     * [argb]'s sRGB colour, 0–1, into [out]; with [linearView], the colour the splat material
     * turns back into it under a linear tone mapper (see the class doc).
     */
    fun colour(argb: Int, linearView: Boolean, out: FloatArray) {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        if (!linearView) {
            out[0] = r; out[1] = g; out[2] = b
            return
        }
        val toned = ViewerBackdrop.pbrNeutral(floatArrayOf(srgbToLinear(r), srgbToLinear(g), srgbToLinear(b)))
        for (c in 0 until 3) out[c] = linearToSrgb(toned[c]).coerceIn(0f, 1f)
    }

    /** The exact sRGB EOTF, as `splat.mat`'s `splatSrgbToLinear`. */
    fun srgbToLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    /** The exact sRGB OETF, the inverse of [srgbToLinear]. */
    fun linearToSrgb(c: Float): Float = when {
        c <= 0f -> 0f
        c <= 0.0031308f -> c * 12.92f
        else -> 1.055f * c.pow(1f / 2.4f) - 0.055f
    }
}
