package io.github.sceneview.demo.demos

import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.ar.rememberHostLifecycle
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ArDemoPermissionGate
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.demos.internal.AnchorHealth
import io.github.sceneview.demo.demos.internal.AnchorSpot
import io.github.sceneview.demo.demos.internal.ArDrawingStage
import io.github.sceneview.demo.demos.internal.ArDrawingStatus
import io.github.sceneview.demo.demos.internal.Drawing
import io.github.sceneview.demo.demos.internal.FingerDrawing
import io.github.sceneview.demo.demos.internal.FingerDrawingPlacement
import io.github.sceneview.demo.demos.internal.SteadyPen
import io.github.sceneview.demo.isDemoCompactHeight
import io.github.sceneview.demo.recording.rememberDemoSceneRecorder
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.math.Position
import io.github.sceneview.rememberARView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.utils.screenToRay

/**
 * Finger Drawing, AR mode — the same paint as [FingerDrawingDemo], left hanging in the room
 * (#4082).
 *
 * The finger paints on a plane [FingerDrawingPlacement.AR_PAINT_DEPTH_METERS] in front of
 * the phone, an arm's reach, and the stroke stays where it was painted: walk around it, look
 * at it from behind. No surface has to be found first, so there is nothing to scan before
 * the first stroke.
 *
 * Two things differ from the 3D mode, and both are in this file:
 *
 * - **The pen follows the camera, not the touch events.** The touch handler only records
 *   where the finger is; the stroke is extended in `onSessionUpdated`, once per camera
 *   frame. A finger held still on a phone that moves draws a line through the room — the
 *   gesture AR drawing is made of — and no touch event fires for it.
 * - **A stroke lives in the space of an ARCore anchor**, not in world space. ARCore moves
 *   its world as it learns the room; an anchor moves with what it was attached to. A new
 *   anchor is only created when the pen starts further than
 *   [FingerDrawingPlacement.AR_ANCHOR_REACH_METERS] from every anchor the drawing already
 *   has, so a whole sketch hangs on one anchor and stays rigid.
 *
 * An anchor is released by leaving the composition: `AnchorNode` detaches it when the last
 * stroke that hung on it is undone.
 *
 * **An anchor is not always tracked**, and `AnchorNode` hides what hangs on one that is not.
 * After a trip to the background ARCore pauses every anchor until it recognises the room
 * again, which a phone lying still may never do. So the state of each anchor is read on
 * every camera frame, and three things follow from it ([ArDrawingStage]): a new stroke never
 * hangs on an anchor that is not tracked, the stroke count is what is on screen and the
 * banner says how to bring the rest back, and the strokes of an anchor ARCore stopped for
 * good are removed.
 *
 * @param brush Colour and thickness, shared with the 3D mode so a mode switch keeps them.
 */
@Composable
fun FingerDrawingArDemo(onBack: () -> Unit, brush: FingerBrush = rememberFingerBrush()) {
    // Inspection mode (Android Studio @Preview pane, Robolectric tests): bypass the
    // Filament-backed body BEFORE any rememberEngine() call — LayoutLib ships no .so files.
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = "Finger Drawing", onBack = onBack)
        return
    }
    // Only this mode opens the camera, so only this mode asks for it: the 3D mode stays
    // one tap away for someone who refuses.
    ArDemoPermissionGate(
        title = stringResource(R.string.demo_finger_drawing_title),
        onBack = onBack,
    ) {
        FingerDrawingArScene(onBack, brush)
    }
}

@Composable
private fun FingerDrawingArScene(onBack: () -> Unit, brush: FingerBrush) {
    var drawing by remember { mutableStateOf(Drawing()) }
    // Which anchor each stroke hangs on, by stroke id.
    var anchorOf by remember { mutableStateOf(emptyMap<Int, Anchor>()) }
    // What ARCore said of each of those anchors on the last camera frame.
    var healthOf by remember { mutableStateOf(emptyMap<Anchor, AnchorHealth>()) }

    var isTracking by remember { mutableStateOf(false) }
    // Cover the jet-black ARSceneView surface until ARCore delivers its first camera frame.
    var cameraReady by remember { mutableStateOf(false) }
    // Non-null once ARCore has ruled this device out: `cameraReady` never flips then, so
    // the init scrim has to read the verdict or it covers the SDK's own explanation card.
    var arCoreAvailability by remember { mutableStateOf<ARCoreAvailability?>(null) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val view = rememberARView(engine)
    val cameraNode = rememberARCameraNode(engine)
    val arPlaybackDataset = rememberArPlaybackDataset()
    // The shared capture actions: a photo or a video of the drawing in the room — camera feed
    // and paint, rendered by Filament, without the Compose chrome.
    val recorder = rememberDemoSceneRecorder()
    val paints = rememberPaintMaterials(materialLoader)

    val density = LocalDensity.current.density
    val pen = remember { ArPen() }

    /** Keeps only the anchors a stroke still hangs on; the others leave with their `AnchorNode`. */
    fun commit(next: Drawing) {
        drawing = next
        val alive = next.strokes.mapTo(HashSet()) { it.id }
        next.active?.let { alive += it.id }
        if (anchorOf.keys.any { it !in alive }) anchorOf = anchorOf.filterKeys { it in alive }
    }

    /** The point under the finger on the plane an arm's reach in front of the phone. */
    fun paintPoint(x: Float, y: Float): Position? {
        val forward = cameraNode.forwardDirection
        val ray = view.screenToRay(x, y) ?: return null
        val depth = FingerDrawingPlacement.AR_PAINT_DEPTH_METERS
        return FingerDrawingPlacement.planeHit(
            rayOrigin = ray.origin,
            rayDirection = ray.direction,
            planePoint = cameraNode.worldPosition + forward * depth,
            planeNormal = forward,
        )
    }

    fun startStroke(session: Session, frameNanos: Long) {
        val point = paintPoint(pen.x, pen.y) ?: return
        val aside = paintPoint(pen.x + SCALE_PROBE_DP * density, pen.y) ?: return
        val anchors = anchorOf.values.distinct()
        // Read from ARCore now, not from the last frame's state: only a tracked anchor is
        // on screen and has a pose worth measuring a distance to.
        val reused = FingerDrawingPlacement.anchorToReuse(
            point = point,
            anchors = anchors.map { AnchorSpot(it.pose.position(), it.trackingState.toHealth()) },
            reach = FingerDrawingPlacement.AR_ANCHOR_REACH_METERS,
        )
        val anchor = reused?.let { anchors[it] }
            // ARCore refuses an anchor while it is not tracking, and can run out of them.
            ?: runCatching {
                session.createAnchor(Pose(floatArrayOf(point.x, point.y, point.z), IDENTITY_ROTATION))
            }.getOrNull()
            ?: return
        val pose = anchor.pose

        pen.anchor = anchor
        pen.unitsPerDp = FingerDrawingPlacement.unitsPerDp(point, aside, SCALE_PROBE_DP)
        pen.steady = SteadyPen.at(point)
        pen.lastNanos = frameNanos
        pen.speed = 0f
        val next = drawing.penDown(
            point = FingerDrawingPlacement.worldToAnchor(point, pose.position(), pose.rotation()),
            radius = FingerDrawing.radiusFor(brush.thicknessDp, pen.unitsPerDp, 0f),
            colorIndex = brush.colorIndex,
            frameHint = FingerDrawingPlacement.worldDirectionToAnchor(
                direction = cameraNode.forwardDirection,
                anchorRotation = pose.rotation(),
            ),
            spacing = FingerDrawing.RING_SPACING_DP * pen.unitsPerDp,
        )
        next.active?.let { anchorOf = anchorOf + (it.id to anchor) }
        commit(next)
    }

    fun endStroke() {
        pen.anchor = null
        pen.steady = null
        commit(drawing.penUp())
    }

    fun extendStroke(frameNanos: Long) {
        val anchor = pen.anchor ?: return
        val steady = pen.steady ?: return
        // The anchor went under the pen: its pose means nothing now and the stroke is
        // hidden. End it where it is; the next touch gets an anchor that is tracked.
        if (anchor.trackingState != TrackingState.TRACKING) {
            endStroke()
            return
        }
        val point = paintPoint(pen.x, pen.y) ?: return
        val dtSeconds = (frameNanos - pen.lastNanos) / NANOS_PER_SECOND
        pen.speed = FingerDrawing.smoothedSpeed(
            previous = pen.speed,
            measured = FingerDrawing.speedDpPerSecond(steady.finger, point, pen.unitsPerDp, dtSeconds),
            dtSeconds = dtSeconds,
        )
        // The same steadying as the 3D mode, in the room: it also takes the tremor of a
        // hand holding a phone at arm's length out of the line.
        val followed = steady.follow(point, dtSeconds)
        pen.steady = followed
        pen.lastNanos = frameNanos
        val pose = anchor.pose
        val next = drawing.penMove(
            point = FingerDrawingPlacement.worldToAnchor(followed.paint, pose.position(), pose.rotation()),
            targetRadius = FingerDrawing.radiusFor(brush.thicknessDp, pen.unitsPerDp, pen.speed),
        )
        // A very long stroke is continued in a new one; it hangs on the same anchor.
        next.active?.let { if (it.id !in anchorOf) anchorOf = anchorOf + (it.id to anchor) }
        commit(next)
    }

    /**
     * Reads every anchor's state from ARCore — a handful of anchors, one call each — and
     * drops the strokes of those it stopped for good. A paused anchor keeps its strokes: it
     * may come back.
     */
    fun readAnchors() {
        if (anchorOf.isEmpty()) {
            if (healthOf.isNotEmpty()) healthOf = emptyMap()
            return
        }
        val health = HashMap<Anchor, AnchorHealth>()
        anchorOf.values.forEach { anchor -> health.getOrPut(anchor) { anchor.trackingState.toHealth() } }
        if (health != healthOf) healthOf = health
        val lost = anchorOf.filterValues { health[it] == AnchorHealth.Stopped }.keys
        if (lost.isEmpty()) return
        if (pen.anchor?.let { health[it] } == AnchorHealth.Stopped) {
            pen.anchor = null
            pen.steady = null
        }
        commit(drawing.without(lost))
    }

    val stage = ArDrawingStage.of(
        drawing = drawing,
        healthOf = anchorOf.mapValues { (_, anchor) -> healthOf[anchor] ?: AnchorHealth.Tracking },
        cameraReady = cameraReady,
        cameraTracking = isTracking,
    )
    val strokeCount = stage.visibleStrokeCount
    val compactHeight = isDemoCompactHeight()

    DemoScaffold(
        title = stringResource(R.string.demo_finger_drawing_title),
        onBack = onBack,
        peekHeader = when {
            compactHeight || strokeCount == 0 -> null
            else -> pluralStringResource(R.plurals.demo_finger_drawing_strokes, strokeCount, strokeCount)
        },
        onResetSettings = brush::reset,
        recorder = recorder,
        dock = listOf(
            DockItem(
                icon = Icons.AutoMirrored.Outlined.Undo,
                label = stringResource(R.string.demo_finger_drawing_undo),
                onClick = { commit(drawing.undo()) },
                enabled = !drawing.isEmpty,
            ),
            DockItem(
                icon = Icons.Rounded.DeleteSweep,
                label = stringResource(R.string.demo_finger_drawing_clear),
                onClick = { commit(drawing.clear()) },
                enabled = !drawing.isEmpty,
            ),
        ),
        controls = {
            FingerDrawingControls(
                explainer = stringResource(R.string.demo_finger_drawing_ar_explainer),
                colorIndex = brush.colorIndex,
                onColorChange = { brush.colorIndex = it },
                thicknessDp = brush.thicknessDp,
                onThicknessChange = { brush.thicknessDp = it },
                canUndo = !drawing.isEmpty,
                onUndo = { commit(drawing.undo()) },
                onClear = { commit(drawing.clear()) },
            )
        },
        bottomOverlay = {
            DemoStatusBanner(
                text = when (stage.status) {
                    ArDrawingStatus.Starting, ArDrawingStatus.Drawing -> null
                    ArDrawingStatus.FindingPlace ->
                        stringResource(R.string.demo_finger_drawing_ar_tracking_hint)

                    ArDrawingStatus.DrawingHidden ->
                        stringResource(R.string.demo_finger_drawing_ar_hidden_hint)

                    ArDrawingStatus.ReadyToDraw ->
                        stringResource(R.string.demo_finger_drawing_ar_draw_hint)
                },
                tone = if (isTracking) DemoStatusTone.Guidance else DemoStatusTone.Progress,
                icon = if (stage.status == ArDrawingStatus.ReadyToDraw) Icons.Rounded.Gesture else null,
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                lifecycle = rememberHostLifecycle(),
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                materialLoader = materialLoader,
                view = view,
                cameraNode = cameraNode,
                playbackDataset = arPlaybackDataset,
                surfaceMirrorer = recorder.surfaceMirrorer,
                // Nothing is placed on a surface here: the plane grid would only be noise
                // behind the paint.
                planeRenderer = false,
                sessionConfiguration = { _: Session, config: Config ->
                    config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                },
                onARCoreAvailability = { arCoreAvailability = it },
                onSessionUpdated = { session: Session, frame: Frame ->
                    cameraReady = true
                    val tracking = frame.camera.trackingState == TrackingState.TRACKING
                    isTracking = tracking
                    readAnchors()
                    // Down, move and up in that order, so a tap shorter than a camera frame
                    // still leaves its dot.
                    if (pen.pendingDown) {
                        pen.pendingDown = false
                        if (pen.anchor != null) endStroke()
                        if (tracking) startStroke(session, frame.timestamp)
                    } else if (tracking && pen.anchor != null) {
                        extendStroke(frame.timestamp)
                    }
                    if (pen.pendingUp) {
                        pen.pendingUp = false
                        endStroke()
                    }
                },
                onTouchEvent = { event, _ ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            pen.x = event.x
                            pen.y = event.y
                            pen.pendingDown = true
                            pen.pendingUp = false
                        }

                        MotionEvent.ACTION_MOVE -> {
                            pen.x = event.x
                            pen.y = event.y
                        }

                        // A second finger ends the stroke where it is.
                        MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            pen.pendingUp = true
                    }
                    true
                },
            ) {
                // Reading the anchors' state here also recomposes every `AnchorNode` when one
                // changes, and a recomposed `AnchorNode` works out again whether it shows,
                // from the anchor itself: the screen cannot lag behind the count.
                val health = healthOf
                val strokes = drawing.strokes + listOfNotNull(drawing.active)
                strokes.groupBy { anchorOf[it.id] }.forEach { (anchor, hung) ->
                    if (anchor != null && health[anchor] != AnchorHealth.Stopped) {
                        key(anchor) {
                            AnchorNode(anchor = anchor) {
                                hung.forEach { stroke ->
                                    key(stroke.id) { StrokeMesh(stroke, paints[stroke.colorIndex]) }
                                }
                            }
                        }
                    }
                }
            }
            ARCameraInitScrim(initializing = !cameraReady, arCoreAvailability = arCoreAvailability)
        }
    }
}

/**
 * What the AR pen remembers between a touch event and the camera frame that acts on it. Not
 * Compose state: nothing reads it to draw.
 */
private class ArPen {
    var x = 0f
    var y = 0f
    var pendingDown = false
    var pendingUp = false

    /** Anchor of the stroke being drawn; `null` when the pen is up. */
    var anchor: Anchor? = null
    var unitsPerDp = 0f
    var steady: SteadyPen? = null
    var lastNanos = 0L
    var speed = 0f
}

private fun Pose.position() = Position(tx(), ty(), tz())

private fun TrackingState.toHealth() = when (this) {
    TrackingState.TRACKING -> AnchorHealth.Tracking
    TrackingState.PAUSED -> AnchorHealth.Paused
    TrackingState.STOPPED -> AnchorHealth.Stopped
}

private fun Pose.rotation() = Quaternion(qx(), qy(), qz(), qw())

private val IDENTITY_ROTATION = floatArrayOf(0f, 0f, 0f, 1f)

/** Screen distance between the two rays that measure one dp on the painting plane. */
private const val SCALE_PROBE_DP = 100f

private const val NANOS_PER_SECOND = 1e9f
