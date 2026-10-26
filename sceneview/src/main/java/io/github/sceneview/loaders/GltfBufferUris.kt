package io.github.sceneview.loaders

import org.json.JSONObject
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    /** The external buffer URIs of [source], among its [resourceUris]. */
    fun of(source: Buffer, resourceUris: Collection<String>): Set<String> {
        val external = resourceUris.filterNot { it.startsWith("data:") }
        if (external.isEmpty()) return emptySet()
        val declared = (source as? ByteBuffer)
            ?.let { runCatching { declaredBufferUris(it) }.getOrNull() }
            ?: return external.filter { it.endsWith(".bin", ignoreCase = true) }.toSet()
        return external.filter { it in declared }.toSet()
    }

    private fun declaredBufferUris(source: ByteBuffer): Set<String>? {
        val json = jsonText(source.duplicate().order(ByteOrder.LITTLE_ENDIAN)) ?: return null
        val buffers = JSONObject(json).optJSONArray("buffers") ?: return emptySet()
        return (0 until buffers.length())
            .mapNotNull { buffers.optJSONObject(it)?.optString("uri", "") }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * Where the glTF JSON of [source] (little-endian, from its position) lies, as a start and a
     * length: a GLB's JSON chunk, or the whole buffer for a text glTF. `null` for a GLB whose JSON
     * chunk is missing or truncated.
     */
    internal fun jsonRange(source: ByteBuffer): Pair<Int, Int>? {
        val start = source.position()
        val isGlb = source.remaining() >= GLB_HEADER_SIZE + CHUNK_HEADER_SIZE &&
            source.getInt(start) == GLB_MAGIC
        if (!isGlb) return start to source.remaining()
        val length = source.getInt(start + GLB_HEADER_SIZE)
        val type = source.getInt(start + GLB_HEADER_SIZE + 4)
        val from = start + GLB_HEADER_SIZE + CHUNK_HEADER_SIZE
        if (type != CHUNK_JSON || length < 0 || from.toLong() + length > source.limit()) return null
        return from to length
    }

    /** The glTF JSON of [source] (little-endian), see [jsonRange]. Leaves its position alone. */
    internal fun jsonText(source: ByteBuffer): String? {
        val (from, length) = jsonRange(source) ?: return null
        val bytes = ByteArray(length)
        source.duplicate().apply { position(from) }.get(bytes)
        return bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
    }
}
