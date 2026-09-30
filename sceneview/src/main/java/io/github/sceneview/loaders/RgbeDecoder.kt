package io.github.sceneview.loaders

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Arrays

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
     * Largest image decoded on the JVM side (4k × 2k, 96 MiB of RGB floats). Above it the float
     * buffer would sit next to the file bytes in the app heap budget, so the caller hands the
     * file to `HDRLoader`, which decodes natively.
     */
    const val MAX_DECODE_PIXELS = 4_096L * 2_048L

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

    /** Scanlines buffered in a `FloatArray` between two bulk copies into the direct buffer. */
    private const val ROWS_PER_PUT = 16

    private val EMPTY_BYTES = ByteArray(0)

    /**
     * Every RGBE channel value, indexed by `exponent shl 8 or mantissa`: Filament's
     * `(mantissa + 0.5) * 2^(e - 136)` in float, computed with the same operations, and 0 for
     * `e == 0`. One lookup per channel keeps the per-pixel loop short on an interpreted
     * (debuggable) runtime as well as a compiled one; 256 KiB, built on first use.
     */
    private val CHANNEL_VALUES = FloatArray((BYTE_MASK + 1) shl BYTE_BITS).also { table ->
        for (e in 1..BYTE_MASK) {
            val scale = Math.scalb(1f, e - EXPONENT_BIAS)
            val row = e shl BYTE_BITS
            for (m in 0..BYTE_MASK) table[row or m] = (m + MANTISSA_CENTER) * scale
        }
    }

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
        // Planar RLE scanline: all R bytes, then G, then B, then E — so every run is one bulk
        // `fill` or `arraycopy` instead of a per-byte loop.
        val scanline = if (rle) ByteArray(w * CHANNELS) else EMPTY_BYTES
        val rows = FloatArray(w * RGB * minOf(ROWS_PER_PUT, h))
        var rowsInBatch = 0
        for (y in 0 until h) {
            if (y % CANCEL_CHECK_LINES == 0) checkCancelled()
            val out = rowsInBatch * w * RGB
            if (rle) {
                if (!isRleHeader(bytes, pos, end, w)) return null
                pos += CHANNELS
                for (channel in 0 until CHANNELS) {
                    pos = readRleChannel(bytes, pos, end, w, channel * w, scanline)
                    if (pos < 0) return null
                }
                unpackPlanar(scanline, w, rows, out)
            } else {
                if (pos + w * CHANNELS > end) return null
                unpackInterleaved(bytes, pos, w, rows, out)
                pos += w * CHANNELS
            }
            if (++rowsInBatch == ROWS_PER_PUT || y == h - 1) {
                pixels.put(rows, 0, rowsInBatch * w * RGB)
                rowsInBatch = 0
            }
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

    /**
     * Reads one RLE channel of a scanline into `scanline[base until base + width]`; the new
     * offset, or -1 if malformed.
     */
    @Suppress("LongParameterList")
    private fun readRleChannel(
        bytes: ByteArray,
        start: Int,
        end: Int,
        width: Int,
        base: Int,
        scanline: ByteArray
    ): Int {
        var pos = start
        var out = base
        val limit = base + width
        while (out < limit) {
            if (pos >= end) return -1
            var count = bytes[pos++].toInt() and BYTE_MASK
            if (count > RLE_RUN_FLAG) {
                count -= RLE_RUN_FLAG
                if (count > limit - out || pos >= end) return -1
                Arrays.fill(scanline, out, out + count, bytes[pos++])
            } else {
                if (count == 0 || count > limit - out || pos + count > end) return -1
                System.arraycopy(bytes, pos, scanline, out, count)
                pos += count
            }
            out += count
        }
        return pos
    }

    /**
     * Converts one planar RGBE [scanline] (R plane, G, B, E) to linear RGB floats in [rows]
     * from [out], through [CHANNEL_VALUES].
     */
    private fun unpackPlanar(scanline: ByteArray, width: Int, rows: FloatArray, out: Int) {
        val values = CHANNEL_VALUES
        var r = 0
        var g = width
        var b = 2 * width
        var e = 3 * width
        var o = out
        val rEnd = width
        while (r < rEnd) {
            val row = (scanline[e++].toInt() and BYTE_MASK) shl BYTE_BITS
            rows[o++] = values[row or (scanline[r++].toInt() and BYTE_MASK)]
            rows[o++] = values[row or (scanline[g++].toInt() and BYTE_MASK)]
            rows[o++] = values[row or (scanline[b++].toInt() and BYTE_MASK)]
        }
    }

    /** [unpackPlanar] for a flat file: interleaved RGBE pixels straight from [bytes] at [start]. */
    private fun unpackInterleaved(bytes: ByteArray, start: Int, width: Int, rows: FloatArray, out: Int) {
        val values = CHANNEL_VALUES
        var i = start
        var o = out
        val iEnd = start + width * CHANNELS
        while (i < iEnd) {
            val row = (bytes[i + 3].toInt() and BYTE_MASK) shl BYTE_BITS
            rows[o++] = values[row or (bytes[i].toInt() and BYTE_MASK)]
            rows[o++] = values[row or (bytes[i + 1].toInt() and BYTE_MASK)]
            rows[o++] = values[row or (bytes[i + 2].toInt() and BYTE_MASK)]
            i += CHANNELS
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
