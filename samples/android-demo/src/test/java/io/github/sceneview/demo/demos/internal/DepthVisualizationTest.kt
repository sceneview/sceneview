package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JVM unit tests for [DepthVisualization]. ARCore's `Image` is not mockable in JVM
 * tests, so the colorize routine is fed a plain [ByteBuffer] shaped like an
 * ARCore depth image (Y_16 little-endian, row-strided) instead.
 *
 * The colorize helpers also back the raw-depth point-cloud demo (#1715) — pinning the
 * `falseColorArgb` ramp and the `normalize` clamping behaviour here keeps the cloud
 * color-mapping deterministic across releases.
 */
class DepthVisualizationTest {

    // ── normalize ──────────────────────────────────────────────────────────

    @Test
    fun `normalize returns null for zero depth (no-data sentinel)`() {
        assertNull(DepthVisualization.normalize(0))
    }

    @Test
    fun `normalize maps near plane to 0 and far plane to 1`() {
        assertEquals(0f, DepthVisualization.normalize(300)!!, 1e-4f)
        assertEquals(1f, DepthVisualization.normalize(5_000)!!, 1e-4f)
    }

    @Test
    fun `normalize clamps depth below near to 0`() {
        assertEquals(0f, DepthVisualization.normalize(100)!!, 1e-4f)
    }

    @Test
    fun `normalize clamps depth above far to 1`() {
        assertEquals(1f, DepthVisualization.normalize(20_000)!!, 1e-4f)
    }

    @Test
    fun `normalize interpolates linearly between near and far`() {
        // Halfway: 300 + (5000-300)/2 = 2650
        assertEquals(0.5f, DepthVisualization.normalize(2_650)!!, 1e-3f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `normalize rejects far smaller-or-equal to near`() {
        DepthVisualization.normalize(1_000, nearMm = 500, farMm = 500)
    }

    // ── falseColorArgb ─────────────────────────────────────────────────────

    @Test
    fun `falseColorArgb at 0 is red (warm = near)`() {
        val argb = DepthVisualization.falseColorArgb(0f)
        // Alpha 0xFF, red 0xFF, green 0x00, blue 0x00.
        assertEquals(0xFF, (argb ushr 24) and 0xFF)
        assertEquals(0xFF, (argb ushr 16) and 0xFF)
        assertEquals(0x00, (argb ushr 8) and 0xFF)
        assertEquals(0x00, argb and 0xFF)
    }

    @Test
    fun `falseColorArgb at 1 is blue (cool = far)`() {
        val argb = DepthVisualization.falseColorArgb(1f)
        assertEquals(0xFF, (argb ushr 24) and 0xFF)
        assertEquals(0x00, (argb ushr 16) and 0xFF)
        assertEquals(0x00, (argb ushr 8) and 0xFF)
        assertEquals(0xFF, argb and 0xFF)
    }

    @Test
    fun `falseColorArgb at 0_5 is between yellow and cyan (not the endpoint hues)`() {
        // Midpoint sits inside the yellow→cyan band of the 3-segment ramp.
        val argb = DepthVisualization.falseColorArgb(0.5f)
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        assertTrue("red should fade out at mid: $r", r < 0xFF)
        assertTrue("green should be near full at mid: $g", g >= 0xF0)
        assertTrue("blue should be ramping up at mid: $b", b > 0)
    }

    @Test
    fun `falseColorArgb clamps out-of-range values`() {
        // Below 0 collapses to the near color; above 1 collapses to the far color.
        assertEquals(DepthVisualization.falseColorArgb(0f), DepthVisualization.falseColorArgb(-0.5f))
        assertEquals(DepthVisualization.falseColorArgb(1f), DepthVisualization.falseColorArgb(1.5f))
    }

    @Test
    fun `falseColorArgb alpha is always opaque`() {
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { t ->
            assertEquals("alpha at t=$t", 0xFF, (DepthVisualization.falseColorArgb(t) ushr 24) and 0xFF)
        }
    }

    // ── depthBufferToArgb ─────────────────────────────────────────────────

    @Test
    fun `depthBufferToArgb converts a tiny depth buffer to false-color`() {
        // 2×1 image: pixel 0 = near (300 mm), pixel 1 = far (5000 mm).
        val width = 2
        val height = 1
        val rowStride = width * 2 // no padding
        val buf = ByteBuffer.allocate(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0, 300.toShort())
        buf.putShort(2, 5_000.toShort())

        val pixels = DepthVisualization.depthBufferToArgb(
            depthBytes = buf,
            width = width,
            height = height,
            rowStrideBytes = rowStride,
        )

        assertEquals(2, pixels.size)
        // Near sample → red.
        assertEquals(DepthVisualization.falseColorArgb(0f), pixels[0])
        // Far sample → blue.
        assertEquals(DepthVisualization.falseColorArgb(1f), pixels[1])
    }

    @Test
    fun `depthBufferToArgb emits transparent pixel for zero-depth (no data)`() {
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0, 0)

        val pixels = DepthVisualization.depthBufferToArgb(
            depthBytes = buf,
            width = 1,
            height = 1,
            rowStrideBytes = 2,
        )

        // Fully transparent so the live camera feed shows through where ARCore
        // has no depth datum.
        assertEquals(0, pixels[0])
    }

    @Test
    fun `depthBufferToArgb respects rowStride padding`() {
        // 1×2 image with padded rows: rowStride = 4 bytes but only 2 are valid.
        val width = 1
        val height = 2
        val rowStride = 4 // 2 bytes of pixel + 2 bytes of padding
        val buf = ByteBuffer.allocate(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0, 300.toShort())  // row 0
        buf.putShort(4, 5_000.toShort()) // row 1
        // Bytes at offset 2 and 6 are padding (zero), which the reader must NOT
        // interpret as the next pixel.

        val pixels = DepthVisualization.depthBufferToArgb(
            depthBytes = buf,
            width = width,
            height = height,
            rowStrideBytes = rowStride,
        )

        assertEquals(2, pixels.size)
        assertEquals(DepthVisualization.falseColorArgb(0f), pixels[0])
        assertEquals(DepthVisualization.falseColorArgb(1f), pixels[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `depthBufferToArgb rejects zero-sized depth image`() {
        val buf = ByteBuffer.allocate(0)
        DepthVisualization.depthBufferToArgb(buf, 0, 0, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `depthBufferToArgb rejects rowStride smaller than width`() {
        val buf = ByteBuffer.allocate(10)
        DepthVisualization.depthBufferToArgb(buf, 5, 1, 5) // width*2 = 10, given 5
    }

    // ── clampUnit ─────────────────────────────────────────────────────────

    @Test
    fun `clampUnit pins values to 0_1 range`() {
        assertEquals(0f, DepthVisualization.clampUnit(-1f), 0f)
        assertEquals(1f, DepthVisualization.clampUnit(2f), 0f)
        assertEquals(0.5f, DepthVisualization.clampUnit(0.5f), 0f)
        assertEquals(0f, DepthVisualization.clampUnit(0f), 0f)
        assertEquals(1f, DepthVisualization.clampUnit(1f), 0f)
    }

    @Test
    fun `defaults are sane and ordered`() {
        assertTrue(DepthVisualization.FAR_MM_DEFAULT > DepthVisualization.NEAR_MM_DEFAULT)
        assertTrue(DepthVisualization.NEAR_MM_DEFAULT > 0)
    }
}
