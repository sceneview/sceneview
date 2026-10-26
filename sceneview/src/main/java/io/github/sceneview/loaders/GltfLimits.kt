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
 *   included), on every node — gltfio also instantiates the root nodes no scene lists — and of each
 *   such texture only the source gltfio picks — `EXT_texture_webp`, then
 *   `KHR_texture_basisu`, then the plain `source`. A PNG fallback next to a KTX2 is never decoded.
 *   PNG, JPEG and KTX2 headers are read, from the first 64 KiB of a `data:` URI; WebP is not (Filament's Android build decodes none, so it
 *   builds no texture for it, and [ModelLoader] re-encodes WebP to PNG before this check anyway).
 * - **An accessor, sparse accessor or buffer view that reaches past its buffer view or buffer, or
 *   starts at a negative offset, and an external or `data:` buffer shorter than the `byteLength` it
 *   declares.** `cgltf_validate` checks these only in Filament's debug builds (`#ifndef NDEBUG`,
 *   gltfio `Utility.cpp`); the release build
 *   reads `accessor.offset + bufferView.offset` and on, as far as the counts say, out of the
 *   buffer. Every accessor is checked, used or not, as `cgltf_validate` does: a file with one that
 *   reaches past its data is invalid glTF. Sparse indices are also checked against the accessor's
 *   count when their bytes are at hand, since cgltf writes each value at its index.
 * - **A buffer `cgltf_load_buffers` cannot load**: a `.glb` whose BIN chunk is shorter than the
 *   `byteLength` of buffer 0, a `data:` buffer that is not base64 or holds a character base64 does
 *   not have within the bytes it declares — a JSON escape included, since cgltf never unescapes a
 *   string —, and a buffer with an absolute (`://`) URI, read or not: cgltf stops at it, and
 *   refusing it outright is simpler than tracking which buffers after it a view still reads.
 *   gltfio ignores the error that returns (`ResourceLoader.cpp`): that buffer, and those after
 *   it, keep no data, and the vertex upload reads from a null pointer.
 * - **A buffer a buffer view reads that cgltf leaves without data**: one with no `uri`, other than
 *   buffer 0 of a `.glb` that has a BIN chunk (an `EXT_meshopt_compression` fallback, never read,
 *   aside), one whose `uri` is empty, and one whose percent-encoded `uri` decodes to bytes a
 *   resource cannot be fed under — not UTF-8, a NUL, or a character outside the BMP. Any other
 *   percent-encoded `uri` is fed by [ModelLoader] under the decoded name cgltf looks up as well,
 *   unless a resource has that name already: cgltf then gets that one's bytes, and its length is
 *   the one checked against the buffer. Nor is it when another buffer `uri` decodes to the same
 *   name (`a%20b.bin` and `a%20%62.bin`): gltfio would keep the last one fed for both, so neither
 *   is, and such a buffer is refused too.
 * - **A primitive whose attributes or morph targets disagree on their vertex count, and an index
 *   accessor that is not a scalar of unsigned bytes, shorts or ints, tightly packed.** gltfio sizes
 *   the vertex and morph target buffers from the first attribute and uploads every other one at its
 *   own count; `cgltf_validate` rejects both, in debug builds only.
 * - **An index past the vertex count, when the index bytes are at hand** — the BIN chunk of a
 *   `.glb`, or an external buffer already read. gltfio's tangent computation reads, on the CPU, the
 *   vertex each index names; `cgltf_validate`, which checks them, runs in debug builds only.
 * - **An animation sampler whose keyframe times are in no buffer view.** gltfio's `Animator` reads
 *   them through `input->buffer_view` without checking it. A sampler whose output count does not
 *   match its keyframes is not refused: the `Animator` checks that in every build and leaves the
 *   model's animations off, without crashing.
 * - **An animation of the morph weights of a mesh with more than [MAX_MORPH_TARGETS] targets.**
 *   gltfio's `Animator` hands `RenderableManager::setMorphWeights` one weight per target, and that
 *   requires at most `CONFIG_MAX_MORPH_TARGET_COUNT` (256): the first frame of the animation aborts.
 *
 * The JSON is never parsed whole: one pass over its bytes first cuts each `data:` URI to its
 * header, its decoded length and its first 64 KiB, so a model with embedded textures costs a few
 * hundred KiB of heap here, not its size again (see [reducedJson]). What that drops is not checked.
 *
 * Not checked:
 * - indices and sparse indices stored in a `data:` buffer, and the images such a buffer holds;
 * - the bytes of a buffer view compressed with `EXT_meshopt_compression` (its range is a
 *   fallback, often empty). The cgltf of Filament 1.72.1 parses no `KHR_meshopt_compression`, so
 *   a view compressed with it is measured like any other;
 * - a `data:` URI whose header escapes its comma (`\u002C`);
 * - a buffer `uri` that holds a JSON escape (`\uXXXX`, `\/`): it is checked unescaped, while
 *   cgltf looks the raw string up;
 * - an `EXT_meshopt_compression` fallback buffer whose `uri` is empty or cannot be fed: no view
 *   reads it as it is, so it is not refused;
 * - a `uri` that holds a character outside the BMP as it is, not percent-encoded: JNI feeds it in
 *   modified UTF-8, under a name cgltf does not look up;
 * - an external buffer whose resource is not at hand yet: [ModelLoader] resolves it before the load;
 * - iridescence and anisotropy textures: gltfio 1.72.1 binds neither, so it never decodes them;
 * - WebP image sizes, and anything that fails while a texture is decoded rather than built.
 *
 * Filament-free and JVM-testable (`org.json` is an Android API, so its test runs on Robolectric). A
 * payload that is not glTF, or whose JSON `org.json` cannot read, passes: gltfio reports it the way
 * it always has. One too large or too deeply nested to read at all — an `OutOfMemoryError` or a
 * `StackOverflowError` from the parse — is refused rather than handed to gltfio unchecked, and so is
 * one a check throws on: it is reported as a malformed glTF, never thrown to the caller.
 */
internal object GltfLimits {

    /** Filament's `CONFIG_MAX_BONE_COUNT`: the most joints one skinned renderable can have. */
    const val MAX_SKIN_JOINTS = 256

    /** Filament's `CONFIG_MAX_MORPH_TARGET_COUNT`: the most morph weights one renderable takes. */
    const val MAX_MORPH_TARGETS = 256

    /**
     * Parses [source] once, for [Parsed.violations] to check as its external resources arrive.
     *
     * Costs one pass over the JSON's bytes and one JSON parse of what is left once each `data:` URI
     * is cut to its head (see [reducedJson]): keep it off the main thread where the caller can.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun parse(source: Buffer): Parsed {
        val bytes = (source as? ByteBuffer)?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN) ?: return Parsed.NOT_GLTF
        val chunks = glbChunks(bytes)
        val (jsonFrom, jsonLength) = chunks?.json ?: (bytes.position() to bytes.remaining())
        return try {
            Parsed(JSONObject(reducedJson(bytes, jsonFrom, jsonLength)), bytes, chunks?.bin, null)
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
        @Suppress("TooGenericExceptionCaught")
        fun violations(maxTextureSize: Int, resource: (uri: String) -> Buffer? = { null }): List<String> {
            if (unreadable != null) return listOf(unreadable)
            val json = json ?: return emptyList()
            // A value no rule foresaw must not escape as an exception the caller does not expect.
            return try {
                val gltf = Gltf(json, glb, bin, resource)
                val violations = mutableListOf<String>()
                gltf.checkBuffers(violations)
                gltf.checkAccessors(violations)
                gltf.checkPrimitives(violations)
                gltf.checkSkins(violations)
                gltf.checkAnimations(violations)
                if (maxTextureSize > 0) gltf.checkTextures(maxTextureSize, violations)
                violations
            } catch (e: RuntimeException) {
                listOf("malformed glTF: ${e.message ?: e.javaClass.simpleName}")
            }
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
        /**
         * The bytes cgltf gets for the buffer [uri]: those under the name it decodes [uri] to when
         * there are any — a resource of that name, or [uri]'s own, which [ModelLoader] feeds under
         * it too unless [uri] is [ambiguouslyDecoded] — else [uri]'s own.
         */
        fun bufferResource(uri: String): Buffer? = GltfBufferUris.cgltfKey(uri)?.let(resource) ?: resource(uri)

        val accessors = json.optJSONArray("accessors")
        val bufferViews = json.optJSONArray("bufferViews")
        val buffers = json.optJSONArray("buffers")
        val nodes = json.optJSONArray("nodes")
        val meshes = json.optJSONArray("meshes")

        /**
         * The buffer `uri`s whose decoded name another buffer `uri` decodes to as well, and that no
         * resource has as its own `uri`: [ModelLoader] feeds none of them under it, since gltfio
         * keeps only the last resource fed under one name, so cgltf finds no data for them.
         */
        val ambiguouslyDecoded: Set<String> by lazy {
            fun JSONArray?.uris() = buildList { forEachObject { _, item -> (item.opt("uri") as? String)?.let(::add) } }
            val bufferUris = buffers.uris().filterNot { it.startsWith("data:") }.toSet()
            val names = bufferUris + json.optJSONArray("images").uris()
            bufferUris
                .mapNotNull { uri -> GltfBufferUris.cgltfKey(uri)?.takeIf { it !in names }?.let { it to uri } }
                .groupBy({ it.first }, { it.second })
                .values.filter { it.size > 1 }
                .flatten().toSet()
        }

        /**
         * Each buffer `cgltf_load_buffers` would leave without data, each buffer shorter than it
         * declares when its bytes are at hand, and each buffer view against its buffer.
         */
        fun checkBuffers(violations: MutableList<String>) {
            val viewed = mutableSetOf<Int>()
            bufferViews.forEachObject { _, view -> viewed += view.optInt("buffer", -1) }
            buffers.forEachObject { index, buffer ->
                val declared = buffer.optLong("byteLength", 0)
                // As cgltf reads it: no `uri` is NULL, `""` is a file name; a `uri` that is not a
                // string fails cgltf's parse, which gltfio reports.
                val uri = if (buffer.has("uri")) buffer.opt("uri") as? String ?: return@forEachObject else null
                when {
                    uri == null -> checkUnnamedBuffer(index, buffer, declared, index in viewed, violations)
                    uri.startsWith("data:") -> checkDataBuffer(index, uri, declared, violations)
                    // cgltf loads no buffer at all past one it refuses, and it refuses these.
                    "://" in uri -> violations += "buffer $index has an absolute URI, which cgltf does not load"
                    uri.isEmpty() -> if (index in viewed && declared > 0) {
                        violations += "buffer $index has an empty uri, which names no resource"
                    }
                    GltfBufferUris.isUnfeedable(uri) -> if (index in viewed && declared > 0) {
                        violations += "buffer $index ($uri) decodes to a name no resource can be fed under: " +
                            "cgltf leaves it without data"
                    }
                    uri in ambiguouslyDecoded -> if (index in viewed && declared > 0) {
                        violations += "buffer $index ($uri) decodes to ${GltfBufferUris.cgltfKey(uri)}, as another " +
                            "buffer uri does: cgltf leaves it without data"
                    }
                    else -> {
                        val length = bufferResource(uri)?.remaining()?.toLong() ?: return@forEachObject
                        if (length < declared) {
                            violations += "buffer $index ($uri) is $length bytes, shorter than the $declared it declares"
                        }
                    }
                }
            }
            bufferViews.forEachObject { index, view ->
                val buffer = buffers?.optJSONObject(view.optInt("buffer", -1)) ?: return@forEachObject
                if (view.optLong("byteOffset", 0) < 0 || view.optLong("byteLength", 0) < 0) {
                    violations += "buffer view $index has a negative byteOffset or byteLength"
                    return@forEachObject
                }
                val end = sum(view.optLong("byteOffset", 0), view.optLong("byteLength", 0))
                val length = buffer.optLong("byteLength", 0)
                if (end == null || end > length) {
                    violations += "buffer view $index reaches byte ${end ?: "∞"} of a $length-byte buffer"
                }
            }
        }

        /**
         * A buffer with no `uri`. Only buffer 0 of a `.glb` with a BIN chunk has data: cgltf requires
         * the chunk to hold all of it — shorter, and it loads no buffer at all. Any other is left
         * without data, which a buffer view must not read; only a meshopt fallback, never read, may.
         */
        private fun checkUnnamedBuffer(
            index: Int,
            buffer: JSONObject,
            declared: Long,
            viewed: Boolean,
            violations: MutableList<String>,
        ) {
            val isGlb = glb != null && glb.remaining() >= Int.SIZE_BYTES && glb.getInt(glb.position()) == GLB_MAGIC
            val binLength = bin?.second?.toLong()
            when {
                index == 0 && isGlb && binLength != null -> if (binLength < declared) {
                    violations += "the BIN chunk of this GLB is $binLength bytes, shorter than the $declared " +
                        "buffer 0 declares"
                }
                !viewed || declared <= 0 || buffer.isMeshoptFallback() -> Unit
                index == 0 && isGlb -> violations += "this GLB has no BIN chunk for the $declared bytes of buffer 0"
                else -> violations += "buffer $index has no uri and is not the BIN chunk of a GLB: cgltf leaves it " +
                    "without data"
            }
        }

        /**
         * A `data:` buffer, which cgltf decodes only from base64, exactly the `byteLength` it
         * declares, refusing any character base64 does not have — padding included — on the way.
         */
        private fun checkDataBuffer(index: Int, uri: String, declared: Long, violations: MutableList<String>) {
            val length = dataUriLength(uri)
            val invalidAt = dataUriInvalidAt(uri)
            // cgltf reads six bits a character, as many characters as the declared bytes take.
            val read = product(declared, 4)?.let { (it + 2) / 3 } ?: Long.MAX_VALUE
            when {
                length == null -> violations += "buffer $index is a data: URI that is not base64"
                invalidAt != null && invalidAt < read ->
                    violations += "buffer $index is a data: URI that is not valid base64 at character $invalidAt"
                length < declared ->
                    violations += "buffer $index (data: URI) is $length bytes, shorter than the $declared it declares"
            }
        }

        private fun JSONObject.isMeshoptFallback(): Boolean {
            val extensions = optJSONObject("extensions") ?: return false
            return extensions.optJSONObject(MESHOPT)?.optBoolean("fallback") == true
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
                if (accessor.optLong("byteOffset", 0) < 0) {
                    violations += "accessor $index has a negative byteOffset"
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
            if (indices.optLong("byteOffset", 0) < 0 || values.optLong("byteOffset", 0) < 0) {
                violations += "the sparse part of accessor $index has a negative byteOffset"
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
                    } else if (vertexCount != null && counts.all { it == vertexCount }) {
                        val bound = indexBound(indices) ?: return@forEachObject
                        if (bound >= vertexCount) violations += "$name indexes vertex $bound of its $vertexCount"
                    }
                }
            }
        }

        /**
         * The largest value of an index accessor, read as `cgltf_validate` does, or `null` when its
         * bytes are not at hand: tangents are computed on the CPU from these, vertex by vertex.
         */
        private fun indexBound(indices: JSONObject): Long? {
            val bytes = bufferViewBytes(bufferView(indices.optInt("bufferView", -1)) ?: return null) ?: return null
            val size = componentSize(indices.optInt("componentType"))
            val count = indices.optLong("count", 0)
            // A negative offset or an overflowing end is a violation of checkAccessors, not read here.
            val end = sum(indices.optLong("byteOffset", 0), product(count, size.toLong())) ?: return null
            if (count <= 0 || end > bytes.remaining()) return null
            val at = bytes.position() + indices.optLong("byteOffset", 0).toInt()
            var bound = 0L
            for (i in 0 until count.toInt()) {
                val value = when (size) {
                    1 -> bytes.get(at + i).toLong() and 0xFF
                    2 -> bytes.getShort(at + 2 * i).toLong() and 0xFFFF
                    else -> bytes.getInt(at + 4 * i).toLong() and 0xFFFFFFFFL
                }
                if (value > bound) bound = value
            }
            return bound
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

        /**
         * Each sampler's keyframe times are in a buffer view, which gltfio's `Animator` reads
         * without checking; and an animated mesh hands one morph weight per target to Filament,
         * which takes 256.
         *
         * Not the output count of a sampler: the `Animator` checks it in release builds too, and
         * only leaves the animations off when it is wrong.
         */
        fun checkAnimations(violations: MutableList<String>) {
            val animated = sortedSetOf<Int>()
            json.optJSONArray("animations").forEachObject { animationIndex, animation ->
                // The Animator builds every sampler, used by a channel or not.
                animation.optJSONArray("samplers").forEachObject { samplerIndex, sampler ->
                    val input = accessors?.optJSONObject(sampler.optInt("input", -1)) ?: return@forEachObject
                    if (!input.has("bufferView")) {
                        violations += "the keyframe times of sampler $samplerIndex of animation $animationIndex " +
                            "are in no buffer view"
                    }
                }
                // An output with no buffer view is read as zeros (cgltf_accessor_unpack_floats): no crash.
                animation.optJSONArray("channels").forEachObject { _, channel ->
                    val target = channel.optJSONObject("target") ?: return@forEachObject
                    if (target.optString("path") != "weights") return@forEachObject
                    val mesh = nodes?.optJSONObject(target.optInt("node", -1))?.optInt("mesh", -1) ?: return@forEachObject
                    if (meshes?.optJSONObject(mesh) != null) animated += mesh
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

        /**
         * The bytes of [view], when its buffer is at hand and it lies within it: never those of a
         * `data:` buffer, which [reducedJson] cut to its head, nor a meshopt-compressed range.
         */
        private fun bufferViewBytes(view: JSONObject): ByteBuffer? {
            // A meshopt-compressed view's own range is a fallback, often left empty or zeroed.
            val extensions = view.optJSONObject("extensions")
            if (extensions != null && extensions.has(MESHOPT)) return null
            val bufferIndex = view.optInt("buffer", -1)
            val buffer = buffers?.optJSONObject(bufferIndex) ?: return null
            val bufferUri = buffer.optString("uri")
            val (data, base, limit) = when {
                bufferUri.isEmpty() && bufferIndex == 0 && glb != null && bin != null ->
                    Triple(glb, bin.first, bin.first + bin.second)
                bufferUri.isNotEmpty() && !bufferUri.startsWith("data:") -> {
                    val external = bufferResource(bufferUri).asLittleEndian() ?: return null
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

    /**
     * The decoded length [reducedJson] wrote into a base64 `data:` URI's header: what cgltf can
     * decode, six bits a character up to the padding, in whole bytes. `null` when not base64.
     */
    private fun dataUriLength(uri: String): Long? {
        val comma = uri.indexOf(',')
        if (comma < 0 || !uri.regionMatches(comma - BASE64.length, BASE64, 0, BASE64.length)) return null
        val from = uri.lastIndexOf(DECODED_LENGTH, comma - BASE64.length)
        if (from < 0) return null
        return uri.substring(from + DECODED_LENGTH.length, comma - BASE64.length).toLongOrNull()
    }

    /**
     * Where [reducedJson] found the first character of a base64 `data:` URI's payload that base64
     * does not have, `=` included; `null` when there is none, or when the URI is not base64.
     */
    private fun dataUriInvalidAt(uri: String): Long? {
        val comma = uri.indexOf(',')
        if (comma < 0 || !uri.regionMatches(comma - BASE64.length, BASE64, 0, BASE64.length)) return null
        val lengthFrom = uri.lastIndexOf(DECODED_LENGTH, comma - BASE64.length)
        if (lengthFrom < 0) return null
        val from = uri.lastIndexOf(INVALID_AT, lengthFrom)
        if (from < 0) return null
        return uri.substring(from + INVALID_AT.length, lengthFrom).toLongOrNull()?.takeIf { it >= 0 }
    }

    /**
     * The glTF JSON in `length` bytes of [bytes] at `from`, each `"data:…"` string cut to its
     * header and the first [DATA_URI_HEAD_CHARS] characters of its payload, in one pass.
     *
     * A model that embeds its buffers and textures in base64 is mostly payload: read whole, a 50 MB
     * `.gltf` would be copied as bytes, as a `String` and again by `org.json`'s tokenizer, on the
     * Java heap, only for the checks to read a header out of it. The decoded length of each base64
     * payload is kept in its header, with where its first character outside the base64 alphabet is
     * (`-1` for none), as `;invalid-at=I;decoded-length=N;base64,`, for [dataUriLength] and
     * [dataUriInvalidAt]. The payload is counted as cgltf reads it, raw: cgltf never unescapes a
     * JSON string, so a backslash is a character its base64 decoder refuses. In the kept head, `\/`
     * is unescaped, for the image headers read from it, and other escapes are kept whole. A byte
     * order mark before the JSON is dropped.
     */
    internal fun reducedJson(bytes: ByteBuffer, from: Int, length: Int): String {
        val end = from + length
        val out = Bytes(minOf(length, 1 shl 16))
        var i = from
        if (length >= 3 && bytes.get(i) == 0xEF.toByte() && bytes.get(i + 1) == 0xBB.toByte() && bytes.get(i + 2) == 0xBF.toByte()) i += 3
        fun isDataUri(at: Int) = at + DATA.size <= end && DATA.indices.all { bytes.get(at + it) == DATA[it] }
        while (i < end) {
            val byte = bytes.get(i++)
            out.add(byte)
            if (byte != QUOTE) continue
            if (isDataUri(i)) {
                i = reduceDataUri(bytes, i, end, out)
                continue
            }
            // Any other string, copied as is: only its end matters.
            while (i < end) {
                val c = bytes.get(i++)
                out.add(c)
                if (c == BACKSLASH && i < end) out.add(bytes.get(i++)) else if (c == QUOTE) break
            }
        }
        return String(out.array, 0, out.size, Charsets.UTF_8)
    }

    /** Writes the `data:` string at [start], past its opening quote, cut to its head; returns where it ends. */
    private fun reduceDataUri(bytes: ByteBuffer, start: Int, end: Int, out: Bytes): Int {
        val header = Bytes(64)
        // The last bytes of the whole header: `;base64` is read there even when the header is cut.
        val tail = ByteArray(BASE64_BYTES.size)
        fun pushTail(byte: Byte) {
            System.arraycopy(tail, 1, tail, 0, tail.size - 1)
            tail[tail.size - 1] = byte
        }
        var i = start
        var closed = false
        while (i < end) {
            val c = bytes.get(i++)
            if (c == QUOTE || c == COMMA) {
                closed = c == QUOTE
                break
            }
            // An escape is kept whole or dropped whole: half of one would escape the closing quote.
            val escaped = if (c == BACKSLASH && i < end) bytes.get(i++) else null
            if (header.size + (if (escaped == null) 1 else 2) <= DATA_URI_HEADER_MAX) {
                header.add(c)
                escaped?.let(header::add)
            }
            pushTail(c)
            escaped?.let(::pushTail)
        }
        val isBase64 = tail.contentEquals(BASE64_BYTES)
        if (isBase64 && !header.endsWith(BASE64_BYTES)) header.addAll(BASE64_BYTES)
        out.addAll(header)
        if (closed || i >= end) {
            if (closed) out.add(QUOTE)
            return i
        }
        val head = Bytes(minOf(DATA_URI_HEAD_CHARS, end - i) + 8)
        var chars = 0L
        var invalidAt = -1L
        var last = 0.toByte()
        var beforeLast = 0.toByte()
        fun count(c: Byte, valid: Boolean) {
            if (!valid && invalidAt < 0) invalidAt = chars
            chars++
            beforeLast = last
            last = c
        }
        while (i < end) {
            val c = bytes.get(i++)
            if (c == QUOTE) {
                closed = true
                break
            }
            val keep = chars < DATA_URI_HEAD_CHARS
            val escaped = if (c == BACKSLASH && i < end) bytes.get(i++) else null
            if (escaped == null) {
                if (keep) head.add(c)
                count(c, isBase64Char(c.toInt()))
                continue
            }
            // cgltf never unescapes a JSON string: it decodes the backslash itself, which base64
            // does not have. The head keeps `\/` unescaped for the image headers read from it.
            if (keep) {
                if (escaped != SLASH) head.add(BACKSLASH)
                head.add(escaped)
            }
            count(BACKSLASH, false)
            count(escaped, isBase64Char(escaped.toInt()))
            repeat(if (escaped == LETTER_U) minOf(4, end - i) else 0) {
                bytes.get(i++).let {
                    if (keep) head.add(it)
                    count(it, isBase64Char(it.toInt()))
                }
            }
        }
        if (isBase64) {
            out.size -= BASE64_BYTES.size
            // Six bits a character up to the padding, in whole bytes: 6 characters are 4 bytes.
            val padding = if (last != EQUALS) 0 else if (beforeLast == EQUALS) 2 else 1
            val decoded = (chars - minOf(chars, padding.toLong())) * 3 / 4
            out.addAll(INVALID_AT.toByteArray())
            out.addAll(invalidAt.toString().toByteArray())
            out.addAll(DECODED_LENGTH.toByteArray())
            out.addAll(decoded.toString().toByteArray())
            out.addAll(BASE64_BYTES)
        }
        out.add(COMMA)
        out.addAll(head)
        if (closed) out.add(QUOTE)
        return i
    }

    /** Whether [code] is a character of the base64 alphabet, padding excluded. */
    private fun isBase64Char(code: Int) =
        code in 'A'.code..'Z'.code || code in 'a'.code..'z'.code || code in '0'.code..'9'.code ||
            code == '+'.code || code == '/'.code

    /** A growable byte array: [reducedJson]'s output, without the copies of a stream. */
    private class Bytes(capacity: Int) {
        var array = ByteArray(maxOf(capacity, 16))
        var size = 0

        fun add(byte: Byte) {
            if (size == array.size) array = array.copyOf(array.size * 2)
            array[size++] = byte
        }

        fun addAll(bytes: ByteArray) = bytes.forEach(::add)

        fun addAll(bytes: Bytes) {
            for (i in 0 until bytes.size) add(bytes.array[i])
        }

        fun endsWith(suffix: ByteArray) =
            size >= suffix.size && suffix.indices.all { array[size - suffix.size + it] == suffix[it] }
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
    private val BASE64_BYTES = BASE64.toByteArray()
    private const val DECODED_LENGTH = ";decoded-length="
    private const val INVALID_AT = ";invalid-at="
    private const val DATA_URI_HEAD_CHARS = 64 * 1024 // 48 KiB decoded, a multiple of 4 characters
    private const val DATA_URI_HEADER_MAX = 1024
    private val DATA = "data:".toByteArray()
    private const val QUOTE: Byte = 0x22
    private const val BACKSLASH: Byte = 0x5C
    private const val SLASH: Byte = 0x2F
    private const val COMMA: Byte = 0x2C
    private const val EQUALS: Byte = 0x3D
    private const val LETTER_U: Byte = 0x75
    // The only meshopt extension the cgltf of Filament 1.72.1 parses: a `KHR_meshopt_compression`
    // view reads its fallback buffer as it is, and one with no `uri` holds no data.
    private const val MESHOPT = "EXT_meshopt_compression"
    private const val GLB_MAGIC = 0x46546C67 // "glTF", little-endian
    private const val CHUNK_JSON = 0x4E4F534A // "JSON"
    private const val CHUNK_BIN = 0x004E4942 // "BIN\0"
    private const val GLB_HEADER_SIZE = 12
    private const val CHUNK_HEADER_SIZE = 8
    private val PNG = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG = intArrayOf(0xFF, 0xD8, 0xFF)
    private val KTX2 = intArrayOf(0xAB, 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, 0xBB, 0x0D, 0x0A, 0x1A, 0x0A)
}
