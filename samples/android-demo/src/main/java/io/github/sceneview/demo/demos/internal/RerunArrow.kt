package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.RerunFlatBuffer.Node
import io.github.sceneview.demo.demos.internal.RerunFlatBuffer.Slot
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * A minimal Arrow IPC stream encoder for `.rrd` chunks: the handful of column types Rerun
 * components use, no nulls except where a struct needs them, no dictionaries, no compression.
 * Spec: https://arrow.apache.org/docs/format/Columnar.html (IPC streaming format, metadata
 * version V5, flatbuffers `Schema.fbs` / `Message.fbs`). The iOS demo's `RerunRRDArrow.swift`.
 */

/** An Arrow logical type, restricted to what the `.rrd` writer emits. */
internal sealed class RerunArrowType {
    data object UInt8 : RerunArrowType()
    data object UInt32 : RerunArrowType()
    data object Float32 : RerunArrowType()

    /** `duration[ns]`: Rerun's type for a duration timeline. */
    data object DurationNanoseconds : RerunArrowType()
    data object Utf8 : RerunArrowType()
    data class FixedSizeBinary(val width: Int) : RerunArrowType()
    data class ListOf(val item: RerunArrowField) : RerunArrowType()
    data class FixedSizeList(val item: RerunArrowField, val size: Int) : RerunArrowType()
    data class Struct(val fields: List<RerunArrowField>) : RerunArrowType()
}

/** An Arrow field: name, type, nullability and key/value metadata. */
internal data class RerunArrowField(
    val name: String,
    val type: RerunArrowType,
    val nullable: Boolean,
    val metadata: Map<String, String> = emptyMap(),
) {
    companion object {
        /** The conventional `item` child of a list type. */
        fun item(type: RerunArrowType, nullable: Boolean) = RerunArrowField("item", type, nullable)
    }
}

/**
 * One Arrow array's physical layout: its buffers in IPC order (validity first) and its children,
 * depth-first — exactly what a record batch's `nodes` and `buffers` describe.
 */
internal class RerunArrowArray(
    val length: Int,
    val buffers: List<ByteArray>,
    val children: List<RerunArrowArray> = emptyList(),
    val nullCount: Int = 0,
) {
    companion object {
        private val NONE = ByteArray(0)

        fun floats(values: FloatArray) =
            RerunArrowArray(values.size, listOf(NONE, le(values.size * 4) { b -> values.forEach { b.putFloat(it) } }))

        /** `uint32` values, stored as their bit patterns. */
        fun uint32s(values: IntArray) =
            RerunArrowArray(values.size, listOf(NONE, le(values.size * 4) { b -> values.forEach { b.putInt(it) } }))

        fun uint8s(values: ByteArray) = RerunArrowArray(values.size, listOf(NONE, values.copyOf()))

        fun int64s(values: LongArray) =
            RerunArrowArray(values.size, listOf(NONE, le(values.size * 8) { b -> values.forEach { b.putLong(it) } }))

        /** A primitive array where every slot is null. */
        fun allNull(count: Int, byteWidth: Int) =
            RerunArrowArray(count, listOf(ByteArray((count + 7) / 8), ByteArray(count * byteWidth)), nullCount = count)

        /** `fixed_size_binary[width]` from contiguous bytes. */
        fun fixedSizeBinary(bytes: ByteArray, width: Int) = RerunArrowArray(bytes.size / width, listOf(NONE, bytes))

        /** `utf8` strings. */
        fun utf8(strings: List<String>): RerunArrowArray {
            val values = ByteArrayOutputStream()
            val offsets = IntArray(strings.size + 1)
            strings.forEachIndexed { index, string ->
                values.write(string.encodeToByteArray())
                offsets[index + 1] = values.size()
            }
            return RerunArrowArray(strings.size, listOf(NONE, int32s(offsets), values.toByteArray()))
        }

        /** A `list<...>` whose row `i` holds `counts[i]` consecutive child values. */
        fun list(counts: List<Int>, child: RerunArrowArray): RerunArrowArray {
            val offsets = IntArray(counts.size + 1)
            counts.forEachIndexed { index, count -> offsets[index + 1] = offsets[index] + count }
            return RerunArrowArray(counts.size, listOf(NONE, int32s(offsets)), listOf(child))
        }

        /** A `fixed_size_list<...>[size]` over [child]. */
        fun fixedSizeList(size: Int, child: RerunArrowArray) =
            RerunArrowArray(child.length / size, listOf(NONE), listOf(child))

        /** A `struct<...>` of [length] rows. */
        fun structure(length: Int, children: List<RerunArrowArray>) = RerunArrowArray(length, listOf(NONE), children)

        private fun int32s(values: IntArray) = le(values.size * 4) { b -> values.forEach { b.putInt(it) } }

        private inline fun le(size: Int, fill: (ByteBuffer) -> Unit): ByteArray {
            val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            fill(buffer)
            return buffer.array()
        }
    }
}

/**
 * Encodes one schema and one record batch as an Arrow IPC stream (what arrow-rs' `StreamReader`
 * and pyarrow's `ipc.open_stream` read).
 */
internal object RerunArrowIpc {
    private const val CONTINUATION = -1
    private const val HEADER_SCHEMA = 1
    private const val HEADER_RECORD_BATCH = 3
    private const val METADATA_V5 = 4

    /** Schema message, record batch message, then the end-of-stream marker. */
    fun stream(
        fields: List<RerunArrowField>,
        columns: List<RerunArrowArray>,
        rowCount: Int,
        metadata: Map<String, String>,
    ): ByteArray {
        require(fields.size == columns.size) { "one column per field" }
        val nodes = LittleEndian()
        val buffers = LittleEndian()
        var nodeCount = 0
        var bufferCount = 0
        val body = LittleEndian()
        fun flatten(array: RerunArrowArray) {
            nodes.long(array.length.toLong())
            nodes.long(array.nullCount.toLong())
            nodeCount++
            for (buffer in array.buffers) {
                buffers.long(body.size.toLong())
                buffers.long(buffer.size.toLong())
                bufferCount++
                body.bytes(buffer)
                body.padTo8()
            }
            array.children.forEach(::flatten)
        }
        columns.forEach(::flatten)

        val schema = Node.Table(
            mapOf(
                0 to Slot.I16(0), // Endianness.Little
                1 to Slot.Child(Node.Vector(fields.map(::fieldTable))),
                2 to Slot.Child(Node.Vector(keyValues(metadata))),
            ),
        )
        val recordBatch = Node.Table(
            mapOf(
                0 to Slot.I64(rowCount.toLong()),
                1 to Slot.Child(Node.Structs(nodeCount, 8, nodes.toByteArray())),
                2 to Slot.Child(Node.Structs(bufferCount, 8, buffers.toByteArray())),
            ),
        )
        val out = LittleEndian()
        appendMessage(schema, HEADER_SCHEMA, ByteArray(0), out)
        appendMessage(recordBatch, HEADER_RECORD_BATCH, body.toByteArray(), out)
        out.int(CONTINUATION)
        out.int(0)
        return out.toByteArray()
    }

    private fun appendMessage(header: Node, headerType: Int, body: ByteArray, out: LittleEndian) {
        val message = Node.Table(
            mapOf(
                0 to Slot.I16(METADATA_V5),
                1 to Slot.U8(headerType),
                2 to Slot.Child(header),
                3 to Slot.I64(body.size.toLong()),
            ),
        )
        val flat = RerunFlatBuffer.serialize(message)
        // The prefix is 8 bytes, so padding the flatbuffer to 8 keeps the body 8-aligned.
        val metadata = flat.copyOf((flat.size + 7) / 8 * 8)
        out.int(CONTINUATION)
        out.int(metadata.size)
        out.bytes(metadata)
        out.bytes(body)
    }

    private fun fieldTable(field: RerunArrowField): Node {
        val (typeId, typeTable, children) = typeDescription(field.type)
        val slots = linkedMapOf<Int, Slot>(
            0 to Slot.Child(Node.Text(field.name)),
            1 to Slot.Bool(field.nullable),
            2 to Slot.U8(typeId),
            3 to Slot.Child(typeTable),
            5 to Slot.Child(Node.Vector(children.map(::fieldTable))),
        )
        if (field.metadata.isNotEmpty()) slots[6] = Slot.Child(Node.Vector(keyValues(field.metadata)))
        return Node.Table(slots)
    }

    /** The `Type` union discriminant, its table, and the child fields. */
    private fun typeDescription(type: RerunArrowType): Triple<Int, Node, List<RerunArrowField>> = when (type) {
        RerunArrowType.UInt8 -> Triple(2, Node.Table(mapOf(0 to Slot.I32(8), 1 to Slot.Bool(false))), emptyList())
        RerunArrowType.UInt32 -> Triple(2, Node.Table(mapOf(0 to Slot.I32(32), 1 to Slot.Bool(false))), emptyList())
        RerunArrowType.Float32 -> Triple(3, Node.Table(mapOf(0 to Slot.I16(1))), emptyList()) // SINGLE
        RerunArrowType.DurationNanoseconds -> Triple(18, Node.Table(mapOf(0 to Slot.I16(3))), emptyList())
        RerunArrowType.Utf8 -> Triple(5, Node.Table(emptyMap()), emptyList())
        is RerunArrowType.FixedSizeBinary -> Triple(15, Node.Table(mapOf(0 to Slot.I32(type.width))), emptyList())
        is RerunArrowType.ListOf -> Triple(12, Node.Table(emptyMap()), listOf(type.item))
        is RerunArrowType.FixedSizeList -> Triple(16, Node.Table(mapOf(0 to Slot.I32(type.size))), listOf(type.item))
        is RerunArrowType.Struct -> Triple(13, Node.Table(emptyMap()), type.fields)
    }

    /** Sorted by key so the output is deterministic. */
    private fun keyValues(metadata: Map<String, String>): List<Node> = metadata.keys.sorted().map { key ->
        Node.Table(mapOf(0 to Slot.Child(Node.Text(key)), 1 to Slot.Child(Node.Text(metadata.getValue(key)))))
    }
}

/** A growable little-endian byte buffer. */
internal class LittleEndian {
    private val out = ByteArrayOutputStream()

    val size: Int get() = out.size()

    fun toByteArray(): ByteArray = out.toByteArray()

    fun bytes(value: ByteArray) = out.write(value)

    fun int(value: Int) = long(value.toLong(), Int.SIZE_BYTES)

    fun long(value: Long, width: Int = Long.SIZE_BYTES) {
        for (i in 0 until width) out.write((value ushr (Byte.SIZE_BITS * i)).toInt() and 0xFF)
    }

    fun padTo8() {
        while (out.size() % 8 != 0) out.write(0)
    }
}
