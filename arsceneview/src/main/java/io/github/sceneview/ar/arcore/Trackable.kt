package io.github.sceneview.ar.arcore

import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingState

/**
 * Whether this [Trackable] is currently in [TrackingState.TRACKING].
 */
val Trackable.isTracking get() = trackingState == TrackingState.TRACKING

/**
 * Creates an anchor that is attached to this trackable, using the given initial pose in the world
 * coordinate space. The type of trackable will determine the semantics of attachment and how the
 * anchor's pose will be updated to maintain this relationship. Note that the relative offset
 * between the pose of multiple anchors attached to a trackable may adjust slightly over time as
 * ARCore updates its model of the world.
 *
 * This is the canonical entry point for the plane tap-to-place pattern — anchor a plane at its
 * own center pose:
 * ```
 * anchor = frame.getUpdatedPlanes()
 *     .firstOrNull { it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
 *     ?.let { it.createAnchorOrNull(it.centerPose) }
 * ```
 * (`Frame` has no `createAnchorOrNull` — the receiver is the [Trackable], here a `Plane`.)
 *
 * @return `null` if an exception was thrown during anchor creation.
 */
fun Trackable.createAnchorOrNull(pose: Pose): Anchor? =
    runCatching { createAnchor(pose) }.getOrNull()