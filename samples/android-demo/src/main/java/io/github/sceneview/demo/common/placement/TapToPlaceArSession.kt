package io.github.sceneview.demo.common.placement

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusWeak
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.android.filament.Engine
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneScope
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.demo.common.QaCameraBackdrop
import io.github.sceneview.demo.common.qaCameraBackdropEnabled
import io.github.sceneview.demo.common.qaCameraBackdropSurfaceType
import io.github.sceneview.demo.common.rememberQaCameraBackdropActive
import io.github.sceneview.ar.arcore.subsumedBy
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.DemoBottomOverlayScope
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.ar.PlacementReticleVisual
import io.github.sceneview.ar.PlaneDiscoveryGuide
import io.github.sceneview.ar.RETICLE_READY_ALPHA
import io.github.sceneview.ar.RETICLE_SEARCHING_ALPHA
import io.github.sceneview.ar.ReticlePhase
import io.github.sceneview.ar.reticlePhaseFor
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import kotlinx.coroutines.delay
import java.io.File

/**
 * The single canonical tap-to-place AR session
 * ([#2482](https://github.com/sceneview/sceneview/issues/2482) Option A). Renders a
 * full-bleed [ARSceneView] with: plane visualisation, centre placement reticle
 * ([#1882](https://github.com/sceneview/sceneview/issues/1882)), tap-to-place with the
 * shared [PlacementHitPolicy], per-placement `AnchorNode` + `ModelNode` with
 * texture-settle gating ([#1435](https://github.com/sceneview/sceneview/issues/1435)),
 * PAUSED-surviving anchors (#1435), per-asset rotation correction
 * ([#1477](https://github.com/sceneview/sceneview/issues/1477)), QA playback passthrough
 * ([#1576](https://github.com/sceneview/sceneview/issues/1576)), the camera-init scrim
 * (#2484) and the default coaching overlay.
 *
 * The **interaction** model each placed model carries — drag across the surface, twist to
 * turn, pinch against a 100 % real-world-size detent, and a damped arrival — lives in
 * [PlacedModelNode], and the decisions behind it in [PlacementInteraction.kt]
 * ([#3326](https://github.com/sceneview/sceneview/issues/3326)).
 *
 * The engine moves verbatim in behaviour out of `ARPlacementDemo`'s scene `Box`. Hosts:
 * the AR View tab (consumer entry) and the `ar-placement` feature demo (dev toggles).
 *
 * @param nextModelLabel Display name of what the NEXT tap will place, or `null` when
 *   nothing is armed. Named by the coaching line ("Tap to place {label}") and read by
 *   hosts for their own chrome ("Next tap places: …").
 * @param onPlaceModel Resolves the model for an accepted tap. CONTRACT: invoked exactly
 *   once per accepted placement, on the main thread, INSIDE the tap handler — never
 *   captured at composition (this is the #2476 fix as an API invariant). The host may
 *   advance internal cycle state here. Return `null` to reject the tap (e.g. asset still
 *   resolving and no fallback armed) — no anchor is created.
 * @param snapToPlane ON (default) ⇒ only detected-plane hits inside the polygon place;
 *   OFF ⇒ any tracked hit, with plane hits still polygon-gated (#1883).
 * @param showReticle Hide the reticle without losing the hit-test pipeline
 *   (#1882/#1883 dev toggle).
 * @param playbackDataset ARCore MP4 replay for the device-QA harness (#1576). Defaults to
 *   the pending deep-link dataset (`null` on every real-user launch).
 * @param sessionConfiguration Extra session config. Default `null` — the [ARSceneView]
 *   defaults already are HORIZONTAL_AND_VERTICAL plane finding and ENVIRONMENTAL_HDR
 *   light estimation.
 * @param onModelPlaced Fired after a placement is committed (haptics, analytics, snackbars).
 * @param overlays Coaching overlays drawn inside the session's Box, above the viewport.
 *   The default renders [TapToPlaceStatusOverlays] — one line at a time.
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
    /** Picks the QA camera backdrop deterministically per demo (#3308). */
    backdropSeed: String = "ar-placement",
) {
    // Viewport pixels (#1882). Captured via `onSizeChanged` on the outer Box.
    // `HitResultNode(xPx, yPx)` needs view-space pixel coordinates to continuously
    // hit-test the scene at the screen centre — without a measured viewport the reticle
    // would race a zero pose at composition time and stay parked at the AR origin.
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    // Keep a reference to the latest Frame for hit testing in the gesture callback.
    var latestFrame by remember { mutableStateOf<Frame?>(null) }
    // Planes currently tracked — feeds one invisible ShadowReceiverPlane each so placed
    // models ground with a real contact shadow (#2241 PR 5). Only consumed once a model is
    // placed (#2657 — the grid's own shadow receiver covers the pre-placement phase).
    var trackedPlanes by remember { mutableStateOf<List<Plane>>(emptyList()) }

    // Placement + resize confirmations. A placement you can feel is the difference between
    // "did that register?" and "it's in the room" — Scene Viewer, IKEA Place and Reality
    // Composer all tick on commit and again at the 100 % detent (#3326).
    val haptic = LocalHapticFeedback.current

    // QA camera backdrop (#3308): translucent surface + room photo beneath it when the
    // emulator delivers no camera frame. Inert on a device / when QA mode is off.
    val cameraStream = rememberARCameraStream(materialLoader)
    val qaBackdrop = rememberQaCameraBackdropActive(state.cameraReady)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it },
    ) {
        if (qaBackdrop) QaCameraBackdrop(seed = backdropSeed)
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            isOpaque = !qaCameraBackdropEnabled(),
            surfaceType = qaCameraBackdropSurfaceType(),
            cameraStream = if (qaBackdrop) null else cameraStream,
            playbackDataset = playbackDataset,
            // #2657: fade the plane grid — and, crucially, the V1 plane renderer's OWN shadow
            // receiver (plane_renderer_shadow.filamat) that rides with it — once the first model
            // is placed, so it never coexists with the ShadowReceiverPlane below on the same
            // plane. Two coplanar shadowMultiplier receivers z-fight and double-darken the contact
            // shadow (0.4 × 0.4 ≈ 0.16, near-black). Mirrors PlacementScene.fadePlaneOnFirstPlacement
            // + Google AR design guidance (stop decorating the floor once discovery is done).
            planeRenderer = shouldRenderPlaneGrid(state.placedCount),
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
                // Exclude subsumed (merged) planes — ARCore can keep a subsumed plane in
                // TRACKING with a non-null `subsumedBy`; rendering a ShadowReceiverPlane for
                // it double-darkens the multiplicative shadow and z-fights the coplanar quad
                // it was merged into (ARCore's recommended pre-render check).
                val tracked = session.getAllTrackables(Plane::class.java)
                    .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
                state.anyPlaneTracked = tracked.isNotEmpty()
                // Change-only write (60 Hz path): drives the ShadowReceiverPlane set.
                if (trackedPlanes != tracked) trackedPlanes = tracked
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
                        // Confirm the commit in the hand, and open the one-shot
                        // "drag / turn / pinch" window (#3326).
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        state.lastPlacedAtMillis = SystemClock.uptimeMillis()
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
                onScaleEnd = { _, _, _ ->
                    state.activeGesture = null
                    // The percentage read-out belongs to the live gesture only — Scene
                    // Viewer's number is gone the instant the fingers lift (#3326).
                    state.scalePercent = null
                    state.isRealWorldSize = false
                }
            )
        ) {
            // Placement reticle (#1882 → #2241 PR 5 → #3326). `PlacementReticle` runs the
            // centre-of-screen hit test each frame with the Depth Lab orientation smoothing
            // (slerp 0.75), so the marker does not jitter as ARCore refines the normal.
            // Acceptance stays single-sourced: the same PlacementHitPolicy the tap
            // handler uses runs in the reticle predicate (the node's built-in filters
            // already cover tracking state and plane-in-polygon; the policy re-checks
            // them plus the 5 m cap).
            // The reticle has no surface to sit on while the QA backdrop stands in for the
            // camera: un-hit it parks at the camera and fills the frame (#3308).
            if (viewportSize != IntSize.Zero && showReticle && !qaCameraBackdropEnabled()) {
                // Searching / ready, cross-faded rather than stepped. ARCore's centre-pixel
                // hit test flickers in and out over a half-converged plane, and a hard alpha
                // step turns that flicker into a strobing ring — the opposite of the
                // unambiguous "you can place now" signal the phase change exists to give
                // (#3326).
                val reticlePhase = reticlePhaseFor(state.reticleHit != null)
                val reticleAlpha by animateFloatAsState(
                    targetValue = if (reticlePhase == ReticlePhase.READY) {
                        RETICLE_READY_ALPHA
                    } else {
                        RETICLE_SEARCHING_ALPHA
                    },
                    animationSpec = tween(durationMillis = RETICLE_FADE_MS),
                    label = "placement-reticle-alpha",
                )
                PlacementReticle(
                    xPx = viewportSize.width / 2f,
                    yPx = viewportSize.height / 2f,
                    snapToPlane = snapToPlane,
                    predicate = { result ->
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
                    },
                    // Push the hit out to Compose state so the coaching line and the
                    // reticle phase can react. The callback already fires only on change.
                    onHitResultChanged = { state.reticleHit = it },
                ) {
                    // The consumer-AR ring with a centre dot on lock (Scene Viewer / IKEA
                    // Place / Houzz), not the flat cyan disc this used to draw. A filled disc
                    // has no "ready" state to show and reads as a decal stuck to the floor;
                    // the ring reads as a target and its centre dot appears the moment a tap
                    // would land (#3326). `arsceneview` already shipped this visual for
                    // `PlacementScene` — the demo was the surface still on the old one.
                    PlacementReticleVisual(
                        materialLoader = materialLoader,
                        phase = reticlePhase,
                        alpha = reticleAlpha,
                    )
                }
            }

            // One placement per committed anchor. `key(id)` gives each its own remember
            // slot, so the model instance inside loads fresh and independent per anchor.
            //
            // Everything the user then *does* to a placed model — drag it across the floor,
            // twist it, resize it against a 100 % detent, watch it grow into place — lives in
            // [PlacedModelNode] (#3326).
            state.placedModels.forEach { placed ->
                key(placed.id) {
                    PlacedModelNode(
                        placed = placed,
                        modelLoader = modelLoader,
                        snapToPlane = snapToPlane,
                        onScaleChanged = { percent, isRealWorldSize, crossedIntoRealWorldSize ->
                            state.scalePercent = percent
                            state.isRealWorldSize = isRealWorldSize
                            if (crossedIntoRealWorldSize) {
                                // One tick on entering the detent, never a buzz for every
                                // event spent inside it.
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                    )
                }
            }

            // Contact-shadow catcher per tracked plane (#2241 PR 5) — placed models read as
            // grounded instead of floating. The mesh renders nothing by itself
            // (shadow_receiver.filamat, shadowMultiplier).
            //
            // #2657: gated on "something has been placed". The V1 plane renderer above ALSO
            // receives shadows on every plane; an always-on ShadowReceiverPlane stacked a SECOND
            // coplanar shadowMultiplier quad on the same plane → z-fight + double-darkening. By
            // the time a model exists to cast a shadow, the grid (and its receiver) has receded
            // (shouldRenderPlaneGrid → false), so exactly ONE shadow receiver is ever live on a
            // plane. The two predicates are mutually exclusive by construction.
            if (shouldCatchGroundShadows(state.placedCount)) {
                trackedPlanes.forEach { plane ->
                    key(plane) {
                        ShadowReceiverPlane(plane = plane)
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
 * The placement screen's coaching layer — **one** sentence at a time, in the readable AR
 * scrim (#3295), plus the live resize read-out
 * ([#3326](https://github.com/sceneview/sceneview/issues/3326)).
 *
 * ## Why this is one line and not three
 *
 * It used to render three overlapping affordances at once: [PlaneDiscoveryGuide]'s pill at
 * the bottom, a top status pill, and an "Aim at a surface…" hint 96 dp off the bottom. Two
 * of them said the same thing in different words at the same moment ("Scanning for
 * surfaces…" over "Move your phone to find a surface"), the top pill announced a placed
 * count nobody asked for, and the guide's pill and the model bar were laid out 40 dp and
 * 16 dp off the same bottom edge — i.e. on top of each other on first launch, which is the
 * first thing a user sees.
 *
 * Consumer AR (Scene Viewer, IKEA Place, Reality Composer) shows exactly one short line,
 * and removes it as soon as the user has demonstrated they no longer need it. That
 * decision is [placementCoaching], a pure function with unit tests; this composable only
 * renders it. The split of responsibilities is deliberate and total:
 *
 *  - **Before a surface exists** — initialising, tracking lost, scanning — belongs to
 *    [PlaneDiscoveryGuide], the ARCore-Elements onboarding with its animated hand hint and
 *    its "Need help?" card. Nothing here duplicates it.
 *  - **After a surface exists** — aim, tap, and the one-shot gesture hint — belongs here.
 *  - **Once something is placed and the hint has expired**, the screen goes quiet.
 *
 * Reads [ForcedTrackingFailure.override] so the #1881 QA shim drives both surfaces.
 *
 * @param state The hoisted session state to render from.
 * @param nextModelLabel The model the next tap will place, named in the coaching line.
 */
@Composable
fun BoxScope.TapToPlaceStatusOverlays(
    state: TapToPlaceState,
    nextModelLabel: String?,
) {
    // The one-shot "drag / turn / pinch" window opened by the most recent placement. Keyed
    // on the placement timestamp, so a second placement restarts it rather than inheriting
    // the remains of the first one's window.
    var gestureHintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.lastPlacedAtMillis) {
        if (state.lastPlacedAtMillis == 0L) {
            gestureHintVisible = false
            return@LaunchedEffect
        }
        gestureHintVisible = true
        delay(PLACEMENT_GESTURE_HINT_MS)
        gestureHintVisible = false
    }

    // Surface discovery, tracking loss and the first-run coaching are the guide's job.
    //
    // The bottom padding is not decoration: the guide anchors its pill 40 dp off the bottom
    // edge and `TapToPlaceExperience` floats the model bar 16 dp off the same edge, so
    // unlifted they occupy the same band and collide on first launch — before the user has
    // done anything at all. Lifting the guide clear is a one-line fix here rather than a
    // signature change in `arsceneview`, because the collision is between two *demo*
    // decisions about the bottom band (#3326).
    PlaneDiscoveryGuide(
        cameraReady = state.cameraReady,
        isTracking = state.isTracking,
        anyPlaneTracked = state.anyPlaneTracked,
        trackingFailureReason = ForcedTrackingFailure.override ?: state.trackingFailureReason,
        modifier = Modifier.padding(bottom = PLANE_GUIDE_LIFT),
    )

    val coaching = placementCoaching(
        uxState = state.uxState,
        placedCount = state.placedCount,
        gestureHintVisible = gestureHintVisible,
    )
    val modelLabel = nextModelLabel ?: stringResource(R.string.ar_coach_generic_model)
    val coachingText = when (coaching) {
        PlacementCoachingMessage.POINT_AT_SURFACE ->
            stringResource(R.string.ar_coach_point_at_surface, modelLabel)

        PlacementCoachingMessage.TAP_TO_PLACE ->
            stringResource(R.string.ar_coach_tap_to_place, modelLabel)

        PlacementCoachingMessage.GESTURE_HINT ->
            stringResource(R.string.ar_coach_gesture_hint)

        null -> null
    }

    // ONE top-anchored stack. A Column computes the gap between the coaching line and the
    // resize read-out from the line's *measured* height, so it cannot go stale at a font
    // scale, a locale or a string the arithmetic was never checked against (#3237).
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                )
            )
            // Below the scaffold's glass identity row when there is one (#3250).
            .padding(
                top = SceneViewTokens.Space.sm +
                    io.github.sceneview.demo.LocalDemoChromeTopInset.current
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
    ) {
        // The same dark near-opaque scrim every other AR demo coaches through (#3295) —
        // white 16 sp on ar-scrim reads over an arbitrary camera frame, where the old
        // `surface.copy(alpha = 0.85f)` capsule with 14 sp label text did not. A null
        // `text` animates the pill out, so "say nothing" needs no wrapper here.
        DemoBottomOverlayScope(this, 0.dp).DemoStatusBanner(
            text = coachingText,
            tone = DemoStatusTone.Guidance,
            icon = coachingIcon(coaching),
        )

        // Live resize read-out — on screen only while two fingers are on the model.
        PlacementScaleReadout(
            percent = state.scalePercent,
            isRealWorldSize = state.isRealWorldSize,
        )
    }
}

/**
 * The leading indicator for a coaching line.
 *
 * `Guidance` is the tone throughout — the user is being asked to do something physical —
 * so the icon carries the difference between "point somewhere else", "you can tap now" and
 * "here is what your fingers can do".
 */
private fun coachingIcon(message: PlacementCoachingMessage?): ImageVector? = when (message) {
    PlacementCoachingMessage.POINT_AT_SURFACE -> Icons.Rounded.CenterFocusWeak
    PlacementCoachingMessage.TAP_TO_PLACE -> Icons.Rounded.TouchApp
    PlacementCoachingMessage.GESTURE_HINT -> Icons.Rounded.OpenWith
    null -> null
}

/**
 * The live pinch read-out: the placed model's size as a percentage of its **real-world**
 * size, with `100 %` called out as such.
 *
 * This is the piece that makes real-world scale legible rather than merely true. Scene
 * Viewer shows the same number for the same reason: a user who has resized an object has
 * no way back to "actual size" without one, and a percentage of an arbitrary fitted scale
 * would be a number about nothing. The gesture snaps to this value — see [PlacementScale].
 *
 * Hidden whenever [percent] is `null`, which is every moment except a live pinch.
 */
@Composable
private fun PlacementScaleReadout(
    percent: Int?,
    isRealWorldSize: Boolean,
) {
    // Latch the last value for the length of the exit fade, or the pill blanks its own
    // content on the frame the fade starts (same reason DemoStatusBanner latches).
    var lastPercent by remember { mutableStateOf(100) }
    var lastWasRealWorldSize by remember { mutableStateOf(true) }
    if (percent != null) {
        lastPercent = percent
        lastWasRealWorldSize = isRealWorldSize
    }

    AnimatedVisibility(
        visible = percent != null,
        enter = fadeIn(tween(SceneViewTokens.Duration.shortMillis)),
        exit = fadeOut(tween(SceneViewTokens.Duration.shortMillis)),
    ) {
        Surface(
            color = SceneViewTokens.ArOverlay.scrimDark,
            contentColor = SceneViewTokens.ArOverlay.onScrim,
            shape = RoundedCornerShape(50),
        ) {
            Text(
                text = if (lastWasRealWorldSize) {
                    stringResource(R.string.ar_scale_actual_size)
                } else {
                    stringResource(R.string.ar_scale_percent, lastPercent)
                },
                modifier = Modifier.padding(
                    horizontal = SceneViewTokens.Space.md,
                    vertical = SceneViewTokens.Space.sm,
                ),
                style = MaterialTheme.typography.labelLarge,
                // The detent is worth a weight change: it is the value the gesture will
                // pull back to, and the only one that means anything physical.
                fontWeight = if (lastWasRealWorldSize) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

/**
 * How far the [PlaneDiscoveryGuide] pill is lifted off the bottom edge so it clears the
 * model bar. The bar is 16 dp off the edge and roughly 56 dp tall; the guide's own 40 dp
 * offset then lands it inside the bar. 56 dp of lift puts a readable gap between them at
 * every font scale the bar itself survives.
 */
private val PLANE_GUIDE_LIFT = 56.dp

/**
 * Cross-fade duration for the reticle's searching / ready alpha, milliseconds. Short
 * enough that the lock still reads as immediate feedback, long enough to absorb a
 * one-frame hit-test dropout instead of strobing on it.
 */
private const val RETICLE_FADE_MS = 160
