package io.github.sceneview.ar.node

import com.google.android.filament.Engine
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingState

/**
 * AR [Trackable] positioned node.
 *
 * A [Trackable] is something that ARCore can track and that Anchors can be attached to
 */
open class TrackableNode<T : Trackable>(
    engine: Engine,
    /**
     * Set the node to be visible only on those tracking states
     */
    val visibleTrackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
    var onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
    var onUpdated: ((T) -> Unit)? = null
) : PoseNode(engine) {

    /**
     * The node world positioned trackable
     */
    var trackable: T? = null
        set(value) {
            if (field != value) {
                field = value
                update(value)
            }
        }

    /**
     * The TrackingState of this Node.
     *
     * Updated on each frame
     */
    var trackingState = TrackingState.STOPPED
        get() = trackable?.trackingState ?: TrackingState.STOPPED
        protected set(value) {
            if (field != value) {
                field = value
                updateVisibility()
                onTrackingStateChanged?.invoke(value)
            }
        }

    /**
     * Gets the Anchors attached to this node trackable.
     */
    val anchors get() = trackable?.anchors ?: listOf()

    override var isVisible
        get() = super.isVisible && trackingState in visibleTrackingStates
        set(value) {
            super.isVisible = value
        }

    override fun update(session: Session, frame: Frame) {
        super.update(session, frame)

        trackable?.takeIf {
            // #2270: prefer the per-frame batched updated-trackables set computed once by the
            // AR frame driver (O(1) HashSet lookup, no per-node JNI allocation). Fall back to
            // the per-node JNI query when this node's update runs outside that driver so
            // behaviour stays identical in every path.
            it in (frameUpdatedTrackables ?: frame.getUpdatedTrackables(it.javaClass))
        }?.let {
            update(it)
            onUpdated?.invoke(it)
        }
    }

    open fun update(trackable: T?) {
        trackingState = trackable?.trackingState ?: TrackingState.STOPPED
    }

    /**
     * Creates an anchor that is attached to this trackable, using the given initial pose in the
     * world coordinate space.
     *
     * The type of trackable will determine the semantics of attachment and how the anchor's pose
     * will be updated to maintain this relationship. Note that the relative offset between the pose
     * of multiple anchors attached to a trackable may adjust slightly over time as ARCore updates
     * its model of the world.
     *
     * @throws com.google.ar.core.exceptions.NotTrackingException if the trackable's tracking state
     * was not [TrackingState.TRACKING]
     * @throws com.google.ar.core.exceptions.SessionPausedException if the session had been paused.
     * @throws com.google.ar.core.exceptions.ResourceExhaustedException if too many anchors exist.
     * @throws java.lang.IllegalStateException if this trackable doesn't support anchors.
     */
    override fun createAnchor() = runCatching { trackable?.createAnchor(pose) }.getOrNull()

    /**
     * Creates an [AnchorNode] that is attached to this trackable, using the given initial pose in
     * the world coordinate space.
     *
     * The type of trackable will determine the semantics of attachment and how the anchor's pose
     * will be updated to maintain this relationship. Note that the relative offset between the pose
     * of multiple anchors attached to a trackable may adjust slightly over time as ARCore updates
     * its model of the world.
     *
     * @throws com.google.ar.core.exceptions.NotTrackingException if the trackable's tracking state
     * was not [TrackingState.TRACKING]
     * @throws com.google.ar.core.exceptions.SessionPausedException if the session had been paused.
     * @throws com.google.ar.core.exceptions.ResourceExhaustedException if too many anchors exist.
     * @throws java.lang.IllegalStateException if this trackable doesn't support anchors.
     */
    override fun createAnchorNode() = createAnchor()?.let { AnchorNode(engine, it) }
}

