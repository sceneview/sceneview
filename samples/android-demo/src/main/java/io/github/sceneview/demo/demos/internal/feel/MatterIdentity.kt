package io.github.sceneview.demo.demos.internal.feel

import io.github.sceneview.demo.ui.viewer.ViewerBackdrop
import kotlin.math.pow

/** The flat identity's top, screen-left and screen-right faces, in sRGB. */
internal object MatterIdentity {
    fun colours(dark: Boolean): List<Int> =
        listOf(0xC9D9FF, 0x005BC1, if (dark) 0xADC6FF else 0x004494)

    /** Local +Z projects left, +X right; their shared vertical edge faces the camera. */
    fun yaw(cameraAzimuth: Float): Float = cameraAzimuth - 45f

    // Filament grades in Rec.2020, even with PBR Neutral. Inverting directly in sRGB
    // produces negative HDR channels for the pale faces and shifts the blue face's hue.
    fun linearBeforeToneMap(rgb: Int): FloatArray {
        val linear = FloatArray(3) { channel ->
            val value = ((rgb shr ((2 - channel) * 8)) and 255) / 255f
            if (value <= .04045f) value / 12.92f else ((value + .055f) / 1.055f).pow(2.4f)
        }
        return toSrgb(ViewerBackdrop.inversePbrNeutral(toRec2020(linear)))
    }

    internal fun toRec2020(rgb: FloatArray): FloatArray = floatArrayOf(
        .6274039f * rgb[0] + .3292830f * rgb[1] + .0433131f * rgb[2],
        .0690973f * rgb[0] + .9195404f * rgb[1] + .0113623f * rgb[2],
        .0163914f * rgb[0] + .0880133f * rgb[1] + .8955953f * rgb[2],
    )

    internal fun toSrgb(rgb: FloatArray): FloatArray = floatArrayOf(
        1.6604910f * rgb[0] - .5876411f * rgb[1] - .0728499f * rgb[2],
        -.1245505f * rgb[0] + 1.1328999f * rgb[1] - .0083494f * rgb[2],
        -.0181508f * rgb[0] - .1005789f * rgb[1] + 1.1187297f * rgb[2],
    )

    const val FACE_INSET = .045f
}
