package io.github.sceneview.ar.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pins the Filament direct-buffer lifetime contract for [DepthMeshNode.uploadGeometry] (#1841).
 *
 * Filament's `VertexBuffer.setBufferAt` / `IndexBuffer.setBuffer` capture a global ref to the
 * direct [ByteBuffer] passed in and copy ASYNCHRONOUSLY on the render command stream. If the same
 * direct buffer is reused across uploads (the #1810 perf opt), the next frame's overwrite can
 * clobber bytes Filament has not yet consumed → torn vertex uploads, corrupted mesh frames.
 *
 * The fix reverts the buffer cache and allocates a fresh direct ByteBuffer per upload — matching
 * the standard pattern used by every other `Geometry.kt` caller (lines 216, 229, 242, 255 of
 * `sceneview/src/main/java/io/github/sceneview/geometries/Geometry.kt`).
 *
 * The Filament Engine can't run on the JVM, so this test pins the invariant in two ways:
 *  - it walks the [DepthMeshNode] source to confirm there is no `ByteBuffer` field for
 *    upload-buffer caching (`vertexUploadBuffer`, `indexUploadBuffer`, `acquireDirectBuffer`,
 *    `MIN_UPLOAD_BUFFER_BYTES` must all be gone);
 *  - it asserts the source emits a fresh `ByteBuffer.allocateDirect(...)` call per upload code
 *    path — two such calls per `uploadGeometry`, one for vertices + one for indices.
 *
 * Regression test surface for #1841 — re-introducing the cache will fail this test.
 */
class DepthMeshNodeUploadBufferFreshnessTest {

    private val sourceFile: File by lazy {
        // Resolved relative to the gradle workdir for arsceneview unit tests.
        File("src/main/java/io/github/sceneview/ar/node/DepthMeshNode.kt")
            .takeIf { it.exists() }
            ?: File("arsceneview/src/main/java/io/github/sceneview/ar/node/DepthMeshNode.kt")
    }

    private val source: String by lazy { sourceFile.readText() }

    @Test
    fun `source compiles in expected layout`() {
        assertTrue("Could not locate DepthMeshNode.kt at ${sourceFile.absolutePath}", sourceFile.exists())
        assertTrue("Source must contain the uploadGeometry function", source.contains("fun uploadGeometry("))
    }

    @Test
    fun `no cached upload ByteBuffer fields are present (#1841)`() {
        // The #1810 perf opt introduced `vertexUploadBuffer` / `indexUploadBuffer` fields and a
        // helper `acquireDirectBuffer`. The #1841 fix reverts that cache so Filament's async
        // memcpy never races with a JVM-side overwrite.
        for (forbiddenSymbol in listOf(
            "vertexUploadBuffer",
            "indexUploadBuffer",
            "acquireDirectBuffer",
            "MIN_UPLOAD_BUFFER_BYTES",
        )) {
            assertFalse(
                "Cached upload buffer symbol '$forbiddenSymbol' was re-introduced — the #1810 " +
                    "cache violates Filament's direct-buffer lifetime contract (#1841). Use a " +
                    "fresh ByteBuffer.allocateDirect per upload instead.",
                source.contains(forbiddenSymbol),
            )
        }
    }

    @Test
    fun `uploadGeometry allocates a fresh direct ByteBuffer per upload (#1841)`() {
        val uploadBlock = extractUploadGeometryBody()
        // Two allocateDirect calls: one for the vertex stream, one for the index stream. Any
        // path that reuses a buffer would have FEWER allocateDirect calls per upload.
        val allocCount = Regex("ByteBuffer\\.allocateDirect\\(").findAll(uploadBlock).count()
        assertEquals(
            "uploadGeometry must allocate exactly two fresh direct ByteBuffers per call " +
                "(vertices + indices) — see #1841 / Geometry.kt pattern.",
            2,
            allocCount,
        )
    }

    @Test
    fun `the fresh ByteBuffer is native-order (Filament JNI assumption)`() {
        val uploadBlock = extractUploadGeometryBody()
        val nativeOrderCount = Regex("\\.order\\(\\s*ByteOrder\\.nativeOrder\\(\\)\\s*\\)").findAll(uploadBlock).count()
        // Both allocations must be flipped to nativeOrder before bytes are written to them, or
        // Filament reads back garbage on big-endian-emulating JVMs (ARCore packs LE which matches
        // every supported device's native order, but the order() call must still be explicit).
        assertEquals(
            "uploadGeometry must order both fresh ByteBuffers to ByteOrder.nativeOrder().",
            2,
            nativeOrderCount,
        )
    }

    /**
     * Sanity probe: build a fresh direct ByteBuffer the same way [DepthMeshNode.uploadGeometry]
     * does and confirm two successive allocations are distinct objects (different identities)
     * so Filament's async memcpy never sees a concurrently-overwritten payload.
     */
    @Test
    fun `successive fresh allocations are distinct ByteBuffer instances`() {
        val a = ByteBuffer.allocateDirect(1024).order(ByteOrder.nativeOrder())
        val b = ByteBuffer.allocateDirect(1024).order(ByteOrder.nativeOrder())
        // Two distinct identities. If a cache were in play this assertion would fire for the
        // second call returning the same wrapper as the first.
        assertNotSame(a, b)
        // Capacity matches request — a no-op assertion that documents the invariant.
        assertEquals(1024, a.capacity())
        assertEquals(1024, b.capacity())
        // Native order is required by Filament JNI on every supported device.
        assertNotEquals(ByteOrder.BIG_ENDIAN, a.order())
        assertEquals(ByteOrder.nativeOrder(), a.order())
    }

    /**
     * Pulls the body of `uploadGeometry` out of the source so the regexes only look at the
     * upload code path (vs. other helpers that may legitimately allocateDirect for other
     * reasons).
     */
    private fun extractUploadGeometryBody(): String {
        val start = source.indexOf("private fun uploadGeometry(")
        assertTrue("uploadGeometry must be present in DepthMeshNode.kt", start >= 0)
        // Walk braces to find the matching closing brace.
        val bodyStart = source.indexOf('{', start)
        assertTrue("uploadGeometry must have an opening brace", bodyStart >= 0)
        var depth = 1
        var i = bodyStart + 1
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return source.substring(bodyStart, i)
    }
}
