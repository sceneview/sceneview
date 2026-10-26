package io.github.sceneview.loaders

import java.io.ByteArrayOutputStream
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A glTF or GLB model lists, in `extensionsRequired`, an extension the bundled Filament gltfio
 * cannot load ([extensions], in file order). The glTF specification says a loader must refuse such
 * a file; gltfio does not check the list and would load it anyway, without what the extension
 * carries (an `EXT_texture_avif` model renders untextured).
 *
 * Thrown by the [ModelLoader] loads **before any Filament call**, so nothing was created and
 * nothing needs destroying. The `load*Async` calls and `rememberModelInstance` report it as a
 * failed load (`null`, logged); [ModelLoader.loadModel], [ModelLoader.loadModelInstance],
 * [ModelLoader.awaitModelInstance] and [ModelLoader.createModel] throw it, and
 * [io.github.sceneview.rememberModelLoadState] hands it over as
 * [io.github.sceneview.ModelLoadState.Failed].
 *
 * What gltfio supports: [ModelLoader.SUPPORTED_GLTF_EXTENSIONS]. A generation or optimisation
 * pipeline can usually export without the offending extension (PNG/JPEG or KTX2 textures instead
 * of AVIF, `EXT_meshopt_compression` instead of `KHR_meshopt_compression`).
 */
class UnsupportedGltfExtensionException(
    val extensions: List<String>,
) : IllegalArgumentException(
    "The glTF model requires ${extensions.joinToString()}, which Filament's gltfio cannot load " +
        "(supported: ModelLoader.SUPPORTED_GLTF_EXTENSIONS)",
)

/**
 * Reads a payload's `extensionsRequired` against what gltfio implements, without touching Filament
 * (a JVM-testable pass the suspend loads run off the main thread).
 *
 * Only the JSON is looked at: a GLB's JSON chunk, or a text glTF whole. Nothing is parsed but the
 * `extensionsRequired` array itself, read in place: a text glTF can embed tens of MB of base64
 * data URIs, which a whole-document parse would copy several times over. A payload with no such
 * array, or one that is not glTF, passes, so gltfio reports it the way it always has.
 */
internal object GltfRequiredExtensions {

    /**
     * The extensions Filament 1.72.1's gltfio reads (the names its Android build parses), plus
     * `KHR_mesh_quantization`, which needs no parsing: it only widens the accessor types, and gltfio
     * uploads quantized attributes as they are. gltfpack writes it next to `EXT_meshopt_compression`.
     */
    val SUPPORTED: Set<String> = setOf(
        "EXT_mesh_gpu_instancing",
        "EXT_meshopt_compression",
        "EXT_texture_webp",
        "KHR_draco_mesh_compression",
        "KHR_lights_punctual",
        "KHR_materials_anisotropy",
        "KHR_materials_clearcoat",
        "KHR_materials_diffuse_transmission",
        "KHR_materials_dispersion",
        "KHR_materials_emissive_strength",
        "KHR_materials_ior",
        "KHR_materials_iridescence",
        "KHR_materials_pbrSpecularGlossiness",
        "KHR_materials_sheen",
        "KHR_materials_specular",
        "KHR_materials_transmission",
        "KHR_materials_unlit",
        "KHR_materials_variants",
        "KHR_materials_volume",
        "KHR_mesh_quantization",
        "KHR_texture_basisu",
        "KHR_texture_transform",
    )

    private val KEY = "\"extensionsRequired\"".toByteArray(Charsets.US_ASCII)

    /** The extensions [source] requires that gltfio cannot load, in file order. */
    fun unsupported(source: Buffer): List<String> {
        val bytes = (source as? ByteBuffer)?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN) ?: return emptyList()
        val (from, length) = GltfBufferUris.jsonRange(bytes) ?: return emptyList()
        val required = bytes.requiredExtensions(from, from + length) ?: return emptyList()
        return required.filter { it !in SUPPORTED }.distinct()
    }

    /** Throws [UnsupportedGltfExtensionException] when [source] requires what gltfio cannot load. */
    fun check(source: Buffer) {
        val unsupported = unsupported(source)
        if (unsupported.isNotEmpty()) throw UnsupportedGltfExtensionException(unsupported)
    }

    /**
     * The strings of the first `"extensionsRequired": [...]` between [from] and [end], or `null`
     * when there is none or it is not an array of strings. The key is matched with its quotes, so
     * the same text inside a string value (escaped quotes) does not count.
     */
    private fun ByteBuffer.requiredExtensions(from: Int, end: Int): List<String>? {
        var at = indexOf(KEY, from, end)
        while (at >= 0) {
            val colon = skipSpaces(at + KEY.size, end)
            if (colon < end && get(colon) == COLON) return stringArray(skipSpaces(colon + 1, end), end)
            at = indexOf(KEY, at + 1, end)
        }
        return null
    }

    private fun ByteBuffer.stringArray(start: Int, end: Int): List<String>? {
        if (start >= end || get(start) != OPEN) return null
        val strings = mutableListOf<String>()
        var i = skipSpaces(start + 1, end)
        if (i < end && get(i) == CLOSE) return strings
        while (true) {
            if (i >= end || get(i) != QUOTE) return null
            val text = ByteArrayOutputStream()
            i++
            while (true) {
                if (i >= end) return null
                val b = get(i++)
                if (b == QUOTE) break
                if (b != BACKSLASH) {
                    text.write(b.toInt())
                    continue
                }
                if (i >= end) return null
                when (val escaped = get(i++).toInt().toChar()) {
                    'u' -> {
                        if (i + 4 > end) return null
                        val code = (0 until 4).fold(0) { acc, k ->
                            val digit = Character.digit(get(i + k).toInt().toChar(), 16)
                            if (digit < 0) return null
                            acc * 16 + digit
                        }
                        i += 4
                        text.write(code.toChar().toString().toByteArray(Charsets.UTF_8))
                    }
                    'b' -> text.write('\b'.code)
                    'f' -> text.write(0x0C)
                    'n' -> text.write('\n'.code)
                    'r' -> text.write('\r'.code)
                    't' -> text.write('\t'.code)
                    else -> text.write(escaped.code) // `"`, `\` and `/`
                }
            }
            strings += text.toString(Charsets.UTF_8.name())
            i = skipSpaces(i, end)
            if (i >= end) return null
            when (get(i)) {
                COMMA -> i = skipSpaces(i + 1, end)
                CLOSE -> return strings
                else -> return null
            }
        }
    }

    private fun ByteBuffer.indexOf(key: ByteArray, from: Int, end: Int): Int {
        val last = end - key.size
        var i = from
        while (i <= last) {
            if (get(i) == key[0]) {
                var j = 1
                while (j < key.size && get(i + j) == key[j]) j++
                if (j == key.size) return i
            }
            i++
        }
        return -1
    }

    private fun ByteBuffer.skipSpaces(from: Int, end: Int): Int {
        var i = from
        while (i < end && get(i).toInt().toChar().let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) i++
        return i
    }

    private val QUOTE = '"'.code.toByte()
    private val BACKSLASH = '\\'.code.toByte()
    private val COLON = ':'.code.toByte()
    private val COMMA = ','.code.toByte()
    private val OPEN = '['.code.toByte()
    private val CLOSE = ']'.code.toByte()
}
