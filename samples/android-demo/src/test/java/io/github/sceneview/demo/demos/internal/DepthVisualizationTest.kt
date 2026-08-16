package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
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

    // ── rotation (#3184) ──────────────────────────────────────────────────

    /**
     * Every rotation assertion below uses this 3×2 grid, never a 1-pixel-tall strip.
     * A strip is degenerate: with `height == 1` the no-rotation destination index and the
     * 90° one collapse onto the same value, so the pre-fix behaviour — no rotation at all,
     * which *is* #3184 — passes a strip-based test. The grid does not let it. Each cell
     * carries its own depth so every sample is distinguishable by color.
     *
     * ```
     *  a b c        depth 400 500 600
     *  d e f              700 800 900
     * ```
     */
    private val gridWidth = 3
    private val gridHeight = 2
    private val gridDepths = intArrayOf(400, 500, 600, 700, 800, 900)

    private fun grid(): ByteBuffer =
        ByteBuffer.allocate(gridWidth * gridHeight * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            gridDepths.forEachIndexed { i, mm -> putShort(i * 2, mm.toShort()) }
        }

    /** The color the grid cell holding [depthMm] must end up as. */
    private fun color(depthMm: Int): Int =
        DepthVisualization.falseColorArgb(DepthVisualization.normalize(depthMm)!!)

    /** Expected pixels, written in reading order of the *rotated* image. */
    private fun expect(vararg depthsMm: Int): IntArray =
        IntArray(depthsMm.size) { color(depthsMm[it]) }

    private fun rotate(degrees: Int): IntArray = DepthVisualization.depthBufferToArgb(
        depthBytes = grid(),
        width = gridWidth,
        height = gridHeight,
        rowStrideBytes = gridWidth * 2,
        rotationDegrees = degrees,
    )

    @Test
    fun `depthBufferToArgb rotates 90 degrees clockwise`() {
        // abc/def turned clockwise is da/eb/fc — the bottom-left sample leads the top row.
        // This is the portrait case: without it the overlay lies 90° off the camera (#3184).
        assertArrayEquals(
            expect(
                700, 400,
                800, 500,
                900, 600,
            ),
            rotate(90)
        )
    }

    @Test
    fun `depthBufferToArgb rotates 270 degrees the other way`() {
        // The mirror of the 90° case: cf/be/ad. If these two ever agree, the rotation has
        // collapsed to a transpose and reverse-portrait renders upside-down.
        assertArrayEquals(
            expect(
                600, 900,
                500, 800,
                400, 700,
            ),
            rotate(270)
        )
    }

    @Test
    fun `depthBufferToArgb rotates 180 degrees through both axes`() {
        // fed/cba — a row-only or column-only flip would leave one axis in place.
        assertArrayEquals(
            expect(
                900, 800, 700,
                600, 500, 400,
            ),
            rotate(180)
        )
    }

    @Test
    fun `depthBufferToArgb at 0 degrees is unchanged`() {
        val rotated = rotate(0)
        val plain = DepthVisualization.depthBufferToArgb(
            depthBytes = grid(),
            width = gridWidth,
            height = gridHeight,
            rowStrideBytes = gridWidth * 2,
        )

        // The default must stay a no-op — the rotation is opt-in per call site.
        assertArrayEquals(plain, rotated)
    }

    @Test
    fun `depthBufferToArgb rotation is a permutation, never a drop`() {
        // Every source sample must land somewhere distinct: a wrong destination index
        // silently overwrites one pixel and leaves another at 0, which reads on screen
        // as noise rather than as a rotation bug.
        val width = 3
        val height = 4
        val rowStride = width * 2
        val buf = ByteBuffer.allocate(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        // Distinct in-range depths, one per pixel, so each maps to its own color.
        (0 until width * height).forEach { i ->
            buf.putShort(i * 2, (400 + i * 100).toShort())
        }

        listOf(0, 90, 180, 270).forEach { degrees ->
            val pixels = DepthVisualization.depthBufferToArgb(
                depthBytes = buf,
                width = width,
                height = height,
                rowStrideBytes = rowStride,
                rotationDegrees = degrees,
            )
            assertEquals("size at $degrees°", width * height, pixels.size)
            assertEquals(
                "every sample survives the $degrees° rotation",
                width * height,
                pixels.toSet().size
            )
        }
    }

    @Test
    fun `rotated dimensions swap on a quarter turn only`() {
        assertEquals(240, DepthVisualization.rotatedWidth(240, 180, 0))
        assertEquals(180, DepthVisualization.rotatedHeight(240, 180, 0))
        // Portrait: the 240×180 sensor image is displayed as 180×240.
        assertEquals(180, DepthVisualization.rotatedWidth(240, 180, 90))
        assertEquals(240, DepthVisualization.rotatedHeight(240, 180, 90))
        assertEquals(240, DepthVisualization.rotatedWidth(240, 180, 180))
        assertEquals(180, DepthVisualization.rotatedHeight(240, 180, 180))
        assertEquals(180, DepthVisualization.rotatedWidth(240, 180, 270))
        assertEquals(240, DepthVisualization.rotatedHeight(240, 180, 270))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `depthBufferToArgb rejects a rotation that is not a quarter turn`() {
        rotate(45)
    }

    @Test
    fun `displayRotationToDegrees turns the sensor image upright`() {
        // Surface.ROTATION_* ordinals. Portrait needs the 90° turn — that is the case
        // #3184 reported, and the one the emulator's landscape default never showed.
        assertEquals(90, DepthVisualization.displayRotationToDegrees(0))
        assertEquals(0, DepthVisualization.displayRotationToDegrees(1))
        assertEquals(270, DepthVisualization.displayRotationToDegrees(2))
        assertEquals(180, DepthVisualization.displayRotationToDegrees(3))
    }

    @Test
    fun `displayRotationToDegrees falls back to portrait on an unknown rotation`() {
        assertEquals(90, DepthVisualization.displayRotationToDegrees(-1))
        assertEquals(90, DepthVisualization.displayRotationToDegrees(42))
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
