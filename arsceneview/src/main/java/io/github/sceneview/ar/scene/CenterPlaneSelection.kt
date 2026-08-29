package io.github.sceneview.ar.scene

import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.dot
import io.github.sceneview.ar.arcore.displayOrientedPose
import io.github.sceneview.ar.arcore.isTracking
import io.github.sceneview.ar.arcore.position
import io.github.sceneview.ar.arcore.yDirection
import io.github.sceneview.ar.arcore.zDirection
import kotlin.math.abs

/**
 * Centre-of-screen plane selection for [PlaneRenderer.PlaneRendererMode.RENDER_CENTER],
 * computed analytically instead of through `Frame.hitTest` (#3339).
 *
 * ### Why this exists
 *
 * `RENDER_CENTER` only needs to answer one question — *which detected floor plane is the
 * camera looking at?* — so that exactly one plane gets its grid highlighted. Until #3339 it
 * answered it by firing a real ARCore raycast at the centre pixel on every processed frame:
 *
 * ```kotlin
 * frame.hitTest(viewSize.width / 2f, viewSize.height / 2f)
 *     .firstByTypeOrNull(planeTypes = setOf(Plane.Type.HORIZONTAL_UPWARD_FACING))
 * ```
 *
 * `RENDER_CENTER` is the default mode and the plane renderer is on by default, so **every**
 * AR screen ran that raycast continuously. `ARCore` internally attempts a depth sub-test
 * inside `Frame.hitTest`; on devices where the motion-stereo depth pipeline is unavailable
 * that sub-test fails and ARCore's own native logger emits, per call:
 *
 * ```
 * W native: W0000 … session.cc:2805] FAILED_PRECONDITION:
 * W native: ARCoreError: third_party/arcore/ar/core/depth_hit_test.cc:332
 * W native: ARCoreError: vr/perception/depth/projects/motion_stereo/manager/motion_stereo_manager.cc:2029
 * W native: Depth estimation is disabled for the requested depth type.; Error calculating
 *           the depth hit tests. [… 'ArStatusErrorSpace::AR_ERROR_ILLEGAL_STATE']
 * ```
 *
 * Those lines come from ARCore's **native** logger, not from SceneView, so nothing on the
 * Kotlin side can filter them — the `depthPoint = false` result filter runs *after* the
 * native call, and no `Frame.hitTest` overload accepts a trackable-type filter. The only way
 * to stop the noise is to stop making the call. That is what this file does.
 *
 * The failure never removed anything the call site wanted: the result was filtered down to
 * `HORIZONTAL_UPWARD_FACING` planes, and the depth sub-test could only ever have contributed
 * `DepthPoint` candidates, which were discarded anyway.
 *
 * ### Why the approximation is sound here
 *
 * The replacement intersects the camera's optical-axis ray with each candidate plane. That
 * is an approximation of a true centre-pixel unprojection — but the **centre pixel is
 * exactly the case where it is best**. Any centre-preserving crop/fit (which is what the AR
 * camera stream uses) maps the viewport centre to the camera-image centre, so aspect fitting
 * drops out entirely and the only residual is the sub-degree principal-point offset. For
 * deciding *which of a handful of floor planes to highlight*, that is far below the
 * threshold that changes the answer.
 *
 * The maths mirrors [io.github.sceneview.collision.Plane.rayIntersection] — the repo's
 * canonical ray/plane intersection — including its `1e-6` parallel-ray epsilon, but over
 * immutable [Float3] instead of the mutable, allocating `Vector3` / `Ray` / `RayHit` triple.
 *
 * @see PlaneRenderer
 * @see PlaneRendererV2
 */

/**
 * Parallel-ray rejection threshold, matching the repo-canonical
 * `io.github.sceneview.collision.Plane.NEAR_ZERO_THRESHOLD` (also used by
 * `Box.rayIntersection`, `MeshCollider` and `MathHelper`).
 */
internal const val RAY_PLANE_PARALLEL_EPSILON = 1e-6f

/**
 * Distance along [rayDirection] at which the ray meets the infinite plane defined by
 * [planeCenter] / [planeNormal], or `null` when there is no forward intersection.
 *
 * Returns `null` when the ray is parallel to the plane (`|denominator| <=`
 * [RAY_PLANE_PARALLEL_EPSILON]) and when the intersection lies at or behind the ray origin.
 * The `> 0f` test is deliberately strict — a hit exactly at the camera origin is not a
 * surface the user is looking *at* — and is NaN-safe, since every comparison against `NaN`
 * is false.
 *
 * [rayDirection] and [planeNormal] are expected to be unit length (both come from an ARCore
 * [Pose], which is always normalized). Only the *ratio* matters for the returned distance's
 * sign, so a non-unit normal still classifies correctly.
 *
 * @param rayOrigin    Ray origin in world space.
 * @param rayDirection Ray direction in world space, normalized.
 * @param planeCenter  Any point lying on the plane.
 * @param planeNormal  Plane surface normal, normalized.
 * @return Forward hit distance, or `null` if the ray misses.
 */
internal fun rayPlaneDistance(
    rayOrigin: Float3,
    rayDirection: Float3,
    planeCenter: Float3,
    planeNormal: Float3
): Float? {
    val denominator = dot(planeNormal, rayDirection)
    if (abs(denominator) <= RAY_PLANE_PARALLEL_EPSILON) return null
    val distance = dot(planeNormal, planeCenter - rayOrigin) / denominator
    return distance.takeIf { it > 0f }
}

/**
 * Plane geometry reduced to what the ray test needs, so the selection maths stays free of
 * ARCore types (and therefore unit-testable on a plain JVM).
 *
 * @param center Plane centre in world space.
 * @param normal Plane surface normal in world space, normalized.
 */
internal data class PlaneRayCandidate(val center: Float3, val normal: Float3)

/**
 * Index of the [candidates] entry the ray hits first, or `null` when it hits none.
 *
 * Candidates are infinite planes; [isInPolygon] re-imposes the finite boundary and is only
 * invoked for candidates the ray actually meets in front of the origin. It receives the
 * candidate index and the world-space hit point. This mirrors ARCore's own contract, where
 * `HitResult` filtering applies `Plane.isPoseInPolygon` to a hit that already passed the
 * infinite-plane test (see `firstByTypeOrNull`'s `planePoseInPolygon = true` default).
 *
 * Iterates with an index loop rather than `mapIndexedNotNull` / `minByOrNull` so the hot
 * path allocates nothing beyond the hit points themselves.
 *
 * @param rayOrigin    Ray origin in world space.
 * @param rayDirection Ray direction in world space, normalized.
 * @param candidates   Planes to test, in any order.
 * @param isInPolygon  Finite-boundary test for a candidate's hit point.
 * @return Index into [candidates] of the nearest accepted hit, or `null`.
 */
internal fun nearestPlaneAlongRay(
    rayOrigin: Float3,
    rayDirection: Float3,
    candidates: List<PlaneRayCandidate>,
    isInPolygon: (index: Int, hitPoint: Float3) -> Boolean
): Int? {
    var nearestIndex: Int? = null
    var nearestDistance = Float.MAX_VALUE
    for (index in candidates.indices) {
        val candidate = candidates[index]
        val distance = rayPlaneDistance(
            rayOrigin = rayOrigin,
            rayDirection = rayDirection,
            planeCenter = candidate.center,
            planeNormal = candidate.normal
        ) ?: continue
        if (distance >= nearestDistance) continue
        if (!isInPolygon(index, rayOrigin + rayDirection * distance)) continue
        nearestIndex = index
        nearestDistance = distance
    }
    return nearestIndex
}

/**
 * ARCore-facing half of the centre-plane selection: turns a [Frame] plus a set of live
 * planes into "the floor plane the camera is pointed at", with no `Frame.hitTest` call.
 *
 * Kept as its own object so the pure maths above compiles into a separate, ARCore-free class
 * that JVM unit tests can load without the ARCore runtime.
 */
internal object CenterPlaneFinder {

    /**
     * The [Plane] the camera's optical axis meets first, or `null` when the camera is not
     * tracking or no candidate qualifies.
     *
     * Applies the same acceptance rules the replaced
     * `firstByTypeOrNull(planeTypes = setOf(HORIZONTAL_UPWARD_FACING))` call applied through
     * its defaults: [TrackingState.TRACKING] only, `HORIZONTAL_UPWARD_FACING` only, and the
     * hit point inside the plane polygon (`planePoseInPolygon = true`). Subsumed planes are
     * additionally skipped — they are merged into a larger plane and are never rendered by
     * `renderPlane`, so highlighting one would select a plane that is not drawn.
     *
     * The ray is built from [com.google.ar.core.Camera.getDisplayOrientedPose] — the pose
     * SceneView's own KDoc prescribes for "building a screen-space ray for hit-testing" —
     * whose forward axis is `-Z`.
     *
     * Each candidate's `centerPose` is fetched **once** (one JNI round trip per plane per
     * call, at the renderer's ~7.5 Hz gate) and reused for both position and normal.
     *
     * @param frame      Current AR frame.
     * @param candidates Live planes to consider — the renderer passes its updated planes
     *                   unioned with the planes it currently draws.
     * @return The centre plane, or `null`.
     */
    fun find(frame: Frame, candidates: Collection<Plane>): Plane? {
        val camera = frame.camera
        if (!camera.isTracking) return null

        val cameraPose = camera.displayOrientedPose
        val rayOrigin = cameraPose.position
        // ARCore poses are OpenGL-style: the camera looks down its own -Z axis.
        val rayDirection = -cameraPose.zDirection

        val planes = ArrayList<Plane>(candidates.size)
        val geometry = ArrayList<PlaneRayCandidate>(candidates.size)
        for (plane in candidates) {
            if (plane.trackingState != TrackingState.TRACKING) continue
            if (plane.subsumedBy != null) continue
            if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            val centerPose = plane.centerPose
            planes += plane
            geometry += PlaneRayCandidate(
                center = centerPose.position,
                normal = centerPose.yDirection
            )
        }

        val index = nearestPlaneAlongRay(
            rayOrigin = rayOrigin,
            rayDirection = rayDirection,
            candidates = geometry
        ) { candidateIndex, hitPoint ->
            // `isPoseInPolygon` reads only the pose translation, so a translation-only pose
            // is the exact equivalent of what ARCore's own hit-result filtering applies.
            planes[candidateIndex].isPoseInPolygon(
                Pose.makeTranslation(hitPoint.x, hitPoint.y, hitPoint.z)
            )
        }

        return index?.let { planes[it] }
    }
}
