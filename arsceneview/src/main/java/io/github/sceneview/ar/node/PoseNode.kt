package io.github.sceneview.ar.node

import android.view.MotionEvent
import com.google.android.filament.Engine
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.arcore.createAnchor
import io.github.sceneview.ar.arcore.isTracking
import io.github.sceneview.ar.arcore.position
import io.github.sceneview.ar.arcore.quaternion
import io.github.sceneview.gesture.MoveGestureDetector
import io.github.sceneview.node.Node

/**
 * Construct a new ARCore node
 */
open class PoseNode(
    engine: Engine,
    pose: Pose = Pose.IDENTITY,
    var moveHitTest: PoseNode.(Frame, MotionEvent) -> HitResult? = { frame, motionEvent ->
        frame.hitTest(motionEvent).firstOrNull()?.takeIf { it.trackable.isTracking }
    },
    var onPoseChanged: ((Pose) -> Unit)? = null
) : Node(engine) {

    /**
     * The position of the intersection between a ray and detected real-world geometry.
     *
     * The position is the location in space where the ray intersected the geometry.
     * The orientation is a best effort to face the user's device, and its exact definition differs
     * depending on the Trackable that was hit.
     *
     * - [Plane]: X+ is perpendicular to the cast ray and parallel to the plane, Y+ points along the
     * plane normal (up, for [Plane.Type.HORIZONTAL_UPWARD_FACING] planes), and Z+ is parallel to
     * the plane, pointing roughly toward the user's device.
     *
     * - [Point]: Attempt to estimate the normal of the surface centered around the hit test.
     * Surface normal estimation is most likely to succeed on textured surfaces and with camera
     * motion. If [Point.getOrientationMode] returns
     * [Point.OrientationMode.ESTIMATED_SURFACE_NORMAL], then X+ is perpendicular to the cast ray
     * and parallel to the physical surface centered around the hit test, Y+ points along the
     * estimated surface normal, and Z+ points roughly toward the user's device.
     * If [Point.getOrientationMode] returns [Point.OrientationMode.INITIALIZED_TO_IDENTITY], then
     * X+ is perpendicular to the cast ray and points right from the perspective of the user's
     * device, Y+ points up, and Z+ points roughly toward the user's device.
     *
     * - If you wish to retain the location of this pose beyond the duration of a single frame,
     * create an [Anchor] using [createAnchor] to save the pose in a physically consistent way.
     */
    var pose: Pose = pose
        set(value) {
            if (field != value) {
                field = value
                // Write the world translation + rotation from the ARCore Pose components
                // instead of routing through `worldTransform(pose.transform)`, which
                // allocated a fresh FloatArray(16) + a Transform wrap and then decomposed
                // that matrix back into position/quaternion/scale. An ARCore Pose is a
                // rigid transform (no scale), so the prior path also reset any user-set
                // scale to identity every frame — passing only translation + rotation
                // preserves the node's scale. Fires for every AnchorNode / PlaneNode /
                // AugmentedFaceNode / AugmentedImageNode / StreetscapeGeometryNode on every
                // frame (#2266 / umbrella #2263).
                if (isSmoothTransformEnabled) {
                    // HitResultNode / DepthHitResultNode set isSmoothTransformEnabled = true
                    // and reassign `pose` every frame; the old `worldTransform(pose.transform)`
                    // path fed the smooth slerp. Keep that smooth-follow (the component
                    // overload still avoids Pose.transform's FloatArray(16)) (#2296 review).
                    worldTransform(position = value.position, quaternion = value.quaternion)
                } else {
                    // High-frequency non-smooth nodes: fully alloc-free direct TRS write.
                    worldPosition = value.position
                    worldQuaternion = value.quaternion
                }
                onPoseChanged(value)
            }
        }

    /**
     * Is the AR camera tracking.
     *
     * Used for visibility update.`
     */
    open var cameraTrackingState = TrackingState.TRACKING
        protected set(value) {
            if (field != value) {
                field = value
                onCameraTrackingChanged(value)
            }
        }

    /**
     * Set the node to be visible only on those camera tracking states
     */
    var visibleCameraTrackingStates = setOf(TrackingState.TRACKING)
        set(value) {
            field = value
            updateVisibility()
        }

    var session: Session? = null
    var frame: Frame? = null

    // Rotation edition is disabled by default because retrieved from the pose.
    override var isRotationEditable = false

    override var isVisible
        get() = super.isVisible &&
                cameraTrackingState in visibleCameraTrackingStates
        set(value) {
            super.isVisible = value
        }

    init {
        // Seed the initial world transform from the Pose components directly, matching the
        // allocation-free setter path above (#2266).
        worldPosition = pose.position
        worldQuaternion = pose.quaternion

        updateVisibility()
    }

    /**
     * Defines a tracked location in the physical world at the actual [pose].
     *
     * See [Anchor] for more details.
     *
     * Anchors incur ongoing processing overhead within ARCore. To release unneeded anchors use
     * [Anchor.detach].
     *
     * @return the created anchor or null is it couldn't be created
     */
    open fun createAnchor() = runCatching { session?.createAnchor(pose) }.getOrNull()

    /**
     * Defines a tracked [AnchorNode] in the physical world at the actual [pose].
     *
     * See [Anchor] for more details.
     *
     * Anchors incur ongoing processing overhead within ARCore. To release unneeded anchors use
     * [Anchor.detach].
     *
     * @return the created anchor or null is it couldn't be created
     */
    open fun createAnchorNode() = createAnchor()?.let { AnchorNode(engine, it) }

    open fun update(session: Session, frame: Frame) {
        this.session = session
        this.frame = frame
        this.cameraTrackingState = frame.camera.trackingState
    }

    open fun onCameraTrackingChanged(trackingState: TrackingState) {
        updateVisibility()
    }

    open fun onPoseChanged(pose: Pose) {
        onPoseChanged?.invoke(pose)
    }

    override fun onMove(detector: MoveGestureDetector, e: MotionEvent): Boolean {
        return if (isPositionEditable) {
            frame?.let { frame ->
                moveHitTest(frame, e)?.let {
                    onMove(detector, e, it.hitPose)
                }
            } ?: false
        } else {
            super.onMove(detector, e)
            parent?.onMove(detector, e) ?: false
        }
    }

    open fun onMove(
        detector: MoveGestureDetector,
        e: MotionEvent,
        pose: Pose
    ): Boolean {
        return if (onMove?.invoke(detector, e, pose.position) != false) {
            this.pose = pose
            true
        } else {
            false
        }
    }
}
