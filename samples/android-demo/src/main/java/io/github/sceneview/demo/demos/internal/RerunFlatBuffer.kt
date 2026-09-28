package io.github.sceneview.demo.demos.internal

/**
 * A tiny FlatBuffers serializer, written front to back: every table, vector and string is laid
 * out after whatever points at it (FlatBuffers offsets are unsigned and point forward), each
 * vtable right before its table. Alignment is relative to the buffer start. The iOS demo's
 * `RerunFlatBuffer`, byte for byte.
 */
internal object RerunFlatBuffer {
    sealed class Node {
        /** Field slot index → value. */
        class Table(val slots: Map<Int, Slot>) : Node()

        class Text(val value: String) : Node()

        /** A vector of tables (or strings). */
        class Vector(val items: List<Node>) : Node()

        /** A vector of fixed-size structs, already packed. */
        class Structs(val count: Int, val alignment: Int, val bytes: ByteArray) : Node()
    }

    sealed class Slot(val size: Int) {
        class U8(val value: Int) : Slot(1)
        class Bool(val value: Boolean) : Slot(1)
        class I16(val value: Int) : Slot(2)
        class I32(val value: Int) : Slot(4)
        class I64(val value: Long) : Slot(8)
        class Child(val node: Node) : Slot(4)
    }

    /** The whole buffer: a root offset, then the root table. */
    fun serialize(root: Node): ByteArray {
        val writer = Writer()
        writer.zeros(4)
        val rootPosition = writer.write(root)
        writer.patchOffset(0, rootPosition)
        return writer.toByteArray()
    }

    private class Writer {
        private var bytes = ByteArray(INITIAL_CAPACITY)
        var size = 0
            private set

        fun toByteArray(): ByteArray = bytes.copyOf(size)

        private fun ensure(extra: Int) {
            if (size + extra > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + extra))
        }

        fun append(byte: Int) {
            ensure(1)
            bytes[size++] = byte.toByte()
        }

        fun append(data: ByteArray) {
            ensure(data.size)
            data.copyInto(bytes, size)
            size += data.size
        }

        fun zeros(count: Int) {
            ensure(count)
            size += count // The array is zero-filled past `size`: it only ever grows.
        }

        fun pad(alignment: Int) {
            while (size % alignment != 0) append(0)
        }

        /** Little-endian [value] of [width] bytes at the end. */
        fun put(value: Long, width: Int) {
            ensure(width)
            store(value, width, size)
            size += width
        }

        fun store(value: Long, width: Int, position: Int) {
            for (i in 0 until width) bytes[position + i] = (value ushr (Byte.SIZE_BITS * i)).toByte()
        }

        fun patchOffset(position: Int, target: Int) = store((target - position).toLong(), 4, position)

        /** Writes [node] at the end of the buffer and returns the position offsets target. */
        fun write(node: Node): Int = when (node) {
            is Node.Text -> writeString(node.value)
            is Node.Vector -> writeVector(node.items)
            is Node.Structs -> writeStructs(node)
            is Node.Table -> writeTable(node.slots)
        }

        private fun writeString(value: String): Int {
            pad(4)
            val position = size
            val utf8 = value.encodeToByteArray()
            put(utf8.size.toLong(), 4)
            append(utf8)
            append(0)
            return position
        }

        private fun writeVector(items: List<Node>): Int {
            pad(4)
            val position = size
            put(items.size.toLong(), 4)
            val slots = size
            zeros(4 * items.size)
            items.forEachIndexed { index, item -> patchOffset(slots + 4 * index, write(item)) }
            return position
        }

        private fun writeStructs(node: Node.Structs): Int {
            pad(4)
            // The elements follow the 4-byte length and must be aligned themselves.
            while ((size + 4) % node.alignment != 0) append(0)
            val position = size
            put(node.count.toLong(), 4)
            append(node.bytes)
            return position
        }

        private fun writeTable(slots: Map<Int, Slot>): Int {
            // Largest fields first keeps the inline part tightly packed and aligned.
            val order = slots.keys.sortedWith(compareByDescending<Int> { slots.getValue(it).size }.thenBy { it })
            val inlineOffset = HashMap<Int, Int>()
            var inlineSize = 4 // the soffset to the vtable
            var maxAlignment = 4
            for (id in order) {
                val slotSize = slots.getValue(id).size
                inlineSize = (inlineSize + slotSize - 1) / slotSize * slotSize
                inlineOffset[id] = inlineSize
                inlineSize += slotSize
                maxAlignment = maxOf(maxAlignment, slotSize)
            }
            val slotCount = (slots.keys.maxOrNull() ?: -1) + 1

            pad(2)
            val vtable = size
            put((4 + 2 * slotCount).toLong(), 2)
            put(inlineSize.toLong(), 2)
            for (id in 0 until slotCount) put((inlineOffset[id] ?: 0).toLong(), 2)

            pad(maxAlignment)
            val table = size
            put((table - vtable).toLong(), 4)
            zeros(inlineSize - 4)

            val pending = ArrayList<Pair<Int, Node>>()
            for (id in order) {
                val position = table + inlineOffset.getValue(id)
                when (val slot = slots.getValue(id)) {
                    is Slot.U8 -> store(slot.value.toLong(), 1, position)
                    is Slot.Bool -> store(if (slot.value) 1L else 0L, 1, position)
                    is Slot.I16 -> store(slot.value.toLong(), 2, position)
                    is Slot.I32 -> store(slot.value.toLong(), 4, position)
                    is Slot.I64 -> store(slot.value, 8, position)
                    is Slot.Child -> pending += position to slot.node
                }
            }
            for ((position, child) in pending) patchOffset(position, write(child))
            return table
        }

        private companion object {
            const val INITIAL_CAPACITY = 256
        }
    }
}
