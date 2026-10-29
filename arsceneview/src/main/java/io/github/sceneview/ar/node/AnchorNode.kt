package io.github.sceneview.ar.node

import android.view.MotionEvent
import com.google.android.filament.Engine
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.PlacementTrackableKind
import io.github.sceneview.gesture.MoveGestureDetector

/**
 * Construct a new AR placement Node
 */
open class AnchorNode(
    engine: Engine,
    anchor: Anchor,
    var onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
    onPoseChanged: ((Pose) -> Unit)? = null,
    var onAnchorChanged: ((Anchor) -> Unit)? = null,
    var onUpdated: ((Anchor) -> Unit)? = null
) : PoseNode(engine = engine, pose = anchor.pose, onPoseChanged = onPoseChanged) {

    /**
     * Should the anchor automatically update the anchor new pose
     */
    var updateAnchorPose = true

    /**
     * The node world positioned anchor
     *
     * Describes a fixed location and orientation in the real world. To stay at a fixed location in
     * physical space, the numerical description of this position will update as ARCore's
     * understanding of the space improves.
     *
     * The nose uses [Anchor.getPose] to get the current numerical location of this anchor. This
     * location may change any time [Session.update] is called, but will never spontaneously change.
     *
     * mathematically:
     * `point_world = anchor.getPose().transformPoint(point_local);}`
     * `point_world = anchor.getPose().toMatrix() * point_local;`
     *
     * [Anchor]s are hashable and may for example be used as keys in [Map].
     *
     * Anchors incur ongoing processing overhead within ARCore. To release unneeded anchors use
     * [destroy].
     *
     * Reassigning this property detaches the previously held anchor — the node owns the
     * lifecycle of its anchor. An anchor still attached to the [Session] keeps accruing
     * per-frame tracking cost, so a caller that wants to keep the old anchor must retain a
     * reference and re-attach it before this setter runs (#2043). `Anchor.detach()` is
     * idempotent, so the internal move-gesture path (which already detaches in
     * [onMoveBegin]) is safe.
     */
    var anchor: Anchor = anchor
        set(value) {
            if (field == value) return
            // Detach the replaced anchor so it stops accruing ARCore tracking cost.
            field.detach()
            field = value
            trackingState = value.trackingState
            pose = value.pose
            onAnchorChanged?.invoke(value)
        }

    /**
     * The anchor [TrackingState] of this Node.
     *
     * Updated on each frame
     */
    open var trackingState = anchor.trackingState
        get() = anchor.trackingState
        protected set(value) {
            if (field != value) {
                field = value
                updateVisibility()
                onTrackingStateChanged?.invoke(value)
            }
        }

    /**
     * Set the node to be visible only on those tracking states
     */
    var visibleTrackingStates = setOf(TrackingState.TRACKING)
        set(value) {
            field = value
            updateVisibility()
        }

    /**
     * Whether the anchor can be moved by drag gestures.
     *
     * Like every editable flag, it is gated by [isEditable]: a move gesture detaches and
     * re-creates the anchor only when both [isEditable] and this flag are `true`.
     */
    override var isPositionEditable = true
        get() = isEditable && field

    override var isVisible
        get() = super.isVisible && (trackingState in visibleTrackingStates || isMoving)
        set(value) {
            super.isVisible = value
        }

    private var isMoving = false

    /**
     * The finger is up but the dragged node has no anchor yet: the camera was not tracking at
     * release. The node stays frozen at its world pose and [update] retries every frame.
     */
    private var awaitsReleaseAnchor = false

    init {
        updateVisibility()
    }

    override fun update(session: Session, frame: Frame) {
        super.update(session, frame)

        if (awaitsReleaseAnchor && anchorAtRelease()) {
            awaitsReleaseAnchor = false
            isMoving = false
        }
        if (isMoving) return

        // #2270: prefer the per-frame batched updated-anchors set computed once by the AR frame
        // driver (O(1) HashSet lookup, no per-node JNI allocation). Fall back to the per-node
        // JNI query when this node's update runs outside that driver so behaviour is identical.
        if (anchor in (frameUpdatedAnchors ?: frame.updatedAnchors)) {
            trackingState = anchor.trackingState
            if (trackingState == TrackingState.TRACKING && updateAnchorPose) {
                pose = anchor.pose
            }
            onUpdated?.invoke(anchor)
        }
    }

    open fun detachAnchor() {
        anchor.detach()
    }

    override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent): Boolean {
        super.onMoveBegin(detector, e)

        if (isPositionEditable) {
            detachAnchor()
            isMoving = true
            awaitsReleaseAnchor = false
            lastMoveTrackable = null
        }
        return true
    }

    override fun onMoveEnd(detector: MoveGestureDetector, e: MotionEvent) {
        super.onMoveEnd(detector, e)

        if (isPositionEditable) {
            if (anchorAtRelease()) {
                isMoving = false
            } else {
                // No anchor can be made this frame. Keep the node frozen and visible where the
                // finger left it rather than on the detached (STOPPED) anchor; update() retries.
                awaitsReleaseAnchor = true
            }
        }
    }

    /**
     * Re-anchors the node at the end of a drag, where the finger left it.
     *
     * On the tracked plane the last move hit landed on when there is one, so the model follows
     * the plane as ARCore refines it; otherwise on a plain world anchor at the node's current
     * pose. Never on an Instant Placement point: an approximate anchor rides the camera until
     * ARCore converges, which is the very drift a release must not reintroduce.
     *
     * @return `true` once the node holds its new anchor.
     */
    private fun anchorAtRelease(): Boolean {
        val plane = (lastMoveTrackable as? Plane)?.let { topPlane(it) }
        val decision = runCatching {
            dragReleaseAnchor(
                lastHitKind = when {
                    plane == null -> null
                    plane.isPoseInPolygon(pose) -> PlacementTrackableKind.PLANE_IN_POLYGON
                    else -> PlacementTrackableKind.PLANE_OUT_OF_POLYGON
                },
                lastHitTracking = plane?.trackingState == TrackingState.TRACKING,
                cameraTracking = cameraTrackingState == TrackingState.TRACKING,
            )
        }.getOrDefault(DragReleaseAnchor.WAIT)
        val newAnchor = when (decision) {
            DragReleaseAnchor.PLANE ->
                runCatching { plane?.createAnchor(pose) }.getOrNull() ?: createAnchor()
            DragReleaseAnchor.WORLD_POSE -> createAnchor()
            DragReleaseAnchor.WAIT -> null
        } ?: return false
        anchor = newAnchor
        lastMoveTrackable = null
        return true
    }

    override fun destroy() {
        // Once only (#4026): `Anchor.detach()` is a native call through its session, and a
        // second destroy() — a parent node tearing down its children after the composable
        // already did — can come after that session was closed.
        if (isDestroyed) return
        detachAnchor()

        super.destroy()
    }
}

/** A plane ARCore merged into a bigger one hands its anchors to that one. */
private tailrec fun topPlane(plane: Plane): Plane {
    val parent = plane.subsumedBy ?: return plane
    return topPlane(parent)
}

/** What [AnchorNode] re-anchors on when a drag ends. */
internal enum class DragReleaseAnchor {
    /** The tracked plane under the node: the anchor follows the plane as ARCore refines it. */
    PLANE,

    /** A plain world anchor at the node's pose at release: the model stays where it was left. */
    WORLD_POSE,

    /** The camera is not tracking, so no anchor can be made: hold the pose and try next frame. */
    WAIT,
}

/**
 * The anchor a released drag gets — the pure core of `AnchorNode.anchorAtRelease`.
 *
 * There is deliberately no Instant Placement outcome: whatever the drag landed on, an
 * [PlacementTrackableKind.INSTANT_PLACEMENT] hit included, the result is never an approximate
 * anchor that would follow the camera until ARCore reaches full tracking.
 *
 * @param lastHitKind     What the last accepted move hit landed on, `null` when none did.
 * @param lastHitTracking Whether that trackable is still `TRACKING` at release.
 * @param cameraTracking  Whether the camera is `TRACKING`; no anchor can be made otherwise.
 */
internal fun dragReleaseAnchor(
    lastHitKind: PlacementTrackableKind?,
    lastHitTracking: Boolean,
    cameraTracking: Boolean,
): DragReleaseAnchor = when {
    !cameraTracking -> DragReleaseAnchor.WAIT
    lastHitKind == PlacementTrackableKind.PLANE_IN_POLYGON && lastHitTracking -> DragReleaseAnchor.PLANE
    else -> DragReleaseAnchor.WORLD_POSE
}
