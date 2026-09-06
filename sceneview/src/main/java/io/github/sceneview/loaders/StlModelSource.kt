package io.github.sceneview.loaders

import io.github.sceneview.core.stl.StlLoader
import io.github.sceneview.core.stl.StlParseException
import java.nio.Buffer
import java.nio.ByteBuffer

/** Convert only STL candidates; sniff with absolute reads so ordinary GLBs are never copied. */
internal fun Buffer.convertStlToGlb(): Buffer {
    val source = this as? ByteBuffer ?: return this
    if (!source.looksLikeStlPayload()) return this
    if (source.remaining() > StlLoader.DEFAULT_MAX_BYTES) {
        throw StlParseException("STL input exceeds ${StlLoader.DEFAULT_MAX_BYTES} bytes")
    }
    val bytes = ByteArray(source.remaining()).also { source.duplicate().get(it) }
    val glb = StlLoader.toGlb(bytes)
    return ByteBuffer.allocateDirect(glb.size).put(glb).apply { rewind() }
}

/** Length/count wins over ASCII detection: binary writers often start their header with solid. */
internal fun ByteBuffer.looksLikeStlPayload(): Boolean {
    val start = position()
    val size = remaining()
    if (size >= 84 && (size - 84) % 50 == 0) {
        var count = 0L
        repeat(4) { count = count or ((get(start + 80 + it).toLong() and 0xff) shl (it * 8)) }
        if (count == (size - 84).toLong() / 50) return true
    }
    var at = start
    while (at < limit() && stlSpace(get(at).toInt())) at++
    if (!stlTokenAt(at, "solid")) return false
    at += 5
    while (at < limit()) {
        val value = get(at).toInt()
        if (!stlSpace(value) && value !in 32..126) return false
        if (stlSpace(get(at - 1).toInt()) && stlTokenAt(at, "facet")) return true
        at++
    }
    return false
}

private fun ByteBuffer.stlTokenAt(at: Int, token: String): Boolean =
    at + token.length <= limit() && token.indices.all { get(at + it).toInt() == token[it].code } &&
        (at + token.length == limit() || stlSpace(get(at + token.length).toInt()))

private fun stlSpace(value: Int): Boolean = value == 32 || value in 9..13
