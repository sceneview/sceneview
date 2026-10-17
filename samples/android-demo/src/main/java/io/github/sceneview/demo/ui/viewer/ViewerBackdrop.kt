package io.github.sceneview.demo.ui.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.google.android.filament.Engine
import com.google.android.filament.Skybox
import io.github.sceneview.demo.theme.SceneViewTokens
import kotlin.math.sqrt

/**
 * A flat skybox that reaches the screen as a design token — `stage-background` (DESIGN.md) by
 * default, the colour iOS shows behind the same models.
 *
 * A SceneView surface is opaque, so the renderer's clear — black — covers the stage colour the
 * layout paints behind it; the backdrop has to be drawn by Filament. And everything Filament draws
 * goes through the view's tone mapper, [com.google.android.filament.ToneMapper.PBRNeutralToneMapper]
 * by default, whose toe pulls the darkest channel of a colour this dark close to black, and whose
 * shoulder compresses the light theme's near-white. So the backdrop is painted in the colour the
 * tone mapper maps *onto* the token — its exact inverse, the same idea as the AR camera materials'
 * `Inverse_Tonemap_Filmic` (see `createARView`).
 *
 * The Model Viewer's showroom renders under [pbrNeutral] too and inverts that curve with
 * [linearBeforeNeutral] — see [ShowroomStage]. [filmic] stays for the views that opt back into
 * Filmic (`FilmicView`).
 *
 * The Lighting demos keep `StageFade.stageBackdrop`: their floor fades into that same uncompensated
 * tone, and the two must stay one colour.
 */
object ViewerBackdrop {

    /** Filament's `ToneMapper.Filmic` (Narkowicz 2015 ACES fit), one linear channel. */
    fun filmic(x: Float): Float = (x * (2.51f * x + 0.03f)) / (x * (2.43f * x + 0.59f) + 0.14f)

    /** The exact inverse of [filmic] — Filament's `Inverse_Tonemap_Filmic`, one channel. */
    fun inverseFilmic(y: Float): Float =
        (0.03f - 0.59f * y - sqrt(0.0009f + 1.3702f * y - 1.0127f * y * y)) / (-5.02f + 4.86f * y)

    /** The linear colour to draw so that [color] is what the Filmic tone mapper outputs. */
    fun linearBeforeFilmic(color: Color): FloatArray =
        color.convert(ColorSpaces.LinearSrgb).let { floatArrayOf(it.red, it.green, it.blue) }
            .map(::inverseFilmic).toFloatArray()

    /**
     * Khronos PBR Neutral — Filament's `ToneMapper.PBRNeutralToneMapper` — on one linear RGB
     * colour. Past a small toe it keeps a colour as it is up to 0.76 and only rolls off what is
     * brighter, without the hue shifts and the lifted mid-tones of [filmic]: a white sofa stays
     * white and a red car stays the red it was authored in, which is what a product shot needs.
     */
    fun pbrNeutral(rgb: FloatArray): FloatArray {
        val lowest = minOf(rgb[0], rgb[1], rgb[2])
        val offset =
            if (lowest < NEUTRAL_TOE_END) lowest - NEUTRAL_TOE_CURVE * lowest * lowest else NEUTRAL_TOE_OFFSET
        val c = FloatArray(3) { rgb[it] - offset }
        val peak = maxOf(c[0], c[1], c[2])
        if (peak < NEUTRAL_START_COMPRESSION) return c
        val newPeak =
            1f - NEUTRAL_SHOULDER * NEUTRAL_SHOULDER / (peak + NEUTRAL_SHOULDER - NEUTRAL_START_COMPRESSION)
        val g = 1f - 1f / (NEUTRAL_DESATURATION * (peak - newPeak) + 1f)
        return FloatArray(3) { c[it] * newPeak / peak * (1f - g) + newPeak * g }
    }

    /**
     * The exact inverse of [pbrNeutral]. A display value of 1 has no finite source, so each channel
     * is held just under it.
     */
    fun inversePbrNeutral(display: FloatArray): FloatArray {
        val o = FloatArray(3) { display[it].coerceIn(0f, NEUTRAL_MAX_DISPLAY) }
        val newPeak = maxOf(o[0], o[1], o[2])
        val c = if (newPeak < NEUTRAL_START_COMPRESSION) {
            o
        } else {
            val peak = NEUTRAL_START_COMPRESSION - NEUTRAL_SHOULDER +
                NEUTRAL_SHOULDER * NEUTRAL_SHOULDER / (1f - newPeak)
            val g = 1f - 1f / (NEUTRAL_DESATURATION * (peak - newPeak) + 1f)
            FloatArray(3) { (o[it] - g * newPeak) / (1f - g) * peak / newPeak }
        }
        // Undo the toe: it took `offset(lowest)` off every channel, and the lowest channel came out
        // as 6.25·lowest² below 0.08, as lowest − 0.04 above.
        val shifted = maxOf(minOf(c[0], c[1], c[2]), 0f)
        val lowest =
            if (shifted < NEUTRAL_TOE_OFFSET) sqrt(shifted / NEUTRAL_TOE_CURVE) else shifted + NEUTRAL_TOE_OFFSET
        val offset = lowest - shifted
        return FloatArray(3) { c[it] + offset }
    }

    /** The linear colour to draw so that [color] is what the PBR Neutral tone mapper outputs. */
    fun linearBeforeNeutral(color: Color): FloatArray =
        inversePbrNeutral(color.convert(ColorSpaces.LinearSrgb).let { floatArrayOf(it.red, it.green, it.blue) })

    /** The linear colour to draw so that [color] is what the SDK's default tone mapper outputs. */
    fun linearBeforeToneMap(color: Color): FloatArray = linearBeforeNeutral(color)

    /** Creates the backdrop skybox; the caller owns it and frees it with `engine.destroySkybox`. */
    fun create(engine: Engine, color: Color = SceneViewTokens.Stage.background): Skybox =
        linearBeforeToneMap(color).let { (r, g, b) -> Skybox.Builder().color(r, g, b, 1f).build(engine) }

    private const val NEUTRAL_START_COMPRESSION = 0.76f
    private const val NEUTRAL_SHOULDER = 1f - NEUTRAL_START_COMPRESSION
    private const val NEUTRAL_DESATURATION = 0.15f
    private const val NEUTRAL_TOE_END = 0.08f
    private const val NEUTRAL_TOE_OFFSET = 0.04f
    private const val NEUTRAL_TOE_CURVE = 6.25f
    private const val NEUTRAL_MAX_DISPLAY = 0.995f
}
