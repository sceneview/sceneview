package io.github.sceneview.web.nodes

import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.web.bindings.Entity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for the #2024 slice-2 node subtypes ([ModelNode], [GeometryNode]
 * and its primitives) and the slice-2 [Node] hardening (the `isDestroyed`
 * transform-write guard, `adoptChildEntity`).
 *
 * Same recording-fake strategy as [NodeTest]: graph semantics are pure
 * Kotlin; the engine seam is asserted through the recorder. The
 * SceneView-side factories (`addModelNode`/`addCubeNode`/…) need the
 * Filament WASM module and are exercised in-browser by the web-demo
 * Playwright suite instead (the DSL now delegates to them, so every
 * existing `model{}`/`geometry{}` spec drives this path).
 */
class NodeSubtypesTest {

    private class RecordingBackend : NodeBackend {
        var transformWrites = 0
        var destroyCalls = 0
        val adopted = mutableListOf<Entity>()

        override fun setLocalTransform(transform: Transform) {
            transformWrites++
        }

        override fun setParent(parent: NodeBackend?) = Unit

        override fun adoptChildEntity(child: Entity) {
            adopted.add(child)
        }

        override fun destroy() {
            destroyCalls++
        }
    }

    private fun fakeEntity(id: Int): Entity = id.asDynamic().unsafeCast<Entity>()

    // --- isDestroyed transform-write guard (slice-1 review carry-over) ------

    @Test
    fun transformWriteAfterDestroyNeverReachesTheEngine() {
        val backend = RecordingBackend()
        val node = Node(backend)
        val writesBeforeDestroy = backend.transformWrites

        node.destroy()
        node.position = Position(1f, 2f, 3f)
        node.scale = io.github.sceneview.math.Scale(2f)

        assertEquals(
            writesBeforeDestroy, backend.transformWrites,
            "post-destroy transform writes must not reach the engine (freed entity)",
        )
        assertEquals(1, backend.destroyCalls)
    }

    @Test
    fun kotlinStateStillReadableAfterDestroy() {
        val node = Node(RecordingBackend())
        node.position = Position(1f, 0f, 0f)
        node.destroy()
        // The pure-Kotlin state survives (only the engine push is guarded).
        assertEquals(1f, node.position.x)
    }

    // --- adoptChildEntity ----------------------------------------------------

    @Test
    fun adoptChildEntityForwardsToTheBackend() {
        val backend = RecordingBackend()
        val node = Node(backend)
        val assetRoot = fakeEntity(7)

        node.adoptChildEntity(assetRoot)

        assertEquals(1, backend.adopted.size)
        assertSame(assetRoot, backend.adopted[0])
    }

    // --- Subtype contracts ----------------------------------------------------

    @Test
    fun modelNodeStartsWithNullAssetAndIsAGraphNode() {
        val model = ModelNode(RecordingBackend())
        assertNull(model.asset, "asset is null while the async load is in flight")

        // It participates in the hierarchy like any Node.
        val root = Node(RecordingBackend())
        model.parent = root
        assertSame(root, model.parent)
        assertTrue(root.childNodes.contains(model))
    }

    @Test
    fun geometryPrimitivesAreGeometryNodesAreNodes() {
        val cube: GeometryNode = CubeNode(RecordingBackend())
        val sphere: GeometryNode = SphereNode(RecordingBackend())
        val cylinder: GeometryNode = CylinderNode(RecordingBackend())
        val plane: GeometryNode = PlaneNode(RecordingBackend())

        for (node in listOf(cube, sphere, cylinder, plane)) {
            assertNull(node.asset)
            // Each is a real Node — transform state works.
            node.position = Position(0f, 1f, 0f)
            assertEquals(1f, node.position.y)
        }
    }

    @Test
    fun destroyingAModelNodeDoesNotTouchTheAdoptedEntity() {
        val backend = RecordingBackend()
        val model = ModelNode(backend)
        model.adoptChildEntity(fakeEntity(9))

        model.destroy()

        // destroy() frees the node's OWN resources exactly once; the adopted
        // asset entity belongs to the SceneView tracker (slice-2 ownership).
        assertEquals(1, backend.destroyCalls)
        assertEquals(1, backend.adopted.size)
    }
}
