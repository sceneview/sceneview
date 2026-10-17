package io.github.sceneview.demo.demos.squat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pose model must see the picture the way it is on screen, or its landmarks land
 * beside the person. These tests feed [UprightFrame] the corner transforms ARCore produces
 * and check the YUV → RGB conversion honours them pixel by pixel.
 */
class UprightFrameTest {

    @Test
    fun portraitBackCameraIsAQuarterTurn() {
        // Portrait phone, sensor landscape: raw (0,0) → top-right, raw u runs down,
        // raw v runs left. Feed slightly cropped horizontally (4:3 image in a taller view).
        val f = UprightFrame.fromTransformedCorners(floatArrayOf(1.1f, 0f, 1.1f, 1f, -0.1f, 0f))!!
        assertTrue(f.swapAxes)
        assertTrue(f.flipX)
        assertFalse(f.flipY)
        assertEquals(-0.1f, f.viewLeft, 1e-6f)
        assertEquals(1.1f, f.viewRight, 1e-6f)
        assertEquals(0f, f.viewTop, 1e-6f)
        assertEquals(1f, f.viewBottom, 1e-6f)
        // The upright image's centre is the view's centre; its edges sit outside the crop.
        assertEquals(0.5f, f.toViewX(0.5f), 1e-6f)
        assertEquals(-0.1f, f.toViewX(0f), 1e-6f)
        assertEquals(480, f.uprightWidth(640, 480))
        assertEquals(640, f.uprightHeight(640, 480))
    }

    @Test
    fun landscapeIsIdentity() {
        val f = UprightFrame.fromTransformedCorners(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f))!!
        assertFalse(f.swapAxes)
        assertFalse(f.flipX)
        assertFalse(f.flipY)
        assertEquals(640, f.uprightWidth(640, 480))
    }

    @Test
    fun reverseLandscapeFlipsBothAxes() {
        val f = UprightFrame.fromTransformedCorners(floatArrayOf(1f, 1f, 0f, 1f, 1f, 0f))!!
        assertFalse(f.swapAxes)
        assertTrue(f.flipX)
        assertTrue(f.flipY)
    }

    @Test
    fun collapsedTransformIsNull() {
        assertNull(UprightFrame.fromTransformedCorners(FloatArray(6)))
    }

    // --- Conversion ---------------------------------------------------------------------

    /**
     * A 4×4 raw image whose 2×2 half-resolution pixels are identified by their luma:
     * (0,0)=50 (1,0)=100 (0,1)=150 (1,1)=200, neutral chroma, with padded strides and an
     * interleaved chroma pixel stride of 2 — what most ARCore devices hand over.
     */
    private fun rawImage(): Triple<ByteArray, ByteArray, ByteArray> {
        val yStride = 6 // 4 pixels + 2 bytes of padding
        val y = ByteArray(yStride * 4)
        val lumaOf = arrayOf(intArrayOf(50, 100), intArrayOf(150, 200)) // [rv][ru]
        for (row in 0 until 4) for (col in 0 until 4) {
            y[row * yStride + col] = lumaOf[row / 2][col / 2].toByte()
        }
        val uvStride = 6
        val u = ByteArray(uvStride * 2) { 128.toByte() }
        val v = ByteArray(uvStride * 2) { 128.toByte() }
        return Triple(y, u, v)
    }

    private fun lumaToRgb(l: Int): Int = ((1192 * (l - 16)) shr 10).coerceIn(0, 255)

    private fun convert(frame: UprightFrame): Pair<Pair<Int, Int>, IntArray> {
        val (y, u, v) = rawImage()
        val out = ByteArray(2 * 2 * 3)
        val size = yuv420ToUprightRgbHalf(y, 6, u, v, 6, 2, 4, 4, frame, out)
        // Neutral chroma → R = G = B; read back R of each pixel.
        val reds = IntArray(4) { out[it * 3].toInt() and 0xFF }
        for (i in 0 until 4) {
            assertEquals(reds[i], out[i * 3 + 1].toInt() and 0xFF)
            assertEquals(reds[i], out[i * 3 + 2].toInt() and 0xFF)
        }
        return size to reds
    }

    @Test
    fun identityKeepsTheRawLayout() {
        val (size, px) = convert(UprightFrame(false, false, false, 0f, 0f, 1f, 1f))
        assertEquals(2 to 2, size)
        assertArrayEquals(intArrayOf(50, 100, 150, 200).map(::lumaToRgb).toIntArray(), px)
    }

    @Test
    fun portraitQuarterTurnPutsRawBottomLeftAtTopLeft() {
        // swap + flipX: upright (i, j) reads raw (u = j, v = W-1-i).
        val (_, px) = convert(UprightFrame(true, true, false, 0f, 0f, 1f, 1f))
        assertArrayEquals(intArrayOf(150, 50, 200, 100).map(::lumaToRgb).toIntArray(), px)
    }

    @Test
    fun colourConversionIsBt601() {
        // Pure red in BT.601 limited range: Y=81, Cb=90, Cr=240.
        val y = ByteArray(4) { 81.toByte() }
        val u = byteArrayOf(90.toByte())
        val v = byteArrayOf(240.toByte())
        val out = ByteArray(3)
        yuv420ToUprightRgbHalf(y, 2, u, v, 1, 1, 2, 2, UprightFrame(false, false, false, 0f, 0f, 1f, 1f), out)
        val r = out[0].toInt() and 0xFF
        val g = out[1].toInt() and 0xFF
        val b = out[2].toInt() and 0xFF
        assertTrue("r=$r", r > 245)
        assertTrue("g=$g", g < 10)
        assertTrue("b=$b", b < 10)
    }
}
