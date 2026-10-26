package io.github.sceneview.loaders

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The external `buffers[].uri` of a glTF or GLB payload: the resources a model cannot load
 * without, unlike its images.
 *
 * gltfio does not tell them apart, nor fail on a missing buffer. In Filament 1.72.1 its Android
 * `cgltf` read callback answers a buffer absent from the URI cache with *success* and no data
 * (`libs/gltfio/src/Utility.cpp`, `loadCgltfBuffers`), `asyncBeginLoad` still returns `true` (it
 * returns `false` only for an asset whose resources were already loaded or whose meshopt
 * compressed data fails to decode), and `uploadBuffers` skips every vertex and index upload from
 * the null data: the model comes back with no geometry.
 * So a load has to know which of `FilamentAsset.resourceUris` are buffers before it begins.
 *
 * `data:` URIs are left out: cgltf decodes them itself. A payload whose JSON cannot be read falls
 * back to the `.bin` suffix.
 */
internal object GltfBufferUris {

    private const val GLB_MAGIC = 0x46546C67 // "glTF", little-endian
    private const val GLB_HEADER_SIZE = 12
    private const val CHUNK_HEADER_SIZE = 8
    private const val CHUNK_JSON = 0x4E4F534A // "JSON", little-endian
    private const val PERCENT = '%'.code.toByte()
    private const val HEX_RADIX = 16

    /** The external buffer URIs of [source], among its [resourceUris]. */
    fun of(source: Buffer, resourceUris: Collection<String>): Set<String> {
        val external = resourceUris.filterNot { it.startsWith("data:") }
        if (external.isEmpty()) return emptySet()
        val declared = (source as? ByteBuffer)
            ?.let { runCatching { declaredBufferUris(it) }.getOrNull() }
            ?: return external.filter { it.endsWith(".bin", ignoreCase = true) }.toSet()
        return external.filter { it in declared }.toSet()
    }

    /**
     * The key cgltf looks [uri] up by among the resources fed to gltfio, when it is not [uri]
     * itself: `cgltf_load_buffer_file` decodes each `%` followed by two hex digits
     * (`cgltf_decode_uri`) and leaves any other `%` as it is, so a `my%20model.bin` fed under its
     * own name is never found. `null` when decoding changes nothing, or yields bytes a JNI string
     * cannot carry (not UTF-8, a NUL, or a character outside the BMP, which JNI encodes otherwise).
     */
    fun cgltfKey(uri: String): String? {
        if ('%' !in uri) return null
        val bytes = uri.toByteArray(Charsets.UTF_8)
        val decoded = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val high = if (bytes[i] == PERCENT && i + 2 < bytes.size) hexValue(bytes[i + 1]) else -1
            val low = if (high >= 0) hexValue(bytes[i + 2]) else -1
            if (low >= 0) {
                decoded.write(high * HEX_RADIX + low)
                i += 3
            } else {
                decoded.write(bytes[i++].toInt())
            }
        }
        val key = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decoded.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        return key.takeIf { it != uri && key.none { c -> c == '\u0000' || c.isSurrogate() } }
    }

    private fun hexValue(byte: Byte): Int = Character.digit(byte.toInt().toChar(), HEX_RADIX).takeIf { byte >= 0 } ?: -1

    private fun declaredBufferUris(source: ByteBuffer): Set<String>? {
        val json = jsonText(source.duplicate().order(ByteOrder.LITTLE_ENDIAN)) ?: return null
        val buffers = JSONObject(json).optJSONArray("buffers") ?: return emptySet()
        return (0 until buffers.length())
            .mapNotNull { buffers.optJSONObject(it)?.optString("uri", "") }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun jsonText(source: ByteBuffer): String? {
        val start = source.position()
        val isGlb = source.remaining() >= GLB_HEADER_SIZE + CHUNK_HEADER_SIZE &&
            source.getInt(start) == GLB_MAGIC
        if (!isGlb) {
            return ByteArray(source.remaining()).also { source.get(it) }
                .toString(Charsets.UTF_8).removePrefix("\uFEFF")
        }
        val length = source.getInt(start + GLB_HEADER_SIZE)
        val type = source.getInt(start + GLB_HEADER_SIZE + 4)
        val from = start + GLB_HEADER_SIZE + CHUNK_HEADER_SIZE
        if (type != CHUNK_JSON || length < 0 || from.toLong() + length > source.limit()) return null
        return ByteArray(length).also { source.position(from); source.get(it) }.toString(Charsets.UTF_8)
    }
}
