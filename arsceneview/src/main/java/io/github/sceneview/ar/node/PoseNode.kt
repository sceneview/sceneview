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
import io.github.sceneview.ar.arcore.transform
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
                worldTransform(pose.transform)
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

    /**
     * Per-frame set of updated trackables/anchors, computed **once** by the AR frame driver
     * (`onARFrame`) and shared across every node in the scene (#2270).
     *
     * Before this batched context existed, [TrackableNode] and [AnchorNode] each called back
     * into ARCore independently every frame (`frame.getUpdatedTrackables(...)` /
     * `frame.updatedAnchors`) — one JNI thunk + a fresh JNI-allocated `List` + an O(n) linear
     * `contains()` scan **per node**. With N nodes that is O(N×M) work and N JNI allocations
     * per frame. Pre-building the membership sets once and handing them to every node turns the
     * per-node check into an O(1) `HashSet.contains()` and collapses the cost to O(N+M).
     *
     * `internal` and nullable on purpose: it is only populated by the in-library frame driver.
     * When a node's [update] is invoked outside that driver (unit tests, imperative third-party
     * code), the field stays `null` and the node falls back to its original per-node JNI lookup,
     * so behaviour is identical in every path — this is a pure performance refactor.
     */
    internal var frameUpdatedTrackables: Set<com.google.ar.core.Trackable>? = null
    internal var frameUpdatedAnchors: Set<com.google.ar.core.Anchor>? = null

    // Rotation edition is disabled by default because retrieved from the pose.
    override var isRotationEditable = false

    override var isVisible
        get() = super.isVisible &&
                cameraTrackingState in visibleCameraTrackingStates
        set(value) {
            super.isVisible = value
        }

    init {
        worldTransform = pose.transform

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
