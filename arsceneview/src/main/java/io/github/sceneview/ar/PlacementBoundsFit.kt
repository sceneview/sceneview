package io.github.sceneview.ar

import com.google.android.filament.Box
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.inverse
import io.github.sceneview.ar.arcore.transform
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.math.Transform
import io.github.sceneview.math.centerPosition
import io.github.sceneview.math.halfExtentSize
import io.github.sceneview.math.times
import io.github.sceneview.math.toColumnsFloatArray
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fit of a transformed bounding box's projection within a convex plane polygon.
 *
 * @property marginMeters smallest signed distance to a polygon edge's supporting line, in
 * metres, measured along that edge's inward normal. Positive means clearance, zero means
 * contact, negative means the greatest perpendicular overhang past an edge. This is not the
 * translation needed to make an oversized object fit, nor a Euclidean distance to a corner.
 */
data class PlacementBoundsFit(val marginMeters: Float) {
    /** Whether the whole projected box is contained; touching the boundary is allowed. */
    val fits: Boolean get() = marginMeters >= 0f
}

/**
 * Tests the **whole** model box against a tracked surface's convex polygon, rather than
 * testing only the hit position or the plane's rectangular extents. Pure JVM geometry.
 *
 * All eight corners are accounted for, including the height of a tilted box. Only their
 * projection onto plane-local X/Z matters: this checks surface coverage, not support, room
 * height, collisions, or whether the physical surface extends beyond its tracked polygon.
 *
 * @param boundsCenter authored box centre in model-local coordinates (not the hit position).
 * @param boundsHalfExtent nonnegative half-side lengths in the same coordinates.
 * @param modelToPlane affine model-local to plane-local transform, including the placement
 * pose, pivot offsets, asset rotation and final scale. Plane-local +Y is the surface normal.
 * @param planePolygon flat `[x0, z0, x1, z1, …]` convex polygon in metres, in either winding.
 * Duplicate and collinear vertices are allowed.
 * @return clearance/overhang, or `null` for missing, nonfinite, degenerate or nonconvex
 * geometry, empty bounds, or a non-affine transform. Flat models are supported.
 */
fun placementBoundsFit(
    boundsCenter: Position,
    boundsHalfExtent: Size,
    modelToPlane: Transform,
    planePolygon: FloatArray,
): PlacementBoundsFit? {
    if (!boundsCenter.isFinitePosition() || !boundsHalfExtent.isFinitePosition() ||
        boundsHalfExtent.x < 0f || boundsHalfExtent.y < 0f || boundsHalfExtent.z < 0f ||
        (boundsHalfExtent.x == 0f && boundsHalfExtent.y == 0f && boundsHalfExtent.z == 0f)
    ) return null
    if (!modelToPlane.toColumnsFloatArray().all { it.isFinite() } ||
        modelToPlane.x.w != 0f || modelToPlane.y.w != 0f ||
        modelToPlane.z.w != 0f || modelToPlane.w.w != 1f
    ) return null
    if (planePolygon.size < 6 || planePolygon.size % 2 != 0 ||
        !planePolygon.all { it.isFinite() }
    ) return null

    val area = polygonSignedArea(planePolygon)
    if (area == 0.0) return null
    val winding = if (area > 0.0) 1.0 else -1.0
    val center = modelToPlane * boundsCenter
    if (!center.isFinitePosition()) return null
    var margin = Double.POSITIVE_INFINITY
    for (i in planePolygon.indices step 2) {
        val next = (i + 2) % planePolygon.size
        val ax = planePolygon[i].toDouble()
        val az = planePolygon[i + 1].toDouble()
        val dx = planePolygon[next].toDouble() - ax
        val dz = planePolygon[next + 1].toDouble() - az
        val length = sqrt(dx * dx + dz * dz)
        if (length == 0.0) continue
        val nx = -dz / length * winding
        val nz = dx / length * winding

        // Every vertex must lie on the inward side of every edge: reject concavity and
        // crossings instead of returning a misleading fit for an unsupported polygon.
        for (j in planePolygon.indices step 2) {
            if (winding * (dx * (planePolygon[j + 1] - az) -
                    dz * (planePolygon[j] - ax)) < 0.0
            ) return null
        }

        // Support radius along the inward normal, equivalent to projecting all eight
        // transformed corners. This also handles nonuniform scale, reflection and shear.
        val radius = abs(nx * modelToPlane.x.x + nz * modelToPlane.x.z) * boundsHalfExtent.x +
            abs(nx * modelToPlane.y.x + nz * modelToPlane.y.z) * boundsHalfExtent.y +
            abs(nx * modelToPlane.z.x + nz * modelToPlane.z.z) * boundsHalfExtent.z
        margin = min(margin, nx * (center.x - ax) + nz * (center.z - az) - radius)
    }
    return margin.toFloat().takeIf { it.isFinite() }?.let { PlacementBoundsFit(it) }
}

/**
 * Checks [bounds] (for example, `modelInstance.model.restPoseBoundingBox`) at [modelTransform]
 * against this plane's **current** polygon. [modelTransform] is the complete model-local to
 * world transform; for an unscaled model at a hit, pass `hit.hitPose.transform`. For a composed
 * model, pass its node's `worldTransform`, including its scale and pivot/asset corrections.
 *
 * Returns `null` while the plane is not tracking or the geometry has no usable verdict.
 * Re-evaluate as the polygon grows or the object moves/scales. Instant-placement and depth
 * hits have no plane polygon, so they cannot establish a fit. No placement is gated or moved
 * automatically; the caller decides how to present [PlacementBoundsFit.fits].
 */
fun Plane.placementBoundsFit(bounds: Box, modelTransform: Transform): PlacementBoundsFit? {
    if (trackingState != TrackingState.TRACKING) return null
    return placementBoundsFit(
        boundsCenter = bounds.centerPosition,
        boundsHalfExtent = bounds.halfExtentSize,
        modelToPlane = inverse(centerPose.transform) * modelTransform,
        planePolygon = polygonArray(),
    )
}

private fun Position.isFinitePosition(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

private fun polygonSignedArea(polygon: FloatArray): Double {
    // Translate before summing to avoid cancellation for polygons far from the local origin.
    val originX = polygon[0].toDouble()
    val originZ = polygon[1].toDouble()
    var area = 0.0
    for (i in polygon.indices step 2) {
        val next = (i + 2) % polygon.size
        area += (polygon[i] - originX) * (polygon[next + 1] - originZ) -
            (polygon[next] - originX) * (polygon[i + 1] - originZ)
    }
    return area
}
