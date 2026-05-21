package io.github.sceneview.ar.node

import com.google.android.filament.Engine
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState

/**
 * AR node anchored to a detected ARCore [Plane] (#1774).
 *
 * Sceneform + AR Foundation parity: where AR Foundation surfaces detected planes through
 * `ARPlaneManager` and an `ARPlane` per-plane component, SceneView exposes one [PlaneNode] per
 * tracked [Plane]. The node follows the plane's [Plane.getCenterPose] every frame, so any child
 * content declared in its `content` block (a label, a snapped model, a debug marker) rides the
 * plane as ARCore refines its extents.
 *
 * Planes are otherwise opaque inside [io.github.sceneview.ar.scene.PlaneRenderer]: this node is
 * the supported way to *react* to a plane in the scene graph rather than re-implementing a
 * `frame.getUpdatedTrackables(Plane::class.java)` loop.
 *
 * **Subsumed planes.** When ARCore merges two coplanar overlapping planes the smaller one is
 * subsumed — its [Plane.getTrackingState] goes to [TrackingState.STOPPED] and
 * [io.github.sceneview.ar.arcore.subsumedBy] points at the survivor. A `PlaneNode` for a
 * subsumed plane simply stops being visible (STOPPED ∉ [visibleTrackingStates] by default);
 * apps that want to re-home content should observe the lifecycle from
 * [io.github.sceneview.ar.ARSceneScope.rememberDetectedPlanes].
 *
 * The node's pose is only refreshed while the plane is in [TrackingState.TRACKING] — a paused
 * plane holds its last known pose instead of snapping to identity.
 *
 * @param engine        The Filament [Engine] shared with the parent AR scene.
 * @param plane         The ARCore [Plane] this node tracks.
 * @param visibleTrackingStates States in which the node (and its children) are rendered.
 * @param onTrackingStateChanged Callback when the plane's tracking state changes.
 * @param onUpdated     Callback invoked each frame the plane is updated by ARCore.
 */
open class PlaneNode(
    engine: Engine,
    /**
     * The ARCore [Plane] this node tracks.
     */
    val plane: Plane,
    visibleTrackingStates: Set<TrackingState> = setOf(TrackingState.TRACKING),
    onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
    onUpdated: ((Plane) -> Unit)? = null
) : TrackableNode<Plane>(
    engine = engine,
    visibleTrackingStates = visibleTrackingStates,
    onTrackingStateChanged = onTrackingStateChanged,
    onUpdated = onUpdated
) {

    /**
     * The plane's orientation — horizontal (upward / downward facing) or vertical.
     *
     * @see Plane.getType
     */
    val type: Plane.Type get() = plane.type

    /**
     * The length of this plane's bounding rectangle along the local X axis, in metres.
     *
     * @see Plane.getExtentX
     */
    val extentX: Float get() = plane.extentX

    /**
     * The length of this plane's bounding rectangle along the local Z axis, in metres.
     *
     * @see Plane.getExtentZ
     */
    val extentZ: Float get() = plane.extentZ

    init {
        trackable = plane
    }

    override fun update(trackable: Plane?) {
        super.update(trackable)

        // Only follow the plane while ARCore is actively tracking it. A paused / stopped plane
        // keeps its last pose so subsumed-plane content does not teleport to the AR origin.
        if (plane.trackingState == TrackingState.TRACKING) {
            pose = plane.centerPose
        }
    }
}
