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
 * Its output is bit-identical to Filament's own `imageio` HDRDecoder, which `HDRLoader` runs,
 * and it follows the same rules: the dimension limits, run-length encoding decided once from the
 * first scanline, and every RLE scanline carrying the `0x02 0x02` magic and the image width.
 * It reads flat and new-style RLE files in the standard `-Y <h> +X <w>` orientation. Anything
 * else — another orientation, a malformed or truncated file, or an image above
 * [MAX_DECODE_PIXELS] — returns `null` so the caller can fall back to the native loader.
 */
internal object RgbeDecoder {

    /** Filament HDRDecoder's per-side limit. */
    const val MAX_DIMENSION = 65_536L

    /** Filament HDRDecoder's total pixel limit. */
    const val MAX_PIXELS = 16_384L * 16_384L

    /**
     * Largest image decoded on the JVM side (8k × 4k, 384 MiB of floats). Above it the float
     * buffer would sit next to the file bytes in the app heap budget, so the caller hands the
     * file to `HDRLoader`, which decodes natively.
     */
    const val MAX_DECODE_PIXELS = 8_192L * 4_096L

    private const val MIN_RLE_WIDTH = 8
    private const val MAX_RLE_WIDTH = 0x7fff
    private const val RLE_MAGIC = 2
    private const val RLE_RUN_FLAG = 128
    private const val EXPONENT_BIAS = 128 + 8
    private const val CHANNELS = 4
    private const val RGB = 3
    private const val MANTISSA_CENTER = 0.5f
    private const val BYTE_MASK = 0xff
    private const val BYTE_BITS = 8

    /** Smallest RLE scanline: 4-byte header, then one 2-byte run per channel. */
    private const val MIN_RLE_SCANLINE_BYTES = CHANNELS + CHANNELS * 2L

    /** Scanlines decoded between two [decode] `checkCancelled` calls. */
    private const val CANCEL_CHECK_LINES = 32

    /** `2^(e - 136)` for every exponent byte — the same float `ldexp` gives Filament. */
    private val EXPONENT_SCALE = FloatArray(BYTE_MASK + 1) { e -> Math.scalb(1f, e - EXPONENT_BIAS) }

    /**
     * Decodes [buffer] from its current position without moving it; `null` if unsupported.
     *
     * @param checkCancelled Called every few scanlines; throw from it to abort the decode.
     */
    fun decode(buffer: ByteBuffer, checkCancelled: () -> Unit = {}): RgbeImage? =
        if (buffer.hasArray()) {
            decode(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining(), checkCancelled)
        } else {
            val bytes = ByteArray(buffer.remaining())
            buffer.duplicate().get(bytes)
            decode(bytes, 0, bytes.size, checkCancelled)
        }

    /** Decodes [bytes]; `null` when they are not a Radiance file this decoder supports. */
    fun decode(bytes: ByteArray, checkCancelled: () -> Unit = {}): RgbeImage? =
        decode(bytes, 0, bytes.size, checkCancelled)

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun decode(bytes: ByteArray, offset: Int, length: Int, checkCancelled: () -> Unit): RgbeImage? {
        val end = offset + length
        val reader = HeaderReader(bytes, offset, end)
        if (!reader.readLine().orEmpty().startsWith("#?")) return null
        var resolution: String
        do {
            resolution = reader.readLine() ?: return null
        } while (!resolution.startsWith("-Y ") && !resolution.startsWith("+Y "))
        val parts = resolution.trim().split(Regex("\\s+"))
        if (parts.size != 4 || parts[0] != "-Y" || parts[2] != "+X") return null
        val height = parts[1].toLongOrNull()?.takeIf { it in 1..MAX_DIMENSION } ?: return null
        val width = parts[3].toLongOrNull()?.takeIf { it in 1..MAX_DIMENSION } ?: return null
        val pixelCount = width * height
        if (pixelCount > MAX_PIXELS || pixelCount > MAX_DECODE_PIXELS) return null
        val w = width.toInt()
        val h = height.toInt()

        var pos = reader.position
        // Filament decides run-length encoding once, from the first scanline.
        val rle = w in MIN_RLE_WIDTH..MAX_RLE_WIDTH && pos + CHANNELS <= end &&
            bytes[pos].toInt() == RLE_MAGIC && bytes[pos + 1].toInt() == RLE_MAGIC &&
            (bytes[pos + 2].toInt() and RLE_RUN_FLAG) == 0
        // Refuse a file too short to hold its scanlines before allocating for them.
        val minScanlineBytes = if (rle) MIN_RLE_SCANLINE_BYTES else width * CHANNELS
        if (end - pos < height * minScanlineBytes) return null

        val pixels = ByteBuffer.allocateDirect((pixelCount * RGB * Float.SIZE_BYTES).toInt())
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val scanline = ByteArray(w * CHANNELS)
        val row = FloatArray(w * RGB)
        for (y in 0 until h) {
            if (y % CANCEL_CHECK_LINES == 0) checkCancelled()
            if (rle) {
                if (!isRleHeader(bytes, pos, end, w)) return null
                pos += CHANNELS
                for (channel in 0 until CHANNELS) {
                    pos = readRleChannel(bytes, pos, end, w, channel, scanline)
                    if (pos < 0) return null
                }
            } else {
                if (pos + w * CHANNELS > end) return null
                System.arraycopy(bytes, pos, scanline, 0, w * CHANNELS)
                pos += w * CHANNELS
            }
            unpackScanline(scanline, w, row)
            pixels.put(row)
        }
        pixels.rewind()
        return RgbeImage(w, h, pixels)
    }

    /** An RLE scanline starts with `0x02 0x02` then the image width, big-endian. */
    private fun isRleHeader(bytes: ByteArray, pos: Int, end: Int, width: Int): Boolean {
        if (pos + CHANNELS > end) return false
        if (bytes[pos].toInt() != RLE_MAGIC || bytes[pos + 1].toInt() != RLE_MAGIC) return false
        val lineWidth = ((bytes[pos + 2].toInt() and BYTE_MASK) shl BYTE_BITS) or
            (bytes[pos + 3].toInt() and BYTE_MASK)
        return lineWidth == width
    }

    /** Reads one RLE [channel] of a scanline into [scanline]; the new offset, or -1 if malformed. */
    @Suppress("LongParameterList")
    private fun readRleChannel(
        bytes: ByteArray,
        start: Int,
        end: Int,
        width: Int,
        channel: Int,
        scanline: ByteArray
    ): Int {
        var pos = start
        var x = 0
        while (x < width) {
            if (pos >= end) return -1
            var count = bytes[pos++].toInt() and BYTE_MASK
            if (count > RLE_RUN_FLAG) {
                count -= RLE_RUN_FLAG
                if (count > width - x || pos >= end) return -1
                val value = bytes[pos++]
                repeat(count) { scanline[(x + it) * CHANNELS + channel] = value }
            } else {
                if (count == 0 || count > width - x || pos + count > end) return -1
                repeat(count) { scanline[(x + it) * CHANNELS + channel] = bytes[pos++] }
            }
            x += count
        }
        return pos
    }

    /** Converts one RGBE [scanline] to linear RGB floats in [row]. */
    private fun unpackScanline(scanline: ByteArray, width: Int, row: FloatArray) {
        for (x in 0 until width) {
            val e = scanline[x * CHANNELS + 3].toInt() and BYTE_MASK
            val out = x * RGB
            if (e == 0) {
                row[out] = 0f
                row[out + 1] = 0f
                row[out + 2] = 0f
                continue
            }
            // Filament's imageio HDRDecoder: (mantissa + 0.5) * 2^(e - 136), in float.
            val scale = EXPONENT_SCALE[e]
            row[out] = ((scanline[x * CHANNELS].toInt() and BYTE_MASK) + MANTISSA_CENTER) * scale
            row[out + 1] = ((scanline[x * CHANNELS + 1].toInt() and BYTE_MASK) + MANTISSA_CENTER) * scale
            row[out + 2] = ((scanline[x * CHANNELS + 2].toInt() and BYTE_MASK) + MANTISSA_CENTER) * scale
        }
    }

    private class HeaderReader(private val bytes: ByteArray, start: Int, private val end: Int) {
        var position = start
            private set

        fun readLine(): String? {
            if (position >= end) return null
            val start = position
            while (position < end && bytes[position] != '\n'.code.toByte()) position++
            val line = String(bytes, start, position - start, Charsets.US_ASCII)
            position++
            return line
        }
    }
}
