package io.github.sceneview.web

import io.github.sceneview.collision.Box
import io.github.sceneview.collision.Ray
import io.github.sceneview.collision.TransformProvider
import io.github.sceneview.collision.Vector3
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.math.toMatrix
import io.github.sceneview.scene.SceneGraph
import io.github.sceneview.web.nodes.Node
import io.github.sceneview.web.nodes.NodeBackend
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #2024 P5c — ray-vs-node picking through the core [SceneGraph], with the
 * exact world-shape derivation `SceneView.refreshCollisionShapes` performs
 * (local [Node.collisionShape] transformed by the node's `worldTransform`).
 * The screen-ray half is [ScreenRayTest]; the engine matrix reads are the
 * `kotlin-bundle.spec.ts` P5c probe's job.
 */
class NodeHitTestTest {

    private class FakeBackend : NodeBackend {
        override fun setLocalTransform(transform: Transform) = Unit
        override fun setParent(parent: NodeBackend?) = Unit
        override fun adoptChildEntity(child: io.github.sceneview.web.bindings.Entity) = Unit
        override fun destroy() = Unit
    }

    private fun node(name: String) = Node(FakeBackend()).also { it.name = name }

    /** The refreshCollisionShapes derivation, applied to one node. */
    private fun SceneGraph.refreshShape(node: Node) {
        node.collisionShape?.let { local ->
            setCollisionShape(
                node,
                local.transform(TransformProvider { node.worldTransform.toMatrix() }),
            )
        }
    }

    private val unitBox get() = Box(Vector3(1f, 1f, 1f))

    private val rayAlongX = Ray(Vector3(0f, 0f, 0f), Vector3(1f, 0f, 0f))

    @Test
    fun rayHitsATranslatedNodeAtItsWorldBounds() {
        val graph = SceneGraph()
        val node = node("target")
        node.collisionShape = unitBox
        node.position = Position(5f, 0f, 0f)
        graph.addNode(node)
        graph.refreshShape(node)

        val hits = graph.hitTest(rayAlongX)
        assertEquals(1, hits.size, "one node under the ray")
        assertEquals(node, hits[0].node)
        // Unit box centred at x=5 → near face at 4.5.
        assertTrue(abs(hits[0].distance - 4.5f) < 1e-3f, "distance was ${hits[0].distance}")
    }

    @Test
    fun hitsAreSortedNearestFirst() {
        val graph = SceneGraph()
        val near = node("near").also { it.collisionShape = unitBox; it.position = Position(3f, 0f, 0f) }
        val far = node("far").also { it.collisionShape = unitBox; it.position = Position(8f, 0f, 0f) }
        graph.addNode(far)
        graph.addNode(near)
        graph.refreshShape(near)
        graph.refreshShape(far)

        val hits = graph.hitTest(rayAlongX)
        assertEquals(listOf<Any>(near, far), hits.map { it.node }, "nearest first")
    }

    @Test
    fun scaledParentGrowsTheChildBounds() {
        val graph = SceneGraph()
        val parent = node("group")
        parent.scale = io.github.sceneview.math.Scale(3f)
        val child = node("content")
        child.collisionShape = unitBox
        child.position = Position(2f, 0f, 0f) // world x = 6 under scale 3
        graph.addNode(parent)
        graph.addNode(child, parent)
        graph.refreshShape(child)

        val hits = graph.hitTest(rayAlongX)
        assertEquals(1, hits.size)
        // Unit box scaled ×3 centred at world x=6 → near face at 4.5.
        assertTrue(abs(hits[0].distance - 4.5f) < 1e-3f, "distance was ${hits[0].distance}")
    }

    @Test
    fun nonHittableAndShapelessNodesAreSkipped() {
        val graph = SceneGraph()
        val ghost = node("ghost").also {
            it.collisionShape = unitBox
            it.position = Position(3f, 0f, 0f)
            it.isHittable = false
        }
        val shapeless = node("pivot").also { it.position = Position(5f, 0f, 0f) }
        graph.addNode(ghost)
        graph.addNode(shapeless)
        graph.refreshShape(ghost)
        graph.refreshShape(shapeless)

        assertEquals(0, graph.hitTest(rayAlongX).size)
    }

    @Test
    fun missedRayReturnsEmpty() {
        val graph = SceneGraph()
        val node = node("aside").also { it.collisionShape = unitBox; it.position = Position(0f, 5f, 0f) }
        graph.addNode(node)
        graph.refreshShape(node)

        assertEquals(0, graph.hitTest(rayAlongX).size)
    }
}
