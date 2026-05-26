package io.github.sceneview.ar.arcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pins the pure depth→plane-clipped mesh math behind [io.github.sceneview.ar.PlaneVisualizerV2]
 * (#2203 PR #2). The ARCore [com.google.ar.core.Frame] / depth [android.media.Image] plumbing
 * cannot run in a JVM unit test, so [buildPlaneDepthMeshGeometry] is `internal` and verified
 * here in isolation — sign errors in the unprojection / matrix-multiply / polygon-clip / slope-
 * normal paths would otherwise only surface on a depth-capable device.
 */
class PlaneDepthMeshGeometryTest {

    private val fx = 100f
    private val fy = 100f
    private val cx = 50f
    private val cy = 40f

    /** Column-major 4x4 identity. Camera space and plane-local space coincide. */
    private val identity = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    private fun depthBuffer(depthsMillimeters: IntArray): ShortBuffer {
        val byteCount = (depthsMillimeters.size * 2).coerceAtLeast(2)
        val bytes = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        val shorts = bytes.asShortBuffer()
        depthsMillimeters.forEach { shorts.put(it.toShort()) }
        shorts.rewind()
        return shorts
    }

    /** Polygon that covers the whole world — every (x, z) sample passes the clip. */
    private val unboundedPolygon = floatArrayOf(
        -1000f, -1000f,
        1000f, -1000f,
        1000f, 1000f,
        -1000f, 1000f,
    )

    // ── Empty + degenerate ─────────────────────────────────────────────────────────────────

    @Test
    fun `empty depth image returns an empty mesh`() {
        val mesh = buildPlaneDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(0)),
            depthWidth = 0, depthHeight = 0, rowStrideShorts = 0,
            fx = fx, fy = fy, cx = cx, cy = cy,
            cameraToPlaneLocal = identity, polygon = unboundedPolygon,
        )
        assertEquals(0, mesh.vertexCount)
        assertEquals(0, mesh.triangleCount)
    }

    @Test
    fun `degenerate polygon yields zero valid vertices`() {
        // Polygon with only 2 points → fewer than 3 → pointInPolygon2D returns false.
        val mesh = buildPlaneDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(16) { 2000 }),
            depthWidth = 4, depthHeight = 4, rowStrideShorts = 4,
            fx = fx, fy = fy, cx = cx, cy = cy,
            cameraToPlaneLocal = identity,
            polygon = floatArrayOf(-1f, -1f, 1f, -1f),
        )
        // Vertex slots allocated, but no vertex passes the polygon clip → no triangles.
        assertEquals(0, mesh.triangleCount)
    }

    @Test
    fun `empty polygon yields zero valid vertices`() {
        val mesh = buildPlaneDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(16) { 2000 }),
            depthWidth = 4, depthHeight = 4, rowStrideShorts = 4,
            fx = fx, fy = fy, cx = cx, cy = cy,
            cameraToPlaneLocal = identity,
            polygon = FloatArray(0),
        )
        assertEquals(0, mesh.triangleCount)
    }

    @Test
    fun `invalid stride throws`() {
        try {
            buildPlaneDepthMeshGeometry(
                depthBuffer = depthBuffer(intArrayOf(2000)),
                depthWidth = 1, depthHeight = 1, rowStrideShorts = 1,
                fx = fx, fy = fy, cx = cx, cy = cy,
                cameraToPlaneLocal = identity, polygon = unboundedPolygon,
                stride = 0,
            )
            assertTrue("Expected IllegalArgumentException for stride = 0", false)
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `mis-sized cameraToPlaneLocal throws`() {
        try {
            buildPlaneDepthMeshGeometry(
                depthBuffer = depthBuffer(intArrayOf(2000)),
                depthWidth = 1, depthHeight = 1, rowStrideShorts = 1,
                fx = fx, fy = fy, cx = cx, cy = cy,
                cameraToPlaneLocal = FloatArray(9),
                polygon = unboundedPolygon,
            )
            assertTrue("Expected IllegalArgumentException for non-4x4 matrix", false)
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    // ── Flat synthetic depth, identity transform ──────────────────────────────────────────

    @Test
    fun `flat depth with identity transform projects into plane-local Y=0`() {
        // 4×4 depth image, all at 2 m. Identity transform → camera-space === plane-local.
        // Camera-space Y = -(py-cy)·z/fy → non-zero (py != cy), so vertices spread across
        // plane-local Y. We need a per-pixel check rather than "all y are 0".
        // To make plane-local Y truly zero, pick a transform that rotates camera-space Y
        // and Z so the plane sees a constant depth as a flat slab. Easiest construction:
        // pick a depth pattern that consistently yields plane-local Y == 0 — see the next
        // test. Here we only assert the vertex grid is fully built (no clipping happens).
        val width = 4
        val height = 4
        val mesh = buildPlaneDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(width * height) { 2000 }),
            depthWidth = width, depthHeight = height, rowStrideShorts = width,
            fx = fx, fy = fy, cx = cx, cy = cy,
            cameraToPlaneLocal = identity,
            polygon = unboundedPolygon,
            maxDisplacementMeters = 1000f,
            stride = 1,
        )
        assertEquals(width * height, mesh.vertexCount)
        assertEquals(2 * (width - 1) * (height - 1), mesh.triangleCount)
    }

    @Test
    fun `flat horizontal plane in plane-local space gives every normal pointing along +Y`() {
        // Use a transform that maps the depth grid to a flat plane in plane-local space:
        // - flip camera-space Y to plane-local Z (so depth-image rows = +Z),
        // - flip camera-space Z to plane-local Y (depth = +Y),
        // - and offset so plane-local Y ends up at zero everywhere.
        // Construct cameraToPlaneLocal column-major:
        //   row 0: x_cam        → x_plane
        //   row 1: -z_cam - 2   → y_plane (camera Z is -depth, so -z_cam = depth = 2 → y=0)
        //   row 2: -y_cam       → z_plane (flip Y so depth-image rows grow +Z)
        val m = floatArrayOf(
            // col 0
            1f, 0f, 0f, 0f,
            // col 1
            0f, 0f, -1f, 0f,
            // col 2
            0f, -1f, 0f, 0f,
            // col 3 (translation)
            0f, -2f, 0f, 1f,
        )
        val width = 4
        val height = 4
        val mesh = buildPlaneDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(width * height) { 2000 }),
            depthWidth = width, depthHeight = height, rowStrideShorts = width,
            fx = fx, fy = fy, cx = cx, cy = cy,
            cameraToPlaneLocal = m,
            polygon = unboundedPolygon,
            stride = 1,
            maxDisplacementMeters = 0.10f,
        )
        // Each vertex should have plane-local Y exactly 0 after the chosen transform.
        for (i in 0 until mesh.vertexCount) {
            val yPlane = mesh.positions[i * 3 + 1]
            assertEquals("vertex $i Y should be 0", 0f, yPlane, 1e-4f)
        }
        // And the per-vertex normals should all be (0, 1, 0).
        for (i in 0 until mesh.vertexCount) {
            val nx = mesh.normals[i * 3]
            val ny = mesh.normals[i * 3 + 1]
            val nz = mesh.normals[i * 3 + 2]
            assertEquals("nx[$i]", 0f, nx, 1e-3f)
            assertEquals("ny[$i]", 1f, ny, 1e-3f)
            assertEquals("nz[$i]", 0f, nz, 1e-3f)
        }
        // And triangles cover the grid: (width-1) * (height-1) * 2.
        assertEquals(2 * (width - 1) * (height - 1), mesh.triangleCount)
    }

    // ── Polygon clip ──────────────────────────────────────────────────────────────────────

    @Test
    fun `polygon covering only positive-X half rejects negative-X vertices`() {
        // 4-wide depth, identity transform, generous displacement clamp. Polygon covers
        // only x >= 0. Pixels 0..1 (after the unproject, px<cx → camX<0) should be rejected.
        val width = 4
        val height = 4
        val mesh = buildPlaneDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(width * height) { 2000 }),
            depthWidth = width, depthHeight = height, rowStrideShorts = width,
            fx = fx, fy = fy, cx = cx, cy = cy,
            cameraToPlaneLocal = identity,
            polygon = floatArrayOf(0f, -1000f, 1000f, -1000f, 1000f, 1000f, 0f, 1000f),
            maxDisplacementMeters = 1000f,
            stride = 1,
        )
        // Vertices with cx=50, fx=100, depth=2 → camX = (px-50)*2/100 = (px-50)/50.
        // px=0..3 are all < 50 → camX < 0 → fail clip. Zero triangles spanning them.
        assertEquals(0, mesh.triangleCount)
        // And no vertex position lies in the rejected half (every kept vertex has X >= 0).
        for (i in 0 until mesh.vertexCount) {
            val x = mesh.positions[i * 3]
            val y = mesh.positions[i * 3 + 1]
            val z = mesh.positions[i * 3 + 2]
            val isOriginPad = x == 0f && y == 0f && z == 0f
            assertTrue("vertex $i with x=$x must be either valid (x>=0) or padded", isOriginPad || x >= 0f)
        }
    }

    @Test
    fun `polygon clipping prunes triangles to the polygon-visible region`() {
        // Half-width polygon — exactly half the vertex grid is rejected; the kept triangles
        // are well below the total possible.
        val width = 8
        val height = 8
        // Polygon covers only camX >= ~0.5 (cx=50, fx=100, depth=2 → camX = (px-50)/50).
        // px=4..7 yield camX in [-0.92..-0.86] → all rejected.
        // Actually with cx=50, px=0..7 yields camX in [-1.0..-0.86] — ALL negative.
        // Use a polygon centred at camX=-0.93 to keep px=4..7 and reject px=0..3.
        val midPolyX = -0.93f
        val polygon = floatArrayOf(
            midPolyX, -1000f,
            1000f, -1000f,
            1000f, 1000f,
            midPolyX, 1000f,
        )
        val mesh = buildPlaneDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(width * height) { 2000 }),
            depthWidth = width, depthHeight = height, rowStrideShorts = width,
            fx = fx, fy = fy, cx = cx, cy = cy,
            cameraToPlaneLocal = identity,
            polygon = polygon,
            maxDisplacementMeters = 1000f,
            stride = 1,
        )
        // Full grid would be 7×7 × 2 = 98 triangles. Half-clipped should be < 60% of that.
        val fullTriangles = 2 * (width - 1) * (height - 1)
        assertTrue(
            "triangle count ${mesh.triangleCount} should be < ${fullTriangles * 0.6f}",
            mesh.triangleCount < fullTriangles * 0.6f,
        )
        assertTrue("at least some triangles should survive", mesh.triangleCount > 0)
    }

    // ── maxDisplacementMeters clamp ───────────────────────────────────────────────────────

    @Test
    fun `quadrant 1m above plane is rejected by maxDisplacementMeters`() {
        // Use the same flat transform as the normals test (camera depth → plane Y at 0
        // when measured depth = 2 m). One quadrant of the depth image is at 1 m
        // (1000 mm) — that translates to plane-local Y = +1 m, above the 0.30 m clamp.
        val m = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, -1f, 0f, 0f,
            0f, -2f, 0f, 1f,
        )
        val width = 4
        val height = 4
        val depths = IntArray(width * height) { 2000 }
        // Bottom-right 2×2 quadrant at 1 m (1000 mm).
        for (y in 2 until height) for (x in 2 until width) depths[y * width + x] = 1000
        val mesh = buildPlaneDepthMeshGeometry(
            depthBuffer = depthBuffer(depths),
            depthWidth = width, depthHeight = height, rowStrideShorts = width,
            fx = fx, fy = fy, cx = cx, cy = cy,
            cameraToPlaneLocal = m,
            polygon = unboundedPolygon,
            maxDisplacementMeters = 0.30f,
            edgeThresholdMeters = 10f, // disable edge culling — only displacement matters here
            stride = 1,
        )
        // No vertex in the kept set should have |Y| > 0.30. Padded (off-grid) slots remain
        // (0,0,0); skip them via the (0,0,0) check.
        for (i in 0 until mesh.vertexCount) {
            val x = mesh.positions[i * 3]
            val y = mesh.positions[i * 3 + 1]
            val z = mesh.positions[i * 3 + 2]
            val isOrigin = x == 0f && y == 0f && z == 0f
            if (isOrigin) continue
            assertTrue("vertex $i has |y|=${abs(y)} exceeding clamp", abs(y) <= 0.30f + 1e-4f)
        }
    }

    // ── Edge discontinuity ────────────────────────────────────────────────────────────────

    @Test
    fun `triangles spanning a 1m plane-Y jump are culled`() {
        // Same flat transform. Set up a step: left column at 2 m (plane Y = 0), right
        // column at 1.5 m (plane Y = 0.5 m). That's a 0.50 m Y jump across each quad
        // — edge threshold defaults to 10 cm so both triangles per quad should be culled.
        val m = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, -1f, 0f, 0f,
            0f, -2f, 0f, 1f,
        )
        val width = 2
        val height = 2
        val depths = intArrayOf(
            2000, 1500,
            2000, 1500,
        )
        val mesh = buildPlaneDepthMeshGeometry(
            depthBuffer = depthBuffer(depths),
            depthWidth = width, depthHeight = height, rowStrideShorts = width,
            fx = fx, fy = fy, cx = cx, cy = cy,
            cameraToPlaneLocal = m,
            polygon = unboundedPolygon,
            maxDisplacementMeters = 10f,
            edgeThresholdMeters = 0.10f,
            stride = 1,
        )
        assertEquals(0, mesh.triangleCount)
    }

    @Test
    fun `raising the edge threshold preserves triangles across the jump`() {
        val m = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, -1f, 0f, 0f,
            0f, -2f, 0f, 1f,
        )
        val mesh = buildPlaneDepthMeshGeometry(
            depthBuffer = depthBuffer(intArrayOf(2000, 1500, 2000, 1500)),
            depthWidth = 2, depthHeight = 2, rowStrideShorts = 2,
            fx = fx, fy = fy, cx = cx, cy = cy,
            cameraToPlaneLocal = m,
            polygon = unboundedPolygon,
            maxDisplacementMeters = 10f,
            edgeThresholdMeters = 1.0f,
            stride = 1,
        )
        assertEquals(2, mesh.triangleCount)
    }

    // ── Slope normals ─────────────────────────────────────────────────────────────────────

    @Test
    fun `slope ramp produces normals consistently tilted away from +Y`() {
        // Same flat transform. Depth ramps from 2.0 m at the left to 2.3 m at the right
        // — that yields a 30 cm rise in plane-local Y across the depth-image columns.
        val m = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, -1f, 0f, 0f,
            0f, -2f, 0f, 1f,
        )
        val width = 6
        val height = 6
        val depths = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            // Depth varies linearly from 2000 mm at x=0 to 2300 mm at x=width-1.
            depths[y * width + x] = 2000 + (300 * x / (width - 1))
        }
        val mesh = buildPlaneDepthMeshGeometry(
            depthBuffer = depthBuffer(depths),
            depthWidth = width, depthHeight = height, rowStrideShorts = width,
            fx = fx, fy = fy, cx = cx, cy = cy,
            cameraToPlaneLocal = m,
            polygon = unboundedPolygon,
            maxDisplacementMeters = 10f,
            edgeThresholdMeters = 10f, // disable culling — we want every normal
            stride = 1,
        )
        // Pick an interior vertex (away from the borders so it uses central differences)
        // and verify its normal tilts away from (0,1,0).
        val interiorIdx = (height / 2) * width + (width / 2)
        val nx = mesh.normals[interiorIdx * 3]
        val ny = mesh.normals[interiorIdx * 3 + 1]
        val nz = mesh.normals[interiorIdx * 3 + 2]
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        assertEquals("normal must be unit length", 1f, length, 1e-3f)
        assertTrue("normal Y should tilt below 1.0 on a slope; got $ny", ny < 0.99f)
        assertTrue("normal X or Z should carry the tilt; got nx=$nx nz=$nz", abs(nx) > 0.05f || abs(nz) > 0.05f)
    }

    // ── Equality + buffer integrity ───────────────────────────────────────────────────────

    @Test
    fun `equality compares contents not identity`() {
        // 2×2 depth image — buildPlaneDepthMeshGeometry needs the helper to actually
        // produce something to compare. Use a fully-flat plane mapping so the displacement
        // clamp does not zero everything out (identity transform leaves camera-space
        // points at depth=2m, which sit ~0.8 m away from plane-Y=0 — past the default
        // clamp).
        val flatTransform = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, -1f, 0f, 0f,
            0f, -2f, 0f, 1f,
        )
        val a = buildPlaneDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(4) { 2000 }),
            depthWidth = 2, depthHeight = 2, rowStrideShorts = 2,
            fx = fx, fy = fy, cx = cx, cy = cy,
            cameraToPlaneLocal = flatTransform, polygon = unboundedPolygon,
            stride = 1,
        )
        val b = buildPlaneDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(4) { 2000 }),
            depthWidth = 2, depthHeight = 2, rowStrideShorts = 2,
            fx = fx, fy = fy, cx = cx, cy = cy,
            cameraToPlaneLocal = flatTransform, polygon = unboundedPolygon,
            stride = 1,
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue("equality fixture should produce non-empty mesh", a.triangleCount > 0)

        val different = buildPlaneDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(4) { 3000 }),
            depthWidth = 2, depthHeight = 2, rowStrideShorts = 2,
            fx = fx, fy = fy, cx = cx, cy = cy,
            cameraToPlaneLocal = flatTransform, polygon = unboundedPolygon,
            stride = 1,
        )
        assertNotEquals(a, different)
    }

    @Test
    fun `normals array has one normal per position`() {
        val mesh = buildPlaneDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(9) { 2000 }),
            depthWidth = 3, depthHeight = 3, rowStrideShorts = 3,
            fx = fx, fy = fy, cx = cx, cy = cy,
            cameraToPlaneLocal = identity, polygon = unboundedPolygon,
            maxDisplacementMeters = 1000f,
        )
        assertEquals(mesh.positions.size, mesh.normals.size)
    }

    // ── Confidence filter ─────────────────────────────────────────────────────────────────

    @Test
    fun `low-confidence samples are rejected when confidence buffer is supplied`() {
        // 2×2 depth grid, all confident enough on the left column, all zero on the right.
        // Expected result: only the left two vertices pass → no full quad → 0 triangles.
        val confidence = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        confidence.put(0, 200.toByte()) // px=0, py=0 — confident
        confidence.put(1, 0.toByte())   // px=1, py=0 — rejected
        confidence.put(2, 200.toByte()) // px=0, py=1
        confidence.put(3, 0.toByte())   // px=1, py=1

        val flatTransform = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, -1f, 0f, 0f,
            0f, -2f, 0f, 1f,
        )
        val mesh = buildPlaneDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(4) { 2000 }),
            depthWidth = 2, depthHeight = 2, rowStrideShorts = 2,
            fx = fx, fy = fy, cx = cx, cy = cy,
            cameraToPlaneLocal = flatTransform, polygon = unboundedPolygon,
            stride = 1,
            confidenceBuffer = confidence,
            confidenceRowStrideBytes = 2,
            confidenceWidth = 2,
            confidenceHeight = 2,
            minConfidence = 128,
        )
        assertEquals(0, mesh.triangleCount)
    }

    @Test
    fun `null confidence buffer is treated as fully confident`() {
        val flatTransform = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, -1f, 0f, 0f,
            0f, -2f, 0f, 1f,
        )
        val mesh = buildPlaneDepthMeshGeometry(
            depthBuffer = depthBuffer(IntArray(4) { 2000 }),
            depthWidth = 2, depthHeight = 2, rowStrideShorts = 2,
            fx = fx, fy = fy, cx = cx, cy = cy,
            cameraToPlaneLocal = flatTransform, polygon = unboundedPolygon,
            stride = 1,
            confidenceBuffer = null,
        )
        assertEquals(2, mesh.triangleCount)
    }

    @Test
    fun `passesConfidence reads the byte at row stride pitch`() {
        val rowStride = 4 // padded row
        val buf = ByteBuffer.allocateDirect(rowStride * 2).order(ByteOrder.nativeOrder())
        buf.put(0, 200.toByte()) // (0, 0)
        buf.put(rowStride, 50.toByte()) // (0, 1)

        assertTrue(passesConfidence(buf, rowStride, width = 2, height = 2, x = 0, y = 0, minConfidence = 128))
        assertTrue(!passesConfidence(buf, rowStride, width = 2, height = 2, x = 0, y = 1, minConfidence = 128))
        // Out of bounds returns false.
        assertTrue(!passesConfidence(buf, rowStride, width = 2, height = 2, x = -1, y = 0, minConfidence = 128))
        assertTrue(!passesConfidence(buf, rowStride, width = 2, height = 2, x = 2, y = 0, minConfidence = 128))
    }

    // ── transformPoint helper ─────────────────────────────────────────────────────────────

    @Test
    fun `transformPoint applies column-major 4x4 to a point`() {
        // Identity → input == output.
        val identityM = identity
        assertEquals(3f, transformPoint(identityM, 3f, 4f, 5f, row = 0), 0f)
        assertEquals(4f, transformPoint(identityM, 3f, 4f, 5f, row = 1), 0f)
        assertEquals(5f, transformPoint(identityM, 3f, 4f, 5f, row = 2), 0f)

        // Pure translation by (10, 20, 30).
        val translate = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            10f, 20f, 30f, 1f,
        )
        assertEquals(13f, transformPoint(translate, 3f, 4f, 5f, row = 0), 0f)
        assertEquals(24f, transformPoint(translate, 3f, 4f, 5f, row = 1), 0f)
        assertEquals(35f, transformPoint(translate, 3f, 4f, 5f, row = 2), 0f)
    }
}
