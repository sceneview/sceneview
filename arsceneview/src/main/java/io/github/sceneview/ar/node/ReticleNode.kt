package io.github.sceneview.ar.node

import com.google.android.filament.Engine
import com.google.ar.core.Camera
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.TrackingState

/**
 * AR placement reticle — a **thin wrapper** over [HitResultNode] tuned for the
 * standard "tap to place" UX (#1882).
 *
 * The reticle is the placement-UX primitive: the user sees a small marker on the
 * detected surface where their next tap will land, snapped to the surface normal.
 *
 * ### Relationship to [HitResultNode]
 *
 * [ReticleNode] does **not** re-implement hit-testing. It is a [HitResultNode]
 * subclass that delegates the entire screen-coordinate hit test — including
 * [#1891](https://github.com/sceneview/sceneview/issues/1891)'s plane-only
 * defaults (`point` / `depthPoint` / `instantPlacementPoint` all `false`) and the
 * defensive 30 cm `minCameraDistance` floor — to [HitResultNode]'s
 * View-coordinate constructor. The only behaviour it adds is the
 * [onHitResultChanged] callback.
 *
 * **Auto-hide on no-hit comes for free.** When the ray misses every trackable,
 * `hitResult` is set to `null`, which clears `trackable` and drops
 * `trackingState` to [TrackingState.STOPPED]. Because [TrackableNode.isVisible]
 * already gates visibility on `trackingState in visibleTrackingStates`, a child
 * marker stops rendering automatically — no manual visibility juggling needed.
 *
 * **`onHitResultChanged` callback.** Notifies the caller every time the resolved
 * hit transitions between `null` and a value (or between two distinct hits), so
 * a demo can drive "aim at a surface" hint text and capture the last-known hit
 * pose on tap-to-place — without attaching a separate `onSessionUpdated`
 * listener that re-runs an identical hit test.
 *
 * ### Hit-test cost
 *
 * The reticle inherits [HitResultNode.refreshIntervalMs] and forwards it from its own
 * constructor ([#3391](https://github.com/sceneview/sceneview/issues/3391)). `0` — the
 * default — runs ARCore's [HitResult] raycast on **every** updated frame, the original
 * byte-for-byte behaviour. Because a reticle is the one node that is on screen for the
 * whole placement flow, it is also the one most likely to want a ceiling: pass a positive
 * value (e.g. `100` = 10 Hz) to rate-limit the raycast. Between hit tests the reticle keeps
 * its last pose and the inherited smooth-transform easing still runs every frame, so the
 * marker keeps gliding rather than stepping.
 *
 * ### When to use [ReticleNode] vs raw [HitResultNode]
 *
 * - Use [ReticleNode] for a placement cursor where you need a one-call callback
 *   on every hit change (hint text, last-known hit capture).
 * - Use [HitResultNode] directly when you only need a node that follows the
 *   hit test and don't need the change callback, or when you want a fully
 *   custom `hitTest` lambda.
 *
 * Visual rendering is intentionally left to the caller — declare a child geometry
 * (typically a small `CylinderNode` disc or a `SphereNode`) inside the
 * composable's `content` block and it will follow the reticle's pose. See the
 * `samples/android-demo` `ARPlacementDemo` for a reference implementation.
 *
 * ### Cross-platform parity
 *
 * Android-only. On Apple (RealityKit) the equivalent is a continuous
 * `ARView.raycast(from:allowing:alignment:)` against `.existingPlaneGeometry`,
 * driven from the render loop — there is no node type, the raycast result feeds
 * a placement entity directly.
 *
 * ### Threading
 *
 * The hit test runs from the AR render path (`ARSceneView`'s `withFrameNanos`
 * coroutine, dispatched on the main thread). [onHitResultChanged] is invoked
 * inline from the same thread, so listeners can safely mutate Compose
 * `MutableState` without any cross-thread plumbing.
 *
 * @param engine                    Filament [Engine] that owns this node.
 * @param xPx                       View X coordinate in pixels for the hit test
 *                                  (screen center on most placement UIs).
 * @param yPx                       View Y coordinate in pixels for the hit test.
 * @param planeTypes                Which plane types to include in results.
 * @param point                     Include [Point] trackable results. Default
 *                                  `false` (#1891 plane-only).
 * @param depthPoint                Include depth-based hit results. Default
 *                                  `false` (#1891 plane-only).
 * @param instantPlacementPoint     Include instant placement results. Default
 *                                  `false` — the marker only appears on real geometry.
 * @param trackingStates            Only accept results where the trackable has these states.
 * @param pointOrientationModes     Filter by point orientation mode.
 * @param planePoseInPolygon        Require the pose to lie inside the plane polygon.
 * @param minCameraDistance         Camera-to-hit floor in meters. Hits closer than
 *                                  this are dropped (the node keeps its last pose).
 *                                  Default `0.3f` (#1891); pass `null` to disable.
 * @param minCameraDistanceFromPlane Legacy plane-only distance gate. Prefer
 *                                  [minCameraDistance].
 * @param predicate                 Custom filter applied to each candidate [HitResult].
 * @param onHitResultChanged        Invoked whenever the resolved [HitResult] changes
 *                                  (including transitions to / from `null`).
 * @param refreshIntervalMs         Minimum interval, in milliseconds, between two ARCore
 *                                  hit tests. `0` (the default) runs the hit test on every
 *                                  updated frame — the original behaviour; a positive value
 *                                  (e.g. `100` = 10 Hz) rate-limits the raycast. Forwarded
 *                                  to [HitResultNode.refreshIntervalMs], which stays
 *                                  writable afterwards for live changes (#3391).
 */
open class ReticleNode(
    engine: Engine,
    xPx: Float,
    yPx: Float,
    planeTypes: Set<Plane.Type> = Plane.Type.values().toSet(),
    point: Boolean = false,
    depthPoint: Boolean = false,
    instantPlacementPoint: Boolean = false,
    trackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
    pointOrientationModes: Set<Point.OrientationMode> = setOf(
        Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
    ),
    planePoseInPolygon: Boolean = true,
    minCameraDistance: Float? = 0.3f,
    minCameraDistanceFromPlane: Pair<Camera, Float>? = null,
    predicate: ((HitResult) -> Boolean)? = null,
    var onHitResultChanged: ((HitResult?) -> Unit)? = null,
    refreshIntervalMs: Long = 0L
) : HitResultNode(
    engine = engine,
    xPx = xPx,
    yPx = yPx,
    planeTypes = planeTypes,
    point = point,
    depthPoint = depthPoint,
    instantPlacementPoint = instantPlacementPoint,
    trackingStates = trackingStates,
    pointOrientationModes = pointOrientationModes,
    planePoseInPolygon = planePoseInPolygon,
    minCameraDistance = minCameraDistance,
    minCameraDistanceFromPlane = minCameraDistanceFromPlane,
    predicate = predicate,
    refreshIntervalMs = refreshIntervalMs
) {

    /**
     * Overrides [HitResultNode.hitResult] only to fire [onHitResultChanged] when
     * the resolved hit transitions to a different value (including `null`). All
     * positioning behaviour (pose, trackable, smooth transform) is delegated to
     * the [HitResultNode] base setter via `super.hitResult`.
     */
    override var hitResult: HitResult?
        get() = super.hitResult
        set(value) {
            val changed = super.hitResult !== value
            super.hitResult = value
            if (changed) {
                onHitResultChanged?.invoke(value)
            }
        }
}
