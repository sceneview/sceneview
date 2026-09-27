package io.github.sceneview.core.splat

/**
 * Pure-Kotlin Zstandard (RFC 8878) decoder, shared by every Kotlin Multiplatform target so
 * [SpzParser] can read SPZ version 4 (one ZSTD frame per attribute stream) without a native
 * libzstd or an `expect`/`actual` per platform — the same choice [Inflate] makes for gzip.
 *
 * Scope: complete single-shot decoding of standard frames — raw, RLE and compressed blocks,
 * raw/RLE/Huffman/treeless literals (1 and 4 streams, direct or FSE-compressed weights),
 * predefined/RLE/FSE/repeat sequence tables and repeat offsets — plus skippable frames and
 * concatenated frames. Not supported: dictionaries (a frame that names one is rejected). The
 * optional XXH64 content checksum is skipped rather than verified; callers that know the
 * expected size (the SPZ table of contents does) check it instead.
 *
 * Arithmetic stays in `Int` on purpose: this runs on Kotlin/JS too, where `Long` is emulated.
 */
internal object Zstd {

    /**
     * Decompress every frame in `input[offset until offset + length]` and return the
     * concatenated content. When [expectedSize] is non-negative the result must be exactly that
     * long, which also bounds the output buffer against a corrupt frame.
     */
    fun decompress(input: ByteArray, offset: Int = 0, length: Int = input.size - offset, expectedSize: Int = -1): ByteArray {
        val end = offset + length
        if (offset < 0 || length < 0 || end > input.size) splatError("zstd: range out of bounds")
        // Start small and grow: a corrupt or hostile size must not allocate up front.
        val initial = if (length > (Int.MAX_VALUE - 65_536) / 16) Int.MAX_VALUE else length * 16 + 65_536
        val out = OutputBuffer(if (expectedSize >= 0) minOf(expectedSize, initial) else initial, expectedSize)
        var pos = offset
        if (pos >= end) splatError("zstd: empty input")
        while (pos < end) {
            if (end - pos < 4) splatError("zstd: truncated frame magic")
            val magic = readLe32(input, pos)
            pos += 4
            pos = if ((magic and SKIPPABLE_MASK) == SKIPPABLE_MAGIC) {
                if (end - pos < 4) splatError("zstd: truncated skippable frame")
                val size = readLe32(input, pos)
                if (size < 0 || size > end - pos - 4) splatError("zstd: skippable frame overruns input")
                pos + 4 + size
            } else if (magic == FRAME_MAGIC) {
                FrameDecoder(input, end, out).decode(pos)
            } else {
                splatError("zstd: bad frame magic 0x${magic.toUInt().toString(16)}")
            }
        }
        if (expectedSize >= 0 && out.size != expectedSize) {
            splatError("zstd: decompressed ${out.size} bytes, expected $expectedSize")
        }
        return out.toByteArray()
    }

    private const val FRAME_MAGIC = 0xFD2FB528.toInt()
    private const val SKIPPABLE_MAGIC = 0x184D2A50
    private const val SKIPPABLE_MASK = 0xFFFFFFF0.toInt()
}

/** Growable output with the back-reference copy the sequence executor needs. */
private class OutputBuffer(initialCapacity: Int, private val limit: Int) {
    var data = ByteArray(maxOf(initialCapacity, 16))
    var size = 0

    private fun ensure(extra: Int) {
        val needed = size + extra
        if (needed < 0 || (limit >= 0 && needed > limit)) {
            splatError("zstd: output exceeds the expected ${limit} bytes")
        }
        if (needed > data.size) {
            var cap = data.size
            while (cap < needed) cap = if (cap > Int.MAX_VALUE / 2) Int.MAX_VALUE else cap * 2
            data = data.copyOf(cap)
        }
    }

    fun write(src: ByteArray, from: Int, count: Int) {
        if (count == 0) return
        ensure(count)
        src.copyInto(data, size, from, from + count)
        size += count
    }

    fun fill(value: Byte, count: Int) {
        ensure(count)
        data.fill(value, size, size + count)
        size += count
    }

    /** Copy [count] bytes starting [distance] back; overlapping copies repeat the pattern. */
    fun copyMatch(distance: Int, count: Int, frameStart: Int) {
        if (distance <= 0 || distance > size - frameStart) splatError("zstd: match offset $distance out of range")
        ensure(count)
        var src = size - distance
        val d = data
        if (distance >= count) {
            d.copyInto(d, size, src, src + count)
            size += count
        } else {
            var dst = size
            repeat(count) { d[dst++] = d[src++] }
            size = dst
        }
    }

    fun toByteArray(): ByteArray = if (size == data.size) data else data.copyOf(size)
}

/** Decodes one standard frame; holds the tables that persist from block to block. */
private class FrameDecoder(private val src: ByteArray, private val end: Int, private val out: OutputBuffer) {

    private val frameStart = out.size
    private var huffman: HuffmanTable? = null
    private var llTable: FseTable? = null
    private var ofTable: FseTable? = null
    private var mlTable: FseTable? = null
    private val repeatOffsets = intArrayOf(1, 4, 8)
    private var literals = ByteArray(0)
    private var literalsSize = 0

    /** Decode the frame whose header starts at [start] (just past the magic); returns the end. */
    fun decode(start: Int): Int {
        var pos = start
        if (pos >= end) splatError("zstd: truncated frame header")
        val descriptor = src[pos++].toInt() and 0xFF
        val fcsFlag = descriptor ushr 6
        val singleSegment = (descriptor and 0x20) != 0
        if ((descriptor and 0x08) != 0) splatError("zstd: reserved frame header bit set")
        val hasChecksum = (descriptor and 0x04) != 0
        val dictIdSize = intArrayOf(0, 1, 2, 4)[descriptor and 0x3]
        if (!singleSegment) pos++ // window descriptor: a single-shot decoder needs no window bound
        var dictId = 0
        for (i in 0 until dictIdSize) dictId = dictId or ((byteAt(pos + i)) shl (8 * i))
        if (dictId != 0) splatError("zstd: dictionaries are not supported (dictionary id $dictId)")
        pos += dictIdSize
        val fcsSize = when (fcsFlag) {
            0 -> if (singleSegment) 1 else 0
            1 -> 2
            2 -> 4
            else -> 8
        }
        pos += fcsSize // content size is not needed: the caller checks the total
        if (pos > end) splatError("zstd: truncated frame header")

        while (true) {
            if (end - pos < 3) splatError("zstd: truncated block header")
            val header = byteAt(pos) or (byteAt(pos + 1) shl 8) or (byteAt(pos + 2) shl 16)
            pos += 3
            val last = (header and 1) != 0
            val type = (header ushr 1) and 0x3
            val blockSize = header ushr 3
            when (type) {
                0 -> { // raw
                    if (blockSize > end - pos) splatError("zstd: raw block overruns input")
                    out.write(src, pos, blockSize)
                    pos += blockSize
                }
                1 -> { // RLE: one byte, repeated blockSize times
                    if (pos >= end) splatError("zstd: truncated RLE block")
                    out.fill(src[pos], blockSize)
                    pos += 1
                }
                2 -> {
                    if (blockSize > end - pos) splatError("zstd: compressed block overruns input")
                    if (blockSize > MAX_BLOCK_SIZE) splatError("zstd: block larger than 128 KiB")
                    decodeCompressedBlock(pos, pos + blockSize)
                    pos += blockSize
                }
                else -> splatError("zstd: reserved block type")
            }
            if (last) break
        }
        if (hasChecksum) {
            if (end - pos < 4) splatError("zstd: truncated content checksum")
            pos += 4
        }
        return pos
    }

    private fun byteAt(i: Int): Int {
        if (i >= end) splatError("zstd: unexpected end of input")
        return src[i].toInt() and 0xFF
    }

    // ── literals ────────────────────────────────────────────────────────────────────────────

    private fun decodeCompressedBlock(start: Int, blockEnd: Int) {
        var pos = decodeLiterals(start, blockEnd)
        if (pos >= blockEnd) splatError("zstd: missing sequences section")
        val b0 = byteAt(pos)
        val sequenceCount: Int
        when {
            b0 == 0 -> { sequenceCount = 0; pos += 1 }
            b0 < 128 -> { sequenceCount = b0; pos += 1 }
            b0 < 255 -> { sequenceCount = ((b0 - 128) shl 8) + byteAt(pos + 1); pos += 2 }
            else -> { sequenceCount = byteAt(pos + 1) + (byteAt(pos + 2) shl 8) + 0x7F00; pos += 3 }
        }
        if (sequenceCount == 0) {
            out.write(literals, 0, literalsSize)
            return
        }
        val modes = byteAt(pos++)
        if ((modes and 0x3) != 0) splatError("zstd: reserved sequence-mode bits set")
        pos = readSequenceTable(pos, blockEnd, (modes ushr 6) and 3, SequenceKind.LITERAL_LENGTH)
        pos = readSequenceTable(pos, blockEnd, (modes ushr 4) and 3, SequenceKind.OFFSET)
        pos = readSequenceTable(pos, blockEnd, (modes ushr 2) and 3, SequenceKind.MATCH_LENGTH)
        executeSequences(pos, blockEnd, sequenceCount)
    }

    /** Decodes the literals section into [literals]; returns the position after it. */
    private fun decodeLiterals(start: Int, blockEnd: Int): Int {
        val b0 = byteAt(start)
        val type = b0 and 0x3
        val sizeFormat = (b0 ushr 2) and 0x3
        if (type == 0 || type == 1) {
            val regenerated: Int
            val headerSize: Int
            when (sizeFormat) {
                0, 2 -> { regenerated = b0 ushr 3; headerSize = 1 }
                1 -> { regenerated = (b0 ushr 4) + (byteAt(start + 1) shl 4); headerSize = 2 }
                else -> {
                    regenerated = (b0 ushr 4) + (byteAt(start + 1) shl 4) + (byteAt(start + 2) shl 12)
                    headerSize = 3
                }
            }
            if (regenerated > MAX_BLOCK_SIZE) splatError("zstd: literals larger than a block")
            val pos = start + headerSize
            ensureLiterals(regenerated)
            if (type == 0) {
                if (regenerated > blockEnd - pos) splatError("zstd: raw literals overrun the block")
                src.copyInto(literals, 0, pos, pos + regenerated)
                return pos + regenerated
            }
            if (pos >= blockEnd) splatError("zstd: truncated RLE literals")
            literals.fill(src[pos], 0, regenerated)
            return pos + 1
        }

        // Huffman-compressed (type 2) or treeless (type 3, reuses the previous table).
        val regenerated: Int
        val compressed: Int
        val headerSize: Int
        val fourStreams = sizeFormat != 0
        when (sizeFormat) {
            0, 1 -> {
                val v = b0 or (byteAt(start + 1) shl 8) or (byteAt(start + 2) shl 16)
                regenerated = (v ushr 4) and 0x3FF
                compressed = (v ushr 14) and 0x3FF
                headerSize = 3
            }
            2 -> {
                val v = b0 or (byteAt(start + 1) shl 8) or (byteAt(start + 2) shl 16) or (byteAt(start + 3) shl 24)
                regenerated = (v ushr 4) and 0x3FFF
                compressed = (v ushr 18) and 0x3FFF
                headerSize = 4
            }
            else -> {
                val v = b0 or (byteAt(start + 1) shl 8) or (byteAt(start + 2) shl 16) or (byteAt(start + 3) shl 24)
                regenerated = (v ushr 4) and 0x3FFFF
                compressed = ((v ushr 22) and 0x3FF) or (byteAt(start + 4) shl 10)
                headerSize = 5
            }
        }
        if (regenerated > MAX_BLOCK_SIZE) splatError("zstd: literals larger than a block")
        var pos = start + headerSize
        val literalsEnd = pos + compressed
        if (literalsEnd > blockEnd) splatError("zstd: compressed literals overrun the block")
        if (type == 2) {
            pos = readHuffmanTable(pos, literalsEnd)
        } else if (huffman == null) {
            splatError("zstd: treeless literals without a previous Huffman table")
        }
        val table = huffman!!
        ensureLiterals(regenerated)
        if (!fourStreams) {
            decodeHuffmanStream(table, pos, literalsEnd, 0, regenerated)
        } else {
            if (literalsEnd - pos < 6) splatError("zstd: truncated literals jump table")
            val s1 = readLe16(src, pos)
            val s2 = readLe16(src, pos + 2)
            val s3 = readLe16(src, pos + 4)
            val streamsStart = pos + 6
            val s4 = literalsEnd - streamsStart - s1 - s2 - s3
            if (s4 < 1) splatError("zstd: bad literals jump table")
            val segment = (regenerated + 3) / 4
            if (segment * 3 > regenerated) splatError("zstd: too few literals for four streams")
            var streamPos = streamsStart
            var outPos = 0
            val sizes = intArrayOf(s1, s2, s3, s4)
            for (i in 0 until 4) {
                val count = if (i < 3) segment else regenerated - 3 * segment
                decodeHuffmanStream(table, streamPos, streamPos + sizes[i], outPos, count)
                streamPos += sizes[i]
                outPos += count
            }
        }
        literalsSize = regenerated
        return literalsEnd
    }

    private fun ensureLiterals(size: Int) {
        if (literals.size < size) literals = ByteArray(maxOf(size, MAX_BLOCK_SIZE))
        literalsSize = size
    }

    private fun decodeHuffmanStream(table: HuffmanTable, from: Int, to: Int, outStart: Int, count: Int) {
        val bits = BackwardBitReader(src, from, to)
        val maxBits = table.maxBits
        val mask = (1 shl maxBits) - 1
        var state = bits.read(maxBits)
        val lit = literals
        for (i in 0 until count) {
            lit[outStart + i] = table.symbols[state]
            val n = table.numBits[state].toInt()
            state = ((state shl n) + bits.read(n)) and mask
        }
        // After the last symbol the reader must sit exactly maxBits before the stream start.
        if (bits.position != -maxBits) splatError("zstd: Huffman stream size mismatch")
    }

    /** Reads a Huffman tree description into [huffman]; returns the position after it. */
    private fun readHuffmanTable(start: Int, limit: Int): Int {
        val header = byteAt(start)
        var pos = start + 1
        val weights = IntArray(260)
        var count: Int
        if (header >= 128) {
            count = header - 127
            val bytes = (count + 1) / 2
            if (bytes > limit - pos) splatError("zstd: truncated Huffman weights")
            for (i in 0 until count) {
                val b = src[pos + i / 2].toInt() and 0xFF
                weights[i] = if (i % 2 == 0) b ushr 4 else b and 0xF
            }
            pos += bytes
        } else {
            if (header > limit - pos) splatError("zstd: truncated FSE Huffman weights")
            val fseEnd = pos + header
            val reader = ForwardBitReader(src, pos, fseEnd)
            val table = FseTable.readDescription(reader, MAX_HUFFMAN_WEIGHT_LOG, 255)
            val streamStart = reader.alignedPosition()
            count = decodeInterleavedWeights(table, streamStart, fseEnd, weights)
            pos = fseEnd
        }
        if (count > 255) splatError("zstd: too many Huffman weights")
        huffman = HuffmanTable.fromWeights(weights, count)
        return pos
    }

    /** Two interleaved FSE states share one table; decoding stops when the stream is exhausted. */
    private fun decodeInterleavedWeights(table: FseTable, from: Int, to: Int, weights: IntArray): Int {
        if (to <= from) splatError("zstd: empty FSE weight stream")
        val bits = BackwardBitReader(src, from, to)
        var state1 = bits.read(table.accuracyLog)
        var state2 = bits.read(table.accuracyLog)
        var n = 0
        while (true) {
            if (n > 255) splatError("zstd: too many Huffman weights")
            weights[n++] = table.symbols[state1].toInt()
            state1 = table.base[state1] + bits.read(table.numBits[state1].toInt())
            if (bits.position < 0) { weights[n++] = table.symbols[state2].toInt(); break }
            weights[n++] = table.symbols[state2].toInt()
            state2 = table.base[state2] + bits.read(table.numBits[state2].toInt())
            if (bits.position < 0) { weights[n++] = table.symbols[state1].toInt(); break }
        }
        return n
    }

    // ── sequences ───────────────────────────────────────────────────────────────────────────

    private enum class SequenceKind { LITERAL_LENGTH, OFFSET, MATCH_LENGTH }

    private fun readSequenceTable(start: Int, blockEnd: Int, mode: Int, kind: SequenceKind): Int {
        var pos = start
        val table: FseTable = when (mode) {
            0 -> when (kind) {
                SequenceKind.LITERAL_LENGTH -> FseTable.PREDEFINED_LL
                SequenceKind.OFFSET -> FseTable.PREDEFINED_OF
                SequenceKind.MATCH_LENGTH -> FseTable.PREDEFINED_ML
            }
            1 -> FseTable.rle(byteAt(pos++))
            2 -> {
                val (maxLog, maxSymbol) = when (kind) {
                    SequenceKind.LITERAL_LENGTH -> 9 to 35
                    SequenceKind.OFFSET -> 8 to 31
                    SequenceKind.MATCH_LENGTH -> 9 to 52
                }
                val reader = ForwardBitReader(src, pos, blockEnd)
                val t = FseTable.readDescription(reader, maxLog, maxSymbol)
                pos = reader.alignedPosition()
                t
            }
            else -> when (kind) {
                SequenceKind.LITERAL_LENGTH -> llTable
                SequenceKind.OFFSET -> ofTable
                SequenceKind.MATCH_LENGTH -> mlTable
            } ?: splatError("zstd: repeat table mode without a previous table")
        }
        when (kind) {
            SequenceKind.LITERAL_LENGTH -> llTable = table
            SequenceKind.OFFSET -> ofTable = table
            SequenceKind.MATCH_LENGTH -> mlTable = table
        }
        return pos
    }

    private fun executeSequences(start: Int, blockEnd: Int, count: Int) {
        val ll = llTable!!
        val of = ofTable!!
        val ml = mlTable!!
        val bits = BackwardBitReader(src, start, blockEnd)
        var llState = bits.read(ll.accuracyLog)
        var ofState = bits.read(of.accuracyLog)
        var mlState = bits.read(ml.accuracyLog)
        var literalPos = 0
        val rep = repeatOffsets
        for (i in 0 until count) {
            val ofCode = of.symbols[ofState].toInt()
            val llCode = ll.symbols[llState].toInt()
            val mlCode = ml.symbols[mlState].toInt()
            if (llCode > 35 || mlCode > 52 || ofCode > 31) splatError("zstd: invalid sequence code")

            val offsetValue = if (ofCode < 31) (1 shl ofCode) + bits.readLong(ofCode) else splatError("zstd: offset code too large")
            val matchLength = ML_BASE[mlCode] + bits.read(ML_BITS[mlCode])
            val literalLength = LL_BASE[llCode] + bits.read(LL_BITS[llCode])

            if (i < count - 1) { // the last sequence does not update the states
                llState = ll.base[llState] + bits.read(ll.numBits[llState].toInt())
                mlState = ml.base[mlState] + bits.read(ml.numBits[mlState].toInt())
                ofState = of.base[ofState] + bits.read(of.numBits[ofState].toInt())
            }

            val offset: Int
            if (offsetValue > 3) {
                offset = offsetValue - 3
                rep[2] = rep[1]; rep[1] = rep[0]; rep[0] = offset
            } else {
                var idx = offsetValue - 1
                if (literalLength == 0) idx++
                if (idx == 0) {
                    offset = rep[0]
                } else {
                    offset = if (idx < 3) rep[idx] else rep[0] - 1
                    if (idx > 1) rep[2] = rep[1]
                    rep[1] = rep[0]
                    rep[0] = offset
                }
            }

            if (literalLength > literalsSize - literalPos) splatError("zstd: sequence reads past the literals")
            out.write(literals, literalPos, literalLength)
            literalPos += literalLength
            out.copyMatch(offset, matchLength, frameStart)
        }
        if (bits.position != 0) splatError("zstd: sequence bitstream size mismatch")
        out.write(literals, literalPos, literalsSize - literalPos)
    }

    companion object {
        const val MAX_BLOCK_SIZE = 128 * 1024
        const val MAX_HUFFMAN_WEIGHT_LOG = 6

        val LL_BASE = intArrayOf(
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
            16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096,
            8192, 16384, 32768, 65536,
        )
        val LL_BITS = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12,
            13, 14, 15, 16,
        )
        val ML_BASE = intArrayOf(
            3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
            19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
            35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051,
            4099, 8195, 16387, 32771, 65539,
        )
        val ML_BITS = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11,
            12, 13, 14, 15, 16,
        )
    }
}

/** Huffman decoding table indexed by the next `maxBits` bits of the stream. */
private class HuffmanTable(val maxBits: Int, val symbols: ByteArray, val numBits: ByteArray) {
    companion object {
        /** Builds the table from the `count` transmitted weights; the last weight is implied. */
        fun fromWeights(weights: IntArray, count: Int): HuffmanTable {
            var total = 0
            for (i in 0 until count) {
                val w = weights[i]
                if (w > 11) splatError("zstd: Huffman weight $w too large")
                if (w > 0) total += 1 shl (w - 1)
            }
            if (total == 0) splatError("zstd: empty Huffman weights")
            val maxBits = highestBit(total) + 1
            if (maxBits > 11) splatError("zstd: Huffman code longer than 11 bits")
            val left = (1 shl maxBits) - total
            if (left and (left - 1) != 0) splatError("zstd: Huffman weights do not complete a tree")
            weights[count] = highestBit(left) + 1
            val symbolCount = count + 1

            val size = 1 shl maxBits
            val symbols = ByteArray(size)
            val numBits = ByteArray(size)
            val rankCount = IntArray(maxBits + 2)
            val bits = IntArray(symbolCount)
            for (s in 0 until symbolCount) {
                bits[s] = if (weights[s] > 0) maxBits + 1 - weights[s] else 0
                rankCount[bits[s]]++
            }
            val rankStart = IntArray(maxBits + 2)
            // Longest codes take the lowest table indices.
            for (b in maxBits downTo 1) rankStart[b - 1] = rankStart[b] + rankCount[b] * (1 shl (maxBits - b))
            for (s in 0 until symbolCount) {
                val b = bits[s]
                if (b == 0) continue
                val start = rankStart[b]
                val len = 1 shl (maxBits - b)
                for (j in start until start + len) {
                    symbols[j] = s.toByte()
                    numBits[j] = b.toByte()
                }
                rankStart[b] = start + len
            }
            return HuffmanTable(maxBits, symbols, numBits)
        }
    }
}

/** Finite State Entropy decoding table. */
private class FseTable(val accuracyLog: Int, val symbols: ByteArray, val numBits: ByteArray, val base: IntArray) {
    companion object {
        fun rle(symbol: Int) = FseTable(0, byteArrayOf(symbol.toByte()), byteArrayOf(0), intArrayOf(0))

        /** Reads a normalized-count table description (RFC 8878 §4.1.1) and builds the table. */
        fun readDescription(reader: ForwardBitReader, maxLog: Int, maxSymbol: Int): FseTable {
            val accuracyLog = reader.read(4) + 5
            if (accuracyLog > maxLog) splatError("zstd: FSE accuracy log $accuracyLog > $maxLog")
            var remaining = 1 shl accuracyLog
            val counts = IntArray(maxSymbol + 1)
            var symbol = 0
            while (remaining > 0) {
                if (symbol > maxSymbol) splatError("zstd: FSE table has too many symbols")
                val bits = highestBit(remaining + 1) + 1
                var value = reader.read(bits)
                val lowerMask = (1 shl (bits - 1)) - 1
                val threshold = (1 shl bits) - 1 - (remaining + 1)
                if ((value and lowerMask) < threshold) {
                    reader.rewind(1)
                    value = value and lowerMask
                } else if (value > lowerMask) {
                    value -= threshold
                }
                val probability = value - 1
                remaining -= if (probability < 0) -probability else probability
                counts[symbol++] = probability
                if (probability == 0) {
                    var repeat = reader.read(2)
                    while (true) {
                        for (i in 0 until repeat) {
                            if (symbol > maxSymbol) splatError("zstd: FSE zero-run overflows the alphabet")
                            counts[symbol++] = 0
                        }
                        if (repeat == 3) repeat = reader.read(2) else break
                    }
                }
            }
            if (remaining != 0) splatError("zstd: FSE probabilities do not sum to the table size")
            return build(counts, symbol, accuracyLog)
        }

        fun build(counts: IntArray, symbolCount: Int, accuracyLog: Int): FseTable {
            val size = 1 shl accuracyLog
            val symbols = ByteArray(size)
            val next = IntArray(symbolCount)
            var highThreshold = size
            for (s in 0 until symbolCount) {
                if (counts[s] == -1) {
                    symbols[--highThreshold] = s.toByte()
                    next[s] = 1
                } else {
                    next[s] = counts[s]
                }
            }
            val step = (size ushr 1) + (size ushr 3) + 3
            val mask = size - 1
            var pos = 0
            for (s in 0 until symbolCount) {
                for (i in 0 until counts[s]) {
                    symbols[pos] = s.toByte()
                    do { pos = (pos + step) and mask } while (pos >= highThreshold)
                }
            }
            if (pos != 0) splatError("zstd: FSE spread did not cover the table")
            val numBits = ByteArray(size)
            val base = IntArray(size)
            for (i in 0 until size) {
                val s = symbols[i].toInt() and 0xFF
                val n = next[s]++
                val bits = accuracyLog - highestBit(n)
                numBits[i] = bits.toByte()
                base[i] = (n shl bits) - size
            }
            return FseTable(accuracyLog, symbols, numBits, base)
        }

        private fun predefined(counts: IntArray, accuracyLog: Int) = build(counts, counts.size, accuracyLog)

        val PREDEFINED_LL = predefined(
            intArrayOf(
                4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1,
                1, 1, 1, 1, -1, -1, -1, -1,
            ),
            6,
        )
        val PREDEFINED_ML = predefined(
            intArrayOf(
                1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1, -1, -1,
            ),
            6,
        )
        val PREDEFINED_OF = predefined(
            intArrayOf(
                1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1,
                -1, -1,
            ),
            5,
        )
    }
}

/** Little-endian, least-significant-bit-first reader for FSE table descriptions. */
private class ForwardBitReader(private val src: ByteArray, private val start: Int, private val end: Int) {
    private var bit = 0 // bit position relative to start

    fun read(n: Int): Int {
        var value = 0
        for (i in 0 until n) {
            val p = bit + i
            val byteIndex = start + (p ushr 3)
            if (byteIndex >= end) splatError("zstd: FSE table description overruns its section")
            value = value or (((src[byteIndex].toInt() ushr (p and 7)) and 1) shl i)
        }
        bit += n
        return value
    }

    fun rewind(n: Int) { bit -= n }

    /** Position of the first byte after the bits read so far. */
    fun alignedPosition(): Int = start + (bit + 7) / 8
}

/**
 * Reads a ZSTD backward bitstream: it starts at the highest set bit of the last byte (the
 * end-of-stream marker) and moves towards the first byte. Reading past the start yields zeros
 * and drives [position] negative, which the decoders use to detect the end of the stream.
 */
private class BackwardBitReader(private val src: ByteArray, private val start: Int, private val end: Int) {
    var position: Int

    init {
        if (end <= start) splatError("zstd: empty bitstream")
        val last = src[end - 1].toInt() and 0xFF
        if (last == 0) splatError("zstd: bitstream is missing its end marker")
        position = (end - start) * 8 - (8 - highestBit(last))
    }

    /** Reads [n] bits (0..24). */
    fun read(n: Int): Int {
        if (n == 0) return 0
        position -= n
        var at = position
        var count = n
        if (at < 0) {
            count += at
            at = 0
            if (count <= 0) return 0
        }
        var value = bitsAt(at, count)
        if (position < 0) value = value shl (-position)
        return value
    }

    /** Reads up to 30 bits, as two reads so the byte window never exceeds 32 bits. */
    fun readLong(n: Int): Int {
        if (n <= 24) return read(n)
        val high = read(n - 16)
        val low = read(16)
        return (high shl 16) or low
    }

    private fun bitsAt(bitOffset: Int, count: Int): Int {
        val first = start + (bitOffset ushr 3)
        val shift = bitOffset and 7
        var window = 0
        for (i in 0 until 4) {
            val idx = first + i
            if (idx < end) window = window or ((src[idx].toInt() and 0xFF) shl (8 * i))
        }
        return (window ushr shift) and ((1 shl count) - 1)
    }
}

/** Index of the highest set bit (`0` for `1`); [value] must be positive. */
private fun highestBit(value: Int): Int = 31 - value.countLeadingZeroBits()
