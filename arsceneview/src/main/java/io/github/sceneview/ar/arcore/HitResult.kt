package io.github.sceneview.ar.arcore

import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.DepthPoint
import com.google.ar.core.HitResult
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Point.OrientationMode
import com.google.ar.core.TrackingState

/**
 * Returns a list containing only elements matching the given parameters and [predicate].
 */
fun List<HitResult>.filterTypes(
    planeTypes: Set<Plane.Type> = setOf(),
    point: Boolean = false,
    depthPoint: Boolean = false,
    instantPlacementPoint: Boolean = false,
    trackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
    pointOrientationModes: Set<OrientationMode> = setOf(OrientationMode.ESTIMATED_SURFACE_NORMAL),
    planePoseInPolygon: Boolean = true,
    minCameraDistance: Pair<Camera, Float>? = null,
    predicate: ((HitResult) -> Boolean)? = null
) = filter { hitResult ->
    hitResult.isValid(
        planeTypes, point, depthPoint, instantPlacementPoint, trackingStates, pointOrientationModes,
        planePoseInPolygon, minCameraDistance, predicate
    )
}

/**
 * Returns the first element matching the given [predicate], or `null` if element was not found.
 */
fun List<HitResult>.findByType(
    planeTypes: Set<Plane.Type> = setOf(),
    point: Boolean = false,
    depthPoint: Boolean = false,
    instantPlacementPoint: Boolean = false,
    trackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
    pointOrientationModes: Set<OrientationMode> = setOf(OrientationMode.ESTIMATED_SURFACE_NORMAL),
    planePoseInPolygon: Boolean = true,
    minCameraDistance: Pair<Camera, Float>? = null,
    predicate: ((HitResult) -> Boolean)? = null
) = find { hitResult ->
    hitResult.isValid(
        planeTypes, point, depthPoint, instantPlacementPoint, trackingStates, pointOrientationModes,
        planePoseInPolygon, minCameraDistance, predicate
    )
}

/**
 * Returns the first element matching the given [predicate], or `null` if element was not found.
 */
fun List<HitResult>.firstByTypeOrNull(
    planeTypes: Set<Plane.Type> = setOf(),
    point: Boolean = false,
    depthPoint: Boolean = false,
    instantPlacementPoint: Boolean = false,
    trackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
    pointOrientationModes: Set<OrientationMode> = setOf(OrientationMode.ESTIMATED_SURFACE_NORMAL),
    planePoseInPolygon: Boolean = true,
    minCameraDistance: Pair<Camera, Float>? = null,
    predicate: ((HitResult) -> Boolean)? = null
) = firstOrNull { hitResult ->
    hitResult.isValid(
        planeTypes, point, depthPoint, instantPlacementPoint, trackingStates, pointOrientationModes,
        planePoseInPolygon, minCameraDistance, predicate
    )
}

/**
 * Check for hit results first valid on provided criteria.
 *
 * Significant geometric leeway is given when returning hit results. For example, a plane hit may be
 * generated if the ray came close, but did not actually hit within the plane extents or plane
 * bounds [Plane.isPoseInExtents] and [Plane.isPoseInPolygon] can be used to determine these cases).
 */
fun HitResult.isValid(
    planeTypes: Set<Plane.Type> = Plane.Type.values().toSet(),
    point: Boolean = true,
    depthPoint: Boolean = true,
    instantPlacementPoint: Boolean = true,
    trackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
    pointOrientationModes: Set<OrientationMode> = setOf(OrientationMode.ESTIMATED_SURFACE_NORMAL),
    planePoseInPolygon: Boolean = true,
    minCameraDistance: Pair<Camera, Float>? = null,
    predicate: ((HitResult) -> Boolean)? = null
) = when (val trackable = trackable.takeIf { it.trackingState in trackingStates }) {
    is Plane -> trackable.type in planeTypes &&
            (!planePoseInPolygon || trackable.isPoseInPolygon(hitPose)) &&
            minCameraDistance?.let { (camera, minDistance) ->
                hitPose.distanceToPlane(camera.pose) > minDistance
            } ?: true

    is Point -> point && trackable.orientationMode in pointOrientationModes
    is DepthPoint -> depthPoint
    is InstantPlacementPoint -> instantPlacementPoint
    else -> false
}.let { predicate?.invoke(this) ?: it }

/**
 * Creates a new anchor at the hit location.
 *
 * See [HitResult.getHitPose] for details.
 *
 * Anchors incur ongoing processing overhead within ARCore. To release unneeded anchors use
 * [Anchor.detach].
 *
 * This method is a convenience alias for
 * `hitResult.getTrackable().createAnchor(hitResult.getHitPose())`
 *
 * @return `null` if an exception was thrown during anchor creation.
 */
fun HitResult.createAnchorOrNull(): Anchor? = runCatching {
    createAnchor()
}.getOrNull()

/**
 * Distance from the camera to the hit location, in meters.
 *
 * Direct wrapper around [HitResult.getDistance]; exposed as a Kotlin property so apps don't
 * have to write the same one-liner every time they gate placement on a max distance
 * (e.g. `hit.distance < 2f`).
 *
 * This is the camera-to-hit-pose distance as computed by ARCore at the time of the hit
 * test — it is not re-evaluated as the camera moves. Re-run the hit test (or compute
 * `frame.camera.pose.distance(hit.hitPose)` manually) for a live distance.
 */
val HitResult.distance: Float get() = getDistance()
