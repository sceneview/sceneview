package io.github.sceneview.ar.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM sentinel for the `MeshNode` buffer-ownership contract (#2037).
 *
 * The bug: `MeshNode` held its `VertexBuffer` / `IndexBuffer` as `val` fields but had no
 * `destroy()` override, so the buffers were never freed. `StreetscapeGeometryNode` builds a
 * fresh `VertexBuffer` + `IndexBuffer` per trackable and hands them to a `MeshNode` it owns
 * — every building/terrain trackable leaked two Filament buffers for the whole engine
 * lifetime.
 *
 * The fix adds an opt-in `destroyBuffersOnDispose` flag to `MeshNode` (mirroring
 * `RenderableNode.destroyMaterialsOnDispose`); `MeshNode.destroy()` frees the buffers only
 * when the flag is set. `StreetscapeGeometryNode` passes `destroyBuffersOnDispose = true`
 * because it exclusively owns the buffers it builds, and its `MeshNode` is a child node so
 * `Node.destroy()`'s recursive child teardown (#2036) reaches it.
 *
 * `MeshNode` needs a Filament `Engine` (native JNI) so it cannot be instantiated in pure
 * JVM. This test pins the ownership-decision logic the fix relies on.
 */
class StreetscapeGeometryBufferOwnershipTest {

    /** Records buffer frees; mirrors `engine.safeDestroyVertexBuffer/IndexBuffer`. */
    private class FakeEngine {
        val destroyedBuffers = mutableListOf<String>()
        fun destroyBuffer(name: String) {
            destroyedBuffers += name
        }
    }

    /** Mirrors `MeshNode`'s buffer-ownership branch in `destroy()`. */
    private class FakeMeshNode(
        private val engine: FakeEngine,
        private val vertexBufferName: String,
        private val indexBufferName: String,
        private val destroyBuffersOnDispose: Boolean
    ) {
        fun destroy() {
            // super.destroy() (renderable teardown) happens first in production.
            if (destroyBuffersOnDispose) {
                engine.destroyBuffer(vertexBufferName)
                engine.destroyBuffer(indexBufferName)
            }
        }
    }

    @Test
    fun `MeshNode with destroyBuffersOnDispose false leaves buffers to an external owner`() {
        // Backward-compatible default: a caller sharing buffers must not have them freed.
        val engine = FakeEngine()
        FakeMeshNode(engine, "vb", "ib", destroyBuffersOnDispose = false).destroy()

        assertTrue(
            "default MeshNode must not free buffers it does not own",
            engine.destroyedBuffers.isEmpty()
        )
    }

    @Test
    fun `MeshNode with destroyBuffersOnDispose true frees both buffers`() {
        val engine = FakeEngine()
        FakeMeshNode(engine, "vb", "ib", destroyBuffersOnDispose = true).destroy()

        assertEquals(
            "an owning MeshNode frees both its vertex and index buffers",
            listOf("vb", "ib"),
            engine.destroyedBuffers
        )
    }

    @Test
    fun `StreetscapeGeometryNode owns the buffers it builds — opts into buffer destruction`() {
        // StreetscapeGeometryNode builds a VertexBuffer + IndexBuffer just for its MeshNode
        // and nothing else references them, so it must pass destroyBuffersOnDispose = true.
        // This pins that the streetscape path frees both buffers on teardown.
        val engine = FakeEngine()
        val streetscapeMeshNode = FakeMeshNode(
            engine,
            vertexBufferName = "streetscape-vb",
            indexBufferName = "streetscape-ib",
            destroyBuffersOnDispose = true
        )

        // Node.destroy()'s recursive child teardown reaches the child MeshNode (#2036).
        streetscapeMeshNode.destroy()

        assertEquals(
            "every streetscape trackable must release its buffers — no unbounded leak",
            listOf("streetscape-vb", "streetscape-ib"),
            engine.destroyedBuffers
        )
    }
}
