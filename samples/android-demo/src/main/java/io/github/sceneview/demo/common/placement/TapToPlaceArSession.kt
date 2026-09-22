package io.github.sceneview.demo.common.placement

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.ScreenRotationAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.android.filament.Engine
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.findAutoPlacementSurface
import io.github.sceneview.ar.AutoPlacementResult
import io.github.sceneview.ar.ARSceneScope
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.subsumedBy
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.DemoBottomOverlayScope
import io.github.sceneview.demo.LocalDemoChromeBottomInset
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.common.QaCameraBackdrop
import io.github.sceneview.demo.common.qaCameraBackdropEnabled
import io.github.sceneview.demo.common.qaCameraBackdropSurfaceType
import io.github.sceneview.demo.common.rememberQaCameraBackdropActive
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.haptic.rememberHapticFeedback
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import kotlinx.coroutines.delay
import java.io.File

/**
 * The single canonical placement AR session
 * ([#2482](https://github.com/sceneview/sceneview/issues/2482) Option A), now **automatic**:
 * the selected asset is placed on the first usable surface the camera finds — no cursor,
 * no tap, no on-screen instruction compensating for either (Quick Look parity, plan §2.1).
 *
 * The decisions — when to search, when a frame places, what a tracking loss does to a
 * placed object, when the 10 s help card opens — are [AutoPlacementController], pure and
 * JVM-tested. What "usable surface" means is [UsableSurfacePolicy]. This composable is the
 * ARCore-facing shell around both: it feeds the controller one [FrameInput] per frame,
 * creates the anchor when the controller says [FrameEffect.PLACE], and mirrors the phase
 * into [TapToPlaceState] for the overlays.
 *
 * The **interaction** model the placed object carries — drag across the surface, twist to
 * turn, and pinch against a 100 % base-size detent — lives in
 * [PlacedModelNode], and the decisions behind it in [PlacementInteraction.kt]
 * ([#3326](https://github.com/sceneview/sceneview/issues/3326)).
 *
 * Hosts: the AR View tab (consumer entry) and the `ar-placement` feature demo.
 *
 * @param playbackDataset ARCore MP4 replay for the device-QA harness (#1576). Defaults to
 *   the pending deep-link dataset (`null` on every real-user launch).
 * @param sessionConfiguration Extra session config, applied after the placement defaults
 *   (horizontal plane finding, instant placement off).
 * @param onModelPlaced Fired after a placement is committed (analytics, snackbars).
 * @param onViewIn3D The no-surface card's primary action. `null` hides the button.
 * @param onRestartSession The camera-error card's *Try again*. `null` hides the button.
 * @param overlays Coaching overlays drawn inside the session's Box, above the viewport.
 *   The default renders [TapToPlaceStatusOverlays].
 * @param extraSceneContent Extra AR-scope scene content rendered inside the same
 *   [ARSceneView].
 */
@Composable
fun TapToPlaceArSession(
    modifier: Modifier = Modifier,
    state: TapToPlaceState = rememberTapToPlaceState(),
    engine: Engine = rememberEngine(),
    modelLoader: ModelLoader = rememberModelLoader(engine),
    materialLoader: MaterialLoader = rememberMaterialLoader(engine),
    playbackDataset: File? = rememberArPlaybackDataset(),
    sessionConfiguration: ((Session, Config) -> Unit)? = null,
    onModelPlaced: ((PlacementSpec) -> Unit)? = null,
    onViewIn3D: (() -> Unit)? = null,
    onRestartSession: (() -> Unit)? = null,
    overlays: @Composable BoxScope.(TapToPlaceState) -> Unit = { s ->
        TapToPlaceStatusOverlays(
            state = s,
            onViewIn3D = onViewIn3D,
            onRestartSession = onRestartSession,
        )
    },
    extraSceneContent: (@Composable ARSceneScope.() -> Unit)? = null,
    /** Picks the QA camera backdrop deterministically per demo (#3308). */
    backdropSeed: String = "ar-placement",
) {
    // Viewport pixels: the automatic search casts its first ray through the viewport
    // centre (§2.3), which needs the measured size — a zero viewport searches nothing.
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    // Planes currently tracked — one invisible ShadowReceiverPlane each, so the placed
    // object grounds with a real contact shadow (#2241 PR 5). Always on: the plane grid
    // and its own receiver are gone (§2.6), so there is never a second coplanar receiver.
    var trackedPlanes by remember { mutableStateOf<List<Plane>>(emptyList()) }

    // §2.8: `medium()` on the first placement, `warning()` once on tracking loss,
    // `selection()` on the 100 % detent and on selecting the object.
    val haptic = rememberHapticFeedback()

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
            // §2.6 — no plane fill, no reticle. The object itself is the only feedback.
            planeRenderer = false,
            // Upward-facing surfaces only (§2.3); walls use the separate vertical policy.
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL,
            instantPlacementMode = Config.InstantPlacementMode.DISABLED,
            sessionConfiguration = sessionConfiguration,
            onSessionUpdated = { session, frame: Frame ->
                state.cameraReady = true
                // The #1881 QA shim forces a tracking failure on the emulator; the
                // controller sees it as a real loss so both surfaces rehearse the state.
                val tracking = frame.camera.trackingState == TrackingState.TRACKING &&
                    ForcedTrackingFailure.override == null
                if (state.isTracking != tracking) state.isTracking = tracking

                // Exclude subsumed (merged) planes — ARCore can keep a subsumed plane in
                // TRACKING with a non-null `subsumedBy`; a ShadowReceiverPlane on it
                // double-darkens the multiplicative shadow and z-fights the quad it was
                // merged into. Change-only write (60 Hz path).
                val tracked = session.getAllTrackables(Plane::class.java)
                    .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
                if (trackedPlanes != tracked) trackedPlanes = tracked

                val controller = state.controller
                // Search only while a placement is owed: nothing is hit-tested once the
                // object stands, and nothing before an asset is offered.
                val surface = if (controller.wantsSurface && tracking && viewportSize != IntSize.Zero) {
                    findAutoPlacementSurface(frame, tracked, viewportSize.width, viewportSize.height)
                } else {
                    null
                }
                val now = SystemClock.uptimeMillis()
                var committed: AutoPlacementResult? = null
                val effect = controller.onFrame(
                    FrameInput(
                        nowMillis = now,
                        tracking = tracking,
                        surfaceAvailable = surface != null,
                        anchorTracking = state.placed?.anchor?.let {
                            it.trackingState == TrackingState.TRACKING
                        },
                    ),
                    commit = {
                        if (state.modelInstance == null) false else {
                            committed = surface?.createAnchor()
                            committed != null
                        }
                    },
                )
                when (effect) {
                    FrameEffect.PLACE -> {
                        val spec = state.spec
                        if (spec != null && committed != null) {
                            state.placed = PlacedModel(
                                id = state.nextId++,
                                placement = committed!!,
                                spec = spec,
                            )
                            // Confirm the commit in the hand, and open the one-shot
                            // "drag / pinch / twist" window.
                            haptic.medium()
                            state.lastPlacedAtMillis = now
                            onModelPlaced?.invoke(spec)
                        }
                    }

                    FrameEffect.TRACKING_LOST -> {
                        state.activeGesture = null
                        state.scalePercent = null
                        haptic.warning()
                    }
                    FrameEffect.NONE -> Unit
                }
                if (state.phase != controller.phase) state.phase = controller.phase
            },
            onARCoreAvailability = { state.arCoreAvailability = it },
            onTrackingFailureChanged = { reason ->
                state.trackingFailureReason = reason
            },
            onGestureListener = rememberOnGestureListener(
                // A tap on the object selects it (§2.4) — felt, not drawn: the demo has no
                // selection chrome to show. A tap on empty space creates nothing; the
                // controller documents that as a no-op rather than leaving it implicit.
                onSingleTapConfirmed = { _, node ->
                    if (node != null) {
                        state.controller.selectPlacement()
                        haptic.selection()
                    } else {
                        state.controller.deselectPlacement()
                        state.controller.onBackgroundTap()
                    }
                },
                // Surface which gesture is active so the read-out can tell drag-to-move
                // from twist-to-rotate from pinch-to-scale. `node == null` ⇒ the touch
                // fell through to the background (AR has no orbit camera), so we skip it.
                onMoveBegin = { _, _, node ->
                    if (node != null) state.activeGesture = PlacementGesture.MOVING
                },
                onMoveEnd = { _, _, _ ->
                    state.activeGesture = null
                    state.dragOffSurface = false
                },
                onRotateBegin = { _, _, node ->
                    if (node != null) state.activeGesture = PlacementGesture.ROTATING
                },
                onRotateEnd = { _, _, _ -> state.activeGesture = null },
                onScaleBegin = { _, _, node ->
                    if (node != null) state.activeGesture = PlacementGesture.SCALING
                },
                onScaleEnd = { _, _, _ ->
                    state.activeGesture = null
                    // The percentage read-out belongs to the live gesture only — the
                    // number is gone the instant the fingers lift (#3326).
                    state.scalePercent = null
                    state.isRealWorldSize = false
                }
            )
        ) {
            // The one placement. `key(id)` gives a reset-then-replaced object its own
            // remember slot, so the model instance inside loads fresh per anchor.
            state.placed?.let { placed ->
                key(placed.id) {
                    PlacedModelNode(
                        placed = placed,
                        modelInstance = state.modelInstance,
                        controller = state.controller,
                        onScaleChanged = { percent, isRealWorldSize, crossedIntoRealWorldSize ->
                            state.scalePercent = percent
                            state.isRealWorldSize = isRealWorldSize
                            // One tick on entering the detent, never a buzz for every
                            // event spent inside it (§2.8).
                            if (crossedIntoRealWorldSize) haptic.selection()
                        },
                        onDragOffSurface = { off ->
                            if (state.dragOffSurface != off) state.dragOffSurface = off
                        },
                    )
                }
            }

            // Contact-shadow catcher per tracked plane (#2241 PR 5). The mesh renders
            // nothing by itself (shadow_receiver.filamat, shadowMultiplier).
            val grounded = state.phase == PlacementPhase.PLACED ||
                state.phase == PlacementPhase.ADJUSTING
            trackedPlanes.filter { grounded }.forEach { plane ->
                key(plane) {
                    ShadowReceiverPlane(plane = plane)
                }
            }

            extraSceneContent?.invoke(this)
        }

        // Cover the still-black AR viewport until the first camera frame (#2484) — unless
        // ARCore has already ruled the session out (#3341), in which case that frame is never
        // coming and the scrim would bury the SDK's own explanation card.
        ARCameraInitScrim(
            initializing = !state.cameraReady,
            arCoreAvailability = state.arCoreAvailability,
        )

        overlays(state)
    }
}

/**
 * The placement screen's coaching layer — **one** sentence at a time in the readable AR
 * scrim (#3295), one action card when the flow needs a decision, plus the live resize
 * read-out ([#3326](https://github.com/sceneview/sceneview/issues/3326)).
 *
 * What is said is [placementCoaching] / [placementCard], pure functions with unit tests;
 * this composable only renders them, from the bottom edge up: the read-out, then the
 * card, then the coaching pill nearest the dock.
 *
 * Reads [ForcedTrackingFailure.override] so the #1881 QA shim drives both surfaces.
 */
@Composable
fun BoxScope.TapToPlaceStatusOverlays(
    state: TapToPlaceState,
    onViewIn3D: (() -> Unit)? = null,
    onRestartSession: (() -> Unit)? = null,
) {
    // The one-shot "Drag to move. Pinch or twist to adjust." window opened by the
    // placement. Keyed on the placement timestamp, so a re-placement restarts it rather
    // than inheriting the remains of the first one's window.
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

    // Did AR simply never start? `ARCameraInitScrim` covers the wait, then dismisses
    // itself on a timeout whether or not a frame arrived — so past that point INITIALIZING
    // has no affordance at all unless this one speaks. Leaving INITIALIZING cancels the
    // timer; an ARCore-unsupported device keeps the SDK's own explanation instead.
    val initializing = state.phase == PlacementPhase.INITIALIZING
    LaunchedEffect(initializing, state.arCoreAvailability) {
        if (!initializing || state.arCoreAvailability != null) return@LaunchedEffect
        delay(PLACEMENT_STARTUP_STALL_MS)
        state.controller.cameraFailed()
        state.phase = state.controller.phase
    }

    val lowLight = (ForcedTrackingFailure.override ?: state.trackingFailureReason) ==
        TrackingFailureReason.INSUFFICIENT_LIGHT
    val coaching = placementCoaching(
        phase = state.phase,
        gestureHintVisible = gestureHintVisible,
        dragOffSurface = state.dragOffSurface,
        lowLight = lowLight,
    )
    val card = placementCard(state.phase)

    // ── The one bottom anchor ─────────────────────────────────────────────────────────
    //
    // Everything this screen says lives in one stack, measured from one edge: the bottom
    // of the safe area, plus the dock the scaffold parks there, plus one 16 dp gutter.
    // Each child carries its own gutter as a TOP padding inside its visibility wrapper,
    // so a hidden child contributes exactly nothing and the bottom-most visible thing
    // sits 16 dp off the dock (#3237, measured on the goldens).
    val chromeBottom = LocalDemoChromeBottomInset.current
    var coachStackPx by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .testTag(PlacementTestTags.COACH_STACK)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .padding(horizontal = SceneViewTokens.Space.md)
            .padding(bottom = chromeBottom + SceneViewTokens.Space.md)
            // LAST in the chain: reports the stack's own content and nothing else.
            .onSizeChanged { coachStackPx = it.height },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Live resize read-out — on screen only while two fingers are on the model.
        PlacementScaleReadout(
            percent = state.scalePercent,
            isRealWorldSize = state.isRealWorldSize,
            label = state.scaleLabel,
        )

        // The decision cards (§2.2): no surface after 10 s, a placement that could not be
        // recovered, a camera that never started.
        PlacementActionCard(
            card = if (state.modelLoading || state.modelError) null else card,
            onViewIn3D = onViewIn3D,
            onKeepScanning = { state.keepScanning() },
            onScanAgain = { state.resetPlacement() },
            onRestartSession = onRestartSession,
        )

        if (state.modelError) {
            Button(onClick = { state.assetRetry++ }) { Text(stringResource(R.string.ar_place_try_again)) }
        }

        // The same dark near-opaque scrim every other AR demo coaches through (#3295). A
        // null `text` animates the pill out. Last in the Column = nearest the dock = the
        // fixed point of the anchor.
        DemoBottomOverlayScope(this, 0.dp).DemoStatusBanner(
            text = when {
                state.modelLoading -> stringResource(R.string.ar_place_loading_model)
                state.modelError -> stringResource(R.string.ar_place_model_failed)
                else -> coachingText(coaching)
            },
            tone = coachingTone(coaching),
            icon = coachingIcon(coaching),
            modifier = Modifier
                .testTag(PlacementTestTags.COACHING_LINE)
                .padding(top = SceneViewTokens.Space.sm),
        )
    }
}

/** Test tags for the placement screen's bottom anchor. */
object PlacementTestTags {
    /** The single coaching sentence, last child of the anchor and nearest the dock. */
    const val COACHING_LINE = "placement-coaching-line"

    /** The live resize read-out — on screen only while two fingers are on the model. */
    const val SCALE_READOUT = "placement-scale-readout"

    /** The anchored Column itself — the node whose measured content height is `coachStackPx`. */
    const val COACH_STACK = "placement-coach-stack"

    /** The action card (no surface / recovery failed / camera error). */
    const val PLACEMENT_CARD = "placement-card"

    /** The action card's primary (filled) button. */
    const val PLACEMENT_CARD_PRIMARY = "placement-card-primary"

    /** The action card's secondary (text) button, when the card has one. */
    const val PLACEMENT_CARD_SECONDARY = "placement-card-secondary"
}

@Composable
private fun coachingText(message: PlacementCoachingMessage?): String? = when (message) {
    PlacementCoachingMessage.MOVE_SLOWLY -> stringResource(R.string.ar_place_move_slowly)
    PlacementCoachingMessage.TRACKING_PAUSED -> stringResource(R.string.ar_place_tracking_paused)
    PlacementCoachingMessage.TRACKING_PAUSED_LOW_LIGHT ->
        stringResource(R.string.ar_place_tracking_paused) + " " +
            stringResource(R.string.ar_place_try_brighter_area)

    PlacementCoachingMessage.FINDING_PLACEMENT -> stringResource(R.string.ar_place_finding_placement)
    PlacementCoachingMessage.GESTURE_HINT -> stringResource(R.string.ar_place_gesture_hint)
    PlacementCoachingMessage.KEEP_ON_SURFACE -> stringResource(R.string.ar_place_keep_on_surface)
    null -> null
}

/**
 * `Guidance` for everything the user can act on with their hands, `Progress` while the
 * session is doing the work (finding the placement again).
 */
private fun coachingTone(message: PlacementCoachingMessage?): DemoStatusTone = when (message) {
    PlacementCoachingMessage.FINDING_PLACEMENT -> DemoStatusTone.Progress
    else -> DemoStatusTone.Guidance
}

private fun coachingIcon(message: PlacementCoachingMessage?): ImageVector? = when (message) {
    PlacementCoachingMessage.MOVE_SLOWLY -> Icons.Rounded.ScreenRotationAlt
    PlacementCoachingMessage.TRACKING_PAUSED,
    PlacementCoachingMessage.TRACKING_PAUSED_LOW_LIGHT -> Icons.Rounded.PauseCircleOutline

    PlacementCoachingMessage.GESTURE_HINT,
    PlacementCoachingMessage.KEEP_ON_SURFACE -> Icons.Rounded.OpenWith

    PlacementCoachingMessage.FINDING_PLACEMENT, null -> null
}

/**
 * The §2.2 decision card: a title, an optional detail line, a filled primary action and an
 * optional secondary one, on the AR scrim like every other line the screen shows. Built
 * from the same `DESIGN.md` tokens as the coaching pill (`ar-scrim`, `radius-lg`,
 * `over-media-edge`, `dock-item` touch targets), with the primary action filled in the
 * theme's `primary` role.
 */
@Composable
private fun PlacementActionCard(
    card: PlacementCard?,
    onViewIn3D: (() -> Unit)?,
    onKeepScanning: () -> Unit,
    onScanAgain: () -> Unit,
    onRestartSession: (() -> Unit)?,
) {
    // Latch the last card for the length of the exit animation, as the pill does.
    var lastCard by remember { mutableStateOf(PlacementCard.NO_SURFACE) }
    if (card != null) lastCard = card

    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val scrim = if (dark) SceneViewTokens.ArOverlay.scrimDark else SceneViewTokens.ArOverlay.scrimLight
    val shape = RoundedCornerShape(SceneViewTokens.Radius.lg)
    val buttonShape = RoundedCornerShape(SceneViewTokens.Radius.md)

    AnimatedVisibility(
        visible = card != null,
        enter = fadeIn(
            tween(SceneViewTokens.Duration.mediumMillis, easing = SceneViewTokens.Ease.expressive)
        ) + slideInVertically(
            tween(SceneViewTokens.Duration.mediumMillis, easing = SceneViewTokens.Ease.expressive),
            initialOffsetY = { it / 3 },
        ),
        exit = fadeOut(
            tween(SceneViewTokens.Duration.shortMillis, easing = SceneViewTokens.Ease.expressive)
        ) + slideOutVertically(
            tween(SceneViewTokens.Duration.shortMillis, easing = SceneViewTokens.Ease.expressive),
            targetOffsetY = { it / 3 },
        ),
    ) {
        val title: String
        val detail: String?
        val primary: String
        val onPrimary: (() -> Unit)?
        val secondary: String?
        val onSecondary: (() -> Unit)?
        when (lastCard) {
            PlacementCard.NO_SURFACE -> {
                title = stringResource(R.string.ar_place_no_surface_title)
                detail = stringResource(R.string.ar_place_no_surface_detail)
                primary = stringResource(R.string.ar_place_view_in_3d)
                onPrimary = onViewIn3D
                secondary = stringResource(R.string.ar_place_keep_scanning)
                onSecondary = onKeepScanning
            }

            PlacementCard.RECOVERY_FAILED -> {
                title = stringResource(R.string.ar_place_recovery_failed_title)
                detail = null
                primary = stringResource(R.string.ar_place_scan_again)
                onPrimary = onScanAgain
                secondary = null
                onSecondary = null
            }

            PlacementCard.CAMERA_ERROR -> {
                title = stringResource(R.string.ar_place_camera_error_title)
                detail = null
                primary = stringResource(R.string.ar_place_try_again)
                onPrimary = onRestartSession
                secondary = null
                onSecondary = null
            }
        }

        Column(
            modifier = Modifier
                .padding(top = SceneViewTokens.Space.sm)
                .testTag(PlacementTestTags.PLACEMENT_CARD)
                .widthIn(max = SceneViewTokens.ArOverlay.maxWidth)
                .fillMaxWidth()
                .shadow(elevation = SceneViewTokens.Elevation.lg, shape = shape, clip = false)
                .background(color = scrim, shape = shape)
                .overMediaEdge(shape)
                .padding(SceneViewTokens.Space.md),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = SceneViewTokens.ArOverlay.onScrim,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SceneViewTokens.ArOverlay.onScrimMuted,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = SceneViewTokens.Space.sm),
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (secondary != null && onSecondary != null) {
                    TextButton(
                        onClick = onSecondary,
                        shape = buttonShape,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = SceneViewTokens.ArOverlay.onScrim,
                        ),
                        modifier = Modifier
                            .heightIn(min = SceneViewTokens.Layout.touchTarget)
                            .testTag(PlacementTestTags.PLACEMENT_CARD_SECONDARY),
                    ) {
                        Text(secondary, style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (onPrimary != null) {
                    Button(
                        onClick = onPrimary,
                        shape = buttonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier
                            .heightIn(min = SceneViewTokens.Layout.touchTarget)
                            .testTag(PlacementTestTags.PLACEMENT_CARD_PRIMARY),
                    ) {
                        Text(primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/**
 * The live pinch read-out: the placed model's size as a percentage of its real-world
 * size — "Actual size" when that size was measured, "Preview size" when it is an estimate
 * (§2.3), so the number never claims more than the asset knows.
 *
 * Hidden whenever [percent] is `null`, which is every moment except a live pinch.
 */
@Composable
private fun PlacementScaleReadout(
    percent: Int?,
    isRealWorldSize: Boolean,
    label: ScaleLabelMode,
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
            modifier = Modifier
                .padding(top = SceneViewTokens.Space.sm)
                .testTag(PlacementTestTags.SCALE_READOUT),
            color = SceneViewTokens.ArOverlay.scrimDark,
            contentColor = SceneViewTokens.ArOverlay.onScrim,
            shape = RoundedCornerShape(50),
        ) {
            Text(
                text = when (label) {
                    ScaleLabelMode.ACTUAL ->
                        stringResource(R.string.ar_scale_actual_size_percent, lastPercent)

                    ScaleLabelMode.PREVIEW ->
                        stringResource(R.string.ar_scale_preview_size, lastPercent)
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
