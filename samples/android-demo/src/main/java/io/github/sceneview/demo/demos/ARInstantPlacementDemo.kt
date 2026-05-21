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
import androidx.compose.runtime.LaunchedEffect
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
import io.github.sceneview.math.Position
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
     *  bundled GLB. `rememberModelInstance` accepts both via its single-string overload. */
    val assetLocation: String,
    val displayName: String,
)

private data class InstantCycleEntry(val assetPath: String, val displayName: String)

// Avocado dropped per audit #949 — see ARPlacementDemo for the rationale.
private val INSTANT_MODEL_CYCLE = listOf(
    InstantCycleEntry("models/khronos_damaged_helmet.glb", "Damaged Helmet"),
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
        }
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
            )
        }
    }
}

@Composable
private fun InstantPlacementScene(
    instantEnabled: Boolean,
    selectedSlug: SketchfabSlug?,
    selectedFile: File?,
    playbackDataset: File?,
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)

    val placedModels = remember { mutableStateListOf<InstantPlacedModel>() }
    var nextId by remember { mutableStateOf(0) }
    var cycleIndex by remember { mutableStateOf(0) }

    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var isTracking by remember { mutableStateOf(false) }
    var latestFrame by remember { mutableStateOf<Frame?>(null) }

    // Live status of each placed Instant Placement point. Keyed by model id.
    // Using a `mutableStateMapOf` (vs. a list rebuilt every frame) means the per-
    // frame onSessionUpdated only writes when a value actually changes — Compose
    // therefore only recomposes the badges when ARCore promotes a point from
    // approximate → full tracking, not on every one of the 60 ARCore frames/sec.
    val trackingMethods = remember {
        mutableStateMapOf<Int, InstantPlacementPoint.TrackingMethod>()
    }

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
    val lostAnchors = remember { mutableStateMapOf<Int, Boolean>() }

    Box(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
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
                isTracking = frame.camera.trackingState == TrackingState.TRACKING
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
                    val anchorStopped = placed.anchor.trackingState == TrackingState.STOPPED
                    if (lostAnchors[placed.id] != anchorStopped) {
                        lostAnchors[placed.id] = anchorStopped
                    }
                    if (anchorStopped) {
                        // Free ARCore's anchor slot — there are only a few dozen per
                        // session and dead Instant Placement points never recover.
                        runCatching { placed.anchor.detach() }
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
                trackingFailureReason = reason
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
                        val entry = INSTANT_MODEL_CYCLE[cycleIndex % INSTANT_MODEL_CYCLE.size]
                        cycleIndex = (cycleIndex + 1) % INSTANT_MODEL_CYCLE.size
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
                                    isVisible = textured,
                                    isEditable = true
                                )
                            }
                        }
                    }
                }
            }
        }

        // Top-center pill: model count + per-state tally.
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
            val lost = lostAnchors.values.count { it }
            val approximating = placedModels.count { placed ->
                lostAnchors[placed.id] != true &&
                    trackingMethods[placed.id] ==
                    InstantPlacementPoint.TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE
            }
            val tracked = placedModels.count { placed ->
                lostAnchors[placed.id] != true &&
                    trackingMethods[placed.id] ==
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

        // Most-recent-model tracking badge, shown just below the count pill. The old
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
            val latest = placedModels.lastOrNull()
            if (latest != null) {
                val isLost = lostAnchors[latest.id] == true
                val method = trackingMethods[latest.id]
                val (label, color) = when {
                    isLost -> "Lost — tap to re-place" to Color(0xFF8A0000)
                    method == InstantPlacementPoint.TrackingMethod.FULL_TRACKING ->
                        "Tracked" to Color(0xFF1B873B)
                    method == InstantPlacementPoint.TrackingMethod.SCREENSPACE_WITH_APPROXIMATE_DISTANCE ->
                        "Approximating" to Color(0xFFE07B00)
                    else -> "Initializing" to Color(0xFF555555)
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 44.dp),
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

        // Clear-all control at the bottom-left. Only surface it once something
        // has actually been placed — issue #1199. Before any tap, the button is
        // a dead affordance that shares vertical space with the "Initializing
        // camera" pill and creates the impression of two stacked buttons.
        AnimatedVisibility(
            visible = placedModels.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            // Solid filled button (#1476). The previous OutlinedButton drew only a
            // hairline border with a transparent fill, so over the live camera feed
            // it was nearly invisible. A filled M3 Button gives an opaque surface
            // with a guaranteed contrast pairing (container / onContainer).
            Button(
                onClick = {
                    placedModels.forEach { runCatching { it.anchor.detach() } }
                    placedModels.clear()
                    trackingMethods.clear()
                    lostAnchors.clear()
                    cycleIndex = 0
                },
            ) {
                Text("Clear All")
            }
        }

        // Scanning indicator pill. Anchored top-center just below the stats pill
        // (issue #1199) so it never competes with the bottom-anchored Clear All
        // button. The top-center placement also matches Material's standard
        // "transient status" snackbar pattern and is the first thing the user
        // sees while ARCore is still initialising.
        //
        // #1265: a `BoxScope.align(TopCenter)` set directly on `AnimatedVisibility`
        // did not reliably position the animated content — the pill rendered
        // bottom-left, overlapping Clear All. `AnimatedVisibility` introduces its
        // own layout node for the enter/exit transition, and the `BoxScope.align`
        // modifier on that wrapper is not always honoured by the animated child.
        // The alignment is now carried by a static `Box` that is a direct child of
        // the outer `Box`; `AnimatedVisibility` lives inside it and handles only
        // the fade. This mirrors how the working stats pill above is structured.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                // Top padding accounts for: the stats pill (8 dp + ~28 dp text
                // height) + the per-model badge column when present
                // (placedModels.take(4) * ~26 dp). 56 dp keeps the pill visible
                // even with up to one badge below the stats; with the v4.3.5
                // hide-when-empty rule the Clear All button is also gone at
                // session start so there's no longer any visual collision.
                .padding(top = 56.dp)
        ) {
            // ForcedTrackingFailure.override shadows the real ARCore-reported reason
            // when a developer has picked one in the debug menu (#1881). Read it here
            // so flipping the override re-renders the overlay immediately.
            val effectiveReason = ForcedTrackingFailure.override ?: trackingFailureReason
            AnimatedVisibility(
                visible = !isTracking || ForcedTrackingFailure.override != null,
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
        }
    }
}
