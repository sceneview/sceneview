package io.github.sceneview.demo.demos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.demo.common.QaCameraBackdrop
import io.github.sceneview.demo.common.qaCameraBackdropEnabled
import io.github.sceneview.demo.common.qaCameraBackdropSurfaceType
import io.github.sceneview.demo.common.rememberQaCameraBackdropActive
import io.github.sceneview.ar.arcore.hitTestDepth
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.demos.internal.MeasureCandidateControl
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.SceneAction
import io.github.sceneview.demo.common.SceneActionBar
import io.github.sceneview.demo.demos.internal.BoxDimensions
import io.github.sceneview.demo.demos.internal.formatCentimeters
import io.github.sceneview.demo.demos.internal.measureBoundingBox
import io.github.sceneview.demo.demos.internal.measureDistanceMeters
import io.github.sceneview.demo.demos.internal.measureMidpoint
import io.github.sceneview.demo.demos.internal.measurePerimeterMeters
import io.github.sceneview.demo.demos.internal.measurePointsMoved
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.math.Position
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.sample.rememberUnlitMaterialInstance

/** Radius of the sphere dropped on each measured point, in metres (~2.4 cm across). */
private const val MARKER_RADIUS_METERS = 0.012f

/** Height of a segment's floating label above the segment midpoint, in metres. */
private const val LABEL_LIFT_METERS = 0.04f

/** World size of a segment label's quad. 4:1 to match the label bitmap's aspect ratio. */
private const val LABEL_WIDTH_METERS = 0.16f
private const val LABEL_HEIGHT_METERS = 0.04f

/**
 * Where a measured point came from. Surfaced to the user because the three sources do not
 * carry the same accuracy — a plane hit is the most trustworthy, a raw feature point the
 * least — and a measuring tool that hides which one it used is a measuring tool you cannot
 * calibrate. See `AR_MEASURE.md`.
 */
private enum class MeasureHitSource(val label: String) {
    Plane("plane"),
    Depth("depth map"),
    FeaturePoint("feature point"),
}

/** A point the user placed: its ARCore [Anchor] plus where the hit came from. */
private data class MeasurePoint(
    val anchor: Anchor,
    val source: MeasureHitSource,
)

/**
 * AR measurement demo — add two or more points on the real world and read the distance
 * between them.
 *
 * ## What it does
 * Each frame resolves the visible center target; Add point anchors that exact candidate on
 * whatever it lands on. Consecutive points are joined by a line carrying a world-anchored
 * 3D label with the distance in centimetres. Keep adding points to build a chain (a running
 * perimeter), close the loop to measure an outline, and read the bounding box of every
 * point placed so far — width, height and depth.
 *
 * ## What it is for
 * Surveying a real space — a workshop, a room, a piece of furniture — to size something you
 * are about to 3D-print or build for it. That is the use case the sample's README documents,
 * and it is also the honest limit of the sample: see the accuracy section below.
 *
 * ## Hit-test strategy
 * Three sources, in descending order of trustworthiness:
 *  1. **A detected [Plane]**, when the center target lands inside its polygon. ARCore has fitted this
 *     surface over many frames, so it is the most stable target available.
 *  2. **A [DepthPoint]** from the same [Frame.hitTest] call, which resolves geometry ARCore
 *     has depth for but has not grown a plane over.
 *  3. **The depth image directly**, via [hitTestDepth] — this catches the case that matters
 *     most for surveying a cluttered space: a sofa, a slope, the edge of a workbench, where
 *     no plane will ever appear. Toggleable, because on a device with no depth support it
 *     can only ever return `null`.
 *
 * A raw feature [com.google.ar.core.Point] is accepted last and labelled as such, since its
 * position is the noisiest of the three.
 *
 * ## Accuracy — read this before trusting a number
 * On a phone with no time-of-flight sensor, ARCore synthesises depth from motion stereo and
 * the error is **several centimetres**. With ToF/LiDAR hardware it approaches the
 * centimetre. That is enough to lay out a space — will this cabinet fit, how far apart are
 * these studs, what is the clear height — and it is **not** enough for a fitting dimension
 * on a printed part, which lives at tenths of a millimetre. The full protocol and the
 * measured figures are in
 * [`samples/android-demo/AR_MEASURE.md`](../../../../../../../../AR_MEASURE.md).
 *
 * The arithmetic behind every displayed number lives in
 * [io.github.sceneview.demo.demos.internal.MeasureMathKt] and is unit-tested headlessly —
 * a wrong distance formula renders a perfectly plausible label that no device pass catches.
 */
@Composable
fun ARMeasureDemo(onBack: () -> Unit) {
    var arSessionFailed by remember { mutableStateOf(false) }
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val cameraNode = rememberARCameraNode(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch.
    val arPlaybackDataset = rememberArPlaybackDataset()

    val points = remember { mutableStateListOf<MeasurePoint>() }
    // World positions of `points`, refreshed from the anchors only when they actually move
    // (see `measurePointsMoved`). Anchors drift as ARCore refines tracking, so this cannot
    // be computed once when added — but neither can it be pushed into Compose state on
    // every frame without recomposing the scene subtree at 60 Hz for invisible jitter.
    var worldPoints by remember { mutableStateOf<List<Position>>(emptyList()) }
    var closedLoop by remember { mutableStateOf(false) }

    var session by remember { mutableStateOf<Session?>(null) }
    val candidateControl = remember { MeasureCandidateControl<MeasureCandidate>() }
    var candidateReady by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var isTracking by remember { mutableStateOf(false) }
    // Cover the jet-black ARSceneView surface until ARCore delivers its first camera frame,
    // so the ~1-3 s warm-up on entry doesn't read as a frozen screen (#2484).
    var cameraReady by remember { mutableStateOf(false) }
    // #3341: non-null once ARCore has ruled this device out. `cameraReady` never flips
    // then, so the init scrim below has to read the verdict or it covers the SDK's own
    // explanation card forever.
    var arCoreAvailability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    val cameraStream = rememberARCameraStream(materialLoader)
    // QA camera backdrop (#3308) — the emulator has no camera stream, so without this the
    // measuring screen is an empty surface behind its error card, like its sibling demos.
    val qaBackdrop = rememberQaCameraBackdropActive(cameraReady)
    var depthSupported by remember { mutableStateOf(false) }
    var useDepthFallback by remember { mutableStateOf(true) }
    // Why Add point placed nothing, or where the last placed point came from. Both are
    // shown to the user: an action that silently does nothing is the single most confusing
    // thing a measuring tool can do.
    var pointFeedback by remember { mutableStateOf<String?>(null) }
    // Loudness of `pointFeedback`, set in the same branches that set the sentence: the
    // two failure messages tell the user to move or re-aim the device (Guidance), the
    // success one just confirms a point landed (Progress).
    var pointTone by remember { mutableStateOf(DemoStatusTone.Progress) }

    // Anchors are native ARCore handles: dropping the composable without detaching them
    // leaks them into the session for as long as it lives.
    DisposableEffect(Unit) {
        onDispose { points.forEach { it.anchor.detach() } }
    }

    val markerMaterial = rememberUnlitMaterialInstance(materialLoader, SceneViewColors.Accent)
    val lineMaterial = rememberUnlitMaterialInstance(materialLoader, SceneViewColors.Primary)

    val boundingBox: BoxDimensions? = measureBoundingBox(worldPoints)
    val totalMeters = measurePerimeterMeters(worldPoints, closed = closedLoop)
    val lastSegmentMeters = if (worldPoints.size >= 2) {
        measureDistanceMeters(worldPoints[worldPoints.size - 2], worldPoints.last())
    } else {
        null
    }

    DemoScaffold(
        arSessionFailed = arSessionFailed,
        arOverlaysEnabled = arCoreAvailability == null,
        title = stringResource(R.string.demo_ar_measure_title),
        chromeToggleOnTap = false,
        onBack = onBack,
        peekHeader = when {
            worldPoints.isEmpty() -> stringResource(R.string.demo_ar_measure_hint_first)
            worldPoints.size == 1 -> stringResource(R.string.demo_ar_measure_hint_second)
            closedLoop -> stringResource(
                R.string.demo_ar_measure_perimeter,
                formatCentimeters(totalMeters),
            )
            else -> stringResource(
                R.string.demo_ar_measure_total,
                formatCentimeters(totalMeters),
            )
        },
        onReset = {
            points.forEach { it.anchor.detach() }
            points.clear()
            worldPoints = emptyList()
            closedLoop = false
            pointFeedback = null
        },
        controls = {
            Text(
                text = stringResource(R.string.demo_ar_measure_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = SceneViewTokens.Space.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.demo_ar_measure_depth_toggle),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = if (depthSupported) {
                            stringResource(R.string.demo_ar_measure_depth_supported)
                        } else {
                            stringResource(R.string.demo_ar_measure_depth_unsupported)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = useDepthFallback && depthSupported,
                    onCheckedChange = {
                        useDepthFallback = it
                        candidateControl.update(null)
                        candidateReady = false
                    },
                    enabled = depthSupported,
                )
            }
            SceneActionBar(
                SceneAction(
                    label = stringResource(R.string.demo_ar_measure_action_undo),
                    onClick = {
                        points.removeLastOrNull()?.anchor?.detach()
                        worldPoints = points.map { it.anchor.pose.toPosition() }
                        if (points.size < 3) closedLoop = false
                        pointFeedback = null
                    },
                    enabled = points.isNotEmpty(),
                ),
                SceneAction(
                    label = if (closedLoop) {
                        stringResource(R.string.demo_ar_measure_action_open)
                    } else {
                        stringResource(R.string.demo_ar_measure_action_close)
                    },
                    onClick = { closedLoop = !closedLoop },
                    enabled = points.size > 2,
                ),
                SceneAction(
                    label = stringResource(R.string.demo_ar_measure_action_clear),
                    onClick = {
                        points.forEach { it.anchor.detach() }
                        points.clear()
                        worldPoints = emptyList()
                        closedLoop = false
                        pointFeedback = null
                    },
                    enabled = points.isNotEmpty(),
                ),
            )
            // The accuracy caveat is not buried in the README only: a user reading a
            // centimetre figure on screen needs to know what it is worth, right there.
            Text(
                text = stringResource(R.string.demo_ar_measure_accuracy_notice),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = SceneViewTokens.Space.sm),
            )
        },
        topOverlay = {
            // Readout pill — the last segment, and the bounding box once there is one.
            // Screen-anchored rather than world-anchored on purpose: the per-segment labels
            // are already in the world, and a summary that can drift off-screen is a summary
            // you cannot read (#2727).
            if (worldPoints.size >= 2) {
                Surface(
                    color = SceneViewTokens.Glass.scrim,
                    contentColor = SceneViewTokens.Glass.onGlass,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = SceneViewTokens.Space.md,
                            vertical = SceneViewTokens.Space.sm,
                        ),
                    ) {
                        lastSegmentMeters?.let {
                            Text(
                                text = stringResource(
                                    R.string.demo_ar_measure_last_segment,
                                    formatCentimeters(it),
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        boundingBox?.let { box ->
                            Text(
                                text = stringResource(
                                    R.string.demo_ar_measure_bounding_box,
                                    formatCentimeters(box.widthMeters),
                                    formatCentimeters(box.heightMeters),
                                    formatCentimeters(box.depthMeters),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        bottomOverlay = {
            // Per-point feedback, including the hit source that produced the point.
            AnimatedVisibility(
                visible = pointFeedback != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                DemoStatusBanner(
                    text = pointFeedback.orEmpty(),
                    tone = pointTone,
                )
            }

            SceneActionBar(
                SceneAction(
                    label = stringResource(R.string.demo_ar_measure_action_add),
                    enabled = candidateReady && isTracking,
                    onClick = {
                        val placed = if (isTracking) candidateControl.consume { it.anchor() } else null
                        candidateReady = candidateControl.current != null
                        if (placed != null) {
                            points.add(placed)
                            closedLoop = false
                            worldPoints = points.map { it.anchor.pose.toPosition() }
                            pointFeedback = "Point ${points.size} on ${placed.source.label}"
                            pointTone = DemoStatusTone.Progress
                        } else {
                            pointFeedback = "No surface at the target. Move slowly."
                            pointTone = DemoStatusTone.Guidance
                        }
                    },
                ),
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (qaBackdrop) QaCameraBackdrop(seed = "ar-measure")
            ARSceneView(
                onSessionFailure = { arSessionFailed = true },
                modifier = Modifier.fillMaxSize().onSizeChanged {
                    viewport = it
                    candidateControl.update(null)
                    candidateReady = false
                },
                engine = engine,
                materialLoader = materialLoader,
                isOpaque = !qaCameraBackdropEnabled(),
                surfaceType = qaCameraBackdropSurfaceType(),
                cameraStream = if (qaBackdrop) null else cameraStream,
                cameraNode = cameraNode,
                playbackDataset = arPlaybackDataset,
                planeRenderer = false,
                sessionConfiguration = { configuredSession: Session, config: Config ->
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    // Depth widens what can be measured from "surfaces ARCore grew a plane
                    // over" to "anything the depth camera sees". Probe before enabling:
                    // requesting an unsupported mode fails session configuration outright.
                    val supported =
                        configuredSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                    depthSupported = supported
                    config.depthMode = if (supported) {
                        Config.DepthMode.AUTOMATIC
                    } else {
                        Config.DepthMode.DISABLED
                    }
                },
                onSessionCreated = { created: Session -> session = created },
                onARCoreAvailability = { arCoreAvailability = it },
                onSessionUpdated = { _: Session, frame: Frame ->
                    cameraReady = true
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    candidateControl.update(if (isTracking && viewport.width > 0 && viewport.height > 0) {
                        findMeasureCandidate(frame, viewport.width / 2f, viewport.height / 2f,
                            useDepthFallback && depthSupported, session)
                    } else null)
                    candidateReady = candidateControl.current != null

                    // Re-read every anchor pose: ARCore corrects them as it refines its map,
                    // and a measurement that ignored those corrections would drift away from
                    // the marker the user is looking at.
                    if (points.isNotEmpty()) {
                        val next = points.map { it.anchor.pose.toPosition() }
                        if (measurePointsMoved(worldPoints, next)) {
                            worldPoints = next
                        }
                    }
                },

            ) {
                // One AnchorNode per measured point. The library keeps each node glued to
                // its anchor every frame without a recomposition, so the markers stay put
                // even between the throttled `worldPoints` refreshes.
                points.forEach { point ->
                    key(point.anchor) {
                        AnchorNode(anchor = point.anchor) {
                            SphereNode(
                                radius = MARKER_RADIUS_METERS,
                                materialInstance = markerMaterial,
                            )
                        }
                    }
                }

                // Segments + their anchored labels, in world space. `LineNode` renders a
                // 1-px GPU line: thin, but geometrically exact and free — the alternative
                // (a rotated cylinder) would need a direction-to-Euler conversion whose
                // convention risk buys nothing a measuring overlay needs.
                val pts = worldPoints
                for (i in 0 until pts.size - 1) {
                    key("segment-$i") {
                        MeasureSegment(
                            start = pts[i],
                            end = pts[i + 1],
                            materialInstance = lineMaterial,
                            cameraPositionProvider = { cameraNode.worldPosition },
                        )
                    }
                }
                if (closedLoop && pts.size > 2) {
                    key("segment-closing") {
                        MeasureSegment(
                            start = pts.last(),
                            end = pts.first(),
                            materialInstance = lineMaterial,
                            cameraPositionProvider = { cameraNode.worldPosition },
                        )
                    }
                }
            }

            // This target and the ARCore center ray share the exact same viewport bounds.
            // Only Add point consumes the candidate; camera taps have no placement handler.
            if (cameraReady) {
                val targetDescription = stringResource(if (candidateReady)
                    R.string.demo_ar_measure_target_ready else R.string.demo_ar_measure_target_searching)
                Canvas(Modifier.align(Alignment.Center).size(SceneViewTokens.Space.lg)
                    .semantics { contentDescription = targetDescription }) {
                    val radius = size.minDimension / 3f
                    drawCircle(SceneViewTokens.Glass.edgeHalo, radius = radius,
                        style = Stroke(SceneViewTokens.Glass.edgeWidth.toPx() * 3))
                    drawCircle(SceneViewTokens.Glass.onGlass, radius = radius,
                        style = Stroke(SceneViewTokens.Glass.edgeWidth.toPx()))
                    if (candidateReady) drawCircle(SceneViewColors.TintLight,
                        radius = SceneViewTokens.Glass.edgeWidth.toPx() * 3)
                }
            }

            // Cover the still-black AR viewport until the first camera frame (#2484).
            ARCameraInitScrim(
                initializing = !cameraReady,
                arCoreAvailability = arCoreAvailability,
            )
        }
    }
}

/**
 * One measured segment: the line itself plus its distance label, floating just above the
 * midpoint and turned toward the camera so it stays readable from any angle.
 */
@Composable
private fun io.github.sceneview.ar.ARSceneScope.MeasureSegment(
    start: Position,
    end: Position,
    materialInstance: com.google.android.filament.MaterialInstance,
    cameraPositionProvider: () -> Position,
) {
    LineNode(
        start = start,
        end = end,
        materialInstance = materialInstance,
    )
    val midpoint = measureMidpoint(start, end)
    TextNode(
        text = formatCentimeters(measureDistanceMeters(start, end)),
        widthMeters = LABEL_WIDTH_METERS,
        heightMeters = LABEL_HEIGHT_METERS,
        position = Position(
            x = midpoint.x,
            y = midpoint.y + LABEL_LIFT_METERS,
            z = midpoint.z,
        ),
        cameraPositionProvider = cameraPositionProvider,
    )
}

/** Immutable center-ray result. Anchoring never performs a second hit test. */
private data class MeasureCandidate(val createAnchor: () -> Anchor?, val source: MeasureHitSource) {
    fun anchor(): MeasurePoint? = runCatching { createAnchor() }.getOrNull()?.let {
        MeasurePoint(it, source)
    }
}

/** Accuracy-ordered candidates at the displayed center target, without creating anchors. */
private fun findMeasureCandidate(
    frame: Frame,
    x: Float,
    y: Float,
    allowDepthFallback: Boolean,
    session: Session?,
): MeasureCandidate? {
    val hits = runCatching { frame.hitTest(x, y) }.getOrNull().orEmpty()
        .filter { it.trackable.trackingState == TrackingState.TRACKING }
    hits.firstOrNull { hit ->
        val plane = hit.trackable as? Plane
        plane != null && plane.subsumedBy == null && plane.isPoseInPolygon(hit.hitPose)
    }?.let { hit ->
        return MeasureCandidate({
            hit.takeIf { it.trackable.trackingState == TrackingState.TRACKING }?.createAnchor()
        }, MeasureHitSource.Plane)
    }
    if (allowDepthFallback) {
        hits.firstOrNull { it.trackable is DepthPoint }?.let { hit ->
            return MeasureCandidate({
                hit.takeIf { it.trackable.trackingState == TrackingState.TRACKING }?.createAnchor()
            }, MeasureHitSource.Depth)
        }
        if (session != null) frame.hitTestDepth(x, y)?.let { depthHit ->
            val pose = Pose(
                floatArrayOf(depthHit.position.x, depthHit.position.y, depthHit.position.z),
                floatArrayOf(0f, 0f, 0f, 1f),
            )
            return MeasureCandidate({ session.createAnchor(pose) }, MeasureHitSource.Depth)
        }
    }
    hits.firstOrNull { it.trackable is com.google.ar.core.Point }?.let { hit ->
        return MeasureCandidate({
            hit.takeIf { it.trackable.trackingState == TrackingState.TRACKING }?.createAnchor()
        }, MeasureHitSource.FeaturePoint)
    }
    return null
}

/** ARCore [Pose] translation as a SceneView world [Position]. */
private fun Pose.toPosition(): Position = Position(x = tx(), y = ty(), z = tz())
