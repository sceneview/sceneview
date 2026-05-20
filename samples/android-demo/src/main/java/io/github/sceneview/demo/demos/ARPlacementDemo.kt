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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.IntSize
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
 * Interactive AR tap-to-place demo with a "Pick what to place" picker and a screen-center
 * placement reticle.
 *
 * Plane detection is rendered as a translucent overlay. Each tap on a detected horizontal or
 * vertical plane spawns a NEW [ModelNode] instance attached to its own
 * [AnchorNode][io.github.sceneview.ar.node.AnchorNode].
 *
 * **Placement reticle ([#1882](https://github.com/sceneview/sceneview/issues/1882)).** A small
 * disc tracks the centre-of-screen hit-test result each frame so the user can see *where* their
 * next tap will land before committing. The reticle is purely visual — taps still resolve their
 * own hit at the tap coordinates, so the user can place anywhere on screen, not only at centre.
 * When no surface is detected under the centre pixel, a "Aim at a surface…" prompt is shown
 * instead. Wired via the AR-scope [io.github.sceneview.ar.ARSceneScope.HitResultNode] composable.
 *
 * **Picker — Stage 2 ([#1152](https://github.com/sceneview/sceneview/issues/1152)).** The
 * controls sheet exposes a chip row sourced from [SampleAssets.byCategory]`["ar_placement"]`
 * — coffee mug / houseplant / wooden crate / side table / floor lamp / picture frame, all
 * streamed CC-BY models from Sketchfab via [SketchfabAssetResolver]. Tapping a chip arms
 * that slug as the next placed model; subsequent taps on a plane place a fresh instance.
 *
 * **Bundled model picker ([#1883](https://github.com/sceneview/sceneview/issues/1883)).** A
 * second chip row lets the user lock the bundled cycle to a single specific GLB (Damaged
 * Helmet / Fox / Lantern / Toy Car / Shiba) or keep the auto-cycle that rotates through all
 * five on every tap. Bundled-mode chips are mutually exclusive with the streamed picker
 * above — selecting a streamed slug overrides the bundled choice for that tap.
 *
 * **Settings ([#1883](https://github.com/sceneview/sceneview/issues/1883)).** The sheet also
 * exposes a Snap-to-plane toggle (default ON — accept only detected plane hits) and a Show
 * reticle toggle (default ON — dev-only). The "Clear All" button wipes every placed model and
 * detaches the underlying ARCore anchors.
 *
 * Each placed model is **editable** — `isEditable = true` on the [ModelNode] enables
 * pinch-to-scale, two-finger rotate, and one-finger drag. Because the parent [AnchorNode] is
 * locked to its ARCore [Anchor] pose, the editable child node transforms relative to the anchor:
 * the anchor stays glued to the plane while the user manipulates the model on top of it.
 *
 * Top-center pill shows the live "X models placed" count.
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

    // Bundled-model picker (#1883). Locks the cycle to a single GLB so the
    // user can predict exactly what each tap places. `null` ⇒ keep the
    // auto-cycle that rotates through all five on every tap (v4.3.1
    // behaviour). Mutually exclusive with the streamed `selectedSlug`
    // chip row above — if a streamed slug is selected and resolved, that
    // wins regardless of `selectedBundledIndex`.
    var selectedBundledIndex by remember { mutableStateOf<Int?>(null) }

    // Settings toggles (#1883). Defaults preserve the previous strict
    // plane-only behaviour. Show-reticle is wired to the in-scene reticle
    // node's `isVisible`; dev users can disable it for screenshots.
    var snapToPlane by remember { mutableStateOf(true) }
    var showReticle by remember { mutableStateOf(true) }

    // Viewport pixels (#1882). Captured via `onSizeChanged` on the outer
    // Box. `HitResultNode(xPx, yPx)` needs view-space pixel coordinates to
    // continuously hit-test the scene at the screen centre — without a
    // measured viewport the reticle would race a zero pose at composition
    // time and stay parked at the AR origin.
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    // Latest centre-pixel hit, surfaced from the HitResultNode lambda
    // below. Drives the "Aim at a surface…" prompt: `null` ⇒ nothing
    // detected → hide the reticle disc + show the prompt; non-null ⇒
    // reticle disc is at a real surface.
    var reticleHit by remember { mutableStateOf<HitResult?>(null) }

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

    // Active-gesture label (shown while the user is mid-manipulating a placed
    // model). `null` ⇒ no overlay. Mirrors the GestureEditingDemo pattern so
    // both demos share the same visual language for "what is my touch doing
    // right now". Drag → "Moving", twist → "Rotating", pinch → "Scaling".
    var gestureMode by remember { mutableStateOf<String?>(null) }

    // Per-demo offline indicator chip (#1152 Stage 3). The chip reflects the
    // selected slug's resolve state — `null` means no slug picked yet (cycle
    // mode), so we surface "Bundled fallback" (the cycle is 100% bundled).
    val assetSource = when {
        selectedSlug == null -> AssetSourceState.Bundled
        SketchfabConfig.apiKey == null -> AssetSourceState.Bundled
        selectedFile == null -> AssetSourceState.Streaming
        else -> AssetSourceState.Streamed
    }

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

            // Settings toggles (#1883). Snap-to-plane gates the tap handler:
            // ON ⇒ only detected planes accept placements (v4.3.1 behaviour);
            // OFF ⇒ accept any tracked hit (points, depth, instant placement).
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
            // disabled at a glance.
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    // Detach every ARCore anchor so the session stops tracking them, then drop
                    // the Compose state — recomposition removes the AnchorNodes from the graph.
                    placedModels.forEach { runCatching { it.anchor.detach() } }
                    placedModels.clear()
                    cycleIndex = 0
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Clear All")
            }

            // Up-next preview so the user knows what the next tap will spawn.
            val nextLabel = selectedSlug?.let { slug ->
                if (selectedFile != null) slug.displayName
                else stringResource(R.string.demo_ar_placement_picker_streaming, slug.displayName)
            } ?: selectedBundledIndex?.let { MODEL_CYCLE[it].displayName }
                ?: MODEL_CYCLE[cycleIndex % MODEL_CYCLE.size].displayName
            Text(
                text = "Next tap places: $nextLabel",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = it },
        ) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
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

                        // Perform an ARCore hit test at the tap coordinates. With Snap-to-plane
                        // ON (default), only detected planes accept placements — the v4.3.1
                        // behaviour. With Snap-to-plane OFF, any tracked hit is accepted
                        // (Plane, Point, DepthPoint, InstantPlacementPoint) so the user can
                        // place on arbitrary geometry the depth/feature pipeline can see (#1883).
                        val hitResults = frame.hitTest(event)
                        val hit = hitResults.firstOrNull { result ->
                            val trackable = result.trackable
                            if (trackable.trackingState != TrackingState.TRACKING) return@firstOrNull false
                            if (result.distance > 5.0f) return@firstOrNull false
                            if (snapToPlane) {
                                trackable is Plane && trackable.isPoseInPolygon(result.hitPose)
                            } else {
                                // Free placement: any tracked surface, including the plane
                                // path above. Still require Plane hits to fall inside the
                                // polygon so we don't anchor floating off the edge of a half-
                                // converged plane.
                                if (trackable is Plane) trackable.isPoseInPolygon(result.hitPose)
                                else true
                            }
                        }
                        if (hit != null) {
                            // Resolve the asset location based on the picker. Priority order:
                            //   1. A selected streamed slug whose download has landed → its
                            //      file URI.
                            //   2. A bundled-model lock chosen in Settings (#1883) → that
                            //      specific GLB on every tap.
                            //   3. Fall back to the rotating MODEL_CYCLE (v4.3.1 behaviour).
                            // A streamed slug whose download is still in flight silently
                            // demotes to (2) or (3) so the tap is never lost.
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
                // Placement reticle (#1882). A thin unlit cyan disc that follows the centre-of-
                // screen hit-test result each frame. The reticle is purely visual — the tap
                // handler above runs its own hit-test at the tap coordinates, so the user can
                // still place anywhere on screen, not only at the centre dot.
                //
                // Hidden when:
                //   - The viewport hasn't been measured yet (`viewportSize == Zero`), to avoid
                //     racing a (0, 0) hit-test on first composition.
                //   - The Settings "Show reticle (dev)" toggle is OFF.
                //   - No hit landed under the centre pixel (`reticleHit == null`). The "Aim at
                //     a surface…" prompt below covers that state.
                //
                // The `HitResultNode(hitTest = …)` overload gives us full control over which
                // hits feed the reticle, mirroring the snap-to-plane policy of the tap handler
                // so the on-screen disc and the next-tap behaviour stay in sync. The lambda
                // also writes the latest hit to `reticleHit` so the "Aim at a surface…" prompt
                // outside the AR scope can recompose on each new state.
                // Allocate the reticle material once and unconditionally so toggling
                // `showReticle` doesn't leak a fresh MaterialInstance on every flip — the
                // `remember` slot must stay stable across recompositions.
                val reticleMaterial = remember(materialLoader) {
                    materialLoader.createUnlitColorInstance(
                        Color(0x99_44_E7_FF)  // semi-transparent cyan
                    )
                }
                if (viewportSize != IntSize.Zero && showReticle) {
                    val centreX = viewportSize.width / 2f
                    val centreY = viewportSize.height / 2f
                    HitResultNode(
                        hitTest = { frame ->
                            val candidate = frame.hitTest(centreX, centreY).firstOrNull { result ->
                                val trackable = result.trackable
                                if (trackable.trackingState != TrackingState.TRACKING) {
                                    return@firstOrNull false
                                }
                                if (result.distance > 5.0f) return@firstOrNull false
                                if (snapToPlane) {
                                    trackable is Plane && trackable.isPoseInPolygon(result.hitPose)
                                } else {
                                    if (trackable is Plane) trackable.isPoseInPolygon(result.hitPose)
                                    else true
                                }
                            }
                            // Push the hit out to Compose state so the "Aim at a surface…"
                            // prompt can react. Only write on change to avoid churning the
                            // snapshot at 60 Hz with the same value.
                            if (reticleHit !== candidate) {
                                reticleHit = candidate
                            }
                            candidate
                        },
                    ) {
                        // Thin disc — 7 cm radius, 5 mm tall — sits flush on the detected
                        // surface. The CylinderNode's local +Y axis is its height axis;
                        // HitResultNode's pose orients +Y along the surface normal for Plane
                        // hits, so the disc naturally lays flat. For Point/Depth hits the
                        // pose's +Y is the estimated surface normal — still correct.
                        CylinderNode(
                            radius = 0.07f,
                            height = 0.005f,
                            sideCount = 48,
                            materialInstance = reticleMaterial,
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

            // "Aim at a surface…" prompt (#1882). Visible only when the camera is tracking
            // (no tracking-failure pill below) but the centre-pixel hit-test came back empty
            // — the user is panning across nothing the depth/plane pipeline can grab onto, so
            // the reticle is hidden and we surface a one-liner so the screen never looks broken.
            // Bottom-centred above the scanning pill so the two never collide.
            AnimatedVisibility(
                visible = isTracking && reticleHit == null && showReticle,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp),
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    contentColor = Color.White,
                    tonalElevation = 4.dp,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "Aim at a surface…",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
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

