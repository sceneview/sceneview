package io.github.sceneview.snapshot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer

class ModelSnapshotPixelsTest {

    /** A [width]×[height] RGBA image whose pixel (x, y) is (x, y, 7, 255), rows [rowStride] bytes apart. */
    private fun image(width: Int, height: Int, rowStride: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(rowStride * height)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            for (x in 0 until width) buffer.put(byteArrayOf(x.toByte(), y.toByte(), 7, 255.toByte()))
            // Row padding the producer is free to fill with anything.
            while (buffer.position() < (y + 1) * rowStride) buffer.put(0x5A)
        }
        buffer.rewind()
        return buffer
    }

    private fun ByteBuffer.bytes(): ByteArray = ByteArray(remaining()).also { duplicate().get(it) }

    @Test
    fun paddedRowsArePackedTight() {
        val packed = packRgbaRows(image(3, 2, rowStride = 16), width = 3, height = 2, rowStride = 16, pixelStride = 4)
        val expected = byteArrayOf(
            0, 0, 7, -1, 1, 0, 7, -1, 2, 0, 7, -1,
            0, 1, 7, -1, 1, 1, 7, -1, 2, 1, 7, -1,
        )
        assertArrayEquals(expected, packed.bytes())
    }

    @Test
    fun tightRowsAreReturnedUnchanged() {
        val source = image(4, 3, rowStride = 16)
        val packed = packRgbaRows(source, width = 4, height = 3, rowStride = 16, pixelStride = 4)
        assertEquals(48, packed.remaining())
        assertArrayEquals(source.bytes(), packed.bytes())
    }

    @Test
    fun anythingButFourBytesPerPixelIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            packRgbaRows(image(2, 2, rowStride = 8), width = 2, height = 2, rowStride = 8, pixelStride = 3)
        }
    }

    @Test
    fun aRowStrideShorterThanARowIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            packRgbaRows(image(2, 2, rowStride = 8), width = 2, height = 2, rowStride = 4, pixelStride = 4)
        }
    }
}
