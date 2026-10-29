package io.github.sceneview.utils

import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node

/**
 * A snapshot description of a single [Node] in the scene graph.
 *
 * Useful for debugging and building developer tools. Obtain a full scene snapshot with
 * [inspectScene].
 *
 * @property name       The node's [Node.name], or a generated label like "Node@3f2a".
 * @property type       Simple class name of the node (e.g. "ModelNode", "LightNode").
 * @property position   The node's local position.
 * @property rotation   The node's local rotation in Euler angles (degrees).
 * @property scale      The node's local scale.
 * @property isVisible  Whether the node is visible (accounts for parent visibility).
 * @property childCount Number of direct children.
 * @property children   Recursive snapshots of child nodes.
 * @property extras     Optional type-specific information (e.g. animation count for ModelNode).
 */
@ExperimentalSceneViewApi
data class NodeSnapshot(
    val name: String,
    val type: String,
    val position: Position,
    val rotation: Rotation,
    val scale: Scale,
    val isVisible: Boolean,
    val childCount: Int,
    val children: List<NodeSnapshot>,
    val extras: Map<String, String> = emptyMap()
)

/**
 * Creates a recursive snapshot of the scene graph starting from [rootNodes].
 *
 * This is a diagnostic utility for building debug UIs and logging. It walks the full scene
 * graph and returns a list of [NodeSnapshot]s that describe every node's state at the time
 * of the call.
 *
 * ```kotlin
 * val snapshot = inspectScene(sceneRootNodes)
 * snapshot.forEach { node ->
 *     Log.d("SceneInspector", "${node.type}: ${node.name} at ${node.position}")
 * }
 * ```
 *
 * @param rootNodes The top-level nodes of the scene (typically from `SceneNodeManager`).
 * @return A list of [NodeSnapshot]s representing the full scene tree.
 */
@ExperimentalSceneViewApi
fun inspectScene(rootNodes: Collection<Node>): List<NodeSnapshot> =
    rootNodes.map { snapshotNode(it) }

/**
 * Creates a [NodeSnapshot] for a single [node] and its descendants.
 */
@ExperimentalSceneViewApi
fun snapshotNode(node: Node): NodeSnapshot {
    val extras = buildMap {
        if (node is ModelNode && node.isModelDestroyed) {
            put("model", "destroyed")
        } else if (node is ModelNode) {
            put("animationCount", node.animationCount.toString())
            put("playingAnimations", node.playingAnimations.size.toString())
            put("renderableNodes", node.renderableNodes.size.toString())
            put("boundingBox", "center=${node.center}, extents=${node.extents}")
        }
    }

    return NodeSnapshot(
        name = node.name ?: "Node@${Integer.toHexString(System.identityHashCode(node))}",
        type = node::class.simpleName ?: "Node",
        position = node.position,
        rotation = node.rotation,
        scale = node.scale,
        isVisible = node.isVisible,
        childCount = node.childNodes.size,
        children = node.childNodes.map { snapshotNode(it) },
        extras = extras
    )
}

/**
 * Flattens a list of [NodeSnapshot]s (and their children) into a single flat list.
 *
 * Useful for displaying all nodes in a flat list view.
 */
@ExperimentalSceneViewApi
fun List<NodeSnapshot>.flatten(): List<NodeSnapshot> = buildList {
    fun visit(snapshot: NodeSnapshot) {
        add(snapshot)
        snapshot.children.forEach { visit(it) }
    }
    this@flatten.forEach { visit(it) }
}
