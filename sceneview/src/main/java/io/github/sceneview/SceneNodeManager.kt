package io.github.sceneview

import com.google.android.filament.Scene
import io.github.sceneview.collision.CollisionSystem
import io.github.sceneview.node.Node

/**
 * Manages the Filament scene graph for a fully-composable [io.github.sceneview.SceneView] (or
 * [io.github.sceneview.ar.ARSceneView]) without requiring a [SceneView] instance.
 *
 * Replicates the `addNode` / `removeNode` logic from [SceneView] so that the composable surface
 * variants can manage nodes directly against the Filament [SceneView].
 */
class SceneNodeManager(
    val scene: Scene,
    val collisionSystem: CollisionSystem
) {
    // Tracks which nodes are currently registered in the Filament scene so that
    // removeNode() is idempotent — safe to call from both SceneScope.detach()
    // (synchronous, before node.destroy()) and the LaunchedEffect snapshot collector.
    private val managedNodes = mutableSetOf<Node>()

    fun addNode(node: Node) {
        if (!managedNodes.add(node)) return
        node.collisionSystem = collisionSystem
        if (node.sceneEntities.isNotEmpty()) {
            scene.addEntities(node.sceneEntities.toIntArray())
        }
        // Lets Node.destroy() un-register itself if it is destroyed without being removed
        // first — an id recycled while still listed in the scene would resurface as a
        // ghost renderable (#2859).
        node.attachedScene = scene
        node.onChildAdded += ::addNode
        node.onChildRemoved += ::removeNode
        node.onAddedToScene(scene)
        node.childNodes.forEach { addNode(it) }
    }

    fun removeNode(node: Node) {
        if (!managedNodes.remove(node)) return
        node.collisionSystem = null
        if (node.sceneEntities.isNotEmpty()) {
            scene.removeEntities(node.sceneEntities.toIntArray())
        }
        node.attachedScene = null
        node.onChildAdded -= ::addNode
        node.onChildRemoved -= ::removeNode
        node.onRemovedFromScene(scene)
        node.childNodes.forEach { removeNode(it) }
    }

    fun replaceNode(oldNode: Node?, newNode: Node?) {
        oldNode?.let { removeNode(it) }
        newNode?.let { addNode(it) }
    }
}
