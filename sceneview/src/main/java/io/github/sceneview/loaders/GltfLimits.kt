package io.github.sceneview.loaders

import org.json.JSONArray
import org.json.JSONObject
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A glTF model gltfio would abort the app on, rather than refuse: what [GltfLimits] found.
 *
 * An [IllegalArgumentException], like every other model [ModelLoader] cannot load.
 */
internal class GltfLimitException(val violations: List<String>) : IllegalArgumentException(
    "Filament cannot load this glTF model: ${violations.joinToString("; ")}",
)

/**
 * The limits of Filament's that a glTF file can exceed, read from the file before gltfio sees it.
 *
 * gltfio checks neither. It passes them on to Filament's builders, whose `PreconditionPanic` is an
 * `abort()` of the whole process on Android, out of reach of any Java `try`:
 *
 * - a skin of more than [MAX_SKIN_JOINTS] joints: gltfio calls `RenderableManager::Builder::
 *   skinning(joints_count)` as is, and `build` requires at most `CONFIG_MAX_BONE_COUNT` (256),
 *   inside `AssetLoader.createAsset`;
 * - a PNG, JPEG or KTX2 texture wider or taller than the GPU's largest texture: the texture
 *   providers build the `Texture` at the image's size, and `Texture::Builder::build` requires both
 *   to fit `Texture.getMaxTextureSize`, inside `ResourceLoader.asyncBeginLoad`. An 8K texture is
 *   beyond a good share of the GPUs still in use.
 *
 * Filament-free and JVM-testable (`org.json` is an Android API, so its test runs on Robolectric). A
 * payload with neither `"skins"` nor `"images"` in its JSON is not parsed at all, and one that is
 * not glTF, or whose JSON is broken, passes: gltfio reports it the way it always has. Images
 * embedded as `data:` URIs are not measured.
 */
internal object GltfLimits {

    /** Filament's `CONFIG_MAX_BONE_COUNT`: the most joints one skinned renderable can have. */
    const val MAX_SKIN_JOINTS = 256

    private val SKINS = "\"skins\"".toByteArray(Charsets.US_ASCII)
    private val IMAGES = "\"images\"".toByteArray(Charsets.US_ASCII)

    /**
     * What in [source] exceeds Filament's limits, one sentence each, in file order.
     *
     * @param maxTextureSize the GPU's largest texture side; `0` or less skips the textures.
     * @param resource the external resource a relative URI names, when it was loaded already. Its
     * images are measured too, and so are the images of its buffers.
     */
    fun violations(
        source: Buffer,
        maxTextureSize: Int,
        resource: (uri: String) -> Buffer? = { null },
    ): List<String> {
        val bytes = (source as? ByteBuffer)?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN) ?: return emptyList()
        val chunks = glbChunks(bytes)
        val (jsonFrom, jsonLength) = chunks?.json ?: (bytes.position() to bytes.remaining())
        if (!bytes.contains(SKINS, jsonFrom, jsonLength) && !bytes.contains(IMAGES, jsonFrom, jsonLength)) {
            return emptyList()
        }
        val json = runCatching {
            val text = ByteArray(jsonLength).also { bytes.duplicate().apply { position(jsonFrom) }.get(it) }
            JSONObject(text.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
        }.getOrNull() ?: return emptyList()

        val violations = mutableListOf<String>()
        json.optJSONArray("skins").forEachObject { index, skin ->
            val joints = skin.optJSONArray("joints")?.length() ?: 0
            if (joints > MAX_SKIN_JOINTS) {
                violations += "skin $index has $joints joints, more than the $MAX_SKIN_JOINTS Filament can skin"
            }
        }
        if (maxTextureSize > 0) {
            json.optJSONArray("images").forEachObject { index, image ->
                val pixels = imageBytes(image, json, bytes, chunks?.bin, resource) ?: return@forEachObject
                val (width, height) = imageSize(pixels) ?: return@forEachObject
                if (width > maxTextureSize || height > maxTextureSize) {
                    val name = image.optString("uri").takeIf { it.isNotEmpty() && !it.startsWith("data:") }
                        ?: image.optString("name").takeIf { it.isNotEmpty() }
                    violations += "image $index${name?.let { " ($it)" }.orEmpty()} is ${width}x$height, " +
                        "larger than the ${maxTextureSize}x$maxTextureSize textures this GPU supports"
                }
            }
        }
        return violations
    }

    /** Throws a [GltfLimitException] when [source] exceeds Filament's limits, see [violations]. */
    fun check(source: Buffer, maxTextureSize: Int, resource: (uri: String) -> Buffer? = { null }) {
        val violations = violations(source, maxTextureSize, resource)
        if (violations.isNotEmpty()) throw GltfLimitException(violations)
    }

    /**
     * The bytes of [image]: an external file by its URI, or a buffer view into the GLB's BIN chunk
     * or into an external buffer. `null` when they are not at hand (or are a `data:` URI).
     */
    private fun imageBytes(
        image: JSONObject,
        json: JSONObject,
        glb: ByteBuffer,
        bin: Pair<Int, Int>?,
        resource: (String) -> Buffer?,
    ): ByteBuffer? {
        val uri = image.optString("uri")
        if (uri.isNotEmpty()) return if (uri.startsWith("data:")) null else resource(uri).asLittleEndian()
        val view = json.optJSONArray("bufferViews")?.optJSONObject(image.optInt("bufferView", -1)) ?: return null
        val bufferIndex = view.optInt("buffer", -1)
        val buffer = json.optJSONArray("buffers")?.optJSONObject(bufferIndex) ?: return null
        val bufferUri = buffer.optString("uri")
        val (data, base, limit) = when {
            bufferUri.isEmpty() && bufferIndex == 0 && bin != null -> Triple(glb, bin.first, bin.first + bin.second)
            bufferUri.isNotEmpty() && !bufferUri.startsWith("data:") -> {
                val external = resource(bufferUri).asLittleEndian() ?: return null
                Triple(external, external.position(), external.limit())
            }
            else -> return null
        }
        val from = base.toLong() + view.optLong("byteOffset", 0)
        val to = from + view.optLong("byteLength", 0)
        if (from < base || to > limit || to <= from) return null
        return data.duplicate().apply {
            limit(to.toInt())
            position(from.toInt())
        }.slice()
    }

    /** The width and height a PNG, JPEG or KTX2 header declares, or `null` for anything else. */
    internal fun imageSize(image: ByteBuffer): Pair<Int, Int>? {
        val bytes = image.duplicate()
        val at = bytes.position()
        fun u8(i: Int) = bytes.get(at + i).toInt() and 0xFF
        fun be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun be32(i: Int) = (be16(i) shl 16) or be16(i + 2)
        fun le32(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)
        val size = bytes.remaining()
        fun startsWith(magic: IntArray) = size >= magic.size && magic.indices.all { u8(it) == magic[it] }

        return when {
            // IHDR is the first chunk: width and height, big-endian, at 16 and 20.
            startsWith(PNG) && size >= 24 -> be32(16) to be32(20)
            // pixelWidth and pixelHeight, little-endian, after the identifier, vkFormat and typeSize.
            startsWith(KTX2) && size >= 28 -> le32(20) to le32(24)
            startsWith(JPEG) -> jpegSize(size, ::u8, ::be16)
            else -> null
        }
    }

    /** Walks the JPEG markers up to the first start-of-frame, which holds height then width. */
    private fun jpegSize(size: Int, u8: (Int) -> Int, be16: (Int) -> Int): Pair<Int, Int>? {
        var i = 2
        while (i + 3 < size) {
            if (u8(i) != 0xFF) return null
            val marker = u8(i + 1)
            when {
                marker == 0xFF -> { i++; continue } // fill byte
                marker == 0x01 || marker in 0xD0..0xD7 -> { i += 2; continue } // no length
                marker == 0xD9 || marker == 0xDA -> return null // end of image, start of scan
            }
            val length = be16(i + 2)
            if (length < 2) return null
            val isStartOfFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isStartOfFrame) {
                if (i + 8 >= size) return null
                return be16(i + 7) to be16(i + 5)
            }
            i += 2 + length
        }
        return null
    }

    private class GlbChunks(val json: Pair<Int, Int>, val bin: Pair<Int, Int>?)

    /** The JSON and BIN chunks of a GLB, as a start and a length each; `null` when not a GLB. */
    private fun glbChunks(bytes: ByteBuffer): GlbChunks? {
        val start = bytes.position()
        if (bytes.remaining() < GLB_HEADER_SIZE + CHUNK_HEADER_SIZE || bytes.getInt(start) != GLB_MAGIC) return null
        val chunks = mutableListOf<Triple<Int, Int, Int>>() // type, from, length
        var at = start + GLB_HEADER_SIZE
        while (at.toLong() + CHUNK_HEADER_SIZE <= bytes.limit()) {
            val length = bytes.getInt(at)
            val from = at + CHUNK_HEADER_SIZE
            if (length < 0 || from.toLong() + length > bytes.limit()) break
            chunks += Triple(bytes.getInt(at + 4), from, length)
            at = from + length
        }
        val json = chunks.firstOrNull()?.takeIf { it.first == CHUNK_JSON } ?: return GlbChunks(start to 0, null)
        val bin = chunks.getOrNull(1)?.takeIf { it.first == CHUNK_BIN }
        return GlbChunks(json.second to json.third, bin?.let { it.second to it.third })
    }

    private fun Buffer?.asLittleEndian(): ByteBuffer? =
        (this as? ByteBuffer)?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN)

    private inline fun JSONArray?.forEachObject(action: (index: Int, item: JSONObject) -> Unit) {
        if (this == null) return
        for (index in 0 until length()) optJSONObject(index)?.let { action(index, it) }
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

    private const val GLB_MAGIC = 0x46546C67 // "glTF", little-endian
    private const val CHUNK_JSON = 0x4E4F534A // "JSON"
    private const val CHUNK_BIN = 0x004E4942 // "BIN\0"
    private const val GLB_HEADER_SIZE = 12
    private const val CHUNK_HEADER_SIZE = 8
    private val PNG = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG = intArrayOf(0xFF, 0xD8, 0xFF)
    private val KTX2 = intArrayOf(0xAB, 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, 0xBB, 0x0D, 0x0A, 0x1A, 0x0A)
}
