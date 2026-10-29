package io.github.sceneview.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Reading a model file into memory: one array of the stream's size, filled in place, so a 50 MB GLB
 * is held once rather than twice (`readBytes()` grows a buffer, then copies it out).
 */
class ReadFullyTest {

    private val bytes = ByteArray(100_000) { (it * 31).toByte() }

    /** A stream that reports [hint] as `available()` and hands out at most 4 KB per read. */
    private class Chunked(private val data: ByteArray, private val hint: Int) : InputStream() {
        private var position = 0
        val arrays = mutableListOf<ByteArray>()
        override fun available() = hint
        override fun read(): Int = if (position < data.size) data[position++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (b !in arrays) arrays += b
            if (position >= data.size) return -1
            val n = minOf(len, 4096, data.size - position)
            System.arraycopy(data, position, b, off, n)
            position += n
            return n
        }
    }

    @Test
    fun `an exact size hint fills one array and returns it, no copy`() {
        val stream = Chunked(bytes, hint = bytes.size)

        val read = stream.readFully()

        assertArrayEquals(bytes, read)
        assertEquals(1, stream.arrays.size)
        assertSame(stream.arrays.single(), read)
    }

    @Test
    fun `a hint too small still reads everything`() {
        assertArrayEquals(bytes, Chunked(bytes, hint = 1000).readFully())
    }

    @Test
    fun `a hint too large is trimmed`() {
        assertArrayEquals(bytes, Chunked(bytes, hint = bytes.size * 3).readFully())
    }

    @Test
    fun `no hint at all reads everything`() {
        assertArrayEquals(bytes, Chunked(bytes, hint = 0).readFully())
        assertArrayEquals(ByteArray(0), Chunked(ByteArray(0), hint = 0).readFully())
    }

    @Test
    fun `progress rises to 1 over a known size`() {
        val fractions = mutableListOf<Float>()

        ByteArrayInputStream(bytes).readFully(onProgress = fractions::add)

        assertTrue(fractions.size > 1)
        assertEquals(fractions.sorted(), fractions)
        assertEquals(1f, fractions.last(), 0f)
    }

    @Test
    fun `progress is not reported for an unknown size`() {
        val fractions = mutableListOf<Float>()

        Chunked(bytes, hint = 0).readFully(onProgress = fractions::add)

        assertEquals(emptyList<Float>(), fractions)
    }

    @Test
    fun `toByteArray keeps its contract and closes the stream`() {
        var closed = false
        val stream = object : ByteArrayInputStream(bytes) {
            override fun close() {
                closed = true
            }
        }

        assertArrayEquals(bytes, stream.toByteArray())
        assertTrue(closed)
    }

    @Test
    fun `growing past 1 GB stops at the largest array instead of overflowing`() {
        assertEquals(DEFAULT_BUFFER_SIZE, grownCapacity(0))
        assertEquals(2 * 512 * 1024 * 1024, grownCapacity(512 * 1024 * 1024))
        // Doubling 1.5 GB overflows Int: the old `size * 2` went negative.
        assertEquals(Int.MAX_VALUE - 8, grownCapacity(1536 * 1024 * 1024))
        assertThrows(OutOfMemoryError::class.java) { grownCapacity(Int.MAX_VALUE - 8) }
    }
}
