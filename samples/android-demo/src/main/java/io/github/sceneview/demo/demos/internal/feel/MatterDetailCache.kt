package io.github.sceneview.demo.demos.internal.feel

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Background-only, best-effort cache. No Android or Filament dependencies.
 * RG8 is losslessly deflated with a size/CRC header: even same-length corruption is a miss.
 * Each file (including its header) is capped at 1 MiB, five families at 5 MiB total.
 * An incompressible payload is simply not cached. Writes replace through a sibling temp file;
 * stale versions and abandoned temp files are removed under the same process-wide IO lock.
 */
internal class MatterDetailCache(cacheDir: File) {
    private val directory = File(cacheDir, "matter")

    private fun file(kind: FeelMaterial) = File(directory, "${kind.name}-${MatterDetailAtlas.CACHE_VERSION}.bin")

    fun read(kind: FeelMaterial): ByteArray? = synchronized(lock) {
        require(kind != FeelMaterial.Gel)
        try {
            prune()
            val file = file(kind)
            val length = file.length()
            if (length !in (HEADER_BYTES + 1L)..MAX_FILE_BYTES.toLong()) return@synchronized null
            val encoded = ByteArray(length.toInt())
            DataInputStream(file.inputStream().buffered()).use {
                it.readFully(encoded)
                if (it.read() != -1) return@synchronized null
            }
            DataInputStream(encoded.inputStream()).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != PIXEL_BYTES) return@synchronized null
                val checksum = input.readLong()
                val pixels = ByteArray(PIXEL_BYTES)
                val inflater = Inflater()
                try {
                    inflater.setInput(encoded, HEADER_BYTES, encoded.size - HEADER_BYTES)
                    var count = 0
                    while (count < pixels.size) {
                        val read = inflater.inflate(pixels, count, pixels.size - count)
                        if (read == 0) return@synchronized null
                        count += read
                    }
                    // Consume a possible zlib trailer without permitting unbounded output.
                    if (inflater.inflate(ByteArray(1)) != 0 || !inflater.finished() || inflater.remaining != 0) {
                        return@synchronized null
                    }
                } finally {
                    inflater.end()
                }
                if (CRC32().apply { update(pixels) }.value == checksum) pixels else null
            }
        } catch (_: IOException) { null } catch (_: DataFormatException) { null } catch (_: SecurityException) { null }
    }

    fun write(kind: FeelMaterial, pixels: ByteArray): Boolean = synchronized(lock) {
        require(kind != FeelMaterial.Gel && pixels.size == PIXEL_BYTES)
        val temporary = File(directory, "${file(kind).name}.tmp")
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return@synchronized false
            prune()
            val encoded = ByteArrayOutputStream()
            DataOutputStream(encoded).apply {
                writeInt(MAGIC)
                writeInt(PIXEL_BYTES)
                writeLong(CRC32().apply { update(pixels) }.value)
                flush()
            }
            DeflaterOutputStream(encoded).use { it.write(pixels) }
            if (encoded.size() > MAX_FILE_BYTES) return@synchronized false
            // Free this family's slot before writing: temp + final files also stay below 5 MiB.
            val target = file(kind)
            if (target.exists() && !target.delete()) return@synchronized false
            temporary.outputStream().use { encoded.writeTo(it) }
            temporary.renameTo(target)
        } catch (_: IOException) { false } catch (_: SecurityException) { false }
        finally { try { temporary.delete() } catch (_: SecurityException) { } }
    }

    private fun prune() {
        val allowed = FeelMaterial.entries.filter { it != FeelMaterial.Gel }.map { file(it).name }.toSet()
        directory.listFiles()?.forEach {
            if ((it.name !in allowed || it.length() > MAX_FILE_BYTES) && !it.delete()) {
                throw IOException("Cannot prune Matter cache")
            }
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 1024 * 1024
        private const val PIXEL_BYTES = MatterDetailAtlas.WIDTH * MatterDetailAtlas.HEIGHT * 2
        private const val HEADER_BYTES = 16
        private const val MAGIC = 0x4d415431 // MAT1: size, CRC32 (long), deflated RG8.
        private val lock = Any()
    }
}
