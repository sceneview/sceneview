package io.github.sceneview.demo.demos

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.ar.core.ArCoreApk
import io.github.sceneview.demo.AssetSourceState
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ArDemoPermissionGate
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.placement.BUNDLED_PLACEMENT_MODELS
import io.github.sceneview.demo.common.placement.DOWNLOADABLE_REAL_PRODUCTS
import io.github.sceneview.demo.common.placement.downloadablePlacementRow
import io.github.sceneview.demo.common.placement.PlacementChooserScreen
import io.github.sceneview.demo.common.placement.OPENED_FILE_PLACEMENT_ROW_ID
import io.github.sceneview.demo.common.placement.PlacementFlowPhase
import io.github.sceneview.demo.common.placement.PlacementModel
import io.github.sceneview.demo.common.placement.PlacementModelSource
import io.github.sceneview.demo.common.placement.PlacementBackAction
import io.github.sceneview.demo.common.placement.TapToPlaceExperience
import io.github.sceneview.demo.common.placement.placementBackAction
import io.github.sceneview.demo.common.placement.rememberPlacementFlowState
import io.github.sceneview.demo.common.placement.rememberPlacementPickerState
import io.github.sceneview.demo.common.placement.rememberTapToPlaceState
import io.github.sceneview.demo.common.placement.resolveRequestedExtraPlacementRow
import io.github.sceneview.demo.hdpack.HdPack
import io.github.sceneview.demo.hdpack.rememberHdPackStore
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * `ar-placement` — **the** AR placement flow of the demo app
 * ([#3405](https://github.com/sceneview/sceneview/issues/3405)).
 *
 * ## What changed, and what the issue actually said
 *
 * The report was filed from this screen: *"faut que tu revoies complètement l'ergonomie de
 * cet écran, il date pas mal… j'ai l'impression que tu as plein d'écrans pour placer des
 * objets en AR différents… il faut juste qu'il puisse avoir un input avant pour savoir quel
 * modèle."* Three things, in order of how much they mattered:
 *
 * 1. **An input up front.** You now choose the model on a still, themed screen
 *    ([PlacementChooserScreen]) and *then* open the camera. Before this, every placement
 *    surface in the app dropped you into a viewfinder and asked afterwards, through a sheet
 *    floated over a camera that was still converging its first plane.
 * 2. **One flow, not many.** `ar-instant-placement` was a 701-line hand-rolled second
 *    implementation of this same screen (the exact defect class of
 *    [#2476](https://github.com/sceneview/sceneview/issues/2476)). It is gone, and so is
 *    the "placement accuracy" mode that briefly replaced it: placement with both a cursor
 *    and a tap "makes no sense, it must lose half the users" (owner decision, 2026-09-22).
 * 3. **The UX itself.** The AR half is [TapToPlaceExperience], shared with the AR View tab
 *    (#2482): the chosen object is placed **automatically** on the first usable floor
 *    surface — no reticle, no tap, no plane grid (plan §2.1/§2.6) — then one coaching line
 *    at a time (#3326), contact shadows (#2241), drag / twist / pinch about the grounded
 *    base with a real-world-size detent, a 10 s "No surface found." card and
 *    tracking-lost / recovering states.
 *
 * ## The two phases
 *
 * [PlacementFlowPhase.CHOOSING] renders no AR at all — no ARCore session, no Filament
 * viewport. That is what makes the front half of this demo reviewable on `emulator-5554`,
 * where ARCore has no camera HAL
 * ([#2754](https://github.com/sceneview/sceneview/issues/2754)).
 * [PlacementFlowPhase.PLACING] is the camera, entered with a subject already chosen — the
 * rule AR Model Viewer ("Will It Fit") arrived at independently and wrote down.
 *
 * Back from the camera lands on the chooser with the same model still armed, not out of the
 * demo ([placementBackAction]).
 *
 * ## What this file still contributes over the AR View tab
 *
 *  - **A richer catalogue** — the bundled real products of [BUNDLED_PLACEMENT_MODELS] plus
 *    the rest of the HD pack's everyday products, downloaded when chosen (#4560). The six
 *    streamed Sketchfab `ar_placement` rows (#1152) it used to append are no longer offered.
 *  - **The asset-source chip**, reporting where the *armed* row's bytes came from (#2953).
 *  - **The QA tracking-failure shim** ([ForceTrackingFailureMenu], #1881).
 *
 * Model resolution happens in [TapToPlaceExperience], which offers the armed row to the
 * session with a generation ticket — the #2476 invariant, with a single call site.
 */
@Composable
fun ARPlacementDemo(onBack: () -> Unit) {
    val context = LocalContext.current
    val sampleId = io.github.sceneview.demo.telemetry.LocalSampleId.current

    // Phase holder. Saveable, so a rotation in the camera does not dump the user back onto
    // the chooser.
    val flow = rememberPlacementFlowState()
    var wallMode by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(DemoSettings.consumeInitialTab() == 1)
    }

    // The shared session owns the placement, its anchor and the camera/plane signals. The
    // demo reads it for Reset placement; the session writes it on every frame. Keyed on
    // `sessionKey` so the camera-error card's *Try again* can recreate the ARCore session.
    var sessionKey by remember { mutableStateOf(0) }
    val state = key(sessionKey) { rememberTapToPlaceState() }

    // Canonical picker selection, shared with the AR View tab's implementation and with the
    // in-AR sheet — one selection, two surfaces, so they cannot disagree.
    //
    // The Model Viewer's "View in AR" handoff passes the model it was showing as the `model`
    // route argument (an asset path or its bare file stem); when it names a bundled row that
    // row is armed instead of the catalogue's first. The handoff arrives having already
    // answered "what are we placing?", so it skips the chooser and opens the camera — which
    // is the same contract, reached from a different door.
    val requestedModel = remember { DemoSettings.requestedModel.also { DemoSettings.requestedModel = null } }
    val requestedDisplayName = remember(requestedModel) {
        DemoSettings.requestedModelDisplayName.also { DemoSettings.requestedModelDisplayName = null }
    }
    // The handoff must win even when the model it names is not one of the curated six —
    // notably the Model Viewer's Damaged Helmet, deliberately left out of
    // [BUNDLED_PLACEMENT_MODELS] by #2023 ("a helmet hovering over a floor reads as a test
    // payload"). That curation is a rule about what the CHOOSER offers a fresh visitor, not
    // a veto over an explicit "take me to AR with THIS model" request (#3493) — before this,
    // an uncatalogued model silently produced no [requestedRow] below, so [flow.enterAr]
    // never fired and the user landed on the picker instead of the camera.
    //
    // "Open with SceneView" (#3482) is the special case of this: the handoff may name a file
    // the user opened rather than any bundled asset — a `file://` path staged by
    // `OpenedModelIntent`. Both cases become a row of its own, first in the picker; the opened
    // file additionally carries the real-world size the viewer measured on the loaded model —
    // the whole point for a 3MF, which carries true manufacturing size, so a 60 mm print has
    // to arrive in the room as 60 mm rather than as the catalogue's default.
    val requestedExtraRow = remember(requestedModel) {
        resolveRequestedExtraPlacementRow(
            requestedModel = requestedModel,
            requestedDisplayName = requestedDisplayName,
            openedDisplayName = DemoSettings.openedModelDisplayName,
            openedSizeMeters = DemoSettings.openedModelSizeMeters,
        )
    }
    val requestedRow = remember(requestedModel) {
        requestedExtraRow ?: BUNDLED_PLACEMENT_MODELS.firstOrNull { model ->
            model.assetLocation == requestedModel ||
                model.assetLocation.substringAfterLast('/').substringBeforeLast('.') == requestedModel
        }
    }
    val picker = rememberPlacementPickerState(
        requestedRow?.id ?: BUNDLED_PLACEMENT_MODELS.first().id,
    )
    LaunchedEffect(requestedRow) {
        if (requestedRow != null) flow.enterAr()
    }

    // ARCore availability, for the chooser's CTA. Resolved here rather than inside the
    // chooser so the probe is not restarted every time the user swaps a model.
    val arSupported by produceState<Boolean?>(initialValue = null, context) {
        var availability = ArCoreApk.getInstance().checkAvailability(context)
        repeat(20) {
            if (availability != ArCoreApk.Availability.UNKNOWN_CHECKING) return@repeat
            delay(100)
            availability = ArCoreApk.getInstance().checkAvailability(context)
        }
        value = availability == ArCoreApk.Availability.SUPPORTED_INSTALLED ||
            availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ||
            availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD
    }

    // The everyday catalogue (#4560): the bundled real products first, then the rest of the
    // HD pack's everyday shelves — furniture, cars, bikes, home, tech, fashion — each at its
    // real size. An HD row that is not on disk yet is `pending`: choosing it starts its
    // download, the card says so, and the session places nothing until the real file lands.
    val hdStore = rememberHdPackStore()
    val readyIds: Set<String> by remember(hdStore) {
        hdStore?.readyIds ?: MutableStateFlow(emptySet<String>())
    }.collectAsState()
    val armedHdId = remember(picker.selectedId, hdStore) {
        picker.selectedId.takeIf { id -> hdStore?.manifest?.asset(id) != null }
    }
    LaunchedEffect(armedHdId, readyIds) {
        if (armedHdId != null && armedHdId !in readyIds) HdPack.downloadNow(context, armedHdId)
    }
    val models: List<PlacementModel> = remember(requestedExtraRow, hdStore, readyIds, armedHdId) {
        val downloadable = hdStore?.let { store ->
            DOWNLOADABLE_REAL_PRODUCTS.mapNotNull { product ->
                val asset = store.manifest.asset(product.id) ?: return@mapNotNull null
                val ready = product.id in readyIds
                product.downloadablePlacementRow(
                    fileLocation = "file://${store.fileFor(asset).absolutePath}",
                    ready = ready,
                    downloading = !ready && product.id == armedHdId,
                )
            }
        }.orEmpty()
        listOfNotNull(requestedExtraRow) + BUNDLED_PLACEMENT_MODELS + downloadable
    }

    // Per-demo origin chip (#1152 Stage 3), reporting the ARMED row: bundled bytes, or an HD
    // pack file that is on disk ("Streamed") or still downloading ("Streaming").
    val armedRow = models.firstOrNull { it.id == picker.selectedId }
    val assetSource = when {
        armedRow == null || armedRow.id == OPENED_FILE_PLACEMENT_ROW_ID -> null
        armedRow.source != PlacementModelSource.Downloadable -> AssetSourceState.Bundled
        armedRow.pending -> AssetSourceState.Streaming
        else -> AssetSourceState.Streamed
    }

    // One back ladder for both phases, so the camera can never be exited by accident and the
    // chooser can never trap the user in the demo. Pure rung, unit-tested.
    val onBackPressed: () -> Unit = {
        when (placementBackAction(flow.phase)) {
            PlacementBackAction.RETURN_TO_CHOOSER -> {
                // Close this session generation AND recreate the holder: `state` lives
                // outside the phase branches, so the next camera entry would otherwise
                // inherit a dismissed controller and an old phase.
                state.clearAll()
                sessionKey++
                flow.backToChooser()
            }

            PlacementBackAction.LEAVE_DEMO -> onBack()
        }
    }
    BackHandler(onBack = onBackPressed)

    if (flow.phase == PlacementFlowPhase.CHOOSING) {
        PlacementChooserScreen(
            models = models,
            picker = picker,
            flow = flow,
            arSupported = arSupported,
            onBack = onBack,
            title = stringResource(R.string.demo_ar_placement_title),
            teaches = stringResource(R.string.ar_placement_teaches),
            wallMode = wallMode,
            onWallModeChange = { next ->
                if (next != wallMode) {
                    io.github.sceneview.demo.telemetry.logSampleModeChange(
                        sampleId,
                        AR_PLACEMENT_SURFACE_MODES[if (next) 1 else 0],
                    )
                    wallMode = next
                }
            },
        )
        return
    }

    // Only the camera phase asks for the camera: the chooser above — and its "View in 3D"
    // way out — stays open to someone who refused it (#4139).
    ArDemoPermissionGate(
        title = stringResource(R.string.demo_ar_placement_title),
        onBack = onBackPressed,
    ) {
        if (wallMode) {
            WallPlacementDemo(
                onBack = onBackPressed,
                playbackDataset = io.github.sceneview.demo.rememberArPlaybackDataset(),
            )
            return@ArDemoPermissionGate
        }

        val engine = rememberEngine()
        val modelLoader = rememberModelLoader(engine)
        val materialLoader = rememberMaterialLoader(engine)

        DemoScaffold(
            title = stringResource(R.string.demo_ar_placement_title),
            // The scaffold's top-start arrow is the same rung as the system gesture: it leaves
            // the camera for the chooser, not the demo.
            onBack = onBackPressed,
            assetSource = assetSource,
            controls = {
                Text(
                    text = stringResource(R.string.ar_placement_teaches),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.ar_placement_catalogue_note),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = SceneViewTokens.Space.sm),
                )
                // The QA tracking-failure shim: a debug affordance for a failure you can only
                // stage while a session is running (#1881). It drives the tracking-lost state.
                ForceTrackingFailureMenu()
            },
            // The two in-session actions, in the dock — where every other demo in the app puts
            // its actions. They used to be a `PlacementModelBar` floated in the scaffold's
            // bottom band (and over the camera on the AR View tab): a `primaryContainer` FAB
            // and a `secondaryContainer` disc, i.e. theme colours over a camera frame that has
            // no theme. The dock's Controls item (Settings) is appended by the scaffold, so
            // this screen's dock is Models · Reset · Settings. Models is `Category`, the same
            // glyph as the Model Viewer's Models item: one action, one icon across the app.
            dock = listOf(
                DockItem(
                    icon = Icons.Filled.Category,
                    label = stringResource(R.string.ar_dock_models_label),
                    caption = stringResource(R.string.ar_dock_models_caption),
                    onClick = picker::openSheet,
                ),
                // §2.2 *Restarting placement*: removes the anchor, keeps the chosen asset,
                // scans again.
                DockItem(
                    icon = Icons.Filled.Refresh,
                    label = stringResource(R.string.ar_dock_reset_label),
                    caption = stringResource(R.string.ar_dock_reset_caption),
                    onClick = { state.resetPlacement() },
                    enabled = state.placedCount > 0,
                ),
            ),
        ) {
            key(sessionKey) {
                TapToPlaceExperience(
                    models = models,
                    picker = picker,
                    state = state,
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    // "View in 3D" on the no-surface card: back to the chooser, where the model
                    // is shown on a still, themed screen.
                    onViewIn3D = onBackPressed,
                    // "Try again" on the camera-error card: a fresh ARCore session.
                    onRestartSession = {
                        state.clearAll()
                        sessionKey++
                    },
                )
            }
        }
    }
}

internal val AR_PLACEMENT_SURFACE_MODES = listOf("place", "wall")
