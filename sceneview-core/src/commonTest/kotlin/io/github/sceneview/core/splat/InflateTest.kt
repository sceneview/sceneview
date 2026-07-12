package io.github.sceneview.core.splat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InflateTest {

    /**
     * A real gzip stream (dynamic Huffman, produced by `gzip -9`) of a known 774-byte plaintext.
     * Proves the inflater decodes genuine gzip output — LZ77 back-references and dynamic Huffman
     * tables — not just the stored-block form the test builder emits.
     */
    private val realGzip = bytesOf(
        31, 139, 8, 0, 0, 0, 0, 0, 2, 255, 237, 206, 49, 14, 194, 64, 12, 68, 209, 171, 204, 5, 160,
        161, 163, 70, 162, 128, 34, 82, 36, 36, 232, 156, 196, 73, 12, 139, 119, 177, 119, 9, 112,
        122, 34, 46, 65, 147, 122, 244, 53, 175, 110, 89, 249, 36, 60, 97, 179, 195, 158, 138, 187,
        144, 162, 78, 129, 114, 22, 29, 182, 200, 35, 227, 81, 164, 189, 161, 177, 56, 41, 250, 248,
        194, 181, 220, 147, 35, 62, 217, 126, 115, 160, 207, 27, 93, 28, 214, 168, 171, 11, 72, 59,
        84, 199, 51, 18, 153, 179, 57, 124, 36, 99, 68, 101, 164, 98, 188, 58, 196, 28, 68, 33, 218,
        207, 23, 60, 39, 11, 96, 1, 252, 27, 240, 5, 200, 164, 106, 83, 6, 3, 0, 0
    )

    private val realPlain =
        ("SceneView 3D Gaussian Splatting: the quick brown fox jumps over the lazy dog. " +
            "SPZ and PLY parsers share one pure-Kotlin inflate. ").repeat(6)

    @Test
    fun inflatesRealDynamicHuffmanGzip() {
        val out = Inflate.gunzip(realGzip)
        assertEquals(realPlain, out.decodeToString())
    }

    @Test
    fun roundTripsStoredBlocks() {
        val payload = ByteArray(1000) { ((it * 31 + 7) and 0xFF).toByte() }
        val gz = gzipStored(payload)
        assertTrue(gz.contentEquals(gzipStored(payload)), "deterministic")
        assertTrue(Inflate.gunzip(gz).contentEquals(payload))
    }

    @Test
    fun roundTripsMultipleStoredBlocks() {
        // Larger than 64 KiB → forces several stored blocks, exercising the block loop.
        val payload = ByteArray(200_000) { ((it * 131 + 17) and 0xFF).toByte() }
        assertTrue(Inflate.gunzip(gzipStored(payload)).contentEquals(payload))
    }

    @Test
    fun roundTripsEmptyPayload() {
        assertEquals(0, Inflate.gunzip(gzipStored(ByteArray(0))).size)
    }

    @Test
    fun rejectsCorruptCrc() {
        val gz = gzipStored(ByteArray(64) { it.toByte() })
        gz[gz.size - 5] = (gz[gz.size - 5] + 1).toByte() // flip a CRC byte
        assertFailsWith<SplatParseException> { Inflate.gunzip(gz) }
    }

    @Test
    fun rejectsBadMagic() {
        val gz = gzipStored(ByteArray(8))
        gz[0] = 0
        assertFailsWith<SplatParseException> { Inflate.gunzip(gz) }
    }

    @Test
    fun rejectsTruncatedStream() {
        val gz = gzipStored(ByteArray(64) { it.toByte() })
        assertFailsWith<SplatParseException> { Inflate.gunzip(gz.copyOf(gz.size - 20)) }
    }

    @Test
    fun crc32MatchesKnownVector() {
        // CRC-32 of ASCII "123456789" is 0xCBF43926.
        assertEquals(0xCBF43926.toInt(), crc32("123456789".encodeToByteArray()))
    }
}
