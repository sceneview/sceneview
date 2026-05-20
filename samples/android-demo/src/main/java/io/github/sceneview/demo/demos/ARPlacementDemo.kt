package io.github.sceneview.demo.demos

import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.demo.AssetSourceState
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.demos.internal.ArPlacement
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.demos.internal.rememberTexturesSettled
import io.github.sceneview.demo.sketchfab.SketchfabConfig
import io.github.sceneview.demo.R
import io.github.sceneview.demo.sketchfab.SampleAssets
import io.github.sceneview.demo.sketchfab.SketchfabAssetResolver
import io.github.sceneview.demo.sketchfab.SketchfabSlug
import io.github.sceneview.math.Position
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import java.io.File

/**
 * Interactive AR tap-to-place demo with a "Pick what to place" picker.
 *
 * Plane detection is rendered as a translucent overlay. Each tap on a detected horizontal or
 * vertical plane spawns a NEW [ModelNode] instance attached to its own
 * [AnchorNode][io.github.sceneview.ar.node.AnchorNode].
 *
 * **Picker — Stage 2 ([#1152](https://github.com/sceneview/sceneview/issues/1152)).** The
 * controls sheet exposes a chip row sourced from [SampleAssets.byCategory]`["ar_placement"]`
 * — coffee mug / houseplant / wooden crate / side table / floor lamp / picture frame, all
 * streamed CC-BY models from Sketchfab via [SketchfabAssetResolver]. Tapping a chip arms
 * that slug as the next placed model; subsequent taps on a plane place a fresh instance.
 *
 * **Bundled fallback.** The default chip "Bundled cycle" preserves the v4.3.1 behaviour —
 * each tap places the next entry of the 5-model bundled GLB cycle (helmet → fox → lantern
 * → toy car → shiba). This keeps the demo useful even when the app launched with no
 * Sketchfab API key or with the network down — the resolver's per-slug fallback path will
 * also serve a bundled GLB, but the explicit cycle gives a deterministic "no surprises"
 * mode for QA / offline / store-listing screenshots.
 *
 * Each placed model is **editable** — `isEditable = true` on the [ModelNode] enables
 * pinch-to-scale, two-finger rotate, and one-finger drag. Because the parent [AnchorNode] is
 * locked to its ARCore [Anchor] pose, the editable child node transforms relative to the anchor:
 * the anchor stays glued to the plane while the user manipulates the model on top of it.
 *
 * Top-center pill shows the live "X models placed" count. The "Clear All" control wipes every
 * placed model and detaches the underlying ARCore anchors.
 */

private data class PlacedModel(
    val id: Int,
    val anchor: Anchor,
    /** Local file URI (`file://...`) for a streamed slug, OR `assets/`-relative path for a
     *  bundled GLB. `rememberModelInstance` accepts both via its single-string overload. */
    val assetLocation: String,
    val displayName: String,
)

private data class CycleEntry(val assetPath: String, val displayName: String)

// Curated list of bundled GLBs that look good as small AR objects on a plane.
// Each has a distinct silhouette and material so the cycle visibly rotates through variety.
// (Khronos Avocado dropped per audit #949 — 7.7 MB grey-green low-poly that read as
// 2003-textbook quality next to the helmet/lantern/dragon brass-and-PBR neighbours.)
private val MODEL_CYCLE = listOf(
    CycleEntry("models/khronos_damaged_helmet.glb", "Damaged Helmet"),
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
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch - see
    // `rememberArPlaybackDataset` - so live AR is completely unchanged for real users.
    val arPlaybackDataset = rememberArPlaybackDataset()
    val context = LocalContext.current

    val placedModels = remember { mutableStateListOf<PlacedModel>() }
    var nextId by remember { mutableStateOf(0) }
    var cycleIndex by remember { mutableStateOf(0) }

    // Streamed `ar_placement` slugs from SampleAssets. selectedSlug == null
    // means "Bundled cycle" (the v4.3.1 default behaviour). Selecting a slug
    // arms it as the next tap's payload, replacing the cycle for that tap.
    val placementSlugs = remember { SampleAssets.byCategory["ar_placement"].orEmpty() }
    var selectedSlug by remember { mutableStateOf<SketchfabSlug?>(null) }

    // Pinned bundled-model selection (#1883). `null` ⇒ unchanged behaviour
    // (each tap rotates through `MODEL_CYCLE`); a non-null entry locks the
    // next tap to that specific bundled GLB. The user picks a bundled model
    // either by tapping its chip in the bottom-sheet menu or by leaving the
    // selection at `null` (cycle mode, which the "Bundled cycle" chip
    // selects explicitly).
    var pinnedBundled by remember { mutableStateOf<CycleEntry?>(null) }

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

    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var isTracking by remember { mutableStateOf(false) }

    // Keep a reference to the latest Frame for hit testing in the gesture callback.
    var latestFrame by remember { mutableStateOf<Frame?>(null) }

    // Placement reticle state (#1882). The reticle continuously hit-tests at
    // screen center; `reticleHit` is the most-recent successful hit, surfaced
    // back into Compose state by `ReticleNode`'s `onHitResultChanged`. Tap-to-
    // place reads this value so the placed model lands exactly where the
    // reticle was — what-you-see-is-what-you-get placement. Cleared when the
    // ray misses every trackable, which drives the "aim at a surface" hint.
    var reticleHit by remember { mutableStateOf<HitResult?>(null) }
    // View size in pixels — captured via `onSizeChanged` and used to position
    // the library-level `ReticleNode` at screen center. Initial `null` keeps
    // the reticle out of the scene until layout completes (first non-zero
    // size triggers the recomp that inserts the node).
    var viewWidthPx by remember { mutableStateOf(0) }
    var viewHeightPx by remember { mutableStateOf(0) }

    // Active-gesture label (shown while the user is mid-manipulating a placed
    // model). `null` ⇒ no overlay. Mirrors the GestureEditingDemo pattern so
    // both demos share the same visual language for "what is my touch doing
    // right now". Drag → "Moving", twist → "Rotating", pinch → "Scaling".
    var gestureMode by remember { mutableStateOf<String?>(null) }

    // When the reticle is hidden via the settings toggle, immediately clear the
    // cached `reticleHit` so a stale value can't drive tap-to-place. The
    // reticle subtree leaves the composition, so its `onHitResultChanged`
    // never fires again — without this, the last successful hit would persist
    // and a tap after disabling the reticle would place the model wherever it
    // was last centred. See `showReticle` declaration below for the toggle.

    // Settings exposed through the bottom-sheet menu (#1883).
    //
    // - `snapToPlane = true` (default) requires every tap/reticle hit to land
    //   inside a real ARCore plane polygon. Turning this off opts placement
    //   into instant-placement + depth points + feature points so the user
    //   can drop models on textured surfaces ARCore hasn't grown a plane on
    //   yet (sofas, cluttered desks, walls without enough features). The
    //   reticle reflects the same toggle so the visible marker matches what
    //   the tap will land on.
    //
    // - `showReticle = true` (default) keeps the on-screen placement reticle
    //   visible. Turning it off is a debug aid — useful for capture-only
    //   screenshots where the cyan disc would clutter the frame, and for
    //   verifying that tap-to-place still falls back to the legacy
    //   tap-coordinate hit-test path when the reticle isn't running.
    var snapToPlane by remember { mutableStateOf(true) }
    var showReticle by remember { mutableStateOf(true) }

    // Clear the cached reticle hit when the reticle subtree leaves the
    // composition — see the comment block above the settings declarations
    // for the rationale.
    LaunchedEffect(showReticle) {
        if (!showReticle) reticleHit = null
    }

    // Per-demo offline indicator chip (#1152 Stage 3). The chip reflects the
    // selected slug's resolve state — `null` means no slug picked yet (cycle
    // mode), so we surface "Bundled fallback" (the cycle is 100% bundled).
    val assetSource = when {
        selectedSlug == null -> AssetSourceState.Bundled
        SketchfabConfig.apiKey == null -> AssetSourceState.Bundled
        selectedFile == null -> AssetSourceState.Streaming
        else -> AssetSourceState.Streamed
    }

    // Helper to clear every placed model + detach its anchor (#1883). Exposed
    // both via the in-sheet "Clear all placed" button and via the scaffold's
    // top-bar Reset action so the user has one-tap access to a clean slate
    // without having to scroll the sheet.
    val clearAllPlaced: () -> Unit = {
        placedModels.forEach { runCatching { it.anchor.detach() } }
        placedModels.clear()
        cycleIndex = 0
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_placement_title),
        onBack = onBack,
        assetSource = assetSource,
        // Wire the scaffold's reset action (#1883). Tapping "Reset" inside the
        // controls sheet header clears every placed model AND snaps the
        // toggles back to their defaults, so a user can hand the phone to
        // somebody else and they get the demo's pristine state. Picker
        // selection is also reset to the "Bundled cycle" mode.
        onResetSettings = {
            clearAllPlaced()
            snapToPlane = true
            showReticle = true
            selectedSlug = null
            pinnedBundled = null
        },
        controls = {
            Text(
                text = "Tap a detected plane to drop a model. Each model is editable: drag to " +
                    "translate, pinch to scale, twist to rotate — the active gesture is shown " +
                    "in the top-center pill.",
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
                    selected = selectedSlug == null && pinnedBundled == null,
                    onClick = {
                        selectedSlug = null
                        pinnedBundled = null
                    },
                    label = {
                        Text(stringResource(R.string.demo_ar_placement_picker_bundled))
                    },
                )
                // Per-bundled-model chips (#1883) — pin a specific GLB so the
                // user can iterate on placement-and-edit without each tap
                // rotating through the cycle.
                MODEL_CYCLE.forEach { entry ->
                    FilterChip(
                        selected = pinnedBundled?.assetPath == entry.assetPath,
                        onClick = {
                            pinnedBundled = entry
                            selectedSlug = null
                        },
                        label = { Text(entry.displayName) },
                    )
                }
                placementSlugs.forEach { slug ->
                    FilterChip(
                        selected = selectedSlug?.uid == slug.uid,
                        onClick = {
                            selectedSlug = slug
                            pinnedBundled = null
                        },
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

            // ── Placement options ───────────────────────────────────────────────────────────────
            //
            // "Snap to plane" and "Show reticle" are the two settings introduced
            // alongside the placement reticle (#1882 + #1883). Defaults match
            // the user's likely first run: snap ON (only place on real planes
            // — the safest behaviour) and reticle ON (visible feedback for
            // every aimed tap).
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Placement options",
                style = MaterialTheme.typography.labelLarge,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Snap to plane",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Only place on detected ARCore plane polygons.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Switch(
                    checked = snapToPlane,
                    onCheckedChange = { snapToPlane = it }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Show reticle",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Cyan disc at the aim point. Debug aid — disabling " +
                            "falls back to the legacy tap-coordinate hit test.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Switch(
                    checked = showReticle,
                    onCheckedChange = { showReticle = it }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            OutlinedButton(
                onClick = clearAllPlaced,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text("Clear all placed")
            }

            // Up-next preview so the user knows what the next tap will spawn.
            val nextLabel = selectedSlug?.let { slug ->
                if (selectedFile != null) slug.displayName
                else stringResource(R.string.demo_ar_placement_picker_streaming, slug.displayName)
            } ?: pinnedBundled?.displayName
            ?: MODEL_CYCLE[cycleIndex % MODEL_CYCLE.size].displayName
            Text(
                text = "Next tap places: $nextLabel",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                modifier = Modifier
                    .fillMaxSize()
                    // Track view size so we can position the placement reticle
                    // at screen center each frame (#1882). The reticle Composable
                    // is only emitted once `viewWidthPx`/`viewHeightPx` are
                    // non-zero — see the content block below.
                    .onSizeChanged { size ->
                        viewWidthPx = size.width
                        viewHeightPx = size.height
                    },
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                planeRenderer = true,
                sessionConfiguration = { _: Session, config: Config ->
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                },
                onSessionUpdated = { _, frame: Frame ->
                    latestFrame = frame
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                },
                onTrackingFailureChanged = { reason ->
                    trackingFailureReason = reason
                },
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = { event: MotionEvent, node ->
                        // If the tap landed on an existing editable ModelNode, the gesture
                        // system handles it (drag/scale/rotate). Don't spawn a new model on top.
                        if (node != null) return@rememberOnGestureListener

                        val frame = latestFrame ?: return@rememberOnGestureListener
                        if (frame.camera.trackingState != TrackingState.TRACKING) {
                            return@rememberOnGestureListener
                        }

                        // Placement strategy: prefer the reticle's last-known hit
                        // (what-you-see-is-what-you-get placement, #1882) so the
                        // model lands exactly where the on-screen marker is. Fall
                        // back to a tap-coordinate hit test when the reticle has
                        // no hit (e.g. user disabled the reticle in settings) so
                        // existing tap behaviour still works.
                        //
                        // `snapToPlane` (#1883) gates whether we require the hit
                        // to land inside an ARCore plane polygon. Off → accept
                        // any trackable so the user can drop models on textured
                        // surfaces ARCore hasn't promoted to a plane yet.
                        fun acceptHit(result: HitResult): Boolean {
                            if (result.distance > 5.0f) return false
                            if (!snapToPlane) return true
                            val trackable = result.trackable
                            return trackable is Plane &&
                                trackable.isPoseInPolygon(result.hitPose)
                        }
                        val hit = reticleHit?.takeIf(::acceptHit)
                            ?: frame.hitTest(event).firstOrNull(::acceptHit)
                        if (hit != null) {
                            // Resolve the asset location based on the picker. Precedence:
                            //  1. Streamed slug — when the user picked a Sketchfab chip
                            //     and the file has staged locally.
                            //  2. Pinned bundled entry (#1883) — the user picked a
                            //     specific GLB chip; every tap places that same model.
                            //  3. Bundled cycle — default; rotate through `MODEL_CYCLE`
                            //     so each tap surfaces a different demo asset.
                            val slug = selectedSlug
                            val pendingFile = selectedFile
                            val pinned = pinnedBundled
                            val (location, name) = when {
                                slug != null && pendingFile != null ->
                                    "file://${pendingFile.absolutePath}" to slug.displayName
                                pinned != null ->
                                    pinned.assetPath to pinned.displayName
                                else -> {
                                    val entry = MODEL_CYCLE[cycleIndex % MODEL_CYCLE.size]
                                    cycleIndex = (cycleIndex + 1) % MODEL_CYCLE.size
                                    entry.assetPath to entry.displayName
                                }
                            }
                            placedModels.add(
                                PlacedModel(
                                    id = nextId++,
                                    anchor = hit.createAnchor(),
                                    assetLocation = location,
                                    displayName = name,
                                )
                            )
                        }
                    },
                    // Pixel 9 review v2: surface which gesture is active so the user
                    // can tell drag-to-move from twist-to-rotate from pinch-to-scale.
                    // `node != null` ⇒ gesture is targeting an editable ModelNode
                    // (placed model). `node == null` ⇒ the touch fell through to the
                    // background; AR has no orbit camera so we skip the indicator.
                    onMoveBegin = { _, _, node ->
                        if (node != null) gestureMode = "Moving"
                    },
                    onMoveEnd = { _, _, _ -> gestureMode = null },
                    onRotateBegin = { _, _, node ->
                        if (node != null) gestureMode = "Rotating"
                    },
                    onRotateEnd = { _, _, _ -> gestureMode = null },
                    onScaleBegin = { _, _, node ->
                        if (node != null) gestureMode = "Scaling"
                    },
                    onScaleEnd = { _, _, _ -> gestureMode = null }
                )
            ) {
                // Placement reticle (#1882, gated by `showReticle` setting #1883).
                // Centred on the live view in pixels — only emitted once
                // `onSizeChanged` has measured the surface so the hit test runs
                // against a real screen coordinate. The visual marker is a
                // small unlit cyan disc; the library-level `ReticleNode`
                // handles auto-hide on no-hit and surfaces the current hit
                // back into Compose state via `onHitResultChanged`, which the
                // `onSingleTapConfirmed` handler above consults to place the
                // model exactly under the reticle.
                //
                // When `showReticle` is off, the entire reticle subtree exits
                // the composition: the visual marker disappears, the hit-test
                // loop stops, and `reticleHit` resets to null. Tap-to-place
                // falls back to a per-tap `frame.hitTest(event)` via the
                // `?:` fallback in the gesture handler above.
                if (showReticle && viewWidthPx > 0 && viewHeightPx > 0) {
                    val reticleMaterial = remember(materialLoader) {
                        materialLoader.createUnlitColorInstance(
                            Color(red = 0.40f, green = 0.85f, blue = 1.00f, alpha = 0.85f)
                        )
                    }
                    ReticleNode(
                        xPx = viewWidthPx / 2f,
                        yPx = viewHeightPx / 2f,
                        // `snapToPlane` (#1883) mirrors the tap-to-place rule
                        // so the reticle's visible position matches what a
                        // tap would land on: plane-only when ON, planes +
                        // depth + instant-placement when OFF.
                        instantPlacementPoint = !snapToPlane,
                        depthPoint = !snapToPlane,
                        point = !snapToPlane,
                        planePoseInPolygon = snapToPlane,
                        onHitResultChanged = { reticleHit = it }
                    ) {
                        // Flat disc (1 mm thick, 4 cm radius) lying along the
                        // surface normal that ARCore returned. The reticle node
                        // already orients itself along the surface, so the
                        // disc renders parallel to the detected plane.
                        CylinderNode(
                            radius = 0.04f,
                            height = 0.002f,
                            materialInstance = reticleMaterial
                        )
                    }
                }

                // One AnchorNode + ModelNode per placement. Wrapping each in `key(id)` gives
                // every placement its own remember slot, so the rememberModelInstance call
                // inside loads a fresh, independent ModelInstance per anchor (Filament instances
                // can only live in one transform at a time, so we cannot share them).
                placedModels.forEach { placed ->
                    key(placed.id) {
                        // visibleTrackingStates includes PAUSED so a placed model survives
                        // transient plane loss — it holds its last known pose instead of
                        // vanishing when ARCore briefly stops tracking the anchor (#1435).
                        AnchorNode(
                            anchor = placed.anchor,
                            visibleTrackingStates = ArPlacement.ANCHORED_VISIBLE_STATES
                        ) {
                            val instance = rememberModelInstance(modelLoader, placed.assetLocation)
                            // Gate visibility until Filament finishes uploading the model's
                            // textures, so it doesn't flash black on placement (#1435).
                            val textured = rememberTexturesSettled(ready = instance != null)
                            instance?.let {
                                ModelNode(
                                    modelInstance = it,
                                    scaleToUnits = 0.3f,
                                    centerOrigin = Position(0.0f, 0.0f, 0.0f),
                                    // The bundled DamagedHelmet GLB carries a residual +90° X
                                    // root rotation that lands it face-down on the plane.
                                    // Keyed to the placed asset path so only the helmet is
                                    // corrected; the other cycle models stay upright. See #1477.
                                    rotation = DemoMath.placementRotationFor(placed.assetLocation),
                                    isVisible = textured,
                                    isEditable = true
                                )
                            }
                        }
                    }
                }
            }

            // Top-center pill: live count of placed models. Mirrors the GestureEditingDemo
            // "Editing: …" Surface pattern so the two AR/3D demos share an overlay style.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
                color = Color.Black.copy(alpha = 0.7f),
                contentColor = Color.White,
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.small
            ) {
                val count = placedModels.size
                Text(
                    text = if (count == 1) "1 model placed" else "$count models placed",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // Active-gesture indicator. Sits below the count pill, only visible
            // while the user is actively manipulating a placed model. Uses the
            // primary tonal color (vs. the count pill's neutral black) so the
            // two overlays stay visually distinct even when stacked.
            AnimatedVisibility(
                visible = gestureMode != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    tonalElevation = 4.dp,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = gestureMode ?: "",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // Reticle "aim at a surface" hint (#1882). Visible only while
            // tracking IS working but the reticle has no hit — distinguishes
            // "ARCore isn't ready yet" (the scanning indicator below) from
            // "ARCore is ready but you need to point at a real surface". The
            // gap is small (just below the count + gesture pills) so the user
            // sees the prompt without occluding the camera view.
            AnimatedVisibility(
                visible = isTracking && reticleHit == null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 96.dp)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White,
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = "Aim at a surface",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            // Scanning indicator overlay
            AnimatedVisibility(
                visible = !isTracking,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    modifier = Modifier.padding(bottom = 32.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = trackingFailureMessage(trackingFailureReason)
                            ?: stringResource(R.string.ar_status_scanning),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

