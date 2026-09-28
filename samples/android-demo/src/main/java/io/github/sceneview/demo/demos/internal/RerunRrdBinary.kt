package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.RerunRrdReader.Failure

/*
 * The byte-level readers under RerunRrdReader: a bounds-checked window, protobuf messages and
 * FlatBuffers tables. Every read that would leave the data throws Failure.Truncated rather than
 * an index error, whatever offset the file claims.
 */

/** A bounds-checked window on the file's bytes. */
internal class RrdBytes private constructor(private val storage: ByteArray, private val start: Int, val count: Int) {
    constructor(data: ByteArray) : this(data, 0, data.size)

    fun slice(offset: Int, length: Int): RrdBytes {
        val inside = offset in 0..count && length in 0..count - offset
        if (!inside) rrdFail(Failure.Truncated())
        return RrdBytes(storage, start + offset, length)
    }

    /** A little-endian unsigned integer of [size] bytes (as a `Long` bit pattern for 8). */
    fun uint(offset: Int, size: Int): Long {
        if (offset < 0 || size > Long.SIZE_BYTES || offset > count - size) rrdFail(Failure.Truncated())
        var value = 0L
        for (i in size - 1 downTo 0) {
            value = (value shl Byte.SIZE_BITS) or (storage[start + offset + i].toLong() and BYTE_MASK)
        }
        return value
    }

    fun u8(offset: Int): Int = uint(offset, 1).toInt()
    fun u16(offset: Int): Int = uint(offset, 2).toInt()
    fun u32(offset: Int): Long = uint(offset, 4)
    fun i32(offset: Int): Int = uint(offset, 4).toInt()
    fun u64(offset: Int): Long = uint(offset, 8)
    fun i64(offset: Int): Long = uint(offset, 8)

    /** A copy of [length] bytes at [offset]. */
    fun copy(offset: Int, length: Int): ByteArray {
        val window = slice(offset, length)
        return storage.copyOfRange(window.start, window.start + length)
    }

    fun matches(ascii: String, offset: Int): Boolean =
        offset >= 0 && offset + ascii.length <= count &&
            ascii.indices.all { storage[start + offset + it].toInt() == ascii[it].code }

    private companion object {
        const val BYTE_MASK = 0xFFL
    }
}

/** A protobuf message, decoded field by field (the last occurrence of a field wins). */
internal class RrdProto(bytes: RrdBytes) {
    private val varints = HashMap<Int, Long>()
    private val fixed = HashMap<Int, Long>()
    private val blobs = HashMap<Int, RrdBytes>()

    init {
        var position = 0
        while (position < bytes.count) {
            val (key, afterKey) = varint(bytes, position)
            position = afterKey
            val number = key ushr WIRE_TYPE_BITS
            if (number <= 0 || number > Int.MAX_VALUE) rrdFail(Failure.Malformed("protobuf field number"))
            position = field(bytes, number.toInt(), (key and WIRE_TYPE_MASK).toInt(), position)
        }
    }

    /** Reads one field's value at [position]; returns the position after it. */
    private fun field(bytes: RrdBytes, number: Int, wireType: Int, position: Int): Int {
        forget(number)
        return when (wireType) {
            WIRE_VARINT -> varint(bytes, position).let { (value, next) ->
                varints[number] = value
                next
            }
            WIRE_FIXED64 -> {
                fixed[number] = bytes.u64(position)
                position + Long.SIZE_BYTES
            }
            WIRE_BYTES -> {
                val (length, start) = varint(bytes, position)
                if (length < 0 || length > bytes.count - start) rrdFail(Failure.Truncated())
                blobs[number] = bytes.slice(start, length.toInt())
                start + length.toInt()
            }
            WIRE_FIXED32 -> {
                fixed[number] = bytes.u32(position)
                position + Int.SIZE_BYTES
            }
            else -> rrdFail(Failure.Malformed("protobuf wire type $wireType"))
        }
    }

    private fun forget(number: Int) {
        varints.remove(number)
        fixed.remove(number)
        blobs.remove(number)
    }

    fun varint(number: Int): Long? = varints[number]

    fun bytes(number: Int): RrdBytes? = blobs[number]

    fun message(number: Int): RrdProto? = blobs[number]?.let(::RrdProto)

    private companion object {
        const val WIRE_TYPE_BITS = 3
        const val WIRE_TYPE_MASK = 7L
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_BYTES = 2
        const val WIRE_FIXED32 = 5
        const val MAX_SHIFT = 64
        const val VARINT_SHIFT = 7
        const val LOW_BITS = 0x7FL
        const val MORE = 0x80

        /** The varint at [start] and the position after it. */
        fun varint(bytes: RrdBytes, start: Int): Pair<Long, Int> {
            var value = 0L
            var shift = 0
            var position = start
            while (true) {
                if (shift >= MAX_SHIFT) rrdFail(Failure.Malformed("protobuf varint too long"))
                val byte = bytes.u8(position++)
                value = value or ((byte.toLong() and LOW_BITS) shl shift)
                if (byte < MORE) return value to position
                shift += VARINT_SHIFT
            }
        }
    }
}

/**
 * Reads FlatBuffers the standard way: a table starts with a signed offset back to its vtable, the
 * vtable lists each field's offset in the table (0 when absent), tables, vectors and strings are
 * reached through unsigned forward offsets.
 */
internal class RrdFlatBuffer(val bytes: RrdBytes) {
    fun root(): Int = indirect(0)

    fun indirect(position: Int): Int {
        val target = position.toLong() + bytes.u32(position)
        if (target >= bytes.count) rrdFail(Failure.Truncated())
        return target.toInt()
    }

    fun field(table: Int, slot: Int): Int? {
        val vtable = table - bytes.i32(table)
        val vtableSize = bytes.u16(vtable)
        val entry = 4 + 2 * slot
        if (entry + 2 > vtableSize) return null
        val offset = bytes.u16(vtable + entry)
        return if (offset == 0) null else table + offset
    }

    fun u8(table: Int, slot: Int): Int = field(table, slot)?.let(bytes::u8) ?: 0

    fun i16(table: Int, slot: Int, default: Int = 0): Int =
        field(table, slot)?.let { bytes.u16(it).toShort().toInt() } ?: default

    fun i32(table: Int, slot: Int): Int = field(table, slot)?.let(bytes::i32) ?: 0

    fun i64(table: Int, slot: Int): Long = field(table, slot)?.let(bytes::i64) ?: 0L

    fun table(table: Int, slot: Int): Int? = field(table, slot)?.let(::indirect)

    fun string(table: Int, slot: Int): String? {
        val start = table(table, slot) ?: return null
        val length = bytes.u32(start)
        if (length > bytes.count) rrdFail(Failure.Truncated())
        return bytes.copy(start + 4, length.toInt()).decodeToString()
    }

    /** A vector's element count and the position of its first element. */
    fun vector(table: Int, slot: Int, elementSize: Int): Pair<Int, Int>? {
        val position = table(table, slot) ?: return null
        val count = bytes.u32(position)
        if (count > maxOf(0, bytes.count - position - 4) / elementSize) rrdFail(Failure.Truncated())
        return count.toInt() to position + 4
    }

    fun tables(table: Int, slot: Int): List<Int> {
        val (count, start) = vector(table, slot, 4) ?: return emptyList()
        return List(count) { indirect(start + 4 * it) }
    }

    /** A `[KeyValue]` field (Arrow's custom metadata). */
    fun keyValues(table: Int, slot: Int): Map<String, String> {
        val out = HashMap<String, String>()
        for (entry in tables(table, slot)) string(entry, 0)?.let { key -> out[key] = string(entry, 1) ?: "" }
        return out
    }
}
