package io.github.sceneview.loaders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The off-main-thread HDR decode behind `EnvironmentLoader.loadHDREnvironment`: it must produce
 * the linear floats `HDRLoader` would, from flat and run-length encoded Radiance files, and
 * refuse (null, so the loader falls back to `HDRLoader`) what it does not understand.
 */
class RgbeDecoderTest {

    private fun header(width: Int, height: Int) =
        "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y $height +X $width\n".toByteArray(Charsets.US_ASCII)

    @Test
    fun `flat scanlines decode like Filament, mantissa plus half times two to the exponent minus 136`() {
        val out = ByteArrayOutputStream()
        out.write(header(2, 1))
        // (128, 64, 32) at e = 129 → (value + 0.5) * 2^(129 - 136) = (value + 0.5) / 128.
        out.write(byteArrayOf(128.toByte(), 64, 32, 129.toByte()))
        // e = 0 is black whatever the mantissa.
        out.write(byteArrayOf(10, 20, 30, 0))

        val image = checkNotNull(RgbeDecoder.decode(out.toByteArray()))
        assertEquals(2, image.width)
        assertEquals(1, image.height)
        val px = FloatArray(6).also { image.pixels.get(it) }
        assertEquals(128.5f / 128f, px[0], 0f)
        assertEquals(64.5f / 128f, px[1], 0f)
        assertEquals(32.5f / 128f, px[2], 0f)
        assertEquals(0f, px[3], 0f)
        assertEquals(0f, px[4], 0f)
        assertEquals(0f, px[5], 0f)
    }

    @Test
    fun `run length encoded scanlines match their flat equivalent`() {
        val width = 8
        val out = ByteArrayOutputStream()
        out.write(header(width, 2))
        repeat(2) { row ->
            out.write(byteArrayOf(2, 2, 0, width.toByte()))
            // R: one run of 8 × 100.
            out.write(byteArrayOf((128 + width).toByte(), 100))
            // G: 8 literal values.
            out.write(byteArrayOf(width.toByte()))
            out.write(ByteArray(width) { (it * 10 + row).toByte() })
            // B: run of 4 × 7, then 4 literals.
            out.write(byteArrayOf((128 + 4).toByte(), 7, 4, 1, 2, 3, 4))
            // E: one run of 8 × 136 → scale 1, so each channel is its mantissa + 0.5.
            out.write(byteArrayOf((128 + width).toByte(), 136.toByte()))
        }

        val image = checkNotNull(RgbeDecoder.decode(ByteBuffer.wrap(out.toByteArray())))
        val px = FloatArray(width * 2 * 3).also { image.pixels.get(it) }
        for (row in 0 until 2) {
            for (x in 0 until width) {
                val o = (row * width + x) * 3
                assertEquals(100.5f, px[o], 0f)
                assertEquals(x * 10 + row + 0.5f, px[o + 1], 0f)
                assertEquals(if (x < 4) 7.5f else x - 3 + 0.5f, px[o + 2], 0f)
            }
        }
    }

    @Test
    fun `decoding a buffer leaves its position untouched`() {
        val bytes = header(1, 1) + byteArrayOf(1, 1, 1, 136.toByte())
        val buffer = ByteBuffer.wrap(bytes)
        assertNotNull(RgbeDecoder.decode(buffer))
        assertEquals(0, buffer.position())
    }

    @Test
    fun `unsupported or broken files return null so the loader falls back to HDRLoader`() {
        assertNull(RgbeDecoder.decode("not an hdr".toByteArray()))
        // Flipped X orientation is legal Radiance but not what HDRLoader's callers ship.
        assertNull(RgbeDecoder.decode("#?RADIANCE\n\n-Y 1 -X 1\n".toByteArray() + ByteArray(4)))
        // Truncated pixel data.
        assertNull(RgbeDecoder.decode(header(4, 4) + ByteArray(8)))
    }
}
