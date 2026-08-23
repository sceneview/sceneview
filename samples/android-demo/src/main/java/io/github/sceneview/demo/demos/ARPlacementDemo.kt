package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.sceneview.demo.AssetSourceState
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.placement.BUNDLED_PLACEMENT_MODELS
import io.github.sceneview.demo.common.placement.PlacementModel
import io.github.sceneview.demo.common.placement.PlacementModelBar
import io.github.sceneview.demo.common.placement.PlacementModelSource
import io.github.sceneview.demo.common.placement.TapToPlaceExperience
import io.github.sceneview.demo.common.placement.armed
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
import java.io.File

/**
 * `ar-placement` — the **feature-demo** host of the one canonical tap-to-place screen
 * ([#2482](https://github.com/sceneview/sceneview/issues/2482)).
 *
 * The screen itself is [TapToPlaceExperience], the identical composable the AR View tab
 * renders: same centre reticle, same status vocabulary, same top-start back arrow
 * affordance (here supplied by [DemoScaffold]'s own app bar), same
 * [PlacementModelBar] naming what the next tap will place, same picker sheet. This file
 * contributes only what makes it a *demo* rather than a launcher:
 *
 *  - **A richer catalogue.** The five bundled models both surfaces offer
 *    ([BUNDLED_PLACEMENT_MODELS]) plus the six streamed CC-BY Sketchfab models of
 *    [SampleAssets.byCategory]`["ar_placement"]`
 *    ([#1152](https://github.com/sceneview/sceneview/issues/1152)). The picker marks the
 *    streamed rows; nothing else about them behaves differently.
 *  - **Developer toggles** in the Settings sheet: Snap-to-plane (#1883) and Show reticle
 *    (#1882), forwarded to the shared session as parameters.
 *  - **The QA tracking-failure shim** ([ForceTrackingFailureMenu], #1881).
 *  - **The asset-source chip**, which reports where the *armed* row's bytes came from.
 *
 * ### What this file no longer contains, and why
 *
 * The two chip strips ("Bundled cycle" + "Auto-cycle"), the "Next tap places: …" caption
 * and the "Clear All" button are gone. They were this demo's private answer to questions
 * the canonical experience now answers once, in the same words, on both surfaces: the bar
 * names the armed model, the pill counts what is placed, Reset clears the room.
 *
 * The **auto-cycle** in particular is deliberately not carried over. It made every tap
 * place a different model from the one the picker showed — which is, from the user's side,
 * indistinguishable from the stale-picker defect
 * ([#2476](https://github.com/sceneview/sceneview/issues/2476)) this whole consolidation
 * exists to make impossible. One picker, one armed model, one answer.
 *
 * Model resolution still happens inside the shared session's `onPlaceModel`, at tap time
 * on the main thread — the #2476 invariant, now with a single call site.
 */
@Composable
fun ARPlacementDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val context = LocalContext.current

    // The shared session owns the placed-model list, anchors and camera/plane/reticle
    // signals. The demo reads it for Reset; the session writes it on every tap/frame.
    val state = rememberTapToPlaceState()

    // Canonical picker selection, shared with the AR View tab's implementation.
    // The Model Viewer's "View in AR" handoff passes the model it was showing as
    // the `model` route argument (an asset path or its bare file stem); when it
    // names a bundled row that row is armed instead of the catalogue's first.
    val requestedModel = remember { DemoSettings.requestedModel.also { DemoSettings.requestedModel = null } }
    val picker = rememberPlacementPickerState(
        BUNDLED_PLACEMENT_MODELS.firstOrNull { model ->
            model.assetLocation == requestedModel ||
                model.assetLocation.substringAfterLast('/').substringBeforeLast('.') == requestedModel
        }?.id ?: BUNDLED_PLACEMENT_MODELS.first().id,
    )

    // Settings toggles (#1883). Defaults preserve the strict plane-only behaviour.
    // Show-reticle is forwarded to the session's `showReticle` param; dev users can
    // disable it for screenshots.
    var snapToPlane by remember { mutableStateOf(true) }
    var showReticle by remember { mutableStateOf(true) }

    // Streamed `ar_placement` slugs from SampleAssets (#1152 Stage 2).
    val placementSlugs = remember { SampleAssets.byCategory["ar_placement"].orEmpty() }

    // Warm the `ar_placement` cache so taps land instantly once the user picks a row. The
    // resolver dedupes concurrent calls, so when the per-selection resolve fires below it
    // picks up the already-staged file.
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
    val models: List<PlacementModel> = remember(placementSlugs, armedSlug, armedFile) {
        BUNDLED_PLACEMENT_MODELS + placementSlugs.map { slug ->
            val isArmed = slug.uid == armedSlug?.uid
            PlacementModel(
                id = streamedModelId(slug),
                displayName = slug.displayName,
                assetLocation = if (isArmed && armedFile != null) {
                    "file://${armedFile.absolutePath}"
                } else {
                    slug.fallbackBundledPath
                },
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
    val assetSource = if (armedSlug == null) {
        AssetSourceState.Bundled
    } else {
        AssetSourceProbe.of(
            resolvedFile = armedFile,
            hasApiKey = SketchfabConfig.apiKey != null,
            loaded = armedFile != null,
        )
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_placement_title),
        onBack = onBack,
        assetSource = assetSource,
        controls = {
            Text(
                text = "Aim the centre reticle at a surface, then tap to drop the model " +
                    "named on the bar. Each placed model is editable: drag to translate, " +
                    "pinch to scale, twist to rotate — the active gesture is shown in the " +
                    "top-center pill.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
            Text(
                text = "Five models ship inside the APK; the rows marked Streamed are " +
                    "CC-BY models fetched from Sketchfab the first time you arm them, " +
                    "and fall back to a bundled stand-in until they land.",
                style = MaterialTheme.typography.labelSmall,
            )

            // Settings toggles (#1883). Snap-to-plane gates the session's hit policy:
            // ON ⇒ only detected planes accept placements; OFF ⇒ any tracked hit.
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
            HorizontalDivider()
            LabeledToggle(
                label = "Snap to plane",
                checked = snapToPlane,
                onCheckedChange = { snapToPlane = it },
            )
            Text(
                text = if (snapToPlane) {
                    "Only detected planes accept placements (recommended)."
                } else {
                    "Free placement — any tracked surface accepts placements (points, depth)."
                },
                style = MaterialTheme.typography.labelSmall,
            )

            // Show-reticle toggle (#1882 / #1883). Dev-only: hide the centre disc for
            // screenshots without losing the underlying hit-test pipeline.
            LabeledToggle(
                label = "Show reticle (dev)",
                checked = showReticle,
                onCheckedChange = { showReticle = it },
            )

            // Developer-only debug toggle — visible when QA mode is on. Lets QA
            // force-emit each TrackingFailureReason so the actionable-message overlay can
            // be validated without staging a real failure. See
            // io.github.sceneview.demo.common.ForcedTrackingFailure / #1881.
            ForceTrackingFailureMenu()
        },
        // The SAME bar the AR View tab floats over the camera, handed to the scaffold's
        // bottom band so it stacks clear of the Settings FAB instead of fighting it
        // (#3237). The control is shared; only the container differs, because a demo
        // screen HAS a container and a fullscreen tab does not.
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
            snapToPlane = snapToPlane,
            showReticle = showReticle,
        )
    }
}

/**
 * Picker id for a streamed slug. Prefixed so a Sketchfab uid can never collide with a
 * bundled row's id, and stable across catalogue rebuilds.
 */
private fun streamedModelId(slug: SketchfabSlug): String = "streamed:${slug.uid}"

@Composable
private fun LabeledToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = SceneViewTokens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
