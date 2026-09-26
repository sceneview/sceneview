package io.github.sceneview.geometries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteOrder

/**
 * Pins the direct-buffer upload contract for [directFloatBuffer] / [directIntBuffer] and their
 * call sites in `VertexBuffer.setVertices` / `IndexBuffer.setIndices` (#3715).
 *
 * Every animated [Tube] — the marching dashes and moving trail on the Lines & Paths demo, for
 * instance — re-uploads its vertex streams every frame. Before this fix, `setVertices` built
 * those streams with `FloatBuffer.allocate(...)`: a **heap** buffer. Filament's `setBufferAt`
 * captures a JNI global reference to a heap buffer it is handed *and* a second one to its backing
 * `float[]`, both held until the driver's async copy completes — twice the live global refs per
 * upload. On a render thread that falls behind a loaded GPU those refs pile up faster than
 * Filament's callback releases them and blow ART's 51 200-entry global-ref-table cap
 * (`JNI ERROR (app bug): global reference table overflow`).
 *
 * [directFloatBuffer] / [directIntBuffer] fix this at the root: every upload is still fresh (a
 * buffer reused across frames would race Filament's async copy — see #1841, which ruled out that
 * shortcut for `DepthMeshNode`'s equivalent per-frame path) but **direct**, so there is no backing
 * Java array to pin and only one global ref is taken per upload instead of two.
 *
 * [directFloatBuffer] / [directIntBuffer] have no Filament dependency, so — unlike
 * `VertexBuffer.setVertices` itself, which needs a real Filament `Engine` the JVM can't provide —
 * they can be exercised directly here. The call-site regression (setVertices/setIndices must
 * actually route through them, not through `FloatBuffer.allocate`/`IntBuffer.allocate`) is pinned
 * by scanning the source, the same technique `DepthMeshNodeUploadBufferFreshnessTest` uses for the
 * `arsceneview` side of this same contract.
 */
class GeometryDirectBufferUploadTest {

    // ── directFloatBuffer / directIntBuffer: real behaviour, no Filament involved ────────────────

    @Test
    fun `directFloatBuffer produces a direct buffer`() {
        val buffer = directFloatBuffer(3) { put(floatArrayOf(1f, 2f, 3f)) }
        assertTrue(
            "setBufferAt must be handed a direct buffer or it re-introduces the heap " +
                "double-global-ref that caused #3715",
            buffer.isDirect,
        )
    }

    @Test
    fun `directFloatBuffer is ordered to native order`() {
        val buffer = directFloatBuffer(1) { put(1f) }
        // A direct buffer's default order is BIG_ENDIAN regardless of platform — nativeOrder()
        // must be applied explicitly or Filament's JNI reads back garbage on a little-endian
        // device (every supported device).
        assertEquals(ByteOrder.nativeOrder(), buffer.order())
    }

    @Test
    fun `directFloatBuffer is left ready to read`() {
        val values = floatArrayOf(1f, 2f, 3f, 4f)
        val buffer = directFloatBuffer(values.size) {
            put(values)
            flip()
        }
        assertEquals(0, buffer.position())
        assertEquals(values.size, buffer.limit())
        val read = FloatArray(values.size)
        buffer.get(read)
        assertEquals(values.toList(), read.toList())
    }

    @Test
    fun `directFloatBuffer allocates a fresh instance every call`() {
        // Reusing a buffer across uploads is its own bug class (#1841): Filament's copy is
        // asynchronous, so a shared buffer overwritten by the next frame's put() could tear the
        // upload Filament is still reading. Every call must return an independent instance.
        val first = directFloatBuffer(4) { put(floatArrayOf(1f, 2f, 3f, 4f)) }
        val second = directFloatBuffer(4) { put(floatArrayOf(5f, 6f, 7f, 8f)) }
        assertNotSame(first, second)
    }

    @Test
    fun `directIntBuffer produces a direct native-order fresh buffer ready to read`() {
        val indices = intArrayOf(0, 2, 1, 0, 3, 2)
        val buffer = directIntBuffer(indices.size) {
            put(indices)
            flip()
        }
        assertTrue(buffer.isDirect)
        assertEquals(ByteOrder.nativeOrder(), buffer.order())
        assertEquals(0, buffer.position())
        assertEquals(indices.size, buffer.limit())
        val read = IntArray(indices.size)
        buffer.get(read)
        assertEquals(indices.toList(), read.toList())
        assertNotSame(buffer, directIntBuffer(indices.size) { put(indices) })
    }

    // ── Call-site regression: setVertices / setIndices must route through the direct helpers ────

    private val sourceFile: File by lazy {
        // Resolved relative to the gradle workdir for sceneview unit tests.
        File("src/main/java/io/github/sceneview/geometries/Geometry.kt")
            .takeIf { it.exists() }
            ?: File("sceneview/src/main/java/io/github/sceneview/geometries/Geometry.kt")
    }

    private val source: String by lazy { sourceFile.readText() }

    @Test
    fun `source compiles in expected layout`() {
        assertTrue("Could not locate Geometry.kt at ${sourceFile.absolutePath}", sourceFile.exists())
        assertTrue(source.contains("fun VertexBuffer.setVertices("))
        assertTrue(source.contains("fun IndexBuffer.setIndices("))
    }

    @Test
    fun `no heap FloatBuffer or IntBuffer allocation remains in the upload path (#3715)`() {
        // FloatBuffer.allocate(...) / IntBuffer.allocate(...) are heap allocations — the bug this
        // test guards against. Re-introducing either in this file means an upload path went back
        // to double-pinning a Java array on every frame.
        assertFalse(
            "FloatBuffer.allocate(...) re-appeared in Geometry.kt — setBufferAt must be handed " +
                "a direct buffer (directFloatBuffer), see #3715.",
            Regex("""FloatBuffer\.allocate\(""").containsMatchIn(source),
        )
        assertFalse(
            "IntBuffer.allocate(...) re-appeared in Geometry.kt — setBuffer must be handed a " +
                "direct buffer (directIntBuffer), see #3715.",
            Regex("""IntBuffer\.allocate\(""").containsMatchIn(source),
        )
    }

    @Test
    fun `setVertices uploads position, tangent, uv and color through directFloatBuffer`() {
        val body = extractFunctionBody("fun VertexBuffer.setVertices(")
        // One directFloatBuffer( call per attribute stream the function can emit: position
        // (unconditional), tangent, uv, color.
        val callCount = Regex("""directFloatBuffer\(""").findAll(body).count()
        assertEquals(
            "setVertices must build every attribute stream (position, tangent, uv, color) " +
                "through directFloatBuffer — found $callCount call(s).",
            4,
            callCount,
        )
    }

    @Test
    fun `setIndices uploads through directIntBuffer`() {
        val body = extractFunctionBody("fun IndexBuffer.setIndices(")
        assertTrue(
            "setIndices must build the index stream through directIntBuffer.",
            body.contains("directIntBuffer("),
        )
    }

    /** Walks braces from the first `{` after [signature] to the matching `}`. */
    private fun extractFunctionBody(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("Could not find '$signature' in Geometry.kt", start >= 0)
        val bodyStart = source.indexOf('{', start)
        assertTrue("'$signature' has no opening brace", bodyStart >= 0)
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
