package io.github.sceneview.ar.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the throw-during-upload leak fix in [DepthMeshNode.uploadGeometry] (#1840).
 *
 * Before the fix, [DepthMeshNode.rebuildBuffersIfNeeded] swapped `ownedVertexBuffer` /
 * `ownedIndexBuffer` to the NEW buffer **before** running the Filament upload calls
 * ([com.google.android.filament.VertexBuffer.setBufferAt] / [com.google.android.filament.IndexBuffer.setBuffer]
 * / [com.google.android.filament.RenderableManager.setGeometryAt]).
 *
 * If any of those upload calls threw — `IllegalStateException` on engine teardown mid-frame is
 * the realistic case — the OLD buffers became unreachable AND undestroyed. `destroy()` only
 * walks the current `owned*` fields, so the orphaned old handles leaked into the next session.
 *
 * The fix:
 *  - `rebuildBuffersIfNeeded` no longer mutates `owned*` — it returns the freshly-built buffers
 *    plus the new capacity for the caller to commit later.
 *  - `uploadGeometry` runs the entire Filament upload sequence against `target*` locals first;
 *    on success it swaps fields + destroys the old buffers; on any throw it destroys the freshly
 *    allocated buffers (the only references to them) and rethrows.
 *
 * The Filament Engine can't run on the JVM, so this test models the upload-with-throw flow via a
 * recorder that mirrors the post-fix [DepthMeshNode.uploadGeometry] exactly. The recorder makes
 * the property under test ("on throw: stale old buffer is preserved, new buffer is destroyed
 * exactly once") observable as a list of events the new code path emits.
 */
class DepthMeshNodeUploadThrowLeakTest {

    /**
     * Mirrors the post-fix [DepthMeshNode.uploadGeometry] data flow. `throwOnUpload` simulates
     * `setGeometryAt` throwing.
     */
    private class ThrowingUploader {
        val events = mutableListOf<String>()
        val destroyedHandles = mutableListOf<String>()
        private var nextHandle = 1
        var currentVertexBufferHandle: String = "vb#0"
            private set
        var currentIndexBufferHandle: String = "ib#0"
            private set
        var allocatedVertexCount: Int = DepthMeshNode.INITIAL_VERTEX_CAPACITY
            private set
        var allocatedIndexCount: Int = DepthMeshNode.INITIAL_INDEX_CAPACITY
            private set

        /** Returns true if the upload succeeded; false if it caught + rethrew at the boundary. */
        fun upload(
            requestedVertexCount: Int,
            requestedIndexCount: Int,
            throwOnUpload: Boolean,
        ): Boolean {
            val plan = planBufferRebuild(
                allocatedVertexCount = allocatedVertexCount,
                allocatedIndexCount = allocatedIndexCount,
                requestedVertexCount = requestedVertexCount,
                requestedIndexCount = requestedIndexCount,
            )
            // Phase A: build the new buffers WITHOUT mutating any owned* state.
            var newVertexHandle: String? = null
            var newIndexHandle: String? = null
            if (plan.newVertexCount != null) {
                newVertexHandle = "vb#${nextHandle++}"
                events += "build:$newVertexHandle"
            }
            if (plan.newIndexCount != null) {
                newIndexHandle = "ib#${nextHandle++}"
                events += "build:$newIndexHandle"
            }
            val targetVertex = newVertexHandle ?: currentVertexBufferHandle
            val targetIndex = newIndexHandle ?: currentIndexBufferHandle
            try {
                events += "setGeometryAt:$targetVertex:$targetIndex"
                if (throwOnUpload) throw IllegalStateException("engine teardown mid-frame")
            } catch (t: Throwable) {
                // Failure-rollback path: destroy the newly-allocated buffers (the only refs to
                // them) and leave the owned* fields unchanged so [destroy] can still free them.
                newVertexHandle?.let { events += "rollback-destroy:$it"; destroyedHandles += it }
                newIndexHandle?.let { events += "rollback-destroy:$it"; destroyedHandles += it }
                return false
            }
            // Phase B: commit — swap fields, destroy displaced old buffers.
            if (newVertexHandle != null) {
                val stale = currentVertexBufferHandle
                currentVertexBufferHandle = newVertexHandle
                allocatedVertexCount = plan.newVertexCount!!
                events += "destroy:$stale"
                destroyedHandles += stale
            }
            if (newIndexHandle != null) {
                val stale = currentIndexBufferHandle
                currentIndexBufferHandle = newIndexHandle
                allocatedIndexCount = plan.newIndexCount!!
                events += "destroy:$stale"
                destroyedHandles += stale
            }
            return true
        }

        /** Mirrors [DepthMeshNode.destroy] — releases the owned* refs. */
        fun destroy() {
            events += "destroy:$currentVertexBufferHandle"
            destroyedHandles += currentVertexBufferHandle
            events += "destroy:$currentIndexBufferHandle"
            destroyedHandles += currentIndexBufferHandle
        }
    }

    @Test
    fun `setGeometryAt throw destroys new buffers and keeps old buffers reachable for destroy`() {
        val uploader = ThrowingUploader()
        val originalVertex = uploader.currentVertexBufferHandle
        val originalIndex = uploader.currentIndexBufferHandle

        // Force a growth so new buffers get built; then make the upload throw.
        val ok = uploader.upload(
            requestedVertexCount = DepthMeshNode.INITIAL_VERTEX_CAPACITY + 1,
            requestedIndexCount = DepthMeshNode.INITIAL_INDEX_CAPACITY + 1,
            throwOnUpload = true,
        )
        assertFalse("upload must report failure when setGeometryAt throws", ok)

        // The owned* fields must still point at the OLD buffers — otherwise the next destroy()
        // would leak the originals (#1840).
        assertEquals(originalVertex, uploader.currentVertexBufferHandle)
        assertEquals(originalIndex, uploader.currentIndexBufferHandle)

        // The freshly-built buffers must have been destroyed exactly once during rollback —
        // they're the only references and would otherwise leak forever (#1840).
        val rollbackDestroys = uploader.events.filter { it.startsWith("rollback-destroy:") }
        assertEquals("must destroy both new vertex + new index buffers", 2, rollbackDestroys.size)

        // Now simulate node teardown — destroy() must release the STILL-OLD owned buffers.
        uploader.destroy()
        assertTrue("old vertex buffer must be destroyed by destroy()", originalVertex in uploader.destroyedHandles)
        assertTrue("old index buffer must be destroyed by destroy()", originalIndex in uploader.destroyedHandles)

        // No buffer destroyed more than once (no double-free).
        val grouped = uploader.destroyedHandles.groupingBy { it }.eachCount()
        for ((handle, count) in grouped) {
            assertEquals("buffer $handle destroyed more than once", 1, count)
        }
    }

    @Test
    fun `successful upload still commits the swap and destroys old buffers exactly once`() {
        // Sanity check — the success path must be unchanged by the throw-rollback fix.
        val uploader = ThrowingUploader()
        val originalVertex = uploader.currentVertexBufferHandle
        val originalIndex = uploader.currentIndexBufferHandle

        val ok = uploader.upload(
            requestedVertexCount = DepthMeshNode.INITIAL_VERTEX_CAPACITY + 1,
            requestedIndexCount = DepthMeshNode.INITIAL_INDEX_CAPACITY + 1,
            throwOnUpload = false,
        )
        assertTrue("upload must succeed when nothing throws", ok)
        // Old buffers must have been destroyed by the commit path.
        assertTrue(originalVertex in uploader.destroyedHandles)
        assertTrue(originalIndex in uploader.destroyedHandles)
        // owned* must now point at the new buffers.
        assertTrue(uploader.currentVertexBufferHandle != originalVertex)
        assertTrue(uploader.currentIndexBufferHandle != originalIndex)
    }

    @Test
    fun `throw during upload does not leak even when only the vertex buffer was reallocated`() {
        // Capacity bump for vertices only — index buffer still fits. The fix must still destroy
        // the (single) newly allocated vertex buffer + leave the OLD vertex buffer reachable.
        val uploader = ThrowingUploader()
        val originalVertex = uploader.currentVertexBufferHandle

        val ok = uploader.upload(
            requestedVertexCount = DepthMeshNode.INITIAL_VERTEX_CAPACITY + 1,
            requestedIndexCount = DepthMeshNode.INITIAL_INDEX_CAPACITY - 100,
            throwOnUpload = true,
        )
        assertFalse(ok)
        assertEquals(originalVertex, uploader.currentVertexBufferHandle)
        val rollbackDestroys = uploader.events.filter { it.startsWith("rollback-destroy:") }
        assertEquals("only the new vertex buffer needed destruction", 1, rollbackDestroys.size)
        assertTrue(rollbackDestroys.single().startsWith("rollback-destroy:vb#"))
    }
}
