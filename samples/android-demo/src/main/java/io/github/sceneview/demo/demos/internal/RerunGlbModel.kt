package io.github.sceneview.demo.demos.internal

import java.util.Base64

/*
 * Placed models merged into a `.glb` export: reading a model's GLB, appending its shared
 * resources once, instancing its node tree under each anchor. The iOS demo's `merge`,
 * `instantiate`, `GLBFile` and their helpers in `RerunGLBWriter.swift`.
 */

/**
 * A model merged into the output: its shared resources are already appended, its nodes, skins
 * and animations are copied per anchor by [instantiate]. [bounds] is its axis-aligned box in its
 * own scene space, `null` when it has no readable `POSITION` bounds.
 */
internal class RerunMergedModel(
    val nodes: List<JsonMap>,
    val roots: List<Int>,
    val skins: List<JsonMap>,
    val animations: List<JsonMap>,
    val accessorBase: Int,
    val meshBase: Int,
    val cameraBase: Int,
    val lightBase: Int,
    val bounds: Pair<Vec3, Vec3>?,
)

/** A parsed GLB: its JSON chunk and its optional BIN chunk. */
internal class RerunGlbFile(val json: JsonMap, val bin: ByteArray?) {
    companion object {
        private const val MAGIC = 0x46546C67L
        private const val JSON_CHUNK = 0x4E4F534AL
        private const val BIN_CHUNK = 0x004E4942L
        private const val HEADER = 12
        private const val MIN_LENGTH = 20

        /** [data] read as a GLB; throws [RerunGlbWriter.InvalidModel] (naming [name]) otherwise. */
        fun parse(data: ByteArray, name: String): RerunGlbFile {
            fun fail(reason: String): Nothing = throw RerunGlbWriter.InvalidModel(name, reason)
            fun word(offset: Int): Long = (0 until 4).fold(0L) { acc, i ->
                acc or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
            }
            if (data.size < MIN_LENGTH || word(0) != MAGIC) fail("not a GLB file")
            if (word(4) != 2L) fail("glTF version ${word(4)}, expected 2")
            val length = word(8)
            if (length < MIN_LENGTH || length > data.size) fail("truncated file")
            var json: JsonMap? = null
            var bin: ByteArray? = null
            var offset = HEADER
            while (offset + 8 <= length) {
                val chunkLength = word(offset)
                val type = word(offset + 4)
                val start = offset + 8
                if (chunkLength > length - start) fail("truncated chunk")
                val chunk = data.copyOfRange(start, start + chunkLength.toInt())
                if (type == JSON_CHUNK && json == null) {
                    json = readJson(chunk) ?: fail("unreadable JSON chunk")
                } else if (type == BIN_CHUNK && bin == null) {
                    bin = chunk
                }
                offset = start + chunkLength.toInt()
            }
            return RerunGlbFile(json ?: fail("no JSON chunk"), bin)
        }

        @Suppress("UNCHECKED_CAST") // a JSON object parses to a String-keyed map
        private fun readJson(chunk: ByteArray): JsonMap? =
            runCatching { RerunJson.parse(String(chunk, Charsets.UTF_8)) as? JsonMap }.getOrNull()
    }
}

// Merge

/**
 * Appends [file]'s buffers, images, textures, samplers, materials, accessors, meshes, cameras and
 * lights once. `null` when it requires an extension outside [RerunGlbWriter.MERGEABLE_EXTENSIONS].
 */
internal fun RerunGltfBuilder.merge(file: RerunGlbFile, name: String): RerunMergedModel? {
    val mergeable = RerunGlbWriter.MERGEABLE_EXTENSIONS
    val required = strings(file.json["extensionsRequired"])
    if (!required.all { it in mergeable }) return null
    @Suppress("UNCHECKED_CAST") // stripping keeps an object an object
    val json = stripExtensions(file.json, mergeable) as JsonMap

    val viewBase = mergeBufferViews(json, file.bin, name)
    val samplerBase = samplers.size
    samplers += json.objects("samplers")
    val imageBase = mergeImages(json, viewBase, name)
    val textureBase = mergeTextures(json, imageBase, samplerBase)
    val materialBase = materials.size
    @Suppress("UNCHECKED_CAST") // shifting keeps an object an object
    json.objects("materials").forEach { materials += shiftTextureInfos(it, textureBase) as JsonMap }
    val accessorBase = mergeAccessors(json, viewBase)
    val meshBase = mergeMeshes(json, accessorBase, materialBase, viewBase)
    val cameraBase = cameras.size
    cameras += json.objects("cameras")
    val lightBase = lights.size
    json.obj("extensions")?.obj("KHR_lights_punctual")?.let { lights += it.objects("lights") }

    extensionsUsed += strings(json["extensionsUsed"]).filter { it in mergeable }
    extensionsUsed += required
    extensionsRequired += required
    val credit = jsonOf("model" to name)
    json.obj("asset")?.let { credit["asset"] = it }
    credits += credit

    val modelNodes = json.objects("nodes")
    val roots = modelRoots(json, modelNodes)
    return RerunMergedModel(
        nodes = modelNodes,
        roots = roots,
        skins = json.objects("skins"),
        animations = json.objects("animations"),
        accessorBase = accessorBase,
        meshBase = meshBase,
        cameraBase = cameraBase,
        lightBase = lightBase,
        bounds = modelBounds(json, modelNodes, roots),
    )
}

private fun RerunGltfBuilder.mergeBufferViews(json: JsonMap, bin: ByteArray?, name: String): Int {
    val buffers = json.objects("buffers").mapIndexed { index, buffer ->
        val uri = buffer["uri"] as? String
        if (uri != null) decodeDataUri(uri) else if (index == 0) bin else null
    }
    val viewBase = bufferViews.size
    for (view in json.objects("bufferViews")) {
        val data = (view["buffer"] as? Int)?.let { buffers.getOrNull(it) }
            ?: throw RerunGlbWriter.InvalidModel(name, "a buffer view points outside the file")
        val offset = view["byteOffset"] as? Int ?: 0
        val length = view["byteLength"] as? Int ?: -1
        if (offset < 0 || length < 0 || offset.toLong() + length > data.size) {
            throw RerunGlbWriter.InvalidModel(name, "a buffer view is out of range")
        }
        addBufferView(data.copyOfRange(offset, offset + length), view)
    }
    return viewBase
}

private fun RerunGltfBuilder.mergeImages(json: JsonMap, viewBase: Int, name: String): Int {
    val imageBase = images.size
    for (source in json.objects("images")) {
        val image = LinkedHashMap(source)
        val uri = image["uri"] as? String
        if (image["bufferView"] != null) {
            shift(image, "bufferView", viewBase)
        } else if (uri != null && !uri.startsWith("data:")) {
            throw RerunGlbWriter.InvalidModel(name, "image $uri is external")
        }
        images += image
    }
    return imageBase
}

private fun RerunGltfBuilder.mergeTextures(json: JsonMap, imageBase: Int, samplerBase: Int): Int {
    val textureBase = textures.size
    for (source in json.objects("textures")) {
        val texture = LinkedHashMap(source)
        shift(texture, "source", imageBase)
        shift(texture, "sampler", samplerBase)
        texture.obj("extensions")?.let { extensions ->
            // KHR_texture_basisu, EXT_texture_webp, EXT_texture_avif: `source` is an image.
            texture["extensions"] = extensions.mapValuesTo(LinkedHashMap()) { (_, value) ->
                @Suppress("UNCHECKED_CAST") // JSON objects are String-keyed
                (value as? JsonMap)?.let { LinkedHashMap(it).also { copy -> shift(copy, "source", imageBase) } }
                    ?: value
            }
        }
        textures += texture
    }
    return textureBase
}

private fun RerunGltfBuilder.mergeAccessors(json: JsonMap, viewBase: Int): Int {
    val accessorBase = accessors.size
    for (source in json.objects("accessors")) {
        val accessor = LinkedHashMap(source)
        shift(accessor, "bufferView", viewBase)
        accessor.obj("sparse")?.let { sparse ->
            val copy = LinkedHashMap(sparse)
            for (key in listOf("indices", "values")) {
                copy.obj(key)?.let { part ->
                    copy[key] = LinkedHashMap(part).also { shift(it, "bufferView", viewBase) }
                }
            }
            accessor["sparse"] = copy
        }
        accessors += accessor
    }
    return accessorBase
}

private fun RerunGltfBuilder.mergeMeshes(json: JsonMap, accessorBase: Int, materialBase: Int, viewBase: Int): Int {
    val meshBase = meshes.size
    for (source in json.objects("meshes")) {
        val mesh = LinkedHashMap(source)
        mesh["primitives"] = source.objects("primitives").map { original ->
            val primitive = LinkedHashMap(original)
            shiftedValues(primitive["attributes"], accessorBase)?.let { primitive["attributes"] = it }
            shift(primitive, "indices", accessorBase)
            shift(primitive, "material", materialBase)
            (primitive["targets"] as? List<*>)?.let { targets ->
                val shifted = targets.map { shiftedValues(it, accessorBase) }
                if (shifted.all { it != null }) primitive["targets"] = shifted
            }
            primitive.obj("extensions")?.let { extensions ->
                extensions.obj("KHR_draco_mesh_compression")?.let { draco ->
                    val copy = LinkedHashMap(extensions)
                    copy["KHR_draco_mesh_compression"] = LinkedHashMap(draco).also { shift(it, "bufferView", viewBase) }
                    primitive["extensions"] = copy
                }
            }
            primitive
        }
        meshes += mesh
    }
    return meshBase
}

private fun modelRoots(json: JsonMap, modelNodes: List<JsonMap>): List<Int> {
    val scenes = json.objects("scenes")
    val roots = if (scenes.isEmpty()) {
        val children = modelNodes.flatMap { it.ints("children") ?: emptyList() }.toSet()
        modelNodes.indices.filter { it !in children }
    } else {
        val index = json["scene"] as? Int ?: 0
        (scenes.getOrNull(index) ?: scenes[0]).ints("nodes") ?: emptyList()
    }
    return roots.filter { it in modelNodes.indices }
}

// Instances

/**
 * Copies [model]'s node tree (and the skins and animations that point into it) and returns the
 * copy's roots. Meshes, accessors and images stay shared.
 */
internal fun RerunGltfBuilder.instantiate(model: RerunMergedModel): List<Int> {
    val nodeBase = nodes.size
    val skinBase = skins.size
    for (source in model.nodes) nodes += instanceNode(source, model, nodeBase, skinBase)
    for (source in model.skins) {
        val skin = LinkedHashMap(source)
        shift(skin, "inverseBindMatrices", model.accessorBase)
        shift(skin, "skeleton", nodeBase)
        skin.ints("joints")?.let { joints -> skin["joints"] = joints.map { it + nodeBase } }
        skins += skin
    }
    for (source in model.animations) {
        val animation = LinkedHashMap(source)
        animation["samplers"] = source.objects("samplers").map { sampler ->
            LinkedHashMap(sampler).also {
                shift(it, "input", model.accessorBase)
                shift(it, "output", model.accessorBase)
            }
        }
        animation["channels"] = source.objects("channels").map { channel ->
            LinkedHashMap(channel).also { copy ->
                copy.obj("target")?.let { target ->
                    copy["target"] = LinkedHashMap(target).also { shift(it, "node", nodeBase) }
                }
            }
        }
        animations += animation
    }
    return model.roots.map { it + nodeBase }
}

private fun instanceNode(source: JsonMap, model: RerunMergedModel, nodeBase: Int, skinBase: Int): JsonMap {
    val node = LinkedHashMap(source)
    node.ints("children")?.let { children -> node["children"] = children.map { it + nodeBase } }
    shift(node, "mesh", model.meshBase)
    shift(node, "camera", model.cameraBase)
    shift(node, "skin", skinBase)
    node.obj("extensions")?.let { original ->
        val extensions = LinkedHashMap(original)
        extensions.obj("KHR_lights_punctual")?.let { light ->
            extensions["KHR_lights_punctual"] = LinkedHashMap(light).also { shift(it, "light", model.lightBase) }
        }
        extensions.obj("EXT_mesh_gpu_instancing")?.let { instancing ->
            shiftedValues(instancing["attributes"], model.accessorBase)?.let { attributes ->
                extensions["EXT_mesh_gpu_instancing"] = LinkedHashMap(instancing).also { it["attributes"] = attributes }
            }
        }
        node["extensions"] = extensions
    }
    return node
}

// Bounds

/**
 * The bounds of every mesh under [roots], through the node transforms, from the `min` / `max`
 * glTF requires on each `POSITION` accessor.
 */
internal fun modelBounds(json: JsonMap, nodes: List<JsonMap>, roots: List<Int>): Pair<Vec3, Vec3>? {
    val accessors = json.objects("accessors")
    val meshes = json.objects("meshes")
    val low = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    val high = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
    fun include(world: FloatArray, a: FloatArray, b: FloatArray) {
        for (corner in 0 until 8) {
            val p = floatArrayOf(
                if (corner and 1 == 0) a[0] else b[0],
                if (corner and 2 == 0) a[1] else b[1],
                if (corner and 4 == 0) a[2] else b[2],
            )
            val w = Mat4.transform(world, p)
            for (axis in 0 until 3) {
                low[axis] = minOf(low[axis], w[axis])
                high[axis] = maxOf(high[axis], w[axis])
            }
        }
    }
    fun visit(index: Int, parent: FloatArray, depth: Int) {
        if (depth >= MAX_NODE_DEPTH || index !in nodes.indices) return
        val node = nodes[index]
        val world = Mat4.multiply(parent, Mat4.local(node))
        val mesh = (node["mesh"] as? Int)?.let { meshes.getOrNull(it) }
        for (primitive in mesh?.objects("primitives").orEmpty()) {
            val position = primitive.obj("attributes")?.get("POSITION") as? Int
            val accessor = position?.let { accessors.getOrNull(it) }
            val a = vector3(accessor?.get("min"))
            val b = vector3(accessor?.get("max"))
            if (a != null && b != null) include(world, a, b)
        }
        node.ints("children")?.forEach { visit(it, world, depth + 1) }
    }
    roots.forEach { visit(it, Mat4.IDENTITY, 0) }
    val extent = maxOf(high[0] - low[0], high[1] - low[1], high[2] - low[2])
    if (!low[0].isFinite() || !high[0].isFinite() || !(extent > 0f)) return null
    return Vec3(low[0], low[1], low[2]) to Vec3(high[0], high[1], high[2])
}

private const val MAX_NODE_DEPTH = 64

private fun vector3(value: Any?): FloatArray? = numbers(value, 3)

private fun numbers(value: Any?, count: Int): FloatArray? {
    val list = value as? List<*> ?: return null
    if (list.size != count) return null
    return list.map { (it as? Number)?.toFloat() ?: return null }.toFloatArray()
}

/** Column-major 4×4 matrices, as glTF stores them. */
private object Mat4 {
    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    fun multiply(a: FloatArray, b: FloatArray): FloatArray = FloatArray(16) { i ->
        val column = i / 4
        val row = i % 4
        (0 until 4).fold(0f) { sum, k -> sum + a[k * 4 + row] * b[column * 4 + k] }
    }

    fun transform(m: FloatArray, p: FloatArray): FloatArray = FloatArray(3) { row ->
        m[row] * p[0] + m[4 + row] * p[1] + m[8 + row] * p[2] + m[12 + row]
    }

    /** A node's local transform: its `matrix`, else translation × rotation × scale. */
    fun local(node: JsonMap): FloatArray {
        numbers(node["matrix"], 16)?.let { return it }
        val t = vector3(node["translation"]) ?: floatArrayOf(0f, 0f, 0f)
        val s = vector3(node["scale"]) ?: floatArrayOf(1f, 1f, 1f)
        val r = numbers(node["rotation"], 4)?.let(::rotation) ?: IDENTITY
        val m = r.copyOf()
        for (column in 0 until 3) for (row in 0 until 3) m[column * 4 + row] *= s[column]
        m[12] = t[0]
        m[13] = t[1]
        m[14] = t[2]
        return m
    }

    private fun rotation(q: FloatArray): FloatArray {
        val x = q[0]
        val y = q[1]
        val z = q[2]
        val w = q[3]
        return floatArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0f,
            2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0f,
            2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0f,
            0f, 0f, 0f, 1f,
        )
    }
}

// JSON helpers

/** Adds [offset] to the integer at [key], if there is one. */
private fun shift(target: JsonMap, key: String, offset: Int) {
    (target[key] as? Int)?.let { target[key] = it + offset }
}

/** An object of integers with [offset] added to each; `null` when [value] is not one. */
private fun shiftedValues(value: Any?, offset: Int): JsonMap? {
    val map = value as? Map<*, *> ?: return null
    val result = jsonOf()
    for ((key, index) in map) result[key as String] = (index as? Int ?: return null) + offset
    return result
}

private fun strings(value: Any?): List<String> = (value as? List<*>)?.filterIsInstance<String>() ?: emptyList()

/**
 * Re-bases every texture reference of a material: core and `KHR_materials_*` texture infos all
 * sit under a key ending in `Texture` (`baseColorTexture`, `clearcoatNormalTexture`…).
 */
private fun shiftTextureInfos(value: Any?, offset: Int): Any? = when (value) {
    is List<*> -> value.map { shiftTextureInfos(it, offset) }
    is Map<*, *> -> {
        val result = jsonOf()
        for ((key, child) in value) {
            val shifted = shiftTextureInfos(child, offset)
            @Suppress("UNCHECKED_CAST") // shiftTextureInfos returns a String-keyed map for a map
            if ((key as String).endsWith("Texture") && shifted is Map<*, *>) shift(shifted as JsonMap, "index", offset)
            result[key] = shifted
        }
        result
    }
    else -> value
}

/** Drops every `extensions` entry outside [kept], at any depth. */
private fun stripExtensions(value: Any?, kept: Set<String>): Any? = when (value) {
    is List<*> -> value.map { stripExtensions(it, kept) }
    is Map<*, *> -> {
        val result = jsonOf()
        for ((key, child) in value) {
            if (key == "extensions" && child is Map<*, *>) {
                keptExtensions(child, kept).takeIf { it.isNotEmpty() }?.let { result["extensions"] = it }
            } else {
                result[key as String] = stripExtensions(child, kept)
            }
        }
        result
    }
    else -> value
}

/** The entries of an `extensions` object named in [kept], each stripped in turn. */
private fun keptExtensions(extensions: Map<*, *>, kept: Set<String>): JsonMap {
    val filtered = jsonOf()
    for ((name, extension) in extensions) {
        if (name in kept) filtered[name as String] = stripExtensions(extension, kept)
    }
    return filtered
}

/** The bytes of a base64 `data:` URI. */
private fun decodeDataUri(uri: String): ByteArray? {
    val comma = uri.indexOf(',')
    if (!uri.startsWith("data:") || comma < 0 || !uri.substring(0, comma).endsWith(";base64")) return null
    return runCatching { Base64.getDecoder().decode(uri.substring(comma + 1)) }.getOrNull()
}
