package io.github.sceneview.ar.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pins the identity-tangent buffer cache contract for [AugmentedFaceNode] (#1758).
 *
 * The Filament Engine + ARCore [com.google.ar.core.AugmentedFace] plumbing cannot run in a JVM
 * unit test, so the cache lookup is `internal` ([obtainIdentityTangentsBuffer]) and verified here
 * in isolation. Without this pin, a regression that re-runs the per-vertex `(0,0,0,1)` write
 * loop every frame (or reallocates the direct buffer) would only surface on-device under the
 * unlit ARFaceDemo material and never on CI.
 */
class AugmentedFaceTangentsCacheTest {

    /** ARCore face mesh has 468 vertices in practice — picks a representative size. */
    private val faceVertexCount = 468

    @Test
    fun `first call allocates a fresh buffer and writes identity quaternions`() {
        val (buf, wasCacheHit) = obtainIdentityTangentsBuffer(
            cached = null,
            cachedVertexCount = 0,
            vertexCount = faceVertexCount
        )

        assertEquals("first call must be a miss", false, wasCacheHit)
        assertEquals(
            "buffer must hold vertexCount * 4 floats * 4 bytes",
            faceVertexCount * 4 * 4,
            buf.limit()
        )

        // Verify the identity-quaternion layout (0, 0, 0, 1) per vertex.
        val floats = buf.asFloatBuffer()
        for (v in 0 until faceVertexCount) {
            assertEquals(0f, floats.get(v * 4 + 0), 0f)
            assertEquals(0f, floats.get(v * 4 + 1), 0f)
            assertEquals(0f, floats.get(v * 4 + 2), 0f)
            assertEquals(1f, floats.get(v * 4 + 3), 0f)
        }
    }

    @Test
    fun `second call at same vertex count is a cache hit returning the same buffer`() {
        val (first, _) = obtainIdentityTangentsBuffer(
            cached = null,
            cachedVertexCount = 0,
            vertexCount = faceVertexCount
        )

        val (second, wasCacheHit) = obtainIdentityTangentsBuffer(
            cached = first,
            cachedVertexCount = faceVertexCount,
            vertexCount = faceVertexCount
        )

        assertEquals("second call must be a cache hit", true, wasCacheHit)
        assertSame("must return the cached buffer instance, not a fresh one", first, second)
    }

    @Test
    fun `cache hit does NOT re-run the per-vertex write loop`() {
        // Allocate, then sabotage the buffer to detect a re-write.
        val (first, _) = obtainIdentityTangentsBuffer(
            cached = null,
            cachedVertexCount = 0,
            vertexCount = 4
        )
        first.asFloatBuffer().apply {
            // Overwrite vertex 0's quaternion with garbage.
            put(0, 9f); put(1, 9f); put(2, 9f); put(3, 9f)
        }

        val (second, wasCacheHit) = obtainIdentityTangentsBuffer(
            cached = first,
            cachedVertexCount = 4,
            vertexCount = 4
        )

        assertEquals(true, wasCacheHit)
        val floats = second.asFloatBuffer()
        // Garbage is still there — proves no rewrite happened, which is the whole point:
        // re-running the loop every frame is the perf bug the issue flagged.
        assertEquals(9f, floats.get(0), 0f)
        assertEquals(9f, floats.get(1), 0f)
        assertEquals(9f, floats.get(2), 0f)
        assertEquals(9f, floats.get(3), 0f)
    }

    @Test
    fun `larger vertex count miss reallocates and rewrites the buffer`() {
        val (small, _) = obtainIdentityTangentsBuffer(
            cached = null,
            cachedVertexCount = 0,
            vertexCount = 8
        )

        val (large, wasCacheHit) = obtainIdentityTangentsBuffer(
            cached = small,
            cachedVertexCount = 8,
            vertexCount = 16
        )

        assertEquals("growing vertex count must miss the cache", false, wasCacheHit)
        assertNotSame("must allocate a new larger direct buffer", small, large)
        assertEquals(16 * 4 * 4, large.limit())

        val floats = large.asFloatBuffer()
        // Layout still correct after reallocation.
        for (v in 0 until 16) {
            assertEquals(0f, floats.get(v * 4 + 0), 0f)
            assertEquals(0f, floats.get(v * 4 + 1), 0f)
            assertEquals(0f, floats.get(v * 4 + 2), 0f)
            assertEquals(1f, floats.get(v * 4 + 3), 0f)
        }
    }

    @Test
    fun `smaller vertex count after larger one reuses the existing buffer capacity`() {
        // Edge case: ARCore changes mesh topology mid-session. The capacity check should
        // allow reuse without a heap-grow allocation.
        val (large, _) = obtainIdentityTangentsBuffer(
            cached = null,
            cachedVertexCount = 0,
            vertexCount = 16
        )

        val (smaller, wasCacheHit) = obtainIdentityTangentsBuffer(
            cached = large,
            cachedVertexCount = 16,
            vertexCount = 8
        )

        // It's a miss because vertex count differs, but the underlying ByteBuffer
        // is reused (no fresh allocateDirect call). Sample-check: same identity hash.
        assertEquals(false, wasCacheHit)
        assertSame("must reuse the larger backing buffer, not reallocate", large, smaller)
        assertEquals(8 * 4 * 4, smaller.limit())
    }

    @Test
    fun `direct ByteBuffer uses native order so Filament reads correct floats`() {
        val (buf, _) = obtainIdentityTangentsBuffer(
            cached = null,
            cachedVertexCount = 0,
            vertexCount = 1
        )
        assertTrue("must be a direct (off-heap) buffer for Filament JNI", buf.isDirect)
        assertEquals(
            "must use native byte order (Filament expects host endianness)",
            ByteOrder.nativeOrder(),
            buf.order()
        )
    }
}
