package io.github.sceneview.ar

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.google.android.filament.Engine
import com.google.ar.core.*
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.gesture.MoveGestureDetector
import io.github.sceneview.haptic.ARHapticEvent
import io.github.sceneview.gesture.RotateGestureDetector
import io.github.sceneview.gesture.ScaleGestureDetector
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.toQuaternion
import kotlin.math.roundToInt
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.utils.screenToRay
import io.github.sceneview.rememberOnGestureListener
import java.io.File

/** Supported automatic-placement alignment. Surface accepts tables, never ceilings. */
enum class PlacementSurface { SURFACE, WALL }

/** A real plane-associated placement. The anchor and plane retain their ARCore identity. */
class AutoPlacementResult internal constructor(
    anchor: Anchor,
    plane: Plane,
    pose: Pose,
) {
    var anchor: Anchor = anchor
        internal set
    var plane: Plane = plane
        internal set
    var pose: Pose = pose
        internal set
}

/** A validated, oriented surface candidate. Creating its anchor can still fail. */
class AutoPlacementCandidate internal constructor(val plane: Plane, val pose: Pose) {
    fun createAnchor(): AutoPlacementResult? = runCatching {
        if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null || !plane.isPoseInPolygon(pose)) {
            return@runCatching null
        }
        val anchor = plane.createAnchor(pose)
        if (anchor.trackingState != TrackingState.TRACKING) {
            anchor.detach()
            null
        } else AutoPlacementResult(anchor, plane, pose)
    }.getOrNull()
}

/**
 * Shared center-first policy for automatic placement and app adapters. Fallback centers
 * are ordered by screen-center proximity, and must lie inside the detected polygon.
 */
fun findAutoPlacementSurface(
    frame: Frame,
    planes: Collection<Plane>,
    width: Int,
    height: Int,
    surface: PlacementSurface = PlacementSurface.SURFACE,
): AutoPlacementCandidate? {
    if (width <= 0 || height <= 0 || frame.camera.trackingState != TrackingState.TRACKING) return null
    fun supported(plane: Plane) = plane.trackingState == TrackingState.TRACKING &&
        plane.subsumedBy == null && plane.type == when (surface) {
            PlacementSurface.SURFACE -> Plane.Type.HORIZONTAL_UPWARD_FACING
            PlacementSurface.WALL -> Plane.Type.VERTICAL
        }
    val hit = runCatching { frame.hitTest(width / 2f, height / 2f) }.getOrDefault(emptyList())
        .firstOrNull { hit ->
            val plane = hit.trackable as? Plane
            plane != null && plane.subsumedBy == null && UsableSurfacePolicy.accept(
                surface, plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING,
                plane.type == Plane.Type.VERTICAL, plane.trackingState == TrackingState.TRACKING,
                plane.isPoseInPolygon(hit.hitPose), hit.distance,
            )
        }
    if (hit != null) {
        val hitPlane = hit.trackable as Plane
        return AutoPlacementCandidate(hitPlane, orientedPlacementPose(hit.hitPose, hitPlane, frame.camera.pose))
    }
    val view = FloatArray(16).also { frame.camera.getViewMatrix(it, 0) }
    val projection = FloatArray(16).also { frame.camera.getProjectionMatrix(it, 0, 0.1f, 100f) }
    val viewProjection = ViewportProjection.multiply(projection, view)
    val camera = frame.camera.pose
    val candidates = planes.filter { supported(it) && it.isPoseInPolygon(it.centerPose) }.map { plane ->
        val pose = plane.centerPose
        FallbackCandidate(
            AutoPlacementCandidate(plane, orientedPlacementPose(pose, plane, camera)),
            ViewportProjection.distance(pose.tx(), pose.ty(), pose.tz(), camera.tx(), camera.ty(), camera.tz()),
            ViewportProjection.project(viewProjection, pose.tx(), pose.ty(), pose.tz()),
        )
    }
    return UsableSurfacePolicy.rankFallback(candidates).firstOrNull()
}

/** ARCore's +Y is the surface normal; -Z is gravity-up for upright wall content. */
private fun orientedPlacementPose(pose: Pose, plane: Plane, camera: Pose): Pose {
    if (plane.type != Plane.Type.VERTICAL) return pose
    val axis = plane.centerPose.getTransformedAxis(1, 1f)
    val wall = directWallPose(
        Position(pose.tx(), pose.ty(), pose.tz()), Float3(axis[0], axis[1], axis[2]),
        Float3(camera.tx() - pose.tx(), camera.ty() - pose.ty(), camera.tz() - pose.tz()),
    )
    // Keep the existing automatic anchor convention: +Y normal, -Z wall-up.
    val rotation = wall.rotation * Rotation(x = 90f).toQuaternion()
    return Pose(pose.translation, floatArrayOf(rotation.x, rotation.y, rotation.z, rotation.w))
}

@Composable
fun rememberAutoPlacementState(): AutoPlacementState = remember { AutoPlacementState() }

/**
 * Places once on the first usable detected plane after [assetReady]. No tap, reticle or
 * plane visualization. Load the asset before setting [assetReady]; keep the old asset
 * until a replacement succeeds, and gate asynchronous results using [AutoPlacementState.ticket].
 *
 * [state] exposes recovery, reset and explicit request actions. Camera/capability events
 * are forwarded without imposing app copy. Haptics are opt-in: call [ARHapticFeedback] with the
 * same [state]. Content is composed on the main thread.
 * [AutoPlacementModel] supplies grounded, surface-constrained manipulation.
 */
@Composable
fun AutoPlacementScene(
    assetReady: Boolean,
    modifier: Modifier = Modifier,
    state: AutoPlacementState = rememberAutoPlacementState(),
    surface: PlacementSurface = PlacementSurface.SURFACE,
    engine: Engine = rememberEngine(),
    modelLoader: ModelLoader = rememberModelLoader(engine),
    materialLoader: MaterialLoader = rememberMaterialLoader(engine),
    groundShadows: Boolean = true,
    playbackDataset: File? = null,
    onARCoreAvailability: ((availability: ARCoreAvailability?) -> Unit)? = null,
    onTrackingFailureChanged: ((TrackingFailureReason?) -> Unit)? = null,
    onSessionFailed: ((Exception) -> Unit)? = null,
    onPlaced: ((AutoPlacementResult) -> Unit)? = null,
    content: @Composable ARSceneScope.(AutoPlacementResult) -> Unit,
) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var placement by remember(state) { mutableStateOf<AutoPlacementResult?>(null) }
    var planes by remember { mutableStateOf<List<Plane>>(emptyList()) }
    val ready by rememberUpdatedState(assetReady)
    val placedCallback by rememberUpdatedState(onPlaced)
    LaunchedEffect(state, assetReady) {
        if (assetReady) state.requestPlacement() else state.withdrawRequest()
    }
    // Keyed on [state] alone, the disposal lambda below would capture the `placement` of the
    // composition that created the effect — normally null. Reading the latest value through
    // [rememberUpdatedState] is what makes `detach()` actually run when the host navigates away
    // after a placement, including when the content composes no AnchorNode of its own.
    val currentPlacement by rememberUpdatedState(placement)
    DisposableEffect(state) {
        onDispose {
            currentPlacement?.anchor?.detach()
            state.dismiss()
        }
    }
    ARSceneView(
        modifier = modifier.onSizeChanged { viewport = it },
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        playbackDataset = playbackDataset,
        planeRenderer = false,
        planeFindingMode = if (surface == PlacementSurface.SURFACE) {
            Config.PlaneFindingMode.HORIZONTAL
        } else {
            Config.PlaneFindingMode.VERTICAL
        },
        instantPlacementMode = Config.InstantPlacementMode.DISABLED,
        onGestureListener = rememberOnGestureListener(onSingleTapConfirmed = { _, node ->
            if (node == null) state.deselectPlacement() else state.selectPlacement()
        }),
        onARCoreAvailability = onARCoreAvailability,
        onTrackingFailureChanged = onTrackingFailureChanged,
        onSessionFailed = { state.cameraFailed(); onSessionFailed?.invoke(it) },
        onSessionUpdated = { session, frame ->
            if (!state.hasPlacement && placement != null) {
                placement?.anchor?.detach()
                placement = null
            }
            val tracked = session.getAllTrackables(Plane::class.java)
                .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
            if (tracked != planes) planes = tracked
            val candidate = if (ready && state.wantsSurface) {
                findAutoPlacementSurface(frame, tracked, viewport.width, viewport.height, surface)
            } else null
            val effect = state.onFrame(
                FrameInput(SystemClock.uptimeMillis(), frame.camera.trackingState == TrackingState.TRACKING,
                    candidate != null, placement?.anchor?.trackingState?.let { it == TrackingState.TRACKING }),
                commit = {
                    candidate?.createAnchor()?.let { placement = it; true } ?: false
                },
            )
            if (effect == FrameEffect.PLACE) placement?.let { placedCallback?.invoke(it) }
        },
    ) {
        placement?.let { result -> key(result) { content(result) } }
        if (groundShadows && (state.phase == PlacementPhase.PLACED || state.phase == PlacementPhase.ADJUSTING)) {
            planes.forEach { plane -> key(plane) { ShadowReceiverPlane(plane = plane) } }
        }
    }
}

/**
 * Grounded model content for [AutoPlacementScene], with a 0.3 m longest-axis preview by
 * default. Pass null [scaleToUnits] to retain authored units. Pinch is limited to 25–400%
 * of that base and snaps to 100 % within ±4 %, with a short elastic rebound. The model's complete bounding volume is selectable, including nested meshes.
 * [assetRotation] corrects authored axes before the grounded bounds are calculated.
 */
@Composable
fun ARSceneScope.AutoPlacementModel(
    placement: AutoPlacementResult,
    state: AutoPlacementState,
    modelInstance: ModelInstance,
    scaleToUnits: Float? = 0.3f,
    assetRotation: Rotation = Rotation(0f),
    onInvalidMove: (Boolean) -> Unit = {},
    onScaleChanged: (percent: Int, atBaseSize: Boolean, enteredBaseSize: Boolean) -> Unit = { _, _, _ -> },
) {
    AutomaticPlacementPivot(placement, state, onInvalidMove, onScaleChanged) {
        val model = remember(modelInstance, scaleToUnits, assetRotation) {
            io.github.sceneview.node.ModelNode(modelInstance, scaleToUnits = scaleToUnits).apply {
                quaternion = if (placement.plane.type == Plane.Type.VERTICAL) {
                    Rotation(x = -90f).toQuaternion() * assetRotation.toQuaternion()
                } else assetRotation.toQuaternion()
                // Transform all eight authored bounds corners after the axis correction.
                val corners = buildList<Position> {
                    for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) for (z in listOf(-1f, 1f)) {
                        val corner = center +
                            Position(x * halfExtent.x, y * halfExtent.y, z * halfExtent.z)
                        add(quaternion * (corner * scale))
                    }
                }
                position = automaticPlacementOffset(corners, placement.plane.type == Plane.Type.VERTICAL)
                isEditable = true
                isPositionEditable = false
                isRotationEditable = false
                isScaleEditable = false
            }
        }
        NodeLifecycle(model, null)
    }
}

/**
 * Surface-constrained procedural content using the same gestures and state as [AutoPlacementModel].
 * Author +Y up and +Z front. Put the bottom at y=0 and, for a wall, the back at z=0.
 * Size content before supplying it (demo preview: 0.3 m longest dimension). Each selectable
 * child must be editable with position/rotation/scale editing disabled so gestures reach the pivot.
 * [AutoPlacementState.moveBy], [AutoPlacementState.rotateBy] and [AutoPlacementState.scaleTo]
 * expose the same validated transforms to accessibility controls. Apply the content callback's
 * opacity to transparent materials: it fades from zero on placement and out on tracking loss
 * over 300 ms, without scaling or moving the contact pivot.
 */
@Composable
fun ARSceneScope.AutoPlacementNode(
    placement: AutoPlacementResult,
    state: AutoPlacementState,
    onInvalidMove: (Boolean) -> Unit = {},
    onScaleChanged: (Int, Boolean, Boolean) -> Unit = { _, _, _ -> },
    content: @Composable io.github.sceneview.NodeScope.(opacity: Float) -> Unit,
) {
    val opacity = remember(placement) { Animatable(0f) }
    val visible = state.phase == PlacementPhase.PLACED || state.phase == PlacementPhase.ADJUSTING
    LaunchedEffect(visible) { opacity.animateTo(if (visible) 1f else 0f, tween(300)) }
    AutomaticPlacementPivot(placement, state, onInvalidMove, onScaleChanged,
        visibleDuringFade = opacity.value > 0f) {
        Node(rotation = if (placement.plane.type == Plane.Type.VERTICAL) Rotation(x = -90f) else Rotation()) {
            content(opacity.value)
        }
    }
}

@Composable
private fun ARSceneScope.AutomaticPlacementPivot(
    placement: AutoPlacementResult,
    state: AutoPlacementState,
    onInvalidMove: (Boolean) -> Unit,
    onScaleChanged: (Int, Boolean, Boolean) -> Unit,
    visibleDuringFade: Boolean? = null,
    content: @Composable io.github.sceneview.NodeScope.() -> Unit,
) {
    val invalidMove by rememberUpdatedState(onInvalidMove)
    val scaleChanged by rememberUpdatedState(onScaleChanged)
    val root = remember(engine, placement) {
        var wasInvalid = false
        AutomaticAnchorNode(engine, placement, state) { invalid ->
            // One tick when the move turns invalid, not one per rejected drag frame.
            if (invalid && !wasInvalid) state.gestureHapticSink?.invoke(ARHapticEvent.InvalidMove)
            wasInvalid = invalid
            invalidMove(invalid)
        }
    }
    val visible = state.phase == PlacementPhase.PLACED || state.phase == PlacementPhase.ADJUSTING
    SideEffect {
        root.visibleTrackingStates = if (visibleDuringFade != null) TrackingState.values().toSet()
            else setOf(TrackingState.TRACKING)
        root.isVisible = visibleDuringFade ?: visible
    }
    NodeLifecycle(root) {
        val pivot = remember(engine, placement) { object : io.github.sceneview.node.Node(engine) {
            /** The logical scale; [scale] only differs from it during the 100 % rebound. */
            var logicalScale = 1f
            private var rebound: Boolean? = null // fromAbove, while a rebound is pending or running
            private var reboundStartNanos = 0L

            fun startRebound(fromAbove: Boolean) {
                rebound = fromAbove
                reboundStartNanos = 0L
            }

            fun cancelRebound() {
                rebound = null
            }

            override val isFrameActive: Boolean get() = rebound != null || super.isFrameActive

            override fun onFrame(frameTimeNanos: Long) {
                super.onFrame(frameTimeNanos)
                val fromAbove = rebound ?: return
                if (reboundStartNanos == 0L) reboundStartNanos = frameTimeNanos
                val elapsedMs = (frameTimeNanos - reboundStartNanos) / 1_000_000f
                if (elapsedMs >= ScaleSnap.REBOUND_MS) {
                    rebound = null
                    scale = Scale(logicalScale)
                } else {
                    scale = Scale(logicalScale * ScaleSnap.rebound(elapsedMs, fromAbove))
                }
            }

            override fun onRotateEnd(detector: RotateGestureDetector, e: MotionEvent) {
                super.onRotateEnd(detector, e)
                state.endAdjustment()
            }
            override fun onScaleEnd(detector: ScaleGestureDetector, e: MotionEvent) {
                super.onScaleEnd(detector, e)
                state.endAdjustment()
            }
        }.apply {
            isEditable = true
            isPositionEditable = false
            editableScaleRange = ScaleSnap.MIN..ScaleSnap.MAX
            onRotateBegin = { _, _ -> state.beginAdjustment() }
            var rawScale = 1f
            onScaleBegin = { _, _ -> rawScale = logicalScale; state.beginAdjustment() }
            onRotate = { _, _, _ -> state.isAdjusting }
            onScale = { _, _, factor ->
                if (state.isAdjusting) {
                    val previous = logicalScale
                    rawScale = (rawScale * (1f + (factor - 1f) * scaleGestureSensitivity))
                        .coerceIn(ScaleSnap.MIN, ScaleSnap.MAX)
                    val step = ScaleSnap.step(previous, rawScale)
                    logicalScale = step.displayed
                    if (step.enteredSnap) {
                        startRebound(fromAbove = previous > 1f)
                        state.gestureHapticSink?.invoke(ARHapticEvent.ScaleSnapped)
                    } else if (!step.snapped) {
                        cancelRebound()
                        scale = Scale(step.displayed)
                    }
                    if (step.enteredLimit) state.gestureHapticSink?.invoke(ARHapticEvent.LimitReached)
                    state.scaleFactor = step.displayed
                    scaleChanged((step.displayed * 100).roundToInt(), step.snapped, step.enteredSnap)
                }
                false
            }
        } }
        DisposableEffect(pivot, state) {
            state.moveAction = { x, y ->
                // Every refused button press is felt, not only the first one (throttled per event).
                root.moveBy(x, y).also { if (!it) state.gestureHapticSink?.invoke(ARHapticEvent.InvalidMove) }
            }
            state.rotateAction = { pivot.quaternion *= Rotation(y = it).toQuaternion() }
            state.scaleAction = {
                val previous = pivot.logicalScale
                pivot.cancelRebound()
                pivot.logicalScale = it
                pivot.scale = Scale(it)
                state.scaleFactor = it
                val crossed = (previous < 1f && it >= 1f) || (previous > 1f && it <= 1f)
                if (crossed) state.gestureHapticSink?.invoke(ARHapticEvent.ScaleSnapped)
                if (ScaleSnap.isAtLimit(it) && !ScaleSnap.isAtLimit(previous)) {
                    state.gestureHapticSink?.invoke(ARHapticEvent.LimitReached)
                }
                scaleChanged((it * 100).roundToInt(), it == 1f, crossed)
            }
            onDispose {
                state.moveAction = null
                state.rotateAction = null
                state.scaleAction = null
            }
        }
        NodeLifecycle(pivot, content)
    }
}

/** Bounds are already rotated into the anchor frame before grounding. */
internal fun automaticPlacementOffset(corners: List<Position>, wall: Boolean): Position = Position(
    -(corners.minOf { it.x } + corners.maxOf { it.x }) / 2f,
    -corners.minOf { it.y },
    if (wall) -corners.maxOf { it.z } else -(corners.minOf { it.z } + corners.maxOf { it.z }) / 2f,
)

private fun visiblePlacementPoint(frame: Frame, pose: Pose): Boolean {
    if (frame.camera.trackingState != TrackingState.TRACKING) return false
    val camera = frame.camera.pose
    val distance = ViewportProjection.distance(pose.tx(), pose.ty(), pose.tz(), camera.tx(), camera.ty(), camera.tz())
    if (distance !in UsableSurfacePolicy.MIN_DISTANCE_M..UsableSurfacePolicy.MAX_DISTANCE_M) return false
    val view = FloatArray(16).also { frame.camera.getViewMatrix(it, 0) }
    val projection = FloatArray(16).also { frame.camera.getProjectionMatrix(it, 0, 0.1f, 100f) }
    return ViewportProjection.project(ViewportProjection.multiply(projection, view),
        pose.tx(), pose.ty(), pose.tz())?.isInsideViewport == true
}

/** Keeps the logical anchor alive while dragging; only a valid finished move replaces it. */
private class AutomaticAnchorNode(
    engine: Engine,
    private val placement: AutoPlacementResult,
    private val state: AutoPlacementState,
    private val invalidMove: (Boolean) -> Unit,
) : AnchorNode(engine, placement.anchor) {
    private var offset: FloatArray? = null
    private var pending: AutoPlacementCandidate? = null
    private var interruptedMove = false
    init { isEditable = true }

    /** Accessibility moves obey the same polygon/range/anchor checks as a drag. */
    fun moveBy(x: Float, y: Float): Boolean {
        val frame = frame ?: return false
        if (!isTracking(frame)) return false
        val tangent = pose.rotateVector(floatArrayOf(x, 0f,
            if (placement.plane.type == Plane.Type.VERTICAL) -y else y))
        val target = translated(pose, tangent)
        if (!placement.plane.isPoseInPolygon(target) || !visiblePlacementPoint(frame, target)) {
            invalidMove(true)
            return false
        }
        pending = AutoPlacementCandidate(placement.plane, target)
        val committed = commitMove()
        if (!committed) pending = null
        invalidMove(!committed)
        return committed
    }

    override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent): Boolean {
        val grab = grabOffset(e) ?: return false
        if (!state.beginAdjustment()) return false
        offset = floatArrayOf(grab.x, grab.y, grab.z)
        pending = null
        interruptedMove = false
        updateAnchorPose = false
        return true
    }

    override fun onMove(detector: MoveGestureDetector, e: MotionEvent): Boolean {
        if (!state.isAdjusting) return false
        val frame = frame ?: return false
        val hit = frame.hitTest(e).firstOrNull {
            val plane = it.trackable as? Plane
            plane != null && plane.type == placement.plane.type && plane.subsumedBy == null &&
                plane.trackingState == TrackingState.TRACKING && plane.isPoseInPolygon(it.hitPose) &&
                it.distance in UsableSurfacePolicy.MIN_DISTANCE_M..UsableSurfacePolicy.MAX_DISTANCE_M
        }
        if (hit == null) { invalidMove(true); return false }
        val delta = offset ?: floatArrayOf(
            pose.tx() - hit.hitPose.tx(),
            pose.ty() - hit.hitPose.ty(),
            pose.tz() - hit.hitPose.tz(),
        ).also { offset = it }
        val plane = hit.trackable as Plane
        // Project grab offset into the new plane to prevent movement out of its surface.
        val normal = plane.centerPose.getTransformedAxis(1, 1f)
        val tangent = wallTangentOffset(
            Position(delta[0], delta[1], delta[2]),
            Float3(normal[0], normal[1], normal[2]),
        )
        val target = orientedPlacementPose(
            translated(hit.hitPose, floatArrayOf(tangent.x, tangent.y, tangent.z)),
            plane, frame.camera.pose,
        )
        if (!plane.isPoseInPolygon(target) || !visiblePlacementPoint(frame, target)) { invalidMove(true); return false }
        pending = AutoPlacementCandidate(plane, target)
        pose = target
        invalidMove(false)
        return true
    }

    override fun onMoveEnd(detector: MoveGestureDetector, e: MotionEvent) {
        if (state.isAdjusting) commitMove()
        if (!interruptedMove) {
            updateAnchorPose = true
            pending = null
        }
        offset = null
        invalidMove(false)
        state.endAdjustment()
    }

    /** Where the finger grabbed the node against its own contact plane, null when unusable. */
    private fun grabOffset(e: MotionEvent): Position? {
        val frame = frame ?: return null
        if (!isTracking(frame)) return null
        val ray = collisionSystem?.view?.screenToRay(e.x, e.y) ?: return null
        val axis = pose.getTransformedAxis(1, 1f)
        return wallGrabOffset(
            Position(pose.tx(), pose.ty(), pose.tz()),
            Float3(axis[0], axis[1], axis[2]), ray.origin, ray.direction,
        )
    }

    private fun isTracking(frame: Frame) =
        frame.camera.trackingState == TrackingState.TRACKING &&
            anchor.trackingState == TrackingState.TRACKING

    /** [source] shifted by [delta] in world space, keeping this node's authored rotation. */
    private fun translated(source: Pose, delta: FloatArray) = Pose(
        floatArrayOf(source.tx() + delta[0], source.ty() + delta[1], source.tz() + delta[2]),
        pose.rotationQuaternion,
    )

    private fun commitMove(): Boolean {
        val candidate = pending ?: return false
        val frame = frame ?: return false
        if (anchor.trackingState != TrackingState.TRACKING ||
            !visiblePlacementPoint(frame, candidate.pose)
        ) return false
        val next = candidate.createAnchor() ?: return false
        anchor = next.anchor
        placement.anchor = next.anchor
        placement.plane = next.plane
        placement.pose = next.pose
        pending = null
        return true
    }

    override fun update(session: Session, frame: Frame) {
        val tracking = frame.camera.trackingState == TrackingState.TRACKING &&
            anchor.trackingState == TrackingState.TRACKING
        if (!tracking) {
            if (!updateAnchorPose) {
                // Freeze the last valid drag pose without replacing the logical anchor.
                interruptedMove = pending != null
                offset = null
                state.endAdjustment()
                if (!interruptedMove) updateAnchorPose = true
            }
            // A still-tracking anchor must not move the content while the camera is limited.
            // Keep the frozen pose available for the opacity fade, including anchor loss.
            val followAnchor = updateAnchorPose
            updateAnchorPose = false
            super.update(session, frame)
            updateAnchorPose = followAnchor
            return
        }
        if (interruptedMove && commitMove()) {
            interruptedMove = false
            updateAnchorPose = true
        }
        super.update(session, frame)
    }
}
