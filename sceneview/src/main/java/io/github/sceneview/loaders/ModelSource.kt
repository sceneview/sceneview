package io.github.sceneview.loaders

import io.github.sceneview.core.model.ModelConverter
import io.github.sceneview.core.model.ModelFormat
import io.github.sceneview.core.model.ModelUnit
import io.github.sceneview.core.model.UnsupportedModelFormat
import io.github.sceneview.core.stl.StlLoader
import io.github.sceneview.core.stl.StlParseException
import java.nio.Buffer
import java.nio.ByteBuffer

/**
 * Normalise meshes before JNI; native glTF buffers retain their identity and position.
 *
 * Only a payload some supported format could be is copied onto the heap, once, to be sniffed and
 * converted: one whose first 4 KB, binary-STL size or [fileName] extension names one, a ZIP with
 * no extension, `.zip` or a supported one (a 3MF needs its whole central directory), or text that
 * is only OBJ comments and keywords so far.
 * Anything else (FBX, USDZ, STEP, an HTML error page without an extension) is refused from that
 * prefix. A buffer that is not a [ByteBuffer] goes to gltfio untouched, as it always did.
 *
 * @throws UnsupportedModelFormat for an unsupported format or a Gaussian splat.
 * @throws StlParseException and the other `sceneview-core` parse exceptions for a payload its
 * extension or content routes to a converter that cannot parse it.
 */
internal fun Buffer.toModelGlb(fileName: String? = null, unit: ModelUnit? = null): Buffer {
    val source = this as? ByteBuffer ?: return this
    if (source.isNativeGltf()) return this
    val prefix = ByteArray(minOf(source.remaining(), SNIFF_PREFIX_BYTES)).also { source.duplicate().get(it) }
    val prefixFormat = runCatching { ModelFormat.sniff(null, prefix) }.getOrNull()
    if (prefixFormat == ModelFormat.PLY_SPLAT || prefixFormat == ModelFormat.SPZ) {
        throw UnsupportedModelFormat(if (prefixFormat == ModelFormat.SPZ) "spz" else "ply")
    }
    val byExtension = runCatching { ModelFormat.sniff(fileName, byteArrayOf()) }
    val extensionFormat = byExtension.getOrNull()
    // The unsupported extension, null when there is none or it names a supported format.
    val extension = (byExtension.exceptionOrNull() as? UnsupportedModelFormat)?.extension
    // A ZIP is worth a copy only when it may be a 3MF: not when its extension says USDZ, say.
    val mayBeThreeMf = source.startsWithZipMagic() && (extension == null || extension == "zip")
    val mayBeStl = source.hasBinaryStlSize() || source.hasAsciiStlHeader()
    // OBJ has no magic: a long comment header can push its first vertex past the prefix.
    val mayBeObj = prefix.isObjPreamble()
    if (prefixFormat == null && !mayBeThreeMf && !mayBeStl && !mayBeObj) {
        // A `.glb`/`.gltf` nothing else claims goes to gltfio as it is, for its own parse error.
        if (extensionFormat == ModelFormat.GLB || extensionFormat == ModelFormat.GLTF) return this
        if (extensionFormat == null) throw UnsupportedModelFormat(extension)
    }
    if (source.remaining() > StlLoader.DEFAULT_MAX_BYTES && mayBeStl) {
        throw StlParseException("STL input exceeds ${StlLoader.DEFAULT_MAX_BYTES} bytes")
    }
    val bytes = source.wholeBytes()
    val format = ModelFormat.sniff(fileName, bytes)
    if (format == ModelFormat.GLB || format == ModelFormat.GLTF) return this
    val glb = ModelConverter.toGlb(bytes, format, unit)
    return ByteBuffer.allocateDirect(glb.size).put(glb).apply { rewind() }
}

/** One more byte than the 4 KB sniffers read, so they can tell a cut line from the real end. */
private const val SNIFF_PREFIX_BYTES = 4097

/** The remaining bytes: the backing array itself when it is exactly that, else one copy. */
private fun ByteBuffer.wholeBytes(): ByteArray {
    if (hasArray() && arrayOffset() + position() == 0 && remaining() == array().size) return array()
    return ByteArray(remaining()).also { duplicate().get(it) }
}

/**
 * Whether every line of this prefix is blank, a comment or starts with an OBJ keyword: what an
 * OBJ whose first vertex lies past the prefix starts with. Binary data and markup fail at once.
 */
private fun ByteArray.isObjPreamble(): Boolean {
    var at = 0
    while (at < size) {
        var end = at
        while (end < size && this[end] != '\n'.code.toByte() && this[end] != '\r'.code.toByte()) end++
        val keyword = decodeToString(at, end).trimStart('\uFEFF').substringBefore('#').trim()
            .substringBefore(' ').substringBefore('\t')
        if (keyword !in OBJ_PREAMBLE_KEYWORDS) return false
        at = end + 1
    }
    return true
}

private val OBJ_PREAMBLE_KEYWORDS = setOf("", "v", "vn", "vt", "o", "g", "mtllib", "usemtl", "s", "l", "p")

private fun ByteBuffer.startsWithZipMagic(): Boolean =
    remaining() >= 4 && "PK\u0003\u0004".indices.all { get(position() + it) == "PK\u0003\u0004"[it].code.toByte() }

/** Scan the root asset key without copying embedded data or imposing a JSON-property order. */
private fun ByteBuffer.isNativeGltf(): Boolean {
    val start = position()
    if (remaining() >= 4 && "glTF".indices.all { get(start + it) == "glTF"[it].code.toByte() }) return true
    var at = start
    if (remaining() >= 3 && get(at) == 0xef.toByte() && get(at + 1) == 0xbb.toByte() && get(at + 2) == 0xbf.toByte()) at += 3
    at = skipModelSpaces(at)
    if (at == limit() || get(at) != '{'.code.toByte()) return false
    var depth = 0
    while (at < limit()) {
        when (get(at).toInt().toChar()) {
            '{', '[' -> depth++
            '}', ']' -> depth--
            '"' -> {
                val keyStart = at++
                while (at < limit() && get(at) != '"'.code.toByte()) {
                    at += if (get(at) == '\\'.code.toByte()) 2 else 1
                }
                if (at >= limit()) return false
                val isAsset = depth == 1 && at - keyStart == 6 &&
                    "asset".indices.all { get(keyStart + 1 + it) == "asset"[it].code.toByte() }
                if (isAsset) {
                    at = skipModelSpaces(at + 1)
                    if (at < limit() && get(at++) == ':'.code.toByte()) {
                        at = skipModelSpaces(at)
                        if (at < limit() && get(at) == '{'.code.toByte()) return true
                    }
                    continue
                }
            }
        }
        at++
    }
    return false
}

private fun ByteBuffer.skipModelSpaces(start: Int): Int {
    var at = start
    while (at < limit() && get(at).toInt().toChar() in " \t\r\n") at++
    return at
}

/** Binary STL has no magic; the complete remaining size must agree with its unsigned count. */
private fun ByteBuffer.hasBinaryStlSize(): Boolean {
    if (remaining() < 84 || (remaining() - 84) % 50 != 0) return false
    var count = 0L
    repeat(4) { count = count or ((get(position() + 80 + it).toLong() and 255) shl (it * 8)) }
    return count == (remaining() - 84).toLong() / 50
}

private fun ByteBuffer.hasAsciiStlHeader(): Boolean {
    var at = position()
    while (at < limit() && get(at).toInt().let { it == 32 || it in 9..13 }) at++
    if (!hasStlToken(at, "solid")) return false
    at += 5
    while (at < limit()) {
        val value = get(at).toInt()
        if (value !in 32..126 && value !in 9..13) return false
        if (get(at - 1).toInt().let { it == 32 || it in 9..13 } && hasStlToken(at, "facet")) return true
        at++
    }
    return false
}

private fun ByteBuffer.hasStlToken(at: Int, token: String): Boolean =
    at + token.length <= limit() && token.indices.all { get(at + it) == token[it].code.toByte() } &&
        (at + token.length == limit() || get(at + token.length).toInt().let { it == 32 || it in 9..13 })
