package io.github.sceneview.loaders

import org.json.JSONObject
import org.json.JSONTokener
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
    fun of(source: Buffer, resourceUris: Collection<String>): Set<String> = kinds(source, resourceUris).buffers

    /**
     * Which of [resourceUris] (external ones only) [source] declares as buffers, and which as
     * images. A payload whose JSON cannot be read has its buffers guessed from the `.bin` suffix
     * and **no** image: a load then feeds nothing in place of a missing resource it cannot place.
     *
     * [resourceUris] are gltfio's, which are **raw**: cgltf does not unescape the JSON string of a
     * `uri`, so `"textures\/a.png"` comes back as `textures\/a.png`, while the declarations read
     * here are JSON-decoded. Each is compared through [decodedUri]; the sets returned hold the raw
     * URIs, the ones gltfio feeds by.
     */
    fun kinds(source: Buffer, resourceUris: Collection<String>): Kinds {
        val external = resourceUris.filterNot { it.startsWith("data:") }
        if (external.isEmpty()) return Kinds(emptySet(), emptySet())
        val declared = (source as? ByteBuffer)
            ?.let { runCatching { declaredUris(it) }.getOrNull() }
            ?: return Kinds(external.filter { it.endsWith(".bin", ignoreCase = true) }.toSet(), emptySet())
        return Kinds(
            buffers = external.filter { decodedUri(it) in declared.buffers }.toSet(),
            images = external.filter { uri ->
                decodedUri(uri).let { it in declared.images && it !in declared.buffers }
            }.toSet(),
        )
    }

    /**
     * The key cgltf looks [uri] up by among the resources fed to gltfio, when it is not [uri]
     * itself: `cgltf_load_buffer_file` decodes each `%` followed by two hex digits
     * (`cgltf_decode_uri`) and leaves any other `%` as it is, so a `my%20model.bin` fed under its
     * own name is never found. `null` when decoding changes nothing, or yields bytes a JNI string
     * cannot carry (see [isUnfeedable]).
     */
    fun cgltfKey(uri: String): String? {
        val decoded = decodedBytes(uri) ?: return null
        return feedable(decoded)
    }

    /**
     * Whether cgltf looks [uri] up by a key no resource can be fed under: decoding it changes it,
     * into bytes that are not UTF-8, hold a NUL (where cgltf's C string ends), or a character
     * outside the BMP (which JNI encodes otherwise than UTF-8). Such a buffer is left without data.
     */
    fun isUnfeedable(uri: String): Boolean {
        val decoded = decodedBytes(uri) ?: return false
        return feedable(decoded) == null
    }

    /** The bytes `cgltf_decode_uri` turns [uri] into, `null` when they are those of [uri]. */
    private fun decodedBytes(uri: String): ByteArray? {
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
        return decoded.toByteArray().takeUnless { it.contentEquals(bytes) }
    }

    private fun feedable(decoded: ByteArray): String? {
        val key = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decoded))
                .toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        return key.takeIf { it.none { c -> c == '\u0000' || c.isSurrogate() } }
    }

    private fun hexValue(byte: Byte): Int = Character.digit(byte.toInt().toChar(), HEX_RADIX)

    /**
     * [raw] (a URI as gltfio reports it, straight from the JSON text) with its JSON escapes
     * decoded, as `org.json` reads it. [raw] itself when it has no escape or is not a valid JSON
     * string body.
     */
    fun decodedUri(raw: String): String =
        if ('\\' !in raw) raw
        else runCatching { JSONTokener("\"$raw\"").nextValue() as? String }.getOrNull() ?: raw

    /** External resource URIs by kind; see [kinds]. */
    class Kinds(val buffers: Set<String>, val images: Set<String>)

    private fun declaredUris(source: ByteBuffer): Kinds? {
        val json = jsonText(source.duplicate().order(ByteOrder.LITTLE_ENDIAN)) ?: return null
        val gltf = JSONObject(json)
        fun uris(key: String) = gltf.optJSONArray(key)?.let { array ->
            (0 until array.length())
                .mapNotNull { array.optJSONObject(it)?.optString("uri", "") }
                .filter { it.isNotEmpty() }
                .toSet()
        } ?: emptySet()
        return Kinds(buffers = uris("buffers"), images = uris("images"))
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
