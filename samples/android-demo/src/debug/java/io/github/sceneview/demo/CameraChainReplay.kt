package io.github.sceneview.demo

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * What `camera_stream_flat.mat` does to a camera texel, on the CPU: `inverseViewTonemapSRGB(c, true)`
 * of `sceneview/src/main/materials/include/view_tone_map_inverse.fs` (#5219, #5231).
 *
 * Exact sRGB EOTF, to Filament's Rec.2020 working space, the inverse Filmic per channel there, back
 * to Rec.709, clipped at 0. Filament's ColorGrading tone-maps in Rec.2020, so inverting the curve
 * channel by channel in Rec.709 is exact for greys only: a `#66B0F5` sky came out `#93B2FA`.
 * `ArOverlayColourChainTest` checks the matrices against the include and grades with them.
 */
internal object CameraChainReplay {

    /** Filament's `sRGB_to_REC2020`, row-major (the include has it column-major). */
    val REC709_TO_REC2020 = arrayOf(
        floatArrayOf(0.6274040f, 0.3292820f, 0.0433136f),
        floatArrayOf(0.0690970f, 0.9195400f, 0.0113612f),
        floatArrayOf(0.0163916f, 0.0880132f, 0.8955950f),
    )

    /** Filament's `REC2020_to_sRGB`, row-major. */
    val REC2020_TO_REC709 = arrayOf(
        floatArrayOf(1.6604910f, -0.5876411f, -0.0728499f),
        floatArrayOf(-0.1245505f, 1.1328999f, -0.0083494f),
        floatArrayOf(-0.0181508f, -0.1005789f, 1.1187297f),
    )

    /** The sRGB-encoded camera colour [srgb] (r, g, b in 0..1) as it lands in the HDR buffer. */
    fun cameraToBuffer(srgb: FloatArray): FloatArray {
        val working = REC709_TO_REC2020 * FloatArray(3) { srgbToLinearExact(srgb[it]) }
        val scene = REC2020_TO_REC709 * FloatArray(3) { inverseFilmic(working[it]) }
        return FloatArray(3) { max(scene[it], 0f) }
    }

    operator fun Array<FloatArray>.times(v: FloatArray) =
        FloatArray(3) { r -> this[r][0] * v[0] + this[r][1] * v[1] + this[r][2] * v[2] }

    /** Exact sRGB EOTF (IEC 61966-2-1), `viewInverseSrgbToLinear`. */
    fun srgbToLinearExact(srgb: Float): Float {
        val s = srgb.coerceIn(0f, 1f)
        return if (s < SRGB_KNEE) s / SRGB_LINEAR_SLOPE else ((s + SRGB_OFFSET) / (1f + SRGB_OFFSET)).pow(SRGB_GAMMA)
    }

    /** Exact analytic inverse of `ToneMapper.Filmic`, `viewInverseFilmic`, on one channel. */
    fun inverseFilmic(x: Float): Float =
        (0.03f - 0.59f * x - sqrt(0.0009f + 1.3702f * x - 1.0127f * x * x)) / (-5.02f + 4.86f * x)

    private const val SRGB_KNEE = 0.04045f
    private const val SRGB_LINEAR_SLOPE = 12.92f
    private const val SRGB_OFFSET = 0.055f
    private const val SRGB_GAMMA = 2.4f
}
