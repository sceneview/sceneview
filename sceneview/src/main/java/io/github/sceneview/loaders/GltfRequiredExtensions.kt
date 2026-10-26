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
 * Detected **before any Filament call**, so nothing was created and nothing needs destroying.
 * [ModelLoader.awaitModelInstance] throws it and [io.github.sceneview.rememberModelLoadState]
 * hands it over as [io.github.sceneview.ModelLoadState.Failed]. The synchronous
 * [ModelLoader.createModel], [ModelLoader.createModelInstance] and
 * [ModelLoader.createInstancedModel] throw it as the `IllegalArgumentException` they already
 * declare for a buffer they cannot load. The other suspend loads ([ModelLoader.loadModel],
 * [ModelLoader.loadModelInstance], [ModelLoader.loadInstancedModel]), the `load*Async` calls and
 * `rememberModelInstance` report it as any failed load: `null` (or an empty list), logged.
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
 * Only the JSON is looked at: a GLB's JSON chunk, or a text glTF whole. Only the root object's
 * own `extensionsRequired` counts (not one under `extras` or any other nested object), found by
 * stepping over the root's other values without decoding them, then read in place: a text glTF can
 * embed tens of MB of base64 data URIs, which a whole-document parse would copy several times
 * over. A payload with no such array, one whose JSON is malformed before it, or one that is not
 * glTF, passes, so gltfio reports it the way it always has.
 */
internal object GltfRequiredExtensions {

    /**
     * The extensions Filament 1.72.1's gltfio renders, plus `KHR_mesh_quantization`, which needs
     * no parsing: it only widens the accessor types, and gltfio uploads quantized attributes as they
     * are. gltfpack writes it next to `EXT_meshopt_compression`.
     *
     * Not listed although cgltf parses them, because gltfio's `AssetLoader` never reads what they
     * carry: `KHR_materials_anisotropy`, `KHR_materials_iridescence`,
     * `KHR_materials_diffuse_transmission` (the material renders without them) and
     * `EXT_mesh_gpu_instancing` (every instance collapses onto the node). A model that only *uses*
     * them still loads; one that *requires* them is refused, as the glTF specification asks.
     */
    val SUPPORTED: Set<String> = setOf(
        "EXT_meshopt_compression",
        "EXT_texture_webp",
        "KHR_draco_mesh_compression",
        "KHR_lights_punctual",
        "KHR_materials_clearcoat",
        "KHR_materials_dispersion",
        "KHR_materials_emissive_strength",
        "KHR_materials_ior",
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
     * The strings of the root object's `"extensionsRequired"` array, between [from] and [end], or
     * `null` when the root has no such key, its value is not an array of strings, or the JSON is
     * malformed before it. The root's other values are stepped over, strings and nested
     * objects/arrays whole, so the key inside `extras` or inside a string value does not count.
     */
    private fun ByteBuffer.requiredExtensions(from: Int, end: Int): List<String>? {
        var i = skipSpaces(from, end)
        if (i >= end || get(i) != OPEN_OBJECT) return null
        i = skipSpaces(i + 1, end)
        while (i < end && get(i) == QUOTE) {
            val keyEnd = skipString(i, end)
            if (keyEnd < 0) return null
            val isRequired = keyEnd - i == KEY.size && (KEY.indices).all { get(i + it) == KEY[it] }
            i = skipSpaces(keyEnd, end)
            if (i >= end || get(i) != COLON) return null
            i = skipSpaces(i + 1, end)
            if (isRequired) return stringArray(i, end)
            i = skipValue(i, end)
            if (i < 0) return null
            i = skipSpaces(i, end)
            if (i >= end || get(i) != COMMA) return null
            i = skipSpaces(i + 1, end)
        }
        return null
    }

    /** The index right after the string that starts at [start] (a quote), or `-1` if unterminated. */
    private fun ByteBuffer.skipString(start: Int, end: Int): Int {
        var i = start + 1
        while (i < end) {
            when (get(i)) {
                BACKSLASH -> i += 2
                QUOTE -> return i + 1
                else -> i++
            }
        }
        return -1
    }

    /** The index right after the JSON value that starts at [start], or `-1` if it is cut short. */
    private fun ByteBuffer.skipValue(start: Int, end: Int): Int {
        if (start >= end) return -1
        when (get(start)) {
            QUOTE -> return skipString(start, end)
            OPEN, OPEN_OBJECT -> {
                var depth = 0
                var i = start
                while (i < end) {
                    when (get(i)) {
                        QUOTE -> {
                            i = skipString(i, end)
                            if (i < 0) return -1
                            continue
                        }
                        OPEN, OPEN_OBJECT -> depth++
                        CLOSE, CLOSE_OBJECT -> if (--depth == 0) return i + 1
                    }
                    i++
                }
                return -1
            }
            else -> {
                // A number, `true`, `false` or `null`: up to the next delimiter.
                var i = start
                while (i < end && get(i).let { it != COMMA && it != CLOSE_OBJECT && it != CLOSE } &&
                    get(i).toInt().toChar() !in " \n\r\t") i++
                return i
            }
        }
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
    private val OPEN_OBJECT = '{'.code.toByte()
    private val CLOSE_OBJECT = '}'.code.toByte()
}
