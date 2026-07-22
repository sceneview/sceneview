package io.github.sceneview.scene

import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.collision.Ray
import io.github.sceneview.collision.Sphere
import io.github.sceneview.collision.Vector3
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Transform
import io.github.sceneview.rendering.SceneNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Minimal [SceneNode] implementation for testing.
 */
private class TestNode(override var name: String? = null) : SceneNode {
    override var isVisible: Boolean = true
    override var isHittable: Boolean = true

    override var position: Position = Position()
    override var quaternion: Quaternion = Quaternion()
    override var rotation: Rotation = Rotation()
    override var scale: Scale = Scale(1f)
    override var transform: Transform = Transform()

    override var worldPosition: Position = Position()
    override var worldQuaternion: Quaternion = Quaternion()
    override var worldRotation: Rotation = Rotation()
    override var worldScale: Scale = Scale(1f)
    override var worldTransform: Transform = Transform()

    override var parent: SceneNode? = null
    private val _childNodes = mutableSetOf<SceneNode>()
    override val childNodes: Set<SceneNode> get() = _childNodes

    override fun addChildNode(node: SceneNode) {
        _childNodes.add(node)
        (node as TestNode).parent = this
    }

    override fun removeChildNode(node: SceneNode) {
        _childNodes.remove(node)
        (node as TestNode).parent = null
    }

    override fun lookAt(targetWorldPosition: Position, upDirection: Direction) {}
    override fun lookTowards(lookDirection: Direction, upDirection: Direction) {}

    var addedToScene = false
    var removedFromScene = false
    var frameCount = 0
    var lastDeltaTime = 0f

    override fun onAddedToScene() {
        addedToScene = true
    }

    override fun onRemovedFromScene() {
        removedFromScene = true
    }

    override fun onFrame(deltaTime: Float) {
        frameCount++
        lastDeltaTime = deltaTime
    }

    override fun destroy() {}
}

class SceneGraphTest {

    @Test
    fun addRootNode() {
        val graph = SceneGraph()
        val node = TestNode("A")
        graph.addNode(node)

        assertEquals(1, graph.rootNodes.size)
        assertEquals(node, graph.rootNodes[0])
        assertTrue(node.addedToScene)
    }

    @Test
    fun addChildNode() {
        val graph = SceneGraph()
        val parent = TestNode("parent")
        val child = TestNode("child")
        graph.addNode(parent)
        graph.addNode(child, parent)

        assertEquals(1, graph.rootNodes.size, "Only the parent should be a root")
        assertTrue(parent.childNodes.contains(child))
        assertEquals(parent, child.parent)
    }

    @Test
    fun removeRootNode() {
        val graph = SceneGraph()
        val node = TestNode("A")
        graph.addNode(node)
        graph.removeNode(node)

        assertEquals(0, graph.rootNodes.size)
        assertTrue(node.removedFromScene)
    }

    @Test
    fun removeNodeAlsoRemovesDescendants() {
        val graph = SceneGraph()
        val parent = TestNode("parent")
        val child = TestNode("child")
        graph.addNode(parent)
        graph.addNode(child, parent)

        graph.removeNode(parent)
        assertEquals(0, graph.rootNodes.size)
        assertTrue(parent.removedFromScene)
        assertTrue(child.removedFromScene)
    }

    @Test
    fun findNodeByName() {
        val graph = SceneGraph()
        val a = TestNode("alpha")
        val b = TestNode("beta")
        val c = TestNode("gamma")
        graph.addNode(a)
        graph.addNode(b)
        graph.addNode(c, a) // child of a

        val found = graph.findNode { it.name == "gamma" }
        assertNotNull(found)
        assertEquals("gamma", found.name)
    }

    @Test
    fun findNodeReturnsNullWhenNotFound() {
        val graph = SceneGraph()
        graph.addNode(TestNode("alpha"))

        assertNull(graph.findNode { it.name == "missing" })
    }

    @Test
    fun findAllNodes() {
        val graph = SceneGraph()
        val a = TestNode("match")
        val b = TestNode("other")
        val c = TestNode("match")
        graph.addNode(a)
        graph.addNode(b)
        graph.addNode(c, a)

        val results = graph.findAllNodes { it.name == "match" }
        assertEquals(2, results.size)
    }

    @Test
    fun dispatchFrameCallsOnFrame() {
        val graph = SceneGraph()
        val parent = TestNode("parent")
        val child = TestNode("child")
        graph.addNode(parent)
        graph.addNode(child, parent)

        graph.dispatchFrame(0.016f)

        assertEquals(1, parent.frameCount)
        assertEquals(0.016f, parent.lastDeltaTime)
        assertEquals(1, child.frameCount)
        assertEquals(0.016f, child.lastDeltaTime)
    }

    @Test
    fun dispatchFrameMultipleTimes() {
        val graph = SceneGraph()
        val node = TestNode("A")
        graph.addNode(node)

        graph.dispatchFrame(0.016f)
        graph.dispatchFrame(0.032f)

        assertEquals(2, node.frameCount)
        assertEquals(0.032f, node.lastDeltaTime)
    }

    @Test
    fun hitTestReturnsSortedByDistance() {
        val graph = SceneGraph()

        // Near node at z = -2
        val near = TestNode("near")
        graph.addNode(near)
        val nearSphere = Sphere(1f, Vector3(0f, 0f, -2f))
        graph.setCollisionShape(near, nearSphere)

        // Far node at z = -10
        val far = TestNode("far")
        graph.addNode(far)
        val farSphere = Sphere(1f, Vector3(0f, 0f, -10f))
        graph.setCollisionShape(far, farSphere)

        // Ray from origin looking along -Z
        val ray = Ray(Vector3(0f, 0f, 0f), Vector3(0f, 0f, -1f))
        val results = graph.hitTest(ray)

        assertEquals(2, results.size, "Ray should hit both spheres")
        assertEquals("near", results[0].node.name, "Nearest hit should come first")
        assertEquals("far", results[1].node.name, "Farthest hit should come second")
        assertTrue(results[0].distance < results[1].distance)
    }

    @Test
    fun removeCollisionShapeMakesTheNodeInvisibleToHitTest() {
        val graph = SceneGraph()
        val node = TestNode("target")
        graph.addNode(node)
        graph.setCollisionShape(node, Sphere(1f, Vector3(0f, 0f, -2f)))

        val ray = Ray(Vector3(0f, 0f, 0f), Vector3(0f, 0f, -1f))
        assertEquals(1, graph.hitTest(ray).size, "Shape set — the node must be hit")

        graph.removeCollisionShape(node)
        assertTrue(
            graph.hitTest(ray).isEmpty(),
            "Shape removed — the node must no longer be hit"
        )
        // Idempotent on a node with no shape.
        graph.removeCollisionShape(node)
    }

    @Test
    fun hitTestSkipsNonHittableNodes() {
        val graph = SceneGraph()
        val node = TestNode("hidden")
        node.isHittable = false
        graph.addNode(node)
        graph.setCollisionShape(node, Sphere(1f, Vector3(0f, 0f, -2f)))

        val ray = Ray(Vector3(0f, 0f, 0f), Vector3(0f, 0f, -1f))
        val results = graph.hitTest(ray)
        assertTrue(results.isEmpty(), "Non-hittable nodes should be skipped")
    }

    @Test
    fun hitTestSkipsInvisibleNodes() {
        val graph = SceneGraph()
        val node = TestNode("invisible")
        node.isVisible = false
        graph.addNode(node)
        graph.setCollisionShape(node, Sphere(1f, Vector3(0f, 0f, -2f)))

        val ray = Ray(Vector3(0f, 0f, 0f), Vector3(0f, 0f, -1f))
        val results = graph.hitTest(ray)
        assertTrue(results.isEmpty(), "Invisible nodes should be skipped")
    }
}
