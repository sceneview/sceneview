package io.github.sceneview.ar

import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.google.android.filament.Engine
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.ar.arcore.createAnchorOrNull
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AR **wall-placement** flow — place a product flat against a **vertical surface** (TV, framed
 * art, mirror, shelf) and align it to the **floor↔wall edge**, the way best-in-class retail AR
 * (Amazon "AR View", IKEA Place) does it ([#2740](https://github.com/sceneview/sceneview/issues/2740)).
 *
 * Placing on a wall is materially harder than dropping a model on the floor: ARCore converges
 * vertical planes slowly and noisily, so a naive "anchor at the raw hit pose" leaves the object
 * tilted off the wall and floating at an arbitrary height. The trick the polished apps use is to
 * decouple the two axes of the problem:
 *  - **Orientation** comes from the *wall* — the object is made flush and upright against the
 *    vertical plane ([wallFacingRotation]), never inheriting the hit pose's pitch/roll noise.
 *  - **Vertical position** comes from the *floor* — the object is seated relative to the
 *    **floor↔wall seam** ([floorWallSeam]) at a caller-chosen mount height, so a cabinet's base
 *    rests on the floor and a TV hangs at a stable height even while the wall plane jitters.
 *
 * This file is deliberately split into a **pure-geometry core** (the functions below —
 * [wallFacingRotation], [floorWallSeam], [wallAnchorPose], [nextWallPlacementPhase],
 * [wallPlacementHit]) that is unit-tested on the JVM without Compose or an ARCore session
 * (mirroring [placementHit]/[isPlacementHit] in [PlacementScene]), plus one thin [WallPlacementScene]
 * composable that wires the core to [ARSceneView].
 *
 * **Threading:** like every SceneView composable, all Filament JNI work happens on the main thread;
 * [onPlaced] runs inside the Compose [ARSceneScope].
 */

/** World up axis (+Y). Wall normals and the floor↔wall seam are computed relative to it. */
internal val WORLD_UP: Direction = Direction(0f, 1f, 0f)

/**
 * A degenerate horizontal length below which a "wall normal" is treated as having no usable
 * heading (e.g. a normal pointing straight up — not a wall). Placement then falls back to identity
 * yaw rather than producing a NaN from `atan2(0, 0)`.
 */
internal const val WALL_NORMAL_EPSILON = 1e-6f

/**
 * The upright yaw (radians, about world +Y) that makes an object's **front face** (local +Z, the
 * SceneView convention shared by `ImageNode` / `BillboardNode`) point along [wallNormal] — i.e.
 * flush against the wall and facing into the room.
 *
 * A wall-mounted object rotates about the vertical axis **only**: its up stays world-up and its
 * front is the (horizontal) wall normal, so the full orientation is a single yaw. Deriving it
 * directly — rather than going through a `lookTowards` matrix and extracting a quaternion — keeps
 * the result exact, allocation-free, and trivially unit-testable, and sidesteps any roll/pitch
 * that vertical-plane tracking noise would inject.
 *
 * The wall normal's vertical component is dropped and the horizontal part renormalized, so a
 * slightly-off ARCore normal still yields a clean upright heading. A normal with no horizontal
 * component (|xz| < [WALL_NORMAL_EPSILON]) is not a wall; the yaw falls back to `0`.
 *
 * @param wallNormal the vertical plane's outward normal (points away from the wall, into the room).
 * @return yaw in radians such that `R_y(yaw) · (0,0,1) == normalize(wallNormal.xz)`.
 */
fun wallYaw(wallNormal: Direction): Float {
    val nx = wallNormal.x
    val nz = wallNormal.z
    val horizontalLength = sqrt(nx * nx + nz * nz)
    if (horizontalLength < WALL_NORMAL_EPSILON) return 0f
    return atan2(nx / horizontalLength, nz / horizontalLength)
}

/**
 * The upright orientation that seats an object flat against a wall — a pure yaw (see [wallYaw])
 * about world +Y, as a [Quaternion] ready to feed an ARCore [Pose].
 *
 * @param wallNormal the vertical plane's outward normal (into the room).
 * @return a unit quaternion `(0, sin(yaw/2), 0, cos(yaw/2))`.
 */
fun wallFacingRotation(wallNormal: Direction): Quaternion {
    val halfYaw = wallYaw(wallNormal) / 2f
    return Quaternion(x = 0f, y = sin(halfYaw), z = 0f, w = cos(halfYaw))
}

/**
 * The **floor↔wall seam** — the horizontal line where a vertical wall plane meets the floor. This
 * is the "orange line" the user aligns to in Amazon's / IKEA's wall flow: getting it right is what
 * makes a wall placement read as *attached* rather than *floating*.
 *
 * @param wallNormal the wall plane's outward normal (into the room).
 * @param wallPoint  any point on the wall (e.g. the wall hit position); only its X/Z are used.
 * @param floorY     the world-space Y of the floor (from a tracked horizontal plane).
 * @return the seam as a point at floor height under [wallPoint] plus a **unit horizontal direction**
 *   running along the base of the wall (`normalize(cross(WORLD_UP, wallNormal))`); the direction is
 *   `(0,0,0)` only for a degenerate (non-wall) normal.
 */
fun floorWallSeam(wallNormal: Direction, wallPoint: Position, floorY: Float): FloorWallSeam {
    // cross(up, n) with up=(0,1,0), n=(nx,ny,nz)  ->  (nz, 0, -nx): horizontal, along the wall base.
    val dx = wallNormal.z
    val dz = -wallNormal.x
    val length = sqrt(dx * dx + dz * dz)
    val direction = if (length < WALL_NORMAL_EPSILON) {
        Direction(0f, 0f, 0f)
    } else {
        Direction(dx / length, 0f, dz / length)
    }
    return FloorWallSeam(
        point = Position(wallPoint.x, floorY, wallPoint.z),
        direction = direction,
    )
}

/**
 * The floor↔wall seam produced by [floorWallSeam]: a [point] at floor height on the wall and a unit
 * horizontal [direction] running along the wall base.
 */
data class FloorWallSeam(
    val point: Position,
    val direction: Direction,
)

/**
 * The final wall-anchor transform: an object flush and upright against the wall ([rotation]) at a
 * floor-relative height ([position]).
 *
 * The horizontal placement (X/Z) is taken from where the user tapped the wall; the height is
 * `floorY + mountHeight`, decoupling the vertical position from the noisy wall-plane hit so the
 * object stays put while ARCore refines the wall. For a floor-standing item, pass
 * `mountHeight = objectHalfHeight` (base on the floor); for a hung item (TV, art), pass the desired
 * centre height above the floor.
 *
 * @param wallHit     the wall hit position (X/Z used for horizontal placement).
 * @param wallNormal  the wall plane's outward normal (into the room).
 * @param floorY      the tracked floor height.
 * @param mountHeight height of the object's anchor above the floor, meters.
 * @return the composed [WallAnchorPose].
 */
fun wallAnchorPose(
    wallHit: Position,
    wallNormal: Direction,
    floorY: Float,
    mountHeight: Float,
): WallAnchorPose = WallAnchorPose(
    position = Position(wallHit.x, floorY + mountHeight, wallHit.z),
    rotation = wallFacingRotation(wallNormal),
)

/** The position + rotation produced by [wallAnchorPose], ready to build an ARCore [Pose]. */
data class WallAnchorPose(
    val position: Position,
    val rotation: Quaternion,
)

/** Builds the ARCore [Pose] for a [WallAnchorPose]. */
internal fun WallAnchorPose.toPose(): Pose = Pose(
    floatArrayOf(position.x, position.y, position.z),
    floatArrayOf(rotation.x, rotation.y, rotation.z, rotation.w),
)

/**
 * The onboarding phases of the wall-placement flow, driving coaching UI: find a floor, then a wall,
 * then align to the seam, then done. Mirrors the staged guidance Amazon's "AR View" shows for a
 * wall-mounted product.
 */
enum class WallPlacementPhase {
    /** No floor plane tracked yet — "point at the floor and move to scan". */
    FINDING_FLOOR,

    /** Floor found; no wall tracked yet — "now aim at the wall". */
    FINDING_WALL,

    /** Floor and wall both tracked, nothing placed yet — "align to the floor/wall edge, then tap". */
    ALIGNING_EDGE,

    /** At least one object placed. */
    PLACED,
}

/**
 * Pure phase-transition rule for [WallPlacementScene], extracted so the state machine is unit-tested
 * without Compose or an ARCore session. Placement is terminal: once anything is [placed] the phase
 * stays [WallPlacementPhase.PLACED] regardless of momentary tracking loss.
 *
 * @param floorFound whether a horizontal (floor) plane is tracked.
 * @param wallFound  whether a vertical (wall) plane is tracked.
 * @param placed     whether at least one object has been placed.
 */
fun nextWallPlacementPhase(
    floorFound: Boolean,
    wallFound: Boolean,
    placed: Boolean,
): WallPlacementPhase = when {
    placed -> WallPlacementPhase.PLACED
    floorFound && wallFound -> WallPlacementPhase.ALIGNING_EDGE
    floorFound -> WallPlacementPhase.FINDING_WALL
    else -> WallPlacementPhase.FINDING_FLOOR
}

/**
 * Maximum camera-to-hit distance accepted for a wall placement, meters. Wall planes converge far
 * more noisily than floors, so the reach is a touch shorter than [MAX_PLACEMENT_DISTANCE] to reject
 * spurious far hits.
 */
internal const val MAX_WALL_PLACEMENT_DISTANCE = 4.0f

/**
 * Pure-logic wall-hit selector — picks the first acceptable **vertical-plane** [HitResult] for
 * wall placement. The [PlacementScene] counterpart ([placementHit]) accepts horizontal planes and
 * instant-placement points; this one accepts a vertical plane hit inside its polygon only.
 *
 * @param frame the live ARCore [Frame] the hit-test came from (its camera must be `TRACKING`).
 * @param hits  the hit-test results (already near-to-far sorted by ARCore).
 * @return the first acceptable wall [HitResult], or `null`.
 */
fun wallPlacementHit(frame: Frame, hits: List<HitResult>): HitResult? {
    if (frame.camera.trackingState != TrackingState.TRACKING) return null
    return hits.firstOrNull { hit -> isWallPlacementHit(hit) }
}

/**
 * Per-[HitResult] acceptance predicate for [wallPlacementHit] — reads the JNI-bound trackable once,
 * then delegates to the pure-JVM overload the unit tests exercise.
 */
internal fun isWallPlacementHit(hit: HitResult): Boolean {
    val trackable = hit.trackable
    val verticalInPolygon = trackable is Plane &&
        trackable.type.isVertical() &&
        trackable.isPoseInPolygon(hit.hitPose)
    return isWallPlacementHit(
        verticalPlaneInPolygon = verticalInPolygon,
        trackableTracking = trackable.trackingState == TrackingState.TRACKING,
        distance = hit.distance,
    )
}

/** Whether an ARCore [Plane.Type] is a vertical (wall) surface. */
internal fun Plane.Type.isVertical(): Boolean = this == Plane.Type.VERTICAL

/**
 * Pure-JVM acceptance rule for a single wall-placement hit — the testable core of
 * [isWallPlacementHit]. Accepts a tracking vertical-plane-in-polygon hit within
 * [MAX_WALL_PLACEMENT_DISTANCE].
 *
 * @param verticalPlaneInPolygon whether the hit is a vertical plane and its pose is inside the polygon.
 * @param trackableTracking      whether the trackable's state is `TRACKING`.
 * @param distance               camera-to-hit distance, meters.
 */
internal fun isWallPlacementHit(
    verticalPlaneInPolygon: Boolean,
    trackableTracking: Boolean,
    distance: Float,
): Boolean {
    if (!trackableTracking) return false
    if (distance > MAX_WALL_PLACEMENT_DISTANCE) return false
    return verticalPlaneInPolygon
}

/**
 * One-call **wall placement** AR scene — the vertical-surface sibling of [PlacementScene].
 *
 * Enables horizontal+vertical plane finding, tracks the floor height and the current wall, exposes
 * the live [FloorWallSeam] (so an app can draw the "align to the edge" guide), and on a tap against
 * a wall anchors the caller's content **flush, upright, and at a floor-relative height** via
 * [wallAnchorPose]:
 *
 * ```kotlin
 * WallPlacementScene(mountHeight = 1.2f) { anchor ->
 *     AnchorNode(anchor = anchor) {
 *         ModelNode(
 *             modelInstance = rememberModelInstance(modelLoader, "models/tv.glb"),
 *             scaleToUnits = 1.4f
 *         )
 *     }
 * }
 * ```
 *
 * **Scope of this first increment ([#2740](https://github.com/sceneview/sceneview/issues/2740)):**
 * the placement math (facing rotation, floor↔seam height, vertical-hit selection) and the phase
 * state machine are complete and unit-tested. The in-scene 3D seam guide line, a denser wall-scan
 * visualization, and the dual gizmo/D-pad manipulation UI are follow-ups — the seam is surfaced via
 * [onSeamChanged] and the phase via [onPhaseChanged] so an app can render its own affordances today.
 *
 * @param modifier             [Modifier] for the AR viewport.
 * @param engine               shared Filament [Engine]. Defaults to [rememberEngine].
 * @param modelLoader          [ModelLoader] for glTF/GLB. Defaults to [rememberModelLoader].
 * @param materialLoader       [MaterialLoader]. Defaults to [rememberMaterialLoader].
 * @param mountHeight          height above the floor at which the object is anchored, meters
 *                             (base-on-floor: pass the object's half-height; hung: the centre
 *                             height). Default `1.2f` — a typical TV centre height.
 * @param planeRenderer        render the plane-detection overlay while scanning. Default `true`.
 * @param onSeamChanged        invoked when the tracked floor↔wall [FloorWallSeam] changes (or
 *                             becomes `null` when floor or wall tracking is lost). Draw the
 *                             alignment guide from it. Default `null`.
 * @param onPhaseChanged       invoked when the [WallPlacementPhase] changes, for coaching UI.
 *                             Default `null`.
 * @param playbackDataset      optional recorded ARCore MP4 dataset to replay. Default `null`.
 * @param sessionConfiguration escape-hatch ARCore [Config] callback, forwarded to [ARSceneView].
 * @param onPlaced             invoked inside the [ARSceneScope] once per created [Anchor]; declare
 *                             the content that rides it.
 * @param content              optional extra [ARSceneScope] content; receives the shared
 *                             [PlacementController] (clear placements / read the anchor list).
 */
@Composable
fun WallPlacementScene(
    modifier: Modifier = Modifier,
    engine: Engine = rememberEngine(),
    modelLoader: ModelLoader = rememberModelLoader(engine),
    materialLoader: MaterialLoader = rememberMaterialLoader(engine),
    mountHeight: Float = 1.2f,
    planeRenderer: Boolean = true,
    onSeamChanged: ((FloorWallSeam?) -> Unit)? = null,
    onPhaseChanged: ((WallPlacementPhase) -> Unit)? = null,
    playbackDataset: File? = null,
    sessionConfiguration: ((session: com.google.ar.core.Session, Config) -> Unit)? = null,
    onPlaced: @Composable ARSceneScope.(anchor: Anchor) -> Unit,
    content: (@Composable ARSceneScope.(controller: PlacementController) -> Unit)? = null,
) {
    val controller = remember { PlacementController() }

    var latestFrame by remember { mutableStateOf<Frame?>(null) }

    // Tracked-surface state, refreshed each frame; drives the seam, the phase, and the floor height
    // used for the floor-relative mount.
    var floorY by remember { mutableStateOf<Float?>(null) }
    var wallNormal by remember { mutableStateOf<Direction?>(null) }
    var wallPoint by remember { mutableStateOf<Position?>(null) }
    var phase by remember { mutableStateOf(WallPlacementPhase.FINDING_FLOOR) }

    Box(modifier = modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            playbackDataset = playbackDataset,
            planeRenderer = planeRenderer,
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL,
            sessionConfiguration = sessionConfiguration,
            onSessionUpdated = { updatedSession, frame ->
                latestFrame = frame
                // Lowest tracked horizontal-upward plane = the floor.
                val floors = updatedSession.getAllTrackables(Plane::class.java).filter {
                    it.trackingState == TrackingState.TRACKING &&
                        it.type == Plane.Type.HORIZONTAL_UPWARD_FACING
                }
                floorY = floors.minOfOrNull { it.centerPose.ty() }
                // First tracked vertical plane = the current wall of interest.
                val wall = updatedSession.getAllTrackables(Plane::class.java).firstOrNull {
                    it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.VERTICAL
                }
                if (wall != null) {
                    val c = wall.centerPose
                    // A vertical plane's centre-pose Y axis is its outward normal.
                    val n = c.yAxis
                    wallNormal = Direction(n[0], n[1], n[2])
                    wallPoint = Position(c.tx(), c.ty(), c.tz())
                } else {
                    wallNormal = null
                    wallPoint = null
                }

                val newPhase = nextWallPlacementPhase(
                    floorFound = floorY != null,
                    wallFound = wallNormal != null,
                    placed = controller.count > 0,
                )
                if (newPhase != phase) {
                    phase = newPhase
                    onPhaseChanged?.invoke(newPhase)
                }

                val seam = computeSeam(wallNormal, wallPoint, floorY)
                onSeamChanged?.invoke(seam)
            },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { event: MotionEvent, node ->
                    if (node != null) return@rememberOnGestureListener
                    val frame = latestFrame ?: return@rememberOnGestureListener
                    val hit = wallPlacementHit(frame, frame.hitTest(event))
                        ?: return@rememberOnGestureListener
                    val plane = hit.trackable as? Plane ?: return@rememberOnGestureListener
                    val hitPos = hit.hitPose.let { Position(it.tx(), it.ty(), it.tz()) }
                    val normal = plane.centerPose.yAxis.let { Direction(it[0], it[1], it[2]) }
                    // Floor-relative height when a floor is known; otherwise seat at the raw hit
                    // height so placement still works before the floor converges.
                    val resolvedFloorY = floorY ?: (hitPos.y - mountHeight)
                    val anchorPose = wallAnchorPose(hitPos, normal, resolvedFloorY, mountHeight)
                    plane.createAnchorOrNull(anchorPose.toPose())?.let { controller.add(it) }
                }
            ),
        ) {
            controller.anchors.forEach { anchor ->
                key(anchor) {
                    onPlaced(anchor)
                }
            }
            content?.invoke(this, controller)
        }
    }
}

/**
 * Compose-free seam computation used by [WallPlacementScene] — returns the [FloorWallSeam] when both
 * a wall (normal + point) and a floor height are known, else `null`. Extracted so the null-gating is
 * unit-testable.
 */
internal fun computeSeam(
    wallNormal: Direction?,
    wallPoint: Position?,
    floorY: Float?,
): FloorWallSeam? {
    if (wallNormal == null || wallPoint == null || floorY == null) return null
    return floorWallSeam(wallNormal, wallPoint, floorY)
}
