package io.github.sceneview.ar

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.android.filament.Engine
import com.google.ar.core.*
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.gesture.MoveGestureDetector
import io.github.sceneview.haptic.ARHapticEvent
import io.github.sceneview.gesture.RotateGestureDetector
import io.github.sceneview.gesture.ScaleGestureDetector
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.math.toQuaternion
import io.github.sceneview.material.setColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.roundToInt
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.isDestroyed
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
 * and [planeOrNull] is `null`. So does an estimated placement
 * ([AutoPlacementState.placementSource] is [PlacementSource.ESTIMATED]): it stands on an ARCore
 * instant-placement point until it is dragged onto a plane.
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
     * or inferred from the floor↔wall seam (#4070), and for an estimated placement.
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
            // An instant-placement point has no polygon: anywhere ARCore can hold it.
            null -> when (trackable) {
                is InstantPlacementPoint -> true
                else -> plane != null && plane.subsumedBy == null && plane.isPoseInPolygon(target)
            }
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

/**
 * The instant-placement mode [AutoPlacementScene] asks ARCore for. A surface flow turns it on:
 * the estimated floor placement and a drag off any tracked plane stand on instant-placement
 * points. A wall flow has no estimate and keeps it off.
 */
internal fun autoPlacementInstantPlacementMode(surface: PlacementSurface): Config.InstantPlacementMode =
    when (surface) {
        PlacementSurface.SURFACE -> Config.InstantPlacementMode.LOCAL_Y_UP
        PlacementSurface.WALL -> Config.InstantPlacementMode.DISABLED
    }

/** Whether ARCore holds [trackable] for real: a plane does, an instant point once fully tracked. */
private fun isSettledOn(trackable: Trackable): Boolean =
    (trackable as? InstantPlacementPoint)?.trackingMethod
        ?.let { it == InstantPlacementPoint.TrackingMethod.FULL_TRACKING } ?: true

private fun placementSourceOf(trackable: Trackable): PlacementSource =
    if (trackable is InstantPlacementPoint) PlacementSource.ESTIMATED else PlacementSource.SURFACE

/** [point] with the object's front (+Z) turned to face [camera]. */
private fun facingPose(point: Float3, camera: Pose): Pose {
    val yaw = PlacementMath.yawToward(point, Float3(camera.tx(), camera.ty(), camera.tz()))
    val q = Rotation(y = yaw).toQuaternion()
    return Pose(floatArrayOf(point.x, point.y, point.z), floatArrayOf(q.x, q.y, q.z, q.w))
}

/**
 * The floor spot the screen centre looks at, [distance] ahead at most and
 * [PlacementFeel.NEAR_SHARE] of it at least, on the lowest tracked plane well below the camera,
 * else [PlacementFeel.PHONE_HEIGHT_GUESS_M] under it. With [preferPlane], a tracked floor plane
 * under that spot carries the candidate; otherwise an ARCore instant-placement point does. The
 * pose faces the camera. Null while the camera does not track or ARCore returns no point.
 */
private fun floorCandidate(
    frame: Frame,
    planes: List<Plane>,
    widthPx: Int,
    heightPx: Int,
    distance: Float,
    preferPlane: Boolean,
): AutoPlacementCandidate? {
    val camera = frame.camera
    if (camera.trackingState != TrackingState.TRACKING) return null
    val view = FloatArray(16).also { camera.getViewMatrix(it, 0) }
    val projection = FloatArray(16).also { camera.getProjectionMatrix(it, 0, 0.1f, 100f) }
    val ray = PlacementMath.screenRay(widthPx / 2f, heightPx / 2f, widthPx, heightPx, view, projection)
        ?: return null
    val eye = camera.pose
    val floors = planes.filter { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
    val floorY = PlacementMath.estimateFloorHeight(eye.ty(), floors.map { it.centerPose.ty() })
    val point = PlacementMath.floorPointAlong(ray, floorY, distance, distance * PlacementFeel.NEAR_SHARE)
        ?: return null
    val pose = facingPose(point, eye)
    if (preferPlane) {
        floors.firstOrNull {
            it.subsumedBy == null && kotlin.math.abs(it.centerPose.ty() - point.y) < 0.02f &&
                it.isPoseInPolygon(pose)
        }?.let { return AutoPlacementCandidate(it, pose) }
    }
    val (x, y) = PlacementMath.projectToScreen(point, widthPx, heightPx, view, projection) ?: return null
    val reach = ViewportProjection.distance(point.x, point.y, point.z, eye.tx(), eye.ty(), eye.tz())
        .coerceIn(PlacementFeel.MIN_INSTANT_DISTANCE_M, PlacementFeel.MAX_SURFACE_DISTANCE_M)
    val hit = arSessionGuarded("AutoPlacement estimate", null) { frame.hitTestInstantPlacement(x, y, reach) }
        ?.firstOrNull { it.trackable is InstantPlacementPoint } ?: return null
    val at = hit.hitPose
    return AutoPlacementCandidate(hit.trackable, facingPose(Float3(at.tx(), at.ty(), at.tz()), eye), null)
}

/** A [AutoPlacementState.recenter] call, served on the next session frame. */
private class RecenterRequest {
    var requested = false
    var cameraTracking = false
}

/**
 * Gestures that began off the placed object, which [AutoPlacementScene] hands to it: a drag
 * anywhere moves it, a twist anywhere turns it, a pinch anywhere sizes it.
 */
internal interface ScreenGestures {
    fun moveBegin(detector: MoveGestureDetector, e: MotionEvent)
    fun move(detector: MoveGestureDetector, e: MotionEvent)
    fun moveEnd(detector: MoveGestureDetector, e: MotionEvent)
    fun rotateBegin(detector: RotateGestureDetector, e: MotionEvent)
    fun rotate(detector: RotateGestureDetector, e: MotionEvent)
    fun rotateEnd(detector: RotateGestureDetector, e: MotionEvent)
    fun scaleBegin(detector: ScaleGestureDetector, e: MotionEvent)
    fun scale(detector: ScaleGestureDetector, e: MotionEvent)
    fun scaleEnd(detector: ScaleGestureDetector, e: MotionEvent)
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
 * plane visualization. With no usable plane after [AutoPlacementState.estimatedFallbackMillis]
 * of tracking (600 ms by default), the object stands on the estimated floor
 * [AutoPlacementState.placementDistance] ahead instead, and settles when ARCore tracks it
 * ([AutoPlacementState.isSettled]); a wall flow always waits for a wall. Gestures work anywhere
 * on screen, not only on the object: one finger drags it (and throws it, gliding on), two
 * fingers twist it (and spin it on) and pinch it, a double tap toggles 100 %, and a new touch
 * catches a gliding or spinning object. [AutoPlacementState.recenter] brings it back in front.
 * Load the asset before setting [assetReady]; keep the old asset
 * until a replacement succeeds, and gate asynchronous results using [AutoPlacementState.ticket].
 *
 * [state] exposes recovery, reset and explicit request actions. Camera/capability events
 * are forwarded without imposing app copy. Haptics are opt-in: call [ARHapticFeedback] with the
 * same [state]. Content is composed on the main thread.
 * [AutoPlacementModel] supplies grounded, surface-constrained manipulation.
 *
 * @param coaching show the animated [ARCoachingOverlay] (phone sweep while scanning, a
 *   "surface found" pill on placement, the reason and its fix when tracking degrades). On by
 *   default. Pass `false` to draw your own from [rememberArGuidanceState], and hide your own
 *   status chrome while [ArGuidanceState.isCoaching] is true either way.
 * @param coachingContentPadding the room your own chrome takes over the scene — a dock, a
 *   toolbar, a sheet peek — forwarded to [ARCoachingOverlay] as its `contentPadding`. The card
 *   and its "Surface found" pill already keep clear of the system bars and a 16 dp gutter; this
 *   is what they cannot measure, because you draw it. Only read while [coaching] is `true`.
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
    coachingContentPadding: PaddingValues = PaddingValues(0.dp),
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
    // The coaching card sits where ARSceneView draws its "Couldn't start AR" card, and the
    // old glyph kept sweeping over that card's copy and its Try again button (#3986).
    // DESIGN.md: the coaching is silent whenever a card explains the state.
    var availability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    // Same rule for the "Camera access needed" card: with the camera refused the session
    // never starts, so the coaching sat on "Getting ready" over that card for good (#4452).
    var cameraDenied by remember { mutableStateOf(false) }
    // The coaching card names the reason ("Too dark", "Too fast") while tracking struggles.
    var trackingFailure by remember { mutableStateOf<TrackingFailureReason?>(null) }
    // A recenter needs the camera and the planes: it is served on the next session frame.
    val recenter = remember(state) { RecenterRequest() }
    DisposableEffect(state, surface) {
        state.recenterAction = {
            val accepted = surface == PlacementSurface.SURFACE && recenter.cameraTracking
            if (accepted) recenter.requested = true
            accepted
        }
        onDispose { state.recenterAction = null }
    }
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
            instantPlacementMode = autoPlacementInstantPlacementMode(surface),
            onGestureListener = rememberOnGestureListener(
                // A new touch catches a gliding or spinning object where it is.
                onDown = { _, _ -> state.touchDown() },
                // Tap the object to select it, tap empty space to deselect — Scene Viewer's
                // selection model.
                onSingleTapConfirmed = { _, node ->
                    if (node == null) state.deselectPlacement() else state.selectPlacement()
                },
                // Double-tap anywhere: back to 100 %, or back to the user's size from 100 %.
                onDoubleTap = { _, _ -> if (state.hasPlacement) state.toggleBaseScale() },
                // A gesture that begins off the object still drives it (AR Quick Look): the
                // object is small on screen, the fingers need not find it first.
                onMoveBegin = { d, e, node -> if (node == null) state.screenGestures?.moveBegin(d, e) },
                onMove = { d, e, node -> if (node == null) state.screenGestures?.move(d, e) },
                onMoveEnd = { d, e, node -> if (node == null) state.screenGestures?.moveEnd(d, e) },
                onRotateBegin = { d, e, node -> if (node == null) state.screenGestures?.rotateBegin(d, e) },
                onRotate = { d, e, node -> if (node == null) state.screenGestures?.rotate(d, e) },
                onRotateEnd = { d, e, node -> if (node == null) state.screenGestures?.rotateEnd(d, e) },
                onScaleBegin = { d, e, node -> if (node == null) state.screenGestures?.scaleBegin(d, e) },
                onScale = { d, e, node -> if (node == null) state.screenGestures?.scale(d, e) },
                onScaleEnd = { d, e, node -> if (node == null) state.screenGestures?.scaleEnd(d, e) },
            ),
            onARCoreAvailability = {
                availability = it
                onARCoreAvailability?.invoke(it)
            },
            onCameraPermissionStateChanged = { cameraDenied = it != null },
            onTrackingFailureChanged = {
                trackingFailure = it
                onTrackingFailureChanged?.invoke(it)
            },
            onSessionFailed = { state.cameraFailed(); onSessionFailed?.invoke(it) },
            onSessionUpdated = { session, frame ->
                if (!state.hasPlacement && placement != null) {
                    placement?.anchor?.detach()
                    placement = null
                }
                val tracked = session.getAllTrackables(Plane::class.java)
                    .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
                if (tracked != planes) planes = tracked
                state.onFloor(trackedFloorY(tracked))
                val tracking = frame.camera.trackingState == TrackingState.TRACKING
                recenter.cameraTracking = tracking
                if (tracking) state.onCamera(frame.camera.pose.ty())
                val searching = ready && state.wantsSurface
                val candidate = if (searching) {
                    findAutoPlacementSurface(frame, tracked, viewport.width, viewport.height, surface)
                } else null
                val effect = state.onFrame(
                    FrameInput(
                        nowMillis = SystemClock.uptimeMillis(),
                        tracking = tracking,
                        surfaceAvailable = candidate != null,
                        anchorTracking = placement?.anchor?.trackingState?.let { it == TrackingState.TRACKING },
                        estimatedAvailable = searching && tracking && surface == PlacementSurface.SURFACE,
                        anchorSettled = placement?.let { isSettledOn(it.trackable) },
                    ),
                    commit = { source ->
                        val next = when (source) {
                            PlacementSource.SURFACE -> candidate?.createAnchor()
                            PlacementSource.ESTIMATED -> floorCandidate(
                                frame, tracked, viewport.width, viewport.height, state.placementDistance,
                                preferPlane = false,
                            )?.createAnchor()
                        }
                        next?.let { placement = it; true } ?: false
                    },
                )
                if (effect == FrameEffect.PLACE) placement?.let { placedCallback?.invoke(it) }
                if (recenter.requested) {
                    recenter.requested = false
                    if (tracking && placement?.isWall == false) {
                        floorCandidate(
                            frame, tracked, viewport.width, viewport.height, state.placementDistance,
                            preferPlane = true,
                        )?.let { state.relocateAction?.invoke(it) }
                    }
                }
            },
        ) {
            placement?.let { result -> key(result) { content(result) } }
            if (groundShadows && (state.phase == PlacementPhase.PLACED || state.phase == PlacementPhase.ADJUSTING)) {
                planes.forEach { plane -> key(plane) { ShadowReceiverPlane(plane = plane) } }
            }
        }
        if (coaching) {
            val guidance = rememberArGuidanceState(state, surface, trackingFailureReason = trackingFailure)
            ARCoachingOverlay(
                cue = if (availability == null && !cameraDenied) guidance.cue else ArGuidanceCue.NONE,
                surface = guidance.surface,
                hint = guidance.hint,
                scanLingering = guidance.scanLingering,
                contentPadding = coachingContentPadding,
            )
        }
    }
}

/**
 * Binary-compatibility shim for the pre-`coachingContentPadding` descriptor of
 * [AutoPlacementScene] (v4.51). Code compiled against it gets no extra coaching padding, which
 * is what it had.
 */
@Deprecated(
    "Binary-compatibility overload. Use the AutoPlacementScene overload that takes `coachingContentPadding`.",
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
    coaching: Boolean = true,
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
    coaching = coaching,
    coachingContentPadding = PaddingValues(0.dp),
    playbackDataset = playbackDataset,
    onARCoreAvailability = onARCoreAvailability,
    onTrackingFailureChanged = onTrackingFailureChanged,
    onSessionFailed = onSessionFailed,
    onPlaced = onPlaced,
    content = content,
)

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
 * Grounded model content for [AutoPlacementScene], at its real size by default: an AR viewer
 * shows a chair as big as a chair. Pass [scaleToUnits] for a longest-axis preview size instead.
 * The model's complete bounding volume is selectable, including nested meshes. [assetRotation]
 * corrects authored axes before the grounded bounds are calculated. The object grows into place
 * from [PlacementFeel.APPEAR_SCALE].
 *
 * Gestures, as Scene Viewer and AR Quick Look, anywhere on screen:
 * - **Drag** with one finger moves the object over the surface; released fast, it glides on and
 *   slows down. Off any tracked plane it slides on the floor height it stands at.
 * - **Pinch** follows the fingers 1:1 relative to where they landed (spreading them twice as
 *   far doubles the object), limited to [AutoPlacementState.scaleRange] (25–400 % of the base by
 *   default), snapping to 100 % within ±4 % with a tick and a short elastic rebound.
 * - **Twist** turns the object about its base; released fast, it spins on and slows down.
 * - **Double-tap** ([AutoPlacementState.toggleBaseScale]) animates back to 100 %; at 100 % it
 *   returns to the size last pinched to.
 * - A new touch catches a gliding or spinning object.
 *
 * A soft [contactShadow] grounds a floor or table object. A flat ring lies around its footprint
 * while it is under the fingers and for a moment after it lands
 * ([PlacementRingVisibility]); it is dashed while the placement is only estimated. Opt out with
 * [AutoPlacementState.showsPlacementRing]. [AutoPlacementNode]'s free-form content draws neither
 * — it has no bounds to measure.
 */
@Composable
fun ARSceneScope.AutoPlacementModel(
    placement: AutoPlacementResult,
    state: AutoPlacementState,
    modelInstance: ModelInstance,
    scaleToUnits: Float? = null,
    assetRotation: Rotation = Rotation(0f),
    contactShadow: Boolean = true,
    onInvalidMove: (Boolean) -> Unit = {},
    onScaleChanged: (percent: Int, atBaseSize: Boolean, enteredBaseSize: Boolean) -> Unit = { _, _, _ -> },
) {
    val entrance = rememberPlacementEntrance(placement, state)
    AutomaticPlacementPivot(placement, state, onInvalidMove, onScaleChanged,
        visibleDuringFade = entrance.value > 0f) {
        // A model destroyed under the composition has nothing left to show, and building a node
        // over it (a re-key below) would throw: the pivot stays, the object leaves.
        if (modelInstance.isDestroyed) return@AutomaticPlacementPivot
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
        val grounded = remember(model) {
            // The model's own bounds, already rotated and grounded — shadow and ring hug it.
            buildList<Position> {
                for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) for (z in listOf(-1f, 1f)) {
                    val corner = model.center +
                        Position(x * model.halfExtent.x, y * model.halfExtent.y, z * model.halfExtent.z)
                    add(model.quaternion * (corner * model.scale) + model.position)
                }
            }
        }
        val footprint = remember(grounded) { SelectionRing.footprint(grounded) }
        val placed = state.phase == PlacementPhase.PLACED || state.phase == PlacementPhase.ADJUSTING
        val touching = state.phase == PlacementPhase.ADJUSTING
        val ringVisible = rememberPlacementRingVisible(
            placed = placed,
            settled = state.isSettled,
            touching = touching,
            revision = state.placementRevision,
        )
        // Opaque glTF materials cannot fade, so the model grows into place and shrinks away
        // on tracking loss instead. Scaling about the pivot keeps the contact point fixed.
        Node(scale = Scale(PlacementEntrance.scaleFraction(entrance.value))) {
            NodeLifecycle(model, null)
            if (!placement.isWall) {
                val center = Position(footprint.centerX, 0f, footprint.centerZ)
                if (contactShadow) {
                    val width = grounded.maxOf { it.x } - grounded.minOf { it.x }
                    val depth = grounded.maxOf { it.z } - grounded.minOf { it.z }
                    ContactShadow(
                        size = Size(
                            x = max(width * PlacementFeel.SHADOW_SPREAD, PlacementFeel.MIN_SHADOW_SIZE_M),
                            y = 0f,
                            z = max(depth * PlacementFeel.SHADOW_SPREAD, PlacementFeel.MIN_SHADOW_SIZE_M),
                        ),
                        position = center,
                    )
                }
                Node(position = center) {
                    PlacementRingNode(
                        radius = footprint.radius,
                        look = placementRingLookFor(touching = touching, settled = state.isSettled),
                        visible = state.showsPlacementRing && placed && ringVisible,
                    )
                }
            }
        }
    }
}

/** The footprint the placement ring encloses — pure, pinned in `ScaleSnapTest`. */
internal object SelectionRing {
    /** Clearance between the object's footprint and the ring, as a fraction of the radius. */
    const val MARGIN = 0.12f

    /** Ring centre on the surface plane (anchor X/Z) and radius, metres. */
    data class Footprint(val centerX: Float, val centerZ: Float, val radius: Float)

    /**
     * The circle on the surface plane (anchor X/Z — the floor, or the wall for a wall
     * placement, whose normal is the anchor's Y) that encloses every grounded bounds corner,
     * plus [MARGIN].
     */
    fun footprint(corners: List<Position>): Footprint {
        if (corners.isEmpty()) return Footprint(0f, 0f, 0f)
        val cx = (corners.minOf { it.x } + corners.maxOf { it.x }) / 2f
        val cz = (corners.minOf { it.z } + corners.maxOf { it.z }) / 2f
        val r = corners.maxOf { kotlin.math.hypot(it.x - cx, it.z - cz) }
        return Footprint(cx, cz, if (r.isFinite()) r * (1f + MARGIN) else 0f)
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
 * over 300 ms. The content also grows into place from [PlacementFeel.APPEAR_SCALE] (the same
 * entrance as [AutoPlacementModel]), scaled about the contact pivot so it never leaves the surface.
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
            /**
             * The logical scale; [scale] only differs from it during the 100 % rebound and
             * the animated double-tap resize.
             */
            var logicalScale = 1f
            private var rebound: Boolean? = null // fromAbove, while a rebound is pending or running
            private var reboundStartNanos = 0L

            /** The last size the user pinched to away from 100 % — what a double-tap restores. */
            var rememberedScale: Float? = null

            /**
             * This pinch opened an adjustment. The detector ends a pinch on the node it began
             * on even when the begin was refused, so only a pinch that opened one closes one.
             */
            var pinching = false

            /** This twist opened an adjustment — same rule as [pinching]. */
            var rotating = false

            /** Visual scale the double-tap animation started from, `null` when none runs. */
            private var tweenFrom: Float? = null
            private var tweenStartNanos = 0L

            /** The spin after a flicked twist that began off the object, in degrees. */
            private val twist = PlacementFeel.twistCoast()
            private val twistStep = FloatArray(1)
            private var twistAngle = 0f

            /** The detector of a twist still spinning on after the fingers left, else null. */
            private var twistDetector: RotateGestureDetector? = null
            private var twistLastNanos = 0L

            /** A twist that began off the object; the scene hands it here. */
            fun screenRotateBegin(detector: RotateGestureDetector, e: MotionEvent): Boolean {
                stopTwist()
                val began = onRotateBegin(detector, e)
                if (began) {
                    twist.reset()
                    twistAngle = 0f
                    twist.addSample(e.eventTime / 1000.0, twistAngle)
                }
                return began
            }

            fun screenRotate(detector: RotateGestureDetector, e: MotionEvent) {
                // The same angle the node applies (NodeGestureDelegate), wrapped across ±π.
                val delta = detector.currentAngle - detector.lastAngle
                twistAngle += Math.toDegrees(-atan2(sin(delta), cos(delta)).toDouble()).toFloat()
                twist.addSample(e.eventTime / 1000.0, twistAngle)
                onRotate(detector, e)
            }

            /** A flicked twist spins on ([PlacementFeel.twistCoast]); any other one ends here. */
            fun screenRotateEnd(detector: RotateGestureDetector, e: MotionEvent) {
                if (rotating && e.actionMasked != MotionEvent.ACTION_CANCEL &&
                    twist.release(e.eventTime / 1000.0)
                ) {
                    twistDetector = detector
                    twistLastNanos = 0L
                    return
                }
                onRotateEnd(detector, e)
            }

            /** Ends a spinning twist where it is: a new touch caught it, or it slowed down. */
            fun stopTwist() {
                val detector = twistDetector ?: return
                twistDetector = null
                twist.stop()
                val now = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 0f, 0f, 0)
                try {
                    onRotateEnd(detector, up)
                } finally {
                    up.recycle()
                }
            }

            fun startRebound(fromAbove: Boolean) {
                tweenFrom = null
                rebound = fromAbove
                reboundStartNanos = 0L
            }

            fun cancelRebound() {
                rebound = null
                // Any running double-tap tween lands where it was going, so a pinch that
                // starts mid-animation grabs the object at its logical size.
                if (tweenFrom != null) {
                    tweenFrom = null
                    scale = Scale(logicalScale)
                }
            }

            /** Animates the visual scale to [target] (already written to [logicalScale]). */
            fun animateTo(target: Float) {
                rebound = null
                tweenFrom = scale.x
                tweenStartNanos = 0L
                logicalScale = target
            }

            override val isFrameActive: Boolean
                get() = rebound != null || tweenFrom != null || twistDetector != null || super.isFrameActive

            override fun onFrame(frameTimeNanos: Long) {
                super.onFrame(frameTimeNanos)
                if (twistDetector != null) {
                    val dt = if (twistLastNanos == 0L) 0f
                        else ((frameTimeNanos - twistLastNanos) / 1e9f).coerceIn(0f, 0.1f)
                    twistLastNanos = frameTimeNanos
                    val spinning = twist.advance(dt, twistStep)
                    if (rotating && state.isAdjusting) {
                        quaternion *= Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), twistStep[0])
                    }
                    if (!spinning || !rotating || !state.isAdjusting) stopTwist()
                }
                tweenFrom?.let { from ->
                    if (tweenStartNanos == 0L) tweenStartNanos = frameTimeNanos
                    val elapsedMs = (frameTimeNanos - tweenStartNanos) / 1_000_000f
                    scale = Scale(ScaleSnap.doubleTapProgress(from, logicalScale, elapsedMs))
                    if (elapsedMs >= ScaleSnap.DOUBLE_TAP_MS) tweenFrom = null
                    return
                }
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
                if (rotating) {
                    rotating = false
                    state.endAdjustment()
                }
            }
            override fun onScaleEnd(detector: ScaleGestureDetector, e: MotionEvent) {
                super.onScaleEnd(detector, e)
                if (pinching) {
                    pinching = false
                    state.endAdjustment()
                }
            }
        }.apply {
            isEditable = true
            isPositionEditable = false
            editableScaleRange = state.usableScaleRange
            onRotateBegin = { _, _ ->
                rotating = state.beginAdjustment()
                rotating
            }
            // The pinch is measured against the moment two fingers landed — the scale then,
            // times the span ratio since — never accumulated event by event: 1:1 with the
            // fingers (Scene Viewer / Quick Look / the iOS controller), path-independent, and
            // immune to whether the detector's factor is per-event or cumulative.
            var pinchStartScale = 1f
            var pinchStartSpan = 0f
            onScaleBegin = { detector, _ ->
                if (!state.allowsScaling) {
                    // Real size is locked: keep the pinch (refusing it would hand it to the
                    // anchor, which would resize instead) and do nothing with it.
                    pinching = false
                    true
                } else {
                    cancelRebound()
                    editableScaleRange = state.usableScaleRange
                    pinchStartScale = logicalScale
                    pinchStartSpan = detector.currentSpan
                    pinching = state.beginAdjustment()
                    pinching
                }
            }
            onRotate = { _, _, _ -> rotating && state.isAdjusting }
            onScale = { detector, _, _ ->
                // Locking real size mid-pinch stops it at once, not at the next pinch.
                if (pinching && state.allowsScaling && state.isAdjusting) {
                    val previous = logicalScale
                    val range = state.usableScaleRange
                    val rawScale = ScaleSnap.pinchRaw(pinchStartScale, pinchStartSpan, detector.currentSpan)
                        .coerceIn(range.start, range.endInclusive)
                    val step = ScaleSnap.step(previous, rawScale, range)
                    logicalScale = step.displayed
                    if (step.enteredSnap) {
                        startRebound(fromAbove = previous > 1f)
                        state.gestureHapticSink?.invoke(ARHapticEvent.ScaleSnapped)
                    } else if (!step.snapped) {
                        cancelRebound()
                        scale = Scale(step.displayed)
                    }
                    if (step.enteredLimit) state.gestureHapticSink?.invoke(ARHapticEvent.LimitReached)
                    if (!step.snapped) rememberedScale = step.displayed
                    state.scaleFactor = step.displayed
                    scaleChanged((step.displayed * 100).roundToInt(), step.snapped, step.enteredSnap)
                }
                false
            }
        } }
        DisposableEffect(pivot, state) {
            state.screenGestures = object : ScreenGestures {
                // Only a gesture that began closes one, as on the object itself.
                var moving = false
                var twisting = false
                var scaling = false
                override fun moveBegin(detector: MoveGestureDetector, e: MotionEvent) {
                    moving = root.screenMoveBegin(detector, e)
                }
                override fun move(detector: MoveGestureDetector, e: MotionEvent) {
                    if (moving) root.screenMove(detector, e)
                }
                override fun moveEnd(detector: MoveGestureDetector, e: MotionEvent) {
                    if (moving) root.screenMoveEnd(detector, e)
                    moving = false
                }
                override fun rotateBegin(detector: RotateGestureDetector, e: MotionEvent) {
                    twisting = pivot.screenRotateBegin(detector, e)
                }
                override fun rotate(detector: RotateGestureDetector, e: MotionEvent) {
                    if (twisting) pivot.screenRotate(detector, e)
                }
                override fun rotateEnd(detector: RotateGestureDetector, e: MotionEvent) {
                    if (twisting) pivot.screenRotateEnd(detector, e)
                    twisting = false
                }
                override fun scaleBegin(detector: ScaleGestureDetector, e: MotionEvent) {
                    scaling = pivot.onScaleBegin(detector, e)
                }
                override fun scale(detector: ScaleGestureDetector, e: MotionEvent) {
                    if (scaling) pivot.onScale(detector, e)
                }
                override fun scaleEnd(detector: ScaleGestureDetector, e: MotionEvent) {
                    if (scaling) pivot.onScaleEnd(detector, e)
                    scaling = false
                }
            }
            state.relocateAction = { root.relocate(it) }
            state.touchDownAction = {
                root.stopGlide()
                pivot.stopTwist()
            }
            state.moveAction = { x, y ->
                // Every refused button press is felt, not only the first one (throttled per event).
                root.moveBy(x, y).also { if (!it) state.gestureHapticSink?.invoke(ARHapticEvent.InvalidMove) }
            }
            state.rotateAction = { pivot.quaternion *= Rotation(y = it).toQuaternion() }
            state.scaleAction = {
                val previous = pivot.logicalScale
                pivot.cancelRebound()
                // Keep the double-tap toggle coherent with the read-out's tap-to-100 % and
                // the accessibility slider: the size left behind is the size a double-tap
                // brings back.
                if (it != 1f) pivot.rememberedScale = it else if (previous != 1f) pivot.rememberedScale = previous
                pivot.logicalScale = it
                pivot.scale = Scale(it)
                state.scaleFactor = it
                val crossed = (previous < 1f && it >= 1f) || (previous > 1f && it <= 1f)
                if (crossed) state.gestureHapticSink?.invoke(ARHapticEvent.ScaleSnapped)
                val range = state.usableScaleRange
                if (ScaleSnap.isAtLimit(it, range) && !ScaleSnap.isAtLimit(previous, range)) {
                    state.gestureHapticSink?.invoke(ARHapticEvent.LimitReached)
                }
                scaleChanged((it * 100).roundToInt(), it == 1f, crossed)
            }
            state.scaleToggleAction = {
                val previous = pivot.logicalScale
                val target = ScaleSnap.doubleTapTarget(previous, pivot.rememberedScale, state.usableScaleRange)
                if (target == null) {
                    // Nothing to toggle: acknowledge the tap with the 100 % rebound.
                    pivot.startRebound(fromAbove = false)
                } else {
                    if (target == 1f) pivot.rememberedScale = previous
                    pivot.animateTo(target)
                    state.scaleFactor = target
                    if (target == 1f) state.gestureHapticSink?.invoke(ARHapticEvent.ScaleSnapped)
                    scaleChanged((target * 100).roundToInt(), target == 1f, target == 1f)
                }
            }
            onDispose {
                state.screenGestures = null
                state.relocateAction = null
                state.touchDownAction = null
                state.moveAction = null
                state.rotateAction = null
                state.scaleAction = null
                state.scaleToggleAction = null
            }
        }
        // Locking the size brings an already-resized object back to real size.
        LaunchedEffect(pivot, state.allowsScaling) {
            if (!state.allowsScaling && pivot.logicalScale != 1f) {
                pivot.rememberedScale = null
                pivot.animateTo(1f)
                state.scaleFactor = 1f
                scaleChanged(100, true, false)
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

    /**
     * Scale fraction the object starts at: [PlacementFeel.APPEAR_SCALE], a speck where it lands
     * (AR Quick Look, ARMV). Not 0, which would leave a zero-scale transform on the first frame.
     */
    const val START_FRACTION = PlacementFeel.APPEAR_SCALE

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

/**
 * What a finished one-finger drag does to the placement (#4731). The move detector ends a drag
 * when the finger lifts, and also when a second finger lands — whether or not the drag had begun.
 */
internal enum class DragEnd {
    /** The drag never began: nothing to commit, no adjustment to close. */
    NONE,

    /** The object stays where it was dragged, on a new anchor. */
    COMMIT,

    /**
     * A second finger landed right after the first one started sliding: a pinch or twist is
     * starting, not a move, and the object goes back on its anchor.
     */
    REVERT;

    companion object {
        /** A drag at most this long when a second finger lands is the start of a pinch or twist. */
        const val SLIP_MAX_MS = 300L

        /** ...and one that carried the object at most this far, metres. */
        const val SLIP_MAX_M = 0.05f

        /**
         * [began]: the drag opened an adjustment. [pointerCount]: fingers down as it ends.
         * [elapsedMs] and [travelM]: how long it lasted and how far it carried the object. A real
         * move followed by a two-finger twist is committed like any other.
         */
        fun of(began: Boolean, pointerCount: Int, elapsedMs: Long, travelM: Float): DragEnd = when {
            !began -> NONE
            pointerCount > 1 && elapsedMs <= SLIP_MAX_MS && travelM <= SLIP_MAX_M -> REVERT
            else -> COMMIT
        }
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

    /** This drag opened an adjustment: only a drag that did closes one ([DragEnd]). */
    private var dragging = false

    /** When this drag began, `MotionEvent.eventTime`. */
    private var dragStartMs = 0L

    /** The glide after a thrown drag that began off the object, in screen pixels. */
    private val glide = PlacementFeel.moveCoast()
    private val glideStep = FloatArray(2)

    /** The detector of a drag still gliding on after the finger left, else null. */
    private var glideDetector: MoveGestureDetector? = null
    private var glideX = 0f
    private var glideY = 0f
    private var glideDownTime = 0L
    private var lastFrameNanos = 0L

    /** Re-anchoring a move the camera interrupted, backing off between attempts. */
    private val retry = AnchorRetryPolicy()

    /** The instant-placement point a drag off every tracked plane stands on, once per drag. */
    private var dragPoint: Trackable? = null
    private var dragPointTried = false

    init {
        isEditable = true
        // The object follows the finger closely, and eases onto its anchor when the anchor moves
        // on its own (an estimated placement settling, a recenter).
        isSmoothTransformEnabled = true
        smoothTransformSpeed = PlacementFeel.SETTLE_SPEED
    }

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
        dragging = true
        dragStartMs = e.eventTime
        offset = floatArrayOf(grab.x, grab.y, grab.z)
        pending = null
        interruptedMove = false
        updateAnchorPose = false
        dragPoint = null
        dragPointTried = false
        smoothTransformSpeed = PlacementFeel.DRAG_SPEED
        return true
    }

    /** A drag that began off the object; the scene hands it here. */
    fun screenMoveBegin(detector: MoveGestureDetector, e: MotionEvent): Boolean {
        stopGlide()
        glide.reset()
        val began = onMoveBegin(detector, e)
        if (began) glide.addSample(e.eventTime / 1000.0, e.x, e.y)
        return began
    }

    fun screenMove(detector: MoveGestureDetector, e: MotionEvent) {
        glide.addSample(e.eventTime / 1000.0, e.x, e.y)
        onMove(detector, e)
    }

    /** A thrown one-finger drag glides on ([PlacementFeel.moveCoast]); any other one ends here. */
    fun screenMoveEnd(detector: MoveGestureDetector, e: MotionEvent) {
        if (dragging && e.pointerCount == 1 && e.actionMasked != MotionEvent.ACTION_CANCEL &&
            state.isAdjusting && pending != null && glide.release(e.eventTime / 1000.0)
        ) {
            glideDetector = detector
            glideX = e.x
            glideY = e.y
            glideDownTime = e.downTime
            return
        }
        onMoveEnd(detector, e)
    }

    /** One glide step per camera frame, through the same checks as a drag. */
    private fun advanceGlide(frame: Frame) {
        val detector = glideDetector ?: return
        val dt = if (lastFrameNanos == 0L) 0f
            else ((frame.timestamp - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        val gliding = glide.advance(dt, glideStep)
        glideX += glideStep[0]
        glideY += glideStep[1]
        val viewport = collisionSystem?.view?.viewport
        if (viewport == null || glideX !in 0f..viewport.width.toFloat() ||
            glideY !in 0f..viewport.height.toFloat()
        ) {
            stopGlide()
            return
        }
        val move = MotionEvent.obtain(glideDownTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
            glideX, glideY, 0)
        val moved = try {
            onMove(detector, move)
        } finally {
            move.recycle()
        }
        // A glide that runs into an invalid spot stops at the last valid one.
        if (!gliding || !moved) stopGlide()
    }

    /** Ends a gliding drag where it is: a new touch caught it, or it slowed down. */
    fun stopGlide() {
        val detector = glideDetector ?: return
        glideDetector = null
        glide.stop()
        val up = MotionEvent.obtain(glideDownTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
            glideX, glideY, 0)
        try {
            onMoveEnd(detector, up)
        } finally {
            up.recycle()
        }
    }

    /**
     * Moves the object onto [candidate] at once ([AutoPlacementState.recenter]), easing over
     * from where it stood. Refused mid-drag, or when ARCore cannot anchor there.
     */
    fun relocate(candidate: AutoPlacementCandidate): Boolean {
        stopGlide()
        if (dragging) return false
        val next = candidate.createAnchor() ?: return false
        adopt(next)
        pending = null
        interruptedMove = false
        retry.reset()
        updateAnchorPose = true
        offset = null
        state.relocated(placementSourceOf(next.trackable), isSettledOn(next.trackable))
        return true
    }

    override fun onMove(detector: MoveGestureDetector, e: MotionEvent): Boolean {
        if (!state.isAdjusting) return false
        val frame = frame ?: return false
        // Same stale-frame case as PoseNode.onMove: a paused session throws from hitTest.
        val hits = arSessionGuarded("AutoPlacement onMove", null) { frame.hitTest(e) } ?: return false
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
                ?: offPlaneCandidate(frame, e)
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
        val target = pending?.pose
        val travel = if (target == null) 0f else ViewportProjection.distance(
            target.tx(), target.ty(), target.tz(), anchor.pose.tx(), anchor.pose.ty(), anchor.pose.tz(),
        )
        when (DragEnd.of(dragging, e.pointerCount, e.eventTime - dragStartMs, travel)) {
            // The detector also ends a drag it never began — a second finger landing before the
            // first one travelled: that drag opened no adjustment, so it closes none (closing
            // one would end the pinch the two fingers are starting).
            DragEnd.NONE -> return
            DragEnd.COMMIT -> if (state.isAdjusting && !commitMove() && pending != null) {
                // ARCore could not anchor there this frame: hold the spot and retry.
                interruptedMove = true
                retry.reset()
                retry.failed(SystemClock.uptimeMillis())
            }
            // A second finger landing right after the first started sliding is a pinch or a
            // twist starting (#4731): the few pixels the first finger slid are not a move.
            // Committed, each pinch walked the object a few centimetres away from where it was
            // set down. Back on its anchor instead.
            DragEnd.REVERT -> {
                pending = null
                interruptedMove = false
                if (anchor.trackingState == TrackingState.TRACKING) pose = anchor.pose
            }
        }
        dragging = false
        if (!interruptedMove) {
            updateAnchorPose = true
            pending = null
        }
        offset = null
        smoothTransformSpeed = PlacementFeel.SETTLE_SPEED
        invalidMove(false)
        state.endAdjustment()
    }

    /**
     * Off every tracked plane, the object slides on the floor height it stands at, under the
     * finger: AR Quick Look keeps moving where ARCore has not mapped the floor yet. It then
     * stands on an instant-placement point until dragged back onto a plane.
     */
    private fun offPlaneCandidate(frame: Frame, e: MotionEvent): AutoPlacementCandidate? {
        val screenRay = collisionSystem?.view?.screenToRay(e.x, e.y) ?: return null
        val ray = PlacementRay(screenRay.origin, screenRay.direction)
        val t = PlacementMath.intersectHorizontal(ray, pose.ty()) ?: return null
        val under = ray.at(t)
        val delta = offset ?: floatArrayOf(pose.tx() - under.x, 0f, pose.tz() - under.z).also { offset = it }
        val camera = frame.camera.pose
        val target = Float3(under.x + delta[0], pose.ty(), under.z + delta[2])
        if (kotlin.math.hypot(target.x - camera.tx(), target.z - camera.tz()) > PlacementFeel.MAX_SURFACE_DISTANCE_M) {
            return null
        }
        val point = dragPoint ?: (placement.trackable as? InstantPlacementPoint) ?: run {
            if (dragPointTried) return null
            dragPointTried = true
            val reach = ViewportProjection.distance(target.x, target.y, target.z, camera.tx(), camera.ty(), camera.tz())
                .coerceIn(PlacementFeel.MIN_INSTANT_DISTANCE_M, PlacementFeel.MAX_SURFACE_DISTANCE_M)
            arSessionGuarded("AutoPlacement drag point", null) { frame.hitTestInstantPlacement(e.x, e.y, reach) }
                ?.firstOrNull { it.trackable is InstantPlacementPoint }?.trackable
        } ?: return null
        dragPoint = point
        return AutoPlacementCandidate(
            point,
            Pose(floatArrayOf(target.x, target.y, target.z), pose.rotationQuaternion),
            null,
        )
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
        val source = placementSourceOf(placement.trackable)
        adopt(next)
        pending = null
        val now = placementSourceOf(next.trackable)
        if (now != source) state.relocated(now, isSettledOn(next.trackable))
        return true
    }

    private fun adopt(next: AutoPlacementResult) {
        anchor = next.anchor
        placement.anchor = next.anchor
        placement.trackable = next.trackable
        placement.kind = next.kind
        placement.pose = next.pose
    }

    override fun update(session: Session, frame: Frame) {
        val tracking = frame.camera.trackingState == TrackingState.TRACKING &&
            anchor.trackingState == TrackingState.TRACKING
        if (!tracking) {
            // Tracking lost mid-glide: the drag ends below like any interrupted one.
            if (glideDetector != null) {
                // The finger already left: no move end will come to close the drag.
                glideDetector = null
                glide.stop()
                dragging = false
                smoothTransformSpeed = PlacementFeel.SETTLE_SPEED
            }
            lastFrameNanos = 0L
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
        if (interruptedMove && !dragging) {
            val now = SystemClock.uptimeMillis()
            if (retry.isDue(now)) {
                if (commitMove()) {
                    interruptedMove = false
                    updateAnchorPose = true
                    retry.reset()
                } else {
                    retry.failed(now)
                    if (retry.isExhausted) {
                        // ARCore will not anchor there: back onto the anchor it has.
                        interruptedMove = false
                        pending = null
                        updateAnchorPose = true
                        retry.reset()
                    }
                }
            }
        }
        super.update(session, frame)
        advanceGlide(frame)
        lastFrameNanos = frame.timestamp
    }
}
