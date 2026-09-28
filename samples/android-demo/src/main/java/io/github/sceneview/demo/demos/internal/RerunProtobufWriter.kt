package io.github.sceneview.demo.demos.internal

import java.io.ByteArrayOutputStream

/**
 * A minimal protobuf encoder: the three wire types `.rrd` messages use (varint, 64-bit,
 * length-delimited). Fields are written in the order they are appended. The iOS demo's
 * `RerunProtobufWriter`, byte for byte.
 */
internal class RerunProtobufWriter {
    private val out = ByteArrayOutputStream()

    val bytes: ByteArray get() = out.toByteArray()

    /** [value] as an unsigned 64-bit varint. */
    fun varint(value: Long) {
        var rest = value
        while (rest and VARINT_LOW_BITS.inv() != 0L) {
            out.write(((rest and VARINT_LOW_BITS) or VARINT_MORE).toInt())
            rest = rest ushr VARINT_SHIFT
        }
        out.write(rest.toInt())
    }

    fun key(field: Int, wireType: Int) = varint((field.toLong() shl WIRE_TYPE_BITS) or wireType.toLong())

    /** `uint64`, `bool` and enum fields; [value] is read as unsigned. */
    fun uint64(field: Int, value: Long) {
        key(field, WIRE_VARINT)
        varint(value)
    }

    /** Negative int32s are sign-extended to ten bytes, per the protobuf spec. */
    fun int32(field: Int, value: Int) {
        key(field, WIRE_VARINT)
        varint(value.toLong())
    }

    fun bool(field: Int, value: Boolean) = uint64(field, if (value) 1L else 0L)

    fun fixed64(field: Int, value: Long) {
        key(field, WIRE_FIXED64)
        for (i in 0 until Long.SIZE_BYTES) out.write((value ushr (Byte.SIZE_BITS * i)).toInt() and 0xFF)
    }

    fun bytes(field: Int, value: ByteArray) {
        key(field, WIRE_BYTES)
        varint(value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String) = bytes(field, value.encodeToByteArray())

    /** A nested message, built by [body]. */
    fun message(field: Int, body: RerunProtobufWriter.() -> Unit) =
        bytes(field, RerunProtobufWriter().apply(body).bytes)

    private companion object {
        const val VARINT_LOW_BITS = 0x7FL
        const val VARINT_MORE = 0x80L
        const val VARINT_SHIFT = 7
        const val WIRE_TYPE_BITS = 3
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_BYTES = 2
    }
}
