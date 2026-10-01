package io.github.sceneview.ar.node

import com.google.android.filament.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
 * The box math and the empty-mesh decision are pure helpers pinned here. The renderable build,
 * rebuild and drop on real Filament are covered by the instrumented
 * `StreetscapeMeshRenderableTest` (arsceneview) and `MeshNodeBoundingBoxTest` (sceneview).
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
        // A terrain tile: every vertex on y = -1.5. Not empty to Filament (length2(halfExtent) > 0),
        // but the floor keeps a non-zero thickness on the flat axis.
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
