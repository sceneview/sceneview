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

    /**
     * Minimum interval, in milliseconds, between two ARCore [Frame.hitTest] calls (#2328 / #2402
     * MED-5).
     *
     * `0` (the default) runs the hit test on **every** updated frame — the original, byte-for-byte
     * behavior. ARCore's [Frame.hitTest] is a comparatively expensive per-pixel raycast, and a
     * scene with many [HitResultNode]s (or a high refresh display) can issue hundreds of hit tests
     * a second, which itself degrades tracking quality. Set a positive value (e.g. `100` = 10 Hz)
     * to rate-limit the raycast the same way [PointCloudNode]/[DepthMeshNode] rate-limit their
     * per-frame rebuilds; between hit tests the node keeps its last pose and [super.update] still
     * runs every frame, so the existing smooth-transform interpolation is unaffected.
     */
    var refreshIntervalMs: Long = 0L

    /**
     * Wall-clock time of the last [hitTest] call so [refreshIntervalMs] can gate the next one.
     * Render/AR-frame-thread only. `0L` = "never run yet"; like [PointCloudNode] it is `0L` and not
     * `Long.MIN_VALUE` so the first frame is never wrongly throttled by overflow (#2186).
     */
    private var lastHitTestTimestampMs: Long = 0L

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
     * @param refreshIntervalMs         Minimum interval (ms) between ARCore hit tests. `0` (the
     *                                  default) runs the hit test every updated frame — the
     *                                  original behavior; a positive value rate-limits the raycast
     *                                  (see [refreshIntervalMs]).
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
        predicate: ((HitResult) -> Boolean)? = null,
        refreshIntervalMs: Long = 0L
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
    ) {
        this.refreshIntervalMs = refreshIntervalMs
    }

    /** Hook for tests — JVM monotonic time. Mirrors [PointCloudNode.nowMs]. */
    protected open fun nowMs(): Long = System.currentTimeMillis()

    override fun update(session: Session, frame: Frame) {
        if (update) {
            // Rate-limit gate (#2328 / #2402 MED-5). With the default refreshIntervalMs = 0 this
            // never trips, so the hit test runs every updated frame exactly as before. A positive
            // interval skips the ARCore Frame.hitTest raycast on frames inside the window; the node
            // keeps its previous hitResult/pose until the interval elapses.
            val now = nowMs()
            if (!hitResultRefreshThrottled(now, lastHitTestTimestampMs, refreshIntervalMs)) {
                hitResult = hitTest(frame)
                lastHitTestTimestampMs = now
            }
        }

        super.update(session, frame)
    }
}

/**
 * Pure rate-limit decision for [HitResultNode.update] (#2328 / #2402 MED-5).
 *
 * Returns `true` when this frame's [com.google.ar.core.Frame.hitTest] should be **skipped** because
 * the configured [refreshIntervalMs] window has not yet elapsed since [lastHitTestTimestampMs].
 *
 * A [refreshIntervalMs] of `0` (the default) is the "every frame" sentinel and is never throttled —
 * preserving the original byte-for-byte per-frame behaviour. Mirrors [pointCloudRebuildThrottled],
 * including the `lastHitTest = 0L` first-frame case (`0L`, not `Long.MIN_VALUE`, so the first hit
 * test is never blocked by overflow — the #2186 guard). Extracted as an `internal` top-level
 * function so the gate can be pinned by a JVM unit test without ARCore / Filament.
 *
 * @param now                    Current wall-clock time (ms).
 * @param lastHitTestTimestampMs Time of the last hit test (ms); `0L` = never run.
 * @param refreshIntervalMs      Minimum interval between hit tests; `0` = never throttle.
 * @return `true` to skip this frame's hit test, `false` to run it.
 */
internal fun hitResultRefreshThrottled(
    now: Long,
    lastHitTestTimestampMs: Long,
    refreshIntervalMs: Long,
): Boolean = refreshIntervalMs > 0L && now - lastHitTestTimestampMs < refreshIntervalMs