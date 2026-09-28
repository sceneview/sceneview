package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.RerunRrdReader.Failure

/*
 * The Arrow IPC side of RerunRrdReader: types, fields, arrays and the stream of schema and record
 * batch messages. Only the layouts Rerun components use are decoded; anything else is kept as
 * Unsupported, to be reported if a column the replay needs has it.
 */

/** An Arrow type, as far as the reader tells types apart. */
internal sealed class RrdArrowType {
    /** Buffers the type owns in a record batch; `null` for a type the reader cannot walk. */
    abstract val bufferCount: Int?

    data object Null : RrdArrowType() {
        override val bufferCount = 0
    }

    data object Bool : RrdArrowType() {
        override val bufferCount = 2
    }

    data class IntType(val bits: Int, val signed: Boolean) : RrdArrowType() {
        override val bufferCount = 2
    }

    data class FloatType(val bits: Int) : RrdArrowType() {
        override val bufferCount = 2
    }

    data class Binary(val largeOffsets: Boolean) : RrdArrowType() {
        override val bufferCount = 3
    }

    data class Utf8(val largeOffsets: Boolean) : RrdArrowType() {
        override val bufferCount = 3
    }

    data class ListType(val largeOffsets: Boolean) : RrdArrowType() {
        override val bufferCount = 2
    }

    data class FixedSizeList(val size: Int) : RrdArrowType() {
        override val bufferCount = 1
    }

    data object Struct : RrdArrowType() {
        override val bufferCount = 1
    }

    data class FixedSizeBinary(val width: Int) : RrdArrowType() {
        override val bufferCount = 2
    }

    /** A timestamp or duration: nanoseconds in one unit. */
    data class Time(val nanosPerUnit: Long) : RrdArrowType() {
        override val bufferCount = 2
    }

    data class Unsupported(val what: String) : RrdArrowType() {
        override val bufferCount: Int? = null
    }

    companion object {
        private val NANOS_PER_UNIT = longArrayOf(1_000_000_000L, 1_000_000L, 1_000L, 1L)
        private val INT_BITS = setOf(8, 16, 32, 64)
        private val FLOAT_BITS = intArrayOf(16, 32, 64)

        private fun time(unit: Int): RrdArrowType =
            NANOS_PER_UNIT.getOrNull(unit)?.let(::Time) ?: Unsupported("time unit $unit")

        /** The `Type` union member [id], its [table] read from [flat]. */
        fun of(id: Int, table: Int?, flat: RrdFlatBuffer): RrdArrowType = when (id) {
            1 -> Null
            4 -> Binary(false)
            5 -> Utf8(false)
            6 -> Bool
            12 -> ListType(false)
            13 -> Struct
            19 -> Binary(true)
            20 -> Utf8(true)
            21 -> ListType(true)
            else -> parameterized(id, table, flat)
        }

        /** The types whose table carries a parameter: a width, a precision, a size or a unit. */
        private fun parameterized(id: Int, table: Int?, flat: RrdFlatBuffer): RrdArrowType {
            fun int32(slot: Int) = table?.let { flat.i32(it, slot) } ?: 0
            fun int16(slot: Int, default: Int) = table?.let { flat.i16(it, slot, default) } ?: default
            return when (id) {
                2 -> int32(0).let { bits ->
                    val signed = table?.let { flat.u8(it, 1) != 0 } ?: false
                    if (bits in INT_BITS) IntType(bits, signed) else Unsupported("int$bits")
                }
                3 -> FLOAT_BITS.getOrNull(int16(0, 0))?.let(::FloatType) ?: Unsupported("float precision")
                10 -> time(int16(0, 0)) // Timestamp; its unit has no default.
                15 -> int32(0).let { if (it >= 0) FixedSizeBinary(it) else Unsupported("fixed_size_binary") }
                16 -> int32(0).let { if (it >= 0) FixedSizeList(it) else Unsupported("fixed_size_list") }
                18 -> time(int16(0, 1)) // Duration, milliseconds by default.
                else -> Unsupported("Arrow type $id")
            }
        }
    }
}

/** `Field`: name (0), type discriminant (2) and table (3), dictionary (4), children (5), metadata (6). */
internal class RrdArrowField(flat: RrdFlatBuffer, table: Int, depth: Int = 0) {
    val name: String
    val type: RrdArrowType
    val children: List<RrdArrowField>
    val metadata: Map<String, String>

    init {
        if (depth >= MAX_DEPTH) rrdFail(Failure.Malformed("Arrow schema nested too deep"))
        name = flat.string(table, 0) ?: ""
        metadata = flat.keyValues(table, 6)
        children = flat.tables(table, 5).map { RrdArrowField(flat, it, depth + 1) }
        type = if (flat.table(table, 4) != null) {
            RrdArrowType.Unsupported("dictionary-encoded")
        } else {
            RrdArrowType.of(flat.u8(table, 2), flat.table(table, 3), flat)
        }
    }

    private companion object {
        const val MAX_DEPTH = 32
    }
}

/** One decoded Arrow array: its buffers (sizes and offsets checked when read) and children. */
internal class RrdArrowArray(val name: String, val type: RrdArrowType, val length: Int) {
    var validity: RrdBytes? = null
    var offsets: RrdBytes? = null
    var values: RrdBytes? = null
    var children: List<RrdArrowArray> = emptyList()

    private val offsetWidth: Int
        get() = when (val t = type) {
            is RrdArrowType.Binary -> if (t.largeOffsets) 8 else 4
            is RrdArrowType.Utf8 -> if (t.largeOffsets) 8 else 4
            is RrdArrowType.ListType -> if (t.largeOffsets) 8 else 4
            else -> 4
        }

    fun isNull(index: Int): Boolean {
        val bits = validity ?: return false
        return bits.u8(index / Byte.SIZE_BITS) and (1 shl (index % Byte.SIZE_BITS)) == 0
    }

    /** Child (or byte) range of list slot [index]. */
    fun range(index: Int): IntRange {
        val offsets = offsets ?: rrdFail(Failure.Malformed("$name: no offsets"))
        val width = offsetWidth
        val lower = signed(offsets.uint(index * width, width), width)
        val upper = signed(offsets.uint((index + 1) * width, width), width)
        if (lower < 0 || lower > upper || upper > Int.MAX_VALUE) rrdFail(Failure.Malformed("$name: offsets"))
        return lower.toInt() until upper.toInt()
    }

    private fun signed(raw: Long, width: Int): Long = if (width == 4) raw.toInt().toLong() else raw

    fun float32(index: Int): Float {
        val values = values
        if (type != RrdArrowType.FloatType(32) || values == null) rrdFail(Failure.UnexpectedLayout(name))
        return Float.fromBits(values.i32(index * 4))
    }

    /** Any integer (or time) value, sign-extended when signed. */
    fun integer(index: Int): Long {
        val values = values ?: rrdFail(Failure.UnexpectedLayout(name))
        return when (val t = type) {
            is RrdArrowType.IntType -> {
                val raw = values.uint(index * t.bits / Byte.SIZE_BITS, t.bits / Byte.SIZE_BITS)
                if (!t.signed || t.bits == Long.SIZE_BITS) {
                    raw
                } else {
                    val shift = Long.SIZE_BITS - t.bits
                    (raw shl shift) shr shift
                }
            }
            is RrdArrowType.Time -> values.i64(index * Long.SIZE_BYTES)
            else -> rrdFail(Failure.UnexpectedLayout(name))
        }
    }

    fun byteRange(range: IntRange): ByteArray {
        val values = values ?: rrdFail(Failure.UnexpectedLayout(name))
        return values.copy(range.first, range.last - range.first + 1)
    }

    fun child(name: String): RrdArrowArray? = children.firstOrNull { it.name == name }
}

/** `Schema`: endianness (0), fields (1), custom metadata (2). */
internal class RrdSchema(flat: RrdFlatBuffer, table: Int) {
    val fields: List<RrdArrowField>
    val metadata: Map<String, String>

    init {
        if (flat.i16(table, 0) != 0) rrdFail(Failure.UnexpectedLayout("big-endian Arrow data"))
        fields = flat.tables(table, 1).map { RrdArrowField(flat, it) }
        metadata = flat.keyValues(table, 2)
    }
}

/**
 * The Arrow IPC streaming format: messages prefixed by `0xFFFFFFFF` and a length (or the length
 * alone, the pre-1.0 form), a flatbuffer `Message`, then its body.
 */
internal object RrdArrowStream {
    private const val HEADER_SCHEMA = 1
    private const val HEADER_RECORD_BATCH = 3

    fun chunks(ipc: RrdBytes): List<RrdChunk> {
        var position = 0
        var schema: RrdSchema? = null
        val chunks = ArrayList<RrdChunk>()
        while (position < ipc.count) {
            var length = ipc.i32(position)
            position += 4
            if (length == -1) {
                length = ipc.i32(position)
                position += 4
            }
            if (length == 0) break // End of stream.
            if (length < 0) rrdFail(Failure.Malformed("Arrow message length"))
            val flat = RrdFlatBuffer(ipc.slice(position, length))
            position += length
            // `Message`: version (0), header type (1), header (2), body length (3).
            val message = flat.root()
            val bodyLength = flat.i64(message, 3)
            if (bodyLength < 0 || bodyLength > ipc.count - position) rrdFail(Failure.Truncated())
            val body = ipc.slice(position, bodyLength.toInt())
            position += bodyLength.toInt()
            val header = flat.table(message, 2) ?: rrdFail(Failure.Malformed("Arrow message without header"))
            when (flat.u8(message, 1)) {
                HEADER_SCHEMA -> schema = RrdSchema(flat, header)
                HEADER_RECORD_BATCH -> {
                    val known = schema ?: rrdFail(Failure.Malformed("record batch before its schema"))
                    RrdChunk.of(known, flat, header, body)?.let { chunks += it }
                }
                else -> Unit // Dictionary batches, tensors: nothing the writer emits.
            }
        }
        return chunks
    }
}

/**
 * Walks a record batch's `nodes` and `buffers` depth-first against the schema, checking each
 * buffer is long enough and each offset in range before anything reads it.
 */
internal class RrdBatchReader(private val nodes: List<Pair<Int, Int>>, private val buffers: List<RrdBytes>) {
    private var nodeIndex = 0
    private var bufferIndex = 0

    fun array(field: RrdArrowField): RrdArrowArray {
        val bufferCount = field.type.bufferCount ?: rrdFail(Failure.UnexpectedLayout("${field.name}: ${field.type}"))
        if (nodeIndex >= nodes.size || bufferIndex + bufferCount > buffers.size) {
            rrdFail(Failure.Malformed("record batch shorter than its schema"))
        }
        val (length, nullCount) = nodes[nodeIndex++]
        val own = buffers.subList(bufferIndex, bufferIndex + bufferCount)
        bufferIndex += bufferCount

        val array = RrdArrowArray(field.name, field.type, length)
        if (bufferCount > 0 && nullCount > 0) {
            if (own[0].count < (length + 7) / 8) rrdFail(Failure.Truncated())
            array.validity = own[0]
        }
        when (val type = field.type) {
            RrdArrowType.Null, is RrdArrowType.Unsupported -> Unit
            RrdArrowType.Bool -> fixedWidth(array, own, (length + 7) / 8, 1)
            is RrdArrowType.IntType -> fixedWidth(array, own, length, type.bits / Byte.SIZE_BITS)
            is RrdArrowType.FloatType -> fixedWidth(array, own, length, type.bits / Byte.SIZE_BITS)
            is RrdArrowType.Time -> fixedWidth(array, own, length, Long.SIZE_BYTES)
            is RrdArrowType.FixedSizeBinary -> fixedWidth(array, own, length, type.width)
            is RrdArrowType.Binary, is RrdArrowType.Utf8 -> {
                array.offsets = own[1]
                array.values = own[2]
                checkOffsets(array, own[2].count)
            }
            is RrdArrowType.ListType -> {
                array.offsets = own[1]
                val child = onlyChild(field)
                array.children = listOf(child)
                checkOffsets(array, child.length)
            }
            is RrdArrowType.FixedSizeList -> {
                val child = onlyChild(field)
                if (child.length.toLong() < length.toLong() * type.size) {
                    rrdFail(Failure.Malformed("${field.name}: short values"))
                }
                array.children = listOf(child)
            }
            RrdArrowType.Struct -> {
                array.children = field.children.map(::array)
                if (array.children.any { it.length < length }) {
                    rrdFail(Failure.Malformed("${field.name}: short struct field"))
                }
            }
        }
        return array
    }

    private fun fixedWidth(array: RrdArrowArray, own: List<RrdBytes>, count: Int, bytesPerValue: Int) {
        if (own[1].count.toLong() < count.toLong() * bytesPerValue) rrdFail(Failure.Truncated())
        array.values = own[1]
    }

    private fun onlyChild(field: RrdArrowField): RrdArrowArray {
        if (field.children.size != 1) rrdFail(Failure.Malformed("${field.name}: one child expected"))
        return array(field.children[0])
    }

    /** Offsets never decrease and stay within the child (or byte) count. */
    private fun checkOffsets(array: RrdArrowArray, limit: Int) {
        var previous = 0
        for (index in 0 until array.length) {
            val range = array.range(index)
            if (range.first < previous || range.last + 1 > limit) {
                rrdFail(Failure.Malformed("${array.name}: offsets out of range"))
            }
            previous = range.last + 1
        }
    }
}
