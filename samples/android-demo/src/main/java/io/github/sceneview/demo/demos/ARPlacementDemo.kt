package io.github.sceneview.demo.demos

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.AssetSourceState
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.placement.PlacementSpec
import io.github.sceneview.demo.common.placement.TapToPlaceArSession
import io.github.sceneview.demo.common.placement.rememberTapToPlaceState
import io.github.sceneview.demo.sketchfab.AssetSourceProbe
import io.github.sceneview.demo.sketchfab.SampleAssets
import io.github.sceneview.demo.sketchfab.SketchfabAssetResolver
import io.github.sceneview.demo.sketchfab.SketchfabConfig
import io.github.sceneview.demo.sketchfab.SketchfabSlug
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import java.io.File

/**
 * Interactive AR tap-to-place demo with a "Pick what to place" picker and a screen-center
 * placement reticle.
 *
 * This is the **feature-demo** host of the shared
 * [io.github.sceneview.demo.common.placement.TapToPlaceArSession] engine
 * ([#2482](https://github.com/sceneview/sceneview/issues/2482) Option A, PR 2/4): the demo
 * keeps its developer-facing chrome — the [DemoScaffold] top bar, the streamed/bundled chip
 * pickers, the Snap-to-plane / Show-reticle toggles, the Clear All button, the "Next tap
 * places:" preview and the [ForceTrackingFailureMenu] QA shim — and delegates the whole AR
 * session (plane visualisation, centre reticle, texture-settle gating, PAUSED-surviving
 * anchors, per-asset rotation correction, the editable placed models, the gesture pill, the
 * camera-init scrim and the plane-gated status vocabulary) to the shared session.
 *
 * **Picker — Stage 2 ([#1152](https://github.com/sceneview/sceneview/issues/1152)).** The
 * controls sheet exposes a chip row sourced from [SampleAssets.byCategory]`["ar_placement"]`
 * — coffee mug / houseplant / wooden crate / side table / floor lamp / picture frame, all
 * streamed CC-BY models from Sketchfab via [SketchfabAssetResolver]. Tapping a chip arms
 * that slug as the next placed model; subsequent taps on a plane place a fresh instance.
 *
 * **Bundled model picker ([#1883](https://github.com/sceneview/sceneview/issues/1883)).** A
 * second chip row lets the user lock the bundled cycle to a single specific GLB (Soldier /
 * Fox / Lantern / Toy Car / Shiba) or keep the auto-cycle that rotates through all
 * five on every tap. Bundled-mode chips are mutually exclusive with the streamed picker
 * above — selecting a streamed slug overrides the bundled choice for that tap.
 *
 * **Settings ([#1883](https://github.com/sceneview/sceneview/issues/1883)).** The sheet also
 * exposes a Snap-to-plane toggle (default ON — accept only detected plane hits) and a Show
 * reticle toggle (default ON — dev-only). The "Clear All" button wipes every placed model and
 * detaches the underlying ARCore anchors via [TapToPlaceState.clearAll].
 *
 * The 3-tier model resolution (resolved streamed slug → `file://` URI; else bundled lock;
 * else auto-cycle) runs inside `onPlaceModel`, which the shared session invokes at tap time —
 * so the picker can never go stale between composition and tap
 * ([#2476](https://github.com/sceneview/sceneview/issues/2476) invariant).
 */

private data class CycleEntry(val assetPath: String, val displayName: String)

// Curated list of bundled GLBs that look good as small AR objects on a plane.
// Each has a distinct silhouette and material so the cycle visibly rotates through variety.
// (Khronos Avocado dropped per audit #949 — 7.7 MB grey-green low-poly that read as
// 2003-textbook quality next to the lantern brass-and-PBR neighbours.)
// The floating Damaged Helmet was dropped per #2023 — every cycle entry is now a
// grounded object (character / animal / household item) that reads as intentional
// content sitting in the room, not a generic test payload hovering above the plane.
private val MODEL_CYCLE = listOf(
    CycleEntry("models/threejs_soldier.glb", "Soldier"),
    CycleEntry("models/khronos_fox.glb", "Fox"),
    CycleEntry("models/khronos_lantern.glb", "Lantern"),
    CycleEntry("models/khronos_toy_car.glb", "Toy Car"),
    CycleEntry("models/shiba.glb", "Shiba")
)

@Composable
fun ARPlacementDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val context = LocalContext.current

    // The shared session owns the placed-model list, anchors and camera/plane/reticle
    // signals. The demo reads it for Clear All; the session writes it on every tap/frame.
    val state = rememberTapToPlaceState()

    // Auto-cycle index. Kept in the host because it advances at tap time inside the
    // `onPlaceModel` lambda below, and resets on Clear All.
    var cycleIndex by remember { mutableStateOf(0) }

    // Streamed `ar_placement` slugs from SampleAssets. selectedSlug == null
    // means "Bundled cycle" (the v4.3.1 default behaviour). Selecting a slug
    // arms it as the next tap's payload, replacing the cycle for that tap.
    val placementSlugs = remember { SampleAssets.byCategory["ar_placement"].orEmpty() }
    var selectedSlug by remember { mutableStateOf<SketchfabSlug?>(null) }

    // Bundled-model picker (#1883). Locks the cycle to a single GLB so the
    // user can predict exactly what each tap places. `null` ⇒ keep the
    // auto-cycle that rotates through all five on every tap (v4.3.1
    // behaviour). Mutually exclusive with the streamed `selectedSlug`
    // chip row above — if a streamed slug is selected and resolved, that
    // wins regardless of `selectedBundledIndex`.
    val requestedModel = remember { DemoSettings.requestedModel.also { DemoSettings.requestedModel = null } }
    var selectedBundledIndex by remember(requestedModel) {
        mutableStateOf(MODEL_CYCLE.indexOfFirst { it.assetPath == requestedModel || it.assetPath.substringAfterLast('/').substringBeforeLast('.') == requestedModel }.takeIf { it >= 0 })
    }

    // Settings toggles (#1883). Defaults preserve the previous strict
    // plane-only behaviour. Show-reticle is forwarded to the session's
    // `showReticle` param; dev users can disable it for screenshots.
    var snapToPlane by remember { mutableStateOf(true) }
    var showReticle by remember { mutableStateOf(true) }

    // Warm the `ar_placement` cache so taps land instantly once the user picks
    // a chip. The resolver dedupes concurrent calls, so when the per-tap
    // resolve fires below it picks up the already-staged file.
    LaunchedEffect(Unit) {
        runCatching {
            SketchfabAssetResolver.getInstance(context).prefetchAll("ar_placement")
        }
    }

    // Resolve the currently-selected slug to a local file (null while
    // downloading / staging the bundled fallback). When null, the next tap
    // falls back to the bundled MODEL_CYCLE.
    val selectedFile: File? = selectedSlug?.let { slug ->
        produceState<File?>(initialValue = null, key1 = slug.uid) {
            value = runCatching {
                SketchfabAssetResolver.getInstance(context).resolve(slug)
            }.getOrNull()
        }.value
    }

    // Per-demo offline indicator chip (#1152 Stage 3). The chip reflects the
    // selected slug's resolve state — `null` means no slug picked yet (cycle
    // mode), so we surface "Offline model" (the cycle is 100% bundled).
    //
    // Once a slug IS picked the origin is MEASURED from the file the resolver handed
    // back, never inferred from `SketchfabConfig.apiKey` (#2953): a key can be present
    // and the resolve still land on the bundled fallback — no network, aeroplane mode, a
    // 4xx, the WAF — and this chip then claimed "Streamed (cached)" over the stand-in
    // the next tap would actually place. `loaded` is the FILE here, not a parsed
    // `ModelInstance`: a tap places whatever `selectedFile` holds, so that is the moment
    // the chip has something true to say. See [AssetSourceProbe].
    val assetSource = if (selectedSlug == null) {
        AssetSourceState.Bundled
    } else {
        AssetSourceProbe.of(
            resolvedFile = selectedFile,
            hasApiKey = SketchfabConfig.apiKey != null,
            loaded = selectedFile != null,
        )
    }

    // What the next tap will spawn — drives the "Next tap places:" preview and the
    // shared session's status pill ("Tap a surface to place {label}").
    val nextLabel = selectedSlug?.let { slug ->
        if (selectedFile != null) slug.displayName
        else stringResource(R.string.demo_ar_placement_picker_streaming, slug.displayName)
    } ?: selectedBundledIndex?.let { MODEL_CYCLE[it].displayName }
        ?: MODEL_CYCLE[cycleIndex % MODEL_CYCLE.size].displayName

    DemoScaffold(
        title = stringResource(R.string.demo_ar_placement_title),
        onBack = onBack,
        assetSource = assetSource,
        controls = {
            Text(
                text = "Aim the centre reticle at a surface, then tap to drop a model. Each model " +
                    "is editable: drag to translate, pinch to scale, twist to rotate — the active " +
                    "gesture is shown in the top-center pill.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.demo_ar_placement_picker_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // "Bundled cycle" chip preserves the v4.3.1 behaviour — each
                // tap rotates through MODEL_CYCLE so QA / offline screenshots
                // stay deterministic.
                FilterChip(
                    selected = selectedSlug == null,
                    onClick = { selectedSlug = null },
                    label = {
                        Text(stringResource(R.string.demo_ar_placement_picker_bundled))
                    },
                )
                placementSlugs.forEach { slug ->
                    FilterChip(
                        selected = selectedSlug?.uid == slug.uid,
                        onClick = { selectedSlug = slug },
                        label = {
                            Text(
                                stringResource(
                                    R.string.demo_ar_placement_picker_streamed,
                                    slug.displayName,
                                )
                            )
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.demo_ar_placement_picker_subtitle),
                style = MaterialTheme.typography.labelSmall,
            )

            // Bundled-model picker (#1883). Lets the user lock the cycle to a
            // single GLB so each tap places exactly what they picked.
            // "Auto-cycle" is the v4.3.1 default. Bundled chips are ignored
            // when a streamed slug above is selected and resolved.
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Bundled model",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = selectedBundledIndex == null,
                    onClick = { selectedBundledIndex = null },
                    label = { Text("Auto-cycle") },
                )
                MODEL_CYCLE.forEachIndexed { index, entry ->
                    FilterChip(
                        selected = selectedBundledIndex == index,
                        onClick = { selectedBundledIndex = index },
                        label = { Text(entry.displayName) },
                    )
                }
            }

            // Settings toggles (#1883). Snap-to-plane gates the session's hit
            // policy: ON ⇒ only detected planes accept placements (v4.3.1
            // behaviour); OFF ⇒ accept any tracked hit (points, depth).
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Snap to plane",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = snapToPlane,
                    onCheckedChange = { snapToPlane = it },
                )
            }
            Text(
                text = if (snapToPlane) {
                    "Only detected planes accept placements (recommended)."
                } else {
                    "Free placement — any tracked surface accepts placements (points, depth)."
                },
                style = MaterialTheme.typography.labelSmall,
            )

            // Show-reticle toggle (#1882 / #1883). Dev-only: hide the centre
            // disc for screenshots without losing the underlying hit-test
            // pipeline.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Show reticle (dev)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = showReticle,
                    onCheckedChange = { showReticle = it },
                )
            }

            // Clear-all: prominent filled Button so it stands out from the
            // chip rows and toggles (#1883). Solid container instead of an
            // OutlinedButton — over the controls sheet the outline read as
            // disabled at a glance. Delegates to the shared state holder, which
            // detaches every ARCore anchor before clearing the placed list.
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    state.clearAll()
                    cycleIndex = 0
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Clear All")
            }

            // Up-next preview so the user knows what the next tap will spawn.
            Text(
                text = "Next tap places: $nextLabel",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            // Developer-only debug toggle — visible when QA mode is on. Lets QA
            // force-emit each TrackingFailureReason so the actionable-message
            // overlay can be validated without staging a real failure. See
            // io.github.sceneview.demo.common.ForcedTrackingFailure / #1881.
            ForceTrackingFailureMenu()
        }
    ) {
        TapToPlaceArSession(
            nextModelLabel = nextLabel,
            // Resolve the asset at tap time — the #2476 invariant. The session calls this
            // exactly once per accepted placement, on the main thread, inside the tap
            // handler, so the picker can never go stale between composition and tap.
            // Priority order:
            //   1. A selected streamed slug whose download has landed → its file URI.
            //   2. A bundled-model lock chosen in Settings (#1883) → that specific GLB.
            //   3. Fall back to the rotating MODEL_CYCLE (v4.3.1 behaviour).
            // A streamed slug whose download is still in flight silently demotes to (2)
            // or (3) so the tap is never lost.
            onPlaceModel = {
                val slug = selectedSlug
                val pendingFile = selectedFile
                val bundledLock = selectedBundledIndex
                val (location, name) = when {
                    slug != null && pendingFile != null ->
                        "file://${pendingFile.absolutePath}" to slug.displayName
                    bundledLock != null -> {
                        val entry = MODEL_CYCLE[bundledLock]
                        entry.assetPath to entry.displayName
                    }
                    else -> {
                        val entry = MODEL_CYCLE[cycleIndex % MODEL_CYCLE.size]
                        cycleIndex = (cycleIndex + 1) % MODEL_CYCLE.size
                        entry.assetPath to entry.displayName
                    }
                }
                PlacementSpec(assetLocation = location, displayName = name)
            },
            state = state,
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            snapToPlane = snapToPlane,
            showReticle = showReticle,
        )
    }
}
