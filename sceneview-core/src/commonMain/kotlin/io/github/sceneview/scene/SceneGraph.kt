package io.github.sceneview.scene

import io.github.sceneview.collision.CollisionShape
import io.github.sceneview.collision.Ray
import io.github.sceneview.collision.RayHit
import io.github.sceneview.math.Position
import io.github.sceneview.rendering.SceneNode

/**
 * Result of a ray hit test against a node in the scene graph.
 *
 * @param node The node that was hit.
 * @param distance Distance along the ray to the hit point.
 * @param point World-space position of the hit.
 */
data class HitResult(
    val node: SceneNode,
    val distance: Float,
    val point: Position
)

/**
 * Concrete scene graph manager that tracks nodes, handles parent-child relationships,
 * and dispatches frame updates.
 *
 * Nodes added without a parent become root nodes. Adding a node with a parent
 * attaches it as a child of that parent via [SceneNode.addChildNode].
 */
class SceneGraph {

    private val _rootNodes = mutableListOf<SceneNode>()

    /** Read-only list of top-level (parentless) nodes. */
    val rootNodes: List<SceneNode> get() = _rootNodes

    /**
     * All nodes currently tracked by this graph (roots + all descendants).
     */
    private val allNodes = mutableSetOf<SceneNode>()

    /**
     * Optional collision shape per node, used for [hitTest].
     * Nodes without an entry here are ignored during hit testing.
     */
    private val collisionShapes = mutableMapOf<SceneNode, CollisionShape>()

    /**
     * Adds a node to the scene graph.
     *
     * @param node The node to add.
     * @param parent If non-null, [node] is added as a child of [parent].
     *               If null, [node] becomes a root node.
     */
    fun addNode(node: SceneNode, parent: SceneNode? = null) {
        if (!allNodes.add(node)) return // already tracked

        if (parent != null) {
            parent.addChildNode(node)
        } else {
            _rootNodes.add(node)
        }
        node.onAddedToScene()
    }

    /**
     * Removes a node (and all its descendants) from the scene graph.
     */
    fun removeNode(node: SceneNode) {
        if (!allNodes.remove(node)) return // not tracked

        // Remove descendants recursively
        for (child in node.childNodes.toList()) {
            removeNode(child)
        }

        // Detach from parent
        val parent = node.parent
        if (parent != null) {
            parent.removeChildNode(node)
        } else {
            _rootNodes.remove(node)
        }

        collisionShapes.remove(node)
        node.onRemovedFromScene()
    }

    /**
     * Associates a collision shape with a node for hit testing.
     */
    fun setCollisionShape(node: SceneNode, shape: CollisionShape) {
        collisionShapes[node] = shape
    }

    /**
     * Removes [node]'s collision shape, making it invisible to [hitTest]
     * until a new shape is set. A no-op when the node has no shape. The
     * clearing counterpart of [setCollisionShape] — without it a caller that
     * nulls a node's shape after a hit test would leave the last world-space
     * shape lingering in the graph (#2024 P5c review).
     */
    fun removeCollisionShape(node: SceneNode) {
        collisionShapes.remove(node)
    }

    /**
     * Returns the first node matching [predicate], searched depth-first from roots.
     */
    fun findNode(predicate: (SceneNode) -> Boolean): SceneNode? {
        for (root in _rootNodes) {
            val found = findNodeRecursive(root, predicate)
            if (found != null) return found
        }
        return null
    }

    /**
     * Returns all nodes matching [predicate], searched depth-first from roots.
     */
    fun findAllNodes(predicate: (SceneNode) -> Boolean): List<SceneNode> {
        val results = mutableListOf<SceneNode>()
        for (root in _rootNodes) {
            findAllNodesRecursive(root, predicate, results)
        }
        return results
    }

    /**
     * Dispatches a frame update to every node in the graph.
     *
     * @param deltaTime Seconds elapsed since the previous frame.
     */
    fun dispatchFrame(deltaTime: Float) {
        for (root in _rootNodes) {
            dispatchFrameRecursive(root, deltaTime)
        }
    }

    /**
     * Tests a ray against all nodes that have a collision shape and are hittable.
     *
     * @return Hit results sorted by distance (nearest first).
     */
    fun hitTest(ray: Ray): List<HitResult> {
        val results = mutableListOf<HitResult>()
        for (node in allNodes) {
            if (!node.isVisible || !node.isHittable) continue
            val shape = collisionShapes[node] ?: continue
            val rayHit = RayHit()
            if (shape.rayIntersection(ray, rayHit)) {
                val point = rayHit.getWorldPosition()
                results.add(HitResult(node, rayHit.getDistance(), point))
            }
        }
        results.sortBy { it.distance }
        return results
    }

    // --- Private helpers ---

    private fun findNodeRecursive(node: SceneNode, predicate: (SceneNode) -> Boolean): SceneNode? {
        if (predicate(node)) return node
        for (child in node.childNodes) {
            val found = findNodeRecursive(child, predicate)
            if (found != null) return found
        }
        return null
    }

    private fun findAllNodesRecursive(
        node: SceneNode,
        predicate: (SceneNode) -> Boolean,
        results: MutableList<SceneNode>
    ) {
        if (predicate(node)) results.add(node)
        for (child in node.childNodes) {
            findAllNodesRecursive(child, predicate, results)
        }
    }

    private fun dispatchFrameRecursive(node: SceneNode, deltaTime: Float) {
        node.onFrame(deltaTime)
        for (child in node.childNodes) {
            dispatchFrameRecursive(child, deltaTime)
        }
    }
}
