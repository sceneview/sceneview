package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.filament.Engine
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewTokens
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
 *  - the **back affordance** — a top-start back arrow, never an X ([#2482]'s original
 *    review note). Drawn here only for a host that has no app bar of its own (the AR View
 *    tab); inside a [io.github.sceneview.demo.DemoScaffold] the scaffold's own top-start
 *    back arrow is the same affordance and this one stays off.
 *  - the **coaching line** — inherited from the session's default overlays, fed the one
 *    label computed here, so both surfaces say "Tap to place Fox" in the same words at the
 *    same moment, and both go quiet at the same moment too (#3326).
 *  - the **model picker** — [PlacementModelPickerSheet] plus [PlacementModelBar], the
 *    richer of the two variants, and now applied on both surfaces.
 *  - **tap-time model resolution** — the [PlacementModel] is read from [picker] *inside*
 *    the tap handler, never captured at composition. That is the
 *    [#2476](https://github.com/sceneview/sceneview/issues/2476) invariant, and having one
 *    call site for it is what stops it regressing on one surface only.
 *
 * The two hosts still differ in the ways their *roles* differ, and only there: the AR View
 * tab is a quick launcher (bundled catalogue, no dev toggles, its own fullscreen chrome),
 * the `ar-placement` demo is the feature demo (bundled + streamed catalogue, Snap-to-plane
 * / Show-reticle toggles, the QA tracking-failure shim, and the scaffold's bottom band
 * hosting the very same [PlacementModelBar]).
 *
 * @param models Catalogue offered by the picker. May grow/shrink between compositions —
 *   selection is by id, so it cannot be shifted by a row appearing.
 * @param picker Hoisted selection + sheet state. See [rememberPlacementPickerState].
 * @param onBack Non-null ⇒ draw the canonical top-start back arrow over the camera. Pass
 *   `null` inside a [io.github.sceneview.demo.DemoScaffold], which already has one.
 * @param onReset Non-null ⇒ the bar carries the Reset control. Ignored when
 *   [showModelBar] is `false`; that host renders the bar itself.
 * @param showModelBar `false` ⇒ the host renders [PlacementModelBar] in its own bottom
 *   band (the scaffold's `bottomOverlay` slot) instead of floating it over the camera.
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
    onBack: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
    showModelBar: Boolean = true,
    snapToPlane: Boolean = true,
    showReticle: Boolean = true,
    onModelPlaced: ((PlacementSpec) -> Unit)? = null,
) {
    val armedModel = models.armed(picker)
    // What the status pill announces. A streamed row that is still downloading says so —
    // and stays placeable, because it carries its own bundled stand-in.
    val nextModelLabel = armedModel?.let { model ->
        if (model.pending) {
            stringResource(R.string.ar_picker_streaming, model.displayName)
        } else {
            model.displayName
        }
    }

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
            onModelPlaced = onModelPlaced,
        )

        if (onBack != null) {
            PlacementBackButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }

        if (showModelBar) {
            PlacementModelBar(
                model = armedModel,
                onPickModel = picker::openSheet,
                onReset = onReset,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                        )
                    )
                    .padding(SceneViewTokens.Space.md),
            )
        }
    }

    PlacementModelPickerSheet(models = models, picker = picker)
}

/**
 * The canonical back affordance over a live camera: a top-**start** arrow, on a
 * translucent surface disc so it stays legible over an arbitrary camera frame.
 *
 * A top-end X used to sit here instead, which is the mismatch #2482 opened on — the review
 * note was, in as many words, "I don't know why this is a cross rather than a back". Every
 * other screen in the app exits with a back arrow at the top start; a camera is not a
 * reason to exit differently.
 */
@Composable
private fun PlacementBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                )
            )
            .padding(SceneViewTokens.Space.sm)
            .size(BACK_BUTTON_SIZE),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.cd_back_button),
            modifier = Modifier.size(BACK_BUTTON_ICON_SIZE),
        )
    }
}

private val BACK_BUTTON_SIZE = 40.dp
private val BACK_BUTTON_ICON_SIZE = 20.dp
