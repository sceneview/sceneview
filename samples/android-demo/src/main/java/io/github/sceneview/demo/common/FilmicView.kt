package io.github.sceneview.demo.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import io.github.sceneview.createView
import io.github.sceneview.safeDestroyView

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
    val graded = remember(engine) {
        val view = createView(engine)
        // The PBR Neutral grading `createView` gave the view: replaced, not freed by the swap.
        val replaced = view.colorGrading
        view.colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.Filmic()).build(engine)
        GradedView(view, listOfNotNull(replaced, view.colorGrading))
    }
    // Not `rememberView`: `destroyView` frees the view alone, and on an engine that outlives the
    // screen (the home hero's, #5202) both gradings, a 3D LUT each, would pile up visit after
    // visit. The view goes first: a grading must not be freed while a view still holds it.
    DisposableEffect(graded) {
        onDispose {
            engine.safeDestroyView(graded.view)
            graded.gradings.forEach { runCatching { engine.destroyColorGrading(it) } }
        }
    }
    return graded.view
}

/** A view and every [ColorGrading] made for it, freed with it. */
private class GradedView(val view: View, val gradings: List<ColorGrading>)
