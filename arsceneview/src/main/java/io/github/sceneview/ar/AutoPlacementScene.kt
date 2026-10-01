package io.github.sceneview.ar

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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

private const val NO_PLANE_MESSAGE = "This wall was found through the depth API or inferred from " +
    "the floor seam: ARCore has no plane for it yet. Read planeOrNull instead."

/**
 * A placement and the ARCore identity of what it stands on. For a floor, a table or a wall
 * ARCore has grown a plane on, that is the [plane]. A plain painted wall often has no plane at
 * all (#4070): the placement then rides a depth hit or the tracked floor it was inferred from,
 * and [planeOrNull] is `null`.
 */
class AutoPlacementResult internal constructor(
    anchor: Anchor,
    trackable: Trackable,
    pose: Pose,
    kind: WallHitKind?,
) {
    internal constructor(anchor: Anchor, plane: Plane, pose: Pose) :
        this(anchor, plane, pose, plane.wallKindOrNull())

    var anchor: Anchor = anchor
        internal set
    var pose: Pose = pose
        internal set

    /** What the anchor is attached to: a [Plane], a [DepthPoint], or the floor under a seam wall. */
    internal var trackable: Trackable = trackable

    /** How the wall holds, `null` for a floor or table placement. */
    internal var kind: WallHitKind? = kind

    internal val isWall: Boolean get() = kind != null

    /**
     * The ARCore plane the object stands on.
     *
     * @throws IllegalStateException for a wall found through the depth API or inferred from the
     *   floor seam, which has no plane yet. Read [planeOrNull] in a wall flow.
     */
    val plane: Plane get() = planeOrNull ?: error(NO_PLANE_MESSAGE)

    /**
     * The ARCore plane the object stands on, or `null` for a wall found through the depth API
     * or inferred from the floor↔wall seam (#4070).
     */
    val planeOrNull: Plane? get() = planeOf(trackable, kind)
}

/** A validated, oriented surface candidate. Creating its anchor can still fail. */
class AutoPlacementCandidate internal constructor(
    internal val trackable: Trackable,
    val pose: Pose,
    internal val kind: WallHitKind?,
) {
    internal constructor(plane: Plane, pose: Pose) : this(plane, pose, plane.wallKindOrNull())

    /**
     * The ARCore plane the candidate lies on.
     *
     * @throws IllegalStateException for a wall found through the depth API or inferred from the
     *   floor seam, which has no plane yet. Read [planeOrNull] in a wall flow.
     */
    val plane: Plane get() = planeOrNull ?: error(NO_PLANE_MESSAGE)

    /** The ARCore plane the candidate lies on, `null` for a wall without one (#4070). */
    val planeOrNull: Plane? get() = planeOf(trackable, kind)

    fun createAnchor(): AutoPlacementResult? = runCatching {
        if (!accepts(pose)) return@runCatching null
        val anchor = trackable.createAnchor(pose)
        if (anchor.trackingState != TrackingState.TRACKING) {
            anchor.detach()
            null
        } else AutoPlacementResult(anchor, trackable, pose, kind)
    }.getOrNull()

    /**
     * Whether [target] is still a valid spot on this candidate's surface: inside the polygon for
     * a floor, up to [UsableSurfacePolicy.WALL_POLYGON_TOLERANCE_M] outside it for a wall plane,
     * anywhere on an inferred wall (which has no polygon).
     */
    internal fun accepts(target: Pose): Boolean {
        if (trackable.trackingState != TrackingState.TRACKING) return false
        val plane = trackable as? Plane
        return when (kind) {
            null -> plane != null && plane.subsumedBy == null && plane.isPoseInPolygon(target)
            WallHitKind.PLANE, WallHitKind.EXTENDED_PLANE -> plane != null && plane.subsumedBy == null &&
                (plane.isPoseInPolygon(target) ||
                    plane.outsidePolygonDistance(target) <= UsableSurfacePolicy.WALL_POLYGON_TOLERANCE_M)
            WallHitKind.DEPTH_POINT, WallHitKind.FLOOR_SEAM -> true
        }
    }
}

private fun Plane.wallKindOrNull(): WallHitKind? = if (type == Plane.Type.VERTICAL) WallHitKind.PLANE else null

private fun planeOf(trackable: Trackable, kind: WallHitKind?): Plane? = when (kind) {
    null, WallHitKind.PLANE, WallHitKind.EXTENDED_PLANE -> trackable as? Plane
    WallHitKind.DEPTH_POINT, WallHitKind.FLOOR_SEAM -> null
}

/**
 * Shared center-first policy for automatic placement and app adapters. Fallback centers
 * are ordered by screen-center proximity, and must lie inside the detected polygon.
 *
 * A [PlacementSurface.WALL] search also takes what ARKit's `.existingPlaneInfinite` and
 * `.estimatedPlane` would (#4070): a wall plane hit up to a metre outside its polygon, a depth
 * hit on a vertical surface (with the depth API on), and last a wall inferred from where the
 * tracked floor ends under the centre of the screen ([seamWallCandidate]). The last two must
 * show upright surface [WALL_MIN_RISE_M] above the floor ([wallRisesAboveFurniture]), or the
 * front of a bed or a cabinet passes for a wall.
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
    val camera = frame.camera.pose
    val hits = runCatching { frame.hitTest(width / 2f, height / 2f) }.getOrDefault(emptyList())
    val view = FloatArray(16).also { frame.camera.getViewMatrix(it, 0) }
    val projection = FloatArray(16).also { frame.camera.getProjectionMatrix(it, 0, 0.1f, 100f) }
    val viewProjection = ViewportProjection.multiply(projection, view)
    // Without a tracked floor, the wall must reach the camera's own height: a phone held standing
    // or seated is above a bed or a cabinet.
    val floorY = trackedFloorY(planes) ?: (camera.ty() - WALL_MIN_RISE_M)
    val risesAboveFurniture = { point: Position, normal: Float3 ->
        val probe = wallRiseProbe(point, floorY)?.let { probeHit(frame, viewProjection, it, width, height) }
        wallRisesAboveFurniture(point, normal, floorY, probe)
    }
    when (surface) {
        PlacementSurface.WALL -> wallRayCandidate(hits, camera, risesAboveFurniture)?.let { return it }
        PlacementSurface.SURFACE -> {
            val hit = hits.firstOrNull { hit ->
                val plane = hit.trackable as? Plane
                plane != null && plane.subsumedBy == null && UsableSurfacePolicy.accept(
                    surface, plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING,
                    plane.type == Plane.Type.VERTICAL, plane.trackingState == TrackingState.TRACKING,
                    plane.isPoseInPolygon(hit.hitPose), hit.distance,
                )
            }
            if (hit != null) return AutoPlacementCandidate(hit.trackable as Plane, hit.hitPose)
        }
    }
    val candidates = planes.filter { supported(it) && it.isPoseInPolygon(it.centerPose) }.map { plane ->
        val pose = plane.centerPose
        FallbackCandidate(
            AutoPlacementCandidate(plane, orientedPlacementPose(pose, planeWallNormal(plane), camera)),
            ViewportProjection.distance(pose.tx(), pose.ty(), pose.tz(), camera.tx(), camera.ty(), camera.tz()),
            ViewportProjection.project(viewProjection, pose.tx(), pose.ty(), pose.tz()),
        )
    }
    return UsableSurfacePolicy.rankFallback(candidates).firstOrNull()
        ?: if (surface == PlacementSurface.WALL) seamCandidate(hits, planes, camera, risesAboveFurniture) else null
}

/** Height of the lowest tracked upward-facing plane, the floor; `null` before one is tracked. */
private fun trackedFloorY(planes: Collection<Plane>): Float? = planes
    .filter {
        it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING &&
            it.subsumedBy == null
    }
    .minOfOrNull { it.centerPose.ty() }

/**
 * Where the camera ray toward [target] first meets tracked geometry (a depth point, a plane, a
 * feature point), or `null` when [target] is off screen or the ray meets nothing.
 */
private fun probeHit(frame: Frame, viewProjection: FloatArray, target: Position, width: Int, height: Int): Position? {
    val ndc = ViewportProjection.project(viewProjection, target.x, target.y, target.z)
        ?.takeIf { it.isInsideViewport } ?: return null
    val x = (ndc.x + 1f) / 2f * width
    val y = (1f - ndc.y) / 2f * height
    val hit = runCatching { frame.hitTest(x, y) }.getOrNull()?.firstOrNull() ?: return null
    return Position(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz())
}

/**
 * The most trusted wall among the centre ray's hits ([WallHitKind] order), nearest first. A
 * depth hit is a wall only if [risesAboveFurniture]: a vertical plane is ARCore's own call.
 */
private fun wallRayCandidate(
    hits: List<HitResult>,
    camera: Pose,
    risesAboveFurniture: (Position, Float3) -> Boolean,
): AutoPlacementCandidate? {
    var best: HitResult? = null
    var bestKind: WallHitKind? = null
    for (hit in hits) {
        val kind = wallHitKind(hit) ?: continue
        if (bestKind == null || kind < bestKind) {
            best = hit
            bestKind = kind
        }
    }
    val hit = best ?: return null
    val normal = wallNormalOf(hit)
    if (bestKind == WallHitKind.DEPTH_POINT && !risesAboveFurniture(
            Position(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz()), Float3(normal[0], normal[1], normal[2]),
        )
    ) return null
    val pose = orientedPlacementPose(hit.hitPose, normal, camera)
    return AutoPlacementCandidate(hit.trackable, pose, bestKind)
}

/** How far past the edge tolerance the "floor ends beyond" probe looks, metres. */
private const val SEAM_PROBE_MARGIN_M = 0.1f

/**
 * A wall inferred from the floor seam: the centre ray meets the tracked floor near where its
 * polygon ends ([seamWallCandidate]), and the wall rises above it ([risesAboveFurniture]): the
 * floor also ends at the foot of a bed. The anchor rides the floor plane, which is tracked.
 */
private fun seamCandidate(
    hits: List<HitResult>,
    planes: Collection<Plane>,
    camera: Pose,
    risesAboveFurniture: (Position, Float3) -> Boolean,
): AutoPlacementCandidate? {
    val floors = planes.filter {
        it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING &&
            it.subsumedBy == null
    }
    val floorY = floors.minOfOrNull { it.centerPose.ty() } ?: return null
    val hit = hits.firstOrNull { hit ->
        val plane = hit.trackable as? Plane
        plane != null && plane in floors && plane.centerPose.ty() - floorY <= SEAM_FLOOR_TOLERANCE_M &&
            plane.isPoseInPolygon(hit.hitPose) &&
            hit.distance in UsableSurfacePolicy.MIN_DISTANCE_M..UsableSurfacePolicy.MAX_DISTANCE_M
    } ?: return null
    val floor = hit.trackable as Plane
    val local = floor.localXZ(hit.hitPose)
    val edge = nearestPolygonEdge(local[0], local[1], floor.polygonArray()) ?: return null
    val edgeWorld = floor.centerPose.rotateVector(floatArrayOf(edge.dx, 0f, edge.dz))
    // Probe past the aimed point, away from the camera: the floor must end there.
    val awayX = hit.hitPose.tx() - camera.tx()
    val awayZ = hit.hitPose.tz() - camera.tz()
    val away = kotlin.math.sqrt(awayX * awayX + awayZ * awayZ)
    val probeStep = (SEAM_EDGE_TOLERANCE_M + SEAM_PROBE_MARGIN_M) / away.coerceAtLeast(1e-3f)
    val probe = Pose.makeTranslation(
        hit.hitPose.tx() + awayX * probeStep, hit.hitPose.ty(), hit.hitPose.tz() + awayZ * probeStep,
    )
    val floorEndsBeyond = floor.outsidePolygonDistance(probe) > 0f
    val depthNormal = hits.firstOrNull { it.trackable is DepthPoint }
        ?.hitPose?.getTransformedAxis(1, 1f)?.let { Float3(it[0], it[1], it[2]) }
    val wall = seamWallCandidate(
        floorY = hit.hitPose.ty(),
        rayHitOnFloor = Position(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz()),
        cameraPosition = Position(camera.tx(), camera.ty(), camera.tz()),
        floorEdgeDistance = edge.distance,
        edgeDirection = Float3(edgeWorld[0], edgeWorld[1], edgeWorld[2]),
        depthNormal = depthNormal,
        floorEndsBeyond = floorEndsBeyond,
    )?.takeIf { risesAboveFurniture(it.point, it.normal) } ?: return null
    val point = Pose.makeTranslation(wall.point.x, wall.point.y, wall.point.z)
    val normal = floatArrayOf(wall.normal.x, wall.normal.y, wall.normal.z)
    return AutoPlacementCandidate(floor, orientedPlacementPose(point, normal, camera), WallHitKind.FLOOR_SEAM)
}

/**
 * The plane finding [AutoPlacementScene] asks ARCore for. A surface flow looks only for the
 * planes it can place on. A wall flow needs vertical planes (#4070) **and** the floor: a plain
 * wall often never grows a plane, and the floor's edge is where it is inferred from
 * ([seamWallCandidate]). Pure, so `AutoPlacementPlaneFindingTest` pins the contract on the JVM.
 */
internal fun autoPlacementPlaneFindingMode(surface: PlacementSurface): Config.PlaneFindingMode =
    when (surface) {
        PlacementSurface.SURFACE -> Config.PlaneFindingMode.HORIZONTAL
        PlacementSurface.WALL -> Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
    }

/**
 * The depth mode [AutoPlacementScene] asks ARCore for. A wall flow turns the depth API on: a
 * depth hit is the only hit ARCore returns on a plain painted wall (#4070). The session
 * downgrades it to `DISABLED` on a device without depth support. A surface flow keeps
 * [ARSceneView]'s default (`DISABLED`), and so does every other scene.
 */
internal fun autoPlacementDepthMode(surface: PlacementSurface): Config.DepthMode =
    when (surface) {
        PlacementSurface.SURFACE -> Config.DepthMode.DISABLED
        PlacementSurface.WALL -> Config.DepthMode.AUTOMATIC
    }

/** A vertical plane's normal (centre-pose +Y), `null` for a floor or table. */
private fun planeWallNormal(plane: Plane): FloatArray? =
    if (plane.type == Plane.Type.VERTICAL) plane.centerPose.getTransformedAxis(1, 1f) else null

/**
 * ARCore's +Y is the surface normal; -Z is gravity-up for upright wall content. A `null`
 * [wallNormal] is a floor or table: the pose is kept as is.
 */
private fun orientedPlacementPose(pose: Pose, wallNormal: FloatArray?, camera: Pose): Pose {
    val axis = wallNormal ?: return pose
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
 *
 * @param coaching show the animated [ARCoachingOverlay] (phone sweep while scanning, a
 *   "surface found" beat on placement, pause/look-back glyphs when tracking degrades). On by
 *   default. Pass `false` to draw your own from [rememberArGuidanceState], and hide your own
 *   status chrome while [ArGuidanceState.isCoaching] is true either way.
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
    coaching: Boolean = true,
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
    // The coaching glyph is centred, exactly where ARSceneView draws its "Couldn't start AR"
    // card — and it kept sweeping over that card's copy and its Try again button (#3986).
    // DESIGN.md: the glyph is silent whenever a card explains the state.
    var availability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    DisposableEffect(state) {
        onDispose {
            currentPlacement?.anchor?.detach()
            state.dismiss()
        }
    }
    Box(modifier = modifier) {
        ARSceneView(
            modifier = Modifier.fillMaxSize().onSizeChanged { viewport = it },
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            playbackDataset = playbackDataset,
            planeRenderer = false,
            planeFindingMode = autoPlacementPlaneFindingMode(surface),
            depthMode = autoPlacementDepthMode(surface),
            instantPlacementMode = Config.InstantPlacementMode.DISABLED,
            onGestureListener = rememberOnGestureListener(onSingleTapConfirmed = { _, node ->
                if (node == null) state.deselectPlacement() else state.selectPlacement()
            }),
            onARCoreAvailability = {
                availability = it
                onARCoreAvailability?.invoke(it)
            },
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
        if (coaching) {
            val guidance = rememberArGuidanceState(state, surface)
            ARCoachingOverlay(
                cue = if (availability == null) guidance.cue else ArGuidanceCue.NONE,
                surface = guidance.surface,
            )
        }
    }
}

/**
 * Binary-compatibility shim for the pre-`coaching` descriptor of [AutoPlacementScene]. The
 * Compose compiler puts every parameter in the JVM signature, so adding `coaching` retyped
 * the method (CONTRIBUTING.md — a retyped public symbol is a breaking change). Code compiled
 * against the old signature gets the coaching overlay, the new default.
 */
@Deprecated(
    "Binary-compatibility overload. Use the AutoPlacementScene overload that takes `coaching`.",
    level = DeprecationLevel.HIDDEN,
)
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
) = AutoPlacementScene(
    assetReady = assetReady,
    modifier = modifier,
    state = state,
    surface = surface,
    engine = engine,
    modelLoader = modelLoader,
    materialLoader = materialLoader,
    groundShadows = groundShadows,
    coaching = true,
    playbackDataset = playbackDataset,
    onARCoreAvailability = onARCoreAvailability,
    onTrackingFailureChanged = onTrackingFailureChanged,
    onSessionFailed = onSessionFailed,
    onPlaced = onPlaced,
    content = content,
)

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
    val entrance = rememberPlacementEntrance(placement, state)
    AutomaticPlacementPivot(placement, state, onInvalidMove, onScaleChanged,
        visibleDuringFade = entrance.value > 0f) {
        val model = remember(modelInstance, scaleToUnits, assetRotation) {
            io.github.sceneview.node.ModelNode(modelInstance, scaleToUnits = scaleToUnits).apply {
                quaternion = if (placement.isWall) {
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
                position = automaticPlacementOffset(corners, placement.isWall)
                isEditable = true
                isPositionEditable = false
                isRotationEditable = false
                isScaleEditable = false
            }
        }
        // Opaque glTF materials cannot fade, so the model grows into place and shrinks away
        // on tracking loss instead. Scaling about the pivot keeps the contact point fixed.
        Node(scale = Scale(PlacementEntrance.scaleFraction(entrance.value))) {
            NodeLifecycle(model, null)
        }
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
 * over 300 ms. The content also grows into place from 55 % (the same entrance as
 * [AutoPlacementModel]), scaled about the contact pivot so it never leaves the surface.
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
    LaunchedEffect(visible) { opacity.animateTo(if (visible) 1f else 0f, tween(PlacementEntrance.EXIT_MS)) }
    val entrance = rememberPlacementEntrance(placement, state)
    AutomaticPlacementPivot(placement, state, onInvalidMove, onScaleChanged,
        visibleDuringFade = opacity.value > 0f || entrance.value > 0f) {
        Node(
            rotation = if (placement.isWall) Rotation(x = -90f) else Rotation(),
            scale = Scale(PlacementEntrance.scaleFraction(entrance.value)),
        ) {
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

/**
 * Entrance progress (0 = hidden, 1 = settled) of a placement: runs up over
 * [PlacementEntrance.DURATION_MS] when the object becomes visible (placed, or re-tracked)
 * and back down over [PlacementEntrance.EXIT_MS] when tracking is lost.
 */
@Composable
private fun rememberPlacementEntrance(
    placement: AutoPlacementResult,
    state: AutoPlacementState,
): Animatable<Float, *> {
    val progress = remember(placement) { Animatable(0f) }
    val visible = state.phase == PlacementPhase.PLACED || state.phase == PlacementPhase.ADJUSTING
    LaunchedEffect(progress, visible) {
        progress.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(
                if (visible) PlacementEntrance.DURATION_MS else PlacementEntrance.EXIT_MS,
                easing = LinearEasing,
            ),
        )
    }
    return progress
}

/**
 * The "grows into place" entrance of a freshly placed object (`motion-placement-entrance`
 * in `DESIGN.md`). A model that appears at full size on the frame its anchor lands reads as
 * a glitch; Scene Viewer, IKEA Place and Reality Composer all ease it up from a smaller scale
 * over about a quarter of a second — short enough to feel instant, long enough to register.
 */
internal object PlacementEntrance {

    /** Duration of the scale-in, milliseconds. */
    const val DURATION_MS = 260

    /** Duration of the shrink/fade-out on tracking loss (`motion-fade`). */
    const val EXIT_MS = 300

    /** Scale fraction the object starts at — deliberately not 0, which reads as a flicker. */
    const val START_FRACTION = 0.55f

    /**
     * Eased scale fraction at animation [progress] (`0..1`). Cubic ease-out: fast out of the
     * gate, settling without overshoot — an overshoot on a *physical-scale* object reads as
     * the object being the wrong size, not as bounce.
     */
    fun scaleFraction(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        return START_FRACTION + (1f - START_FRACTION) * eased
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
        val tangent = pose.rotateVector(floatArrayOf(x, 0f, if (placement.isWall) -y else y))
        val target = translated(pose, tangent)
        val candidate = AutoPlacementCandidate(placement.trackable, target, placement.kind)
        if (!candidate.accepts(target) || !visiblePlacementPoint(frame, target)) {
            invalidMove(true)
            return false
        }
        pending = candidate
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
        val hits = frame.hitTest(e)
        val candidate = if (placement.isWall) {
            // Nearest wall under the finger — plane, extended plane or depth hit — and, on a plain
            // wall with none of those, the wall the object already stands on (#4070).
            hits.firstNotNullOfOrNull { hit -> wallHitKind(hit)?.let { hit to it } }
                ?.let { (hit, kind) -> hitCandidate(frame, hit, kind, wallNormalOf(hit)) }
                ?: slideAlongWall(e)
        } else {
            hits.firstOrNull {
                val plane = it.trackable as? Plane
                plane != null && plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING && plane.subsumedBy == null &&
                    plane.trackingState == TrackingState.TRACKING && plane.isPoseInPolygon(it.hitPose) &&
                    it.distance in UsableSurfacePolicy.MIN_DISTANCE_M..UsableSurfacePolicy.MAX_DISTANCE_M
            }?.let { hitCandidate(frame, it, null, (it.trackable as Plane).centerPose.getTransformedAxis(1, 1f)) }
        }
        if (candidate == null || !candidate.accepts(candidate.pose) || !visiblePlacementPoint(frame, candidate.pose)) {
            invalidMove(true)
            return false
        }
        pending = candidate
        pose = candidate.pose
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

    /**
     * The drag target on [hit]'s surface: the grab offset projected into the surface (so the
     * object never leaves it), oriented against the wall when [kind] is one.
     */
    private fun hitCandidate(
        frame: Frame,
        hit: HitResult,
        kind: WallHitKind?,
        normal: FloatArray,
    ): AutoPlacementCandidate {
        val delta = offset ?: floatArrayOf(
            pose.tx() - hit.hitPose.tx(),
            pose.ty() - hit.hitPose.ty(),
            pose.tz() - hit.hitPose.tz(),
        ).also { offset = it }
        val tangent = wallTangentOffset(
            Position(delta[0], delta[1], delta[2]),
            Float3(normal[0], normal[1], normal[2]),
        )
        val target = orientedPlacementPose(
            translated(hit.hitPose, floatArrayOf(tangent.x, tangent.y, tangent.z)),
            if (kind != null) normal else null, frame.camera.pose,
        )
        return AutoPlacementCandidate(hit.trackable, target, kind)
    }

    /**
     * The finger's ray against the wall the object stands on, treated as infinite — the only
     * drag target on a plain wall that yields no plane and no depth hit under the finger.
     */
    private fun slideAlongWall(e: MotionEvent): AutoPlacementCandidate? {
        val ray = collisionSystem?.view?.screenToRay(e.x, e.y) ?: return null
        val axis = pose.getTransformedAxis(1, 1f)
        val normal = Float3(axis[0], axis[1], axis[2])
        val contact = Position(pose.tx(), pose.ty(), pose.tz())
        // wallGrabOffset returns contact - rayHit: the ray's hit on the wall is contact - that.
        val toRay = wallGrabOffset(contact, normal, ray.origin, ray.direction) ?: return null
        // First drag frame: keep the finger's grab point under the finger, no jump.
        val grab = offset?.let { Position(it[0], it[1], it[2]) }
            ?: toRay.also { offset = floatArrayOf(it.x, it.y, it.z) }
        val tangent = wallTangentOffset(grab, normal)
        val target = contact - toRay + tangent
        return AutoPlacementCandidate(
            placement.trackable,
            Pose(floatArrayOf(target.x, target.y, target.z), pose.rotationQuaternion),
            placement.kind,
        )
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
        placement.trackable = next.trackable
        placement.kind = next.kind
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
