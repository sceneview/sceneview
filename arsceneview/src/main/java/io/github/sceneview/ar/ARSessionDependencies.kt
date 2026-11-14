package io.github.sceneview.ar

import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.node.CloudAnchorNode
import io.github.sceneview.ar.node.HitResultNode
import io.github.sceneview.ar.node.PoseNode
import io.github.sceneview.ar.node.TrackableNode
import io.github.sceneview.node.Node

/** Invalidates SDK-owned references before their session's native handles are released. */
internal fun releaseSessionNodes(roots: List<Node>) {
    val visited = mutableSetOf<Node>()
    fun release(node: Node) {
        if (!visited.add(node) || node.isDestroyed) return
        node.childNodes.forEach(::release)
        if (node is PoseNode) {
            node.session = null
            node.frame = null
            node.lastMoveTrackable = null
            node.frameUpdatedAnchors = null
            node.frameUpdatedTrackables = null
        }
        // Each owner must be invalidated even if another owner's cleanup callback throws.
        try {
            if (node is CloudAnchorNode) node.cancelHost()
        } catch (exception: Exception) {
            android.util.Log.w("ARCore", "Could not cancel a closing session's cloud anchor", exception)
        }
        try {
            when (node) {
                is AnchorNode -> node.releaseSessionAnchor()
                is HitResultNode -> node.hitResult = null
                is TrackableNode<*> -> node.trackable = null
            }
        } catch (exception: Exception) {
            android.util.Log.w("ARCore", "Could not release a closing session's node", exception)
        }
    }
    roots.forEach(::release)
}
