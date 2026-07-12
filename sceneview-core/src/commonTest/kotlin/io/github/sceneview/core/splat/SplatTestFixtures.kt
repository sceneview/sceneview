package io.github.sceneview.core.splat

import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Shared helpers for building synthetic splat fixtures entirely in test code — no binary files in
 * git. The SPZ/PLY byte builders are the faithful inverse of the parsers' decode formulas, so a
 * round-trip through them proves the parser decodes the real on-disk layout, not just its own echo.
 */

/** Build a [ByteArray] from unsigned int byte values, for embedding literal fixtures. */
internal fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

private fun writeLe16(out: ByteArray, at: Int, v: Int) {
    out[at] = (v and 0xFF).toByte()
    out[at + 1] = ((v shr 8) and 0xFF).toByte()
}

private fun writeLe32(out: ByteArray, at: Int, v: Int) {
    out[at] = (v and 0xFF).toByte()
    out[at + 1] = ((v shr 8) and 0xFF).toByte()
    out[at + 2] = ((v shr 16) and 0xFF).toByte()
    out[at + 3] = ((v ushr 24) and 0xFF).toByte()
}

/**
 * Wrap [payload] in a valid gzip stream that uses only DEFLATE *stored* blocks, with a correct
 * CRC-32 and ISIZE trailer. This lets tests produce gzip the parser must accept without needing a
 * full DEFLATE compressor. (Real dynamic-Huffman gzip is covered by an embedded fixture.)
 */
internal fun gzipStored(payload: ByteArray): ByteArray {
    val maxChunk = 0xFFFF
    val chunkCount = if (payload.isEmpty()) 1 else (payload.size + maxChunk - 1) / maxChunk
    val out = ByteArray(GZIP_HEADER + chunkCount * STORED_BLOCK_HEADER + payload.size + GZIP_TRAILER)
    var o = 0
    // gzip header: magic, method=deflate, flags=0, mtime=0, XFL=0, OS=0xFF (unknown)
    out[o++] = 0x1F.toByte(); out[o++] = 0x8B.toByte(); out[o++] = 8; out[o++] = 0
    o += 4 // mtime
    out[o++] = 0; out[o++] = 0xFF.toByte()

    if (payload.isEmpty()) {
        out[o++] = 1 // BFINAL=1, BTYPE=00 (stored)
        writeLe16(out, o, 0); o += 2 // LEN = 0
        writeLe16(out, o, 0xFFFF); o += 2 // NLEN = ~0
    } else {
        var p = 0
        while (p < payload.size) {
            val chunk = minOf(maxChunk, payload.size - p)
            val last = p + chunk == payload.size
            out[o++] = if (last) 1 else 0 // BFINAL bit, BTYPE=00
            writeLe16(out, o, chunk); o += 2
            writeLe16(out, o, chunk.inv() and 0xFFFF); o += 2
            payload.copyInto(out, o, p, p + chunk)
            o += chunk
            p += chunk
        }
    }
    writeLe32(out, o, crc32(payload)); o += 4
    writeLe32(out, o, payload.size); o += 4
    return out
}

private const val GZIP_HEADER = 10
private const val GZIP_TRAILER = 8
private const val STORED_BLOCK_HEADER = 5

// ----- SPZ encoders (inverse of SpzParser's decode) -----

/** One splat's target values, in the same activated units [SplatCloud] exposes. */
internal class SplatSample(
    val position: FloatArray, // x, y, z (world)
    val logScale: FloatArray, // per-axis log-scale (SPZ/PLY store logs; linear = exp)
    val quaternionXyzw: FloatArray, // normalized, w >= 0, largest component positive
    val color: FloatArray, // linear rgb 0..1
    val opacity: Float, // 0..1
)

private fun encodePosition(out: ByteArray, at: Int, pos: FloatArray, fractionalBits: Int) {
    val scale = (1 shl fractionalBits).toFloat()
    for (axis in 0 until 3) {
        val fixed = (pos[axis] * scale).roundToInt()
        out[at + axis * 3] = (fixed and 0xFF).toByte()
        out[at + axis * 3 + 1] = ((fixed shr 8) and 0xFF).toByte()
        out[at + axis * 3 + 2] = ((fixed shr 16) and 0xFF).toByte()
    }
}

private fun toUint8(x: Float): Byte = x.roundToInt().coerceIn(0, 255).toByte()

private fun encodeScale(out: ByteArray, at: Int, logScale: FloatArray) {
    for (axis in 0 until 3) out[at + axis] = toUint8((logScale[axis] + 10f) * 16f)
}

private fun encodeColor(out: ByteArray, at: Int, rgb: FloatArray) {
    for (channel in 0 until 3) {
        val dc = (rgb[channel] - 0.5f) / SplatMath.SH_C0
        out[at + channel] = toUint8(dc * (0.15f * 255f) + 0.5f * 255f)
    }
}

private fun encodeQuaternionFirstThree(out: ByteArray, at: Int, q: FloatArray) {
    // q is normalized with w >= 0, so no sign flip is needed (mirrors packQuaternionFirstThree).
    for (i in 0 until 3) out[at + i] = toUint8((q[i] + 1f) * 127.5f)
}

private const val SQRT_1_2 = 0.70710678f
private const val MASK_9_BIT = (1 shl 9) - 1

private fun encodeQuaternionSmallestThree(out: ByteArray, at: Int, q: FloatArray) {
    var largest = 0
    for (i in 1 until 4) if (kotlin.math.abs(q[i]) > kotlin.math.abs(q[largest])) largest = i
    val negate = q[largest] < 0f
    var comp = largest
    for (i in 0 until 4) {
        if (i != largest) {
            val negbit = if ((q[i] < 0f) != negate) 1 else 0
            val mag = (MASK_9_BIT.toFloat() * (kotlin.math.abs(q[i]) / SQRT_1_2) + 0.5f).toInt()
            comp = (comp shl 10) or (negbit shl 9) or mag
        }
    }
    out[at] = (comp and 0xFF).toByte()
    out[at + 1] = ((comp shr 8) and 0xFF).toByte()
    out[at + 2] = ((comp shr 16) and 0xFF).toByte()
    out[at + 3] = ((comp ushr 24) and 0xFF).toByte()
}

/**
 * Assemble a legacy SPZ file (gzip-wrapped) for [samples] at the given [version] (2 or 3) with
 * SH degree 0. The uncompressed layout matches `serializePackedGaussians`.
 */
internal fun buildSpzGzip(samples: List<SplatSample>, version: Int, fractionalBits: Int = 12): ByteArray {
    val n = samples.size
    val rotStride = if (version >= 3) 4 else 3
    val headerSize = 16
    val positions = headerSize
    val alphas = positions + n * 9
    val colors = alphas + n
    val scales = colors + n * 3
    val rotations = scales + n * 3
    val end = rotations + n * rotStride
    val raw = ByteArray(end)

    writeLe32(raw, 0, 0x5053474E) // "NGSP"
    writeLe32(raw, 4, version)
    writeLe32(raw, 8, n)
    raw[12] = 0 // shDegree
    raw[13] = fractionalBits.toByte()
    raw[14] = 0 // flags
    raw[15] = 0 // reserved

    for (i in 0 until n) {
        val s = samples[i]
        encodePosition(raw, positions + i * 9, s.position, fractionalBits)
        raw[alphas + i] = toUint8(s.opacity * 255f)
        encodeColor(raw, colors + i * 3, s.color)
        encodeScale(raw, scales + i * 3, s.logScale)
        if (version >= 3) {
            encodeQuaternionSmallestThree(raw, rotations + i * 4, s.quaternionXyzw)
        } else {
            encodeQuaternionFirstThree(raw, rotations + i * 3, s.quaternionXyzw)
        }
    }
    return gzipStored(raw)
}

// ----- PLY builder -----

/**
 * Build an INRIA-style binary-little-endian PLY from an ordered list of float property names and
 * per-vertex values, so tests can exercise arbitrary property orderings. All properties are float32.
 */
internal fun buildPly(propertyNames: List<String>, vertices: List<FloatArray>): ByteArray {
    val header = buildString {
        append("ply\n")
        append("format binary_little_endian 1.0\n")
        append("element vertex ${vertices.size}\n")
        for (name in propertyNames) append("property float $name\n")
        append("end_header\n")
    }.encodeToByteArray()

    val body = ByteArray(vertices.size * propertyNames.size * 4)
    var o = 0
    for (row in vertices) {
        require(row.size == propertyNames.size)
        for (v in row) {
            writeLe32(body, o, v.toRawBits())
            o += 4
        }
    }
    return header + body
}

/** Expected linear color for an SH DC coefficient, matching [SplatMath.dcToLinearColor]. */
internal fun expectedColor(dc: Float): Float = SplatMath.dcToLinearColor(dc)

/** Convenience: a unit quaternion (x,y,z,w) about +Z by [radians], with w >= 0. */
internal fun quaternionAboutZ(radians: Float): FloatArray {
    val h = radians / 2f
    return floatArrayOf(0f, 0f, kotlin.math.sin(h), kotlin.math.cos(h))
}

/** Natural log helper for readable fixtures. */
internal fun logOf(x: Float): Float = ln(x)

/** Length of an xyzw quaternion. */
internal fun quatLength(q: FloatArray, offset: Int): Float {
    val x = q[offset]
    val y = q[offset + 1]
    val z = q[offset + 2]
    val w = q[offset + 3]
    return sqrt(x * x + y * y + z * z + w * w)
}
