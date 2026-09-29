package io.github.sceneview.loaders

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * A decoded Radiance `.hdr` image: [width] × [height] linear RGB floats, row 0 first, in a
 * direct native-order buffer ready for a Filament `PixelBufferDescriptor(RGB, FLOAT)`.
 */
internal class RgbeImage(val width: Int, val height: Int, val pixels: FloatBuffer)

/**
 * Pure-Kotlin Radiance RGBE (`.hdr`) decoder.
 *
 * `HDRLoader.createTexture` decodes the file and uploads the texture in a single JNI call, so
 * the decode has to run on the Filament (main) thread with it — a 2k equirect is a
 * multi-hundred-millisecond main-thread stall. This decoder touches no Filament object, so it
 * can run on any dispatcher; only the texture upload that follows has to be on the main thread.
 *
 * It reads what [HDRLoader][com.google.android.filament.utils.HDRLoader] reads: flat and
 * new-style run-length encoded scanlines, in the standard `-Y <h> +X <w>` orientation. Anything
 * else returns `null` so the caller can fall back to the native loader.
 */
internal object RgbeDecoder {

    private const val MIN_RLE_WIDTH = 8
    private const val MAX_RLE_WIDTH = 0x7fff
    private const val RLE_RUN_FLAG = 128
    private const val EXPONENT_BIAS = 128 + 8
    private const val CHANNELS = 4

    /** Decodes [buffer] from its current position without moving it; `null` if unsupported. */
    fun decode(buffer: ByteBuffer): RgbeImage? {
        val source = buffer.duplicate()
        val bytes = ByteArray(source.remaining())
        source.get(bytes)
        return decode(bytes)
    }

    /** Decodes [bytes]; `null` when they are not a Radiance file this decoder supports. */
    @Suppress("ReturnCount")
    fun decode(bytes: ByteArray): RgbeImage? {
        val reader = HeaderReader(bytes)
        if (!reader.readLine().orEmpty().startsWith("#?")) return null
        var resolution: String
        do {
            resolution = reader.readLine() ?: return null
        } while (!resolution.startsWith("-Y ") && !resolution.startsWith("+Y "))
        val parts = resolution.trim().split(Regex("\\s+"))
        if (parts.size != 4 || parts[0] != "-Y" || parts[2] != "+X") return null
        val height = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val width = parts[3].toIntOrNull()?.takeIf { it > 0 } ?: return null

        val pixels = ByteBuffer.allocateDirect(width * height * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val scanline = ByteArray(width * CHANNELS)
        val row = FloatArray(width * 3)
        var pos = reader.position
        for (y in 0 until height) {
            if (pos + CHANNELS > bytes.size) return null
            val rle = width in MIN_RLE_WIDTH..MAX_RLE_WIDTH &&
                bytes[pos].toInt() == 2 && bytes[pos + 1].toInt() == 2 &&
                (bytes[pos + 2].toInt() and 0x80) == 0
            if (rle) {
                pos += CHANNELS
                for (channel in 0 until CHANNELS) {
                    pos = readRleChannel(bytes, pos, width, channel, scanline)
                    if (pos < 0) return null
                }
            } else {
                if (pos + width * CHANNELS > bytes.size) return null
                System.arraycopy(bytes, pos, scanline, 0, width * CHANNELS)
                pos += width * CHANNELS
            }
            unpackScanline(scanline, width, row)
            pixels.put(row)
        }
        pixels.rewind()
        return RgbeImage(width, height, pixels)
    }

    /** Reads one RLE [channel] of a scanline into [scanline]; the new offset, or -1 if truncated. */
    private fun readRleChannel(bytes: ByteArray, start: Int, width: Int, channel: Int, scanline: ByteArray): Int {
        var pos = start
        var x = 0
        while (x < width) {
            if (pos >= bytes.size) return -1
            var count = bytes[pos++].toInt() and 0xff
            if (count > RLE_RUN_FLAG) {
                count -= RLE_RUN_FLAG
                if (count > width - x || pos >= bytes.size) return -1
                val value = bytes[pos++]
                repeat(count) { scanline[(x + it) * CHANNELS + channel] = value }
            } else {
                if (count == 0 || count > width - x || pos + count > bytes.size) return -1
                repeat(count) { scanline[(x + it) * CHANNELS + channel] = bytes[pos++] }
            }
            x += count
        }
        return pos
    }

    /** Converts one RGBE [scanline] to linear RGB floats in [row]. */
    private fun unpackScanline(scanline: ByteArray, width: Int, row: FloatArray) {
        for (x in 0 until width) {
            val e = scanline[x * CHANNELS + 3].toInt() and 0xff
            val out = x * 3
            if (e == 0) {
                row[out] = 0f
                row[out + 1] = 0f
                row[out + 2] = 0f
                continue
            }
            val scale = Math.scalb(1f, e - EXPONENT_BIAS)
            row[out] = (scanline[x * CHANNELS].toInt() and 0xff) * scale
            row[out + 1] = (scanline[x * CHANNELS + 1].toInt() and 0xff) * scale
            row[out + 2] = (scanline[x * CHANNELS + 2].toInt() and 0xff) * scale
        }
    }

    private class HeaderReader(private val bytes: ByteArray) {
        var position = 0
            private set

        fun readLine(): String? {
            if (position >= bytes.size) return null
            val start = position
            while (position < bytes.size && bytes[position] != '\n'.code.toByte()) position++
            val line = String(bytes, start, position - start, Charsets.US_ASCII)
            position++
            return line
        }
    }
}
