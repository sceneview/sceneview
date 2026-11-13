package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.RerunGlbFile
import io.github.sceneview.demo.demos.internal.RerunGltfBuilder
import io.github.sceneview.demo.demos.internal.RerunJson
import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RerunMeshGlb
import io.github.sceneview.demo.demos.internal.instantiate
import io.github.sceneview.demo.demos.internal.jsonOf
import io.github.sceneview.demo.demos.internal.merge
import io.github.sceneview.demo.demos.internal.model.AtlasEncoder
import io.github.sceneview.demo.demos.internal.model.ModelGlb

/**
 * A [RoomShell]'s faces as one glTF 2.0 binary: one mesh, each face its own primitive — one quad
 * per piece ([ShellFace.pieces], its UVs where the piece lies in the face's texture) — with its own
 * photo texture (JPEG), unlit (`KHR_materials_unlit`: the photos carry the room's
 * light), single-sided and wound to face into the room — so orbiting from outside, the walls
 * between the eye and the room are culled and the room reads as a dollhouse. World space, the
 * scan's own, metres, Y up.
 *
 * Accessors 0–3 are `POSITION`, `NORMAL`, `COLOR_0` (each face's mean colour) and every face's
 * indices, as [RerunMeshGlb] writes them, so the app's own readers (framing, ground, thumbnails)
 * read it as they read a model.
 *
 * With an object mesh ([RoomShell.objectMesh]), it is one more primitive, last: its own
 * `POSITION`, `NORMAL`, `COLOR_0` (linear, the vertex colours) and 32-bit indices, with a lit,
 * double-sided white material — the camera's colours carry the room's light, the shading the
 * furniture's shape, and a piece the scan saw from one side only is not culled from the other.
 */
internal object RoomShellGlb {
    /** Add fallback furniture to the shared file, centring both layers together on the floor. */
    fun withPoints(glb: ByteArray, points: DenseCloud): ByteArray {
        if (points.count == 0) return glb
        val writer = RerunGltfBuilder(null)
        val shell = checkNotNull(writer.merge(RerunGlbFile.parse(glb, "room"), "room"))
        val children = writer.instantiate(shell).toMutableList()
        writer.addDense(points)?.let { children += it }
        val (min, max) = checkNotNull(shell.bounds)
        val root = writer.addNode(
            jsonOf(
                "name" to "room", "children" to children,
                "translation" to listOf(-(min.x + max.x) / 2, -min.y, -(min.z + max.z) / 2),
            ),
        )
        return writer.glb("Room", null, root)
    }

    /** The `.glb` of [faces], `null` when [encoder] cannot encode one of their textures. */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun write(
        faces: List<ShellFace>,
        encoder: AtlasEncoder,
        generator: String = "SceneView room shell",
        objects: RerunMesh? = null,
    ): ByteArray? {
        require(faces.isNotEmpty() && faces.all { it.argb.size == it.columns * it.rows }) { "faces without textures" }
        val jpegs = faces.map { encoder.jpeg(it.argb, it.columns, it.rows) ?: return null }
        val firstQuad = IntArray(faces.size + 1).also { q -> faces.forEachIndexed { f, face -> q[f + 1] = q[f] + face.pieceCount } }
        val quads = firstQuad[faces.size]
        val n = quads * CORNERS
        require(n <= MAX_VERTICES) { "too many pieces: $quads" }
        val positions = FloatArray(n * 3)
        val normals = FloatArray(n * 3)
        val uvs = FloatArray(n * 2)
        val indices = IntArray(quads * QUAD_INDICES)
        val corner = FloatArray(3)
        faces.forEachIndexed { f, face ->
            // Counter-clockwise seen from the side the normal points to: the front face.
            val cross = cross(face.u, face.v)
            val front = cross[0] * face.normal[0] + cross[1] * face.normal[1] + cross[2] * face.normal[2] > 0f
            val order = if (front) FRONT else BACK
            for (piece in 0 until face.pieceCount) {
                val quad = firstQuad[f] + piece
                val s0 = face.pieces[piece * 4]
                val t0 = face.pieces[piece * 4 + 1]
                val s1 = face.pieces[piece * 4 + 2]
                val t1 = face.pieces[piece * 4 + 3]
                // Corners (s0, t0), (s1, t0), (s1, t1), (s0, t1) along u and v; the texture's top row is v's far end.
                for (k in 0 until CORNERS) {
                    val s = if (k == 1 || k == 2) s1 else s0
                    val t = if (k >= 2) t1 else t0
                    face.at(s, t, corner)
                    val v = quad * CORNERS + k
                    for (c in 0 until 3) {
                        positions[v * 3 + c] = corner[c]
                        normals[v * 3 + c] = face.normal[c]
                    }
                    uvs[v * 2] = s / face.lengthU
                    uvs[v * 2 + 1] = 1f - t / face.lengthV
                }
                for (k in 0 until QUAD_INDICES) indices[quad * QUAD_INDICES + k] = quad * CORNERS + order[k]
            }
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
        val mesh = objects?.takeIf { it.triangleCount > 0 }
        val meshVertices = mesh?.vertexCount ?: 0
        val meshPositionAt = next(meshVertices * 12)
        val meshNormalAt = next(meshVertices * 12)
        val meshColorAt = next(meshVertices * 4)
        val meshIndexAt = next((mesh?.indices?.size ?: 0) * 4)
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
                "bufferView" to 3, "byteOffset" to firstQuad[f] * QUAD_INDICES * 2,
                "componentType" to UNSIGNED_SHORT, "count" to faces[f].pieceCount * QUAD_INDICES, "type" to "SCALAR",
            )
        }
        val primitives = faces.indices.map { f ->
            jsonOf(
                "attributes" to jsonOf("POSITION" to 0, "NORMAL" to 1, "TEXCOORD_0" to 4),
                "indices" to FIRST_FACE_ACCESSOR + f,
                "material" to f,
                "mode" to TRIANGLES,
            )
        }.toMutableList()
        val views = mutableListOf(
            view(positionAt, positionBytes, ARRAY_BUFFER, 12),
            view(normalAt, normalBytes, ARRAY_BUFFER, 12),
            view(colorAt, colorBytes, ARRAY_BUFFER, 4),
            view(indexAt, indices.size * 2, ELEMENT_ARRAY_BUFFER, null),
            view(uvAt, uvBytes, ARRAY_BUFFER, 8),
        )
        jpegs.forEachIndexed { f, jpeg -> views += jsonOf("buffer" to 0, "byteOffset" to imageAt[f], "byteLength" to jpeg.size) }
        val materials = faces.mapIndexed { f, face ->
            jsonOf(
                "name" to face.name,
                "pbrMetallicRoughness" to jsonOf(
                    "baseColorTexture" to jsonOf("index" to f),
                    "metallicFactor" to 0.0,
                    "roughnessFactor" to 1.0,
                ),
                "extensions" to jsonOf(ModelGlb.UNLIT to jsonOf()),
            )
        }.toMutableList()
        if (mesh != null) {
            val firstView = views.size
            views += view(meshPositionAt, meshVertices * 12, ARRAY_BUFFER, 12)
            views += view(meshNormalAt, meshVertices * 12, ARRAY_BUFFER, 12)
            views += view(meshColorAt, meshVertices * 4, ARRAY_BUFFER, 4)
            views += view(meshIndexAt, mesh.indices.size * 4, ELEMENT_ARRAY_BUFFER, null)
            val b = mesh.bounds()
            val firstAccessor = accessors.size
            accessors += jsonOf(
                "bufferView" to firstView, "componentType" to FLOAT, "count" to meshVertices, "type" to "VEC3",
                "min" to listOf(b[0], b[1], b[2]).map(Float::toDouble), "max" to listOf(b[3], b[4], b[5]).map(Float::toDouble),
            )
            accessors += jsonOf("bufferView" to firstView + 1, "componentType" to FLOAT, "count" to meshVertices, "type" to "VEC3")
            accessors += jsonOf(
                "bufferView" to firstView + 2, "componentType" to UNSIGNED_BYTE, "normalized" to true,
                "count" to meshVertices, "type" to "VEC4",
            )
            accessors += jsonOf("bufferView" to firstView + 3, "componentType" to UNSIGNED_INT, "count" to mesh.indices.size, "type" to "SCALAR")
            primitives += jsonOf(
                "attributes" to jsonOf("POSITION" to firstAccessor, "NORMAL" to firstAccessor + 1, "COLOR_0" to firstAccessor + 2),
                "indices" to firstAccessor + 3,
                "material" to materials.size,
                "mode" to TRIANGLES,
            )
            materials += jsonOf(
                "name" to OBJECTS,
                "pbrMetallicRoughness" to jsonOf(
                    "baseColorFactor" to listOf(1.0, 1.0, 1.0, 1.0),
                    "metallicFactor" to 0.0,
                    "roughnessFactor" to 1.0,
                ),
                "doubleSided" to true,
            )
        }
        val json = jsonOf(
            "asset" to jsonOf("version" to "2.0", "generator" to generator),
            "extras" to jsonOf("sceneviewRoomShell" to true),
            "extensionsUsed" to listOf(ModelGlb.UNLIT),
            "scene" to 0,
            "scenes" to listOf(jsonOf("nodes" to listOf(0))),
            "nodes" to listOf(jsonOf("name" to "room", "mesh" to 0)),
            "meshes" to listOf(jsonOf("name" to "room", "primitives" to primitives)),
            "materials" to materials,
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
                repeat(CORNERS * face.pieceCount) {
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
            if (mesh != null) {
                mesh.positions.forEach { putFloat(it) }
                mesh.normals.forEach { putFloat(it) }
                for (c in mesh.colors) {
                    put(RerunMeshGlb.LINEAR[(c shr 16) and 0xFF])
                    put(RerunMeshGlb.LINEAR[(c shr 8) and 0xFF])
                    put(RerunMeshGlb.LINEAR[c and 0xFF])
                    put(0xFF.toByte())
                }
                mesh.indices.forEach { putInt(it) }
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
    private const val MAX_VERTICES = 65_536
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
    private const val UNSIGNED_INT = 5125

    /** The object mesh's material name: what a reader looks for to find the furniture. */
    const val OBJECTS = "objects"
    private const val TRIANGLES = 4
    private const val LINEAR = 9729
    private const val LINEAR_MIPMAP_LINEAR = 9987
    private const val CLAMP_TO_EDGE = 33071
}
