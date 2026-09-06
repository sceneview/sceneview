package io.github.sceneview.core.splat

/**
 * Compute the gzip/zlib CRC-32 (polynomial `0xEDB88320`) of [data]. Returned as an Int bit pattern
 * to match the little-endian value stored in a gzip trailer.
 */
internal fun crc32(data: ByteArray): Int {
    var crc = -1 // 0xFFFFFFFF
    for (b in data) {
        crc = CRC32_TABLE[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
    }
    return crc.inv()
}

private val CRC32_TABLE: IntArray = IntArray(256) { n ->
    var c = n
    repeat(8) { c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1 }
    c
}

/**
 * Pure-Kotlin gzip (RFC 1952) + DEFLATE (RFC 1951) decoder, shared by every Kotlin Multiplatform
 * target so [SplatParser.fromSpz] can decompress gzip-wrapped SPZ payloads without an `expect`/
 * `actual` per platform or a native zlib/pako dependency.
 *
 * It implements the whole of DEFLATE — stored, fixed-Huffman and dynamic-Huffman blocks — so it
 * inflates real-world gzip streams (`gzip -6` output uses dynamic Huffman), not just the
 * stored-block form. It is a one-shot in-memory decoder, not a streaming zlib.
 */
internal object Inflate {

    /** Decompress a complete gzip stream, validating the CRC-32 and ISIZE trailer. */
    fun gunzip(input: ByteArray): ByteArray {
        if (input.size < GZIP_MIN_SIZE) {
            splatError("gzip stream too short (${input.size} bytes)")
        }
        if ((input[0].toInt() and 0xFF) != 0x1F || (input[1].toInt() and 0xFF) != 0x8B) {
            splatError("not a gzip stream (bad magic)")
        }
        val method = input[2].toInt() and 0xFF
        if (method != DEFLATE_METHOD) {
            splatError("unsupported gzip compression method $method")
        }
        val flags = input[3].toInt() and 0xFF
        var pos = GZIP_HEADER_SIZE // magic(2) + method(1) + flags(1) + mtime(4) + xfl(1) + os(1)
        if (flags and FEXTRA != 0) {
            if (pos + 2 > input.size) splatError("gzip FEXTRA truncated")
            pos += 2 + readLe16(input, pos)
        }
        if (flags and FNAME != 0) pos = skipZeroTerminated(input, pos)
        if (flags and FCOMMENT != 0) pos = skipZeroTerminated(input, pos)
        if (flags and FHCRC != 0) pos += 2
        if (pos >= input.size) splatError("gzip header truncated")

        // The last 8 bytes are CRC-32 then ISIZE (uncompressed size mod 2^32), both little-endian.
        val isize = readLe32(input, input.size - 4)
        val output = Inflater(input, pos).inflate(sizeHint = isize)

        val expectedCrc = readLe32(input, input.size - 8)
        if (crc32(output) != expectedCrc) splatError("gzip CRC32 mismatch")
        if ((output.size.toLong() and 0xFFFFFFFFL) != (isize.toLong() and 0xFFFFFFFFL)) {
            splatError("gzip ISIZE mismatch")
        }
        return output
    }

    /**
     * Decompress a **raw** DEFLATE stream (RFC 1951, no gzip or zlib wrapper) starting at [start]
     * in [input]. [sizeHint] is the expected uncompressed size when the container knows it — a ZIP
     * central directory does — and is only an allocation hint: it is clamped against a sane
     * expansion ratio and never trusted as a length.
     *
     * Used by the ZIP reader behind the 3MF loader, where every entry is a raw DEFLATE member.
     */
    fun raw(input: ByteArray, start: Int, sizeHint: Int = 0): ByteArray =
        Inflater(input, start).inflate(sizeHint)

    private fun skipZeroTerminated(data: ByteArray, start: Int): Int {
        var p = start
        while (p < data.size && data[p].toInt() != 0) p++
        return p + 1
    }

    private const val GZIP_MIN_SIZE = 18
    private const val GZIP_HEADER_SIZE = 10
    private const val DEFLATE_METHOD = 8
    private const val FHCRC = 0x02
    private const val FEXTRA = 0x04
    private const val FNAME = 0x08
    private const val FCOMMENT = 0x10
}

/** Canonical Huffman decoder table built from a list of code lengths (RFC 1951 §3.2.2). */
private class Huffman(lengths: IntArray, count: Int) {
    val counts = IntArray(MAX_BITS + 1)
    val symbols = IntArray(count)

    init {
        for (i in 0 until count) counts[lengths[i]]++
        counts[0] = 0
        val offsets = IntArray(MAX_BITS + 2)
        for (len in 1..MAX_BITS) offsets[len + 1] = offsets[len] + counts[len]
        for (sym in 0 until count) {
            if (lengths[sym] != 0) symbols[offsets[lengths[sym]]++] = sym
        }
    }

    companion object {
        const val MAX_BITS = 15
    }
}

/** Decodes a single DEFLATE stream from [input] starting at [start] (LSB-first bit order). */
private class Inflater(private val input: ByteArray, start: Int) {

    private var bytePos = start
    private var bitBuffer = 0
    private var bitCount = 0

    private var out = ByteArray(INITIAL_CAPACITY)
    private var outLen = 0

    fun inflate(sizeHint: Int): ByteArray {
        // Trust the trailer's ISIZE only within a sane expansion ratio of the
        // compressed input: a ~30-byte stream claiming ISIZE=256 MB must not
        // force that allocation up-front (memory-amplification DoS). DEFLATE
        // rarely exceeds ~1000x on real data; past the clamp, `ensureCapacity`
        // grows organically and the ISIZE check still validates at the end.
        val clamped = minOf(sizeHint.toLong(), input.size.toLong() * MAX_EXPANSION_RATIO)
        if (clamped in 1..MAX_SIZE_HINT.toLong()) {
            out = ByteArray(maxOf(clamped.toInt(), INITIAL_CAPACITY))
        }
        var lastBlock = false
        while (!lastBlock) {
            lastBlock = readBit() == 1
            when (val type = readBits(2)) {
                BLOCK_STORED -> inflateStored()
                BLOCK_FIXED -> inflateBlock(fixedLitLen(), fixedDist())
                BLOCK_DYNAMIC -> inflateDynamic()
                else -> splatError("DEFLATE: invalid block type $type")
            }
        }
        return out.copyOf(outLen)
    }

    private fun readBit(): Int {
        if (bitCount == 0) {
            if (bytePos >= input.size) splatError("DEFLATE: unexpected end of input")
            bitBuffer = input[bytePos++].toInt() and 0xFF
            bitCount = 8
        }
        val bit = bitBuffer and 1
        bitBuffer = bitBuffer shr 1
        bitCount--
        return bit
    }

    private fun readBits(n: Int): Int {
        var value = 0
        for (i in 0 until n) value = value or (readBit() shl i)
        return value
    }

    private fun decode(h: Huffman): Int {
        var code = 0
        var first = 0
        var index = 0
        for (len in 1..Huffman.MAX_BITS) {
            code = code or readBit()
            val count = h.counts[len]
            if (code - first < count) return h.symbols[index + (code - first)]
            index += count
            first = (first + count) shl 1
            code = code shl 1
        }
        splatError("DEFLATE: invalid Huffman code")
    }

    private fun ensureCapacity(extra: Int) {
        if (outLen + extra > out.size) {
            var newSize = out.size
            while (outLen + extra > newSize) newSize = newSize shl 1
            out = out.copyOf(newSize)
        }
    }

    private fun inflateStored() {
        // Stored blocks are byte-aligned: discard the remainder of the current partial byte.
        bitBuffer = 0
        bitCount = 0
        if (bytePos + 4 > input.size) splatError("DEFLATE stored: truncated length")
        val len = readLe16(input, bytePos)
        val nlen = readLe16(input, bytePos + 2)
        bytePos += 4
        if (len != (nlen.inv() and 0xFFFF)) splatError("DEFLATE stored: LEN/NLEN mismatch")
        if (bytePos + len > input.size) splatError("DEFLATE stored: truncated data")
        ensureCapacity(len)
        input.copyInto(out, outLen, bytePos, bytePos + len)
        outLen += len
        bytePos += len
    }

    private fun inflateDynamic() {
        val hlit = readBits(5) + 257
        val hdist = readBits(5) + 1
        val hclen = readBits(4) + 4
        val clLengths = IntArray(CODE_LENGTH_COUNT)
        for (i in 0 until hclen) clLengths[CODE_LENGTH_ORDER[i]] = readBits(3)
        val clHuffman = Huffman(clLengths, CODE_LENGTH_COUNT)

        val total = hlit + hdist
        val lengths = IntArray(total)
        var i = 0
        while (i < total) {
            when (val sym = decode(clHuffman)) {
                in 0..15 -> lengths[i++] = sym
                16 -> {
                    if (i == 0) splatError("DEFLATE: repeat with no previous length")
                    val repeat = 3 + readBits(2)
                    val prev = lengths[i - 1]
                    i = fill(lengths, i, repeat, prev, total)
                }
                17 -> i = fill(lengths, i, 3 + readBits(3), 0, total)
                18 -> i = fill(lengths, i, 11 + readBits(7), 0, total)
                else -> splatError("DEFLATE: invalid code-length symbol $sym")
            }
        }
        inflateBlock(
            Huffman(lengths.copyOfRange(0, hlit), hlit),
            Huffman(lengths.copyOfRange(hlit, total), hdist)
        )
    }

    private fun fill(lengths: IntArray, start: Int, repeat: Int, value: Int, total: Int): Int {
        if (start + repeat > total) splatError("DEFLATE: code-length repeat overflow")
        for (k in 0 until repeat) lengths[start + k] = value
        return start + repeat
    }

    private fun inflateBlock(litLen: Huffman, dist: Huffman) {
        while (true) {
            val sym = decode(litLen)
            when {
                sym == END_OF_BLOCK -> return
                sym < END_OF_BLOCK -> {
                    ensureCapacity(1)
                    out[outLen++] = sym.toByte()
                }
                else -> {
                    val s = sym - 257
                    if (s >= LENGTH_BASE.size) splatError("DEFLATE: invalid length symbol $sym")
                    val length = LENGTH_BASE[s] + readBits(LENGTH_EXTRA[s])
                    val dsym = decode(dist)
                    if (dsym >= DIST_BASE.size) splatError("DEFLATE: invalid distance symbol $dsym")
                    val distance = DIST_BASE[dsym] + readBits(DIST_EXTRA[dsym])
                    if (distance > outLen) splatError("DEFLATE: distance too far back")
                    ensureCapacity(length)
                    var src = outLen - distance
                    repeat(length) { out[outLen++] = out[src++] }
                }
            }
        }
    }

    private fun fixedLitLen(): Huffman {
        val lengths = IntArray(FIXED_LITLEN_COUNT)
        for (i in 0..143) lengths[i] = 8
        for (i in 144..255) lengths[i] = 9
        for (i in 256..279) lengths[i] = 7
        for (i in 280..287) lengths[i] = 8
        return Huffman(lengths, FIXED_LITLEN_COUNT)
    }

    private fun fixedDist(): Huffman = Huffman(IntArray(FIXED_DIST_COUNT) { 5 }, FIXED_DIST_COUNT)

    private companion object {
        const val INITIAL_CAPACITY = 1 shl 16
        const val MAX_SIZE_HINT = 1 shl 28
        const val MAX_EXPANSION_RATIO = 1024L
        const val BLOCK_STORED = 0
        const val BLOCK_FIXED = 1
        const val BLOCK_DYNAMIC = 2
        const val END_OF_BLOCK = 256
        const val CODE_LENGTH_COUNT = 19
        const val FIXED_LITLEN_COUNT = 288
        const val FIXED_DIST_COUNT = 30

        val CODE_LENGTH_ORDER =
            intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

        // Length codes 257..285: base length and number of extra bits (RFC 1951 §3.2.5).
        val LENGTH_BASE = intArrayOf(
            3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
            35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258
        )
        val LENGTH_EXTRA = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
            3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0
        )

        // Distance codes 0..29: base distance and number of extra bits.
        val DIST_BASE = intArrayOf(
            1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
            257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577
        )
        val DIST_EXTRA = intArrayOf(
            0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
            7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13
        )
    }
}
