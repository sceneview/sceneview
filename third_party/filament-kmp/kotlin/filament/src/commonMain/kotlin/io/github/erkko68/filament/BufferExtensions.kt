package io.github.erkko68.filament

/**
 * Extension functions to convert primitive arrays to ByteArray for Filament buffer uploads.
 *
 * These utilities facilitate conversion of Kotlin primitive arrays to raw byte arrays
 * in native byte order (little-endian), which is required for uploading vertex data,
 * index data, and other buffer content to Filament buffers.
 */

/**
 * Convert a FloatArray to a ByteArray in native byte order.
 *
 * Each float is converted to 4 bytes using IEEE 754 representation in little-endian
 * format. The resulting byte array has size = floats.size * 4.
 *
 * @return A ByteArray containing the binary representation of this float array.
 */
fun FloatArray.toBytes(): ByteArray {
    val result = ByteArray(this.size * 4)
    for (i in this.indices) {
        val bits = this[i].toRawBits()
        result[i * 4] = (bits and 0xFF).toByte()
        result[i * 4 + 1] = ((bits shr 8) and 0xFF).toByte()
        result[i * 4 + 2] = ((bits shr 16) and 0xFF).toByte()
        result[i * 4 + 3] = ((bits shr 24) and 0xFF).toByte()
    }
    return result
}

/**
 * Convert a ShortArray to a ByteArray in native byte order.
 *
 * Each short is converted to 2 bytes in little-endian format. The resulting byte
 * array has size = shorts.size * 2. Commonly used for 16-bit index buffers.
 *
 * @return A ByteArray containing the binary representation of this short array.
 */
fun ShortArray.toBytes(): ByteArray {
    val result = ByteArray(this.size * 2)
    for (i in this.indices) {
        val v = this[i].toInt()
        result[i * 2] = (v and 0xFF).toByte()
        result[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
    }
    return result
}

/**
 * Convert an IntArray to a ByteArray in native byte order.
 *
 * Each int is converted to 4 bytes in little-endian format. The resulting byte
 * array has size = ints.size * 4. Commonly used for 32-bit index buffers.
 *
 * @return A ByteArray containing the binary representation of this int array.
 */
fun IntArray.toBytes(): ByteArray {
    val result = ByteArray(this.size * 4)
    for (i in this.indices) {
        val v = this[i]
        result[i * 4] = (v and 0xFF).toByte()
        result[i * 4 + 1] = ((v shr 8) and 0xFF).toByte()
        result[i * 4 + 2] = ((v shr 16) and 0xFF).toByte()
        result[i * 4 + 3] = ((v shr 24) and 0xFF).toByte()
    }
    return result
}
