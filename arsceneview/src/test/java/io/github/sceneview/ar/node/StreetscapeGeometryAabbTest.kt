package io.github.sceneview.ar.node

import com.google.android.filament.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Streetscape Geometry "AABB can't be empty" abort (Crashlytics, 4.51.0-main, AR Scene Geometry).
 *
 * `StreetscapeGeometryNode` built its `MeshNode` without a bounding box. `MeshNode` then turned
 * culling off but left Filament's default `receiveShadows(true)`, and Filament's
 * `RenderableManager.Builder.build` aborts for an empty AABB on a shadow receiver — so every
 * Streetscape geometry ARCore delivered killed the app.
 *
 * `StreetscapeGeometry` and Filament renderables are JNI-only, so the box math and the
 * empty-mesh decision live in pure helpers pinned here, plus source contracts on the node and
 * on `MeshNode`'s no-box branch.
 */
class StreetscapeGeometryAabbTest {

    // ── Empty vertex list ─────────────────────────────────────────────────────────────────────

    @Test
    fun `empty vertex list yields no box`() {
        assertNull(computeStreetscapeAabb(positions(), vertexCount = 0))
    }

    @Test
    fun `vertex count larger than the buffer is clamped, an empty buffer still yields no box`() {
        assertNull(computeStreetscapeAabb(positions(), vertexCount = 12))
    }

    @Test
    fun `only non-finite vertices yield no box`() {
        val nan = positions(Float.NaN, 0f, 0f, 1f, Float.POSITIVE_INFINITY, 2f)
        assertNull(computeStreetscapeAabb(nan, vertexCount = 2))
    }

    @Test
    fun `empty mesh is not renderable`() {
        assertFalse(isStreetscapeMeshRenderable(vertexCount = 0, indexCount = 0))
        assertFalse(isStreetscapeMeshRenderable(vertexCount = 0, indexCount = 3))
        assertFalse(isStreetscapeMeshRenderable(vertexCount = 3, indexCount = 0))
        assertFalse(
            "fewer than three indices is not a triangle",
            isStreetscapeMeshRenderable(vertexCount = 3, indexCount = 2)
        )
        assertTrue(isStreetscapeMeshRenderable(vertexCount = 3, indexCount = 3))
    }

    // ── Non-empty vertex list ─────────────────────────────────────────────────────────────────

    @Test
    fun `non-empty vertex list bounds every vertex`() {
        val box = computeStreetscapeAabb(
            positions(
                -2f, 0f, 1f,
                4f, 6f, -3f,
                1f, 2f, 5f,
            ),
            vertexCount = 3
        )
        assertNotNull(box)
        // x: -2..4, y: 0..6, z: -3..5
        assertCenter(box!!, 1f, 3f, 1f)
        assertHalfExtent(box, 3f, 3f, 4f)
    }

    @Test
    fun `flat terrain patch keeps a non-empty box on its flat axis`() {
        // A terrain tile: every vertex on y = -1.5. A zero half-extent is an empty box to Filament.
        val box = computeStreetscapeAabb(
            positions(
                0f, -1.5f, 0f,
                10f, -1.5f, 0f,
                10f, -1.5f, 10f,
                0f, -1.5f, 10f,
            ),
            vertexCount = 4
        )!!
        assertCenter(box, 5f, -1.5f, 5f)
        assertEquals(5f, box.halfExtent[0], EPS)
        assertEquals(STREETSCAPE_MIN_AABB_HALF_EXTENT_M, box.halfExtent[1], EPS)
        assertEquals(5f, box.halfExtent[2], EPS)
    }

    @Test
    fun `single vertex yields a minimal cube around it`() {
        val box = computeStreetscapeAabb(positions(3f, 4f, 5f), vertexCount = 1)!!
        assertCenter(box, 3f, 4f, 5f)
        assertHalfExtent(
            box,
            STREETSCAPE_MIN_AABB_HALF_EXTENT_M,
            STREETSCAPE_MIN_AABB_HALF_EXTENT_M,
            STREETSCAPE_MIN_AABB_HALF_EXTENT_M
        )
    }

    @Test
    fun `only the first vertexCount vertices are bounded and non-finite ones are skipped`() {
        val box = computeStreetscapeAabb(
            positions(
                0f, 0f, 0f,
                Float.NaN, 100f, 100f,
                2f, 2f, 2f,
                // Past vertexCount: must be ignored.
                1000f, 1000f, 1000f,
            ),
            vertexCount = 3
        )!!
        assertCenter(box, 1f, 1f, 1f)
        assertHalfExtent(box, 1f, 1f, 1f)
    }

    @Test
    fun `reading the box leaves the buffer position unchanged`() {
        val buffer = positions(0f, 0f, 0f, 1f, 1f, 1f)
        buffer.position(3)
        computeStreetscapeAabb(buffer, vertexCount = 2)
        assertEquals(3, buffer.position())
    }

    // ── Source contracts ──────────────────────────────────────────────────────────────────────

    @Test
    fun `StreetscapeGeometryNode passes the computed box and skips empty meshes`() {
        val source = File("src/main/java/io/github/sceneview/ar/node/StreetscapeGeometryNode.kt")
            .readText()
        assertTrue(
            "the node must compute the box from the mesh vertices",
            source.contains("computeStreetscapeAabb(mesh.vertexList, vertexCount)")
        )
        assertTrue(
            "the node must hand the box to MeshNode",
            source.contains("boundingBox = boundingBox")
        )
        assertTrue(
            "the node must build no renderable for an empty mesh",
            source.contains("if (!isStreetscapeMeshRenderable(vertexCount, indexCount)) return null")
        )
        assertTrue(
            "a geometry update must rebuild (or drop) the renderable",
            source.contains("rebuildMeshIfChanged()")
        )
    }

    @Test
    fun `MeshNode without a box disables culling and both shadow flags`() {
        // JVM tests run with the module directory as CWD; MeshNode lives in :sceneview.
        val source = File("../sceneview/src/main/java/io/github/sceneview/node/MeshNode.kt")
            .readText()
        val elseIdx = source.indexOf("} else {", source.indexOf("if (box != null)"))
        assertTrue("MeshNode must branch on the bounding box", elseIdx >= 0)
        val noBoxBranch = source.substring(elseIdx, source.indexOf("}", elseIdx + 8))
        listOf("culling(false)", "castShadows(false)", "receiveShadows(false)").forEach {
            assertTrue("MeshNode's no-box branch must call $it", noBoxBranch.contains(it))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────

    private fun positions(vararg xyz: Float): FloatBuffer = ByteBuffer
        .allocateDirect(xyz.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(xyz)
            rewind()
        }

    private fun assertCenter(box: Box, x: Float, y: Float, z: Float) {
        assertEquals(x, box.center[0], EPS)
        assertEquals(y, box.center[1], EPS)
        assertEquals(z, box.center[2], EPS)
    }

    private fun assertHalfExtent(box: Box, x: Float, y: Float, z: Float) {
        assertEquals(x, box.halfExtent[0], EPS)
        assertEquals(y, box.halfExtent[1], EPS)
        assertEquals(z, box.halfExtent[2], EPS)
    }

    private companion object {
        const val EPS = 1e-6f
    }
}
