package io.github.sceneview.demo.common.placement

import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.android.filament.Engine
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneScope
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.demos.internal.ArPlacement
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.demos.internal.rememberTexturesSettled
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import java.io.File

/**
 * The single canonical tap-to-place AR session
 * ([#2482](https://github.com/sceneview/sceneview/issues/2482) Option A). Renders a
 * full-bleed [ARSceneView] with: plane visualisation, centre placement reticle
 * ([#1882](https://github.com/sceneview/sceneview/issues/1882)), tap-to-place with the
 * shared [PlacementHitPolicy], per-placement `AnchorNode` + `ModelNode` with
 * texture-settle gating ([#1435](https://github.com/sceneview/sceneview/issues/1435)),
 * PAUSED-surviving anchors (#1435), per-asset rotation correction
 * ([#1477](https://github.com/sceneview/sceneview/issues/1477)), editable placed models
 * with a live gesture pill, QA playback passthrough
 * ([#1576](https://github.com/sceneview/sceneview/issues/1576)) and the default status
 * overlays (camera-init scrim #2484 + the
 * [#2234](https://github.com/sceneview/sceneview/issues/2234) plane-gated status pill
 * vocabulary).
 *
 * The engine moves verbatim in behaviour out of `ARPlacementDemo`'s scene `Box`. Hosts:
 * the AR View tab (consumer entry) and the `ar-placement` feature demo (dev toggles).
 *
 * @param nextModelLabel Display name of what the NEXT tap will place, or `null` when
 *   nothing is armed. Read by the default status pill ("Tap a surface to place {label}")
 *   and by hosts for their own chrome ("Next tap places: …").
 * @param onPlaceModel Resolves the model for an accepted tap. CONTRACT: invoked exactly
 *   once per accepted placement, on the main thread, INSIDE the tap handler — never
 *   captured at composition (this is the #2476 fix as an API invariant). The host may
 *   advance internal cycle state here. Return `null` to reject the tap (e.g. asset still
 *   resolving and no fallback armed) — no anchor is created.
 * @param snapToPlane ON (default) ⇒ only detected-plane hits inside the polygon place;
 *   OFF ⇒ any tracked hit, with plane hits still polygon-gated (#1883).
 * @param showReticle Hide the reticle disc without losing the hit-test pipeline
 *   (#1882/#1883 dev toggle).
 * @param playbackDataset ARCore MP4 replay for the device-QA harness (#1576). Defaults to
 *   the pending deep-link dataset (`null` on every real-user launch).
 * @param sessionConfiguration Extra session config. Default `null` — the [ARSceneView]
 *   defaults already are HORIZONTAL_AND_VERTICAL plane finding and ENVIRONMENTAL_HDR
 *   light estimation.
 * @param onModelPlaced Fired after a placement is committed (haptics, analytics, snackbars).
 * @param overlays Status overlays drawn inside the session's Box, above the viewport. The
 *   default renders the unified vocabulary.
 * @param extraSceneContent Extra AR-scope scene content rendered inside the same
 *   [ARSceneView] (escape hatch for future demo flourishes — e.g. Sprint-1
 *   `ShadowReceiverPlane`).
 */
@Composable
fun TapToPlaceArSession(
    nextModelLabel: String?,
    onPlaceModel: () -> PlacementSpec?,
    modifier: Modifier = Modifier,
    state: TapToPlaceState = rememberTapToPlaceState(),
    engine: Engine = rememberEngine(),
    modelLoader: ModelLoader = rememberModelLoader(engine),
    materialLoader: MaterialLoader = rememberMaterialLoader(engine),
    snapToPlane: Boolean = true,
    showReticle: Boolean = true,
    playbackDataset: File? = rememberArPlaybackDataset(),
    sessionConfiguration: ((Session, Config) -> Unit)? = null,
    onModelPlaced: ((PlacementSpec) -> Unit)? = null,
    overlays: @Composable BoxScope.(TapToPlaceState) -> Unit = { s ->
        TapToPlaceStatusOverlays(state = s, nextModelLabel = nextModelLabel)
    },
    extraSceneContent: (@Composable ARSceneScope.() -> Unit)? = null,
) {
    // Viewport pixels (#1882). Captured via `onSizeChanged` on the outer Box.
    // `HitResultNode(xPx, yPx)` needs view-space pixel coordinates to continuously
    // hit-test the scene at the screen centre — without a measured viewport the reticle
    // would race a zero pose at composition time and stay parked at the AR origin.
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    // Keep a reference to the latest Frame for hit testing in the gesture callback.
    var latestFrame by remember { mutableStateOf<Frame?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it },
    ) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            playbackDataset = playbackDataset,
            planeRenderer = true,
            sessionConfiguration = sessionConfiguration,
            // Typed Config.*Mode params (#1766) — both planeFindingMode and
            // lightEstimationMode are already the ARSceneView defaults, so no
            // sessionConfiguration callback is needed by default.
            onSessionUpdated = { session, frame: Frame ->
                state.cameraReady = true
                latestFrame = frame
                state.isTracking = frame.camera.trackingState == TrackingState.TRACKING
                // Recompute "is there any plane the user can actually tap?" each frame
                // (#2234). Detection is cheap — ARCore caches the trackable set
                // internally and we only scan Planes.
                state.anyPlaneTracked = session.getAllTrackables(Plane::class.java)
                    .any { it.trackingState == TrackingState.TRACKING }
            },
            onTrackingFailureChanged = { reason ->
                state.trackingFailureReason = reason
            },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { event: MotionEvent, node ->
                    // If the tap landed on an existing editable ModelNode, the gesture
                    // system handles it (drag/scale/rotate). Don't spawn on top.
                    if (node != null) return@rememberOnGestureListener

                    val frame = latestFrame ?: return@rememberOnGestureListener
                    if (frame.camera.trackingState != TrackingState.TRACKING) {
                        return@rememberOnGestureListener
                    }

                    // Single-sourced acceptance policy (mirrors the reticle filter below).
                    val hit = frame.hitTest(event).firstOrNull { result ->
                        val trackable = result.trackable
                        PlacementHitPolicy.accept(
                            isPlane = trackable is Plane,
                            isPoseInPolygon = trackable is Plane &&
                                trackable.isPoseInPolygon(result.hitPose),
                            isTrackableTracking =
                                trackable.trackingState == TrackingState.TRACKING,
                            distanceMeters = result.distance,
                            snapToPlane = snapToPlane,
                        )
                    }
                    if (hit != null) {
                        // Resolve the asset at tap time — the #2476 invariant. Return
                        // null to reject (asset still resolving, no fallback armed).
                        val spec = onPlaceModel() ?: return@rememberOnGestureListener
                        state.placedModels.add(
                            PlacedModel(
                                id = state.nextId++,
                                anchor = hit.createAnchor(),
                                spec = spec,
                            )
                        )
                        onModelPlaced?.invoke(spec)
                    }
                },
                // Surface which gesture is active so the user can tell drag-to-move from
                // twist-to-rotate from pinch-to-scale. `node != null` ⇒ gesture targets a
                // placed ModelNode; `node == null` ⇒ the touch fell through to the
                // background (AR has no orbit camera) so we skip the indicator.
                onMoveBegin = { _, _, node ->
                    if (node != null) state.activeGesture = PlacementGesture.MOVING
                },
                onMoveEnd = { _, _, _ -> state.activeGesture = null },
                onRotateBegin = { _, _, node ->
                    if (node != null) state.activeGesture = PlacementGesture.ROTATING
                },
                onRotateEnd = { _, _, _ -> state.activeGesture = null },
                onScaleBegin = { _, _, node ->
                    if (node != null) state.activeGesture = PlacementGesture.SCALING
                },
                onScaleEnd = { _, _, _ -> state.activeGesture = null }
            )
        ) {
            // Placement reticle (#1882). A thin unlit cyan disc that follows the
            // centre-of-screen hit-test result each frame. Purely visual — the tap
            // handler above runs its own hit-test at the tap coordinates, so the user
            // can place anywhere on screen, not only at the centre dot.
            //
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
                            PlacementHitPolicy.accept(
                                isPlane = trackable is Plane,
                                isPoseInPolygon = trackable is Plane &&
                                    trackable.isPoseInPolygon(result.hitPose),
                                isTrackableTracking =
                                    trackable.trackingState == TrackingState.TRACKING,
                                distanceMeters = result.distance,
                                snapToPlane = snapToPlane,
                            )
                        }
                        // Push the hit out to Compose state so the "Aim at a surface…"
                        // prompt can react. Only write on change to avoid churning the
                        // snapshot at 60 Hz with the same value.
                        if (state.reticleHit !== candidate) {
                            state.reticleHit = candidate
                        }
                        candidate
                    },
                ) {
                    // Thin disc — 7 cm radius, 5 mm tall — sits flush on the detected
                    // surface. HitResultNode's pose orients +Y along the surface normal
                    // so the disc naturally lays flat.
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
            // inside loads a fresh, independent ModelInstance per anchor.
            state.placedModels.forEach { placed ->
                key(placed.id) {
                    // visibleTrackingStates includes PAUSED so a placed model survives
                    // transient plane loss — it holds its last known pose instead of
                    // vanishing when ARCore briefly stops tracking the anchor (#1435).
                    AnchorNode(
                        anchor = placed.anchor,
                        visibleTrackingStates = ArPlacement.ANCHORED_VISIBLE_STATES
                    ) {
                        // `fileLocation =` forces the URL-capable overload (handles both
                        // the `file://` streamed URI and the bundled asset path). See the
                        // #2302 overload trap.
                        val instance =
                            rememberModelInstance(modelLoader, fileLocation = placed.spec.assetLocation)
                        // Gate visibility until Filament finishes uploading the model's
                        // textures, so it doesn't flash black on placement (#1435).
                        val textured = rememberTexturesSettled(ready = instance != null)
                        instance?.let {
                            ModelNode(
                                modelInstance = it,
                                scaleToUnits = placed.spec.scaleToUnits,
                                // Per-asset placement correction (#1477). `rotationOverride`
                                // wins when supplied; otherwise fall back to the shared
                                // helmet −90° X correction keyed by asset path.
                                rotation = placed.spec.rotationOverride
                                    ?: DemoMath.placementRotationFor(placed.spec.assetLocation),
                                isVisible = textured,
                                isEditable = true
                            )
                        }
                    }
                }
            }

            extraSceneContent?.invoke(this)
        }

        // Cover the still-black AR viewport until the first camera frame (#2484).
        ARCameraInitScrim(initializing = !state.cameraReady)

        overlays(state)
    }
}

/**
 * The unified status vocabulary as a reusable overlay block: camera-init scrim
 * (drawn by [TapToPlaceArSession] itself), the top status pill (#2234 wording +
 * TouchApp/CheckCircle icon), the "Aim at a surface…" bottom hint (#1882), and the
 * active-gesture pill.
 *
 * Reads [ForcedTrackingFailure.override] so the #1881 QA shim drives both surfaces.
 *
 * @param state The hoisted session state to render from.
 * @param nextModelLabel The model the next tap will place (used in the AIMING/READY pill).
 */
@Composable
fun BoxScope.TapToPlaceStatusOverlays(
    state: TapToPlaceState,
    nextModelLabel: String?,
) {
    val uxState = state.uxState
    val placed = state.placedCount

    // Top status pill — translucent capsule, M3 Expressive (AR-View visual style).
    val effectiveReason = ForcedTrackingFailure.override ?: state.trackingFailureReason
    val failureMessage = trackingFailureMessage(effectiveReason)
    val scanningLabel = stringResource(R.string.ar_status_scanning)
    val tapToPlaceLabel = nextModelLabel?.let {
        stringResource(R.string.ar_status_tap_to_place, it)
    } ?: scanningLabel
    val onePlacedLabel = stringResource(R.string.ar_status_one_placed)
    val nPlacedLabel = stringResource(R.string.ar_status_n_placed, placed)

    val pillText = when {
        uxState == TapToPlaceUxState.INITIALIZING -> scanningLabel
        uxState == TapToPlaceUxState.TRACKING_LOST -> failureMessage ?: scanningLabel
        uxState == TapToPlaceUxState.SCANNING -> scanningLabel
        placed == 1 -> onePlacedLabel
        placed > 1 -> nPlacedLabel
        else -> tapToPlaceLabel
    }
    val placedSomething = placed > 0

    Surface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(50),
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (placedSomething) {
                    Icons.Filled.CheckCircle
                } else {
                    Icons.Filled.TouchApp
                },
                contentDescription = null,
                tint = if (placedSomething) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = pillText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }

    // Active-gesture indicator — below the status pill, only while manipulating a model.
    AnimatedVisibility(
        visible = state.activeGesture != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 56.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.onPrimary,
            tonalElevation = 4.dp,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = gestureLabel(state.activeGesture),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }

    // "Aim at a surface…" prompt (#1882). Shown only in AIMING (camera tracking, plane
    // tracked, but the centre-pixel hit is empty).
    AnimatedVisibility(
        visible = uxState == TapToPlaceUxState.AIMING,
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
}

private fun gestureLabel(gesture: PlacementGesture?): String = when (gesture) {
    PlacementGesture.MOVING -> "Moving"
    PlacementGesture.ROTATING -> "Rotating"
    PlacementGesture.SCALING -> "Scaling"
    null -> ""
}
