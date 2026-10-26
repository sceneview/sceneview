package io.github.sceneview.loaders

import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
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
 * What in a glTF file Filament's release build would crash on instead of refusing, read from the
 * file before gltfio sees it.
 *
 * Every rule here guards a native crash no Java `try` reaches: a failed Filament precondition is a
 * `PreconditionPanic`, an `abort()` of the whole process on Android, and a read past the end of a
 * buffer is a `SIGSEGV`.
 *
 * - **A skin of more than [MAX_SKIN_JOINTS] joints on a node with a mesh.** gltfio passes
 *   `skinning(joints_count)` as is, and `RenderableManager::Builder::build` requires at most
 *   `CONFIG_MAX_BONE_COUNT` (256), inside `AssetLoader.createAsset`. A skin no mesh node uses is
 *   never built and is not counted.
 * - **A texture wider or taller than the GPU's largest.** The texture providers build the
 *   `Texture` at the image's size, and `Texture::Builder::build` requires both to fit
 *   `Texture.getMaxTextureSize`, inside `ResourceLoader.asyncBeginLoad`. Only the images gltfio
 *   decodes are measured: those of the textures a material of a mesh primitive binds (variants
 *   included), and of each such texture only the source gltfio picks — `EXT_texture_webp`, then
 *   `KHR_texture_basisu`, then the plain `source`. A PNG fallback next to a KTX2 is never decoded.
 *   PNG, JPEG and KTX2 headers are read; WebP is not (Filament's Android build decodes none, so it
 *   builds no texture for it, and [ModelLoader] re-encodes WebP to PNG before this check anyway).
 * - **An accessor, sparse accessor or buffer view that reaches past its buffer view or buffer, and
 *   an external buffer shorter than the `byteLength` it declares.** `cgltf_validate` checks these
 *   only in Filament's debug builds (`#ifndef NDEBUG`, gltfio `Utility.cpp`); the release build
 *   reads `accessor.offset + bufferView.offset` and on, as far as the counts say, out of the
 *   buffer. Every accessor is checked, used or not, as `cgltf_validate` does: a file with one that
 *   reaches past its data is invalid glTF. Sparse indices are also checked against the accessor's
 *   count when their bytes are at hand, since cgltf writes each value at its index.
 * - **A primitive whose attributes or morph targets disagree on their vertex count, and an index
 *   accessor that is not a scalar of unsigned bytes, shorts or ints, tightly packed.** gltfio sizes
 *   the vertex and morph target buffers from the first attribute and uploads every other one at its
 *   own count; `cgltf_validate` rejects both, in debug builds only.
 * - **An animation of the morph weights of a mesh with more than [MAX_MORPH_TARGETS] targets.**
 *   gltfio's `Animator` hands `RenderableManager::setMorphWeights` one weight per target, and that
 *   requires at most `CONFIG_MAX_MORPH_TARGET_COUNT` (256): the first frame of the animation aborts.
 *
 * Not checked: the values of an index buffer (a vertex index past the vertex count is read by the
 * GPU, not the CPU), and anything that fails while a texture is decoded rather than built.
 *
 * Filament-free and JVM-testable (`org.json` is an Android API, so its test runs on Robolectric). A
 * payload that is not glTF, or whose JSON `org.json` cannot read, passes: gltfio reports it the way
 * it always has. One too large or too deeply nested to read at all — an `OutOfMemoryError` or a
 * `StackOverflowError` from the parse — is refused rather than handed to gltfio unchecked.
 */
internal object GltfLimits {

    /** Filament's `CONFIG_MAX_BONE_COUNT`: the most joints one skinned renderable can have. */
    const val MAX_SKIN_JOINTS = 256

    /** Filament's `CONFIG_MAX_MORPH_TARGET_COUNT`: the most morph weights one renderable takes. */
    const val MAX_MORPH_TARGETS = 256

    /**
     * Parses [source] once, for [Parsed.violations] to check as its external resources arrive.
     *
     * Costs one JSON parse of the glTF: keep it off the main thread where the caller can.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun parse(source: Buffer): Parsed {
        val bytes = (source as? ByteBuffer)?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN) ?: return Parsed.NOT_GLTF
        val chunks = glbChunks(bytes)
        val (jsonFrom, jsonLength) = chunks?.json ?: (bytes.position() to bytes.remaining())
        return try {
            val text = ByteArray(jsonLength).also { bytes.duplicate().apply { position(jsonFrom) }.get(it) }
            Parsed(JSONObject(text.toString(Charsets.UTF_8).removePrefix("﻿")), bytes, chunks?.bin, null)
        } catch (_: JSONException) {
            Parsed.NOT_GLTF
        } catch (_: OutOfMemoryError) {
            Parsed(null, null, null, "its glTF JSON is too large to be checked (out of memory)")
        } catch (_: StackOverflowError) {
            Parsed(null, null, null, "its glTF JSON nests too deeply to be checked")
        }
    }

    /** [parse] then [Parsed.violations], for a single check. */
    fun violations(
        source: Buffer,
        maxTextureSize: Int,
        resource: (uri: String) -> Buffer? = { null },
    ): List<String> = parse(source).violations(maxTextureSize, resource)

    /** Throws a [GltfLimitException] when [source] exceeds Filament's limits, see [violations]. */
    fun check(source: Buffer, maxTextureSize: Int, resource: (uri: String) -> Buffer? = { null }) =
        parse(source).check(maxTextureSize, resource)

    /** A glTF read by [parse]. */
    class Parsed internal constructor(
        private val json: JSONObject?,
        private val glb: ByteBuffer?,
        private val bin: Pair<Int, Int>?,
        private val unreadable: String?,
    ) {

        /**
         * What exceeds Filament's limits, one sentence each.
         *
         * @param maxTextureSize the GPU's largest texture side; `0` or less skips the textures.
         * @param resource the external resource a relative URI names, when it was loaded already:
         * its length is checked against its buffer's, and its images are measured.
         */
        fun violations(maxTextureSize: Int, resource: (uri: String) -> Buffer? = { null }): List<String> {
            if (unreadable != null) return listOf(unreadable)
            val json = json ?: return emptyList()
            val gltf = Gltf(json, glb, bin, resource)
            val violations = mutableListOf<String>()
            gltf.checkBuffers(violations)
            gltf.checkAccessors(violations)
            gltf.checkPrimitives(violations)
            gltf.checkSkins(violations)
            gltf.checkMorphAnimations(violations)
            if (maxTextureSize > 0) gltf.checkTextures(maxTextureSize, violations)
            return violations
        }

        /** Throws a [GltfLimitException] when there is any [violation][violations]. */
        fun check(maxTextureSize: Int, resource: (uri: String) -> Buffer? = { null }) {
            val violations = violations(maxTextureSize, resource)
            if (violations.isNotEmpty()) throw GltfLimitException(violations)
        }

        internal companion object {
            val NOT_GLTF = Parsed(null, null, null, null)
        }
    }

    /** One check of a parsed glTF, against the resources at hand. */
    private class Gltf(
        val json: JSONObject,
        val glb: ByteBuffer?,
        val bin: Pair<Int, Int>?,
        val resource: (String) -> Buffer?,
    ) {
        val accessors = json.optJSONArray("accessors")
        val bufferViews = json.optJSONArray("bufferViews")
        val buffers = json.optJSONArray("buffers")
        val nodes = json.optJSONArray("nodes")
        val meshes = json.optJSONArray("meshes")

        /** Each buffer view against its buffer, and each loaded external buffer against its length. */
        fun checkBuffers(violations: MutableList<String>) {
            buffers.forEachObject { index, buffer ->
                val uri = buffer.optString("uri")
                if (uri.isEmpty() || uri.startsWith("data:")) return@forEachObject
                val data = resource(uri) ?: return@forEachObject
                val declared = buffer.optLong("byteLength", 0)
                if (data.remaining() < declared) {
                    violations += "buffer $index ($uri) is ${data.remaining()} bytes, " +
                        "shorter than the $declared it declares"
                }
            }
            bufferViews.forEachObject { index, view ->
                val buffer = buffers?.optJSONObject(view.optInt("buffer", -1)) ?: return@forEachObject
                val end = sum(view.optLong("byteOffset", 0), view.optLong("byteLength", 0))
                val length = buffer.optLong("byteLength", 0)
                if (end == null || end > length) {
                    violations += "buffer view $index reaches byte ${end ?: "∞"} of a $length-byte buffer"
                }
            }
        }

        /** Each accessor, and its sparse part, against the buffer views it reads. */
        fun checkAccessors(violations: MutableList<String>) {
            accessors.forEachObject { index, accessor ->
                val componentSize = componentSize(accessor.optInt("componentType"))
                val components = components(accessor.optString("type"))
                if (componentSize == 0 || components == 0) {
                    violations += "accessor $index has an unknown componentType or type"
                    return@forEachObject
                }
                val elementSize = elementSize(accessor.optString("type"), componentSize, components)
                val count = accessor.optLong("count", 0)
                if (count < 0 || count > Int.MAX_VALUE) {
                    violations += "accessor $index has a count of $count"
                    return@forEachObject
                }
                bufferView(accessor.optInt("bufferView", -1))?.let { view ->
                    val stride = view.optLong("byteStride", 0).takeIf { it > 0 } ?: elementSize.toLong()
                    val end = if (count == 0L) 0L else sum(accessor.optLong("byteOffset", 0), product(stride, count - 1), elementSize.toLong())
                    val viewLength = view.optLong("byteLength", 0)
                    if (end == null || end > viewLength) {
                        violations += "accessor $index reaches byte ${end ?: "∞"} of a $viewLength-byte buffer view"
                    }
                }
                accessor.optJSONObject("sparse")?.let { checkSparse(index, it, count, elementSize, violations) }
            }
        }

        private fun checkSparse(index: Int, sparse: JSONObject, count: Long, elementSize: Int, violations: MutableList<String>) {
            val sparseCount = sparse.optLong("count", 0)
            val indices = sparse.optJSONObject("indices") ?: return
            val values = sparse.optJSONObject("values") ?: return
            val indexSize = componentSize(indices.optInt("componentType"))
            if (indexSize == 0 || indices.optInt("componentType") == FLOAT || indices.optInt("componentType") % 2 == 0) {
                violations += "the sparse indices of accessor $index are not unsigned integers"
                return
            }
            if (sparseCount < 0 || sparseCount > Int.MAX_VALUE) {
                violations += "the sparse part of accessor $index has a count of $sparseCount"
                return
            }
            val indexEnd = sum(indices.optLong("byteOffset", 0), product(indexSize.toLong(), sparseCount))
            val valueEnd = sum(values.optLong("byteOffset", 0), product(elementSize.toLong(), sparseCount))
            val indexView = bufferView(indices.optInt("bufferView", -1))
            val valueView = bufferView(values.optInt("bufferView", -1))
            if (indexView == null || valueView == null ||
                indexEnd == null || indexEnd > indexView.optLong("byteLength", 0) ||
                valueEnd == null || valueEnd > valueView.optLong("byteLength", 0)
            ) {
                violations += "the sparse part of accessor $index reaches past its buffer views"
                return
            }
            // cgltf writes each sparse value at its index: one past the accessor writes out of bounds.
            val bytes = bufferViewBytes(indexView) ?: return
            val at = bytes.position() + indices.optInt("byteOffset", 0)
            for (i in 0 until sparseCount.toInt()) {
                val target = when (indexSize) {
                    1 -> bytes.get(at + i).toLong() and 0xFF
                    2 -> bytes.getShort(at + 2 * i).toLong() and 0xFFFF
                    else -> bytes.getInt(at + 4 * i).toLong() and 0xFFFFFFFFL
                }
                if (target >= count) {
                    violations += "a sparse index of accessor $index is $target, past its $count elements"
                    return
                }
            }
        }

        /** Each primitive's attributes and morph targets agree on a vertex count; its indices are indices. */
        fun checkPrimitives(violations: MutableList<String>) {
            meshes.forEachObject { meshIndex, mesh ->
                mesh.optJSONArray("primitives").forEachObject { primitiveIndex, primitive ->
                    val name = "primitive $primitiveIndex of mesh $meshIndex"
                    val counts = primitive.optJSONObject("attributes").accessorCounts()
                    val vertexCount = counts.firstOrNull()
                    if (vertexCount != null && counts.any { it != vertexCount }) {
                        violations += "the attributes of $name disagree on their vertex count"
                    }
                    primitive.optJSONArray("targets").forEachObject { target, attributes ->
                        if (vertexCount != null && attributes.accessorCounts().any { it != vertexCount }) {
                            violations += "morph target $target of $name does not have its $vertexCount vertices"
                        }
                    }
                    val indices = accessors?.optJSONObject(primitive.optInt("indices", -1)) ?: return@forEachObject
                    val componentType = indices.optInt("componentType")
                    val stride = bufferView(indices.optInt("bufferView", -1))?.optLong("byteStride", 0) ?: 0
                    if (indices.optString("type") != "SCALAR" || componentType !in INDEX_TYPES ||
                        (stride > 0 && stride != componentSize(componentType).toLong())
                    ) {
                        violations += "the indices of $name are not tightly packed unsigned integers"
                    }
                }
            }
        }

        /** The skins gltfio builds: those of a node that also has a mesh. */
        fun checkSkins(violations: MutableList<String>) {
            val skins = json.optJSONArray("skins") ?: return
            val built = sortedSetOf<Int>()
            nodes.forEachObject { _, node ->
                if (node.has("mesh") && node.has("skin")) built += node.optInt("skin", -1)
            }
            for (index in built) {
                val joints = skins.optJSONObject(index)?.optJSONArray("joints")?.length() ?: continue
                if (joints > MAX_SKIN_JOINTS) {
                    violations += "skin $index has $joints joints, more than the $MAX_SKIN_JOINTS Filament can skin"
                }
            }
        }

        /** An animated mesh hands one morph weight per target to Filament, which takes 256. */
        fun checkMorphAnimations(violations: MutableList<String>) {
            val animated = sortedSetOf<Int>()
            json.optJSONArray("animations").forEachObject { _, animation ->
                animation.optJSONArray("channels").forEachObject { _, channel ->
                    val target = channel.optJSONObject("target") ?: return@forEachObject
                    if (target.optString("path") != "weights") return@forEachObject
                    nodes?.optJSONObject(target.optInt("node", -1))?.optInt("mesh", -1)?.let { animated += it }
                }
            }
            for (index in animated) {
                val primitive = meshes?.optJSONObject(index)?.optJSONArray("primitives")?.optJSONObject(0) ?: continue
                val targets = primitive.optJSONArray("targets")?.length() ?: 0
                if (targets > MAX_MORPH_TARGETS) {
                    violations += "mesh $index animates $targets morph targets, more than the " +
                        "$MAX_MORPH_TARGETS Filament can weigh"
                }
            }
        }

        /** The images gltfio decodes, see [GltfLimits]. */
        fun checkTextures(maxTextureSize: Int, violations: MutableList<String>) {
            val images = json.optJSONArray("images") ?: return
            for (index in decodedImages()) {
                val image = images.optJSONObject(index) ?: continue
                val pixels = imageBytes(image) ?: continue
                val (width, height) = imageSize(pixels) ?: continue
                if (width > maxTextureSize || height > maxTextureSize) {
                    val name = image.optString("uri").takeIf { it.isNotEmpty() && !it.startsWith("data:") }
                        ?: image.optString("name").takeIf { it.isNotEmpty() }
                    violations += "image $index${name?.let { " ($it)" }.orEmpty()} is ${width}x$height, " +
                        "larger than the ${maxTextureSize}x$maxTextureSize textures this GPU supports"
                }
            }
        }

        /** The image of each texture a material of a mesh primitive binds, as gltfio picks it. */
        private fun decodedImages(): Set<Int> {
            val materials = sortedSetOf<Int>()
            nodes.forEachObject { _, node ->
                val mesh = meshes?.optJSONObject(node.optInt("mesh", -1)) ?: return@forEachObject
                mesh.optJSONArray("primitives").forEachObject { _, primitive ->
                    if (primitive.has("material")) materials += primitive.optInt("material")
                    primitive.optJSONObject("extensions")?.optJSONObject("KHR_materials_variants")
                        ?.optJSONArray("mappings").forEachObject { _, mapping ->
                            if (mapping.has("material")) materials += mapping.optInt("material")
                        }
                }
            }
            val textures = json.optJSONArray("textures")
            val images = sortedSetOf<Int>()
            for (material in materials) {
                val textureInfos = json.optJSONArray("materials")?.optJSONObject(material)?.textureInfos() ?: continue
                for (info in textureInfos) {
                    val texture = textures?.optJSONObject(info.optInt("index", -1)) ?: continue
                    val extensions = texture.optJSONObject("extensions")
                    val source = extensions?.optJSONObject("EXT_texture_webp")?.optSource()
                        ?: extensions?.optJSONObject("KHR_texture_basisu")?.optSource()
                        ?: texture.optSource()
                        ?: continue
                    images += source
                }
            }
            return images
        }

        /**
         * The bytes of [image]: an external file by its URI, a `data:` URI's head, or a buffer
         * view into the GLB's BIN chunk or into an external buffer. `null` when they are not at hand.
         */
        private fun imageBytes(image: JSONObject): ByteBuffer? {
            val uri = image.optString("uri")
            if (uri.startsWith("data:")) return dataUriHead(uri)
            if (uri.isNotEmpty()) return resource(uri).asLittleEndian()
            return bufferViewBytes(bufferView(image.optInt("bufferView", -1)) ?: return null)
        }

        /** The bytes of [view], when its buffer is at hand and it lies within it. */
        private fun bufferViewBytes(view: JSONObject): ByteBuffer? {
            val bufferIndex = view.optInt("buffer", -1)
            val buffer = buffers?.optJSONObject(bufferIndex) ?: return null
            val bufferUri = buffer.optString("uri")
            val (data, base, limit) = when {
                bufferUri.isEmpty() && bufferIndex == 0 && glb != null && bin != null ->
                    Triple(glb, bin.first, bin.first + bin.second)
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
            }.slice().order(ByteOrder.LITTLE_ENDIAN)
        }

        private fun bufferView(index: Int): JSONObject? = bufferViews?.optJSONObject(index)

        private fun JSONObject?.accessorCounts(): List<Long> {
            if (this == null) return emptyList()
            return keys().asSequence().mapNotNull { accessors?.optJSONObject(optInt(it, -1))?.optLong("count", 0) }.toList()
        }
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

    /**
     * The first bytes of a base64 `data:` URI, enough for any image header: decoding all of an
     * embedded texture to read its size would cost as much as gltfio's own decode.
     */
    private fun dataUriHead(uri: String): ByteBuffer? {
        val comma = uri.indexOf(',')
        if (comma < 0 || !uri.regionMatches(comma - BASE64.length, BASE64, 0, BASE64.length)) return null
        val end = minOf(uri.length, comma + 1 + DATA_URI_HEAD_CHARS)
        return try {
            ByteBuffer.wrap(Base64.decode(uri.substring(comma + 1, end), Base64.DEFAULT))
        } catch (_: IllegalArgumentException) {
            null
        }
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

    /** Every texture reference of a material gltfio binds: core, then its extensions. */
    private fun JSONObject.textureInfos(): List<JSONObject> {
        val infos = mutableListOf<JSONObject>()
        optJSONObject("pbrMetallicRoughness")?.let { pbr ->
            pbr.optJSONObject("baseColorTexture")?.let(infos::add)
            pbr.optJSONObject("metallicRoughnessTexture")?.let(infos::add)
        }
        for (key in CORE_TEXTURES) optJSONObject(key)?.let(infos::add)
        val extensions = optJSONObject("extensions") ?: return infos
        for ((extension, keys) in EXTENSION_TEXTURES) {
            val ext = extensions.optJSONObject(extension) ?: continue
            for (key in keys) ext.optJSONObject(key)?.let(infos::add)
        }
        return infos
    }

    private fun JSONObject.optSource(): Int? = if (has("source")) optInt("source", -1).takeIf { it >= 0 } else null

    /** `componentType`'s size in bytes, or `0` when it is not one glTF defines. */
    private fun componentSize(componentType: Int) = when (componentType) {
        5120, 5121 -> 1
        5122, 5123 -> 2
        5125, FLOAT -> 4
        else -> 0
    }

    private fun components(type: String) = when (type) {
        "SCALAR" -> 1
        "VEC2" -> 2
        "VEC3" -> 3
        "VEC4", "MAT2" -> 4
        "MAT3" -> 9
        "MAT4" -> 16
        else -> 0
    }

    /** `cgltf_calc_size`: matrix columns of bytes and shorts are padded to four bytes. */
    private fun elementSize(type: String, componentSize: Int, components: Int) = when {
        type == "MAT2" && componentSize == 1 -> 8
        type == "MAT3" && (componentSize == 1 || componentSize == 2) -> 12 * componentSize
        else -> componentSize * components
    }

    private fun sum(vararg terms: Long?): Long? = try {
        terms.fold(0L) { total, term -> Math.addExact(total, term ?: return null) }.takeIf { terms.all { (it ?: 0) >= 0 } }
    } catch (_: ArithmeticException) {
        null
    }

    private fun product(a: Long, b: Long): Long? = try {
        Math.multiplyExact(a, b)
    } catch (_: ArithmeticException) {
        null
    }

    private fun Buffer?.asLittleEndian(): ByteBuffer? =
        (this as? ByteBuffer)?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN)

    private inline fun JSONArray?.forEachObject(action: (index: Int, item: JSONObject) -> Unit) {
        if (this == null) return
        for (index in 0 until length()) optJSONObject(index)?.let { action(index, it) }
    }

    private const val FLOAT = 5126
    private val INDEX_TYPES = setOf(5121, 5123, 5125)
    private val CORE_TEXTURES = listOf("normalTexture", "occlusionTexture", "emissiveTexture")
    private val EXTENSION_TEXTURES = listOf(
        "KHR_materials_pbrSpecularGlossiness" to listOf("diffuseTexture", "specularGlossinessTexture"),
        "KHR_materials_clearcoat" to listOf("clearcoatTexture", "clearcoatRoughnessTexture", "clearcoatNormalTexture"),
        "KHR_materials_sheen" to listOf("sheenColorTexture", "sheenRoughnessTexture"),
        "KHR_materials_transmission" to listOf("transmissionTexture"),
        "KHR_materials_volume" to listOf("thicknessTexture"),
        "KHR_materials_specular" to listOf("specularTexture", "specularColorTexture"),
    )
    private const val BASE64 = ";base64"
    private const val DATA_URI_HEAD_CHARS = 64 * 1024 // 48 KiB decoded, a multiple of 4 characters
    private const val GLB_MAGIC = 0x46546C67 // "glTF", little-endian
    private const val CHUNK_JSON = 0x4E4F534A // "JSON"
    private const val CHUNK_BIN = 0x004E4942 // "BIN\0"
    private const val GLB_HEADER_SIZE = 12
    private const val CHUNK_HEADER_SIZE = 8
    private val PNG = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG = intArrayOf(0xFF, 0xD8, 0xFF)
    private val KTX2 = intArrayOf(0xAB, 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, 0xBB, 0x0D, 0x0A, 0x1A, 0x0A)
}
