package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.JsonMap
import io.github.sceneview.demo.demos.internal.RerunJson
import io.github.sceneview.demo.demos.internal.RerunMeshGlb
import io.github.sceneview.demo.demos.internal.jsonOf
import io.github.sceneview.demo.demos.internal.objects
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The finished model as one glTF 2.0 binary any viewer opens.
 *
 * Textured, it holds two primitives over shared vertices: the faces a photo covers, sampling the
 * embedded JPEG atlas through `TEXCOORD_0`, and the rest with their vertex colours. Both are
 * unlit (`KHR_materials_unlit`): the photos already carry the room's own light, and a second
 * shading on top would darken every corner twice. Accessors 0–3 stay `POSITION`, `NORMAL`,
 * `COLOR_0` and every face's indices, as [RerunMeshGlb] writes them, so the app's own reader
 * (thumbnails, the share's floor origin) reads either kind.
 *
 * Without a texture it is [RerunMeshGlb]'s lit vertex-coloured model, unchanged.
 */
internal object ModelGlb {
    fun write(model: ModelMesh, encoder: AtlasEncoder?, generator: String = "SceneView Rerun"): ByteArray {
        val texture = model.texture
        val uv = model.uv
        val jpeg = if (texture != null && uv != null) {
            // An atlas the encoder refuses leaves the vertex colours; one the heap cannot encode is
            // the finish's to answer (CleanModel), not a texture silently gone.
            runCatching { encoder?.jpeg(texture.atlas, texture.width, texture.height) }
                .onFailure { if (it is OutOfMemoryError) throw it }
                .getOrNull()
        } else {
            null
        }
        if (texture == null || uv == null || jpeg == null) return RerunMeshGlb.write(model.asRerunMesh(), generator)
        return textured(model, uv, jpeg, generator)
    }

    /** Whether [glb] is a textured model [write] wrote: one a vertex-colour reader would strip. */
    fun isTextured(glb: ByteArray): Boolean = json(glb)?.let { (root, _) -> root.objects("images").isNotEmpty() } == true

    /**
     * [glb] as a share sends it: resting on its floor (its lowest point at Y = 0) and centred in
     * X/Z, by a translation on its one node — the binary chunk, texture included, is untouched.
     * `null` when [glb] is not one [write] wrote.
     */
    fun onFloor(glb: ByteArray): ByteArray? {
        val (root, binAt) = json(glb) ?: return null
        val nodes = root.objects("nodes")
        val position = root.objects("accessors").firstOrNull() ?: return null
        val min = (position["min"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: return null } ?: return null
        val max = (position["max"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: return null } ?: return null
        if (nodes.size != 1 || min.size != 3 || max.size != 3) return null
        nodes[0]["translation"] = listOf(-(min[0] + max[0]) / 2, -min[1], -(min[2] + max[2]) / 2)
        val bin = glb.copyOfRange(binAt + CHUNK_HEADER, glb.size)
        val binBytes = ByteBuffer.wrap(glb, binAt, CHUNK_HEADER).order(ByteOrder.LITTLE_ENDIAN).int
        if (binBytes > bin.size) return null
        return RerunMeshGlb.glb(RerunJson.write(root).toByteArray(Charsets.UTF_8), binBytes) { put(bin, 0, binBytes) }
    }

    /** [glb]'s JSON chunk and where its binary chunk's header starts; `null` for anything else. */
    @Suppress("UNCHECKED_CAST")
    private fun json(glb: ByteArray): Pair<JsonMap, Int>? = runCatching {
        val header = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        if (glb.size < GLB_HEADER + CHUNK_HEADER || header.getInt(0) != GLB_MAGIC) return null
        val jsonBytes = header.getInt(GLB_HEADER)
        if (header.getInt(GLB_HEADER + 4) != CHUNK_JSON) return null
        val jsonAt = GLB_HEADER + CHUNK_HEADER
        val root = RerunJson.parse(String(glb, jsonAt, jsonBytes, Charsets.UTF_8)) as? JsonMap ?: return null
        val binAt = jsonAt + jsonBytes
        if (binAt + CHUNK_HEADER > glb.size || header.getInt(binAt + 4) != CHUNK_BIN) return null
        root to binAt
    }.getOrNull()

    private fun textured(model: ModelMesh, uv: FloatArray, jpeg: ByteArray, generator: String): ByteArray {
        val n = model.vertexCount
        val shortIndices = n <= MAX_SHORT_INDEX
        val indexSize = if (shortIndices) 2 else 4
        val positionBytes = n * 12
        val normalBytes = n * 12
        val colorBytes = n * 4
        val indexBytes = pad4((model.texturedIndices.size + model.plainIndices.size) * indexSize)
        val uvBytes = n * 8
        val imageBytes = jpeg.size
        var at = 0
        fun next(length: Int): Int = at.also { at += length }
        val positionAt = next(positionBytes)
        val normalAt = next(normalBytes)
        val colorAt = next(colorBytes)
        val indexAt = next(indexBytes)
        val uvAt = next(uvBytes)
        val imageAt = next(pad4(imageBytes))
        val binBytes = at
        val bounds = model.asBounds()
        val indexType = if (shortIndices) UNSIGNED_SHORT else UNSIGNED_INT
        val all = model.texturedIndices.size + model.plainIndices.size
        val accessors = mutableListOf(
            jsonOf(
                "bufferView" to 0, "componentType" to FLOAT, "count" to n, "type" to "VEC3",
                "min" to listOf(bounds[0], bounds[1], bounds[2]).map(Float::toDouble),
                "max" to listOf(bounds[3], bounds[4], bounds[5]).map(Float::toDouble),
            ),
            jsonOf("bufferView" to 1, "componentType" to FLOAT, "count" to n, "type" to "VEC3"),
            jsonOf("bufferView" to 2, "componentType" to UNSIGNED_BYTE, "normalized" to true, "count" to n, "type" to "VEC4"),
            jsonOf("bufferView" to 3, "componentType" to indexType, "count" to all, "type" to "SCALAR"),
            jsonOf("bufferView" to 4, "componentType" to FLOAT, "count" to n, "type" to "VEC2"),
            jsonOf("bufferView" to 3, "componentType" to indexType, "count" to model.texturedIndices.size, "type" to "SCALAR"),
        )
        val primitives = mutableListOf(
            jsonOf(
                "attributes" to jsonOf("POSITION" to 0, "NORMAL" to 1, "TEXCOORD_0" to 4),
                "indices" to if (model.plainIndices.isEmpty()) 3 else 5,
                "material" to 0,
                "mode" to TRIANGLES,
            ),
        )
        if (model.plainIndices.isNotEmpty()) {
            accessors += jsonOf(
                "bufferView" to 3, "byteOffset" to model.texturedIndices.size * indexSize,
                "componentType" to indexType, "count" to model.plainIndices.size, "type" to "SCALAR",
            )
            primitives += jsonOf(
                "attributes" to jsonOf("POSITION" to 0, "NORMAL" to 1, "COLOR_0" to 2),
                "indices" to 6,
                "material" to 1,
                "mode" to TRIANGLES,
            )
        }
        if (model.plainIndices.isEmpty()) accessors.removeAt(accessors.size - 1)
        val json = jsonOf(
            "asset" to jsonOf("version" to "2.0", "generator" to generator),
            "extensionsUsed" to listOf(UNLIT),
            "scene" to 0,
            "scenes" to listOf(jsonOf("nodes" to listOf(0))),
            "nodes" to listOf(jsonOf("name" to "room", "mesh" to 0)),
            "meshes" to listOf(jsonOf("name" to "room", "primitives" to primitives)),
            "materials" to listOf(
                material("room photo", jsonOf("baseColorTexture" to jsonOf("index" to 0))),
                material("room colour", jsonOf()),
            ),
            "textures" to listOf(jsonOf("sampler" to 0, "source" to 0)),
            "samplers" to listOf(
                jsonOf(
                    "magFilter" to LINEAR, "minFilter" to LINEAR_MIPMAP_LINEAR,
                    "wrapS" to CLAMP_TO_EDGE, "wrapT" to CLAMP_TO_EDGE,
                ),
            ),
            "images" to listOf(jsonOf("bufferView" to 5, "mimeType" to "image/jpeg")),
            "buffers" to listOf(jsonOf("byteLength" to binBytes)),
            "bufferViews" to listOf(
                view(positionAt, positionBytes, ARRAY_BUFFER, 12),
                view(normalAt, normalBytes, ARRAY_BUFFER, 12),
                view(colorAt, colorBytes, ARRAY_BUFFER, 4),
                view(indexAt, all * indexSize, ELEMENT_ARRAY_BUFFER, null),
                view(uvAt, uvBytes, ARRAY_BUFFER, 8),
                jsonOf("buffer" to 0, "byteOffset" to imageAt, "byteLength" to imageBytes),
            ),
            "accessors" to accessors,
        )
        val bytes = RerunJson.write(json).toByteArray(Charsets.UTF_8)
        return RerunMeshGlb.glb(bytes, binBytes) {
            model.positions.forEach { putFloat(it) }
            model.normals.forEach { putFloat(it) }
            for (c in model.colors) {
                put(RerunMeshGlb.LINEAR[(c shr 16) and 0xFF])
                put(RerunMeshGlb.LINEAR[(c shr 8) and 0xFF])
                put(RerunMeshGlb.LINEAR[c and 0xFF])
                put(0xFF.toByte())
            }
            for (i in model.texturedIndices) if (shortIndices) putShort(i.toShort()) else putInt(i)
            for (i in model.plainIndices) if (shortIndices) putShort(i.toShort()) else putInt(i)
            repeat(indexBytes - all * indexSize) { put(0) }
            uv.forEach { putFloat(it) }
            put(jpeg)
            repeat(pad4(imageBytes) - imageBytes) { put(0) }
        }
    }

    private fun material(name: String, pbr: JsonMap): JsonMap {
        pbr["metallicFactor"] = 0.0
        pbr["roughnessFactor"] = 1.0
        return jsonOf("name" to name, "pbrMetallicRoughness" to pbr, "extensions" to jsonOf(UNLIT to jsonOf()))
    }

    private fun view(offset: Int, length: Int, target: Int, stride: Int?): JsonMap =
        jsonOf("buffer" to 0, "byteOffset" to offset, "byteLength" to length, "target" to target).also {
            if (stride != null) it["byteStride"] = stride
        }

    private fun ModelMesh.asBounds(): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in positions.indices) {
            val axis = i % 3
            b[axis] = minOf(b[axis], positions[i])
            b[axis + 3] = maxOf(b[axis + 3], positions[i])
        }
        return b
    }

    private fun pad4(size: Int) = (size + 3) and 3.inv()

    const val UNLIT = "KHR_materials_unlit"

    private const val GLB_MAGIC = 0x46546C67
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942
    private const val GLB_HEADER = 12
    private const val CHUNK_HEADER = 8

    // 65535 is the reserved primitive-restart value and is forbidden in glTF indices.
    private const val MAX_SHORT_INDEX = 65535
    private const val ARRAY_BUFFER = 34962
    private const val ELEMENT_ARRAY_BUFFER = 34963
    private const val FLOAT = 5126
    private const val UNSIGNED_BYTE = 5121
    private const val UNSIGNED_SHORT = 5123
    private const val UNSIGNED_INT = 5125
    private const val TRIANGLES = 4
    private const val LINEAR = 9729
    private const val LINEAR_MIPMAP_LINEAR = 9987
    private const val CLAMP_TO_EDGE = 33071
}
