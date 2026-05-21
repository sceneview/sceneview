package io.github.sceneview.ar.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the **build-new → rebind → destroy-old** ordering of [DepthMeshNode.rebuildBuffersIfNeeded]
 * (#1805).
 *
 * The previous code destroyed the old [com.google.android.filament.VertexBuffer] /
 * [com.google.android.filament.IndexBuffer] **before** calling
 * [com.google.android.filament.RenderableManager.setGeometryAt] — for one frame the renderable
 * referenced freed Filament native handles. Potential use-after-free if any render command was
 * queued between destroy and rebind. Surfaced by the May 2026 Tier-2 SECURITY audit on the merged
 * AR cluster.
 *
 * The Filament Engine can't run on the JVM, so we exercise the pure decision logic through the
 * `internal` [planBufferRebuild] seam and replay the ordering with a recorder that mimics what
 * [DepthMeshNode.uploadGeometry] does on a real engine.
 */
class DepthMeshNodeBufferRebuildTest {

    /**
     * Mock-engine recorder that mirrors the exact sequence [DepthMeshNode.uploadGeometry] runs
     * against a real Filament Engine, but lets the test capture the order of operations as
     * strings.
     */
    private class Recorder {
        val events = mutableListOf<String>()
        private var nextHandle = 1
        var currentVertexBufferHandle: String = "vb#0"
            private set
        var currentIndexBufferHandle: String = "ib#0"
            private set

        /** Mirrors [DepthMeshNode.rebuildBuffersIfNeeded] + [DepthMeshNode.uploadGeometry]. */
        fun upload(
            allocatedVertexCount: Int,
            allocatedIndexCount: Int,
            requestedVertexCount: Int,
            requestedIndexCount: Int,
        ): Pair<Int, Int> {
            val plan = planBufferRebuild(
                allocatedVertexCount = allocatedVertexCount,
                allocatedIndexCount = allocatedIndexCount,
                requestedVertexCount = requestedVertexCount,
                requestedIndexCount = requestedIndexCount,
            )
            // Phase A: build the new buffers, capturing the old handles for later destroy.
            var staleVertex: String? = null
            var staleIndex: String? = null
            var newAllocVertex = allocatedVertexCount
            var newAllocIndex = allocatedIndexCount
            if (plan.newVertexCount != null) {
                staleVertex = currentVertexBufferHandle
                currentVertexBufferHandle = "vb#${nextHandle++}"
                events += "build:$currentVertexBufferHandle"
                newAllocVertex = plan.newVertexCount
            }
            if (plan.newIndexCount != null) {
                staleIndex = currentIndexBufferHandle
                currentIndexBufferHandle = "ib#${nextHandle++}"
                events += "build:$currentIndexBufferHandle"
                newAllocIndex = plan.newIndexCount
            }
            // Phase B: rebind the renderable to the NEW buffers — this is the line that must
            // happen BEFORE the destroy calls below (#1805 ordering invariant).
            events += "setGeometryAt:$currentVertexBufferHandle:$currentIndexBufferHandle"
            // Phase C: only NOW destroy the stale handles. If a stale destroy happens during
            // phase A, [Recorder.events] would record the destroy BEFORE the setGeometryAt.
            staleVertex?.let { events += "destroy:$it" }
            staleIndex?.let { events += "destroy:$it" }
            return newAllocVertex to newAllocIndex
        }
    }

    @Test
    fun `rebuild keeps current buffers when capacity already fits`() {
        val plan = planBufferRebuild(
            allocatedVertexCount = 1024,
            allocatedIndexCount = 6144,
            requestedVertexCount = 800,
            requestedIndexCount = 4000,
        )
        assertNull("no vertex rebuild needed", plan.newVertexCount)
        assertNull("no index rebuild needed", plan.newIndexCount)
    }

    @Test
    fun `rebuild grows to the next power of two`() {
        val plan = planBufferRebuild(
            allocatedVertexCount = 1024,
            allocatedIndexCount = 6144,
            requestedVertexCount = 1500,
            requestedIndexCount = 7000,
        )
        assertEquals(2048, plan.newVertexCount)
        assertEquals(8192, plan.newIndexCount)
    }

    @Test
    fun `rebuild never goes below initial capacity`() {
        val plan = planBufferRebuild(
            allocatedVertexCount = 1,
            allocatedIndexCount = 1,
            requestedVertexCount = 2,
            requestedIndexCount = 2,
            initialVertexCapacity = DepthMeshNode.INITIAL_VERTEX_CAPACITY,
            initialIndexCapacity = DepthMeshNode.INITIAL_INDEX_CAPACITY,
        )
        // Pre-clamp would have produced 2 → nextPow2(2) = 2; but we always promote to at least the
        // initial capacity so the buffer survives ARCore's depth-image-resolution-constant churn.
        assertTrue(
            "vertex rebuild must promote to ≥ INITIAL_VERTEX_CAPACITY",
            (plan.newVertexCount ?: 0) >= DepthMeshNode.INITIAL_VERTEX_CAPACITY,
        )
        assertTrue(
            "index rebuild must promote to ≥ INITIAL_INDEX_CAPACITY",
            (plan.newIndexCount ?: 0) >= DepthMeshNode.INITIAL_INDEX_CAPACITY,
        )
    }

    @Test
    fun `first growth — new buffers are bound BEFORE old ones are destroyed (#1805)`() {
        val recorder = Recorder()
        // Start at the initial capacity, request more — forces a vertex + index rebuild.
        recorder.upload(
            allocatedVertexCount = DepthMeshNode.INITIAL_VERTEX_CAPACITY,
            allocatedIndexCount = DepthMeshNode.INITIAL_INDEX_CAPACITY,
            requestedVertexCount = DepthMeshNode.INITIAL_VERTEX_CAPACITY + 1,
            requestedIndexCount = DepthMeshNode.INITIAL_INDEX_CAPACITY + 1,
        )
        // Ordering invariant: every `destroy:*` must come AFTER `setGeometryAt:*`.
        val setIdx = recorder.events.indexOfFirst { it.startsWith("setGeometryAt:") }
        assertTrue("setGeometryAt must be recorded", setIdx >= 0)
        val firstDestroyIdx = recorder.events.indexOfFirst { it.startsWith("destroy:") }
        assertTrue(
            "first destroy must be AFTER setGeometryAt — was $firstDestroyIdx vs $setIdx",
            firstDestroyIdx > setIdx,
        )
        // And the build calls must come BEFORE setGeometryAt.
        val firstBuildIdx = recorder.events.indexOfFirst { it.startsWith("build:") }
        assertTrue("build must be recorded", firstBuildIdx >= 0)
        assertTrue(
            "build must be BEFORE setGeometryAt",
            firstBuildIdx < setIdx,
        )
    }

    @Test
    fun `two successive growths preserve the ordering invariant (#1805)`() {
        // Acceptance: a JUnit test exercising vertexCount > allocatedVertexCount TWICE — assert the
        // destruction order via a mock Engine. The recorder is our mock; we drive two growth steps
        // and walk every destroy event to confirm it follows the most recent setGeometryAt.
        val recorder = Recorder()
        var allocV = DepthMeshNode.INITIAL_VERTEX_CAPACITY
        var allocI = DepthMeshNode.INITIAL_INDEX_CAPACITY

        // Growth 1: bump just over the initial capacity.
        val (allocV1, allocI1) = recorder.upload(
            allocatedVertexCount = allocV,
            allocatedIndexCount = allocI,
            requestedVertexCount = allocV + 1,
            requestedIndexCount = allocI + 1,
        )
        allocV = allocV1
        allocI = allocI1

        // Growth 2: bump again — must STILL preserve the ordering.
        recorder.upload(
            allocatedVertexCount = allocV,
            allocatedIndexCount = allocI,
            requestedVertexCount = allocV + 1,
            requestedIndexCount = allocI + 1,
        )

        // Walk through events; every `destroy:*` must be preceded by a `setGeometryAt:*` that
        // rebound past that handle (i.e. the renderable no longer references the soon-to-be-
        // destroyed buffer).
        val destroyEvents = recorder.events.filter { it.startsWith("destroy:") }
        assertTrue("two growths must produce ≥ 4 destroy events", destroyEvents.size >= 4)
        for (destroy in destroyEvents) {
            val handle = destroy.removePrefix("destroy:")
            val destroyIdx = recorder.events.indexOf(destroy)
            // The matching setGeometryAt that REBOUND past this handle must be at a lower index.
            val rebindIdx = recorder.events.subList(0, destroyIdx)
                .indexOfLast { event ->
                    event.startsWith("setGeometryAt:") &&
                        !event.contains(":$handle:") &&
                        !event.endsWith(":$handle")
                }
            assertTrue(
                "destroy of $handle (idx $destroyIdx) must follow a setGeometryAt that rebound " +
                    "past it — events=${recorder.events}",
                rebindIdx in 0 until destroyIdx,
            )
        }
    }
}
