package io.github.sceneview.demo.common

import androidx.compose.runtime.Composable
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
 * hero's night circuit (validated as is) and Cosmos, whose `cosmos_spacetime.mat` pre-distorts
 * its sheet by `inverseFilmic`. Cosmos was measured under both for #4623: PBR Neutral turned the
 * star's white-hot disc a flat saturated blue, cast the galaxy core warm and halved its arms —
 * an emissive scene wants Filmic's highlight roll-off. Every other demo takes the SDK default, which shows a base colour
 * as authored instead of bleaching pastels and crushing darks.
 */
@Composable
fun rememberFilmicView(engine: Engine): View {
    // `rememberView` keys on the creator: hand it one stable instance, not a fresh lambda.
    val creator = remember(engine) {
        {
            createView(engine).apply {
                colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.Filmic()).build(engine)
            }
        }
    }
    return rememberView(engine, creator)
}
