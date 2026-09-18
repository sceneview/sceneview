package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.android.filament.Engine
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader

/**
 * **The** tap-to-place experience — one screen, rendered by both AR entry points
 * ([#2482](https://github.com/sceneview/sceneview/issues/2482)).
 *
 * [TapToPlaceArSession] already unified the *engine* (reticle, plane guide, anchors,
 * status vocabulary). What stayed forked afterwards was everything the user actually
 * touches: two model catalogues, two pickers (a bottom-sheet grid on the AR View tab, a
 * chip strip in the demo's Settings sheet), two "what will the next tap place?" answers,
 * two reset controls, and each host writing its own `onPlaceModel`. This composable owns
 * that layer, so there is exactly one implementation of:
 *
 *  - the **coaching line** — inherited from the session's default overlays, fed the one
 *    label computed here, so both surfaces say "Tap to place Fox" in the same words at the
 *    same moment, and both go quiet at the same moment too (#3326).
 *  - the **model picker** — the [PlacementModelPickerSheet], opened from the host's dock.
 *  - **tap-time model resolution** — the [PlacementModel] is read from [picker] *inside*
 *    the tap handler, never captured at composition. That is the
 *    [#2476](https://github.com/sceneview/sceneview/issues/2476) invariant, and having one
 *    call site for it is what stops it regressing on one surface only.
 *
 * ## What this composable no longer draws
 *
 * It used to float two controls of its own over the camera: a back disc and a
 * `PlacementModelBar` (an extended FAB + a Reset button). Both are gone. Every other demo
 * in the app exits through [io.github.sceneview.demo.DemoScaffold]'s back arrow and acts
 * through its dock, and those two controls were the only theme-coloured surfaces the app
 * ever painted over a live camera — `surface @ 85 %` and `primaryContainer`, which is the
 * one place a theme colour cannot be read against its background because the background is
 * whatever the room happens to be. The scaffold now hosts **both** surfaces, so the back
 * arrow, the *Models* item and the *Clear* item are literally the same controls in the same
 * places on the AR View tab and in the `ar-placement` demo.
 *
 * The two hosts still differ in the ways their *roles* differ, and only there: the AR View
 * tab is a quick launcher (bundled catalogue, no dev toggles), the `ar-placement` demo is
 * the feature demo (bundled + streamed catalogue, Snap-to-plane / Show-reticle toggles, the
 * QA tracking-failure shim).
 *
 * @param models Catalogue offered by the picker. May grow/shrink between compositions —
 *   selection is by id, so it cannot be shifted by a row appearing.
 * @param picker Hoisted selection + sheet state. See [rememberPlacementPickerState]. The
 *   host's dock opens the sheet with `picker::openSheet`.
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
    snapToPlane: Boolean = true,
    showReticle: Boolean = true,
    /**
     * `true` ⇒ the folded instant-placement mode (#3405). Forwarded to the session, which
     * configures `LOCAL_Y_UP` and falls back to `hitTestInstantPlacement` when no plane is
     * under the tap, and to the overlays, which stop coaching "point at a surface" for a tap
     * that would land anyway.
     */
    instantPlacement: Boolean = false,
    onModelPlaced: ((PlacementSpec) -> Unit)? = null,
    floorOnly: Boolean = false,
) {
    // What the status pill announces. A streamed row that is still downloading says so —
    // and stays placeable, because it carries its own bundled stand-in. Same helper the
    // picker sheet uses, so the two can never word it differently.
    val nextModelLabel = models.armed(picker)?.let { placementModelLabel(it) }

    Box(modifier = modifier.fillMaxSize()) {
        TapToPlaceArSession(
            nextModelLabel = nextModelLabel,
            // #2476 invariant, single call site: re-read the catalogue and the armed id
            // HERE, inside the tap handler, on the main thread. Nothing about the
            // selection is captured when this lambda is created.
            onPlaceModel = {
                models.armed(picker)?.let { model ->
                    PlacementSpec(
                        assetLocation = model.assetLocation,
                        displayName = model.displayName,
                        realWorldSizeMeters = model.realWorldSizeMeters,
                    )
                }
            },
            state = state,
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            snapToPlane = snapToPlane,
            showReticle = showReticle,
            instantPlacement = instantPlacement,
            sessionConfiguration = { _, config ->
                config.planeFindingMode = if (floorOnly) com.google.ar.core.Config.PlaneFindingMode.HORIZONTAL
                    else com.google.ar.core.Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            },
            onModelPlaced = onModelPlaced,
            overlays = { s ->
                TapToPlaceStatusOverlays(
                    state = s,
                    nextModelLabel = nextModelLabel,
                    instantPlacement = instantPlacement,
                )
            },
        )
    }

    PlacementModelPickerSheet(models = models, picker = picker)
}
