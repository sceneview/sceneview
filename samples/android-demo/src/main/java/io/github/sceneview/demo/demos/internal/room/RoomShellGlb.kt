package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.RerunJson
import io.github.sceneview.demo.demos.internal.RerunMeshGlb
import io.github.sceneview.demo.demos.internal.jsonOf
import io.github.sceneview.demo.demos.internal.model.AtlasEncoder
import io.github.sceneview.demo.demos.internal.model.ModelGlb

/**
 * A [RoomShell]'s faces as one glTF 2.0 binary: one mesh of five quads, each its own primitive
 * with its own photo texture (JPEG), unlit (`KHR_materials_unlit`: the photos carry the room's
 * light), single-sided and wound to face into the room — so orbiting from outside, the walls
 * between the eye and the room are culled and the room reads as a dollhouse. World space, the
 * scan's own, metres, Y up.
 *
 * Accessors 0–3 are `POSITION`, `NORMAL`, `COLOR_0` (each face's mean colour) and every face's
 * indices, as [RerunMeshGlb] writes them, so the app's own readers (framing, ground, thumbnails)
 * read it as they read a model.
 */
internal object RoomShellGlb {
    /** The `.glb` of [faces], `null` when [encoder] cannot encode one of their textures. */
    fun write(faces: List<ShellFace>, encoder: AtlasEncoder, generator: String = "SceneView room shell"): ByteArray? {
        require(faces.isNotEmpty() && faces.all { it.argb.size == it.columns * it.rows }) { "faces without textures" }
        val jpegs = faces.map { encoder.jpeg(it.argb, it.columns, it.rows) ?: return null }
        val n = faces.size * CORNERS
        val positions = FloatArray(n * 3)
        val normals = FloatArray(n * 3)
        val uvs = FloatArray(n * 2)
        val indices = IntArray(faces.size * QUAD_INDICES)
        val corner = FloatArray(3)
        faces.forEachIndexed { f, face ->
            // Corners (0, 0), (1, 0), (1, 1), (0, 1) along u and v; the texture's top row is v's far end.
            for (k in 0 until CORNERS) {
                val su = if (k == 1 || k == 2) 1f else 0f
                val sv = if (k >= 2) 1f else 0f
                face.at(su * face.lengthU, sv * face.lengthV, corner)
                val v = f * CORNERS + k
                for (c in 0 until 3) {
                    positions[v * 3 + c] = corner[c]
                    normals[v * 3 + c] = face.normal[c]
                }
                uvs[v * 2] = su
                uvs[v * 2 + 1] = 1f - sv
            }
            // Counter-clockwise seen from the side the normal points to: the front face.
            val cross = cross(face.u, face.v)
            val front = cross[0] * face.normal[0] + cross[1] * face.normal[1] + cross[2] * face.normal[2] > 0f
            val order = if (front) FRONT else BACK
            for (k in 0 until QUAD_INDICES) indices[f * QUAD_INDICES + k] = f * CORNERS + order[k]
        }
        val positionBytes = n * 12
        val normalBytes = n * 12
        val colorBytes = n * 4
        val indexBytes = pad4(indices.size * 2)
        val uvBytes = n * 8
        var at = 0
        fun next(length: Int): Int = at.also { at += length }
        val positionAt = next(positionBytes)
        val normalAt = next(normalBytes)
        val colorAt = next(colorBytes)
        val indexAt = next(indexBytes)
        val uvAt = next(uvBytes)
        val imageAt = jpegs.map { next(pad4(it.size)) }
        val binBytes = at
        val min = FloatArray(3) { Float.MAX_VALUE }
        val max = FloatArray(3) { -Float.MAX_VALUE }
        for (i in positions.indices) {
            min[i % 3] = minOf(min[i % 3], positions[i])
            max[i % 3] = maxOf(max[i % 3], positions[i])
        }
        val accessors = mutableListOf(
            jsonOf(
                "bufferView" to 0, "componentType" to FLOAT, "count" to n, "type" to "VEC3",
                "min" to min.map(Float::toDouble), "max" to max.map(Float::toDouble),
            ),
            jsonOf("bufferView" to 1, "componentType" to FLOAT, "count" to n, "type" to "VEC3"),
            jsonOf("bufferView" to 2, "componentType" to UNSIGNED_BYTE, "normalized" to true, "count" to n, "type" to "VEC4"),
            jsonOf("bufferView" to 3, "componentType" to UNSIGNED_SHORT, "count" to indices.size, "type" to "SCALAR"),
            jsonOf("bufferView" to 4, "componentType" to FLOAT, "count" to n, "type" to "VEC2"),
        )
        faces.indices.forEach { f ->
            accessors += jsonOf(
                "bufferView" to 3, "byteOffset" to f * QUAD_INDICES * 2,
                "componentType" to UNSIGNED_SHORT, "count" to QUAD_INDICES, "type" to "SCALAR",
            )
        }
        val primitives = faces.indices.map { f ->
            jsonOf(
                "attributes" to jsonOf("POSITION" to 0, "NORMAL" to 1, "TEXCOORD_0" to 4),
                "indices" to FIRST_FACE_ACCESSOR + f,
                "material" to f,
                "mode" to TRIANGLES,
            )
        }
        val views = mutableListOf(
            view(positionAt, positionBytes, ARRAY_BUFFER, 12),
            view(normalAt, normalBytes, ARRAY_BUFFER, 12),
            view(colorAt, colorBytes, ARRAY_BUFFER, 4),
            view(indexAt, indices.size * 2, ELEMENT_ARRAY_BUFFER, null),
            view(uvAt, uvBytes, ARRAY_BUFFER, 8),
        )
        jpegs.forEachIndexed { f, jpeg -> views += jsonOf("buffer" to 0, "byteOffset" to imageAt[f], "byteLength" to jpeg.size) }
        val json = jsonOf(
            "asset" to jsonOf("version" to "2.0", "generator" to generator),
            "extensionsUsed" to listOf(ModelGlb.UNLIT),
            "scene" to 0,
            "scenes" to listOf(jsonOf("nodes" to listOf(0))),
            "nodes" to listOf(jsonOf("name" to "room", "mesh" to 0)),
            "meshes" to listOf(jsonOf("name" to "room", "primitives" to primitives)),
            "materials" to faces.mapIndexed { f, face ->
                jsonOf(
                    "name" to face.name,
                    "pbrMetallicRoughness" to jsonOf(
                        "baseColorTexture" to jsonOf("index" to f),
                        "metallicFactor" to 0.0,
                        "roughnessFactor" to 1.0,
                    ),
                    "extensions" to jsonOf(ModelGlb.UNLIT to jsonOf()),
                )
            },
            "textures" to faces.indices.map { jsonOf("sampler" to 0, "source" to it) },
            "samplers" to listOf(
                jsonOf("magFilter" to LINEAR, "minFilter" to LINEAR_MIPMAP_LINEAR, "wrapS" to CLAMP_TO_EDGE, "wrapT" to CLAMP_TO_EDGE),
            ),
            "images" to faces.indices.map { jsonOf("bufferView" to FIRST_IMAGE_VIEW + it, "mimeType" to "image/jpeg") },
            "buffers" to listOf(jsonOf("byteLength" to binBytes)),
            "bufferViews" to views,
            "accessors" to accessors,
        )
        val bytes = RerunJson.write(json).toByteArray(Charsets.UTF_8)
        return RerunMeshGlb.glb(bytes, binBytes) {
            positions.forEach { putFloat(it) }
            normals.forEach { putFloat(it) }
            for (face in faces) {
                val c = face.meanColor
                repeat(CORNERS) {
                    put(RerunMeshGlb.LINEAR[(c shr 16) and 0xFF])
                    put(RerunMeshGlb.LINEAR[(c shr 8) and 0xFF])
                    put(RerunMeshGlb.LINEAR[c and 0xFF])
                    put(0xFF.toByte())
                }
            }
            indices.forEach { putShort(it.toShort()) }
            repeat(indexBytes - indices.size * 2) { put(0) }
            uvs.forEach { putFloat(it) }
            for (jpeg in jpegs) {
                put(jpeg)
                repeat(pad4(jpeg.size) - jpeg.size) { put(0) }
            }
        }
    }

    private fun cross(a: FloatArray, b: FloatArray) =
        floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun view(offset: Int, length: Int, target: Int, stride: Int?) =
        jsonOf("buffer" to 0, "byteOffset" to offset, "byteLength" to length, "target" to target).also {
            if (stride != null) it["byteStride"] = stride
        }

    private fun pad4(size: Int) = (size + 3) and 3.inv()

    private const val CORNERS = 4
    private const val QUAD_INDICES = 6
    private val FRONT = intArrayOf(0, 1, 2, 0, 2, 3)
    private val BACK = intArrayOf(0, 2, 1, 0, 3, 2)
    private const val FIRST_FACE_ACCESSOR = 5
    private const val FIRST_IMAGE_VIEW = 5
    private const val ARRAY_BUFFER = 34962
    private const val ELEMENT_ARRAY_BUFFER = 34963
    private const val FLOAT = 5126
    private const val UNSIGNED_BYTE = 5121
    private const val UNSIGNED_SHORT = 5123
    private const val TRIANGLES = 4
    private const val LINEAR = 9729
    private const val LINEAR_MIPMAP_LINEAR = 9987
    private const val CLAMP_TO_EDGE = 33071
}
