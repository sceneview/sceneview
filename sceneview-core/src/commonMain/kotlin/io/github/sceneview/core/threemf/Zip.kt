package io.github.sceneview.core.threemf

import io.github.sceneview.core.splat.Inflate
import io.github.sceneview.core.splat.readLe16
import io.github.sceneview.core.splat.readLe32

/**
 * Minimal in-memory ZIP (OPC package) reader — just enough to pull one named entry out of a `.3mf`
 * container on every Kotlin Multiplatform target, with no `java.util.zip` and no `expect`/`actual`.
 *
 * A 3MF file is an OPC package: a plain ZIP whose payload is `3D/3dmodel.model`. Entries are either
 * stored (method 0) or raw DEFLATE (method 8), both handled here — DEFLATE via the pure-Kotlin
 * [Inflate] decoder the SPZ splat parser already ships.
 *
 * Deliberately not supported, each rejected with a clear message rather than a wrong result:
 * ZIP64 (a 3MF that large is not a phone-viewable print), encryption, and any other compression
 * method. Entry lookup goes through the central directory, so a stream written with data
 * descriptors (sizes zeroed in the local header) still reads correctly.
 */
internal object Zip {

    /**
     * Read the entry named [name] (exact, case-sensitive path inside the archive), or `null` if the
     * archive has no such entry.
     *
     * @throws ThreeMfParseException if [bytes] is not a readable ZIP, or the entry cannot be
     * decompressed.
     */
    fun readEntry(bytes: ByteArray, name: String): ByteArray? {
        val entry = entries(bytes).firstOrNull { it.name == name } ?: return null
        return extract(bytes, entry)
    }

    /** The entry names in the archive's central directory, in stored order. */
    fun entryNames(bytes: ByteArray): List<String> = entries(bytes).map { it.name }

    /**
     * Read the first entry whose name matches [predicate]. Used to find `3D/3dmodel.model` when a
     * writer used a different case or a non-standard part name (the OPC relationship part points at
     * it, but every real-world 3MF also uses the conventional path).
     */
    fun readFirstEntry(bytes: ByteArray, predicate: (String) -> Boolean): ByteArray? {
        val entry = entries(bytes).firstOrNull { predicate(it.name) } ?: return null
        return extract(bytes, entry)
    }

    private class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val localHeaderOffset: Int
    )

    private fun entries(bytes: ByteArray): List<Entry> {
        val eocd = findEndOfCentralDirectory(bytes)
        val count = readLe16(bytes, eocd + EOCD_ENTRY_COUNT)
        val directoryOffset = readLe32(bytes, eocd + EOCD_DIRECTORY_OFFSET)
        if (directoryOffset < 0 || directoryOffset >= bytes.size) {
            threeMfError("ZIP central directory offset out of range ($directoryOffset)")
        }
        val entries = ArrayList<Entry>(count)
        var at = directoryOffset
        repeat(count) {
            if (at + CENTRAL_HEADER_SIZE > bytes.size ||
                readLe32(bytes, at) != CENTRAL_HEADER_SIGNATURE
            ) {
                threeMfError("ZIP central directory truncated at entry ${entries.size}")
            }
            entries += readCentralEntry(bytes, at)
            at += CENTRAL_HEADER_SIZE +
                readLe16(bytes, at + CENTRAL_NAME_LENGTH) +
                readLe16(bytes, at + CENTRAL_EXTRA_LENGTH) +
                readLe16(bytes, at + CENTRAL_COMMENT_LENGTH)
        }
        return entries
    }

    private fun readCentralEntry(bytes: ByteArray, at: Int): Entry {
        val nameLength = readLe16(bytes, at + CENTRAL_NAME_LENGTH)
        val nameStart = at + CENTRAL_HEADER_SIZE
        if (nameStart + nameLength > bytes.size) threeMfError("ZIP entry name truncated")
        return Entry(
            name = bytes.decodeToString(nameStart, nameStart + nameLength),
            method = readLe16(bytes, at + CENTRAL_METHOD),
            compressedSize = readLe32(bytes, at + CENTRAL_COMPRESSED_SIZE),
            uncompressedSize = readLe32(bytes, at + CENTRAL_UNCOMPRESSED_SIZE),
            localHeaderOffset = readLe32(bytes, at + CENTRAL_LOCAL_OFFSET)
        )
    }

    private fun extract(bytes: ByteArray, entry: Entry): ByteArray {
        if (entry.compressedSize == ZIP64_MARKER || entry.uncompressedSize == ZIP64_MARKER) {
            threeMfError("ZIP64 archives are not supported (entry \"${entry.name}\")")
        }
        val local = entry.localHeaderOffset
        if (local < 0 || local + LOCAL_HEADER_SIZE > bytes.size ||
            readLe32(bytes, local) != LOCAL_HEADER_SIGNATURE
        ) {
            threeMfError("ZIP local header missing for entry \"${entry.name}\"")
        }
        val dataStart = local + LOCAL_HEADER_SIZE +
            readLe16(bytes, local + LOCAL_NAME_LENGTH) +
            readLe16(bytes, local + LOCAL_EXTRA_LENGTH)
        if (dataStart < 0 || dataStart + entry.compressedSize > bytes.size) {
            threeMfError("ZIP entry \"${entry.name}\" data truncated")
        }
        return when (entry.method) {
            METHOD_STORED -> bytes.copyOfRange(dataStart, dataStart + entry.uncompressedSize)
            METHOD_DEFLATE -> inflateEntry(bytes, dataStart, entry)
            else -> threeMfError(
                "ZIP entry \"${entry.name}\" uses unsupported compression method ${entry.method}"
            )
        }
    }

    private fun inflateEntry(bytes: ByteArray, dataStart: Int, entry: Entry): ByteArray {
        val inflated = runCatching { Inflate.raw(bytes, dataStart, entry.uncompressedSize) }
            .getOrElse { threeMfError("ZIP entry \"${entry.name}\" is not valid DEFLATE data", it) }
        // The central directory's uncompressed size is authoritative: a stream that inflates to a
        // different length is corrupt, and silently accepting it would hand the XML parser a
        // truncated document whose error would point at the wrong layer.
        if (entry.uncompressedSize >= 0 && inflated.size != entry.uncompressedSize) {
            threeMfError(
                "ZIP entry \"${entry.name}\" inflated to ${inflated.size} bytes, " +
                    "expected ${entry.uncompressedSize}"
            )
        }
        return inflated
    }

    /**
     * Scan backwards for the End Of Central Directory record. It is the last thing in the file
     * except for an optional trailing comment (≤ 64 KiB), so the scan is bounded.
     */
    private fun findEndOfCentralDirectory(bytes: ByteArray): Int {
        if (bytes.size < EOCD_MIN_SIZE) threeMfError("Not a ZIP archive (${bytes.size} bytes)")
        val lowest = maxOf(0, bytes.size - EOCD_MIN_SIZE - MAX_COMMENT_SIZE)
        for (at in bytes.size - EOCD_MIN_SIZE downTo lowest) {
            if (readLe32(bytes, at) == EOCD_SIGNATURE) return at
        }
        threeMfError("Not a ZIP archive (no end-of-central-directory record)")
    }

    /** `true` when [bytes] starts with the local-file-header magic `PK`. */
    fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && readLe32(bytes, 0) == LOCAL_HEADER_SIGNATURE

    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATE = 8
    private const val ZIP64_MARKER = -1 // 0xFFFFFFFF as a signed Int

    private const val EOCD_SIGNATURE = 0x06054B50
    private const val EOCD_MIN_SIZE = 22
    private const val EOCD_ENTRY_COUNT = 10
    private const val EOCD_DIRECTORY_OFFSET = 16
    private const val MAX_COMMENT_SIZE = 0xFFFF

    private const val CENTRAL_HEADER_SIGNATURE = 0x02014B50
    private const val CENTRAL_HEADER_SIZE = 46
    private const val CENTRAL_METHOD = 10
    private const val CENTRAL_COMPRESSED_SIZE = 20
    private const val CENTRAL_UNCOMPRESSED_SIZE = 24
    private const val CENTRAL_NAME_LENGTH = 28
    private const val CENTRAL_EXTRA_LENGTH = 30
    private const val CENTRAL_COMMENT_LENGTH = 32
    private const val CENTRAL_LOCAL_OFFSET = 42

    private const val LOCAL_HEADER_SIGNATURE = 0x04034B50
    private const val LOCAL_HEADER_SIZE = 30
    private const val LOCAL_NAME_LENGTH = 26
    private const val LOCAL_EXTRA_LENGTH = 28
}
