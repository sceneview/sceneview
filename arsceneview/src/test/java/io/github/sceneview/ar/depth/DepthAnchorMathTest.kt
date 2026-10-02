package io.github.sceneview.ar.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ShortBuffer

class DepthAnchorMathTest {

    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    @Test
    fun `point on the optical axis lands on the principal point`() {
        val anchors = DepthAnchorSet(4).apply { add(0f, 0f, -2f, 0.8f) }
        val intrinsics = DepthIntrinsics(fx = 100f, fy = 100f, cx = 50f, cy = 40f)
        // A map whose value encodes its own pixel: d = x + 1000·y.
        val map = FloatArray(100 * 80) { (it % 100) + 1000f * (it / 100) }
        val d = FloatArray(4)
        val z = FloatArray(4)
        val c = FloatArray(4)
        val kept = DepthAnchorMath.project(anchors, identity, intrinsics, map, 100, 80, d, z, c)
        assertEquals(1, kept)
        assertEquals(2f, z[0], 1e-6f)
        assertEquals(0.8f, c[0], 0f)
        // Pixel centre (50, 40) is between columns 49/50 and rows 39/40.
        assertEquals(49.5f + 1000f * 39.5f, d[0], 1e-2f)
    }

    @Test
    fun `plus Y goes up in the image and points behind the camera are dropped`() {
        val anchors = DepthAnchorSet(4).apply {
            add(0f, 1f, -2f, 1f) // up → smaller v
            add(0f, 0f, 2f, 1f) // behind
        }
        val intrinsics = DepthIntrinsics(fx = 20f, fy = 20f, cx = 50f, cy = 40f)
        val map = FloatArray(100 * 80) { (it / 100).toFloat() } // d = row index
        val d = FloatArray(4)
        val kept = DepthAnchorMath.project(anchors, identity, intrinsics, map, 100, 80, d, FloatArray(4), FloatArray(4))
        assertEquals(1, kept)
        assertEquals(29.5f, d[0], 1e-3f) // v = 40 − 20·1/2 = 30
    }

    @Test
    fun `view matrix translation moves the camera`() {
        // Camera at (0, 0, 1) looking down −Z: world → camera subtracts 1 from z.
        val view = identity.copyOf().apply { this[14] = -1f }
        val anchors = DepthAnchorSet(1).apply { add(0f, 0f, -1f, 1f) }
        val z = FloatArray(1)
        DepthAnchorMath.project(
            anchors, view, DepthIntrinsics(10f, 10f, 5f, 5f), FloatArray(100) { 1f }, 10, 10,
            FloatArray(1), z, FloatArray(1),
        )
        assertEquals(2f, z[0], 1e-6f)
    }

    @Test
    fun `plane samples stay inside the polygon and follow the center pose`() {
        // A 2 m × 1 m floor rectangle, 1.5 m below a camera at the origin.
        val polygon = floatArrayOf(-1f, -0.5f, 1f, -0.5f, 1f, 0.5f, -1f, 0.5f)
        val pose = identity.copyOf().apply {
            this[13] = -1.5f
            this[14] = -3f
        }
        val out = DepthAnchorSet(100)
        DepthAnchorMath.samplePlane(polygon, pose, out)
        assertEquals(48, out.count)
        for (i in 0 until out.count) {
            assertEquals(-1.5f, out.y[i], 1e-6f)
            assertTrue(out.x[i] in -1f..1f)
            assertTrue(out.z[i] in -3.5f..-2.5f)
        }
    }

    @Test
    fun `triangle keeps only its inner samples`() {
        val triangle = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
        assertTrue(DepthAnchorMath.insidePolygon(triangle, 0.2f, 0.2f))
        assertFalse(DepthAnchorMath.insidePolygon(triangle, 0.8f, 0.8f))
        val out = DepthAnchorSet(100)
        DepthAnchorMath.samplePlane(triangle, identity, out)
        assertTrue(out.count in 18..30)
    }

    @Test
    fun `intrinsics scale with the map`() {
        val k = DepthAnchorMath.scaleIntrinsics(500f, 500f, 320f, 240f, 640, 480, 320, 240)
        assertEquals(DepthIntrinsics(250f, 250f, 160f, 120f), k)
    }

    @Test
    fun `metric map honours range, anchors and confidence`() {
        val scale = DepthScale(scale = 1.0, offset = 0.0, inliers = 20, zMin = 1f, zMax = 2f, rmsRelativeError = 0.0)
        // 1/z = d → d = 1 (1 m), 0.5 (2 m), 0.1 (10 m, out of range), -1 (behind), 0.2 (5 m > 2·zMax).
        val map = floatArrayOf(1f, 0.5f, 0.1f, -1f, 0.2f)
        val mm = ShortBuffer.allocate(5)
        val conf = ByteBuffer.allocate(5)
        val valid = DepthImageMath.writeMetric(map, 5, 1, scale, mm, conf)
        assertEquals(2, valid)
        assertEquals(1000, mm.get(0).toInt())
        assertEquals(2000, mm.get(1).toInt())
        assertEquals(0, mm.get(2).toInt())
        assertEquals(0, mm.get(3).toInt())
        assertEquals(0, mm.get(4).toInt())
        assertEquals(0, conf.get(2).toInt())
        assertTrue((conf.get(0).toInt() and 0xFF) > 0)
    }

    @Test
    fun `yuv grey converts to grey rgb`() {
        val yuv = YuvCopy().apply {
            width = 4
            height = 4
            y = ByteArray(16) { 100 }
            u = ByteArray(4) { 128.toByte() }
            v = ByteArray(4) { 128.toByte() }
            yRowStride = 4
            uvRowStride = 2
            uvPixelStride = 1
        }
        val rgb = ByteArray(2 * 2 * 3)
        DepthImageMath.yuvToRgb(yuv, rgb, 2, 2)
        rgb.forEach { assertEquals(100, it.toInt() and 0xFF) }
    }

    @Test
    fun `yuv red chroma gives a red pixel`() {
        val yuv = YuvCopy().apply {
            width = 2
            height = 2
            y = ByteArray(4) { 76 }
            u = byteArrayOf(85)
            v = byteArrayOf(255.toByte())
            yRowStride = 2
            uvRowStride = 2
            uvPixelStride = 2
        }
        val rgb = ByteArray(3)
        DepthImageMath.yuvToRgb(yuv, rgb, 1, 1)
        assertTrue("r ${rgb[0].toInt() and 0xFF}", (rgb[0].toInt() and 0xFF) > 240)
        assertTrue((rgb[1].toInt() and 0xFF) < 20)
        assertTrue((rgb[2].toInt() and 0xFF) < 20)
    }
}
