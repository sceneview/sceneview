package io.github.sceneview.ar

import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.github.sceneview.haptic.ARHapticEvent
import io.github.sceneview.math.Position
import kotlin.math.hypot

/** Device-free timing and size policy shared with iOS's ARInstantPlacement. */
internal class AutoPlacementInstantPlacement {
    private var trackingSince: Long? = null

    fun reset() { trackingSince = null }

    fun ready(nowMillis: Long, tracking: Boolean): Boolean {
        if (!tracking) {
            reset()
            return false
        }
        val since = trackingSince ?: nowMillis.also { trackingSince = it }
        return nowMillis - since >= DELAY_MS
    }

    companion object {
        const val DELAY_MS = 600L

        fun mode(enabled: Boolean, surface: PlacementSurface): Config.InstantPlacementMode =
            if (enabled && surface == PlacementSurface.SURFACE) Config.InstantPlacementMode.LOCAL_Y_UP
            else Config.InstantPlacementMode.DISABLED

        /** Largest displayed side in metres, including the preview scale (same formula as iOS). */
        fun reach(side: Float): Float =
            if (side.isFinite() && side > 0f) (0.9f + 0.8f * side).coerceIn(1f, 3f) else 1f

        /** Centre-ray heading on the estimated floor, never a point floating along the ray. */
        fun floorPoint(eye: Position, direction: Position, floorY: Float, side: Float): Position? {
            val heading = hypot(direction.x, direction.z)
            if (!heading.isFinite() || heading <= 0.001f || !floorY.isFinite() ||
                !eye.x.isFinite() || !eye.y.isFinite() || !eye.z.isFinite() || !direction.y.isFinite()
            ) return null
            val reach = reach(side)
            val t = (floorY - eye.y) / direction.y
            val distance = if (direction.y < -0.0001f && t > 0f) {
                (t * heading).coerceIn(reach * 0.6f, reach)
            } else reach
            return Position(eye.x + direction.x / heading * distance, floorY,
                eye.z + direction.z / heading * distance)
        }
    }
}

/** Detected geometry always wins; the opt-in fallback never runs for walls. */
internal fun findAutoPlacementCandidate(
    frame: Frame,
    planes: Collection<Plane>,
    width: Int,
    height: Int,
    surface: PlacementSurface,
    instantPlacement: Boolean,
    instantReady: Boolean,
    side: Float,
): AutoPlacementCandidate? = findAutoPlacementSurface(frame, planes, width, height, surface)
    ?: if (instantPlacement && surface == PlacementSurface.SURFACE && instantReady) {
        findInstantPlacementSurface(frame, planes, width, height, side)
    } else null

/** Keep precision observable even during tracking loss; land only after gestures have ended. */
internal fun updateInstantPlacement(
    placement: AutoPlacementResult,
    planes: Collection<Plane>,
    state: AutoPlacementState,
    tracking: Boolean,
    initial: Boolean = false,
) {
    val point = placement.trackable as? InstantPlacementPoint
    state.onTrackingMethod(point?.trackingMethod ?: InstantPlacementPoint.TrackingMethod.FULL_TRACKING, initial)
    if (tracking && point?.trackingState == TrackingState.TRACKING &&
        point.trackingMethod == InstantPlacementPoint.TrackingMethod.FULL_TRACKING &&
        placement.anchor.trackingState == TrackingState.TRACKING && !state.isAdjusting &&
        landInstantPlacement(placement, placement.anchor.pose, planes)
    ) state.gestureHapticSink?.invoke(ARHapticEvent.Placed)
}

/** ARCore owns the estimate and its refinement; the distance guess puts it on the floor. */
internal fun findInstantPlacementSurface(
    frame: Frame,
    planes: Collection<Plane>,
    width: Int,
    height: Int,
    side: Float,
): AutoPlacementCandidate? {
    if (width <= 0 || height <= 0 || frame.camera.trackingState != TrackingState.TRACKING) return null
    val camera = frame.camera.displayOrientedPose
    val eye = Position(camera.tx(), camera.ty(), camera.tz())
    val direction = camera.getTransformedAxis(2, -1f).let { Position(it[0], it[1], it[2]) }
    val floorY = planes.filter {
        it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING &&
            it.subsumedBy == null && it.centerPose.ty() < eye.y - 0.4f
    }.minOfOrNull { it.centerPose.ty() } ?: (eye.y - 1.3f)
    val point = AutoPlacementInstantPlacement.floorPoint(eye, direction, floorY, side) ?: return null
    val ndc = projectPlacementPoint(frame, point) ?: return null
    val distance = ViewportProjection.distance(eye.x, eye.y, eye.z, point.x, point.y, point.z)
    val hit = runCatching {
        frame.hitTestInstantPlacement((ndc.x + 1f) * width / 2f, (1f - ndc.y) * height / 2f, distance)
    }.getOrNull()?.firstOrNull {
        it.trackable is InstantPlacementPoint && it.trackable.trackingState == TrackingState.TRACKING
    } ?: return null
    return AutoPlacementCandidate(hit.trackable, hit.hitPose, null)
}

/** The estimated point's own screen position, rather than the camera's new centre ray. */
private fun projectPlacementPoint(frame: Frame, point: Position): Ndc? {
    val view = FloatArray(16).also { frame.camera.getViewMatrix(it, 0) }
    val projection = FloatArray(16).also { frame.camera.getProjectionMatrix(it, 0, 0.1f, 100f) }
    return ViewportProjection.project(ViewportProjection.multiply(projection, view), point.x, point.y, point.z)
        ?.takeIf { it.isInsideViewport }
}

/**
 * Land at the refined instant point, retaining its yaw. A failed anchor creation leaves the
 * instant anchor alive for a later frame; never jump to an unrelated plane under the camera.
 */
internal fun landInstantPlacement(
    placement: AutoPlacementResult,
    refinedPose: Pose,
    planes: Collection<Plane>,
): Boolean {
    for (plane in planes) {
        if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING || plane.subsumedBy != null ||
            plane.trackingState != TrackingState.TRACKING
        ) continue
        val local = plane.centerPose.inverse().compose(refinedPose)
        if (kotlin.math.abs(local.ty()) > 0.1f) continue
        val position = plane.centerPose.transformPoint(floatArrayOf(local.tx(), 0f, local.tz()))
        val target = Pose(position, refinedPose.rotationQuaternion)
        val next = AutoPlacementCandidate(plane, target).createAnchor() ?: continue
        val previous = placement.anchor
        placement.trackable = next.trackable
        placement.pose = next.pose
        placement.anchor = next.anchor
        previous.detach()
        return true
    }
    return false
}
