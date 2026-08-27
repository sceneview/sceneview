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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Plane
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode as ArAnchorNode
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.demos.internal.ArPlacement
import io.github.sceneview.demo.demos.internal.rememberTexturesSettled
import io.github.sceneview.demo.R
import io.github.sceneview.demo.sketchfab.SampleAssets
import io.github.sceneview.demo.sketchfab.SketchfabAssetResolver
import io.github.sceneview.demo.sketchfab.SketchfabSlug
import io.github.sceneview.gesture.NodeEditingOverlay
import io.github.sceneview.gesture.rememberNodeEditingFeedback
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberARView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import java.io.File

/**
 * Instant Placement demo.
 *
 * Showcases [Config.InstantPlacementMode.LOCAL_Y_UP] — ARCore returns a hit immediately on tap,
 * even before plane detection has converged. The model snaps in at an approximate distance
 * (1 m in front of the camera by default) and refines to a "real" pose once ARCore has gathered
 * enough features. Compare with [ARPlacementDemo], which waits for plane detection.
 *
 * Each placed model carries its own [InstantPlacementPoint] when instant placement is on; the
 * tracking-state badge ("Approximating" → "Tracked") reflects that point's
 * [InstantPlacementPoint.TrackingMethod] transitioning from `SCREENSPACE_WITH_APPROXIMATE_DISTANCE`
 * to `FULL_TRACKING`. With instant placement off, the demo behaves like [ARPlacementDemo] — pure
 * plane-based hit testing.
 */

private data class InstantPlacedModel(
    val id: Int,
    val anchor: Anchor,
    val trackable: Any?,
    /** Local file URI (`file://...`) for a streamed slug, OR `assets/`-relative path for a
     *  bundled GLB. Load it through `rememberModelInstance(modelLoader, fileLocation = …)` — the
     *  `fileLocation =` overload scheme-detects both forms. The two-arg positional call binds to
     *  the asset-path overload and silently fails on `file://` URIs (#1422 / #2302). */
    val assetLocation: String,
    val displayName: String,
)

private data class InstantCycleEntry(val assetPath: String, val displayName: String)

// Avocado dropped per audit #949 — see ARPlacementDemo for the rationale.
// Damaged Helmet dropped per #2023 — the cycle is now all grounded objects so
// nothing reads as a floating generic test payload. See ARPlacementDemo.
private val INSTANT_MODEL_CYCLE = listOf(
    InstantCycleEntry("models/threejs_soldier.glb", "Soldier"),
    InstantCycleEntry("models/khronos_fox.glb", "Fox"),
    InstantCycleEntry("models/khronos_lantern.glb", "Lantern"),
    InstantCycleEntry("models/khronos_toy_car.glb", "Toy Car"),
    InstantCycleEntry("models/shiba.glb", "Shiba")
)

@Composable
fun ARInstantPlacementDemo(onBack: () -> Unit) {
    var instantEnabled by remember { mutableStateOf(true) }

    // Streamed `ar_placement` slugs from SampleAssets. selectedSlug == null
    // means "Bundled cycle" (the v4.3.1 default behaviour).
    val placementSlugs = remember { SampleAssets.byCategory["ar_placement"].orEmpty() }
    var selectedSlug by remember { mutableStateOf<SketchfabSlug?>(null) }

    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). Resolved once in the parent composable
    // (not inside `InstantPlacementScene`, which `key(instantEnabled)` remounts) so the
    // dataset survives the instant-placement toggle. `null` for every normal launch - see
    // `rememberArPlaybackDataset` - so live AR is completely unchanged for real users.
    val arPlaybackDataset = rememberArPlaybackDataset()

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        runCatching {
            SketchfabAssetResolver.getInstance(context).prefetchAll("ar_placement")
        }
    }

    val selectedFile: File? = selectedSlug?.let { slug ->
        produceState<File?>(initialValue = null, key1 = slug.uid) {
            value = runCatching {
                SketchfabAssetResolver.getInstance(context).resolve(slug)
            }.getOrNull()
        }.value
    }

    // Placement and tracking state shared between the scene and the scaffold's overlay
    // slots — the "Clear All" control at the bottom, the status pills at the top. Keyed
    // on `instantEnabled` so flipping the toggle drops every placed model, exactly as the
    // `key(instantEnabled)` remount below already did.
    val sceneState = remember(instantEnabled) { InstantPlacementSceneState() }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_instant_placement_title),
        onBack = onBack,
        controls = {
            Text(
                text = "Instant Placement vs plane-based: tap right after launching the app — " +
                    "the model snaps in immediately. Without instant placement, you'd have to " +
                    "wait several seconds for ARCore to find a plane first.",
                style = MaterialTheme.typography.bodyMedium
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (instantEnabled) "Instant Placement ON" else "Instant Placement OFF",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = instantEnabled,
                    onCheckedChange = { instantEnabled = it }
                )
            }

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

            Text(
                text = if (instantEnabled) {
                    "Tap anywhere on screen — ARCore guesses a pose ~1 m in front. The badge " +
                        "shows \"Approximating\" until the point converges to FULL_TRACKING."
                } else {
                    "Plane-based mode: wait for the plane overlay to appear, then tap inside it."
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            // Developer-only debug toggle — visible when QA mode is on. Lets QA
            // force-emit each TrackingFailureReason so the actionable-message
            // overlay can be validated without staging a real failure. See
            // io.github.sceneview.demo.common.ForcedTrackingFailure / #1881.
            ForceTrackingFailureMenu()
        },
        // Top band: the stats pill, the latest-model badge under it, then the scanning
        // indicator. They are siblings in the scaffold's top Column (#3237), so they
        // stack by layout instead of by the 8 / 44 / 56 dp arithmetic they used to carry
        // — arithmetic that only held while every pill above stayed one line.
        topOverlay = {
            // Model count + per-state tally.
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                contentColor = Color.White,
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.small
            ) {
                val count = sceneState.placedModels.size
                val lost = sceneState.lostAnchors.values.count { it }
                val approximating = sceneState.placedModels.count { placed ->
                    sceneState.lostAnchors[placed.id] != true &&
                        sceneState.trackingMethods[placed.id] ==
                        InstantPlacementPoint.TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE
                }
                val tracked = sceneState.placedModels.count { placed ->
                    sceneState.lostAnchors[placed.id] != true &&
                        sceneState.trackingMethods[placed.id] ==
                        InstantPlacementPoint.TrackingMethod.FULL_TRACKING
                }
                val label = if (instantEnabled) {
                    // Lost segment only surfaces when there's something to report — keeps
                    // the pill compact when everything is tracking cleanly (#1184).
                    val lostSegment = if (lost > 0) " • $lost lost" else ""
                    "$count placed • $approximating approximating • $tracked tracked$lostSegment"
                } else if (count == 1) {
                    "1 model placed"
                } else {
                    "$count models placed"
                }
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // Most-recent-model tracking badge, stacked under the count pill. The old
            // per-model column (one chip per placed model, up to 4 stacked) overflowed the
            // top third of the viewport and overlapped the placed models themselves
            // (#1476). The aggregate "$count placed • $approximating approximating • …"
            // pill above already reports the per-state tally, so a single badge for the
            // latest placed model is enough to teach the approximate → full-tracking
            // transition without the visual clutter.
            //
            // #1184: read from `placedModels` (the source of truth) so a model whose anchor
            // went `STOPPED` before its first `InstantPlacementPoint.trackingMethod` ever
            // fired still surfaces as `Lost`.
            if (instantEnabled) {
                val latest = sceneState.placedModels.lastOrNull()
                if (latest != null) {
                    val isLost = sceneState.lostAnchors[latest.id] == true
                    val method = sceneState.trackingMethods[latest.id]
                    val (label, color) = when {
                        isLost -> "Lost — tap to re-place" to Color(0xFF8A0000)
                        method == InstantPlacementPoint.TrackingMethod.FULL_TRACKING ->
                            "Tracked" to Color(0xFF1B873B)
                        method == InstantPlacementPoint.TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE ->
                            "Approximating" to Color(0xFFE07B00)
                        else -> "Initializing" to Color(0xFF555555)
                    }
                    Surface(
                        color = color.copy(alpha = 0.9f),
                        contentColor = Color.White,
                        tonalElevation = 4.dp,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "Latest — ${latest.displayName}: $label",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            // Scanning indicator pill — last in the top stack, and the first thing the
            // user sees while ARCore is still initialising. It never competes with the
            // bottom-anchored Clear All button (issue #1199), which lives in the mirror
            // slot at the other edge.
            //
            // ForcedTrackingFailure.override shadows the real ARCore-reported reason
            // when a developer has picked one in the debug menu (#1881). Read it here
            // so flipping the override re-renders the overlay immediately.
            val effectiveReason =
                ForcedTrackingFailure.override ?: sceneState.trackingFailureReason
            AnimatedVisibility(
                visible = !sceneState.isTracking || ForcedTrackingFailure.override != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = trackingFailureMessage(effectiveReason)
                            ?: if (instantEnabled) {
                                "Initializing camera — you can already tap to place"
                            } else {
                                stringResource(R.string.ar_status_scanning)
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            }
        },
        // Clear-all control. Bottom-anchored, so it is a tenant of the shared bottom
        // band and belongs in the scaffold slot (#2779) rather than hand-aligned
        // `BottomStart` in the scene, where it could only share pixels with the
        // Settings FAB.
        //
        // Only surface it once something has actually been placed — issue #1199.
        // Before any tap, the button is a dead affordance that creates the impression
        // of two stacked buttons.
        bottomOverlay = {
            AnimatedVisibility(
                visible = sceneState.placedModels.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
                // `ColumnScope.align` — horizontal only, so it keeps the button at the
                // start edge users learned, with no way to re-enter another tenant.
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                // Solid filled button (#1476). The previous OutlinedButton drew only a
                // hairline border with a transparent fill, so over the live camera feed
                // it was nearly invisible. A filled M3 Button gives an opaque surface
                // with a guaranteed contrast pairing (container / onContainer).
                Button(onClick = { sceneState.clearAll() }) {
                    Text("Clear All")
                }
            }
        },
    ) {
        // `key(instantEnabled)` rebuilds the entire ARSceneView (and its ARCore session) when
        // the user flips the toggle. ARCore configs can be reapplied live, but reusing the same
        // session across instant-placement on/off blurs which placed models came from which mode
        // — a fresh session keeps the demo's state clean per toggle.
        key(instantEnabled) {
            InstantPlacementScene(
                instantEnabled = instantEnabled,
                selectedSlug = selectedSlug,
                selectedFile = selectedFile,
                playbackDataset = arPlaybackDataset,
                state = sceneState,
            )
        }
    }
}

/**
 * Placement and tracking state shared between [InstantPlacementScene] and the controls
 * the scaffold hosts in its overlay slots — "Clear All" at the bottom, the count pill,
 * the latest-model badge and the scanning indicator at the top.
 *
 * It is hoisted out of the scene composable only because those controls moved into the
 * slots — the containers that lay a bottom- or top-anchored control out against the
 * Settings FAB and the asset-source chip instead of on top of them (#2779, #3237).
 * Lifetime is unchanged: the caller creates it with `remember(instantEnabled)`, so it
 * dies with the same toggle flip that remounts the scene.
 */
@Stable
private class InstantPlacementSceneState {
    val placedModels = mutableStateListOf<InstantPlacedModel>()

    /** Whether the ARCore camera is tracking — drives the scanning indicator. */
    var isTracking by mutableStateOf(false)

    /** Latest ARCore-reported tracking failure, or `null` while tracking is healthy. */
    var trackingFailureReason by mutableStateOf<TrackingFailureReason?>(null)

    // Live status of each placed Instant Placement point. Keyed by model id.
    // Using a `mutableStateMapOf` (vs. a list rebuilt every frame) means the per-
    // frame onSessionUpdated only writes when a value actually changes — Compose
    // therefore only recomposes the badges when ARCore promotes a point from
    // approximate → full tracking, not on every one of the 60 ARCore frames/sec.
    val trackingMethods = mutableStateMapOf<Int, InstantPlacementPoint.TrackingMethod>()

    // Per-anchor lost flag (#1184). When ARCore can no longer refine an Instant
    // Placement point — typically because the user panned away from the screen
    // region where the point was approximated — the underlying `Anchor` flips its
    // `trackingState` from `TRACKING` to `STOPPED`. Continuing to render a
    // ModelNode under a STOPPED AnchorNode pins it at the last frozen world pose
    // and produces the "anchor floats off into space" effect that hurts the demo
    // (#1184: 2/4 anchors went `Lost` in the production Pixel 9 audit). We hide
    // the ModelNode the moment the anchor stops tracking and surface "Lost" on
    // the badge so the user knows they can drop a fresh tap to retry. Detach the
    // dead anchor too — ARCore won't revive a STOPPED point even on re-entering
    // its screen region.
    val lostAnchors = mutableStateMapOf<Int, Boolean>()

    var cycleIndex by mutableStateOf(0)

    /** Detach every placed anchor, drop every model and restart the bundled cycle. */
    fun clearAll() {
        placedModels.forEach { runCatching { it.anchor.detach() } }
        placedModels.clear()
        trackingMethods.clear()
        lostAnchors.clear()
        cycleIndex = 0
    }
}

@Composable
private fun InstantPlacementScene(
    instantEnabled: Boolean,
    selectedSlug: SketchfabSlug?,
    selectedFile: File?,
    playbackDataset: File?,
    state: InstantPlacementSceneState,
) {
    val engine = rememberEngine()
    // Hoisted so the gesture-feedback overlay below can project world → screen through
    // the same Filament view the AR scene renders with.
    val view = rememberARView(engine)
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)

    // Placed editable nodes, keyed by placed-model id, for the on-model gesture
    // feedback overlays. Entries are removed on disposal (Clear All / lost anchor) so
    // the overlay never projects a destroyed node.
    val editableNodes = remember { mutableStateMapOf<Int, ModelNode>() }

    // The AnchorNode of each placed model. Needed for two things: the drag gesture is
    // handled by the ANCHOR node (detach on begin, re-anchor on end — AnchorNode's
    // `isPositionEditable` is a plain `true` field, not gated on `isEditable`), so the
    // feedback overlay listens to it for move events; and the lost-anchor reconciliation
    // below must read the node's CURRENT anchor, not the placement-time one.
    val anchorNodes = remember { mutableStateMapOf<Int, ArAnchorNode>() }

    // Placement and tracking state live in the caller (see [InstantPlacementSceneState])
    // so the "Clear All" control and the status pills can sit in the scaffold's overlay
    // slots; aliased here so the scene body reads exactly as it did.
    val placedModels = state.placedModels
    val trackingMethods = state.trackingMethods
    val lostAnchors = state.lostAnchors

    var nextId by remember { mutableStateOf(0) }

    var latestFrame by remember { mutableStateOf<Frame?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            view = view,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            playbackDataset = playbackDataset,
            planeRenderer = !instantEnabled,
            // Typed Config.*Mode params (#1766) — reactive: flipping `instantEnabled` recomposes
            // the session config without a sessionConfiguration callback. planeFindingMode +
            // lightEstimationMode are already the defaults.
            instantPlacementMode = if (instantEnabled) {
                Config.InstantPlacementMode.LOCAL_Y_UP
            } else {
                Config.InstantPlacementMode.DISABLED
            },
            onSessionUpdated = { _, frame: Frame ->
                latestFrame = frame
                state.isTracking = frame.camera.trackingState == TrackingState.TRACKING
                // Refresh tracking-method snapshots so the per-model badge updates as
                // ARCore promotes points from approximate → full tracking. Write only
                // when the value actually changes so we don't churn Compose state at
                // 60 Hz (each unchanged write would still flag the snapshot dirty and
                // recompose the badge column).
                //
                // Also reconcile `lostAnchors` for #1184. ARCore can drop a placed
                // Instant Placement point's `trackingState` to STOPPED a few seconds
                // after the camera pans away (e.g. Fox + Toy Car in the Pixel 9 audit).
                // Hiding those models + surfacing "Lost" on the badge is cheaper than
                // a re-hit-test recovery and keeps the demo deterministic.
                placedModels.forEach { placed ->
                    val anchorNode = anchorNodes[placed.id]
                    // A drag on the anchor node DELIBERATELY detaches the anchor for the
                    // duration of the gesture and re-anchors on release — reconciling
                    // mid-gesture would read that as anchor loss and kill the model
                    // (observed on the Pixel 4a rig: model gone ~0.3s into every drag,
                    // with ARCore double-detach warnings).
                    if (anchorNode?.editingTransforms?.isNotEmpty() == true) return@forEach
                    // Read the node's CURRENT anchor: after a drag the AnchorNode holds a
                    // fresh anchor and the placement-time one is dead.
                    val activeAnchor = anchorNode?.anchor ?: placed.anchor
                    val anchorStopped = activeAnchor.trackingState == TrackingState.STOPPED
                    if (lostAnchors[placed.id] != anchorStopped) {
                        lostAnchors[placed.id] = anchorStopped
                    }
                    if (anchorStopped) {
                        // Free ARCore's anchor slot — there are only a few dozen per
                        // session and dead Instant Placement points never recover.
                        runCatching { activeAnchor.detach() }
                        // Don't refresh the trackingMethod snapshot once the anchor's
                        // gone — the underlying InstantPlacementPoint may still report
                        // its last method, which would mask the "Lost" state behind a
                        // stale "Tracked" badge.
                        return@forEach
                    }
                    val current = (placed.trackable as? InstantPlacementPoint)
                        ?.trackingMethod
                        ?: return@forEach
                    if (trackingMethods[placed.id] != current) {
                        trackingMethods[placed.id] = current
                    }
                }
            },
            onTrackingFailureChanged = { reason ->
                state.trackingFailureReason = reason
            },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { event: MotionEvent, node ->
                    if (node != null) return@rememberOnGestureListener
                    val frame = latestFrame ?: return@rememberOnGestureListener
                    if (frame.camera.trackingState != TrackingState.TRACKING) {
                        return@rememberOnGestureListener
                    }

                    val hit = if (instantEnabled) {
                        // hitTestInstantPlacement returns immediately even with no detected
                        // planes — ARCore guesses a pose using the approximate distance.
                        frame.hitTestInstantPlacement(event.x, event.y, 1.0f).firstOrNull()
                    } else {
                        frame.hitTest(event).firstOrNull { result ->
                            val trackable = result.trackable
                            trackable is Plane &&
                                trackable.isPoseInPolygon(result.hitPose) &&
                                result.distance <= 5.0f
                        }
                    } ?: return@rememberOnGestureListener

                    // Picker semantics mirror ARPlacementDemo — selected slug landed
                    // → place that slug; selected slug still streaming OR no selection
                    // → cycle through bundled INSTANT_MODEL_CYCLE.
                    val (location, name) = if (selectedSlug != null && selectedFile != null) {
                        "file://${selectedFile.absolutePath}" to selectedSlug.displayName
                    } else {
                        val entry =
                            INSTANT_MODEL_CYCLE[state.cycleIndex % INSTANT_MODEL_CYCLE.size]
                        state.cycleIndex =
                            (state.cycleIndex + 1) % INSTANT_MODEL_CYCLE.size
                        entry.assetPath to entry.displayName
                    }
                    placedModels.add(
                        InstantPlacedModel(
                            id = nextId++,
                            anchor = hit.createAnchor(),
                            trackable = hit.trackable,
                            assetLocation = location,
                            displayName = name,
                        )
                    )
                }
            )
        ) {
            placedModels.forEach { placed ->
                key(placed.id) {
                    // Skip rendering once the anchor has gone STOPPED — see lostAnchors
                    // doc (#1184). AnchorNode under a STOPPED anchor freezes the model
                    // at the last good pose, which looks broken to the user.
                    if (lostAnchors[placed.id] != true) {
                        // visibleTrackingStates includes PAUSED so a placed model rides out
                        // transient plane loss at its last known pose instead of vanishing;
                        // permanent loss is still handled above via lostAnchors (#1435).
                        AnchorNode(
                            anchor = placed.anchor,
                            visibleTrackingStates = ArPlacement.ANCHORED_VISIBLE_STATES,
                            apply = {
                                anchorNodes[placed.id] = this
                            }
                        ) {
                            // `fileLocation =` forces the URL-capable overload (handles both
                            // the `file://` streamed URI and the bundled asset path). See the
                            // `assetLocation` Kdoc and the #2302 overload trap.
                            val instance =
                                rememberModelInstance(modelLoader, fileLocation = placed.assetLocation)
                            // Gate visibility until Filament finishes uploading the model's
                            // textures, so it doesn't flash black on placement (#1435).
                            val textured = rememberTexturesSettled(ready = instance != null)
                            instance?.let {
                                ModelNode(
                                    modelInstance = it,
                                    scaleToUnits = 0.3f,
                                    isVisible = textured,
                                    isEditable = true,
                                    apply = {
                                        // `editableScaleRange` is an ABSOLUTE local-scale
                                        // window, not a factor — and `scaleToUnits` makes
                                        // that scale a function of how the asset was
                                        // authored. The Khronos Fox is modelled at ~140
                                        // units, so 0.3 units of it is a local scale near
                                        // 0.002: far below the default `0.1f..10f`, which
                                        // silently rejected every pinch update and made
                                        // zoom look dead on that model. Re-center the
                                        // window on the as-placed scale.
                                        editableScaleRange = scale.x * 0.25f..scale.x * 4f
                                        editableNodes[placed.id] = this
                                    }
                                )
                                DisposableEffect(placed.id) {
                                    onDispose {
                                        editableNodes.remove(placed.id)
                                        anchorNodes.remove(placed.id)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Opt-in on-model gesture feedback: one overlay per placed editable model —
        // rotation ring + yaw badge, scale badge with range-limit bounce, contact
        // shadow while dragging. Drawn over the camera feed by world→screen projection
        // through the hoisted [view].
        for (id in editableNodes.keys.toList()) {
            val node = editableNodes[id] ?: continue
            key(id) {
                val feedback = rememberNodeEditingFeedback(node)
                // The drag gesture is handled by the parent ANCHOR node (detach →
                // re-anchor), not by the model node — subscribe the same feedback state
                // to it so the move visuals (contact shadow + accent ring) track drags.
                val anchorNode = anchorNodes[id]
                DisposableEffect(feedback, anchorNode) {
                    anchorNode?.addEditingListener(feedback)
                    onDispose { anchorNode?.removeEditingListener(feedback) }
                }
                NodeEditingOverlay(
                    state = feedback,
                    view = view,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }

        // The status pills and the "Clear All" control are no longer here: they are
        // floating chrome, so they live in the scaffold's `topOverlay` / `bottomOverlay`
        // slots, both declared by ARInstantPlacementDemo.
    }
}
