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

    fun prepare(source: Buffer): Buffer {
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
        if (bytes.remaining() < 20 || bytes.getInt(start) != 0x46546C67) {
            return ByteBuffer.allocateDirect(encoded.size).put(encoded).apply { flip() }
        }
        // Preserve every trailing chunk byte for byte, including BIN padding and unknown chunks.
        val oldJsonLength = bytes.getInt(start + 12)
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
}
