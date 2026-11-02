package io.github.sceneview.ar.replay

import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ready-made [ARSessionProbe] for a replay QA run: provide it through [LocalARSessionProbe] and
 * every `ARSceneView` below replays [playbackDataset] while this records what a test needs to
 * grade the run — read [observe] from the test thread at any time.
 *
 * - frames replayed, and the frame at which the first plane tracked;
 * - anchors currently tracking;
 * - the screen share the visible [ModelNode]s' world bounds project to, and whether the camera
 *   sits inside one of them (a model scaled far too large).
 *
 * Brightness is not measured here: it needs the screen's pixels, which the test reads from a
 * screenshot and feeds to [ARReplayMetrics.meanLuminance] — see [ARReplayObservation].
 *
 * [hideModels] hides every model on each frame: the deliberate failure that proves the coverage
 * check fails when nothing is drawn.
 */
class ARReplayProbe(
    override val playbackDataset: File?,
    @Volatile var hideModels: Boolean = false,
) : ARSessionProbe {

    private val frames = AtomicInteger(0)

    @Volatile private var firstPlaneFrame: Int = -1

    @Volatile private var trackedAnchors: Int = 0

    @Volatile private var visibleModels: Int = 0

    @Volatile private var coverage: ProjectedCoverage? = null

    /** Clears every measure, so one probe can grade several screens in turn. */
    fun reset() {
        frames.set(0)
        firstPlaneFrame = -1
        trackedAnchors = 0
        visibleModels = 0
        coverage = null
    }

    /** What has been measured so far. Thread-safe; brightness fields are left `null`. */
    fun observe(): ARReplayObservation = ARReplayObservation(
        frames = frames.get(),
        firstPlaneFrame = firstPlaneFrame.takeIf { it >= 0 },
        trackedAnchors = trackedAnchors,
        visibleModels = visibleModels,
        coverage = coverage,
    )

    override fun onSessionFrame(session: Session, frame: Frame, nodes: List<Node>) {
        val n = frames.incrementAndGet()
        if (firstPlaneFrame < 0 && hasTrackedPlane(session)) firstPlaneFrame = n
        trackedAnchors = session.allAnchors.count { it.trackingState == TrackingState.TRACKING }

        val models = ArrayList<ModelNode>()
        nodes.forEach { collectModels(it, models) }
        if (hideModels) models.forEach { it.isVisible = false }
        val visible = models.filter { it.isVisible && !it.isModelDestroyed }
        visibleModels = visible.size

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return
        val view = FloatArray(16).also { camera.getViewMatrix(it, 0) }
        val projection = FloatArray(16).also { camera.getProjectionMatrix(it, 0, NEAR, FAR) }
        val viewProjection = ARReplayMetrics.multiply(projection, view)
        val eye = camera.pose.translation
        var total = ProjectedCoverage.NONE
        for (model in visible) {
            val box = model.worldBoundingBox
            val projected = ARReplayMetrics.projectCoverage(
                ARReplayMetrics.boxCorners(box.center, box.halfExtent),
                viewProjection,
            )
            val inside = ARReplayMetrics.isInside(eye, box.center, box.halfExtent)
            total = total.union(projected.copy(cameraInside = inside))
        }
        coverage = total
    }

    private fun hasTrackedPlane(session: Session): Boolean =
        session.getAllTrackables(Plane::class.java).any {
            it.trackingState == TrackingState.TRACKING && it.subsumedBy == null
        }

    private fun collectModels(node: Node, out: MutableList<ModelNode>) {
        if (node is ModelNode) out += node
        node.childNodes.forEach { collectModels(it, out) }
    }

    private companion object {
        const val NEAR = 0.01f
        const val FAR = 100f
    }
}
