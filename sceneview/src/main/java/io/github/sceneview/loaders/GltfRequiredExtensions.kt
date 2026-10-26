package io.github.sceneview.loaders

import org.json.JSONObject
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
 * Only the JSON is looked at: a GLB's JSON chunk, or a text glTF whole. A payload with no
 * `extensionsRequired` key is not parsed at all, and one that is not glTF (or whose JSON is broken)
 * passes, so gltfio reports it the way it always has.
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
        if (!bytes.contains(KEY, from, length)) return emptyList()
        val required = runCatching {
            GltfBufferUris.jsonText(bytes)?.let { JSONObject(it).optJSONArray("extensionsRequired") }
        }.getOrNull() ?: return emptyList()
        return (0 until required.length())
            .mapNotNull { required.opt(it) as? String }
            .filter { it !in SUPPORTED }
            .distinct()
    }

    /** Throws [UnsupportedGltfExtensionException] when [source] requires what gltfio cannot load. */
    fun check(source: Buffer) {
        val unsupported = unsupported(source)
        if (unsupported.isNotEmpty()) throw UnsupportedGltfExtensionException(unsupported)
    }

    private fun ByteBuffer.contains(key: ByteArray, from: Int, length: Int): Boolean {
        val last = from + length - key.size
        var i = from
        while (i <= last) {
            if (get(i) == key[0]) {
                var j = 1
                while (j < key.size && get(i + j) == key[j]) j++
                if (j == key.size) return true
            }
            i++
        }
        return false
    }
}
