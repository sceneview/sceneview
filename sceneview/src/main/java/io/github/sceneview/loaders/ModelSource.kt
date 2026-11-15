package io.github.sceneview.loaders

import io.github.sceneview.core.model.ModelConverter
import io.github.sceneview.core.model.ModelFormat
import io.github.sceneview.core.model.ModelUnit
import io.github.sceneview.core.model.UnsupportedModelFormat
import io.github.sceneview.core.stl.StlLoader
import io.github.sceneview.core.stl.StlParseException
import java.nio.Buffer
import java.nio.ByteBuffer

/** Normalise meshes before JNI; native glTF buffers retain their identity and position. */
internal fun Buffer.toModelGlb(fileName: String? = null, unit: ModelUnit? = null): Buffer {
    val source = this as? ByteBuffer ?: throw UnsupportedModelFormat(null)
    if (source.isNativeGltf()) return this
    val prefix = ByteArray(minOf(source.remaining(), 4097)).also { source.duplicate().get(it) }
    val nativeFormat = runCatching { ModelFormat.sniff(null, prefix) }.getOrNull()
    if (nativeFormat == ModelFormat.PLY_SPLAT || nativeFormat == ModelFormat.SPZ) {
        throw UnsupportedModelFormat(if (nativeFormat == ModelFormat.SPZ) "spz" else "ply")
    }
    if (source.remaining() > StlLoader.DEFAULT_MAX_BYTES &&
        (source.hasBinaryStlSize() || source.hasAsciiStlHeader())) {
        throw StlParseException("STL input exceeds ${StlLoader.DEFAULT_MAX_BYTES} bytes")
    }
    val bytes = ByteArray(source.remaining()).also { source.duplicate().get(it) }
    val format = ModelFormat.sniff(fileName, bytes)
    if (format == ModelFormat.GLB || format == ModelFormat.GLTF) return this
    val glb = ModelConverter.toGlb(bytes, format, unit)
    return ByteBuffer.allocateDirect(glb.size).put(glb).apply { rewind() }
}

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
