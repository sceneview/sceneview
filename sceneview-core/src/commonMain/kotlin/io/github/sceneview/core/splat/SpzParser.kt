package io.github.sceneview.core.splat

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Parser for Niantic **SPZ** files, the gzip-compressed 3D Gaussian Splatting interchange format
 * (https://github.com/nianticlabs/spz). Decode formulas follow the reference `load-spz.cc`; the v3 smallest-three
 * bit layout is validated by encode/decode round-trip only — pending a real-world
 * exporter fixture, treat v3 as an anticipated layout (v2 is the established format).
 *
 * Supported: the legacy gzip container, version **2** (`first-three` quaternion, 3 bytes) and
 * version **3** (`smallest-three` quaternion, 4 bytes) — the formats exported by Scaniverse, Marble,
 * Polycam, KIRI et al. Not supported here: the never-released version 1 (float16 positions) and the
 * version-4 NGSP/ZSTD container (routed here only to raise a clear error).
 *
 * On-disk layout of a legacy SPZ (after gzip decompression), all little-endian, structure-of-arrays:
 * ```
 * header (16 bytes): magic u32 | version u32 | numPoints u32 | shDegree u8 | fractionalBits u8 | flags u8 | reserved u8
 * positions : numPoints * 9 bytes  (3 axes * 24-bit fixed point)
 * alphas    : numPoints * 1 byte
 * colors    : numPoints * 3 bytes
 * scales    : numPoints * 3 bytes  (uint8 log-encoded)
 * rotations : numPoints * (3 | 4) bytes
 * sh        : numPoints * shDim * 3 bytes  (ignored in P1)
 * ```
 */
internal object SpzParser {

    fun parse(bytes: ByteArray): SplatCloud {
        if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B) {
            return parseLegacy(Inflate.gunzip(bytes))
        }
        if (bytes.size >= 4 && readLe32(bytes, 0) == NGSP_MAGIC) {
            splatError(
                "SPZ version 4 (NGSP/ZSTD container) is not supported; this P1 parser handles gzip SPZ v2/v3"
            )
        }
        splatError("not a valid SPZ file (expected gzip magic 0x1F8B)")
    }

    private fun parseLegacy(d: ByteArray): SplatCloud {
        if (d.size < HEADER_SIZE) splatError("SPZ: header truncated (${d.size} bytes)")
        if (readLe32(d, 0) != NGSP_MAGIC) splatError("SPZ: bad magic in decompressed stream")

        val version = readLe32(d, 4)
        val numPoints = readLe32(d, 8)
        val shDegree = d[12].toInt() and 0xFF
        val fractionalBits = d[13].toInt() and 0xFF

        if (numPoints <= 0) splatError("SPZ: invalid point count $numPoints")
        when {
            version == 1 -> splatError("SPZ: version 1 (float16) is not supported")
            version < 2 || version > 3 ->
                splatError("SPZ: unsupported version $version (supported: 2, 3)")
        }
        if (shDegree > MAX_SH_DEGREE) splatError("SPZ: unsupported SH degree $shDegree")

        val smallestThree = version >= 3
        val shDim = dimForDegree(shDegree)
        val rotStride = if (smallestThree) 4 else 3

        // Structure-of-arrays offsets, in serialization order. Long arithmetic:
        // a lying header (numPoints ~2^31/9) must trip THIS check, not wrap Int
        // and sneak past it (same defense PLY's `needed` uses).
        var offset = HEADER_SIZE.toLong()
        val positionsOffset = offset.toInt(); offset += numPoints.toLong() * 9
        val alphasOffset = offset.toInt(); offset += numPoints.toLong()
        val colorsOffset = offset.toInt(); offset += numPoints.toLong() * 3
        val scalesOffset = offset.toInt(); offset += numPoints.toLong() * 3
        val rotationsOffset = offset.toInt(); offset += numPoints.toLong() * rotStride
        offset += numPoints.toLong() * shDim * 3 // sh (ignored, but must be present)
        if (offset > d.size) {
            splatError("SPZ: data truncated (need $offset bytes, have ${d.size})")
        }

        val positions = FloatArray(numPoints * 3)
        val scales = FloatArray(numPoints * 3)
        val rotations = FloatArray(numPoints * 4)
        val colors = FloatArray(numPoints * 3)
        val opacities = FloatArray(numPoints)

        val positionScale = 1f / (1 shl fractionalBits)
        for (i in 0 until numPoints) {
            decodePosition(d, positionsOffset + i * 9, positions, i * 3, positionScale)
            decodeScale(d, scalesOffset + i * 3, scales, i * 3)
            decodeColor(d, colorsOffset + i * 3, colors, i * 3)
            opacities[i] = (d[alphasOffset + i].toInt() and 0xFF) / 255f
            if (smallestThree) {
                decodeQuaternionSmallestThree(d, rotationsOffset + i * 4, rotations, i * 4)
            } else {
                decodeQuaternionFirstThree(d, rotationsOffset + i * 3, rotations, i * 4)
            }
            SplatMath.normalizeQuaternion(rotations, i * 4)
        }
        return SplatCloud(numPoints, positions, scales, rotations, colors, opacities)
    }

    /** 24-bit little-endian signed fixed point → float, scaled by `1 / 2^fractionalBits`. */
    private fun decodePosition(d: ByteArray, src: Int, out: FloatArray, dst: Int, scale: Float) {
        for (axis in 0 until 3) {
            val b = src + axis * 3
            var fixed = (d[b].toInt() and 0xFF) or
                ((d[b + 1].toInt() and 0xFF) shl 8) or
                ((d[b + 2].toInt() and 0xFF) shl 16)
            if (fixed and 0x800000 != 0) fixed = fixed or 0xFF000000.toInt() // sign-extend bit 23
            out[dst + axis] = fixed.toFloat() * scale
        }
    }

    /** uint8 log-encoded scale → linear scale: `exp(byte / 16 - 10)`. */
    private fun decodeScale(d: ByteArray, src: Int, out: FloatArray, dst: Int) {
        for (axis in 0 until 3) {
            val logScale = (d[src + axis].toInt() and 0xFF) / 16f - 10f
            out[dst + axis] = exp(logScale)
        }
    }

    /** uint8 → SH DC coefficient `(b/255 - 0.5) / colorScale`, then to linear RGB clamped `[0,1]`. */
    private fun decodeColor(d: ByteArray, src: Int, out: FloatArray, dst: Int) {
        for (channel in 0 until 3) {
            val dc = ((d[src + channel].toInt() and 0xFF) / 255f - 0.5f) / COLOR_SCALE
            out[dst + channel] = SplatMath.dcToLinearColor(dc)
        }
    }

    /** Version 2: store `x, y, z` in 8-bit fixed point; reconstruct `w >= 0` from unit length. */
    private fun decodeQuaternionFirstThree(d: ByteArray, src: Int, out: FloatArray, dst: Int) {
        val x = (d[src].toInt() and 0xFF) / 127.5f - 1f
        val y = (d[src + 1].toInt() and 0xFF) / 127.5f - 1f
        val z = (d[src + 2].toInt() and 0xFF) / 127.5f - 1f
        out[dst] = x
        out[dst + 1] = y
        out[dst + 2] = z
        out[dst + 3] = sqrt(max(0f, 1f - (x * x + y * y + z * z)))
    }

    /** Version 3+: 2-bit largest-component index + three 10-bit signed magnitudes scaled by 1/sqrt(2). */
    private fun decodeQuaternionSmallestThree(d: ByteArray, src: Int, out: FloatArray, dst: Int) {
        var comp = (d[src].toInt() and 0xFF) or
            ((d[src + 1].toInt() and 0xFF) shl 8) or
            ((d[src + 2].toInt() and 0xFF) shl 16) or
            ((d[src + 3].toInt() and 0xFF) shl 24)
        val largest = (comp ushr 30) and 0x3
        var sumSquares = 0f
        for (i in 3 downTo 0) {
            if (i != largest) {
                val magnitude = comp and MASK_9_BIT
                val negative = (comp ushr 9) and 0x1
                comp = comp ushr 10
                var value = SQRT_1_2 * magnitude.toFloat() / MASK_9_BIT.toFloat()
                if (negative == 1) value = -value
                out[dst + i] = value
                sumSquares += value * value
            }
        }
        out[dst + largest] = sqrt(max(0f, 1f - sumSquares))
    }

    /** Number of SH coefficients (per channel) for a given degree, per the SPZ reference. */
    private fun dimForDegree(degree: Int): Int = when (degree) {
        0 -> 0
        1 -> 3
        2 -> 8
        3 -> 15
        4 -> 24
        else -> splatError("SPZ: unsupported SH degree $degree")
    }

    private const val NGSP_MAGIC = 0x5053474E // "NGSP" little-endian
    private const val HEADER_SIZE = 16
    private const val MAX_SH_DEGREE = 4
    private const val COLOR_SCALE = 0.15f
    private const val SQRT_1_2 = 0.70710678f
    private const val MASK_9_BIT = (1 shl 9) - 1
}
