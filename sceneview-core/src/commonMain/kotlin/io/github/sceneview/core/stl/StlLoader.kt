package io.github.sceneview.core.stl

import io.github.sceneview.core.threemf.ThreeMfGlb
import io.github.sceneview.core.threemf.ThreeMfUnit

/**
 * Reads binary and ASCII STL in pure Kotlin on Android, Apple and web, and emits an in-memory
 * GLB through the same writer as 3MF. Android detects STL automatically:
 * `rememberModelInstance(modelLoader, "part.stl")`.
 *
 * STL has **no units**: [parse] and [toGlb] assume millimetres, the printing convention. Override
 * with `StlLoader.toGlb(bytes, unit = ThreeMfUnit.INCH)` for inch-authored geometry. GLB positions
 * are baked in metres and axes are preserved because STL specifies no up axis.
 *
 * Positions are welded; valid facet normals are preserved and invalid ones are smoothed across
 * coincident vertices. The shared writer has an indexed path for STL, splitting vertices only
 * at normal seams, while 3MF retains its intentionally flat shading. Binary colour/attribute
 * extensions are ignored; STL receives the writer's neutral, double-sided material.
 */
object StlLoader {
    /** Default input limit (32 MiB), checked before decoding or allocating geometry. */
    const val DEFAULT_MAX_BYTES: Int = 32 * 1024 * 1024

    /** Default facet limit (250,000), bounding welded geometry, normals and GLB output allocations. */
    const val DEFAULT_MAX_TRIANGLES: Int = 250_000

    /**
     * Sniff bytes without copying or parsing geometry; never throws. Binary length must equal
     * `84 + 50 * unsignedTriangleCount`; that test wins even for a header beginning with `solid`.
     * Otherwise ASCII needs a leading `solid` token and a later `facet` token. This is detection,
     * not validation: use `StlLoader.parse(bytes)` to reject malformed or truncated data.
     */
    fun isStl(bytes: ByteArray): Boolean = isBinaryStl(bytes) || looksLikeAsciiStl(bytes)

    /**
     * Parse STL to welded positions in [unit] and per-corner normals; see [StlModel].
     * `val mesh = StlLoader.parse(bytes, unit = ThreeMfUnit.MILLIMETER)`.
     *
     * [maxBytes] caps the input; [maxTriangles] caps decoded facets for both encodings. Increase
     * these only when the caller has memory for the input, working geometry and output together.
     * @throws StlParseException for invalid data, non-positive limits or exceeded limits.
     */
    fun parse(
        bytes: ByteArray,
        unit: ThreeMfUnit = ThreeMfUnit.MILLIMETER,
        maxTriangles: Int = DEFAULT_MAX_TRIANGLES,
        maxBytes: Int = DEFAULT_MAX_BYTES
    ): StlModel {
        if (maxBytes <= 0 || maxTriangles <= 0) stlError("STL limits must be positive")
        if (bytes.size > maxBytes) stlError("STL input exceeds $maxBytes bytes")
        val builder = StlMeshBuilder(unit, minOf(maxTriangles, Int.MAX_VALUE / 128))
        if (isBinaryStl(bytes) || !looksLikeAsciiStl(bytes)) {
            readBinaryStl(bytes, builder)
        } else {
            readAsciiStl(bytes, builder)
        }
        return builder.build()
    }

    /**
     * Convert STL to self-contained glTF 2.0, using [parse]'s units, normals and allocation caps.
     * `val glb = StlLoader.toGlb(bytes)` converts millimetres to metres (70 mm becomes 0.07 m).
     * @throws StlParseException if the input cannot be read within the supplied limits.
     */
    fun toGlb(
        bytes: ByteArray,
        unit: ThreeMfUnit = ThreeMfUnit.MILLIMETER,
        maxTriangles: Int = DEFAULT_MAX_TRIANGLES,
        maxBytes: Int = DEFAULT_MAX_BYTES
    ): ByteArray {
        val model = parse(bytes, unit, maxTriangles, maxBytes)
        return ThreeMfGlb.encodeIndexed(model.positions, model.indices, model.normals, unit.meters)
    }
}

internal fun stlUInt32(bytes: ByteArray, at: Int): Long {
    var value = 0L
    repeat(4) { value = value or ((bytes[at + it].toLong() and 0xff) shl (it * 8)) }
    return value
}

private fun isBinaryStl(bytes: ByteArray): Boolean = bytes.size >= 84 &&
    (bytes.size - 84) % 50 == 0 && stlUInt32(bytes, 80) == (bytes.size - 84).toLong() / 50

internal fun stlWhitespace(value: Int): Boolean = value == 32 || value in 9..13

private fun looksLikeAsciiStl(bytes: ByteArray): Boolean {
    var at = 0
    while (at < bytes.size && stlWhitespace(bytes[at].toInt())) at++
    if (!bytes.tokenAt(at, "solid")) return false
    at += 5
    while (at < bytes.size) {
        val value = bytes[at].toInt()
        if (!stlWhitespace(value) && value !in 32..126) return false
        if (stlWhitespace(bytes[at - 1].toInt()) && bytes.tokenAt(at, "facet")) return true
        at++
    }
    return false
}

private fun ByteArray.tokenAt(at: Int, token: String): Boolean =
    at + token.length <= size && token.indices.all { this[at + it].toInt() == token[it].code } &&
        (at + token.length == size || stlWhitespace(this[at + token.length].toInt()))

private fun readBinaryStl(bytes: ByteArray, builder: StlMeshBuilder) {
    if (bytes.size < 84) stlError("Truncated STL binary header: expected at least 84 bytes")
    val count = stlUInt32(bytes, 80)
    builder.checkTriangleCount(count)
    val expected = 84L + 50L * count
    if (bytes.size.toLong() != expected) {
        stlError("STL binary size mismatch: expected $expected bytes, got ${bytes.size}")
    }
    val facet = FloatArray(12)
    repeat(count.toInt()) { triangle ->
        val at = 84 + triangle * 50
        for (i in facet.indices) facet[i] = Float.fromBits(stlUInt32(bytes, at + i * 4).toInt())
        builder.add(facet)
    }
}

private fun readAsciiStl(bytes: ByteArray, builder: StlMeshBuilder) {
    val reader = StlTokens(bytes)
    val facet = FloatArray(12)
    do {
        reader.expect("solid")
        reader.skipLine() // Solid names are arbitrary text, not grammar tokens.
        while (reader.peek() == "facet") {
            builder.checkTriangleCount(builder.triangleCount.toLong() + 1)
            reader.expect("facet")
            facet.fill(0f)
            if (reader.peek() == "normal") {
                reader.expect("normal")
                repeat(3) { facet[it] = reader.number() }
            }
            reader.expect("outer")
            reader.expect("loop")
            repeat(3) { corner ->
                reader.expect("vertex")
                repeat(3) { axis -> facet[3 + corner * 3 + axis] = reader.number() }
            }
            reader.expect("endloop")
            reader.expect("endfacet")
            builder.add(facet)
        }
        reader.expect("endsolid")
        reader.skipLine()
    } while (reader.peek() == "solid")
    if (reader.peek() != null) stlError("Unexpected data after STL endsolid")
}

/** Streaming byte tokenizer: no whole-file String, regex split, or unbounded numeric token. */
private class StlTokens(private val bytes: ByteArray) {
    private var at = 0
    private var pending: String? = null

    fun peek(): String? {
        if (pending == null) pending = next()
        return pending
    }

    fun expect(expected: String) {
        val actual = take()
        if (actual != expected) stlError("Expected STL '$expected', got '$actual'")
    }

    fun number(): Float = take()?.toFloatOrNull() ?: stlError("Invalid STL number")

    fun skipLine() {
        while (at < bytes.size && bytes[at].toInt() !in 10..13) {
            if (bytes[at].toInt() !in 32..126 && bytes[at].toInt() != 9) {
                stlError("Non-ASCII STL name")
            }
            at++
        }
    }

    private fun take(): String? = peek().also { pending = null }

    private fun next(): String? {
        while (at < bytes.size && stlWhitespace(bytes[at].toInt())) at++
        if (at == bytes.size) return null
        val start = at
        while (at < bytes.size && !stlWhitespace(bytes[at].toInt())) {
            if (at - start >= 128 || bytes[at].toInt() !in 33..126) stlError("Invalid STL token")
            at++
        }
        return bytes.decodeToString(start, at)
    }
}
