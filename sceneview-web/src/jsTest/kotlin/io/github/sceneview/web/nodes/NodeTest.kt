package io.github.sceneview.web.nodes

import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.lookAt
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Transform
import io.github.sceneview.math.quaternion
import io.github.sceneview.math.toQuaternion
import io.github.sceneview.scene.SceneGraph
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for the web [Node] scene graph (#2024, slice 1): hierarchy,
 * local/world transform composition, reparenting, and recursive destroy.
 *
 * Nodes are backed by a recording [FakeBackend] instead of the Filament
 * `TransformManager` — the graph semantics under test are pure Kotlin
 * (`sceneview-core` math), while the engine-side calls (`setLocalTransform`,
 * `setParent`, `destroy`) are asserted through the recorder. The
 * Filament-backed path shares every line of graph code; only the thin
 * [FilamentNodeBackend] marshalling is exercised in-browser instead.
 */
class NodeTest {

    private class FakeBackend : NodeBackend {
        var lastTransform: Transform? = null
        var transformWrites = 0
        var parentBackend: NodeBackend? = null
        var setParentCalls = 0
        var destroyCalls = 0
        var adoptedEntities = mutableListOf<io.github.sceneview.web.bindings.Entity>()

        override fun setLocalTransform(transform: Transform) {
            lastTransform = transform
            transformWrites++
        }

        override fun setParent(parent: NodeBackend?) {
            parentBackend = parent
            setParentCalls++
        }

        override fun adoptChildEntity(child: io.github.sceneview.web.bindings.Entity) {
            adoptedEntities.add(child)
        }

        override fun destroy() {
            destroyCalls++
        }
    }

    private fun node(name: String? = null) = Node(FakeBackend()).also { it.name = name }

    private val Node.fake get() = backend as FakeBackend

    // --- Float comparison helpers ------------------------------------------

    private val eps = 1e-4f

    private fun assertClose(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) < eps, "$message: expected $expected, was $actual")
    }

    private fun assertClose(expected: Position, actual: Position, message: String = "position") {
        assertClose(expected.x, actual.x, "$message.x")
        assertClose(expected.y, actual.y, "$message.y")
        assertClose(expected.z, actual.z, "$message.z")
    }

    /** q and -q encode the same rotation → compare |dot| against 1. */
    private fun assertSameRotation(expected: Quaternion, actual: Quaternion, message: String = "quaternion") {
        assertTrue(
            abs(dot(expected, actual)) > 1f - eps,
            "$message: expected $expected, was $actual"
        )
    }

    private fun assertClose(expected: Transform, actual: Transform, message: String = "transform") {
        val e = expected.toFloatArray()
        val a = actual.toFloatArray()
        for (i in e.indices) assertClose(e[i], a[i], "$message[$i]")
    }

    // --- Defaults -----------------------------------------------------------

    @Test
    fun defaultsAreIdentity() {
        val node = node()
        assertClose(Position(0f), node.position)
        assertClose(Position(1f), node.scale, "scale")
        assertSameRotation(Quaternion(), node.quaternion)
        assertClose(Transform(), node.transform)
        assertClose(Transform(), node.worldTransform, "worldTransform")
        assertNull(node.parent)
        assertTrue(node.childNodes.isEmpty())
        assertTrue(node.isVisible)
        assertTrue(node.isHittable)
        // The constructor aligns the engine matrix with the Kotlin state.
        assertEquals(1, node.fake.transformWrites)
        assertClose(Transform(), node.fake.lastTransform!!, "pushed transform")
    }

    // --- Local TRS <-> transform ---------------------------------------------

    @Test
    fun componentsComposeIntoTransformAndPushToBackend() {
        val node = node()
        node.position = Position(1f, 2f, 3f)
        node.rotation = Rotation(0f, 90f, 0f)
        node.scale = Scale(2f)

        // Getters return the pristine values (no decomposition round-trip).
        assertClose(Position(1f, 2f, 3f), node.position)
        assertClose(Position(2f, 2f, 2f), node.scale, "scale")
        assertSameRotation(Rotation(0f, 90f, 0f).toQuaternion(), node.quaternion)

        val expected = Transform(
            position = Position(1f, 2f, 3f),
            quaternion = Rotation(0f, 90f, 0f).toQuaternion(),
            scale = Scale(2f)
        )
        assertClose(expected, node.transform)
        // Every component write pushed the composed matrix to the engine.
        assertClose(expected, node.fake.lastTransform!!, "pushed transform")
        assertEquals(4, node.fake.transformWrites) // init + 3 setters
    }

    @Test
    fun transformSetterDecomposesToComponents() {
        val node = node()
        node.transform = Transform(
            position = Position(4f, 5f, 6f),
            quaternion = Rotation(0f, 0f, 90f).toQuaternion(),
            scale = Scale(3f)
        )
        assertClose(Position(4f, 5f, 6f), node.position)
        assertClose(Position(3f, 3f, 3f), node.scale, "scale")
        assertSameRotation(Rotation(0f, 0f, 90f).toQuaternion(), node.quaternion)
    }

    // --- Hierarchy ------------------------------------------------------------

    @Test
    fun addChildNodeWiresBothSidesAndBackend() {
        val parent = node("parent")
        val child = node("child")

        parent.addChildNode(child)

        assertSame(parent, child.parent)
        assertTrue(child in parent.childNodes)
        // The Filament-side hierarchy followed (TransformManager.setParent).
        assertSame(parent.fake, child.fake.parentBackend)
        assertEquals(1, child.fake.setParentCalls)
    }

    @Test
    fun reparentingMovesBetweenParents() {
        val a = node("a")
        val b = node("b")
        val child = node("child")

        a.addChildNode(child)
        b.addChildNode(child)

        assertSame(b, child.parent)
        assertTrue(child !in a.childNodes, "child must leave the old parent")
        assertTrue(child in b.childNodes)
        assertSame(b.fake, child.fake.parentBackend)
    }

    @Test
    fun removeChildNodeDetaches() {
        val parent = node("parent")
        val child = node("child")
        parent.addChildNode(child)

        parent.removeChildNode(child)

        assertNull(child.parent)
        assertTrue(parent.childNodes.isEmpty())
        assertNull(child.fake.parentBackend)
    }

    @Test
    fun removeChildNodeIgnoresForeignChild() {
        val parent = node("parent")
        val other = node("other")
        val child = node("child")
        parent.addChildNode(child)

        other.removeChildNode(child) // not its child — must be a no-op

        assertSame(parent, child.parent)
        assertTrue(child in parent.childNodes)
    }

    @Test
    fun selfAndCycleParentingRejected() {
        val a = node("a")
        val b = node("b")
        val c = node("c")
        a.addChildNode(b)
        b.addChildNode(c)

        assertFails("self-parenting must fail") { a.addChildNode(a) }
        assertFails("descendant cycle must fail") { c.addChildNode(a) }
        // The failed writes left the graph untouched.
        assertNull(a.parent)
        assertSame(b, c.parent)
    }

    // --- World transform composition -----------------------------------------

    @Test
    fun worldPositionComposesThroughChain() {
        val a = node("a").apply { position = Position(1f, 0f, 0f) }
        val b = node("b").apply { position = Position(1f, 0f, 0f) }
        val c = node("c").apply { position = Position(1f, 0f, 0f) }
        a.addChildNode(b)
        b.addChildNode(c)

        assertClose(Position(1f, 0f, 0f), a.worldPosition, "a.world")
        assertClose(Position(2f, 0f, 0f), b.worldPosition, "b.world")
        assertClose(Position(3f, 0f, 0f), c.worldPosition, "c.world")
    }

    @Test
    fun rotatedParentRotatesChildWorldPosition() {
        val parent = node("parent").apply {
            position = Position(0f, 5f, 0f)
            rotation = Rotation(0f, 90f, 0f) // +90° yaw about Y
        }
        val child = node("child").apply { position = Position(1f, 0f, 0f) }
        parent.addChildNode(child)

        // R_y(+90°) maps +x to -z, then the parent translation applies.
        assertClose(Position(0f, 5f, -1f), child.worldPosition)
    }

    @Test
    fun scaledParentScalesChildWorldSpace() {
        val parent = node("parent").apply { scale = Scale(2f) }
        val child = node("child").apply {
            position = Position(1f, 0f, 0f)
            scale = Scale(3f)
        }
        parent.addChildNode(child)

        assertClose(Position(2f, 0f, 0f), child.worldPosition)
        assertClose(Position(6f, 6f, 6f), child.worldScale, "worldScale")
    }

    @Test
    fun reparentingKeepsLocalChangesWorld() {
        val origin = node("origin")
        val shifted = node("shifted").apply { position = Position(10f, 0f, 0f) }
        val child = node("child").apply { position = Position(1f, 0f, 0f) }

        origin.addChildNode(child)
        assertClose(Position(1f, 0f, 0f), child.worldPosition, "under origin")

        shifted.addChildNode(child)
        // Local is kept (Android parent-setter contract) → world moves.
        assertClose(Position(1f, 0f, 0f), child.position, "local kept")
        assertClose(Position(11f, 0f, 0f), child.worldPosition, "under shifted")
    }

    // --- World setters (world -> local conversion) -----------------------------

    @Test
    fun worldPositionSetterConvertsToLocal() {
        val parent = node("parent").apply { position = Position(5f, 0f, 0f) }
        val child = node("child")
        parent.addChildNode(child)

        child.worldPosition = Position(6f, 1f, 0f)

        assertClose(Position(1f, 1f, 0f), child.position, "local")
        assertClose(Position(6f, 1f, 0f), child.worldPosition, "world round-trip")
    }

    @Test
    fun worldQuaternionSetterConvertsToLocal() {
        val yaw90 = Rotation(0f, 90f, 0f).toQuaternion()
        val parent = node("parent").apply { quaternion = yaw90 }
        val child = node("child")
        parent.addChildNode(child)

        child.worldQuaternion = yaw90

        // Parent already provides the whole world rotation → local ≈ identity.
        assertSameRotation(Quaternion(), child.quaternion, "local quaternion")
        assertSameRotation(yaw90, child.worldQuaternion, "world round-trip")
    }

    @Test
    fun worldTransformSetterRoundTrips() {
        val parent = node("parent").apply {
            position = Position(1f, 2f, 3f)
            rotation = Rotation(0f, 45f, 0f)
        }
        val child = node("child")
        parent.addChildNode(child)

        val target = Transform(
            position = Position(-2f, 0f, 4f),
            quaternion = Rotation(30f, 0f, 0f).toQuaternion(),
            scale = Scale(1.5f)
        )
        child.worldTransform = target

        assertClose(target, child.worldTransform, "world round-trip")
    }

    // --- lookAt -----------------------------------------------------------------

    @Test
    fun lookAtUnderRotatedParentSetsWorldOrientation() {
        val parent = node("parent").apply { rotation = Rotation(0f, 90f, 0f) }
        val child = node("child")
        parent.addChildNode(child)
        child.worldPosition = Position(0f, 0f, 5f)

        child.lookAt(Position(0f, 0f, 0f))

        // The world orientation must equal the raw kotlin-math lookAt result
        // regardless of the parent rotation — i.e. the world→local conversion
        // path absorbed the parent's frame.
        val expected = lookAt(
            eye = Position(0f, 0f, 5f),
            target = Position(0f, 0f, 0f),
            up = Position(0f, 1f, 0f)
        ).quaternion
        assertSameRotation(expected, child.worldQuaternion)
        // Position untouched by lookAt.
        assertClose(Position(0f, 0f, 5f), child.worldPosition)
    }

    // --- destroy -----------------------------------------------------------------

    @Test
    fun destroyIsRecursiveAndIdempotent() {
        val root = node("root")
        val mid = node("mid")
        val leaf = node("leaf")
        root.addChildNode(mid)
        mid.addChildNode(leaf)

        root.destroy()

        assertTrue(root.isDestroyed)
        assertTrue(mid.isDestroyed)
        assertTrue(leaf.isDestroyed)
        assertEquals(1, root.fake.destroyCalls, "root freed exactly once")
        assertEquals(1, mid.fake.destroyCalls, "mid freed exactly once")
        assertEquals(1, leaf.fake.destroyCalls, "leaf freed exactly once")
        assertTrue(root.childNodes.isEmpty())
        assertNull(mid.parent)
        assertNull(leaf.parent)

        // Second destroy is a no-op — nothing double-freed.
        root.destroy()
        assertEquals(1, root.fake.destroyCalls)
        assertEquals(1, mid.fake.destroyCalls)
        assertEquals(1, leaf.fake.destroyCalls)
    }

    @Test
    fun parentSetterAfterDestroyDoesNotReachBackend() {
        val parent = node("parent")
        val child = node("child")

        child.destroy()
        val callsBeforeReparent = child.fake.setParentCalls

        // A caller retains a destroyed node and re-parents it. The Filament
        // entity + transform component are already freed, so the engine write
        // (TransformManager.setParent on a freed instance) would be a WASM
        // use-after-free abort — uncatchable. The guard must skip it, exactly
        // like the transform-write guard skips setLocalTransform after destroy.
        child.parent = parent

        assertEquals(
            callsBeforeReparent, child.fake.setParentCalls,
            "no engine setParent may run on a destroyed node"
        )
    }

    @Test
    fun destroyDetachesFromLivingParent() {
        val parent = node("parent")
        val child = node("child")
        parent.addChildNode(child)

        child.destroy()

        assertTrue(parent.childNodes.isEmpty(), "destroyed child left the parent")
        assertNull(child.parent)
        assertTrue(child.isDestroyed)
        assertEquals(0, parent.fake.destroyCalls, "parent must survive")
    }

    // --- SceneGraph (core) interop ------------------------------------------------

    @Test
    fun sceneGraphAddNodeWiresHierarchy() {
        val graph = SceneGraph()
        val root = node("root")
        val child = node("child").apply { position = Position(1f, 0f, 0f) }

        graph.addNode(root)
        graph.addNode(child, parent = root)

        assertEquals(1, graph.rootNodes.size)
        assertSame(root, graph.rootNodes.single())
        assertSame(root, child.parent)
        root.position = Position(0f, 2f, 0f)
        assertClose(Position(1f, 2f, 0f), child.worldPosition)
    }

    @Test
    fun sceneGraphRemoveNodeDetachesSubtreeRecursively() {
        val graph = SceneGraph()
        val root = node("root")
        val mid = node("mid")
        val leaf = node("leaf")
        graph.addNode(root)
        graph.addNode(mid, parent = root)
        graph.addNode(leaf, parent = mid)

        graph.removeNode(root)

        assertTrue(graph.rootNodes.isEmpty())
        assertNull(mid.parent, "removeNode detaches children")
        assertNull(leaf.parent)
        // Removal is NOT destruction — entities stay alive for re-adding.
        assertEquals(0, root.fake.destroyCalls)
        assertEquals(0, mid.fake.destroyCalls)
        assertEquals(0, leaf.fake.destroyCalls)
    }
}
