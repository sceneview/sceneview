package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
 * ## Two composables, one experience
 *
 * The session goes in the scaffold's `scene` slot; the coaching layer — the coaching line,
 * the cards, the read-out — goes in its `sceneOverlay` slot, through
 * [TapToPlaceExperienceOverlays]. The scaffold paints its bottom scrim *over* `scene`, so
 * a coaching line composed there is veiled by the same wash that grounds the dock
 * (measured on the goldens: 4.3:1 falling to 2.3:1 on the pill's own text, where the dock
 * captions keep 5.4:1). `sceneOverlay` is the `scene` slot's frame and insets, composed
 * after the scrim — the layer lands where it always did and is painted on the scrim
 * instead of under it. A host wires the two halves to the same [state]:
 *
 * ```kotlin
 * DemoScaffold(
 *     …,
 *     sceneOverlay = { TapToPlaceExperienceOverlays(state = state, onViewIn3D = …) },
 * ) {
 *     TapToPlaceExperience(models = models, picker = picker, state = state, onViewIn3D = …)
 * }
 * ```
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
        if (model.pending) {
            // Nothing to offer yet — and the previous offer must not be placed under this
            // row's name while its file downloads.
            state.holdForPendingAsset()
            return@LaunchedEffect
        }
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
            // Nothing over the viewport here: this Box is the scaffold's `scene` slot,
            // under the bottom scrim. The host composes [TapToPlaceExperienceOverlays]
            // in the scaffold's `sceneOverlay` slot instead — see the class KDoc.
            overlays = {},
        )
    }

    PlacementModelPickerSheet(models = models, picker = picker)
}

/**
 * The coaching layer of [TapToPlaceExperience] — [TapToPlaceStatusOverlays] on the same
 * [state] — for the scaffold's `sceneOverlay` slot, where it is painted above the bottom
 * scrim rather than through it. Full-viewport: it anchors itself to the bottom edge from
 * `LocalDemoChromeBottomInset`, which the slot provides exactly as `scene` does.
 *
 * A host keys it like the session (`key(sessionKey) { … }`) so a session restart also
 * restarts the layer's own timers — the gesture-hint window, the start-up stall.
 *
 * @param onViewIn3D The no-surface card's primary action; pass the same lambda as the session.
 * @param onRestartSession The camera-error card's *Try again*; same lambda as the session.
 */
@Composable
fun BoxScope.TapToPlaceExperienceOverlays(
    state: TapToPlaceState,
    onViewIn3D: (() -> Unit)? = null,
    onRestartSession: (() -> Unit)? = null,
) {
    TapToPlaceStatusOverlays(
        state = state,
        onViewIn3D = onViewIn3D,
        onRestartSession = onRestartSession,
    )
}
