package io.github.sceneview.demo.demos.internal

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * An Arrow schema as the bytes **arrow-rs** writes for it — the same schema has many valid
 * FlatBuffers encodings, and a `.rrd` footer stores the SHA-256 of this particular one
 * (`sorbet_schema_sha256`), so [RerunFlatBuffer]'s own layout would hash to something else.
 *
 * Transcribed from arrow-ipc 58.3.0 (`convert.rs`: `schema_to_fb_offset`, `build_field`,
 * `get_fb_field_type`, `metadata_to_fb`) and the Rust `flatbuffers` 25.12.19 builder it drives,
 * the versions Rerun 0.38.1 pins. Checked byte for byte against the schemas and the hashes
 * `rerun` 0.38.1 writes ([RerunRrdFooter] has the recipe).
 */
internal object RerunArrowSchemaMessage {
    private const val ALIGNMENT = 64
    private const val PREFIX_SIZE = 8
    private val CONTINUATION = byteArrayOf(-1, -1, -1, -1)

    /**
     * The encapsulated schema message of an IPC stream, as arrow-rs's `StreamWriter` frames it:
     * continuation marker, length, the flatbuffer, zeros up to a multiple of 64 bytes.
     */
    fun message(fields: List<RerunArrowField>): ByteArray {
        val flat = flatBuffer(fields)
        val aligned = (flat.size + PREFIX_SIZE + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT
        val out = LittleEndian()
        out.bytes(CONTINUATION)
        out.int(aligned - PREFIX_SIZE)
        out.bytes(flat)
        out.bytes(ByteArray(aligned - PREFIX_SIZE - flat.size))
        return out.toByteArray()
    }

    /** A schema-only IPC stream: [message], then the end-of-stream marker. */
    fun stream(fields: List<RerunArrowField>): ByteArray = message(fields) + CONTINUATION + ByteArray(4)

    /**
     * Rerun's `sorbet_schema_sha256`: the SHA-256 of [message] with the fields sorted by name (as
     * UTF-8 bytes), so the hash does not depend on the order the columns were met in.
     */
    fun sha256(fields: List<RerunArrowField>): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(message(fields.sortedWith(compareBy(RerunUtf8Order) { it.name })))

    private fun flatBuffer(fields: List<RerunArrowField>): ByteArray {
        val builder = BackwardFlatBuffer()
        val fieldVector = builder.vector(fields.map { field(builder, it) })
        builder.startTable()
        builder.offsetSlot(SCHEMA_FIELDS, fieldVector) // Endianness.Little is the default: not written.
        val schema = builder.endTable()
        builder.startTable()
        builder.int16Slot(MESSAGE_VERSION, METADATA_V5, default = 0)
        builder.uint8Slot(MESSAGE_HEADER_TYPE, HEADER_SCHEMA, default = 0)
        builder.offsetSlot(MESSAGE_HEADER, schema)
        return builder.finish(builder.endTable())
    }

    /** `build_field`: metadata, name, type and children, then the `Field` table. */
    private fun field(builder: BackwardFlatBuffer, field: RerunArrowField): Int {
        val metadata = if (field.metadata.isEmpty()) null else keyValues(builder, field.metadata)
        val name = builder.string(field.name)
        val (typeId, type, children) = type(builder, field.type)
        builder.startTable()
        builder.offsetSlot(FIELD_NAME, name)
        builder.uint8Slot(FIELD_TYPE_TYPE, typeId, default = 0)
        builder.uint8Slot(FIELD_NULLABLE, if (field.nullable) 1 else 0, default = 0)
        builder.offsetSlot(FIELD_CHILDREN, children)
        builder.offsetSlot(FIELD_TYPE, type)
        if (metadata != null) builder.offsetSlot(FIELD_METADATA, metadata)
        return builder.endTable()
    }

    /** `metadata_to_fb`: one `KeyValue` table per entry, in key order. */
    private fun keyValues(builder: BackwardFlatBuffer, metadata: Map<String, String>): Int =
        builder.vector(
            metadata.keys.sortedWith(RerunUtf8Order).map { key ->
                val k = builder.string(key)
                val v = builder.string(metadata.getValue(key))
                builder.startTable()
                builder.offsetSlot(KEY_VALUE_KEY, k)
                builder.offsetSlot(KEY_VALUE_VALUE, v)
                builder.endTable()
            },
        )

    /**
     * `get_fb_field_type`: the `Type` union's discriminant, its table and the children vector.
     * Which of the last two is written first differs per type, and so do the bytes.
     */
    private fun type(builder: BackwardFlatBuffer, type: RerunArrowType): Triple<Int, Int, Int> = when (type) {
        RerunArrowType.UInt8 -> integer(builder, bits = 8, signed = false)
        RerunArrowType.UInt32 -> integer(builder, bits = 32, signed = false)
        RerunArrowType.UInt64 -> integer(builder, bits = 64, signed = false)
        RerunArrowType.Int64 -> integer(builder, bits = 64, signed = true)
        RerunArrowType.Float32 -> {
            val children = builder.vector(emptyList())
            builder.startTable()
            builder.int16Slot(SLOT_0, PRECISION_SINGLE, default = 0)
            Triple(TYPE_FLOATING_POINT, builder.endTable(), children)
        }
        RerunArrowType.DurationNanoseconds -> {
            builder.startTable()
            builder.int16Slot(SLOT_0, UNIT_NANOSECOND, default = UNIT_MILLISECOND)
            val table = builder.endTable()
            Triple(TYPE_DURATION, table, builder.vector(emptyList()))
        }
        RerunArrowType.Utf8 -> Triple(TYPE_UTF8, emptyTable(builder), builder.vector(emptyList()))
        RerunArrowType.Bool -> Triple(TYPE_BOOL, emptyTable(builder), builder.vector(emptyList()))
        is RerunArrowType.FixedSizeBinary -> {
            builder.startTable()
            builder.int32Slot(SLOT_0, type.width, default = 0)
            val table = builder.endTable()
            Triple(TYPE_FIXED_SIZE_BINARY, table, builder.vector(emptyList()))
        }
        is RerunArrowType.ListOf -> {
            val item = field(builder, type.item)
            Triple(TYPE_LIST, emptyTable(builder), builder.vector(listOf(item)))
        }
        is RerunArrowType.FixedSizeList -> {
            val item = field(builder, type.item)
            builder.startTable()
            builder.int32Slot(SLOT_0, type.size, default = 0)
            val table = builder.endTable()
            Triple(TYPE_FIXED_SIZE_LIST, table, builder.vector(listOf(item)))
        }
        is RerunArrowType.Struct -> {
            val children = type.fields.map { field(builder, it) }
            Triple(TYPE_STRUCT, emptyTable(builder), builder.vector(children))
        }
    }

    /** `Int`: the (empty) children vector first, then `is_signed` before `bitWidth`. */
    private fun integer(builder: BackwardFlatBuffer, bits: Int, signed: Boolean): Triple<Int, Int, Int> {
        val children = builder.vector(emptyList())
        builder.startTable()
        builder.uint8Slot(SLOT_1, if (signed) 1 else 0, default = 0)
        builder.int32Slot(SLOT_0, bits, default = 0)
        return Triple(TYPE_INT, builder.endTable(), children)
    }

    private fun emptyTable(builder: BackwardFlatBuffer): Int {
        builder.startTable()
        return builder.endTable()
    }

    // Vtable offsets of the slots (4 + 2 × field index), from Arrow's `Schema.fbs` / `Message.fbs`.
    private const val SLOT_0 = 4
    private const val SLOT_1 = 6
    private const val KEY_VALUE_KEY = 4
    private const val KEY_VALUE_VALUE = 6
    private const val FIELD_NAME = 4
    private const val FIELD_NULLABLE = 6
    private const val FIELD_TYPE_TYPE = 8
    private const val FIELD_TYPE = 10
    private const val FIELD_CHILDREN = 14
    private const val FIELD_METADATA = 16
    private const val SCHEMA_FIELDS = 6
    private const val MESSAGE_VERSION = 4
    private const val MESSAGE_HEADER_TYPE = 6
    private const val MESSAGE_HEADER = 8

    private const val METADATA_V5 = 4
    private const val HEADER_SCHEMA = 1
    private const val PRECISION_SINGLE = 1
    private const val UNIT_MILLISECOND = 1
    private const val UNIT_NANOSECOND = 3

    // The `Type` union.
    private const val TYPE_INT = 2
    private const val TYPE_FLOATING_POINT = 3
    private const val TYPE_UTF8 = 5
    private const val TYPE_BOOL = 6
    private const val TYPE_LIST = 12
    private const val TYPE_STRUCT = 13
    private const val TYPE_FIXED_SIZE_BINARY = 15
    private const val TYPE_FIXED_SIZE_LIST = 16
    private const val TYPE_DURATION = 18
}

/** Rust's `str` order: byte-wise on UTF-8, which UTF-16 code units do not always follow. */
internal object RerunUtf8Order : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val x = a.encodeToByteArray()
        val y = b.encodeToByteArray()
        for (i in 0 until minOf(x.size, y.size)) {
            val difference = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (difference != 0) return difference
        }
        return x.size - y.size
    }
}

/**
 * The Rust `flatbuffers` builder's layout: the buffer grows towards its start, a table's fields
 * lie in the order they were pushed, default values are skipped, and a vtable identical to an
 * earlier one is shared instead of written again. Positions are distances from the buffer's end.
 */
private class BackwardFlatBuffer {
    private var bytes = ByteArray(INITIAL_CAPACITY)
    private var head = bytes.size
    private var minAlign = 1
    private val vtables = HashMap<ByteBuffer, Int>()

    /** The open table's slots: vtable offset → position of the value. */
    private val slots = ArrayList<Pair<Int, Int>>()
    private var tableStart = 0

    private val used: Int get() = bytes.size - head

    private fun makeSpace(count: Int) {
        if (head < count) {
            val grown = ByteArray(maxOf(bytes.size * 2, used + count))
            bytes.copyInto(grown, grown.size - used, head, bytes.size)
            head = grown.size - used
            bytes = grown
        }
        head -= count
    }

    /** Zeros so that, once [length] more bytes are written, the position is a multiple of [alignment]. */
    private fun align(length: Int, alignment: Int) {
        minAlign = maxOf(minAlign, alignment)
        makeSpace((alignment - (used + length) % alignment) % alignment)
    }

    private fun push(value: Int, size: Int): Int {
        align(size, size)
        makeSpace(size)
        put(head, value, size)
        return used
    }

    private fun put(at: Int, value: Int, size: Int) {
        for (i in 0 until size) bytes[at + i] = (value ushr (Byte.SIZE_BITS * i)).toByte()
    }

    /** A forward offset to [target], relative to where the offset itself lands. */
    private fun pushOffset(target: Int): Int {
        align(Int.SIZE_BYTES, Int.SIZE_BYTES)
        return push(Int.SIZE_BYTES + used - target, Int.SIZE_BYTES)
    }

    fun string(text: String): Int {
        val data = text.encodeToByteArray()
        align(data.size + 1, Int.SIZE_BYTES)
        push(0, 1)
        makeSpace(data.size)
        data.copyInto(bytes, head)
        return push(data.size, Int.SIZE_BYTES)
    }

    /** A vector of offsets to [targets] (tables or strings). */
    fun vector(targets: List<Int>): Int {
        val size = Int.SIZE_BYTES * targets.size
        align(size, Int.SIZE_BYTES)
        makeSpace(size)
        targets.forEachIndexed { index, target ->
            put(head + Int.SIZE_BYTES * index, used - Int.SIZE_BYTES * index - target, Int.SIZE_BYTES)
        }
        return push(targets.size, Int.SIZE_BYTES)
    }

    fun startTable() {
        slots.clear()
        tableStart = used
    }

    fun uint8Slot(slot: Int, value: Int, default: Int) {
        if (value != default) slots += slot to push(value, 1)
    }

    fun int16Slot(slot: Int, value: Int, default: Int) {
        if (value != default) slots += slot to push(value, Short.SIZE_BYTES)
    }

    fun int32Slot(slot: Int, value: Int, default: Int) {
        if (value != default) slots += slot to push(value, Int.SIZE_BYTES)
    }

    fun offsetSlot(slot: Int, target: Int) {
        slots += slot to pushOffset(target)
    }

    /** Closes the table: its vtable (shared when one with the same bytes exists), then the link to it. */
    fun endTable(): Int {
        val table = push(0, Int.SIZE_BYTES)
        val vtable = ByteArray((slots.maxOfOrNull { it.first } ?: Short.SIZE_BYTES) + Short.SIZE_BYTES)
        fun put16(at: Int, value: Int) {
            vtable[at] = value.toByte()
            vtable[at + 1] = (value ushr Byte.SIZE_BITS).toByte()
        }
        put16(0, vtable.size)
        put16(Short.SIZE_BYTES, table - tableStart)
        for ((slot, position) in slots) put16(slot, table - position)
        val position = vtables.getOrPut(ByteBuffer.wrap(vtable)) {
            makeSpace(vtable.size)
            vtable.copyInto(bytes, head)
            used
        }
        put(bytes.size - table, position - table, Int.SIZE_BYTES)
        slots.clear()
        return table
    }

    /** The finished buffer: the root offset first, the whole a multiple of the widest alignment. */
    fun finish(root: Int): ByteArray {
        align(Int.SIZE_BYTES, minAlign)
        pushOffset(root)
        return bytes.copyOfRange(head, bytes.size)
    }

    private companion object {
        const val INITIAL_CAPACITY = 1024
    }
}
