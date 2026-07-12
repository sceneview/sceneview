package io.github.sceneview.core.splat

/**
 * Thrown when a splat file cannot be parsed: bad magic, unsupported variant, or truncated/corrupt
 * data. Parsers translate low-level failures (e.g. out-of-bounds reads on a truncated buffer) into
 * this exception, so callers never have to catch [IndexOutOfBoundsException] or similar.
 */
class SplatParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Raise a [SplatParseException]. A single-throw helper (in the spirit of the stdlib `error()`) so the
 * parsers can reject malformed input from many guard clauses while keeping each function's literal
 * `throw` count within detekt's `ThrowsCount` budget, and so error construction stays in one place.
 */
internal fun splatError(message: String, cause: Throwable? = null): Nothing =
    throw SplatParseException(message, cause)

/**
 * Portable parsers for 3D Gaussian Splatting files, shared across all SceneView renderer backends.
 *
 * Two formats are supported in this P1 layer (see issue #2646):
 * - **PLY** — the INRIA 3DGS reference output (`binary_little_endian`), the universal trainer format.
 * - **SPZ** — Niantic's gzip-compressed interchange format (~10× smaller than PLY), versions 2 and 3.
 *
 * All entry points return a fully-decoded [SplatCloud] with activations already applied; see that
 * class for the exact per-attribute semantics.
 */
object SplatParser {

    /**
     * Parse an INRIA-style 3D Gaussian Splatting **PLY** file.
     *
     * Only `format binary_little_endian 1.0` is supported; the ASCII and big-endian variants are
     * rejected with a [SplatParseException]. Property offsets are read from the header, so property
     * reordering and extra properties (normals, higher-order `f_rest_*` SH bands) are tolerated.
     *
     * @throws SplatParseException on a malformed header, a missing required property, or truncation.
     */
    fun fromPly(bytes: ByteArray): SplatCloud = wrap("PLY") { PlyParser.parse(bytes) }

    /**
     * Parse a Niantic **SPZ** file (gzip-compressed). Supports the widely-deployed gzip formats:
     * version 2 (`first-three` quaternion encoding) and version 3 (`smallest-three`). The version-4
     * NGSP/ZSTD container and the never-released version-1 float16 layout are rejected with a clear
     * [SplatParseException].
     *
     * @throws SplatParseException on a non-SPZ input, an unsupported version, or truncation.
     */
    fun fromSpz(bytes: ByteArray): SplatCloud = wrap("SPZ") { SpzParser.parse(bytes) }

    /**
     * Parse a splat file of unknown type, sniffing the format from its leading bytes:
     * - `"ply\n"` / `"ply\r\n"` → [fromPly]
     * - gzip magic `0x1F 0x8B` → [fromSpz]
     * - NGSP magic `"NGSP"` (uncompressed) → routed to [fromSpz] to surface the "v4 unsupported" error
     *
     * @throws SplatParseException if the format is not recognized, or parsing fails.
     */
    fun parse(bytes: ByteArray): SplatCloud = when {
        looksLikePly(bytes) -> fromPly(bytes)
        looksLikeGzip(bytes) -> fromSpz(bytes)
        looksLikeNgsp(bytes) -> fromSpz(bytes)
        else -> splatError(
            "Unrecognized splat format: not PLY (\"ply\") and not gzip SPZ (magic 0x1F8B)"
        )
    }

    /** A PLY file starts with the ASCII token `ply` followed by a newline (`\n` or `\r\n`). */
    private fun looksLikePly(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0].toInt() == 'p'.code &&
            bytes[1].toInt() == 'l'.code &&
            bytes[2].toInt() == 'y'.code &&
            (bytes[3].toInt() == '\n'.code || bytes[3].toInt() == '\r'.code)

    /** A gzip stream (legacy SPZ container) starts with `0x1F 0x8B`. */
    private fun looksLikeGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B

    /** An uncompressed NGSP (SPZ v4) file starts with the little-endian magic `"NGSP"` (0x5053474E). */
    private fun looksLikeNgsp(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0].toInt() == 'N'.code &&
            bytes[1].toInt() == 'G'.code &&
            bytes[2].toInt() == 'S'.code &&
            bytes[3].toInt() == 'P'.code

    /** Run [block], normalizing any non-[SplatParseException] failure into a [SplatParseException]. */
    private inline fun wrap(kind: String, block: () -> SplatCloud): SplatCloud =
        try {
            block()
        } catch (e: SplatParseException) {
            throw e
        } catch (e: Exception) {
            splatError("$kind parse failed: ${e.message}", e)
        }
}
