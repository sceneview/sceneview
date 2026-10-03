package io.github.sceneview.demo.common.placement

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.sceneview.demo.telemetry.AR_VIEW_SAMPLE_ID
import io.github.sceneview.demo.telemetry.LocalSampleId
import io.github.sceneview.demo.telemetry.ModelLoadFailure
import io.github.sceneview.demo.telemetry.logModelLoadFailed
import io.github.sceneview.demo.telemetry.logSampleInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.github.sceneview.ar.AutoPlacementNode
import io.github.sceneview.ar.ARSceneScope
import io.github.sceneview.ar.AutoPlacementResult
import io.github.sceneview.demo.demos.DollhouseBuild
import io.github.sceneview.demo.demos.DollhouseReplay
import io.github.sceneview.demo.demos.loadRerunReplay
import io.github.sceneview.demo.demos.loadRerunSession
import io.github.sceneview.demo.demos.rerunSessionStore
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import com.google.android.filament.Engine
import io.github.sceneview.haptic.rememberHapticFeedback
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.model.model
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
    roomPlaying: Boolean = true,
) {
    val context = LocalContext.current
    var roomBuild by remember { mutableStateOf<DollhouseBuild?>(null) }
    var roomAsset by remember { mutableStateOf<String?>(null) }
    val armed = models.armed(picker)
    val haptic = rememberHapticFeedback()
    val telemetrySampleId = LocalSampleId.current ?: AR_VIEW_SAMPLE_ID
    // `sample_interaction` / `model`: a model picked after the first one armed.
    val firstArmedId = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(armed?.id) {
        val id = armed?.id ?: return@LaunchedEffect
        if (firstArmedId.value == null) firstArmedId.value = id
        else logSampleInteraction(telemetrySampleId, "model")
    }

    // One ticket per (selection, resolution). Keyed on what the row resolves to, so a
    // streamed row is re-offered — with a fresh ticket — the moment its file lands, and a
    // row still downloading offers nothing at all.
    LaunchedEffect(state, armed?.id, armed?.assetLocation, armed?.pending, state.assetRetry) {
        val model = armed ?: return@LaunchedEffect
        if (model.pending) {
            // Nothing to offer yet — and the previous offer must not be placed under this
            // row's name while its file downloads.
            state.holdForPendingAsset()
            return@LaunchedEffect
        }
        val replacing = state.placedCount > 0
        val ticket = state.controller.selectModel()
        state.controller.withdrawRequest()
        state.modelLoading = true
        state.modelError = false
        if (model.roomRecordingId != null) {
            val build = try {
                val media = if (model.roomRecordingId == BUNDLED_ROOM_RECORDING_ID) {
                    loadRerunReplay(context)
                } else {
                    val capture = withContext(Dispatchers.IO) {
                        rerunSessionStore(context).capture(model.roomRecordingId)
                    }
                    capture?.let { loadRerunSession(it) }
                }
                media?.let { withContext(Dispatchers.Default) { DollhouseBuild.of(it) } }
                    ?.takeIf { it.room != null }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (!state.controller.acceptsAsset(ticket)) return@LaunchedEffect
            state.modelLoading = false
            if (build == null) {
                state.modelError = true
                return@LaunchedEffect
            }
            roomBuild = build
            roomAsset = model.assetLocation
            state.modelInstance = null
            val accepted = state.offerAsset(ticket, PlacementSpec(model.assetLocation, model.displayName))
            if (accepted && replacing) haptic.selection()
            return@LaunchedEffect
        }
        val instance = try { modelLoader.loadModelInstance(model.assetLocation) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (!state.controller.acceptsAsset(ticket)) {
            instance?.let { modelLoader.destroyModel(it.model) }
            return@LaunchedEffect
        }
        state.modelLoading = false
        if (instance == null) {
            state.modelError = true
            logModelLoadFailed(telemetrySampleId, ModelLoadFailure.DecodeFailed)
            return@LaunchedEffect
        }
        roomBuild = null
        roomAsset = null
        state.modelInstance = instance
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

    val ownedInstance = state.modelInstance
    DisposableEffect(ownedInstance) {
        onDispose { ownedInstance?.let { modelLoader.destroyModel(it.model) } }
    }
    DisposableEffect(state) {
        onDispose { state.clearAll(); state.modelInstance = null }
    }

    val build = roomBuild
    val placedRoomContent: (@Composable ARSceneScope.(AutoPlacementResult) -> Unit)? =
        if (build == null) null else { placement ->
            AutoPlacementNode(
                placement = placement, state = state.controller,
                onInvalidMove = { state.dragOffSurface = it },
                onScaleChanged = { percent, atBase, _ ->
                    state.scalePercent = percent
                    state.isRealWorldSize = atBase
                },
            ) {
                DollhouseReplay(build, engine, materialLoader, playing = roomPlaying)
            }
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
            replacementAssetLocation = roomAsset,
            placedContent = placedRoomContent,
        )
    }

    PlacementModelPickerSheet(models = models, picker = picker)
}
