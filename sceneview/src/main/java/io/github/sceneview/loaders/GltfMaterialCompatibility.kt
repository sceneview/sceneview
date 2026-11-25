package io.github.sceneview.loaders

import org.json.JSONArray
import org.json.JSONObject
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Workaround for Filament 1.77.3's incomplete Java MaterialKey / MaterialKeyHelper bridge.
 * It omits hasVolume, hasSpecular (and its textures) and hasDispersion. The Java ubershader
 * delegate therefore selects a material without those uniforms, but AssetLoader retains the
 * original flags and sets the missing parameters, aborting the process.
 *
 * Neither constraining the Java key nor calling constrainMaterial fixes fields absent from JNI.
 * Drop only these extensions until Filament exposes them through its Java provider API. Keep
 * transmission, sheen, clearcoat, IOR and iridescence untouched, along with material recording.
 */
internal object GltfMaterialCompatibility {
    private val unsupported = setOf(
        "KHR_materials_volume", "KHR_materials_specular", "KHR_materials_dispersion",
    )

    // cgltf does not unescape mimeType; Android JSONObject escapes its slash otherwise.
    private fun unescapeSlashes(text: String): String = buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (c == '\\' && i < text.length) {
                val next = text[i++]
                if (next == '/') append('/') else append(c).append(next)
            } else append(c)
        }
    }

    /**
     * [source] with the unsupported extensions removed. When [ownsSource] is `true` (the loader
     * read the file itself, so nobody else holds these bytes) and the rewritten JSON fits in the
     * old one, the JSON is rewritten in place and padded with spaces: a 5.8 MB GLB no longer needs
     * a second 5.8 MB copy right after it was read. On Android a direct buffer is a Java-heap
     * array, so that copy doubled the heap a load needed and ran a 16 MiB heap out of memory on a
     * rotation (#5271). Dropping extensions shrinks the JSON, but `org.json` rewrites numbers
     * (`1e9` becomes `1000000000`), so a minified file can grow: it then gets the copy. A buffer
     * the caller owns is never written to, and gets the copy as before.
     */
    fun prepare(source: Buffer, ownsSource: Boolean = false): Buffer {
        val bytes = (source as? ByteBuffer)?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN) ?: return source
        val text = GltfBufferUris.jsonText(bytes) ?: return source
        // The common case: no affected extension, nothing parsed, the caller's buffer as is.
        if (unsupported.none { it in text }) return source
        // Leave malformed input to gltfio, as before.
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return source
        var changed = false
        val materials = json.optJSONArray("materials")
        if (materials != null) for (i in 0 until materials.length()) {
            val extensions = materials.optJSONObject(i)?.optJSONObject("extensions") ?: continue
            for (name in unsupported) if (extensions.has(name)) {
                extensions.remove(name)
                changed = true
            }
        }
        if (!changed) return source
        for (key in listOf("extensionsUsed", "extensionsRequired")) {
            json.optJSONArray(key)?.let { names ->
                val kept = JSONArray()
                for (i in 0 until names.length()) {
                    val name = names.getString(i)
                    if (name !in unsupported) kept.put(name)
                }
                if (kept.length() == 0) json.remove(key) else json.put(key, kept)
            }
        }
        val encoded = unescapeSlashes(json.toString()).toByteArray(Charsets.UTF_8)
        val start = bytes.position()
        val writable = ownsSource && !bytes.isReadOnly
        if (bytes.remaining() < 20 || bytes.getInt(start) != 0x46546C67) {
            // A .gltf: the JSON is the whole buffer. Trailing spaces are valid JSON.
            if (writable && encoded.size <= bytes.remaining()) {
                return source.also { overwrite(bytes, start, encoded, bytes.remaining()) }
            }
            return ByteBuffer.allocateDirect(encoded.size).put(encoded).apply { flip() }
        }
        // Preserve every trailing chunk byte for byte, including BIN padding and unknown chunks.
        val oldJsonLength = bytes.getInt(start + 12)
        // Same JSON chunk length: the header, the BIN chunk and every offset stay as they are.
        if (writable && encoded.size <= oldJsonLength) {
            return source.also { overwrite(bytes, start + 20, encoded, oldJsonLength) }
        }
        val tail = bytes.duplicate().apply { position(start + 20 + oldJsonLength) }
        val paddedLength = (encoded.size + 3) and -4
        return ByteBuffer.allocateDirect(20 + paddedLength + tail.remaining())
            .order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x46546C67)
                putInt(bytes.getInt(start + 4))
                putInt(capacity())
                putInt(paddedLength)
                putInt(0x4E4F534A)
                put(encoded)
                repeat(paddedLength - encoded.size) { put(0x20.toByte()) }
                put(tail)
                flip()
            }
    }

    /** Writes [encoded] at [at] and pads it with spaces up to [length] bytes, without moving [bytes]. */
    private fun overwrite(bytes: ByteBuffer, at: Int, encoded: ByteArray, length: Int) {
        bytes.duplicate().apply {
            position(at)
            put(encoded)
            repeat(length - encoded.size) { put(0x20.toByte()) }
        }
    }
}
