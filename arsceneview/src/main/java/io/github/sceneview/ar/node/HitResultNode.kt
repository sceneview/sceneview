package io.github.sceneview.ar.node

import com.google.android.filament.Engine
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.arcore.firstByTypeOrNull

/**
 * AR real time AR HitTest positioned node.
 *
 * This [PoseNode] follows the actual ARCore detected orientation and position at the provided
 * relative X, Y location in the AR scene view
 *
 * Performs a ray cast from the user's device in the direction of the given location in the
 * camera view. Intersections with detected scene geometry are returned, sorted by distance from
 * the device; the nearest intersection is returned first.
 */
open class HitResultNode(
    engine: Engine,
    val hitTest: HitResultNode.(Frame) -> HitResult?
) : TrackableNode<Trackable>(engine) {

    /**
     * Make the node follow the camera/screen matching real world positions
     */
    var update: Boolean = true

    open var hitResult: HitResult? = null
        set(value) {
            field = value
            trackable = value?.trackable
            value?.hitPose?.let {
                pose = it
            }
        }

    init {
        isSmoothTransformEnabled = true
    }

    /**
     * Construct a [HitResultNode] from a View-coordinate hit-test location.
     *
     * **Defaults are plane-only ([#1891](https://github.com/sceneview/sceneview/issues/1891)).**
     * `point`, `depthPoint`, and `instantPlacementPoint` all default to `false` because
     * depth / feature-point hits before motion-stereo convergence return positions extremely
     * close to the camera (often <10 cm), which causes a child placement disc / cylinder
     * to render as a fullscreen overlay that blanks the camera feed on session start. Opt
     * each filter back in explicitly once your scene is tracking-stable.
     *
     * **Defensive distance floor.** [minCameraDistance] is a camera-to-hit floor (in meters).
     * Any hit closer than this is rejected — the node keeps its last known pose instead of
     * snapping to the lens. Defaults to `0.3f` (30 cm), the realistic minimum for AR
     * placement; pass `null` to disable.
     *
     * @param xPx                       X view coordinate in pixels where the hit test should be done.
     * @param yPx                       Y view coordinate in pixels where the hit test should be done.
     * @param planeTypes                Which plane types are accepted.
     * @param point                     Include [Point] trackable results. Default `false` (#1891).
     * @param depthPoint                Include depth-based hit results. Default `false` (#1891).
     * @param instantPlacementPoint     Include instant placement results. Default `false` (#1891).
     * @param trackingStates            Only accept results where the trackable has these states.
     * @param pointOrientationModes     Filter by point orientation mode.
     * @param planePoseInPolygon        Require the pose to lie inside the plane polygon.
     * @param minCameraDistance         Floor for accepted hits relative to the camera, in meters.
     *                                  Hits closer than this are dropped (the node keeps its
     *                                  last pose). Default `0.3f`; set `null` to disable.
     * @param minCameraDistanceFromPlane Legacy plane-only camera distance gate (uses
     *                                  `Pose.distanceToPlane`). Kept for back-compat; prefer
     *                                  [minCameraDistance] for new code.
     * @param predicate                 Custom filter applied to each [HitResult].
     */
    constructor(
        engine: Engine,
        xPx: Float,
        yPx: Float,
        planeTypes: Set<Plane.Type> = Plane.Type.values().toSet(),
        point: Boolean = false,
        depthPoint: Boolean = false,
        instantPlacementPoint: Boolean = false,
        trackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
        pointOrientationModes: Set<Point.OrientationMode> = setOf(Point.OrientationMode.ESTIMATED_SURFACE_NORMAL),
        planePoseInPolygon: Boolean = true,
        minCameraDistance: Float? = 0.3f,
        minCameraDistanceFromPlane: Pair<Camera, Float>? = null,
        predicate: ((HitResult) -> Boolean)? = null
    ) : this(
        engine = engine,
        hitTest = { frame ->
            frame.hitTest(xPx, yPx).firstByTypeOrNull(
                planeTypes, point, depthPoint, instantPlacementPoint, trackingStates,
                pointOrientationModes, planePoseInPolygon, minCameraDistanceFromPlane, predicate
            )?.takeIf { hit ->
                // #1891 defensive floor — reject hits closer than `minCameraDistance` meters
                // so a depth/feature hit before motion-stereo convergence can never blank the
                // camera feed with a child reticle disc.
                minCameraDistance == null || hit.distance >= minCameraDistance
            }
        }
    )

    override fun update(session: Session, frame: Frame) {
        if (update) {
            hitResult = hitTest(frame)
        }

        super.update(session, frame)
    }
}