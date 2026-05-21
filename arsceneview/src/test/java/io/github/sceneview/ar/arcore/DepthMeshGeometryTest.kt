package io.github.sceneview.ar.arcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * Pins the pure depth→mesh math behind [DepthMeshNode] (#1739).
 *
 * The ARCore [com.google.ar.core.Frame] / depth [android.media.Image] plumbing cannot run in a
 * JVM unit test, so [buildDepthMeshGeometry] and [isEdgeDiscontinuity] are `internal` and verified
 * here in isolation — a sign error in the unprojection or a stale edge threshold would otherwise
 * only surface on a depth-capable device.
 */
class DepthMeshGeometryTest {

    /** Synthetic pinhole intrinsics — fx = fy = 100 px, principal point at (50, 40). */
    private val fx = 100f
    private val fy = 100f
    private val cx = 50f
    private val cy = 40f

    /** Helper — wraps a depth array (millimeters) into a tightly-packed ShortBuffer. */
    private fun depthBuffer(depthsMillimeters: IntArray, width: Int): ShortBuffer {
        // Allocate at least 2 bytes so an "empty" depth image still has a valid ByteBuffer.
        val byteCount = (depthsMillimeters.size * 2).coerceAtLeast(2)
        val bytes = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        val shorts = bytes.asShortBuffer()
        depthsMillimeters.forEach { shorts.put(it.toShort()) }
        shorts.rewind()
        return shorts
    }

    // ── isEdgeDiscontinuity ───────────────────────────────────────────────────────────────────

    @Test
    fun `flat surface is not a discontinuity`() {
        assertFalse(isEdgeDiscontinuity(2.0f, 2.0f, 2.0f, thresholdMeters = 0.10f))
        assertFalse(isEdgeDiscontinuity(2.0f, 2.05f, 2.04f, thresholdMeters = 0.10f))
    }

    @Test
    fun `depth jump above the threshold IS a discontinuity`() {
        // 1.0 m → 2.0 m across a triangle — a doorway edge.
        assertTrue(isEdgeDiscontinuity(1.0f, 1.0f, 2.0f, thresholdMeters = 0.10f))
        // 1.5 m → 1.2 m across the threshold of 0.1 m.
        assertTrue(isEdgeDiscontinuity(1.5f, 1.3f, 1.2f, thresholdMeters = 0.10f))
    }

    @Test
    fun `discontinuity at the threshold boundary is NOT culled`() {
        // Spread == threshold → keep the triangle (the threshold is "strictly greater than").
        // Use values whose subtraction is exact in IEEE-754 to avoid floating-point noise.
        assertFalse(isEdgeDiscontinuity(1.0f, 1.0f, 1.25f, thresholdMeters = 0.25f))
        // Spread just under the threshold — also kept.
        assertFalse(isEdgeDiscontinuity(1.0f, 1.0f, 1.249f, thresholdMeters = 0.25f))
    }

    // ── buildDepthMeshGeometry ────────────────────────────────────────────────────────────────

    @Test
    fun `empty depth image returns an empty mesh`() {
        val mesh = buildDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(0), width = 0),
            depthWidth = 0,
            depthHeight = 0,
            rowStrideShorts = 0,
            fx = fx, fy = fy, cx = cx, cy = cy,
        )
        assertEquals(0, mesh.vertexCount)
        assertEquals(0, mesh.triangleCount)
    }

    @Test
    fun `flat plane at constant depth produces a fully-stitched grid`() {
        // 4×4 depth image, all samples at 2 m (2000 mm), stride = 1 → 4×4 vertex grid.
        val width = 4
        val height = 4
        val mesh = buildDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(width * height) { 2000 }, width),
            depthWidth = width,
            depthHeight = height,
            rowStrideShorts = width,
            fx = fx, fy = fy, cx = cx, cy = cy,
            stride = 1,
            edgeThresholdMeters = 0.10f,
        )

        assertEquals(width * height, mesh.vertexCount)
        // (width-1) * (height-1) quads, 2 triangles each, all kept (constant depth).
        assertEquals((width - 1) * (height - 1) * 2, mesh.triangleCount)
    }

    @Test
    fun `flat plane vertices match the manual unprojection`() {
        val width = 3
        val height = 3
        val depthMm = 2000
        val mesh = buildDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(width * height) { depthMm }, width),
            depthWidth = width,
            depthHeight = height,
            rowStrideShorts = width,
            fx = fx, fy = fy, cx = cx, cy = cy,
            stride = 1,
        )

        // The principal point happens to be at (50, 40) but the depth image only covers (0..2),
        // so all vertices sit far off-axis. Pick the centre pixel (1, 1) and check its position
        // matches `unprojectDepthPixel`.
        val expected = unprojectDepthPixel(
            pixelX = 1, pixelY = 1, depthMeters = depthMm / 1000f,
            fx = fx, fy = fy, cx = cx, cy = cy,
        )
        val centreIndex = (1 * width + 1) * 3
        assertEquals(expected.x, mesh.positions[centreIndex], 1e-5f)
        assertEquals(expected.y, mesh.positions[centreIndex + 1], 1e-5f)
        assertEquals(expected.z, mesh.positions[centreIndex + 2], 1e-5f)
    }

    @Test
    fun `triangles spanning a depth discontinuity are culled`() {
        // 2×2 depth image with one corner 1 m closer to the camera than the other three — the
        // two triangles of the quad each touch a depth jump, so BOTH should be culled.
        val width = 2
        val height = 2
        val mesh = buildDepthMeshGeometry(
            depthBuffer = depthBuffer(intArrayOf(1000, 2000, 2000, 2000), width),
            depthWidth = width,
            depthHeight = height,
            rowStrideShorts = width,
            fx = fx, fy = fy, cx = cx, cy = cy,
            stride = 1,
            edgeThresholdMeters = 0.10f,
        )

        assertEquals(4, mesh.vertexCount) // all 4 grid vertices are still allocated
        assertEquals(0, mesh.triangleCount) // both triangles cross a 1 m jump
    }

    @Test
    fun `flat plane survives when the edge threshold is raised`() {
        // Same depth-jump grid as above, but with a 2 m threshold — both triangles should be kept.
        val mesh = buildDepthMeshGeometry(
            depthBuffer = depthBuffer(intArrayOf(1000, 2000, 2000, 2000), width = 2),
            depthWidth = 2,
            depthHeight = 2,
            rowStrideShorts = 2,
            fx = fx, fy = fy, cx = cx, cy = cy,
            stride = 1,
            edgeThresholdMeters = 2.0f,
        )
        assertEquals(2, mesh.triangleCount)
    }

    @Test
    fun `zero-depth samples (no return) drop their incident triangles`() {
        // Bottom-right sample has no depth (0 mm) — the only quad's two triangles both reference
        // an invalid vertex and should be culled.
        val mesh = buildDepthMeshGeometry(
            depthBuffer = depthBuffer(intArrayOf(2000, 2000, 2000, 0), width = 2),
            depthWidth = 2,
            depthHeight = 2,
            rowStrideShorts = 2,
            fx = fx, fy = fy, cx = cx, cy = cy,
            stride = 1,
        )
        assertEquals(0, mesh.triangleCount)
        // The invalid vertex sits at (0,0,0) — we keep it in the array so indices remain stable.
        val invalidBase = (1 * 2 + 1) * 3
        assertEquals(0f, mesh.positions[invalidBase], 0f)
        assertEquals(0f, mesh.positions[invalidBase + 1], 0f)
        assertEquals(0f, mesh.positions[invalidBase + 2], 0f)
    }

    @Test
    fun `stride down-samples the grid`() {
        // 8×8 depth image, all at 2 m. Stride 4 → ceil(8/4)=2 vertex columns/rows, so 2×2 grid.
        val mesh = buildDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(8 * 8) { 2000 }, width = 8),
            depthWidth = 8,
            depthHeight = 8,
            rowStrideShorts = 8,
            fx = fx, fy = fy, cx = cx, cy = cy,
            stride = 4,
        )
        assertEquals(2, mesh.width)
        assertEquals(2, mesh.height)
        assertEquals(4, mesh.vertexCount)
        assertEquals(2, mesh.triangleCount)
    }

    @Test
    fun `triangles are wound CCW when viewed from the camera`() {
        // 2×2 grid at constant depth — pick the only quad and check the winding of its first
        // triangle (tl, bl, br) when viewed from the camera (-Z is forward). A CCW triangle has
        // a normal pointing toward +Z (back at the camera).
        val mesh = buildDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(4) { 2000 }, width = 2),
            depthWidth = 2,
            depthHeight = 2,
            rowStrideShorts = 2,
            fx = fx, fy = fy, cx = cx, cy = cy,
            stride = 1,
        )
        assertEquals(6, mesh.indices.size) // 2 triangles
        // Triangle 0: tl(0,0) bl(1,1) br(1,2) — but vertex layout is (gx,gy) flattened row-major
        // so vertex 0 = (0,0) top-left, vertex 1 = (1,0) top-right, vertex 2 = (0,1) bottom-left,
        // vertex 3 = (1,1) bottom-right. Triangle 0 = (tl, bl, br) = (0, 2, 3). Check it.
        assertEquals(0, mesh.indices[0])
        assertEquals(2, mesh.indices[1])
        assertEquals(3, mesh.indices[2])
        // Triangle 1: (tl, br, tr) = (0, 3, 1)
        assertEquals(0, mesh.indices[3])
        assertEquals(3, mesh.indices[4])
        assertEquals(1, mesh.indices[5])
    }

    @Test
    fun `depthMillimetersAt clamps out-of-bounds reads to zero`() {
        val buffer = depthBuffer(intArrayOf(1234, 5678), width = 2)
        // In-bounds reads come through.
        assertEquals(1234, depthMillimetersAt(buffer, 0, 0, rowStrideShorts = 2, width = 2, height = 1))
        assertEquals(5678, depthMillimetersAt(buffer, 1, 0, rowStrideShorts = 2, width = 2, height = 1))
        // Out-of-bounds reads return 0 rather than throwing.
        assertEquals(0, depthMillimetersAt(buffer, -1, 0, rowStrideShorts = 2, width = 2, height = 1))
        assertEquals(0, depthMillimetersAt(buffer, 2, 0, rowStrideShorts = 2, width = 2, height = 1))
        assertEquals(0, depthMillimetersAt(buffer, 0, 1, rowStrideShorts = 2, width = 2, height = 1))
    }

    @Test
    fun `unsigned 16-bit depths above 32767 read as positive`() {
        // A 5 m depth (5000 mm) fits in 16-bit unsigned but exceeds Short.MAX_VALUE.
        // ARCore packs depth as **unsigned** 16-bit, so the raw Short is negative — the helper
        // must unmask it back to a positive int.
        val deep = 40000
        val buffer = depthBuffer(intArrayOf(deep), width = 1)
        assertEquals(deep, depthMillimetersAt(buffer, 0, 0, rowStrideShorts = 1, width = 1, height = 1))
    }

    @Test
    fun `mesh respects rowStride when the depth plane is padded`() {
        // Some devices pack the depth plane with row padding. Build a 2×2 logical grid in a
        // buffer with a row stride of 3 shorts.
        val width = 2
        val height = 2
        val rowStride = 3
        val depths = IntArray(rowStride * height)
        // Fill the live cells; the padding cell (index 2) stays at 0 (which would be invalid).
        depths[0] = 2000; depths[1] = 2000
        depths[3] = 2000; depths[4] = 2000
        val mesh = buildDepthMeshGeometry(
            depthBuffer = depthBuffer(depths, width = rowStride),
            depthWidth = width,
            depthHeight = height,
            rowStrideShorts = rowStride,
            fx = fx, fy = fy, cx = cx, cy = cy,
            stride = 1,
        )
        // All 4 logical samples are valid → 2 triangles emitted.
        assertEquals(2, mesh.triangleCount)
    }

    @Test
    fun `invalid stride throws`() {
        try {
            buildDepthMeshGeometry(
                depthBuffer = depthBuffer(intArrayOf(2000), width = 1),
                depthWidth = 1, depthHeight = 1, rowStrideShorts = 1,
                fx = fx, fy = fy, cx = cx, cy = cy,
                stride = 0,
            )
            assertNull("Expected IllegalArgumentException for stride = 0", "")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `equality compares contents not identity`() {
        val a = buildDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(4) { 2000 }, width = 2),
            depthWidth = 2, depthHeight = 2, rowStrideShorts = 2,
            fx = fx, fy = fy, cx = cx, cy = cy,
            stride = 1,
        )
        val b = buildDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(4) { 2000 }, width = 2),
            depthWidth = 2, depthHeight = 2, rowStrideShorts = 2,
            fx = fx, fy = fy, cx = cx, cy = cy,
            stride = 1,
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val different = buildDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(4) { 3000 }, width = 2),
            depthWidth = 2, depthHeight = 2, rowStrideShorts = 2,
            fx = fx, fy = fy, cx = cx, cy = cy,
            stride = 1,
        )
        assertNotEquals(a, different)
    }
}
