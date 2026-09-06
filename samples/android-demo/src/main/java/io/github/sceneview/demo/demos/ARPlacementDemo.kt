package io.github.sceneview.demo.demos

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.ar.core.ArCoreApk
import io.github.sceneview.demo.AssetSourceState
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.placement.BUNDLED_PLACEMENT_MODELS
import io.github.sceneview.demo.common.placement.PlacementChooserScreen
import io.github.sceneview.demo.common.placement.PlacementFlowPhase
import io.github.sceneview.demo.common.placement.PlacementModel
import io.github.sceneview.demo.common.placement.PlacementModelBar
import io.github.sceneview.demo.common.placement.PlacementModelSource
import io.github.sceneview.demo.common.placement.PlacementBackAction
import io.github.sceneview.demo.common.placement.TapToPlaceExperience
import io.github.sceneview.demo.common.placement.armed
import io.github.sceneview.demo.common.placement.placementBackAction
import io.github.sceneview.demo.common.placement.rememberPlacementFlowState
import io.github.sceneview.demo.common.placement.rememberPlacementPickerState
import io.github.sceneview.demo.common.placement.rememberTapToPlaceState
import io.github.sceneview.demo.sketchfab.AssetSourceProbe
import io.github.sceneview.demo.sketchfab.SampleAssets
import io.github.sceneview.demo.sketchfab.SketchfabAssetResolver
import io.github.sceneview.demo.sketchfab.SketchfabConfig
import io.github.sceneview.demo.sketchfab.SketchfabSlug
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.delay
import java.io.File

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
 *    implementation of this same screen — its own placed-model class, its own state holder,
 *    its own model list, its own copy of the #2302 overload-trap KDoc, no reticle, hardcoded
 *    hex colours, and an auto-cycle that placed a *different* model from the one the picker
 *    showed (the exact defect class of
 *    [#2476](https://github.com/sceneview/sceneview/issues/2476)). It is gone. What it
 *    taught — `Config.InstantPlacementMode.LOCAL_Y_UP` and the
 *    `SCREENSPACE_WITH_APPROXIMATE_DISTANCE → FULL_TRACKING` refinement — is a **mode** of
 *    this flow now, chosen on the same screen as the model, and demonstrated better: a real
 *    plane hit still wins when there is one, which the retired demo did not allow.
 * 3. **The UX itself.** The AR half is [TapToPlaceExperience], unchanged and already shared
 *    with the AR View tab (#2482): centre reticle (#1882), one coaching line at a time
 *    (#3326), contact shadows on placed models (#2241/#2657), drag / twist / pinch with a
 *    real-world-size detent, and the plane grid receding once the room has something in it.
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
 *  - **A richer catalogue** — the six bundled models of [BUNDLED_PLACEMENT_MODELS] plus the
 *    six streamed CC-BY Sketchfab rows of `SampleAssets.byCategory["ar_placement"]` (#1152).
 *  - **The asset-source chip**, reporting where the *armed* row's bytes came from (#2953).
 *  - **The QA tracking-failure shim** ([ForceTrackingFailureMenu], #1881).
 *
 * Model resolution still happens inside the shared session's `onPlaceModel`, at tap time on
 * the main thread — the #2476 invariant, with a single call site.
 */
@Composable
fun ARPlacementDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val context = LocalContext.current

    // Phase + session options (mode, snap-to-plane, reticle). Saveable, so a rotation in the
    // camera does not dump the user back onto the chooser.
    val flow = rememberPlacementFlowState()

    // The shared session owns the placed-model list, anchors and camera/plane/reticle
    // signals. The demo reads it for Reset; the session writes it on every tap/frame.
    val state = rememberTapToPlaceState()

    // Canonical picker selection, shared with the AR View tab's implementation and with the
    // in-AR sheet — one selection, two surfaces, so they cannot disagree.
    //
    // The Model Viewer's "View in AR" handoff passes the model it was showing as the `model`
    // route argument (an asset path or its bare file stem); when it names a bundled row that
    // row is armed instead of the catalogue's first. The handoff arrives having already
    // answered "what are we placing?", so it skips the chooser and opens the camera — which
    // is the same contract, reached from a different door.
    val requestedModel = remember { DemoSettings.requestedModel.also { DemoSettings.requestedModel = null } }
    // "Open with SceneView" (#3482): the handoff may name a file the user opened rather than a
    // catalogue row — a `file://` path staged by `OpenedModelIntent`. It becomes a row of its own,
    // first in the picker, at the size the viewer measured on the loaded model. That size is the
    // whole point for a 3MF: the format carries true manufacturing size, so a 60 mm print has to
    // arrive in the room as 60 mm rather than as the catalogue's default.
    val openedRow = remember(requestedModel) {
        val location = requestedModel?.takeIf { it.startsWith("file://") } ?: return@remember null
        PlacementModel(
            id = "opened-file",
            // The viewer carries the user's own file name across; the location's basename is the
            // staged copy's fixed name (`opened-model`), which would tell the user nothing.
            displayName = DemoSettings.openedModelDisplayName
                ?: location.substringAfterLast('/').ifBlank { "Your file" },
            assetLocation = location,
            realWorldSizeMeters = DemoSettings.openedModelSizeMeters
                ?.takeIf { it.isFinite() && it > 0f }
                ?: PlacementModel.DEFAULT_REAL_WORLD_SIZE_METERS,
        )
    }
    val requestedRow = remember(requestedModel) {
        openedRow ?: BUNDLED_PLACEMENT_MODELS.firstOrNull { model ->
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

    // Streamed `ar_placement` slugs from SampleAssets (#1152 Stage 2).
    val placementSlugs = remember { SampleAssets.byCategory["ar_placement"].orEmpty() }

    // Warm the `ar_placement` cache so taps land instantly once the user picks a row. The
    // resolver dedupes concurrent calls, so when the per-selection resolve fires below it
    // picks up the already-staged file. Now that the chooser precedes the camera, this has a
    // whole screen's worth of dwell time to land in.
    LaunchedEffect(Unit) {
        runCatching {
            SketchfabAssetResolver.getInstance(context).prefetchAll("ar_placement")
        }
    }

    // Which streamed slug (if any) the picker currently has armed.
    val armedSlug: SketchfabSlug? = remember(placementSlugs, picker.selectedId) {
        placementSlugs.firstOrNull { streamedModelId(it) == picker.selectedId }
    }

    // Resolve the armed slug to a local file — `null` while downloading / staging. Only
    // the armed one is resolved: the other five have no bytes to fetch until they are
    // picked, and prefetch above already warmed the cache for them.
    val armedFile: File? = armedSlug?.let { slug ->
        produceState<File?>(initialValue = null, key1 = slug.uid) {
            value = runCatching {
                SketchfabAssetResolver.getInstance(context).resolve(slug)
            }.getOrNull()
        }.value
    }

    // The catalogue the picker offers. A streamed row that has not landed yet carries its
    // OWN bundled fallback as `assetLocation` (never null), so a tap during the download
    // places that slug's stand-in rather than nothing — and the row is flagged `pending`
    // so the bar and the card both say "Streaming …" instead of lying about it.
    val models: List<PlacementModel> = remember(placementSlugs, armedSlug, armedFile, openedRow) {
        listOfNotNull(openedRow) + BUNDLED_PLACEMENT_MODELS + placementSlugs.map { slug ->
            val isArmed = slug.uid == armedSlug?.uid
            PlacementModel(
                id = streamedModelId(slug),
                displayName = slug.displayName,
                assetLocation = if (isArmed && armedFile != null) {
                    "file://${armedFile.absolutePath}"
                } else {
                    slug.fallbackBundledPath
                },
                // Real-world size for the pinch read-out's 100 % (#3326). The registry
                // already records each slug's expected post-load bounding-**sphere radius**
                // in metres (it is what `boundsAreSane` validates a download against), so the
                // object's extent is twice it. That is a measured number about the actual
                // asset, which is exactly what a flat 0.3 m for every row was not.
                realWorldSizeMeters = slug.scaleToUnits * 2f,
                source = PlacementModelSource.Streamed,
                pending = isArmed && armedFile == null,
            )
        }
    }

    // Per-demo offline indicator chip (#1152 Stage 3), reporting the ARMED row.
    //
    // For a streamed row the origin is MEASURED from the file the resolver handed back,
    // never inferred from `SketchfabConfig.apiKey` (#2953): a key can be present and the
    // resolve still land on the bundled fallback — no network, aeroplane mode, a 4xx, the
    // WAF — and this chip would then claim "Streamed (cached)" over the stand-in the next
    // tap actually places. `loaded` is the FILE here, not a parsed `ModelInstance`: a tap
    // places whatever `armedFile` holds, so that is the moment the chip has something true
    // to say. See [AssetSourceProbe].
    val assetSource = if (openedRow != null && picker.selectedId == openedRow.id) {
        // The user's own file: neither bundled nor streamed. No chip rather than a wrong one.
        null
    } else if (armedSlug == null) {
        AssetSourceState.Bundled
    } else {
        AssetSourceProbe.of(
            resolvedFile = armedFile,
            hasApiKey = SketchfabConfig.apiKey != null,
            loaded = armedFile != null,
        )
    }

    // One back ladder for both phases, so the camera can never be exited by accident and the
    // chooser can never trap the user in the demo. Pure rung, unit-tested.
    val onBackPressed: () -> Unit = {
        when (placementBackAction(flow.phase)) {
            PlacementBackAction.RETURN_TO_CHOOSER -> {
                state.clearAll()
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
        )
        return
    }

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
            // Snap-to-plane, Show-reticle and the placement mode all moved to the chooser
            // (#3405) — they decide how the session behaves, so they belong to setup, not to
            // a sheet you open after the first tap has already taught you the wrong thing.
            //
            // The QA tracking-failure shim stays: it is a debug affordance for a failure you
            // can only stage while a session is running (#1881).
            ForceTrackingFailureMenu()
        },
        // The SAME bar the AR View tab floats over the camera, handed to the scaffold's
        // bottom band so it stacks clear of the Settings FAB instead of fighting it
        // (#3237). Tapping it opens the in-AR picker sheet — swapping a model mid-session
        // without walking back to the chooser.
        bottomOverlay = {
            PlacementModelBar(
                model = models.armed(picker),
                onPickModel = picker::openSheet,
                onReset = { state.clearAll() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SceneViewTokens.Space.md)
                    .padding(end = settingsFabReservedSpace),
            )
        },
    ) {
        TapToPlaceExperience(
            models = models,
            picker = picker,
            state = state,
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            // DemoScaffold's app bar already carries the app-wide top-start back arrow,
            // and the bar lives in the scaffold's bottom band — so the experience draws
            // neither here. Everything else it renders is identical to the AR View tab.
            onBack = null,
            showModelBar = false,
            snapToPlane = flow.snapToPlane,
            showReticle = flow.showReticle,
            instantPlacement = flow.instantEnabled,
        )
    }
}

/**
 * Picker id for a streamed slug. Prefixed so a Sketchfab uid can never collide with a
 * bundled row's id, and stable across catalogue rebuilds.
 */
private fun streamedModelId(slug: SketchfabSlug): String = "streamed:${slug.uid}"
