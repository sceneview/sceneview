package io.github.sceneview.demo.ui.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.google.android.filament.Engine
import com.google.android.filament.Skybox
import io.github.sceneview.demo.theme.SceneViewTokens
import kotlin.math.sqrt

/**
 * The Model Viewer's backdrop while the environment is hidden: a flat skybox that reaches the
 * screen as `stage-background` (DESIGN.md), the colour iOS shows behind the same models.
 *
 * The viewer's surface is opaque, so the renderer's clear — black — covers the stage colour the
 * layout paints behind it; the backdrop has to be drawn by Filament. And everything Filament draws
 * goes through the view's tone mapper, [com.google.android.filament.ToneMapper.Filmic] by default,
 * whose toe crushes a colour this dark to about a third of its value: the stage token drawn as is
 * came out near-black. So the backdrop is painted in the colour the tone mapper maps *onto* the
 * token — the same exact inverse the AR camera materials use (`Inverse_Tonemap_Filmic`, see
 * `createARView`).
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

    /** Creates the backdrop skybox; the caller owns it and frees it with `engine.destroySkybox`. */
    fun create(engine: Engine, color: Color = SceneViewTokens.Stage.background): Skybox =
        linearBeforeFilmic(color).let { (r, g, b) -> Skybox.Builder().color(r, g, b, 1f).build(engine) }
}
