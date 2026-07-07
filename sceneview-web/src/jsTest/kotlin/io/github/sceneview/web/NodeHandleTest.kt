package io.github.sceneview.web

import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Transform
import io.github.sceneview.web.bindings.Entity
import io.github.sceneview.web.nodes.Node
import io.github.sceneview.web.nodes.NodeBackend
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for the `@JsExport` [NodeHandle] surface (#2024 slice 3 / P4).
 *
 * The handle is a thin delegation over a [Node] (whose graph semantics
 * `NodeTest` covers) plus [NodeHost] callbacks into the viewer. These tests use
 * a [FakeBackend]-backed node and a recording [FakeHost] so the delegation —
 * transform mapping, degrees convention, visibility cascade routing, hierarchy,
 * destroy — is verified without the Filament WASM module (Karma stubs it). The
 * Filament-backed path shares every line; only [SceneViewJS]'s host wiring runs
 * in-browser (Playwright).
 */
class NodeHandleTest {

    private class FakeBackend : NodeBackend {
        var lastTransform: Transform? = null
        var setParentCalls = 0
        var destroyCalls = 0
        override fun setLocalTransform(transform: Transform) { lastTransform = transform }
        override fun setParent(parent: NodeBackend?) { setParentCalls++ }
        override fun adoptChildEntity(child: Entity) { /* not used here */ }
        override fun destroy() { destroyCalls++ }
    }

    private class FakeHost : NodeHost {
        var renderRequests = 0
        val visibilityCascades = mutableListOf<Node>()
        val removedNodes = mutableListOf<Node>()
        override fun requestRender() { renderRequests++ }
        override fun applyNodeVisibility(node: Node) { visibilityCascades.add(node) }
        override fun removeNodeInternal(node: Node) { removedNodes.add(node) }
    }

    private fun handle(host: FakeHost = FakeHost()): Pair<NodeHandle, FakeHost> {
        val node = Node(FakeBackend())
        return NodeHandle(node, host) to host
    }

    private val eps = 1e-4f
    private fun assertClose(expected: Float, actual: Float, message: String) =
        assertTrue(abs(expected - actual) < eps, "$message: expected $expected, was $actual")

    @Test
    fun setPositionMapsToNodePositionAndRepaints() {
        val (h, host) = handle()
        h.setPosition(1.0, 2.0, 3.0)
        assertClose(1f, h.node.position.x, "x")
        assertClose(2f, h.node.position.y, "y")
        assertClose(3f, h.node.position.z, "z")
        assertEquals(1, host.renderRequests, "a mutation requests a repaint")
    }

    @Test
    fun setRotationUsesEulerDegrees() {
        val (h, _) = handle()
        h.setRotation(0.0, 90.0, 0.0)
        // The node stores the equivalent quaternion; its rotation getter returns
        // the same Euler degrees back.
        val expected = Rotation(0f, 90f, 0f)
        assertClose(expected.y, h.node.rotation.y, "yaw degrees")
    }

    @Test
    fun setScaleAndUniformScale() {
        val (h, _) = handle()
        h.setScale(2.0, 3.0, 4.0)
        assertClose(2f, h.node.scale.x, "sx")
        assertClose(3f, h.node.scale.y, "sy")
        assertClose(4f, h.node.scale.z, "sz")
        h.setScaleUniform(5.0)
        assertClose(5f, h.node.scale.x, "uniform x")
        assertClose(5f, h.node.scale.y, "uniform y")
        assertClose(5f, h.node.scale.z, "uniform z")
    }

    @Test
    fun setVisibleFlipsFlagAndRunsCascade() {
        val (h, host) = handle()
        assertTrue(h.visible, "visible by default")
        h.setVisible(false)
        assertFalse(h.node.isVisible, "flag flipped on the node")
        assertFalse(h.visible, "handle reflects the node flag")
        assertEquals(1, host.visibilityCascades.size, "cascade routed to the host")
        assertSame(h.node, host.visibilityCascades.single())
    }

    @Test
    fun addAndRemoveChildWireHierarchy() {
        val host = FakeHost()
        val (parent, _) = handle(host)
        val (child, _) = handle(host)

        parent.addChild(child)
        assertSame(parent.node, child.node.parent, "child parented under the handle's node")
        assertTrue(child.node in parent.node.childNodes)

        parent.removeChild(child)
        assertNull(child.node.parent, "removeChild detaches")
        assertTrue(parent.node.childNodes.isEmpty())
    }

    @Test
    fun getWorldPositionComposesThroughParentAsArray() {
        val host = FakeHost()
        val (parent, _) = handle(host)
        val (child, _) = handle(host)
        parent.setPosition(10.0, 0.0, 0.0)
        child.setPosition(1.0, 0.0, 0.0)
        parent.addChild(child)

        val world = child.getWorldPosition()
        assertEquals(3, world.size, "returns [x, y, z]")
        assertClose(11f, world[0].toFloat(), "world x composes through parent")
        assertClose(0f, world[1].toFloat(), "world y")
        assertClose(0f, world[2].toFloat(), "world z")
    }

    @Test
    fun destroyDetachesFromGraphFreesEntityAndIsIdempotent() {
        val (h, host) = handle()
        val backend = h.node.backend as FakeBackend

        h.destroy()
        assertTrue(h.node.isDestroyed, "node destroyed")
        assertEquals(1, host.removedNodes.size, "detached from the graph via the host")
        assertSame(h.node, host.removedNodes.single())
        assertEquals(1, backend.destroyCalls, "entity freed exactly once")

        // Second destroy is a no-op on the node (isDestroyed guard).
        h.destroy()
        assertEquals(1, backend.destroyCalls, "no double-free")
    }

    @Test
    fun defaultUniformScaleIsOne() {
        val (h, _) = handle()
        assertClose(1f, h.node.scale.x, "default scale")
        // Sanity: a fresh handle is visible and at the origin.
        assertClose(0f, h.node.position.x, "origin x")
        assertTrue(h.visible)
        // A Scale/Position round-trips through Transform composition.
        val t = Transform(Position(1f, 2f, 3f), scale = Scale(2f))
        assertClose(2f, t.scale.x, "transform scale")
    }
}
