package io.github.sceneview.ar.node

import com.google.android.filament.Engine
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.arcore.firstByTypeOrNull

/**
 * AR placement reticle — a [HitResultNode] that continuously hit-tests at a fixed
 * screen-space coordinate (typically screen center) and auto-hides when no valid
 * surface is found under the camera ray (#1882).
 *
 * The reticle is the standard AR placement-UX primitive: the user sees a small
 * marker on the detected surface where their next tap will land, snapped to the
 * surface normal. Wraps the existing [HitResultNode] hit-test logic with two
 * additions tuned for placement UI:
 *
 *  1. **Auto-hide on no-hit.** When the ray misses every trackable, `isVisible`
 *     drops to `false` so a previously rendered marker doesn't ghost on the last
 *     known pose — a common pitfall when reusing [HitResultNode] directly for
 *     placement reticles. Re-enables automatically on the next successful hit.
 *
 *  2. **`onHitResultChanged` callback.** Notifies the caller every time the hit
 *     result changes between `null` and a value, or between two distinct
 *     trackables / poses. Mirrors the snapshot semantics demos use to drive
 *     "aim at a surface" hint text and to capture the last-known hit pose on
 *     tap-to-place — without the demo having to attach a separate
 *     `onSessionUpdated` listener and re-run an identical hit test.
 *
 * Visual rendering is intentionally left to the caller — declare a child geometry
 * (typically a small `CylinderNode` disc or a `SphereNode`) inside the
 * composable's `content` block and it will follow the reticle's pose. See the
 * `samples/android-demo` `ARPlacementDemo` for a reference implementation.
 *
 * ### Threading
 *
 * [update] runs from the AR render path (`ARSceneView`'s `withFrameNanos`
 * coroutine, dispatched on the main thread). [onHitResultChanged] is invoked
 * inline from the same thread, so listeners can safely mutate Compose
 * `MutableState` without any cross-thread plumbing.
 *
 * @param engine                   Filament [Engine] that owns this node.
 * @param xPx                      View X coordinate in pixels for the hit test.
 * @param yPx                      View Y coordinate in pixels for the hit test.
 * @param planeTypes               Which plane types to include in results.
 * @param point                    Include [Point] trackable results.
 * @param depthPoint               Include depth-based hit results.
 * @param instantPlacementPoint    Include instant placement results.
 * @param trackingStates           Only accept results where the trackable has these states.
 * @param pointOrientationModes    Filter by point orientation mode.
 * @param planePoseInPolygon       Require the pose to lie inside the plane polygon.
 * @param predicate                Custom filter applied to each candidate [HitResult].
 * @param onHitResultChanged       Invoked whenever the resolved [HitResult] changes
 *                                 (including transitions to / from `null`).
 */
open class ReticleNode(
    engine: Engine,
    val xPx: Float,
    val yPx: Float,
    val planeTypes: Set<Plane.Type> = Plane.Type.values().toSet(),
    val point: Boolean = true,
    val depthPoint: Boolean = true,
    val instantPlacementPoint: Boolean = false,
    val trackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
    val pointOrientationModes: Set<Point.OrientationMode> = setOf(
        Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
    ),
    val planePoseInPolygon: Boolean = true,
    val predicate: ((HitResult) -> Boolean)? = null,
    var onHitResultChanged: ((HitResult?) -> Unit)? = null
) : HitResultNode(
    engine = engine,
    hitTest = { frame ->
        frame.hitTest(xPx, yPx).firstByTypeOrNull(
            planeTypes = planeTypes,
            point = point,
            depthPoint = depthPoint,
            instantPlacementPoint = instantPlacementPoint,
            trackingStates = trackingStates,
            pointOrientationModes = pointOrientationModes,
            planePoseInPolygon = planePoseInPolygon,
            minCameraDistance = null,
            predicate = predicate
        )
    }
) {

    /**
     * Last [HitResult] seen by [update] — `null` until the first successful hit
     * test, and re-set to `null` whenever the ray subsequently misses every
     * trackable. Exposed as a separate field rather than reading
     * [HitResultNode.hitResult] directly because the base setter retains the
     * previous value if [HitResultNode.update] is set to `false`, which is
     * the wrong semantics for a placement reticle (the reticle's purpose IS
     * to track the live hit, including its disappearance).
     */
    var currentHitResult: HitResult? = null
        private set(value) {
            if (field !== value) {
                field = value
                onHitResultChanged?.invoke(value)
            }
        }

    override fun update(session: Session, frame: Frame) {
        if (!update) return
        // Recompute the hit at the configured screen coordinate. We deliberately
        // bypass `super.update(session, frame)` to avoid the base class clobbering
        // visibility based on `cameraTrackingState`; the reticle has its own
        // visibility contract (auto-hide on no-hit) layered on top.
        val hit = hitTest(frame)
        this.session = session
        this.frame = frame
        this.cameraTrackingState = frame.camera.trackingState
        // Drive the base [HitResultNode.hitResult] for the non-null case so the
        // node's pose follows the surface. When `hit == null` we keep the last
        // known pose at the Filament level (no jitter back to identity), but
        // surface the disappearance to listeners and hide the visual.
        if (hit != null) {
            hitResult = hit
            isVisible = true
        } else {
            isVisible = false
        }
        currentHitResult = hit
    }
}
