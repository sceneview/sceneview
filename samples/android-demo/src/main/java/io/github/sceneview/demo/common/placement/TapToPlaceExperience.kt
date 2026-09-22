package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.google.android.filament.Engine
import io.github.sceneview.haptic.rememberHapticFeedback
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader

/**
 * **The** placement experience — one screen, rendered by both AR entry points
 * ([#2482](https://github.com/sceneview/sceneview/issues/2482)).
 *
 * [TapToPlaceArSession] owns the engine (the automatic surface search, the anchor, the
 * status vocabulary). This composable owns the layer the user touches around it, so there
 * is exactly one implementation of:
 *
 *  - **asset delivery** — the armed [PlacementModel] is resolved here and offered to the
 *    session with an [AssetTicket]. A ticket is minted per selection inside the current
 *    session generation, so a result that arrives after the user changed model or left the
 *    camera is refused rather than placed (the
 *    [#2476](https://github.com/sceneview/sceneview/issues/2476) invariant, made a type).
 *  - **unresolved assets block** — a streamed row that is still downloading is not offered.
 *    The session keeps scanning and places the real file when it lands, instead of standing
 *    a bundled stand-in in the room and swapping it later.
 *  - the **model picker** — the [PlacementModelPickerSheet], opened from the host's dock.
 *    Changing the model while an object stands replaces it at the same placement once the
 *    new asset loads (§2.2), with a `selection()` haptic.
 *
 * The scaffold hosts both surfaces, so the back arrow, the *Models* item and the *Reset
 * placement* item are the same controls in the same places on the AR View tab and in the
 * `ar-placement` demo.
 *
 * @param models Catalogue offered by the picker. May grow/shrink between compositions —
 *   selection is by id, so it cannot be shifted by a row appearing.
 * @param picker Hoisted selection + sheet state. See [rememberPlacementPickerState]. The
 *   host's dock opens the sheet with `picker::openSheet`.
 * @param onViewIn3D The no-surface card's primary action — the host decides where "3D" is.
 * @param onRestartSession The camera-error card's *Try again* — the host recreates the session.
 */
@Composable
fun TapToPlaceExperience(
    models: List<PlacementModel>,
    picker: PlacementPickerState,
    modifier: Modifier = Modifier,
    state: TapToPlaceState = rememberTapToPlaceState(),
    engine: Engine = rememberEngine(),
    modelLoader: ModelLoader = rememberModelLoader(engine),
    materialLoader: MaterialLoader = rememberMaterialLoader(engine),
    onModelPlaced: ((PlacementSpec) -> Unit)? = null,
    onViewIn3D: (() -> Unit)? = null,
    onRestartSession: (() -> Unit)? = null,
) {
    val armed = models.armed(picker)
    val haptic = rememberHapticFeedback()

    // One ticket per (selection, resolution). Keyed on what the row resolves to, so a
    // streamed row is re-offered — with a fresh ticket — the moment its file lands, and a
    // row still downloading offers nothing at all.
    LaunchedEffect(state, armed?.id, armed?.assetLocation, armed?.pending) {
        val model = armed ?: return@LaunchedEffect
        if (model.pending) return@LaunchedEffect
        val replacing = state.placedCount > 0
        val ticket = state.controller.selectModel()
        val accepted = state.offerAsset(
            ticket = ticket,
            spec = PlacementSpec(
                assetLocation = model.assetLocation,
                displayName = model.displayName,
                realWorldSizeMeters = model.realWorldSizeMeters,
                sizeIsMeasured = model.sizeIsMeasured,
            ),
        )
        // §2.8 — a picker change that swaps the standing object is a selection.
        if (accepted && replacing) haptic.selection()
    }

    Box(modifier = modifier.fillMaxSize()) {
        TapToPlaceArSession(
            state = state,
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            onModelPlaced = onModelPlaced,
            onViewIn3D = onViewIn3D,
            onRestartSession = onRestartSession,
        )
    }

    PlacementModelPickerSheet(models = models, picker = picker)
}
