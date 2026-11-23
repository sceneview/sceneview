package io.github.sceneview.demo.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import io.github.sceneview.createView
import io.github.sceneview.rememberView

/**
 * A [createView] view that keeps the Filmic tone mapper the SDK used before it moved to PBR
 * Neutral.
 *
 * Only for scenes whose look was tuned on screen under Filmic and is still built on it: the home
 * hero's night circuit (validated as is), Cosmos, whose `cosmos_spacetime.mat` pre-distorts
 * its sheet by `inverseFilmic`. Cosmos was measured under both for #4623: PBR Neutral turned the
 * star's white-hot disc a flat saturated blue, cast the galaxy core warm and halved its arms —
 * an emissive scene wants Filmic's highlight roll-off. Every other demo takes the SDK default, which shows a base colour
 * as authored instead of bleaching pastels and crushing darks.
 */
@Composable
fun rememberFilmicView(engine: Engine): View {
    val filmic = remember(engine) {
        ColorGrading.Builder().toneMapper(ToneMapper.Filmic()).build(engine)
    }
    // Declared before the view, so disposed after it: a grading must not be freed while a view
    // still holds it. On an engine that outlives the screen (the home hero's, #5202) it would
    // otherwise pile up, a 3D LUT per visit.
    DisposableEffect(filmic) {
        onDispose { runCatching { engine.destroyColorGrading(filmic) } }
    }
    // `rememberView` frees the view and the PBR Neutral grading `createView` gave it (#5232):
    // each object is destroyed once, by whoever made it.
    return rememberView(engine) { createView(engine).apply { colorGrading = filmic } }
}
