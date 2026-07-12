package io.github.sceneview.core.splat

/**
 * Small little-endian readers shared by the splat parsers. PLY `binary_little_endian` records and
 * the SPZ header are both little-endian, matching every target platform this SDK ships on.
 *
 * All readers assume the caller has bounds-checked the buffer; an out-of-range access throws
 * [IndexOutOfBoundsException], which the public [SplatParser] entry points convert into a
 * [SplatParseException].
 */

/** Read an unsigned 16-bit little-endian value at [at] into an Int (`0..65535`). */
internal fun readLe16(data: ByteArray, at: Int): Int =
    (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)

/** Read a signed 16-bit little-endian value at [at]. */
internal fun readLe16Signed(data: ByteArray, at: Int): Int = readLe16(data, at).toShort().toInt()

/** Read a 32-bit little-endian value at [at] into an Int (bit pattern; sign per two's complement). */
internal fun readLe32(data: ByteArray, at: Int): Int =
    (data[at].toInt() and 0xFF) or
        ((data[at + 1].toInt() and 0xFF) shl 8) or
        ((data[at + 2].toInt() and 0xFF) shl 16) or
        ((data[at + 3].toInt() and 0xFF) shl 24)

/** Read a 64-bit little-endian value at [at] into a Long (bit pattern). */
internal fun readLe64(data: ByteArray, at: Int): Long =
    (readLe32(data, at).toLong() and 0xFFFFFFFFL) or
        (readLe32(data, at + 4).toLong() shl 32)

/** Read an IEEE-754 32-bit float, little-endian, at [at]. */
internal fun readLeFloat(data: ByteArray, at: Int): Float = Float.fromBits(readLe32(data, at))

/** Read an IEEE-754 64-bit double, little-endian, at [at]. */
internal fun readLeDouble(data: ByteArray, at: Int): Double = Double.fromBits(readLe64(data, at))
