package io.github.sceneview.ar.arcore

import com.google.ar.core.Plane
import java.nio.FloatBuffer

/**
 * The 2D vertices of a polygon approximating the detected plane, in the form
 * `[x1, z1, x2, z2, ...]` in plane-local coordinates (X-Z plane, Y = 0).
 *
 * Wraps [Plane.getPolygon] — exposed as a Kotlin property so plane-snapping helpers,
 * plane-merge detection, and custom plane visualizers don't have to redeclare the boilerplate
 * every time.
 *
 * The returned buffer is owned and recycled by ARCore: **do not retain it across frames**.
 * Either consume it immediately or copy the floats into your own buffer.
 *
 * The polygon is empty (`limit() == 0`) until the plane has been tracked for long enough
 * to establish its extents.
 *
 * @see com.google.ar.core.Plane.getPolygon
 */
val Plane.polygon: FloatBuffer get() = getPolygon()

/**
 * The plane that this plane has been subsumed by, or `null` if this plane has not been
 * subsumed.
 *
 * When two trackable planes are detected to be coplanar and overlapping, ARCore merges them
 * — the smaller one is removed from the active tracked-planes list and its
 * [Plane.getSubsumedBy] points at the surviving plane.
 *
 * Apps anchored to a now-subsumed plane should re-anchor onto [subsumedBy] to keep their
 * anchor in sync with the merged geometry. The trackable state of a subsumed plane goes
 * to [com.google.ar.core.TrackingState.STOPPED].
 *
 * @see com.google.ar.core.Plane.getSubsumedBy
 */
val Plane.subsumedBy: Plane? get() = getSubsumedBy()
