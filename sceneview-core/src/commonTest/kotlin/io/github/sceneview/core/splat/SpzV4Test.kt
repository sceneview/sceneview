package io.github.sceneview.core.splat

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SPZ version 4 (NGSP container, one ZSTD frame per stream) and the [Zstd] decoder behind it.
 *
 * Every compressed byte here was written by the reference `zstd` CLI, and the SPZ file holds
 * real splats from Niantic's raccoon scan. Both come from `tools/make-spz-v4-fixture.py`, which
 * also documents the fixtures' source and licence.
 */
class SpzV4Test {

    // ── Zstd ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun zstdDecodesMultiBlockTextFrame() {
        val out = Zstd.decompress(ZSTD_TEXT_FRAME)
        assertEquals(ZSTD_TEXT_SIZE, out.size)
        assertEquals(ZSTD_TEXT_CRC, crc32(out))
        assertContentEquals(lcgText(ZSTD_TEXT_SIZE, ZSTD_TEXT_SEED), out)
    }

    @Test
    fun zstdDecodesSkippableConcatenatedAndRleFrames() {
        val out = Zstd.decompress(ZSTD_MIXED_FRAMES)
        val expected = ZSTD_HELLO.encodeToByteArray() + ByteArray(4096) { 0x2A }
        assertContentEquals(expected, out)
    }

    @Test
    fun zstdDecodesEveryStreamOfTheV4File() {
        val b = SPZ_V4_RACCOON
        var start = 32 + 6 * 16
        for (i in 0 until 6) {
            val compressed = readLe64(b, 32 + i * 16).toInt()
            val out = Zstd.decompress(b, start, compressed, SPZ_V4_STREAM_SIZES[i])
            assertEquals(SPZ_V4_STREAM_SIZES[i], out.size, "stream $i size")
            assertEquals(SPZ_V4_STREAM_CRCS[i], crc32(out), "stream $i CRC-32")
            start += compressed
        }
        assertEquals(b.size, start)
    }

    @Test
    fun zstdEnforcesTheExpectedSize() {
        assertFailsWith<SplatParseException> { Zstd.decompress(ZSTD_MIXED_FRAMES, expectedSize = 100) }
        assertFailsWith<SplatParseException> { Zstd.decompress(ZSTD_TEXT_FRAME, expectedSize = ZSTD_TEXT_SIZE + 1) }
    }

    @Test
    fun zstdRejectsBadInput() {
        assertFailsWith<SplatParseException> { Zstd.decompress(ByteArray(0)) }
        assertFailsWith<SplatParseException> { Zstd.decompress(bytesOf(1, 2, 3, 4, 5, 6, 7, 8)) }
        // A truncated frame fails; it never returns partial content.
        assertFails { Zstd.decompress(ZSTD_TEXT_FRAME.copyOf(ZSTD_TEXT_FRAME.size / 2)) }
        // Dictionary frames are out of scope: descriptor 0x01 = 1-byte dictionary id.
        assertFailsWith<SplatParseException> {
            Zstd.decompress(bytesOf(0x28, 0xB5, 0x2F, 0xFD, 0x01, 0x07, 0x01, 0x00, 0x00))
        }
    }

    // ── SPZ v4 ───────────────────────────────────────────────────────────────────────────

    @Test
    fun parsesV4AgainstReferenceDecodes() {
        val cloud = SplatParser.fromSpz(SPZ_V4_RACCOON)
        assertEquals(SPZ_V4_COUNT, cloud.count)
        for (spot in SPZ_V4_SPOTS) {
            val i = spot.index
            for (a in 0 until 3) {
                assertEquals(spot.position[a], cloud.positions[i * 3 + a], 1e-5f, "pos[$i][$a]")
                assertEquals(spot.scale[a], cloud.scales[i * 3 + a], spot.scale[a] * 1e-5f, "scale[$i][$a]")
                val dc = (spot.colorBytes[a] / 255f - 0.5f) / 0.15f
                assertEquals(SplatMath.dcToLinearColor(dc), cloud.colors[i * 3 + a], 1e-6f, "color[$i][$a]")
            }
            assertEquals(spot.opacity, cloud.opacities[i], 1e-6f, "opacity[$i]")
            // q and -q are the same rotation; compare up to sign.
            val sign = if (spot.quaternionXyzw[3] * cloud.rotations[i * 4 + 3] < 0f) -1f else 1f
            for (c in 0 until 4) {
                assertEquals(spot.quaternionXyzw[c], sign * cloud.rotations[i * 4 + c], 1e-5f, "quat[$i][$c]")
            }
        }
        for (i in 0 until cloud.count) {
            assertEquals(1f, quatLength(cloud.rotations, i * 4), 1e-4f, "quat normalized [$i]")
            assertTrue(cloud.scales[i * 3] > 0f && cloud.opacities[i] in 0f..1f, "sane splat $i")
        }
    }

    @Test
    fun v4DecodesExactlyLikeTheSameDataInAGzipV3Container() {
        val v4 = SplatParser.fromSpz(SPZ_V4_RACCOON)
        val v3 = SplatParser.fromSpz(gzipStored(v3PayloadFromV4()))
        assertEquals(v3.count, v4.count)
        assertContentEquals(v3.positions, v4.positions)
        assertContentEquals(v3.scales, v4.scales)
        assertContentEquals(v3.rotations, v4.rotations)
        assertContentEquals(v3.colors, v4.colors)
        assertContentEquals(v3.opacities, v4.opacities)
    }

    @Test
    fun autoDetectsV4() {
        assertEquals(SPZ_V4_COUNT, SplatParser.parse(SPZ_V4_RACCOON).count)
    }

    @Test
    fun rejectsUnsupportedNgspVersion() {
        val e = assertFailsWith<SplatParseException> { SplatParser.fromSpz(patched { writeLe32(it, 4, 5) }) }
        assertTrue("version 5" in e.message.orEmpty(), e.message)
    }

    @Test
    fun rejectsWrongStreamCount() {
        val e = assertFailsWith<SplatParseException> { SplatParser.fromSpz(patched { it[15] = 5 }) }
        assertTrue("streams" in e.message.orEmpty(), e.message)
    }

    @Test
    fun rejectsTocOutOfBounds() {
        assertFailsWith<SplatParseException> { SplatParser.fromSpz(patched { writeLe32(it, 16, it.size - 8) }) }
        assertFailsWith<SplatParseException> { SplatParser.fromSpz(patched { writeLe32(it, 16, 8) }) }
    }

    @Test
    fun rejectsStreamSizeMismatch() {
        // Stream 1 (alphas) must hold exactly numPoints bytes.
        val e = assertFailsWith<SplatParseException> {
            SplatParser.fromSpz(patched { writeLe32(it, 32 + 16 + 8, SPZ_V4_COUNT + 1) })
        }
        assertTrue("stream 1" in e.message.orEmpty(), e.message)
    }

    @Test
    fun rejectsImplausiblePointCount() {
        assertFailsWith<SplatParseException> { SplatParser.fromSpz(patched { writeLe32(it, 8, Int.MAX_VALUE) }) }
        assertFailsWith<SplatParseException> { SplatParser.fromSpz(patched { writeLe32(it, 8, 0) }) }
    }

    @Test
    fun everyTruncationFailsCleanly() {
        val b = SPZ_V4_RACCOON
        var len = 0
        while (len < b.size) {
            assertFailsWith<SplatParseException>("truncated to $len bytes") { SplatParser.fromSpz(b.copyOf(len)) }
            len += 37
        }
    }

    @Test
    fun corruptBytesNeverEscapeAsAnotherException() {
        val b = SPZ_V4_RACCOON
        var i = 32
        while (i < b.size) {
            val corrupt = b.copyOf()
            corrupt[i] = (corrupt[i].toInt() xor 0x5A).toByte()
            try {
                SplatParser.fromSpz(corrupt)
            } catch (_: SplatParseException) {
                // expected for most positions
            }
            i += 11
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private fun patched(edit: (ByteArray) -> Unit): ByteArray = SPZ_V4_RACCOON.copyOf().also(edit)

    /** Rebuilds the v4 payload as a version-3 gzip body: 16-byte header + the raw streams. */
    private fun v3PayloadFromV4(): ByteArray {
        val b = SPZ_V4_RACCOON
        val header = b.copyOf(16)
        writeLe32(header, 4, 3)
        header[15] = 0
        var body = header
        var start = 32 + 6 * 16
        for (i in 0 until 6) {
            val compressed = readLe64(b, 32 + i * 16).toInt()
            body += Zstd.decompress(b, start, compressed, SPZ_V4_STREAM_SIZES[i])
            start += compressed
        }
        return body
    }

    private fun writeLe32(out: ByteArray, at: Int, v: Int) {
        for (k in 0 until 4) out[at + k] = (v ushr (8 * k)).toByte()
    }

    private fun crc32(data: ByteArray): Int {
        var crc = -1
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1 }
        }
        return crc.inv()
    }

    /** Must match `lcg_text` in tools/make-spz-v4-fixture.py byte for byte. */
    private fun lcgText(size: Int, seed: Int): ByteArray {
        val words = listOf(
            "splat", "gaussian", "scene", "view", "anisotropic", "covariance",
            "raccoon", "stump", "filament", "render", "camera", "sort",
        ).map { it.encodeToByteArray() }
        val out = ByteArray(size)
        var n = 0
        var x = seed.toLong()
        fun put(bytes: ByteArray) {
            for (v in bytes) {
                if (n == size) return
                out[n++] = v
            }
        }
        while (n < size) {
            x = (x * 1103515245L + 12345L) and 0x7FFFFFFFL
            put(words[((x shr 16) % words.size).toInt()])
            put(if (((x shr 8) and 7L) == 0L) ".\n".encodeToByteArray() else " ".encodeToByteArray())
        }
        return out
    }
}
